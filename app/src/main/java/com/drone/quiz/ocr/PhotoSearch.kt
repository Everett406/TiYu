package com.drone.quiz.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.drone.quiz.data.repo.Question
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * 拍照搜题核心逻辑（v2.13.0 重写矫正/切题/匹配）：OCR → 斜拍矫正 → 切题 → 本地题库模糊匹配，
 * 全程离线。
 *
 * OCR 引擎：ML Kit Text Recognition v2 中文 bundled 打包版（模型随 APK，
 * 运行时零 GMS 依赖，国产机可用）。引擎 API 全部封装在本文件内——后续若换
 * PaddleOCR 等引擎，只需重写 [recognizeImage] 返回同样的 [OcrItem] 列表即可，
 * 切题/匹配/UI 层零改动。
 *
 * v2.13.0 切题口径（用户反馈：一框多题连体、题号不可作为唯一判据）：
 * - 题号递增仍是主信号，但不再是唯一信号；
 * - 「题号回落重开」：当前块选项已齐（≥3）时，题号行无论号值如何都开新题——
 *   同时救两种场景：①上一题号被 OCR 错识偏大（38→88 导致后续全部连体）；
 *   ②双栏试卷左右栏号序跳变；
 * - 「漏号结构重开」：当前块选项已齐（单/多选 ≥4 个，或判断式 2 个短选项）
 *   又来一行长文本（≥12 字）——几乎必然是下一题的题干（其题号被漏识），强拆开新题；
 * - 页码/页脚行整行丢弃（进题干只会稀释匹配）。
 *
 * v2.13.0 匹配口径（用户反馈：搜不准，甚至想上 embedding）：
 * - 用户场景是「OCR 文字 vs 题库原文」的同源匹配（题就是从题库出的），字符级
 *   对齐理论即最优，语义向量化留给「措辞完全不同」的检索场景，不在此引入；
 * - 段级匹配：题干整串 + 每个原始行（≥6 字）+ 长串滑窗（40 字窗口/20 字步长）
 *   逐段对题库求分取最大——行级天然隔离连体块内不同题的文本，滑窗兜底截断；
 * - Dice 相似度为主分；包含率（|A∩B|/min）为辅（0.94×降权，仅 ≥10 字段启用，
 *   救「OCR 只截到题干一部分」），综合 = max；
 * - 兜底分数 = OCR 选项串 vs 题库选项串（×0.9 降权，防同选项干扰）；
 * - 阈值：≥0.62 命中（绿）；0.42~0.62 疑似（黄，仍配对但标注请确认）；其余未命中。
 */

