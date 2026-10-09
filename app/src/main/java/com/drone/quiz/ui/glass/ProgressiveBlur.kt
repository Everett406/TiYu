package com.drone.quiz.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 半径封顶：9 抽头核的最外抽头在 2.4·s 处，实际模糊直径≈2.4×此值。 */
private const val MAX_GRADIENT_BLUR_DP = 6f

/**
 * v2.19.0 渐进式模糊 —— 第四次实现，方案来自 Agora（newo-ether/Agora）的 GradientBlur。
 *
 * ## 前三次都错在哪
 *
 * ① v2.6.0~v2.7.2 / ② v2.15.0 / ③ v2.17.0：三次全部把模糊做成「贴在栏体上的**背景采样**层」
 *    （alpha 蒙版 → 自写可变半径着色器 → 分层固定半径叠加）。这条路在本题库的结构下
 *    必炸：内容层本身在记录层内，屏内采样受限；叠加层越多，裁剪边界越多，
 *    接缝与闪烁随之而来（v2.17.0 真机满屏横线即如此）。
 *
 * ## 这一版换的根本思路
 *
 * **不做背景采样，改成模糊「滚动内容自己」**——把可变半径 RenderEffect 直接挂在
 * 包住滚动区的 Box 上（`graphicsLayer { renderEffect }`）。于是：
 *
 *   · 全程只有一个离屏层、一条裁剪边界，接缝无处可生；
 *   · 栏体不是模糊的输入，而是画在这层之上的独立玻璃件，永远锐利；
 *   · 不需要第三个记录层，也不需要任何 backdrop 采样，绕开了 v2.1.0 那条
 *     「记录层内禁用采样」的架构红线。
 *
 * ## 着色器：9 抽头可分离核，不是稠密网格
 *
 * 沿用 Agora 的关键取舍（其 GradientBlur.kt 注释原话：先前版本用稠密 2D 网格
 * 且每个抽头都算 exp()，对滚动列表「太贵了」）。此处横竖两趟串联，每趟 9 个 texel、
 * 常量高斯权重、无动态循环——每像素 18 次采样，不是几百次。
 *
 * 半径随到边缘的距离线性爬升：`s = uMaxBlur · max(顶权重, 底权重)`，
 * 且当 `s < 0.5px` 直接原样返回——过渡带以外的大半个列表几乎零成本。
 * Android 13(API 33) 起走着色器；以下机型降级为纯 alpha 渐隐（视觉接近，成本近零）。
 */
private val EDGE_BLUR_SHADER = """
    uniform shader content;
    uniform float uMaxBlur;    // 边缘处最大模糊（px）
    uniform float uFade;       // 斜坡长度（px）
    uniform float uH;          // 容器高度（px）
    uniform float2 uWeights;   // x = 顶边权重, y = 底边权重
    uniform float2 uOffsets;   // x = 顶部斜坡起点(px), y = 底部斜坡起点(px, 自底向上量)
    uniform float2 uDirection; // 横向趟 (1,0)，纵向趟 (0,1)

    half4 main(float2 coord) {
        if (uWeights.x <= 0.0 && uWeights.y <= 0.0) return content.eval(coord);

        // 斜坡起点可下移：悬浮顶栏并非贴屏幕顶边，若从 y=0 起爬，
        // 斜坡会落在栏体上方而不是栏体下缘，视觉上就"没贴顶"。
        float t = uOffsets.x > 0.0
            ? saturate(1.0 - (coord.y - uOffsets.x) / uFade) * uWeights.x
            : 0.0;
        float b = uOffsets.y > 0.0
            ? saturate(1.0 - ((uH - coord.y) - uOffsets.y) / uFade) * uWeights.y
            : 0.0;
        float s = uMaxBlur * max(t, b);
        if (s < 0.5) return content.eval(coord);

        float2 axis = uDirection * s;
        half4 accum = half4(content.eval(coord)) * 0.24084130;
        accum += half4(content.eval(coord + axis * 0.6)) * 0.20116756;
        accum += half4(content.eval(coord - axis * 0.6)) * 0.20116756;
        accum += half4(content.eval(coord + axis * 1.2)) * 0.11723004;
        accum += half4(content.eval(coord - axis * 1.2)) * 0.11723004;
        accum += half4(content.eval(coord + axis * 1.8)) * 0.04766218;
        accum += half4(content.eval(coord - axis * 1.8)) * 0.04766218;
        accum += half4(content.eval(coord + axis * 2.4)) * 0.01351957;
        accum += half4(content.eval(coord - axis * 2.4)) * 0.01351957;
        return accum;
    }
""".trimIndent()

