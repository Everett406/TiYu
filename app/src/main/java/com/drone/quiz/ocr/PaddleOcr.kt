package com.drone.quiz.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2.16.0 拍照搜题 OCR 引擎：PaddleOCR PP-OCRv4 mobile（det+rec）+ MNN 推理。
 *
 * 体积瘦身：模型不再随 APK 分发（v2.12 起的 ML Kit bundled 中文模型+引擎约 18MB），
 * 首次使用拍照搜题时由 [OcrModels] 按需下载（国内线路 ModelScope）。
 *
 * 流水线与沙箱验证脚本 scripts/ocr_validate.py 逐行同构（合成试题图 10/10 行
 * 全对、±3°/±4° 斜拍自动矫正验证通过）：
 * - det：max 边 960 内缩放（32 对齐）→ (x/255-0.5)/0.5 归一 NCHW → DB 概率图 →
 *   阈值 0.3 + 1px 膨胀 + 4 邻域连通域 → 组件得分（区域概率均值）≥0.5 →
 *   矩形 unclip（offset = 面积×1.6/周长）→ 映射回原图；
 * - 测斜：宽扁组件（宽≥3×高 且 ≥40px）PCA 主轴角取中位数，≥3 个可信行且
 *   |角度| ∈ [1.2°,12°] 时旋转矫正后重跑一遍 det（>12° 视为透视形变不硬转）；
 * - 行合并：y 占用投影分带（空隙 ≥2 行切开，防跨行链并），带内按 x 间隙
 *   (>1.2×中位行高) 拆分 → 行框（分数按面积加权）；
 * - rec：行裁剪 → 高 48 等比缩放（宽 = max(320, 48×宽高比)，右侧零填充）→
 *   (x/255-0.5)/0.5 → CTC 贪心解码（blank=0，字典 1..6623，空格=6624）→
 *   置信度 = 保留字符概率均值，<0.5 丢弃。
 */
object PaddleOcr {

    private const val DET_LIMIT = 960
    private const val DET_THRESH = 0.3f
    private const val DET_BOX_THRESH = 0.5f
    private const val DET_UNCLIP = 1.6f
    private const val REC_MIN_SCORE = 0.5f

    // ---------- 会话管理 ----------

    @Volatile private var detPtr: Long = 0L
    @Volatile private var recPtr: Long = 0L
    private var chars: List<String> = emptyList()

    private fun ensureEngine(context: Context) {
        if (detPtr != 0L && recPtr != 0L) return
        synchronized(this) {
            if (detPtr != 0L && recPtr != 0L) return
            if (chars.isEmpty()) {
                chars = context.assets.open("ocr/ppocr_keys_v1.txt").bufferedReader()
                    .readLines()
                    .let { listOf("<blank>") + it + listOf(" ") }
            }
            val d = OcrNative.nativeCreate(OcrModels.detFile(context).absolutePath)
            if (d == 0L) throw IllegalStateException("OCR 检测模型加载失败")
            val r = OcrNative.nativeCreate(OcrModels.recFile(context).absolutePath)
            if (r == 0L) {
                OcrNative.nativeClose(d)
                throw IllegalStateException("OCR 识别模型加载失败")
            }
            detPtr = d
            recPtr = r
        }
    }

    // ---------- 对外入口 ----------