/** OCR 单行结果（引擎无关 DTO：文字 + 像素坐标框）。 */
data class OcrItem(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** 题块像素框（该题所有 OCR 行的并集，UI overlay 用）。 */
data class PhotoBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** OCR 切出的一个纸质选项。 */
data class PhotoOption(val letter: Char, val text: String)

/** 从照片切出的一道题。 */
data class PhotoQuestion(
    val index: Int,            // 版面序（0-based，即第几块）
    val number: Int?,          // 试卷题号（无法识别/漏号重开时 null）
    val stem: String,          // 题干（首行去掉题号前缀，多行拼接）
    val stemLines: List<String>, // 题干原始行（v2.13.0：段级匹配用，行级隔离连体污染）
    val options: List<PhotoOption>,
    val box: PhotoBox,
    val rawText: String        // 该块全部原文（未命中时展示 + 引导文字搜索）
)

/** 一道切题的匹配结果。 */
data class PhotoMatch(
    val question: PhotoQuestion,
    val matched: Question?,    // null = 未命中（score < HIT_MIN）
    val score: Float           // 0~1，匹配置信度
) {
    /** 命中且置信度充足（绿卡）；false 且有 matched = 疑似（黄卡）；matched==null = 未命中（灰卡）。 */
    val solid: Boolean get() = matched != null && score >= HIT_SOLID
}

// ---------- 切题 ----------

/** 题号行头：数字 + 分隔符（支持中英文标点）。 */
private val questionHead = Regex("""^\s*(\d{1,3})\s*[.、．,，)）:：]""")

/** 行首选项标记：字母 + 分隔符。 */
private val optionHead = Regex("""^\s*([A-Da-d])\s*[.、．)）]""")
/** 行内后续选项标记（前面须有空白，防误切题干里的引用字母）。 */
private val optionInline = Regex("""\s+([B-Da-d])\s*[.、．)）]""")

/** 页码/页脚行：纯数字两侧可带装饰符（- 3 -、·12·、3.）或「第 x 页」式。 */
private val pageFooterNum = Regex("""^[-—–·•.\s]*\d{1,3}[-—–·•.\s]*$""")
private val pageFooterZh = Regex("""^第\s*\d{1,3}\s*页(\s*[,，/共].*)?$""")

/** 页眉/页脚（页码、页标题）行——进题干只会稀释匹配，整行丢弃（v2.13.0）。 */
private fun isPageChrome(t: String): Boolean =
    pageFooterNum.matches(t) || pageFooterZh.matches(t)

/**
 * 把 OCR 行流切成题目（v2.13.0）：
 * - 页码/页脚行整行丢弃；
 * - 题号行开新题：主条件 n 递增；当前块选项已齐时号值回落也重开（救错号/双栏）；
 * - 选项行（A. 开头，行内 B./C./D. 递增可再切）归当前题；
 * - 漏号结构重开：选项齐 + 又来长文本 → 强拆开新题（无号）；
 * - 其余行归当前题的题干；题号出现前的散行（页眉/大标题）丢弃。
 */
fun segmentQuestions(lines: List<OcrItem>): List<PhotoQuestion> {
    class Acc(var number: Int?) {
        val parts = mutableListOf<OcrItem>()     // 题干行（首行已去题号前缀）
        val options = mutableListOf<PhotoOption>()
        val raw = StringBuilder()
    }

    fun boxOf(items: List<OcrItem>): PhotoBox? {
        if (items.isEmpty()) return null
        val l = items.minOf { it.left }; val t = items.minOf { it.top }
        val r = items.maxOf { it.right }; val b = items.maxOf { it.bottom }
        return PhotoBox(l, t, r, b)
    }

    fun flush(acc: Acc?, index: Int): PhotoQuestion? {
        acc ?: return null
        val stem = acc.parts.joinToString(" ") { it.text.trim() }.trim()
        val box = boxOf(acc.parts) ?: return null
        if (stem.isEmpty() && acc.options.isEmpty()) return null
        return PhotoQuestion(
            index = index,
            number = acc.number,
            stem = stem,
            stemLines = acc.parts.map { it.text.trim() }.filter { it.isNotEmpty() },
            options = acc.options.toList(),
            box = box,
            rawText = acc.raw.toString().trim()
        )
    }

    val out = mutableListOf<PhotoQuestion>()
    var lastNumber = 0
    var cur: Acc? = null

    fun openNew(number: Int?, firstStem: OcrItem) {
        cur = Acc(number).apply {
            raw.append(firstStem.text.trim()).append('\n')
            if (firstStem.text.isNotBlank()) parts += firstStem
        }
        if (number != null) lastNumber = number
    }

    for (line in lines) {
        val t = line.text.trim()
        if (t.isEmpty()) continue
        if (isPageChrome(t)) continue

        val qm = questionHead.find(t)
        if (qm != null) {
            val n = qm.groupValues[1].toIntOrNull() ?: 0
            // 递增校验主通道：真试卷题号沿版面递增（「2024.」四位被 \d{1,3} 拦掉，
            // 「3.14」式小数 3 < 上一题号不切）。笔试卷题号 ≤500。
            // 回落重开通道：当前块选项已齐（≥3），题号行无论号值都开新题——
            // 救「题号错识偏大后全页连体」与「双栏号序跳变」，并把 lastNumber 拉回真值。
            val structuralReset = cur != null && cur.options.size >= 3
            if (n in 1..500 && (n > lastNumber || structuralReset)) {
                flush(cur, out.size)?.let { out += it }
                openNew(n, OcrItem(
                    t.substring(qm.value.length).trim(),
                    line.left, line.top, line.right, line.bottom
                ))
                continue
            }
        }

        val om = optionHead.find(t)
        if (om != null && cur != null) {
            cur.raw.append(t).append('\n')
            // 行内多选项：A.xx B.yy → [(A,xx),(B,yy)]；字母必须递增，否则整行归题干
            val marks = mutableListOf(om.range.first to om.groupValues[1][0])
            var searchFrom = om.range.last + 1
            while (true) {
                val m = optionInline.find(t, searchFrom) ?: break
                marks += (m.range.first + 1) to m.groupValues[1][0]
                searchFrom = m.range.last + 1
            }
            val asc = marks.zipWithNext().all { (a, b) -> b.second > a.second }
            if (asc) {
                marks.forEachIndexed { i, (start, letter) ->
                    val end = if (i + 1 < marks.size) marks[i + 1].first else t.length
                    val body = t.substring(start, end)
                        .replaceFirst(Regex("""^[A-Da-d]\s*[.、．)）]\s*"""), "")
                        .trim()
                    if (body.isNotEmpty()) cur.options += PhotoOption(letter, body)
                }
                continue
            }
        }

        if (cur != null) {
            // 漏号结构重开（v2.13.0）：当前块选项已齐（单/多选 ≥4 个，或判断式
            // 2 个短选项如「对/错」「正确/错误」），又来一行长文本——几乎必然是
            // 下一题的题干（题号被 OCR 漏识），强拆开新题防连体大框。
            val judgeLike = cur.options.size == 2 && cur.options.all { it.text.length <= 4 }
            val looksComplete = cur.options.size >= 4 || judgeLike
            if (looksComplete && t.length >= 12) {
                flush(cur, out.size)?.let { out += it }
                openNew(null, line)
                continue
            }
            cur.raw.append(t).append('\n')
            cur.parts += line
        }
        // cur == null：题号前的页眉/标题/章节名，不参与匹配
    }
    flush(cur, out.size)?.let { out += it }
    return out
}

/**
 * 框选区域内的 OCR 行（v2.14.0 手动框选单题）：行与区域相交面积 ≥ 行自身面积 40%
 * 视为框内行，交由 [segmentQuestions] 重切后匹配——手框兜底一切自动切题的漏题/连体。
 */
fun linesInRegion(lines: List<OcrItem>, region: PhotoBox): List<OcrItem> {
    fun ov(a1: Int, a2: Int, b1: Int, b2: Int): Float {
        val lo = maxOf(a1, b1); val hi = minOf(a2, b2)
        return if (hi <= lo) 0f else (hi - lo).toFloat()
    }
    return lines.filter { l ->
        val area = ((l.right - l.left) * (l.bottom - l.top)).coerceAtLeast(1)
        ov(l.left, l.right, region.left, region.right) *
            ov(l.top, l.bottom, region.top, region.bottom) / area >= 0.4f
    }
}

// ---------- 匹配 ----------

const val HIT_SOLID = 0.62f   // 高置信命中
const val HIT_MIN = 0.42f     // 低于此值视为未命中（0.42~0.62 疑似，仍配对）

/** NFKC 归一（全角→半角）+ 去掉所有非文字字符（标点/空白），留中文/字母/数字并转小写。 */
fun normalizeForMatch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
        .lowercase()

/** 字符 bigram 集合（长度 1 的串退化为 unigram）。 */
private fun bigrams(s: String): Set<String> =
    if (s.length < 2) setOf(s) else (0..s.length - 2).map { s.substring(it, it + 2) }.toSet()

/** 字符 bigram Dice 相似度 0~1（集合版）。 */
private fun diceOf(A: Set<String>, B: Set<String>): Float =
    if (A.isEmpty() || B.isEmpty()) 0f else 2f * A.intersect(B).size / (A.size + B.size)

/** 保留串入参版本（兼容外部调用/测试直觉）。 */
fun diceSimilarity(a: String, b: String): Float = diceOf(bigrams(a), bigrams(b))

/**
 * 包含率 |A∩B| / min(|A|,|B|)：OCR 只截到题干一部分（截断/漏行）时比 Dice 公平。
 * 只对 ≥10 字的段启用——过短段全中可能是通用题干开头（「下列关于…」）虚高。
 */
private fun containOf(A: Set<String>, B: Set<String>): Float =
    if (A.isEmpty() || B.isEmpty()) 0f else A.intersect(B).size.toFloat() / min(A.size, B.size)

/** 题干匹配段集：整串 + 每个原始行（≥6 字）+ 长串滑窗（40 字窗 / 20 字步）。 */
private fun matchSegments(p: PhotoQuestion): List<Pair<String, Set<String>>> {
    val out = mutableListOf<String>()
    val nStem = normalizeForMatch(p.stem)
    if (nStem.isNotEmpty()) out += nStem
    // 行级段：连体块内每行只属于一题，天然隔离污染（v2.13.0 主力）
    for (l in p.stemLines) {
        val n = normalizeForMatch(l)
        if (n.length >= 6) out += n
    }
    // 滑窗兜底：整串过长（连体没拆干净/行被 OCR 合并）时，总有一个窗口对准某完整题干
    if (nStem.length > 60) {
        var i = 0
        while (i + 40 <= nStem.length) { out += nStem.substring(i, i + 40); i += 20 }
    }
    return out.filter { it.length >= 6 }.map { it to bigrams(it) }
}

/**
 * 切题结果 × 本地题库匹配（v2.13.0 段级版）。综合口径见文件头注释。
 * 全部段为空（题干归一后 <6 字且无行段）只走选项兜底；题库空则全部未命中。
 */
fun matchQuestions(photos: List<PhotoQuestion>, bank: List<Question>): List<PhotoMatch> {
    if (photos.isEmpty()) return emptyList()
    data class BankEntry(
        val q: Question, val opts: String,
        val stemBi: Set<String>, val optsBi: Set<String>
    )
    val bankNorm = bank.map {
        val opts = normalizeForMatch(it.optionsOrJudge.joinToString(""))
        BankEntry(it, opts, bigrams(normalizeForMatch(it.text)), bigrams(opts))
    }
    return photos.map { p ->
        val segs = matchSegments(p)
        val nOpts = normalizeForMatch(p.options.joinToString("") { it.text })
        val optsBi = bigrams(nOpts)
        var best: Question? = null
        var bestScore = 0f
        for (b in bankNorm) {
            var s = 0f
            for ((seg, bi) in segs) {
                var v = diceOf(bi, b.stemBi)
                if (seg.length >= 10) {
                    val c = 0.94f * containOf(bi, b.stemBi)
                    if (c > v) v = c
                }
                if (v > s) s = v
            }
            if (nOpts.length >= 4) {
                val so = diceOf(optsBi, b.optsBi)
                if (0.9f * so > s) s = 0.9f * so
            }
            if (s > bestScore) { bestScore = s; best = b.q }
        }
        val matched = if (bestScore >= HIT_MIN) best else null
        PhotoMatch(p, matched, bestScore)
    }
}

// ---------- OCR 引擎封装（换引擎只动这里） ----------

/** 识别结果：底图（UI 展示/裁剪框底图，已做斜拍矫正）+ 行列表 + 实测倾斜角。 */
data class RecognizeOutcome(val bitmap: Bitmap, val lines: List<OcrItem>, val deskewDegrees: Float)

/** 单帧识别最大边长：2048 内 ML Kit 中文识别精度几乎无损，内存可控。 */
private const val MAX_DIM = 2048

/** 行基线角（度，顺时针为正）：tl→tr 向量。行宽需达行高 3 倍且 ≥40px 才可信。 */
private fun lineAngle(l: Text.Line): Float? {
    val pts = l.cornerPoints ?: return null
    if (pts.size < 4) return null
    val dx = (pts[1].x - pts[0].x).toFloat()
    val dy = (pts[1].y - pts[0].y).toFloat()
    val w = abs(dx)
    val h = abs((pts[3].y - pts[0].y).toFloat())
    if (w < h * 3f || w < 40f) return null
    return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
}

/** 全页主导倾斜角 = 各可信行基线角的中位数（少于 3 行可信视为没斜）。 */
private fun medianSkew(text: Text): Float {
    val angles = text.textBlocks.flatMap { it.lines }.mapNotNull { lineAngle(it) }
    if (angles.size < 3) return 0f
    val sorted = angles.sorted()
    return sorted[sorted.size / 2]
}

private fun flattenLines(text: Text): List<OcrItem> =
    text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
        val b = l.boundingBox ?: return@mapNotNull null
        OcrItem(l.text, b.left, b.top, b.right, b.bottom)
    }

