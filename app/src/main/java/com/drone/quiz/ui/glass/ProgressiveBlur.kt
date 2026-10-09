package com.drone.quiz.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRenderEffectSupported

/**
 * v2.18.0 渐进式模糊（Progressive Blur）—— 顶栏 / 底栏下沿的「内容溶入栏体」效果。
 *
 * ## 三次尝试的结论（前两次都被真机实测打回，这里把教训写死）
 *
 * ① v2.6.0~v2.7.2：alpha 蒙版 / 雾条 / saveLayer 盖在滚动容器上
 *     → 与玻璃卡的离屏渲染互作，伪影闪烁，五版迭代后砍除。
 * ② v2.15.0：业务层自写着色器做**连续可变半径**（双 pass 链式 RenderEffect）
 *     → 每像素大量采样吃 GPU、仅 API 33+ 可用、且与透明内容层的预乘 alpha 相冲，
 *       v2.16.0 整体撤除。
 * ③ v2.17.0：**分层「固定半径」叠加**（3 层，各自 shape 裁到本层 + 层内渐变 alpha）
 *     → 真机实测**满屏横向接缝 + 底栏被糊穿**，v2.18.0 推翻重写。
 *
 * ## v2.17.0 那版错在哪（两条，都是自找的）
 *
 * ① **在 onDrawSurface 里画了实色矩形当"纱"**——drawBackdrop 节点是 Offscreen 复合层，
 *    在层内画一个全节点尺寸的实色矩形，它的上下边界就是两条硬边；三层叠起来正好六条
 *    横贯全屏的接缝。**层内绝不能画有硬边界的实心形状**。
 * ② **三层各自用 shape 裁到本层**——每层的裁剪边界都落在过渡带内部，
 *    即使 alpha 连续，裁剪本身也断开了采样窗口，接缝照样可见。
 *
 * ## v2.18.0 的做法：单节点、单次模糊、只有渐变 alpha
 *
 * 一个节点铺满整条过渡带，一次固定半径模糊，shape 保持整块矩形不切分，
 * 可见性完全由带内的垂直渐变 alpha（DstIn）压出来。
 * 于是：整条带只有**一个**裁剪边界（贴着栏体、被栏体盖住），
 * 带内不存在任何裁剪接缝，也只跑一次背景采样。
 *
 * 视觉上，靠近栏体处几乎全是模糊副本，越往内模糊副本占比越低、直至原图——
 * 这正是 Figma「渐进模糊」的标准实现（uniform blur + alpha gradient crossfade），
 * 与逐像素算半径的连续可变半径相比，跨层差异在 56dp 的带上肉眼难辨，
 * 却省掉了 per-pixel 采样、N 层离屏合成与全部接缝风险。
 *
 * 曲线沿用项目既有口径 smoothstep 五采样（Common.kt 的 fadeMaskBrush）：
 * 线性渐变的"被幕布切"观感主要来自两端斜率突变，smoothstep 消除之。
 */
enum class ProgressiveEdge { Top, Bottom }

/** 边缘渐隐曲线：smoothstep 五采样，贴栏体端 1.0 → 带内侧 0.0（Top 反向）。 */
private fun edgeFadeBrush(edge: ProgressiveEdge): Brush {
    fun stop(pos: Float, a: Float) =
        pos to Color.Black.copy(alpha = if (edge == ProgressiveEdge.Top) a else 1f - a)
    return Brush.verticalGradient(
        stop(0f, 1f),
        stop(0.25f, 0.84f),
        stop(0.5f, 0.5f),
        stop(0.75f, 0.16f),
        stop(1f, 0f)
    )
}

/**
 * 一条贴边的渐进式模糊过渡带。叠在滚动内容之上、栏体之下，
 * 让内容随靠近栏体逐渐糊掉，形成参考图中「溶入玻璃」的手感。
 *
 * 纯视觉层：不消费任何手势（无 pointerInput），滚动与点击照常穿透。
 *
 * @param edge 贴哪条边：Top = 顶栏下沿（越靠上越糊），Bottom = 底栏上沿（越靠下越糊）
 * @param height 过渡带高度
 * @param maxRadius 最大模糊半径。整条带共用这一个半径，靠 alpha 渐变完成过渡
 */
@Composable
fun ProgressiveEdge(
    backdrop: Backdrop,
    edge: ProgressiveEdge,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    maxRadius: Dp = 20.dp
) {
    // 画面特效关闭（安全模式 / 用户手动关）时不叠玻璃
    if (!GlassRuntime.enabled || !isRenderEffectSupported()) return

    val density = LocalDensity.current
    val radiusPx = with(density) { maxRadius.toPx() }
    if (radiusPx <= 0f) return

    Box(modifier.height(height)) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    // shape 保持整块矩形：带内不做任何切分，杜绝裁剪接缝
                    shape = { RectangleShape },
                    effects = { blur(radiusPx) },
                    onDrawSurface = {
                        // 只用渐变 alpha 羽化整条带——这是层内唯一允许画的形状，
                        // 它没有内部边界，不会产生任何横贯全屏的接缝。
                        drawRect(brush = edgeFadeBrush(edge), blendMode = BlendMode.DstIn)
                    }
                )
        )
    }
}