    /** 识别一张已 EXIF 摆正、下采样 ≤2048 的图。返回行列表（坐标系与传入 bitmap 一致）。 */
    suspend fun recognize(context: Context, source: Bitmap): List<OcrItem> =
        withContext(Dispatchers.Default) {
            ensureEngine(context)

            // ---- 第一遍 det：原始组件 + 测斜 ----
            var bitmap = source
            var comps = detect(bitmap)
            val skew = medianSkew(comps)
            val skewAbs = kotlin.math.abs(skew)
            if (skewAbs >= 1.2f && skewAbs <= 12f) {
                // Android Matrix 正角度=视觉顺时针；PCA 角度 y 向下坐标系同号 → postRotate(-skew) 摆正
                //（>12° 视为透视形变，硬转无益不处理，与 v2.13 口径一致）
                val m = Matrix().apply { postRotate(-skew) }
                bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
                comps = detect(bitmap)
            }

            // ---- 行合并 + 逐行识别 ----
            val lines = mergeLines(comps)
            val out = ArrayList<OcrItem>(lines.size)
            for (box in lines) {
                val l = max(0, box[0]); val t = max(0, box[1])
                val r = min(bitmap.width, box[2]); val b = min(bitmap.height, box[3])
                if (r - l < 4 || b - t < 4) continue
                val crop = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
                val (text, score) = recognizeLine(crop)
                if (score >= REC_MIN_SCORE && text.isNotBlank()) {
                    out += OcrItem(text, l, t, r, b)
                }
            }
            out
        }

    // ---------- 检测 ----------

    private class Comp(
        val l: Int, val t: Int, val r: Int, val b: Int,   // 原图坐标
        val score: Float,
        val angleDeg: Float
    )

    private fun medianSkew(comps: List<Comp>): Float {
        val wide = comps.filter {
            val w = it.r - it.l; val h = it.b - it.t
            w >= 3 * h && w >= 40 && it.angleDeg != 0f
        }.map { it.angleDeg }.sorted()
        if (wide.size < 3) return 0f
        return wide[wide.size / 2]
    }

