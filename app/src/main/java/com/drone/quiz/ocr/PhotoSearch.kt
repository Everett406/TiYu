package com.drone.quiz.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.drone.quiz.data.repo.Question
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.Normalizer

/**
 * 拍照搜题核心逻辑（v2.12.0）：OCR → 切题 → 本地题库模糊匹配，全程离线。
 *
 * OCR 引擎：ML Kit Text Recognition v2 中文 bundled 打包版（模型随 APK，
 * 运行时零 GMS 依赖，国产机可用）。引擎 API 全部封装在本文件内——后续若换
 * PaddleOCR 等引擎，只需重写 [recognizeImage] 返回同样的 [OcrItem] 列表即可，
 * 切题/匹配/UI 层零改动。
 *
 * 匹配口径（用户场景：纸质笔试卷选项常被打乱，但题干永远不打乱）：
 * - 相似度主分数 = OCR 题干 vs 题库题干（NFKC 归一 + 字符 bigram Dice）；
 * - 兜底分数 = OCR 选项串 vs 题库选项串（题干识别失败但选项识别良好的情形），
 *   综合分取 max(题干分, 0.9 × 选项分)——选项串做轻微降权防同选项干扰；
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
    val number: Int?,          // 试卷题号（无法识别时 null）
    val stem: String,          // 题干（首行去掉题号前缀，多行拼接）
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

/**
 * 把 OCR 行流切成题目：
 * - 题号行（数字 > 上一题号，递增校验防「2024.」「3.14」误切）开新题；
 * - 选项行（A. 开头，行内 B./C./D. 递增可再切）归当前题；
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
            options = acc.options.toList(),
            box = box,
            rawText = acc.raw.toString().trim()
        )
    }

    val out = mutableListOf<PhotoQuestion>()
    var lastNumber = 0
    var cur: Acc? = null
    for (line in lines) {
        val t = line.text.trim()
        if (t.isEmpty()) continue

        val qm = questionHead.find(t)
        if (qm != null) {
            val n = qm.groupValues[1].toIntOrNull() ?: 0
            // 递增校验：真试卷题号沿版面递增，可滤掉页眉年份（「2024.」当题号 202>500
            // 会被上限拦掉）与「3.14」式小数（3 < 上一题号不切）。笔试卷题号 ≤500。
            if (n in 1..500 && n > lastNumber) {
                flush(cur, out.size)?.let { out += it }
                lastNumber = n
                cur = Acc(n).apply { raw.append(t).append('\n') }
                val stemPart = OcrItem(
                    t.substring(qm.value.length).trim(),
                    line.left, line.top, line.right, line.bottom
                )
                cur.parts += stemPart
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
            cur.raw.append(t).append('\n')
            cur.parts += line
        }
        // cur == null：题号前的页眉/标题/章节名，不参与匹配
    }
    flush(cur, out.size)?.let { out += it }
    return out
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

/** 字符 bigram Dice 相似度 0~1。 */
fun diceSimilarity(a: String, b: String): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    val A = bigrams(a); val B = bigrams(b)
    return 2f * A.intersect(B).size / (A.size + B.size)
}

/**
 * 切题结果 × 本地题库匹配。score 综合口径见文件头注释。
 * 题干归一后 <6 字（信息太少）只走选项兜底；题库空则全部未命中。
 */
fun matchQuestions(photos: List<PhotoQuestion>, bank: List<Question>): List<PhotoMatch> {
    if (photos.isEmpty()) return emptyList()
    data class BankEntry(val q: Question, val stem: String, val opts: String)
    val bankNorm = bank.map {
        BankEntry(it, normalizeForMatch(it.text), normalizeForMatch(it.optionsOrJudge.joinToString("")))
    }
    return photos.map { p ->
        val nStem = normalizeForMatch(p.stem)
        val nOpts = normalizeForMatch(p.options.joinToString("") { it.text })
        var best: Question? = null
        var bestScore = 0f
        for (b in bankNorm) {
            var s = if (nStem.length >= 6) diceSimilarity(nStem, b.stem) else 0f
            if (nOpts.length >= 4) {
                val so = diceSimilarity(nOpts, b.opts)
                if (0.9f * so > s) s = 0.9f * so
            }
            if (s > bestScore) { bestScore = s; best = b.q }
        }
        val matched = if (bestScore >= HIT_MIN) best else null
        PhotoMatch(p, matched, bestScore)
    }
}

// ---------- OCR 引擎封装（换引擎只动这里） ----------

/** 识别结果：下采样后的原图（UI 展示/裁剪框底图）+ 行列表。 */
data class RecognizeOutcome(val bitmap: Bitmap, val lines: List<OcrItem>)

/** 单帧识别最大边长：2048 内 ML Kit 中文识别精度几乎无损，内存可控。 */
private const val MAX_DIM = 2048

/**
 * 识别一张图（uri 指向拍照产物或相册图）：EXIF 旋转 + 下采样（最长边 ≤2048）
 * → ML Kit 中文识别 → 按引擎返回序展开成行（引擎天然按版面从上到下，多栏不重排）。
 */
suspend fun recognizeImage(context: Context, uri: Uri): RecognizeOutcome {
    val bitmap = decodeDownsampled(context, uri)
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    try {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val b = l.boundingBox ?: return@mapNotNull null
            OcrItem(l.text, b.left, b.top, b.right, b.bottom)
        }
        return RecognizeOutcome(bitmap, lines)
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
