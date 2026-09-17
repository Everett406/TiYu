package com.drone.quiz.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.drone.quiz.data.repo.BankImport
import com.drone.quiz.screens.ExternalBankFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * v2.11.1：外部「其他应用打开」导入器。
 * 微信/文件管理器以 ACTION_VIEW(content://) 打开题库文件时：
 * 1. contentResolver 读流（[MAX_BYTES] 上限防 OOM，微信大文件场景兜底）；
 * 2. DISPLAY_NAME 取原始文件名（题库名默认值）；
 * 3. 内容嗅探（PK 头）路由 ZIP / CSV 解析——不信任来源 App 提供的 MIME
 *    （微信常把 zip/csv 标成 octet-stream 或 text/plain，类型推断不可靠）；
 * 4. 解析错误原样抛出（BankImport 的报错文案已面向用户）。
 */
object ExternalBankImporter {

    /** 读流上限 128MB：题库 ZIP（含图片）实际通常 <20MB，超限视为误选文件 */
    const val MAX_BYTES = 128L * 1024 * 1024

    suspend fun read(context: Context, uri: Uri): Result<ExternalBankFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readLimited(context, uri)
                val name = queryDisplayName(context, uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "外部题库"
                val isZip = bytes.size >= 2 &&
                    bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                if (isZip) {
                    val z = BankImport.parseZip(bytes)
                    ExternalBankFile(preview = z.preview, images = z.images, fileName = name)
                } else {
                    ExternalBankFile(preview = BankImport.parse(bytes), images = emptyMap(), fileName = name)
                }
            }
        }

    /** 带上限读流：超限抛错（runCatching 统一兜底为失败提示） */
    private fun readLimited(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件（来源应用未提供内容）")
        input.use { ins ->
            val buf = ByteArrayOutputStream(minOf(MAX_BYTES, 8L * 1024 * 1024).toInt())
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) {
                    throw IllegalStateException("文件过大（超过 128MB），请检查是否选错文件")
                }
                buf.write(chunk, 0, n)
            }
            if (total == 0L) throw IllegalStateException("文件为空")
            return buf.toByteArray()
        }
    }

    /** 查询 DISPLAY_NAME（provider 无名字列时回退 null → 上层兜底名） */
    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}