    /** det 全流程：缩放 → MNN → DB 后处理 → 组件列表（原图坐标）。 */
    private fun detect(bitmap: Bitmap): List<Comp> {
        val srcW = bitmap.width; val srcH = bitmap.height
        val scale = DET_LIMIT.toFloat() / max(srcW, srcH).coerceAtLeast(1)
        val rw = ((srcW * scale).roundToInt() / 32 * 32).coerceAtLeast(32)
        val rh = ((srcH * scale).roundToInt() / 32 * 32).coerceAtLeast(32)
        val scaled = if (rw == srcW && rh == srcH) bitmap
        else Bitmap.createScaledBitmap(bitmap, rw, rh, true)

        val pw = scaled.width; val ph = scaled.height
        val pixels = IntArray(pw * ph)
        scaled.getPixels(pixels, 0, pw, 0, 0, pw, ph)
        val input = FloatArray(3 * pw * ph)
        val n = pw * ph
        for (i in 0 until n) {
            val p = pixels[i]
            input[i] = ((p shr 16 and 0xFF) / 255f - 0.5f) / 0.5f
            input[n + i] = ((p shr 8 and 0xFF) / 255f - 0.5f) / 0.5f
            input[2 * n + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }

        val shapeOut = IntArray(4)
        val out = OcrNative.nativeRun(
            detPtr, input, intArrayOf(1, 3, ph, pw), shapeOut
        ) ?: throw IllegalStateException("OCR 检测推理失败")
        val mapH = shapeOut[2]; val mapW = shapeOut[3]
        if (mapH <= 0 || mapW <= 0) throw IllegalStateException("OCR 检测输出异常")

        return dbPostprocess(out, mapH, mapW, srcW, srcH)
    }

    /** DB 简化后处理（与验证脚本 det_postprocess 同构）。 */
    private fun dbPostprocess(
        prob: FloatArray, H: Int, W: Int, srcW: Int, srcH: Int
    ): List<Comp> {
        // 1) 阈值 + 1px 4 邻域膨胀
        val mask = BooleanArray(H * W)
        for (i in mask.indices) mask[i] = prob[i] > DET_THRESH
        val dil = BooleanArray(H * W)
        for (y in 0 until H) {
            val row = y * W
            for (x in 0 until W) {
                val i = row + x
                var v = mask[i]
                if (!v && y > 0) v = mask[i - W]
                if (!v && y < H - 1) v = mask[i + W]
                if (!v && x > 0) v = mask[i - 1]
                if (!v && x < W - 1) v = mask[i + 1]
                dil[i] = v
            }
        }
        // 2) 4 邻域连通域（迭代 BFS）
        val labels = IntArray(H * W)
        val stack = IntArray(H * W)
        val comps = ArrayList<Comp>()
        var nextLabel = 0
        val sx = srcW.toFloat() / W
        val sy = srcH.toFloat() / H
        for (start in 0 until H * W) {
            if (!dil[start] || labels[start] != 0) continue
            nextLabel += 1
            var sp = 0
            stack[sp++] = start
            labels[start] = nextLabel
            var minL = W; var maxR = -1; var minT = H; var maxB = -1
            var area = 0; var probSum = 0.0
            var sumX = 0.0; var sumY = 0.0; var sumXX = 0.0; var sumYY = 0.0; var sumXY = 0.0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % W; val y = idx / W
                area += 1
                probSum += prob[idx].toDouble()
                val fx = x.toDouble(); val fy = y.toDouble()
                sumX += fx; sumY += fy; sumXX += fx * fx; sumYY += fy * fy; sumXY += fx * fy
                if (x < minL) minL = x
                if (x > maxR) maxR = x
                if (y < minT) minT = y
                if (y > maxB) maxB = y
                if (x > 0 && dil[idx - 1] && labels[idx - 1] == 0) { labels[idx - 1] = nextLabel; stack[sp++] = idx - 1 }
                if (x < W - 1 && dil[idx + 1] && labels[idx + 1] == 0) { labels[idx + 1] = nextLabel; stack[sp++] = idx + 1 }
                if (y > 0 && dil[idx - W] && labels[idx - W] == 0) { labels[idx - W] = nextLabel; stack[sp++] = idx - W }
                if (y < H - 1 && dil[idx + W] && labels[idx + W] == 0) { labels[idx + W] = nextLabel; stack[sp++] = idx + W }
            }
            val bw = maxR - minL + 1
            val bh = maxB - minT + 1
            if (bw < 3 || bh < 3 || area < 10) continue
            val score = (probSum / area).toFloat()
            if (score < DET_BOX_THRESH) continue
            // 矩形 unclip
            val per = 2.0 * (bw + bh)
            val off = if (per > 0) area * DET_UNCLIP / per else 0.0
            val l2 = max(0.0, minL - off)
            val t2 = max(0.0, minT - off)
            val r2 = min((W - 1).toDouble(), maxR + off)
            val b2 = min((H - 1).toDouble(), maxB + off)
            // PCA 主轴角（仅宽扁组件可信）
            var angle = 0f
            if (bw >= 3 * bh && bw >= 12) {
                val meanX = sumX / area; val meanY = sumY / area
                val sxx = sumXX / area - meanX * meanX
                val syy = sumYY / area - meanY * meanY
                val sxy = sumXY / area - meanX * meanY
                angle = Math.toDegrees(0.5 * atan2(2.0 * sxy, sxx - syy)).toFloat()
            }
            comps += Comp(
                (l2 * sx).roundToInt(), (t2 * sy).roundToInt(),
                ((r2 + 1) * sx).roundToInt(), ((b2 + 1) * sy).roundToInt(),
                score, angle
            )
        }
        return comps
    }

    // ---------- 行合并（y 投影分带，与验证脚本 merge_lines 同构） ----------

