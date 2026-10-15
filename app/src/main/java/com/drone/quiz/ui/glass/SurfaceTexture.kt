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
 * ## 深色纹理：**用户直接提供的贴图，别再加工**
 *
 * 这张图前后试过三个版本（v2.19.29~31），全是我的问题：
 * - v2.19.29 **反错了对象**——去反相了另一张水磨石图，做出黑底铜金碎屑，深色下一坨黑；
 * - v2.19.30 按「把星空反相 + 底色靠米 + 对比拉伸」做，米底黑点，用户嫌效果不好；
 * - **v2.19.31 起直接用用户新提供的贴图**，原样贴，**不再做任何反相 / 偏色 / 对比拉伸**。
 *
 * 教训：贴图是设计的一部分，不是可以随手调的参数。用户给了图就直接用。
 * 唯一保留的处理是 resize 到 256×256（按钮只有 ~50dp 高，原图 8149×4584 不缩会让
 * APK 暴涨；resize 不改变纹理本身的观感）。
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
