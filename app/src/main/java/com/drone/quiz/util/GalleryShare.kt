package com.drone.quiz.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 系统分享（v2.8.2）：CSV 模板导出改走标准分享面板（可发微信 / 文件传输助手 / 邮件等），
 * 比固定保存到「下载/题屿」对零基础用户更友好（用户反馈）。
 *
 * 实现：先写入 cacheDir/share/，经 FileProvider（只读授权）发出 ACTION_SEND chooser。
 */
object GalleryShare {

    /** @return true = 已拉起分享面板 */
    fun shareTextFile(context: Context, fileName: String, content: String): Boolean =
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.files", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "题屿 CSV 题库模板（按列填好题目后，可在题屿 App 导入）"
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    "这是「题屿」APP 的题库 CSV 模板：把学习内容按列整理好（支持单选/多选/判断/填空/简答），" +
                        "然后在题屿 → 设置 → 导入题库 选择这个文件即可变成你的专属题库。"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享 CSV 题库模板")
            )
            true
        }.getOrDefault(false)

    /**
     * 图片分享（v2.10.0 成绩分享卡）：bitmap 写 cache/share/ 后经 FileProvider
     * 拉起系统分享面板（微信/朋友圈/文件传输助手等）。@return true = 已拉起
     */
    fun sharePngImage(
        context: Context,
        bitmap: android.graphics.Bitmap,
        fileName: String,
        title: String,
        text: String
    ): Boolean = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
        true
    }.getOrDefault(false)
}