/**
 * 给「贴边滚动区」加渐进式模糊：靠 [edgeFadeDp] 的距离内从 0 爬到 [maxBlurDp]，
 * 贴边处最强。整条斜坡随容器固定，**不随内容滚动**。
 *
 * 挂在包住滚动区的 Box 上（不是挂在列表本身）：列表内部还有过冲回弹的位移层，
 * 模糊若挂在列表上会跟着内容一起漂，斜坡就失锚了。
 *
 * @param maxBlurDp 贴边处的模糊半径（内部按抽头核封顶到 6dp）
 * @param edgeFadeDp 从贴边往内的斜坡长度
 * @param topWeight 顶边权重。1 = 贴顶最糊，0 = 顶边不糊
 * @param bottomWeight 底边权重。1 = 贴底最糊，0 = 底边不糊
 * @param topRampStartDp 顶部斜坡起点（距容器顶）。悬浮顶栏内缩时必须设成栏体下缘，
 *   否则斜坡落在栏体上方，看着就像"没贴顶"
 * @param bottomRampStartDp 底部斜坡起点（距容器底），同理设成底栏上缘
 */
fun Modifier.gradientBlurEdges(
    maxBlurDp: Float,
    edgeFadeDp: Float = 56f,
    topWeight: Float = 1f,
    bottomWeight: Float = 1f,
    topRampStartDp: Dp = 0.dp,
    bottomRampStartDp: Dp = 0.dp
): Modifier = composed {
    val tw = topWeight.coerceIn(0f, 1f)
    val bw = bottomWeight.coerceIn(0f, 1f)
    if (maxBlurDp <= 0f || (tw <= 0f && bw <= 0f)) return@composed this
    val topOffPx = topRampStartDp.value * LocalDensity.current.density
    val botOffPx = bottomRampStartDp.value * LocalDensity.current.density

    val density = LocalDensity.current.density
    val maxBlurPx = maxBlurDp.coerceAtMost(MAX_GRADIENT_BLUR_DP) * density
    val fadePx = edgeFadeDp * density
    var composableHPx by remember { mutableFloatStateOf(0f) }

    val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxBlurPx > 0f) {
        val horizontal = remember(maxBlurPx, fadePx, tw, bw, topOffPx, botOffPx, composableHPx) {
            RuntimeShader(EDGE_BLUR_SHADER).apply {
                setFloatUniform("uMaxBlur", maxBlurPx)
                setFloatUniform("uFade", fadePx)
                setFloatUniform("uH", composableHPx)
                setFloatUniform("uWeights", tw, bw)
                setFloatUniform("uOffsets", topOffPx, botOffPx)
                setFloatUniform("uDirection", 1f, 0f)
            }
        }
        val vertical = remember(maxBlurPx, fadePx, tw, bw, topOffPx, botOffPx, composableHPx) {
            RuntimeShader(EDGE_BLUR_SHADER).apply {
                setFloatUniform("uMaxBlur", maxBlurPx)
                setFloatUniform("uFade", fadePx)
                setFloatUniform("uH", composableHPx)
                setFloatUniform("uWeights", tw, bw)
                setFloatUniform("uOffsets", topOffPx, botOffPx)
                setFloatUniform("uDirection", 0f, 1f)
            }
        }
        Modifier
            .onSizeChanged { composableHPx = it.height.toFloat() }
            .graphicsLayer {
                renderEffect = RenderEffect
                    .createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(vertical, "content"),
                        RenderEffect.createRuntimeShaderEffect(horizontal, "content")
                    )
                    .asComposeRenderEffect()
            }
    } else {
        // API 31/32 无着色器：退化为纯 alpha 渐隐。观感接近，成本近零
        Modifier
    }

    val fadePxFinal = fadePx
    layer
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || maxBlurPx <= 0f) {
                val h = size.height.coerceAtLeast(1f)
                val norm = (fadePxFinal / h).coerceIn(0f, 0.5f)
                val opaque = Color.Black
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to opaque.copy(alpha = 1f - tw),
                        norm to opaque,
                        1f - norm to opaque,
                        1f to opaque.copy(alpha = 1f - bw)
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
}