    private fun mergeLines(comps: List<Comp>): List<IntArray> {
        if (comps.isEmpty()) return emptyList()
        val top = comps.minOf { it.t }
        val bottom = comps.maxOf { it.b }
        val span = bottom - top
        if (span <= 0) return emptyList()
        // y 占用剖面（空隙 ≥2 切带）
        val occ = BooleanArray(span)
        for (c in comps) {
            val y0 = (c.t - top).coerceIn(0, span)
            val y1 = (c.b - top).coerceIn(0, span)
            for (y in y0 until y1) occ[y] = true
        }
        val bands = ArrayList<IntArray>()
        var y = 0
        while (y < span) {
            if (occ[y]) {
                val y0 = y
                var gap = 0
                while (y < span && (occ[y] || gap < 2)) {
                    if (occ[y]) gap = 0 else gap += 1
                    y += 1
                }
                bands.add(intArrayOf(y0, (y - gap).coerceAtMost(span)))
            } else y += 1
        }
        val out = ArrayList<IntArray>()
        for (band in bands) {
            val inBand = comps.filter {
                val cy = (it.t + it.b) / 2.0
                cy >= top + band[0] && cy < top + band[1]
            }
            if (inBand.isEmpty()) continue
            val sorted = inBand.sortedBy { it.l }
            val medH = sorted.map { it.b - it.t }.sorted()[sorted.size / 2]
            var group = ArrayList<Comp>()
            val groups = ArrayList<List<Comp>>()
            for (c in sorted) {
                if (group.isEmpty()) { group.add(c); continue }
                if (c.l - group.last().r > 1.2 * medH) {
                    groups.add(group); group = ArrayList()
                }
                group.add(c)
            }
            if (group.isNotEmpty()) groups.add(group)
            for (g in groups) {
                val l = g.minOf { it.l }; val t = g.minOf { it.t }
                val r = g.maxOf { it.r }; val b = g.maxOf { it.b }
                out.add(intArrayOf(l, t, r, b))
            }
        }
        out.sortWith(compareBy({ it[1] }, { it[0] }))
        return out
    }

    // ---------- 识别 ----------

    /** 单行识别：高 48 等比缩放 + 零填充 + CTC 解码。返回 (文本, 置信度)。 */
    private fun recognizeLine(crop: Bitmap): Pair<String, Float> {
        val ch = crop.height
        val cw = crop.width
        val ratio = cw.toFloat() / ch.coerceAtLeast(1)
        val imgW = (48 * max(320f / 48f, ratio)).toInt()
        val resizedW = ceil(48 * ratio).toInt().coerceIn(1, imgW)
        val scaled = if (resizedW == cw && 48 == ch) crop
        else Bitmap.createScaledBitmap(crop, resizedW, 48, true)

        val input = FloatArray(3 * 48 * imgW)
        val pixels = IntArray(resizedW * 48)
        scaled.getPixels(pixels, 0, resizedW, 0, 0, resizedW, 48)
        val n = resizedW * 48
        for (i in 0 until n) {
            val p = pixels[i]
            input[i] = ((p shr 16 and 0xFF) / 255f - 0.5f) / 0.5f
            input[48 * imgW + i] = ((p shr 8 and 0xFF) / 255f - 0.5f) / 0.5f
            input[2 * 48 * imgW + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }

        val shapeOut = IntArray(4)
        val out = OcrNative.nativeRun(
            recPtr, input, intArrayOf(1, 3, 48, imgW), shapeOut
        ) ?: return "" to 0f
        // 输出形如 [T,1,C] 或 [1,T,C]（batch=1 时内存布局一致）：类别轴 = 最大维
        var cAxis = 0
        for (i in 0 until 4) if (shapeOut[i] > shapeOut[cAxis]) cAxis = i
        val C = shapeOut[cAxis]
        if (C <= 1 || chars.isEmpty()) return "" to 0f
        val others = (0 until 4).filter { it != cAxis }
        val tAxis = if (shapeOut[others[0]] >= shapeOut[others[1]]) others[0] else others[1]
        val T = shapeOut[tAxis]
        if (T <= 0) return "" to 0f

        val text = StringBuilder()
        var confSum = 0.0
        var confN = 0
        var last = 0
        for (t in 0 until T) {
            val base = t * C
            var bestC = 0; var bestP = out[base]
            for (c in 1 until C) {
                val p = out[base + c]
                if (p > bestP) { bestP = p; bestC = c }
            }
            if (bestC != 0 && bestC != last) {
                val ch0 = chars.getOrNull(bestC) ?: return "" to 0f
                text.append(ch0)
                confSum += bestP
                confN += 1
            }
            last = bestC
        }
        if (confN == 0) return "" to 0f
        return text.toString() to (confSum / confN).toFloat()
    }
}
