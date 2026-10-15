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
 * ## 深色纹理怎么来的（v2.19.30）
 * 用户口径：**把浅色模式那张已验证的星空图反相**——白的地方变黑、黑的地方变白，
 * 再把纯白底往主题淡米色（`ink` 浅色模式那张 `#F2EBDD`）靠。
 * 结果「米色底 + 纯黑星点」，与深色模式按钮原本的浅米底深色字是同一套语言。
 *
 * ⚠️ v2.19.29 我**反错了对象**——去反相了另一张水磨石纹理，做出「黑底铜金碎屑」，
 * 深色模式下就是黑黑一坨（用户原话）。纹理始终只有两张：星空 + 星空反相，
 * 判据仍是 `ui.ink`，只是深色那张换成了反相版。
 *
 * ## 为什么要做对比拉伸
 * 纯反相后底色停在 0.93、星点停在 0.38（灰褐），不是"黑"——减 30% 那一版
 * 把最亮星点压到了 159/255，反相自然也就只到 0.38。用 1~99 分位做线性拉伸
 * 把星点区间用满，最暗的落到纯黑、底色落到纯米，才真正是"白底黑点"。
 *
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
