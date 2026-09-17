package com.drone.quiz.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 题目图片存储（v2.8.5）：
 * - 落盘位置：filesDir/bank_images/<bankId>/<文件名>，随题库删除一并清理；
 * - 文件名来自 ZIP 内的原始文件名（去路径、替换非法字符、重名加序号），
 *   CSV「图片」列按文件名（忽略大小写）引用；
 * - 解码走 BitmapFactory + inSampleSize 降采样（最长边 ≤ load() 的 maxDim），
 *   LruCache 按字节缓存——题目图片多为图纸/示意照片，避免大图反复解码。
 *
 * 支持格式（本地解析口径）：jpg / jpeg / png / webp / gif（取首帧）/ bmp。
 * Agent 整理口径建议优先 jpg / png / webp（见 AgentPrompts）。
 */
object QuestionImages {

    private const val ROOT = "bank_images"

    /** ZIP 内允许作为题目图片的扩展名（小写）。 */
    val ALLOWED_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun bankDir(context: Context, bankId: String): File =
        File(File(context.filesDir, ROOT), bankId)

    /** CSV 引用的文件名 → 落盘后的绝对路径。 */
    fun resolve(context: Context, bankId: String, fileName: String): String =
        File(bankDir(context, bankId), fileName).absolutePath

    /**
     * 把 ZIP 解出的图片字节写入题库目录；fileName 会先经过 sanitize，
     * 与同批次其他文件重名时自动追加 (2)/(3)… 返回最终落盘的文件名。
     */
    fun saveBankImage(
        context: Context,
        bankId: String,
        fileName: String,
        bytes: ByteArray,
        takenNames: MutableSet<String> = mutableSetOf()
    ): String {
        val dir = bankDir(context, bankId)
        if (!dir.exists()) dir.mkdirs()
        val base = sanitize(fileName)
        var candidate = base
        var n = 2
        while (File(dir, candidate).exists() || candidate.lowercase() in takenNames.map { it.lowercase() }) {
            val dot = base.lastIndexOf('.')
            candidate = if (dot > 0) "${base.substring(0, dot)}($n)${base.substring(dot)}" else "$base($n)"
            n++
        }
        File(dir, candidate).writeBytes(bytes)
        takenNames.add(candidate)
        return candidate
    }

    /** 删除整个题库的图片目录（题库删除时调用）。 */
    fun deleteBank(context: Context, bankId: String) {
        bankDir(context, bankId).deleteRecursively()
    }

    /** 解码题目图片（IO 线程）；最长边压到 maxDim，命中缓存直接返回。 */
    suspend fun load(path: String, maxDim: Int = 1440): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(path)?.let { return@withContext it }
        val file = File(path)
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        cache.put(path, bmp)
        bmp
    }

    /** 去目录、替换文件系统非法字符、去首尾空白；空结果兜底为 "img"。 */
    fun sanitize(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").trim()
        return name.ifBlank { "img" }
    }
}
