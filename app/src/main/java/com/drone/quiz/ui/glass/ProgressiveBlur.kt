package com.drone.quiz.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
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

/**
 * 半径封顶：核的最外抽头在 2.4·s 处，实际模糊直径≈2.4×此值。
 * v2.19.3 由 6f 收到 5f —— 抽头间距正比于半径，砍半径是压"带点"最直接的一刀。
 */
private const val MAX_GRADIENT_BLUR_DP = 5f

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
 * ## 着色器：25 抽头可分离核，不是稠密网格
 *
 * 沿用 Agora 的关键取舍（其 GradientBlur.kt 注释原话：先前版本用稠密 2D 网格
 * 且每个抽头都算 exp()，对滚动列表「太贵了」）。此处横竖两趟串联、常量高斯权重、
 * 无动态循环——每像素 50 次采样，不是几百次。
 * 且当 `s < 0.5px` 直接原样返回——过渡带以外的大半个列表几乎零成本。
 * Android 13(API 33) 起走着色器；以下机型降级为纯 alpha 渐隐（视觉接近，成本近零）。
 *
 * ## v2.19.3：抽头 9 → 25，专治"带点"（用户真机反馈"模糊是那种带点的"）
 *
 * 本文件此前的着色器与 Agora 的 `GradientBlur.kt` **逐字节相同**，照搬。
 * 但 Agora 的 9 抽头核是按 0.6s 的间距布点的：6dp 半径在 3x 密度屏上，
 * 相邻抽头相距 ~11px，而双线性滤波每次只覆盖 2px——**覆盖率不到 20%**。
 * 高频内容（细线、细字、小图标）落在抽头空隙里就拍成周期性的"点"与摩尔纹。
 *
 * Agora 没暴露这个问题，是因为它的聊天气泡是大片纯色，没有高频细节；
 * 本题的设置/错题/题库列表恰恰全是高频内容。
 *
 * 本版把间距收到 0.2s、抽头加到 25 个（±0.2s…±2.4s，σ=1.0s，真高斯权重），
 * 跨度不变而采样密度提高 3 倍；配合把最大半径从 6dp 收到 5dp，
 * 抽头间距降到 ~3px，双线性覆盖率过 60%。50 次采样/像素只发生在过渡带内，
 * 面积本来就只有全屏一小条，实测开销可接受。
 */
private val EDGE_BLUR_SHADER = """
    uniform shader content;
    uniform float uMaxBlur;    // 边缘处最大模糊（px）
    uniform float uFade;       // 斜坡长度（px）
    uniform float uH;          // 容器高度（px）
    uniform float2 uWeights;   // x = 顶边权重, y = 底边权重
    uniform float2 uOffsets;   // x = 顶部斜坡起点(px), y = 底部斜坡起点(px, 自底向上量)
    uniform float2 uDirection; // 横向趟 (1,0)，纵向趟 (0,1)

    // 累加用 float4 而非 half4：fp16 尾数只有 10 位，25 项累加后误差可见，
    // 在壁纸那种大片平缓渐变上会踩成一道道细带（banding）。
    float4 main(float2 coord) {
        if (uWeights.x <= 0.0 && uWeights.y <= 0.0) return content.eval(coord);

        // 斜坡起点可下移：悬浮顶栏并非贴屏幕顶边，若从 y=0 起爬，
        // 斜坡会落在栏体上方而不是栏体下缘，视觉上就"没贴顶"。
        float x = uOffsets.x > 0.0
            ? saturate(1.0 - (coord.y - uOffsets.x) / uFade) * uWeights.x
            : 0.0;
        float y = uOffsets.y > 0.0
            ? saturate(1.0 - ((uH - coord.y) - uOffsets.y) / uFade) * uWeights.y
            : 0.0;

        // 二次缓动而非线性：远端（x=0）导数为 0，不会在斜坡起点留下一道可见的
        // "起糊线"；贴近栏体处迅速到满，视觉上更短更集中，也更贴住用户的观感。
        // 唯一斜率不连续处正好被栏体压住，看不见。
        float s = uMaxBlur * max(x * x, y * y);
        if (s < 0.5) return content.eval(coord);

        float2 ax = uDirection * s;
        float4 acc = content.eval(coord) * 0.080780;
        acc += content.eval(coord + ax * 0.2) * 0.079180;
        acc -= content.eval(coord - ax * 0.2) * 0.079180;
        acc += content.eval(coord + ax * 0.4) * 0.074569;
        acc -= content.eval(coord - ax * 0.4) * 0.074569;
        acc += content.eval(coord + ax * 0.6) * 0.067473;
        acc -= content.eval(coord - ax * 0.6) * 0.067473;
        acc += content.eval(coord + ax * 0.8) * 0.058658;
        acc -= content.eval(coord - ax * 0.8) * 0.058658;
        acc += content.eval(coord + ax * 1.0) * 0.048996;
        acc -= content.eval(coord - ax * 1.0) * 0.048996;
        acc += content.eval(coord + ax * 1.2) * 0.039320;
        acc -= content.eval(coord - ax * 1.2) * 0.039320;
        acc += content.eval(coord + ax * 1.4) * 0.030318;
        acc -= content.eval(coord - ax * 1.4) * 0.030318;
        acc += content.eval(coord + ax * 1.6) * 0.022460;
        acc -= content.eval(coord - ax * 1.6) * 0.022460;
        acc += content.eval(coord + ax * 1.8) * 0.015986;
        acc -= content.eval(coord - ax * 1.8) * 0.015986;
        acc += content.eval(coord + ax * 2.0) * 0.010932;
        acc -= content.eval(coord - ax * 2.0) * 0.010932;
        acc += content.eval(coord + ax * 2.2) * 0.007183;
        acc -= content.eval(coord - ax * 2.2) * 0.007183;
        acc += content.eval(coord + ax * 2.4) * 0.004535;
        acc -= content.eval(coord - ax * 2.4) * 0.004535;
        return acc;
    }
""".trimIndent()

