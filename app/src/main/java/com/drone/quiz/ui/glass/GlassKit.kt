package com.drone.quiz.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 全局画面特效状态（三级体系）。
 * - mode==0 液态玻璃：Kyant0 backdrop 真折射（vibrancy+blur+lens），历史渲染路径一字未改；
 * - mode==1 亚克力（v2.11.2 起，原果冻）：正常亚克力模糊表面（只模糊无折射），
 *   无任何 goo 动效——关闭特效 = 干净的毛玻璃质感；
 * - mode==2 安全平涂：无任何 RenderEffect 的半透明材质（[glassMaterial]），仅崩溃兑底。
 *
 * 决策点在 MainActivity：autoSafeMode 无条件置 2（崩溃兑底，独立于用户设置）；
 * 用户开关 settings.effects 仅在非崩溃时区分 0（开=玻璃）/1（关=亚克力）。切换即时生效。
 */
object GlassRuntime {
    const val MODE_GLASS = 0
    const val MODE_ACRYLIC = 1
    const val MODE_SAFE = 2

    var mode by mutableStateOf(MODE_GLASS)

    /** 主题明暗镜像：供非 Composable 上下文（glass() modifier 工厂）的果冻分支取色；由 DroneTheme 同步。 */
    var isDark by mutableStateOf(false)

    /** 兼容旧分叉点：true = 玻璃模式。渲染路径与历史版本完全一致。 */
    val enabled: Boolean get() = mode == MODE_GLASS
}

/**
 * 系统开启「减弱动画」（动画时长缩放 = 0）时：动效降级为直达/瞬时。
 * 读取一次即可（该设置变更会重建 Activity 组合，不做监听）。
 * v2.11.2 自已删除的果冻模式源文件挪入（goo 动效已除，无障碍降级保留）。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * 内容流真折射玻璃（折射页面背景层 backdrop）。
 *
 * 架构约定（对齐 Kyant0 官方 demo）：
 * - AppRoot 把"背景渐变"单独记录进 bgBackdrop，内容流玻璃元素折射它；
 *   元素自身位于内容记录层内，但 bgBackdrop 不包含元素 → 无循环采样，无 SIGSEGV 风险。
 * - 安全模式降级：clip + 实色，无任何 RenderEffect。
 */
fun Modifier.glass(
    backdrop: Backdrop,
    shape: Shape,
    blurDp: Dp = 16.dp,
    lensHeightDp: Dp = 18.dp,
    lensAmountDp: Dp = 24.dp,
    surfaceColor: Color? = null,
    depth: Boolean = false,
    acrylicBlurDp: Dp = 8.dp
): Modifier = if (!GlassRuntime.enabled) {
    if (GlassRuntime.mode == GlassRuntime.MODE_ACRYLIC) {
        // 亚克力（关闭特效）：毛玻璃表面（沿用 backdrop 管线，只模糊、无 vibrancy/lens）+ 细描边柔和阴影
        val dark = GlassRuntime.isDark
        Modifier
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.06f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.10f)
            )
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { blur(acrylicBlurDp.toPx()) },
                onDrawSurface = {
                    if (surfaceColor != null) drawRect(surfaceColor)
                }
            )
            .border(0.75.dp, acrylicStrokeBrush(dark), shape)
    } else {
        // 安全平涂（崩溃兑底，原样保留）
        Modifier
            .clip(shape)
            .then(if (surfaceColor != null) Modifier.background(surfaceColor) else Modifier)
    }
} else {
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurDp.toPx())
            lens(lensHeightDp.toPx(), lensAmountDp.toPx(), depthEffect = depth)
        },
        onDrawSurface = {
            if (surfaceColor != null) drawRect(surfaceColor)
        }
    )
}

/**
 * 质感材质（安全模式 / 特效关闭时的降级形态）：
 * 半透明渐变表面 + 顶部高光描边 + 轻阴影，无任何 backdrop 采样。
 */
