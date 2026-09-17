package com.drone.quiz.ocr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * v2.16.0 OCR 模型按需下载（体积瘦身：模型不随 APK 分发，首次使用拍照搜题时下载）。
 *
 * 来源：RapidAI/RapidOCR 官方模型仓（ModelScope 托管，阿里 CDN，国内直连快且稳；
 * 同仓两种 URL 形态互为备份）。文件落地 filesDir/ocr/，下载完成做字节数 + SHA-256
 * 双校验，损坏自动重下。
 *
 * det=PP-OCRv4 mobile 检测 4.5MB；rec=PP-OCRv4 mobile 识别 10.3MB。不下载方向分类器
 * （cls）：试卷照片方向由 EXIF/桌面摆正，竖排/倒置场景不在拍照搜题工作流内。
 */
object OcrModels {

    private const val DET_NAME = "ch_PP-OCRv4_det_mobile.mnn"
    private const val REC_NAME = "ch_PP-OCRv4_rec_mobile.mnn"
    private const val DET_SIZE = 4736528L
    private const val REC_SIZE = 10851516L

    const val TOTAL_BYTES: Long = DET_SIZE + REC_SIZE

    private const val BASE_API =
        "https://modelscope.cn/api/v1/models/RapidAI/RapidOCR/repo?Revision=master&FilePath="
    private const val BASE_RESOLVE =
        "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/"

    /** SHA-256（沙箱内逐字节校验过的同仓同版本文件）。 */
    private const val DET_SHA = "c46fbae33f0520460204c0321bfdfcf5d65c9a11b7fa750e6b86de69ef6ace34"
    private const val REC_SHA = "8f5855347b2900ff1a8fb897bad548d7c4013f050239adccf145485accb976e5"

    private class ModelSpec(val name: String, val size: Long, val sha: String, val repoPath: String)

    private val DET = ModelSpec(DET_NAME, DET_SIZE, DET_SHA, "mnn/PP-OCRv4/det/$DET_NAME")
    private val REC = ModelSpec(REC_NAME, REC_SIZE, REC_SHA, "mnn/PP-OCRv4/rec/$REC_NAME")

    /** 下载进度（fileIndex 从 0；bytes/total 为当前文件字节）。 */
    data class Progress(val fileIndex: Int, val fileCount: Int, val bytes: Long, val total: Long)

    class ModelDownloadException(message: String) : Exception(message)

    private fun dir(context: Context): File = File(context.filesDir, "ocr").apply { mkdirs() }

    fun detFile(context: Context): File = File(dir(context), DET_NAME)
    fun recFile(context: Context): File = File(dir(context), REC_NAME)

    /** 模型是否已就绪（存在且字节数一致——完整校验在下载时做，这里走快路径）。 */
    fun isReady(context: Context): Boolean =
        detFile(context).length() == DET_SIZE && recFile(context).length() == REC_SIZE

    /**
     * 确保模型可用；缺失/损坏时下载。已在 IO 线程池执行。
     * @param onProgress 下载进度回调（仅下载期间触发）
     * @throws ModelDownloadException 所有线路失败时（调用方据此给可重试错误态）
     */
    suspend fun ensure(context: Context, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val specs = listOf(DET, REC)
        for ((index, spec) in specs.withIndex()) {
            currentCoroutineContext().ensureActive()
            val target = File(dir(context), spec.name)
            if (target.length() == spec.size && sha256Of(target) == spec.sha) continue
            val tmp = File(dir(context), spec.name + ".tmp")
            tmp.delete(); target.delete()
            var lastError: Exception? = null
            val urls = listOf(BASE_API + spec.repoPath, BASE_RESOLVE + spec.repoPath)
            var ok = false
            for (attempt in 0 until 2) {
                currentCoroutineContext().ensureActive()
                for (url in urls) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val jobContext = currentCoroutineContext()
                        download(url, tmp, checkAbort = {
                            if (!jobContext.isActive) throw CancellationException("模型下载已取消")
                        }) { done, total ->
                            onProgress(Progress(index, specs.size, done, total))
                        }
                        if (tmp.length() != spec.size) throw ModelDownloadException("模型文件不完整")
                        val sha = sha256Of(tmp)
                        if (sha != spec.sha) throw ModelDownloadException("模型校验失败")
                        if (!tmp.renameTo(target)) {
                            // 个别 ROM rename 跨句柄失败：复制兜底
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        ok = true
                        break
                    } catch (e: Exception) {
                        tmp.delete()
                        if (e is CancellationException) throw e
                        lastError = e
                    }
                }
                if (ok) break
            }
            if (!ok) {
                throw ModelDownloadException(
                    lastError?.message ?: "模型下载失败，请检查网络后重试"
                )
            }
        }
    }

    private fun download(url: String, target: File, checkAbort: () -> Unit, onProgress: (Long, Long) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TiYu-App/2.16")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw ModelDownloadException("下载线路响应 $code")
            val total = conn.contentLengthLong
            target.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastNotify = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done - lastNotify >= 256 * 1024) {
                            lastNotify = done
                            checkAbort()   // 协作式取消点（256KB 粒度）
                            if (total > 0) onProgress(done, total)
                        }
                    }
                    if (total > 0) onProgress(total, total)
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
