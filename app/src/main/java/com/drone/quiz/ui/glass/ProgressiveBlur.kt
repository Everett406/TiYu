package com.drone.quiz.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * ## v2.19.3 试过 25 抽头 + float4 + 二次缓动，v2.19.4 全部退回
 *
 * 当时的判断是：9 抽头按 0.6·s 布点，6dp 半径下抽头相距约 11px，双线性仅覆盖 2px，
 * 覆盖率不足 20%，高频内容（细线/细字/小图标）落在空隙里会拍成周期性"点"与摩尔纹。
 * 于是加密到 25 抽头（间距 0.2·s）、累加改 float4、斜坡改二次缓动 x*x。
 *
 * **真机实测：反而更乱。** 用户原话"前番修整反致崩乱，不若复归旧制，带点亦无妨"，
 * 已退回本文件的原始形态。另该轮把最大半径从 6dp 压到 5dp、斜坡从 64dp 收到 43dp，
 * 亦一并回退（仅斜坡按用户要求进一步收到 32dp）。
 *
 * ## v2.19.4 算力账与优化（同一轮一并处理）
 *
 * 效果挂在包住滚动区的整块 Box 上，故每帧都要对**整个视口**跑两趟着色器。
 * 以 1080×2400、过渡带 32dp、密度 3 估算（视口 ≈2.5M 像素，过渡带 ≈0.1M）：
 *
 *   真正做活的 18 次采样：0.1M × 18 ≈ 1.9M 次采样
 *   过渡带外的早退路径：2.4M × 2 趟 × 1 次 ≈ 5.0M 次采样
 *
 * 即 **约七成的纹理取样花在「什么也不做」的区域**——那些像素走 `s < 0.5`
 * 早退原样返回。这是本架构的固有成本：想只模糊那一条带，就得把内容画两遍，
 * 而内容是懒加载列表，同一个 `listState` 被两个 LazyColumn 共用会导致
 * `layoutInfo` 互相覆盖，**不安全**，故不做。
 *
 * 在此前提下，砍掉的是三处**纯浪费**（都不改变观感）：
 *
 * 1. **每帧分配 RenderEffect**：`renderEffect = RenderEffect.createChainEffect(...)`
 *    原先写在 `graphicsLayer` 的块里，而该块每帧执行——等于每帧 new 两个
 *    RuntimeShaderEffect + 一个链 + 一个 Compose 包装。改为 `remember` 一次建好，
 *    块里只赋引用。
 * 2. **冗余的 `CompositingStrategy.Offscreen`**：`renderEffect` 本身已强制离屏渲染，
 *    再叠一层 compositing layer 就是多一整块全屏缓冲加一次 blit，每帧一次。
 *    着色器路径已移除；API 31/32 的 `DstIn` 降级路径**必须**保留（否则会裁到父层）。
 * 3. **无条件挂载的 `drawWithContent`**：它在 API 33+ 上什么都不做，但绘制节点
 *    照样挂。改为仅在降级路径挂载。
 *
 * 另：容器高度原为 `remember` 的 key，旋转/改尺寸会重建两个着色器对象；
 * 现改为每帧就地 `setFloatUniform("uH", size.height)`，一次 native 调用换掉整轮重建。
 *
 * **教训**：这类观感问题，"理论上更正确"与"看起来更好"经常不是一回事。
 * 采样覆盖率是纸面推导，收敛是否可见、边缘是否生硬只能真机判。
 * **不要再重复这个实验**——除非有真机可验证，否则不要动抽头数与累加精度。
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
    edgeFadeDp: Float = 32f,
    topWeight: Float = 1f,
    bottomWeight: Float = 1f,
    topRampStartDp: Dp = 0.dp,
    bottomRampStartDp: Dp = 0.dp
): Modifier = composed {
    val tw = topWeight.coerceIn(0f, 1f)
    val bw = bottomWeight.coerceIn(0f, 1f)
    if (maxBlurDp <= 0f || (tw <= 0f && bw <= 0f)) return@composed this

    val density = LocalDensity.current.density
    val maxBlurPx = maxBlurDp.coerceAtMost(MAX_GRADIENT_BLUR_DP) * density
    val fadePx = edgeFadeDp * density
    val topOffPx = topRampStartDp.value * density
    val botOffPx = bottomRampStartDp.value * density

    // v2.19.4 算力优化：着色器与 RenderEffect 对象**只建一次**。
    // 此前 renderEffect = RenderEffect.createChainEffect(...) 写在 graphicsLayer 的
    // 块里——那个块每帧都跑，于是每帧都要 new 两个 RuntimeShaderEffect + 一个
    // createChainEffect + 一个 Compose 包装。全是纯分配，纯浪费。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxBlurPx > 0f) {
        val horizontal = remember(maxBlurPx, fadePx, tw, bw, topOffPx, botOffPx) {
            RuntimeShader(EDGE_BLUR_SHADER).apply {
                setFloatUniform("uMaxBlur", maxBlurPx)
                setFloatUniform("uFade", fadePx)
                setFloatUniform("uH", 0f)
                setFloatUniform("uWeights", tw, bw)
                setFloatUniform("uOffsets", topOffPx, botOffPx)
                setFloatUniform("uDirection", 1f, 0f)
            }
        }
        val vertical = remember(maxBlurPx, fadePx, tw, bw, topOffPx, botOffPx) {
            RuntimeShader(EDGE_BLUR_SHADER).apply {
                setFloatUniform("uMaxBlur", maxBlurPx)
                setFloatUniform("uFade", fadePx)
                setFloatUniform("uH", 0f)
                setFloatUniform("uWeights", tw, bw)
                setFloatUniform("uOffsets", topOffPx, botOffPx)
                setFloatUniform("uDirection", 0f, 1f)
            }
        }
        // 容器高度不再走 remember key（那会让旋转/改尺寸时重建两个着色器），
        // 改为每帧就地写入 uniform：一次 native 调用，换掉整轮对象重建。
        val effect = remember(horizontal, vertical) {
            RenderEffect
                .createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(vertical, "content"),
                    RenderEffect.createRuntimeShaderEffect(horizontal, "content")
                )
                .asComposeRenderEffect()
        }
        Modifier.graphicsLayer {
            vertical.setFloatUniform("uH", size.height)
            horizontal.setFloatUniform("uH", size.height)
            renderEffect = effect
        }
        // 这里**不**再挂 CompositingStrategy.Offscreen：renderEffect 本身已强制
        // 离屏渲染，再叠一层 compositing layer 等于多一整块全屏缓冲 + 一次 blit。
        // 该层是 v2.19.3 之前加的冗余项，每帧白白多搬一次全屏。
    } else {
        // API 31/32 无着色器：退化为纯 alpha 渐隐。观感接近，成本近零。
        // 这条路径用 BlendMode.DstIn，**必须**有离屏层，否则会连带把父层内容一起裁掉，
        // 故上面的 Offscreen 冗余项在这里保留。
        val fadeFinal = fadePx
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val h = size.height.coerceAtLeast(1f)
                val norm = (fadeFinal / h).coerceIn(0f, 0.5f)
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
    maxBlurDp: Float = 6f,
    edgeFadeDp: Float = 32f
): Modifier = gradientBlurEdges(
    maxBlurDp = maxBlurDp,
    edgeFadeDp = edgeFadeDp,
    topWeight = 0f,
    bottomWeight = 1f,
    bottomRampStartDp = BottomBarBottomGap + BottomBarBodyHeight +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
)