@Composable
fun Modifier.glassMaterial(shape: Shape, elevated: Boolean = false): Modifier {
    val ui = LocalUi.current
    return this
        .shadow(
            elevation = (if (elevated) 10 else 5).dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (ui.isDark) 0.30f else 0.06f),
            spotColor = Color.Black.copy(alpha = if (ui.isDark) 0.42f else 0.10f)
        )
        .clip(shape)
        .background(
            if (ui.isDark) Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.07f))
            ) else Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.82f), Color.White.copy(alpha = 0.56f))
            )
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
 * 玻璃卡片容器（内容流）。特效开启时为真折射玻璃（官方 LazyScrollContainer 同款），
 * 安全模式退化为质感材质。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    surfaceAlpha: Float = 0.5f,
    refracts: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(cornerRadius)
    val clickMod = if (onClick != null) Modifier.clickable(
        interactionSource = null,
        indication = null,
        onClick = onClick
    ) else Modifier
    val surfaceMod = if (refracts && GlassRuntime.enabled) {
        Modifier.glass(
            backdrop = backdrop,
            shape = shape,
            blurDp = 18.dp,
            lensHeightDp = 14.dp,
            lensAmountDp = 20.dp,
            surfaceColor = ui.surface.copy(alpha = surfaceAlpha)
        )
    } else if (refracts && GlassRuntime.mode == GlassRuntime.MODE_ACRYLIC) {
        // 亚克力（关闭特效）：毛玻璃表面（只模糊 8dp，无折射），表面半透明度沿用调用方语义
        Modifier.acrylicMaterial(
            backdrop = backdrop,
            shape = shape,
            blurDp = 8.dp,
            surfaceColor = ui.surface.copy(alpha = surfaceAlpha)
        )
    } else {
        Modifier.glassMaterial(shape)
    }
    Box(
        modifier
            .then(surfaceMod)
            .then(clickMod),
        content = content
    )
}

/**
 * 玻璃按钮（官方 LiquidButton 同款实现）：
 * 按压折射增强 + 轻微膨胀 + 跟手位移；安全模式退化为质感胶囊。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    heightDp: Dp = 50.dp,
    isInteractive: Boolean = true,
    refracts: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val ui = LocalUi.current

    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "btnPressScale"
    )

    val glassActive = refracts && GlassRuntime.enabled
    val acrylicActive = !glassActive && GlassRuntime.mode == GlassRuntime.MODE_ACRYLIC

    val containerModifier = if (!glassActive) {
        if (acrylicActive) {
            // 亚克力模式：毛玻璃胶囊（只模糊无折射）+ 细描边 + 按压缩放，无任何 goo 动效
            modifier
                .graphicsLayer {
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                }
                .shadow(5.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = 0.12f))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { blur(8f.dp.toPx()) },
                    onDrawSurface = {
                        if (surfaceColor != Color.Unspecified) drawRect(surfaceColor)
                        else drawRect(if (ui.isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.72f))
                    }
                )
                .border(
                    0.75.dp,
                    if (ui.isDark) Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f))
                    ) else Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.30f))
                    ),
                    RoundedCornerShape(50)
                )
                .clickable(
                    interactionSource = pressSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                )
                .height(heightDp)
                .padding(horizontal = 18.dp)
        } else {
        // 材质模式（安全模式）：质感胶囊，保留按压缩放
        modifier
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .shadow(5.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(50))
            .background(
                if (surfaceColor != Color.Unspecified) surfaceColor
                else if (ui.isDark) Color.White.copy(alpha = 0.14f)
                else Color.White.copy(alpha = 0.72f)
            )
            .border(
                0.75.dp,
                if (ui.isDark) Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f))
                ) else Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.30f))
                ),
                RoundedCornerShape(50)
            )
            .clickable(
                interactionSource = pressSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .height(heightDp)
            .padding(horizontal = 18.dp)
        }
    } else {
        // 真折射模式：官方 LiquidButton 逐行对齐（Capsule + 按压折射 + 跟手位移）
        modifier
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scale +
                            maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                            (width / height).fastCoerceAtMost(1f)
                        scaleY = scale +
                            maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                            (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint != Color.Unspecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor != Color.Unspecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                interactionSource = pressSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(heightDp)
            .padding(horizontal = 18.dp)
    }

    // 亚克力/安全模式：内容直接躺在容器胶囊上（毛玻璃表面已含在 containerModifier）；
    // 真折射模式：LiquidButton 内容同款布局
    Row(
        containerModifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 圆形玻璃图标按钮。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 46.dp,
    iconSize: Dp = 21.dp,
    iconTint: Color = Color.Unspecified,
    refracts: Boolean = true
) {
    val ui = LocalUi.current
    Box(modifier) {
        GlassButton(
            onClick = onClick,
            backdrop = backdrop,
            heightDp = sizeDp,
            refracts = refracts
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (iconTint != Color.Unspecified) iconTint else ui.text,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * 可拖动玻璃滑杆（官方 LiquidSlider 同款实现 + 步进吸附）。
 * 轨道与填充记录进局部 trackBackdrop，滑块折射"背景 + 轨道"呈现 iOS 26 质感；
 * 安全模式退化为实色滑块。
 */