/**
 * 识别一张图（uri 指向拍照产物或相册图）：
 * EXIF 旋转 + 下采样（最长边 ≤2048）→ 首遍识别测倾斜 → |角度| ≥1.2° 且 ≤12°
 * 时自动旋转矫正（deskew，斜拍自救）后重识别；>12° 多为透视形变，硬转无益不处理。
 */
suspend fun recognizeImage(context: Context, uri: Uri): RecognizeOutcome {
    val bitmap = decodeDownsampled(context, uri)
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    try {
        val first = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val skew = medianSkew(first)
        if (abs(skew) < 1.2f || abs(skew) > 12f) {
            return RecognizeOutcome(bitmap, flattenLines(first), if (abs(skew) > 12f) 0f else skew)
        }
        val m = Matrix().apply { postRotate(-skew) }
        val fixed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        val second = recognizer.process(InputImage.fromBitmap(fixed, 0)).await()
        return RecognizeOutcome(fixed, flattenLines(second), skew)
    } finally {
        recognizer.close()
    }
}

/** 下采样解码 + EXIF 旋转（拍照产物竖拍常见 90° 旋转，必须先摆正再识别）。 */
private fun decodeDownsampled(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = context.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: throw IllegalStateException("无法解码图片")

    val rotation = when (
        context.contentResolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (rotation == 0f) return raw
    val m = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}
