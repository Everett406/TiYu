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
    /**
     * 「GitHub 发布页」点击后打开的地址（v2.19.41）。
     *
     * 改指产品发布页（GitHub Pages），而不是 Releases 列表页：用户点开是想
     * "看介绍、下载、看历史版本"，而 Releases 列表页给的是一屏版本号和
     * 大段的更新日志——那是给开发者看的，不是给使用者看的。
     *
     * Pages 不可用时（首次发版前、或网络受限）自动退回 Releases 列表页。
     */
    const val SITE_URL = "https://everett406.github.io/TiYu/"
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
    /** 打开产品发布页；探测失败（还没部署 / 被墙）则退回 Releases 列表页。 */
    fun openReleases(context: Context) {
        val fallback = { fallbackLaunch(context, RELEASES_URL) }
        runCatching {
            // 先用一次 HEAD 探测 Pages 是否活着：不要 UA/IO 阻塞主线程太久，
            // 交给应用切到后台线程执行，探测中先不动 UI。
            Thread {
                val ok = runCatching {
                    val conn = URL(SITE_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.requestMethod = "HEAD"   // 只问在不在，不拉内容
                    conn.responseCode in 200..399
                }.getOrDefault(false)
                val target = if (ok) SITE_URL else RELEASES_URL
                fallbackLaunch(context, target)
            }.start()
        }.onFailure { fallback() }
    }

    private fun fallbackLaunch(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