@Composable
fun GlassSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float = 0f,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    refracts: Boolean = true
) {
    val ui = LocalUi.current
    val glassActive = refracts && GlassRuntime.enabled
    val trackColor =
        if (!ui.isDark) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val accentColor = ui.ink

    val density = LocalDensity.current
    val blurPx = with(density) { 8.dp.toPx() }
    val lens1 = with(density) { 10.dp.toPx() }
    val lens2 = with(density) { 14.dp.toPx() }
    val trackBackdrop = rememberLayerBackdrop()

    fun snap(v: Float): Float =
        if (step > 0f) {
            val k = ((v - valueRange.start) / step).let { kotlin.math.floor(it + 0.5f) }
            (valueRange.start + k * step).coerceIn(valueRange)
        } else v.coerceIn(valueRange)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val trackWidth = constraints.maxWidth.toFloat()

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // 拖拽进行中标志：暂停“外部值 → 滑块”同步循环。
        // 否则进度类滑块（onValueChange 回写页面位置）会与拖拽互相拉扯（橡皮筋），拖不动
        var isDragging by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = 0.01f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }
        // v2.8.4 修复：LaunchedEffect(key=动画器) 闭包捕获的是首帧的 value lambda——
        // 若调用方传捕获局部 val 的 lambda（如题型配比 { r.toFloat() }），外部改值后
        // snapshotFlow 永远读旧值 → 联动滑杆只变数值不动位置（用户反馈）。
        // rememberUpdatedState 让同步循环每次都读到最新 lambda。
        val currentValue by rememberUpdatedState(value)
        androidx.compose.runtime.LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { v ->
                    if (!isDragging && abs(dampedDragAnimation.targetValue - v) > 0.001f) {
                        dampedDragAnimation.updateValue(v)
                    }
                }
        }

        fun valueAtX(x: Float): Float {
            val fraction = (x / trackWidth).fastCoerceIn(0f, 1f)
            val span = valueRange.endInclusive - valueRange.start
            return if (isLtr) valueRange.start + span * fraction
            else valueRange.endInclusive - span * fraction
        }

        // 轨道区：40dp 高的触控容器，6dp 轨道 + 填充条严格垂直居中。
        // 统一手势：按下即跳到该位置，按住左右拖动实时跟随。
        // 每个事件均消费（consume），父级 verticalScroll/横向翻页无法再抢走手势——
        // 修复配置页/设置页内滑块“只能点、不能拖”的问题
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(valueRange, step, isLtr) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isDragging = true
                        dampedDragAnimation.press()
                        val downValue = snap(valueAtX(down.position.x))
                        dampedDragAnimation.updateValue(downValue)
                        onValueChange(downValue)
                        var pointer = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change =
                                event.changes.fastFirstOrNull { it.id == pointer } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                break
                            }
                            if (change.isConsumed) break // 被其他手势接管：结束本次拖拽
                            if (change.positionChange() != Offset.Zero) {
                                val v = snap(valueAtX(change.position.x))
                                dampedDragAnimation.updateValue(v)
                                onValueChange(v)
                                change.consume()
                            }
                        }
                        // 松手：吸附到最终值并回调一次，再恢复同步循环
                        val finalValue = snap(dampedDragAnimation.targetValue)
                        dampedDragAnimation.updateValue(finalValue)
                        onValueChange(finalValue)
                        isDragging = false
                        dampedDragAnimation.release()
                    }
                }
                .then(if (glassActive) Modifier.layerBackdrop(trackBackdrop) else Modifier),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(Capsule())
                    .background(trackColor)
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6.dp)
                    .layout { measurable: Measurable, constraints: Constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        // 滑块：40x24，与轨道同一容器垂直居中（纯视觉；手势统一在轨道区处理）
        val thumbBase = Modifier
            .align(Alignment.CenterStart)
            .graphicsLayer {
                translationX =
                    (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                        .coerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                        (if (isLtr) 1f else -1f)
            }

        if (!glassActive) {
            // 亚克力/安全模式：实色滑块 + 轻阴影，无 RenderEffect（原样保留）
            Box(
                thumbBase
                    .shadow(4.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = 0.22f))
                    .clip(RoundedCornerShape(50))
                    .background(if (ui.isDark) Color(0xFFF2EBDD) else Color.White)
                    .size(40.dp, 24.dp)
            )
        } else {
            Box(
                thumbBase
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            backdrop,
                            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                val progress = dampedDragAnimation.pressProgress
                                val scaleX = lerp(2f / 3f, 1f, progress)
                                val scaleY = lerp(0f, 1f, progress)
                                scale(scaleX, scaleY) {
                                    drawBackdrop()
                                }
                            }
                        ),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            blur(blurPx * (1f - progress))
                            lens(
                                lens1 * progress,
                                lens2 * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f))
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(radius = 4.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                Color.White.copy(
                                    alpha = (1f - progress) * if (ui.isDark) 0.25f else 0.9f
                                )
                            )
                        }
                    )
                    .size(40.dp, 24.dp)
            )
        }
    }
}

