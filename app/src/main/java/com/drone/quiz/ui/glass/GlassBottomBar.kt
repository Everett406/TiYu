package com.drone.quiz.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.ui.glass.rememberReducedMotion
import com.drone.quiz.ui.theme.LocalUi
import androidx.compose.runtime.collectAsState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS 26 风浮动玻璃胶囊底栏（真液态玻璃）。
 *
 * 结构（源自 Kyant0 官方 LiquidBottomTabs）：
 * 1. 底层玻璃胶囊（blur + lens 折射背后内容）
 * 2. 隐藏的内容副本（染色，供选中项"透过"玻璃显示强调色）
 * 3. 移动的玻璃选中块（refract 背后 + 隐藏层 = 图标被染色呈现），支持拖动切换
 */
@Composable
fun GlassBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    tabIcon: @Composable RowScope.(index: Int) -> Unit
) {
    val ui = LocalUi.current
    val accentColor = ui.ink // 选中项透玻璃呈现墨色（浅色主题）/奶油色（深色主题）
    val containerColor = ui.surface.copy(alpha = if (ui.isDark) 0.4f else 0.35f)

    // 玻璃模糊度三档（设置-外观可调）：低 4dp / 中 8dp / 高 14dp
    val glassSettings by ServiceLocator.settings.settings.collectAsState(initial = AppSettings())
    val barBlurDp = when (glassSettings.glassBlur) { 0 -> 4.dp; 2 -> 14.dp; else -> 8.dp }

    // 关闭特效（亚克力）/崩溃兑底（平涂）：无折射管线，保留选中胶囊与切换
    if (!GlassRuntime.enabled) {
        if (GlassRuntime.mode == GlassRuntime.MODE_ACRYLIC) {
            // 亚克力模式（v2.11.2，原果冻）：亚克力底座 + 普通 spring 跟随选中胶囊。
            // 无 goo 融合拖尾：胶囊本身快 spring 追到选中位，图标层永远锐利。
            val reduced = rememberReducedMotion()
            val density = LocalDensity.current
            // glassBlur 三档 → 亚克力模糊 dp（低 4/中 8/高 14 由 barBlurDp 统一口径）
            val acrylicBlurPx = barBlurDp.let { with(density) { it.toPx() } }
            val acrylicSurface = ui.surface.copy(alpha = if (ui.isDark) 0.45f else 0.6f)

            val selectedProvider = rememberUpdatedState(selectedTabIndex)
            val accentAnim = remember { Animatable(selectedTabIndex().toFloat()) }
            // 主胶囊：快 spring 直达（减弱动画时瞬时定位）
            LaunchedEffect(Unit) {
                snapshotFlow { selectedProvider.value() }.drop(1).collectLatest { idx ->
                    if (reduced) accentAnim.snapTo(idx.toFloat())
                    else accentAnim.animateTo(idx.toFloat(), spring(dampingRatio = 0.75f, stiffness = 420f))
                }
            }

            BoxWithConstraints(
                modifier,
                contentAlignment = Alignment.CenterStart
            ) {
                val tabWidth = with(density) {
                    (constraints.maxWidth.toFloat() - 8.dp.toPx()) / tabsCount
                }
                val tabWidthDp = with(density) { tabWidth.toDp() }
                val barShape = RoundedCornerShape(50)

                // 1. 亚克力底座（只模糊无折射）+ 锐利细描边
                Box(
                    Modifier
                        .height(64.dp)
                        .fillMaxWidth()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { blur(acrylicBlurPx) },
                            onDrawSurface = { drawRect(acrylicSurface) }
                        )
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .border(0.75.dp, acrylicStrokeBrush(ui.isDark), barShape)
                )

                // 2. 选中胶囊（普通渲染，在图标之下作高亮背景）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(tabWidthDp)
                        .height(56.dp)
                        .graphicsLayer {
                            translationX = 4.dp.toPx() + accentAnim.value * tabWidth
                        }
                        .clip(barShape)
                        .background(ui.ink)
                )

                // 3. 图标层：选中 tab 图标染 onInk，永远锐利（在胶囊层之上）
                Row(
                    Modifier
                        .matchParentSize()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(tabsCount) { i ->
                        val selected = i == selectedTabIndex()
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(barShape)
                                .clickable(
                                    interactionSource = null,
                                    indication = null
                                ) { onTabSelected(i) },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.runtime.CompositionLocalProvider(
                                    LocalTabIconTint provides if (selected) ui.onInk else Color.Unspecified
                                ) {
                                    tabIcon(i)
                                }
                            }
                        }
                    }
                }
            }
            return
        }
        Row(
            modifier
                .clip(RoundedCornerShape(50))
                .background(ui.surfaceStrong)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(tabsCount) { i ->
                val selected = i == selectedTabIndex()
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) ui.ink else Color.Transparent)
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) { onTabSelected(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalTabIconTint provides if (selected) ui.onInk else Color.Unspecified
                        ) {
                            tabIcon(i)
                        }
                    }
                }
            }
        }
        return
    }

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // 稳定 lambda 引用：避免重组时 LaunchedEffect/remember 因 key 变化重置内部状态
        val selectedTabProvider = rememberUpdatedState(selectedTabIndex)
        // 关键修复：DampedDragAnimation 被 remember 缓存，其 onDragStopped 闭包会捕获
        // 首次组合时的 onTabSelected（进而捕获旧 navigateTab / 旧 tabIndex），
        // 导致拖拽松手后 navigateTab 的 `index == tabIndex` 用旧值误判而吞掉切换。
        // 用 rememberUpdatedState 让拖拽回调始终拿到最新 onTabSelected。
        val onTabSelectedProvider = rememberUpdatedState(onTabSelected)
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabProvider.value().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 恢复官方大鼓包（v2.7.0，用户对比反馈"液体没有凸出来"）：
                // 按压/拖动时选中块鼓出栏体上下各约 11dp，液态凸起感回归。
                // v2.5.1 曾收敛到 62/56——当时误判圆环伪影源于凸出本身，
                // 实际元凶是色散（chromaticAberration），已同步关闭（见下方 lens）
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    // 直接同步回调切页：不经过任何快照流/动画链，回调必定到达
                    onTabSelectedProvider.value.invoke(targetIndex)
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        // 外部页面变化（单击 tab / 系统返回）→ 高亮块动画跟随。
        // 切页职责已完全交给单击回调与 onDragStopped 直接回调，此处只做动画同步。
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { selectedTabProvider.value() }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // 1. 底层玻璃胶囊
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(barBlurDp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { tabIcon(-1) }
        )

        // 2. 隐藏的染色内容副本（alpha 0，仅记录到 tabsBackdrop；禁用点击避免与底层重复触发）
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTabIconTint provides accentColor,
            LocalTabClickEnabled provides false
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(barBlurDp.toPx())
                            lens(
                                24.dp.toPx() * progress,
                                24.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = { tabIcon(-1) }
            )
        }

        // 3. 移动的玻璃选中块
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            8.dp.toPx() * progress,
                            11.dp.toPx() * progress,
                            // 色散是 v2.5.1"圆环/方框伪影"的真正元凶（RGB 边缘分离在凸出
                            // 边界画彩圈）；凸出鼓包本身无碍，关闭色散后液滴干净且可安全凸出
                            chromaticAberration = false
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8.dp * progress,
                            alpha = progress
                        )
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
                            if (!ui.isDark) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

val LocalTabIconTint = androidx.compose.runtime.staticCompositionLocalOf { Color.Unspecified }

/** 隐藏染色层置 false：单击只由底层玻璃胶囊的图标位响应，避免双重触发。 */
val LocalTabClickEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * 底栏单个图标位（官方 LiquidBottomTab 同款：整个图标区可点，Role.Tab）。
 * @param onClick 单击回调；null 或处于隐藏染色层时不响应点击
 */
@Composable
fun RowScope.TabIconSlot(
    index: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null
) {
    val tintOverride = LocalTabIconTint.current
    val clickEnabled = LocalTabClickEnabled.current
    val ui = LocalUi.current
    val clickMod = if (onClick != null && clickEnabled) {
        Modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
    } else {
        Modifier
    }
    Box(
        Modifier
            .weight(1f)
            .height(56.dp)
            .then(clickMod),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tintOverride != Color.Unspecified) tintOverride else ui.textSub,
            modifier = Modifier.height(24.dp)
        )
    }
}
