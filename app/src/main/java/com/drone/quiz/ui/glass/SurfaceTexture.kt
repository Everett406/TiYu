package com.drone.quiz.ui.glass

import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.drone.quiz.R
import com.drone.quiz.ui.theme.LocalUi
import kotlin.math.max

/**
 * **强调底纹理**（v2.19.28）——全 app「实心强调色」的统一底纹。
 *
 * ## 为什么按 `ui.ink` 一刀切
 * 强调底在任何主题下都是 `ui.ink`：浅色模式它是**墨黑**，深色模式它是**浅米色**。
 * 所以判据只需认 `ui.ink`，主题判断是自动的：
 * - 浅色模式 → `btn_starfield.jpg`（深底星空）
 * - 深色模式 → `btn_paper.jpg`（**深底铜金碎屑**）
 * 一行判据同时覆盖两种主题，不需要在每个调用点写 `if (ui.isDark)`。
 *
 * ## 深色纹理为什么是「反相 + 偏米」（v2.19.29）
 * 用户反馈**浅底水磨石观感太差**——碎片又碎又乱，压在浅色按钮上像脏点。
 * 于是把那张水磨石**黑白颠倒**（米白底 → 黑底，深色碎片 → 亮色碎片），
 * 再把蓝调压下去**偏成米铜色**。结果是「黑底 + 铜金碎屑」：
 * - 与深色页面（`#171412`）协调，不再是突兀的一块浅色
 * - 跟浅色模式的星空同属深底，视觉语言统一但质感不同（点状 vs 碎屑状）
 * - 按钮在深底上的轮廓靠**描边**撑住，不是靠明暗对比
 *
 * ## 覆盖到哪些组件
 * `GlassButton` 的墨色主按钮、`SegmentedRow` 的滑动指示块、`ConfigChip` / `FilterChip`
 * 的选中态、`GlassToggle` 的开启轨道——凡是「实心强调底」都走这里，视觉语言统一。
 *
 * ## 为什么自己 draw 而不是 ImageShader
 * 本项目 Compose 版本的 `ImageShader` 没有 `fitScale` / `translationX` 参数，
 * 只能横向拉伸——按钮宽度可变（`weight(1f)` 撑满 or 定宽），星点/碎片会被扯变形。
 * 这里用 nativeCanvas 手绘 Cover + 居中裁切。
 */
@Composable
fun rememberAccentTexture(): ImageBitmap? {
    val res = LocalContext.current.resources
    val ui = LocalUi.current
    val resId = if (ui.isDark) R.drawable.btn_paper else R.drawable.btn_starfield
    // 主题切换时纹理要跟着换，所以 resId 进 key
    return remember(resId) {
        runCatching { BitmapFactory.decodeResource(res, resId)?.asImageBitmap() }.getOrNull()
    }
}

/** 预设深色星空纹理（需要与主题无关时用，例如固定画深色的装饰面）。 */
@Composable
fun rememberStarfieldTexture(): ImageBitmap? {
    val res = LocalContext.current.resources
    return remember {
        runCatching {
            BitmapFactory.decodeResource(res, R.drawable.btn_starfield)?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * 把纹理**等比裁切**铺满当前区域（Cover + 居中）。
 *
 * 取 `max(w/bmpW, h/bmpH)` = Cover，放大到刚好盖住，超出部分平移掉。
 * 横向拉宽的胶囊不会把星点/碎片拉成椭圆。
 *
 * 裁剪责任在调用方（`clip(Capsule())` 之类），本函数只管铺。
 */
fun DrawScope.drawAccentTexture(tex: ImageBitmap?, alpha: Float = 1f) {
    val bmp = tex?.asAndroidBitmap() ?: return
    if (bmp.width <= 0 || bmp.height <= 0) return
    if (size.width <= 0f || size.height <= 0f) return
    val bmpW = bmp.width.toFloat()
    val bmpH = bmp.height.toFloat()
    val sc = max(size.width / bmpW, size.height / bmpH)
    val dx = (size.width - bmpW * sc) / 2f
    val dy = (size.height - bmpH * sc) / 2f
    drawIntoCanvas { canvas ->
        val m = Matrix()
        m.postScale(sc, sc)
        m.postTranslate(dx, dy)
        val p = android.graphics.Paint().apply {
            isFilterBitmap = true          // 放大后仍然平滑，不出现锯齿
            isAntiAlias = true
        }
        if (alpha < 1f) p.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.nativeCanvas.drawBitmap(bmp, m, p)
    }
}

/**
 * 给「强调底」容器挂纹理。
 *
 * 放在 `clip()` **之后**、背景/描边**之前**：clip 保证不溢出轮廓；
 * 排在描边之前才能让描边盖在纹理上（反了的话 0.75dp 的边缘高光会被纹理吃掉）。
 */
@Composable
fun Modifier.accentTexture(tex: ImageBitmap?): Modifier {
    if (tex == null) return this
    return this.drawBehind { drawAccentTexture(tex) }
}
