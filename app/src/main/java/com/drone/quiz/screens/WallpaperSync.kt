package com.drone.quiz.screens

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 读取系统当前壁纸并落盘，供「全局壁纸」使用。
 *
 * ## 为什么需要「所有文件访问权限」
 *
 * Android **从 13（API 33）起不再允许应用读取用户设置的壁纸**：
 * `WallpaperManager.getDrawable()` 在 13 上返回的是**系统默认壁纸**而非用户那张，
 * 在 14 上则直接抛 `SecurityException`。官方文档写明：
 * 「Apps with `android.Manifest.permission.MANAGE_EXTERNAL_STORAGE` can still access
 * the real wallpaper on all versions.」
 * （来源：Android 官方 WallpaperManager API 参考，2026-10 查证）
 *
 * 所以本项目走的是官方给出的唯一出路：声明 `MANAGE_EXTERNAL_STORAGE`，
 * 并在**用户主动点「同步系统壁纸」时**才引导去系统设置开启——
 * 不主动申请、不静默索取。
 */
// v2.19.21：**当前没有任何入口调用本文件**（二级面板已整体撤回），清单也不再声明
// MANAGE_EXTERNAL_STORAGE，故 sync() 在 Android 13+ 必然返回 NeedAllFilesAccess。
// 代码整份保留是因为平台限制这段来之不易：日后若要接回「同步系统壁纸」，
// 直接在设置页加一个入口 + 清单里补回权限声明即可，不必重新踩一遍 Android 13/14 的坑。
// 若长期不再启用，可连同本文件一并删除。
object WallpaperSync {

    sealed interface Result2 {
        /** 成功，文件已落到私有目录 */
        data class Ok(val file: File) : Result2
        /** 未授予「所有文件访问权限」，无法读取（Android 13+） */
        data object NeedAllFilesAccess : Result2
        /** 读取失败（无壁纸服务 / 动态壁纸无静态图 / 权限异常） */
        data class Failed(val reason: String) : Result2
    }

    fun hasAllFilesAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                Environment.isExternalStorageManager()

    /** 跳到本应用的「所有文件访问权限」设置页 */
    fun openAllFilesAccessSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun sync(context: Context): Result2 = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess(context)) return@withContext Result2.NeedAllFilesAccess
        runCatching {
            val wm = context.getSystemService(WallpaperManager::class.java)
                ?: error("设备不支持壁纸服务")
            // 14+ 直接抛 SecurityException；13 返回的是默认壁纸（不是用户那张）
            val drawable = wm.drawable ?: error("系统未返回壁纸图")
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) error("壁纸尺寸异常")
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.draw(canvas)
            val dst = File(context.filesDir, "wall_${System.currentTimeMillis()}.jpg")
            dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            Result2.Ok(dst)
        }.getOrElse { Result2.Failed(it.message ?: "读取失败") }
    }
}
