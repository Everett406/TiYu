package com.drone.quiz.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/**
 * 亚克力（Acrylic）表面材质——果冻模式的表面定义（任务口径）：
 * - 沿用 Kyant0 backdrop 管线，但只留模糊、去掉折射：
 *   drawBackdrop + effects = { blur(8dp) }，无 vibrancy / lens；
 * - 表面半透明：亮色 White α≈0.6 / 暗色对应主题色 α≈0.45（由调用方经 surfaceColor 传入，
 *   与玻璃模式共用 ui.surface 体系，保证主题协调）；
 * - 配柔和阴影 + 0.75dp 细描边；
 * - 模糊半径参数化，默认 8dp（明显低于玻璃模式的 18dp）。
 *
 * 层级体系：玻璃（GlassRuntime.mode==0，真折射）→ 果冻（mode==1，亚克力）→
 * 安全平涂（mode==2，[glassMaterial]，崩溃兜底）。
 * 本材质只在 mode==1 的分支中被调用；mode==2 永远走 glassMaterial，绝不采样 backdrop。
 */
@Composable
fun Modifier.acrylicMaterial(
    backdrop: Backdrop,
    shape: Shape,
    blurDp: Dp = 8.dp,
    surfaceColor: Color? = null,
    elevated: Boolean = false
): Modifier {
    val ui = LocalUi.current
    return this
        .shadow(
            elevation = (if (elevated) 8 else 4).dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (ui.isDark) 0.30f else 0.06f),
            spotColor = Color.Black.copy(alpha = if (ui.isDark) 0.42f else 0.10f)
        )
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            // 只模糊：无 vibrancy、无 lens——亚克力与玻璃的本质区别
            effects = { blur(blurDp.toPx()) },
            onDrawSurface = {
                if (surfaceColor != null) drawRect(surfaceColor)
            }
        )
        .border(
            width = 0.75.dp,
            brush = if (ui.isDark) Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))
            ) else Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.28f))
            ),
            shape = shape
        )
}

/**
 * 非 Composable 环境可用的描边笔刷（glass() modifier 的果冻分支使用，
 * 该分支无法读 Composable LocalUi，经 GlassRuntime.isDark 镜像取主题明暗）。
 */
internal fun acrylicStrokeBrush(isDark: Boolean): Brush = if (isDark) Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))
) else Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.28f))
)