/**
 * 可拖动玻璃开关（官方 LiquidToggle 同款实现）。
 * 轨道记录进局部 trackBackdrop，玻璃圆钮折射"背景 + 轨道"；
 * 安全模式退化为实色圆钮。点击轨道任意位置或拖动圆钮均可切换。
 */
@Composable
fun GlassToggle(
    checked: () -> Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val ui = LocalUi.current
    val glassActive = GlassRuntime.enabled
    val accentColor = ui.ink
    val trackBaseColor =
        if (!ui.isDark) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked()) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    onCheckedChange(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (checked()) 0f else 1f
                    onCheckedChange(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { f ->
                dampedDragAnimation.updateValue(f)
            }
    }
    LaunchedEffect(checked) {
        snapshotFlow { checked() }
            .collectLatest { isChecked ->
                val target = if (isChecked) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val trackBackdrop = rememberLayerBackdrop()

    // 注意：不能在外层加 clickable —— inspectDragGestures 不消费 down/up，
    // 外层 clickable 会与圆钮手势双触发。官方 LiquidToggle 同样只依赖圆钮手势。
    Box(
        modifier.size(64f.dp, 28f.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // 轨道：颜色随 fraction 从灰过渡到墨色
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    drawRect(lerp(trackBaseColor, accentColor, dampedDragAnimation.value))
                }
                .size(64f.dp, 28f.dp)
        )

        // 圆钮：玻璃模式折射"背景 + 轨道"；材质模式实色
        val thumbBase = Modifier
            .graphicsLayer {
                val f = dampedDragAnimation.value
                val pad = 2f.dp.toPx()
                translationX =
                    if (isLtr) lerp(pad, pad + dragWidth, f)
                    else lerp(-pad, -(pad + dragWidth), f)
            }
            .then(dampedDragAnimation.modifier)

        if (!glassActive) {
            // 亚克力/安全模式：实色圆钮 + 轻阴影（无 goo 拉伸融合，v2.11.2 起）
            Box(
                thumbBase
                    .shadow(2.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = 0.2f))
                    .clip(RoundedCornerShape(50))
                    .drawBehind {
                        // 深色下开启态轨道为奶色，钮身同步转深保持对比
                        // （此前白钮贴奶轨几乎看不出差异）；浅色维持白钮
                        drawRect(
                            if (ui.isDark) lerp(Color(0xFFF2EBDD), Color(0xFF2B2620), dampedDragAnimation.value)
                            else Color.White
                        )
                    }
                    .size(40f.dp, 24f.dp)
            )
        } else {
            Box(
                thumbBase
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            backdrop,
                            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                val progress = dampedDragAnimation.pressProgress
                                val scaleX = lerp(2f / 3f, 0.75f, progress)
                                val scaleY = lerp(0f, 0.75f, progress)
                                scale(scaleX, scaleY) {
                                    drawBackdrop()
                                }
                            }
                        ),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            lens(
                                5f.dp.toPx() * progress,
                                10f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(
                                radius = 4f.dp,
                                color = Color.Black.copy(alpha = 0.05f)
                            )
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 4f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 50f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            // 深色开启态轨道为奶色，玻璃钮面叠深色保持对比；浅色维持白面
                            if (ui.isDark) {
                                drawRect(
                                    lerp(Color(0xFFF2EBDD), Color(0xFF2B2620), dampedDragAnimation.value)
                                        .copy(alpha = 0.88f)
                                )
                            } else {
                                drawRect(Color.White.copy(alpha = 1f - progress))
                            }
                        }
                    )
                    .size(40f.dp, 24f.dp)
            )
        }
    }
}
