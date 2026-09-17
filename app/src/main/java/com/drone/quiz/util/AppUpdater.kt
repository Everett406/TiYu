package com.drone.quiz.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 更新检查（v2.8.8，用户口径）：
 * 不做自动下载 / 静默安装——只查最新 Release 的版本号；
 * 有新版 → 弹窗告知，引导用户用浏览器打开发布页自行下载；
 * 无新版 / 网络失败 → 轻提示。
 *
 * 仓库 v2.8.8 起由 DroneQuiz 更名为 TiYu（GitHub 仓库名不支持纯中文，
 * 「题屿」取拼音；GitHub 对旧 DroneQuiz 链接自动 301 重定向，不会 404）。
 */
object AppUpdater {

    /** 最新版查询端点（releases/latest：仅取正式发布，不含 pre-release） */
    private const val LATEST_API = "https://api.github.com/repos/Everett406/TiYu/releases/latest"

    /** Releases 页面（浏览器打开入口；手动浏览/下载安装包都到这里） */
    const val RELEASES_URL = "https://github.com/Everett406/TiYu/releases"

    /**
     * 拉取最新 Release 版本号（tag_name 去掉前导 v，如 "2.8.8"）。
     * 网络失败 / 限流（HTTP 403）抛异常，由调用方 runCatching 兜底。
     */
    suspend fun fetchLatestVersion(): String = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TiYu-App") // GitHub API 要求带 UA
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").trim().removePrefix("v").ifBlank { error("空版本号") }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * remote 是否比 current 新：按数字段逐段比较（"2.8.10" > "2.8.9"），
     * 段数不齐补 0，非数字段按 0 处理；相等视为不新。
     */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.split('.').map { seg -> seg.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 用浏览器打开 Releases 页（用户设备无浏览器时静默失败）。 */
    fun openReleases(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