/**
 * 给「贴边滚动区」加渐进式模糊：靠 [edgeFadeDp] 的距离内从 0 爬到 [maxBlurDp]，
 * 贴边处最强。整条斜坡随容器固定，**不随内容滚动**。
 *
 * 挂在包住滚动区的 Box 上（不是挂在列表本身）：列表内部还有过冲回弹的位移层，
 * 模糊若挂在列表上会跟着内容一起漂，斜坡就失锚了。
 *
 * @param maxBlurDp 贴边处的模糊半径（内部按抽头核封顶到 5dp）
 * @param edgeFadeDp 从贴边往内的斜坡长度
 * @param topWeight 顶边权重。1 = 贴顶最糊，0 = 顶边不糊
 * @param bottomWeight 底边权重。1 = 贴底最糊，0 = 底边不糊
 * @param topRampStartDp 顶部斜坡起点（距容器顶）。悬浮顶栏内缩时必须设成栏体下缘，
 *   否则斜坡落在栏体上方，看着就像"没贴顶"
 * @param bottomRampStartDp 底部斜坡起点（距容器底），同理设成底栏上缘
 */
fun Modifier.gradientBlurEdges(
    maxBlurDp: Float,
    edgeFadeDp: Float = 43f,
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

// ============================ 悬浮底栏专用的便捷入口 ============================

/** 悬浮底栏几何（与 AppRoot 里 AnimatedVisibility 的 padding 保持一致，改动请同步） */
private val BottomBarBottomGap = 10.dp
private val BottomBarBodyHeight = 64.dp

/**
 * 各 Tab 页滚动区统一挂这个：**只**在底缘做渐进模糊。
 *
 * 顶栏（v2.19.1 用户裁定）维持历代原样——固定标题行、不悬浮，
 * 所以顶边不设模糊（`topWeight = 0f`），斜坡起点对齐悬浮底栏的**上缘**。
 *
 * 关键：斜坡起点必须挂在「包住滚动区的那一层」上。滚动容器自身有回弹位移层
 * （BounceLazyColumn / BounceContainer / verticalScroll），效果挂到它上面会跟着
 * 内容一起漂移，斜坡就失去锚点了。
 */
@Composable
fun Modifier.bottomEdgeBlur(
    maxBlurDp: Float = 5f,
    edgeFadeDp: Float = 43f
): Modifier = gradientBlurEdges(
    maxBlurDp = maxBlurDp,
    edgeFadeDp = edgeFadeDp,
    topWeight = 0f,
    bottomWeight = 1f,
    bottomRampStartDp = BottomBarBottomGap + BottomBarBodyHeight +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
)
