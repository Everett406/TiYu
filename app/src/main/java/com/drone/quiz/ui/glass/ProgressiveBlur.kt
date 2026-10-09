package com.drone.quiz.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRenderEffectSupported

/**
 * v2.17.0 渐进式模糊（Progressive Blur）—— 顶栏 / 底栏下沿的「内容溶入栏体」效果。
 *
 * ## 为什么不用连续可变半径
 * 本效果此前栽过两次跟头，两条老路都被证伪，这里把教训固化成实现约束：
 *
 *  ① v2.6.0~v2.7.2：alpha 蒙版 / 雾条 / saveLayer 盖在滚动容器上
 *     → 与玻璃卡的离屏渲染互作，伪影闪烁，五版迭代后砍除；
 *  ② v2.15.0：业务层自写着色器做连续可变半径（双 pass 链式 RenderEffect）
 *     → 每像素大量采样吃 GPU、仅 API 33+ 可用、且与透明内容层的预乘 alpha 相冲，
 *       v2.16.0 上架后经用户实测整体撤除。
 *
 * **本实现走第三条路：不做可变半径，用若干层「固定半径」的背景模糊叠出斜坡。**
 * 每层都是一次 `drawBackdrop { blur(r) }`，即 `RenderEffect.createBlurEffect` 硬件路径——
 * Android 12(API 31) 起可用，无自研 shader、无 per-pixel 采样循环、无额外离屏层嵌套。
 * 视觉上半径按层递增/递减，配合每层自己的 alpha 渐变遮罩，过渡连续、看不出分层。
 * （这与 Figma 的实现思路一致：业界做「渐进模糊」同样是用分层固定半径堆出来的，
 *  而不是逐像素算半径。）
 *
 * ## 分层数学
 *  设过渡带高 H、分层数 N，第 i 层（i 从贴边那层起算）占 y ∈ [i·H/N, (i+1)·H/N]：
 *   - 半径   rᵢ = maxRadius · (N-i) / N      贴边最强，向内线性衰减
 *   - 不透明度 αᵢ = 1 - i/N（层内线性插值到 αᵢ₊₁）
 * 相邻两层在交界处共用同一个 α，故整条过渡带的 α 是连续分段线性、全程无接缝；
 * 半径在交界处有台阶，但该处两侧 α 相同、且模糊内容同源，台阶不可辨。
 *
 * ## 采样正确性
 * 每层的节点尺寸 = **整条过渡带**（不是该层自己那一小段），靠 `shape` 把可见区域
 * 裁到本层。这样模糊的采样窗口覆盖整条带，不会出现「在窄条里做大半径模糊」
 * 导致的边界钳制发虚；带外的顶栏/底栏区域本来就不该出现在效果里。
 */
enum class ProgressiveEdge { Top, Bottom }

/** 把本层可见区域裁到过渡带内的一段（节点本身仍是整条带，保证采样窗口完整）。 */
private class BandShape(
    private val top: Float,
    private val bottom: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(
        // 直接走 Path 手绘，绕开 Rect 构造器（本工程 Compose 版本上不可用）
        Path().apply {
            val b = bottom.coerceAtLeast(top + 1f)
            moveTo(0f, top)
            lineTo(size.width, top)
            lineTo(size.width, b)
            lineTo(0f, b)
            close()
        }
    )
}

/**
 * 一条贴边的渐进式模糊过渡带。叠在滚动内容之上、栏体之下（或之内），
 * 让内容随靠近栏体逐渐糊掉，形成参考图中「溶入玻璃」的手感。
 *
 * 纯视觉层：不消费任何手势（无 pointerInput），滚动与点击照常穿透。
 *
 * @param edge 贴哪条边：Top = 顶栏下沿（越靠上越糊），Bottom = 底栏上沿（越靠下越糊）
 * @param height 过渡带高度
 * @param maxRadius 贴边处的最大模糊半径
 * @param bands 分层数。3~4 足够；再往上肉眼难辨，只白费 GPU
 */
@Composable
fun ProgressiveEdge(
    backdrop: Backdrop,
    edge: ProgressiveEdge,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    maxRadius: Dp = 22.dp,
    bands: Int = 3
) {
    // 画面特效关闭（安全模式 / 用户手动关）时不叠玻璃，交给调用方决定是否退化为纯色纱
    if (!GlassRuntime.enabled || !isRenderEffectSupported()) return
    if (bands < 1) return

    val ui = LocalUi.current
    val n = bands.coerceIn(1, 6)
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { height.toPx() }
    val maxPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxRadius.toPx() }
    val bandH = edgePx / n

    Box(modifier.height(height)) {
        for (i in 0 until n) {
            // 贴边那层（i=0）在 Top 时位于带顶；Bottom 时位于带底
            val bandTop = if (edge == ProgressiveEdge.Top) i * bandH else edgePx - (i + 1) * bandH
            val radius = maxPx * (n - i) / n
            // 层内 alpha：贴边侧 aᵢ，向内插值到 aᵢ₊₁（相邻层共用同值 → 连续无接缝）
            val aOut = 1f - i / n.toFloat()
            val aIn = 1f - (i + 1) / n.toFloat()
            val gTop = if (edge == ProgressiveEdge.Top) aOut else aIn
            val gBottom = if (edge == ProgressiveEdge.Top) aIn else aOut

            Box(
                Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { BandShape(bandTop, bandTop + bandH) },
                        effects = { blur(radius) },
                        onDrawSurface = {
                            // DstIn 把本层自身的不透明度按带内线性渐变压出来，
                            // 消除层与层之间的接缝；drawBackdrop 节点已是 Offscreen 复合层，
                            // 遮罩只作用于本层采样结果，不影响栏体与滚动内容。
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = gTop),
                                    1f to Color.Black.copy(alpha = gBottom)
                                ),
                                blendMode = BlendMode.DstIn
                            )
                            // 极轻的同色纱，抑制高对比内容在糊边上的「跳变感」
                            drawRect(
                                color = if (ui.isDark)
                                    Color(0xFF141110).copy(alpha = 0.10f)
                                else
                                    Color(0xFFF6F1E6).copy(alpha = 0.10f)
                            )
                        }
                    )
            )
        }
    }
}
