package com.drone.quiz.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import kotlinx.coroutines.launch

/**
 * 弹窗面板进出场（统一 spring 手感；v2.11.2 起不再区分果冻过冲）。
 */
private fun overlayEnter(fadeMs: Int, initialScale: Float, stiffness: Float): EnterTransition {
    return fadeIn(tween(fadeMs)) + scaleIn(
        initialScale = initialScale,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = stiffness
        )
    )
}

/**
 * 弹窗打开时置 active：AppRoot 据此对内容层施加真模糊（iOS 风格）。
 * 注意：弹窗面板本身已通过 GlassOverlayPortal 渲染在模糊区之外，不会被连帶模糊。
 *
 * v2.8.3 改为引用计数（持有者 id 列表）：修叠层弹窗互踩——
 * 此前任一弹窗离场都直接把 active 置 false，
 * 例如「查看 CSV 模板」对话框关闭后，仍然开着的导入题库弹窗会丢掉背景模糊（用户反馈）。
 */
object OverlayBlur {
    private val holders = mutableStateListOf<Any>()
    val active: Boolean get() = holders.isNotEmpty()

    fun push(id: Any) {
        if (!holders.contains(id)) holders.add(id)
    }

    fun pop(id: Any) {
        holders.remove(id)
    }
}

/**
 * 弹窗传送门。
 *
 * 背景：v2.4.0 的做法是把整个内容层 blur——但弹窗面板本身就渲染在内容层里，
 * 于是"答题卡展开时把答题卡自己也模糊了"。
 *
 * v2.5.0：弹窗面板不再原地渲染，而是注册进 [entries]，由 AppRoot 在内容层
 * （模糊区）之上、底栏之上统一渲染。面板因此永远清晰，且位于内容记录层之外，
 * 可安全折射"背景+内容"合成层（真 iOS 玻璃，此前因循环采样风险只能折射背景层）。
 *
 * 多槽位列表：同屏多个弹窗（如模考页确认框 + 答题卡）互不覆盖，后组合者在上。
 */
object GlassOverlayPortal {

    class Entry(val id: Any, val content: @Composable () -> Unit)

    val entries = mutableStateListOf<Entry>()

    /** 组合期间调用：按 id 注册/刷新面板内容（幂等，每次重组刷新 lambda）。 */
    fun set(id: Any, content: @Composable () -> Unit) {
        val idx = entries.indexOfFirst { it.id === id }
        if (idx >= 0) {
            entries[idx] = Entry(id, content)
        } else {
            entries.add(Entry(id, content))
        }
    }

    fun remove(id: Any) {
        entries.removeAll { it.id === id }
    }
}

/** AppRoot 提供的双记录层；弹窗面板用它合成折射源。 */
val LocalBgBackdrop = compositionLocalOf<Backdrop?> { null }
val LocalContentBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 弹窗主体（scrim + 玻璃面板），渲染在 AppRoot 顶层。
 *
 * - scrim 极淡：层次感主角是内容层真模糊（AppRoot 响应 OverlayBlur.active）；
 *   点 scrim 关闭，面板消费自身点按防误关。
 * - 面板折射 combined(背景层, 内容层)——内容层此刻正被模糊，折射出的正是
 *   "毛玻璃后的世界"。
 */
@Composable
private fun GlassOverlayPanel(
    scrimColor: Color,
    contentAlignment: Alignment,
    panelShape: Shape,
    panelModifier: Modifier,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    panelOffsetProvider: (() -> Float)? = null,
    headerStrip: (@Composable ColumnScope.() -> Unit)? = null,
    panel: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .background(scrimColor)
                .pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() }
                }
        )
        val bg = LocalBgBackdrop.current
        val contentBd = LocalContentBackdrop.current
        // 面板已处于内容记录层之外 → 折射"背景+内容"安全（与底栏同款）
        val panelBackdrop = if (bg != null && contentBd != null) {
            rememberCombinedBackdrop(bg, contentBd)
        } else {
            backdrop
        }
        Box(
            Modifier
                .align(contentAlignment)
                .then(panelModifier)
                .then(
                    // v2.8.3：允许负值（把手上拉的过冲拉伸）；下滑关闭仍为正值
                    if (panelOffsetProvider != null) Modifier.graphicsLayer {
                        translationY = panelOffsetProvider()
                    } else Modifier
                )
                .pointerInput(Unit) { detectTapGestures { } } // 消费面板内点按，防误关
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(
                        backdrop = panelBackdrop,
                        shape = panelShape,
                        // v2.8.3：24→18dp——面板自身模糊是弹窗掉帧主因之一，降档减负（用户反馈）
                        blurDp = 18.dp,
                        lensHeightDp = 16.dp,
                        lensAmountDp = 22.dp,
                        // 降透明度让折射可见：过实会像不透明色块而非玻璃
                        surfaceColor = LocalUi.current.surface.copy(alpha = 0.62f)
                    )
            ) {
                if (headerStrip != null) headerStrip()
                panel()
            }
        }
    }
}

/**
 * 弹窗注册骨架：BackHandler + OverlayBlur 状态 + 传送门生命周期。
 * 本体不渲染任何东西；[content]（含出入场动画，由各弹窗自带）在 AppRoot 顶层渲染。
 */
@Composable
private fun GlassOverlayRegistration(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    if (visible) {
        BackHandler(onBack = onDismiss)
    }

    val overlayId = remember { Any() }

    // 弹窗可见期间进入模糊态（引用计数，随 visible 增减；叠层弹窗互不冲掉）
    DisposableEffect(visible) {
        if (visible) OverlayBlur.push(overlayId)
        onDispose { OverlayBlur.pop(overlayId) }
    }
    // 整体离开组合：兜底清引用并注销传送门槽位
    DisposableEffect(Unit) {
        onDispose {
            OverlayBlur.pop(overlayId)
            GlassOverlayPortal.remove(overlayId)
        }
    }

    GlassOverlayPortal.set(overlayId, content)
}

/**
 * iOS 26 风玻璃底部面板（替代 ModalBottomSheet）。
 * - 无深色遮罩：内容层真模糊已足够层次（用户反馈）；scrim 仅作点击关闭的透明层
 * - 顶部小把手：拖动把手区可下滑跟手，松手超过阈值关闭、否则弹性回位
 */
@Composable
fun GlassBottomSheet(
    visible: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassOverlayRegistration(visible, onDismiss) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f),
                initialOffsetY = { it }
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
                targetOffsetY = { it }
            ) + fadeOut(tween(180))
        ) {
            val ui = LocalUi.current
            val density = LocalDensity.current
            val dismissThreshold = with(density) { 110.dp.toPx() }
            val dragY = remember { Animatable(0f) }
            // 原始拖拽累计（含向上）：显示值 = 下滑 1:1，上拉 rubber-band 阻尼衰减
            var rawDragY by remember { mutableFloatStateOf(0f) }
            val dragScope = rememberCoroutineScope()

            // 每次打开复位拖拽偏移
            androidx.compose.runtime.LaunchedEffect(visible) {
                if (visible) {
                    dragY.snapTo(0f)
                    rawDragY = 0f
                }
            }

            fun springBack() {
                dragScope.launch {
                    dragY.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 380f))
                }
            }

            GlassOverlayPanel(
                // 无深色遮罩：透明层仅承担点击关闭
                scrimColor = Color.Transparent,
                contentAlignment = Alignment.BottomCenter,
                panelShape = RoundedCornerShape(28.dp),
                panelModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(
                        bottom = 12.dp + WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding()
                    ),
                backdrop = backdrop,
                onDismiss = onDismiss,
                panelOffsetProvider = { dragY.value },
                headerStrip = {
                    // 把手 + 可拖拽热区（仅顶部条响应拖拽，不与内部网格滚动冲突）。
                    // v2.8.3：下滑跟手关闭之外，上拉带 rubber-band 过冲（越拉越费力，
                    // 松手弹回；面板底缘不动，顶缘被轻轻拽起），用户口径
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragScope.launch { dragY.stop() } },
                                    onVerticalDrag = { change, dy ->
                                        change.consume()
                                        dragScope.launch {
                                            rawDragY += dy
                                            val disp = if (rawDragY >= 0f) rawDragY
                                            else -80f * (1f - kotlin.math.exp(rawDragY / 220f))
                                            dragY.snapTo(disp)
                                        }
                                    },
                                    onDragEnd = {
                                        dragScope.launch {
                                            if (rawDragY > dismissThreshold) onDismiss()
                                            else springBack()
                                            rawDragY = 0f
                                        }
                                    },
                                    onDragCancel = {
                                        springBack()
                                        rawDragY = 0f
                                    }
                                )
                            }
                            .padding(top = 10.dp, bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(width = 40.dp, height = 4.5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ui.textSub.copy(alpha = 0.45f))
                        )
                    }
                }
            ) {
                content()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 锚点玻璃小菜单（v2.8.2，替代首页题库切换的 Popup + 原生 surface）：
 * - 走传送门渲染在内容层之上，面板折射「背景+内容」合成层 → 与其他弹窗同款液态玻璃；
 * - 锚定在触发者（副标题胶囊）下方，入场缩放+淡入动画（transformOrigin 在锚点方向）；
 * - 透明 scrim 点击关闭；OverlayBlur 生效期间内容层+背景层一起被真模糊。
 *
 * @param anchorPx 触发者在窗口坐标系下的 (x, y) 像素位置与高度（px）
 */
@Composable
fun GlassAnchorMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    anchorXpx: Float,
    anchorYpx: Float,
    anchorHeightPx: Float,
    backdrop: Backdrop,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassOverlayRegistration(visible, onDismiss) {
        AnimatedVisibility(
            visible = visible,
            enter = overlayEnter(150, 0.86f, 480f),
            exit = fadeOut(tween(130)) + scaleOut(targetScale = 0.94f, animationSpec = tween(130))
        ) {
            val density = LocalDensity.current
            val xDp = with(density) { anchorXpx.toDp() }
            // 菜单顶部 = 触发者底部 + 8dp 间距；最小不低于状态栏之下
            val yDp = with(density) { (anchorYpx + anchorHeightPx + 8f).toDp() }
            GlassOverlayPanel(
                scrimColor = Color.Transparent,
                contentAlignment = Alignment.TopStart,
                panelShape = RoundedCornerShape(18.dp),
                panelModifier = Modifier
                    .padding(start = xDp, top = yDp)
                    .width(248.dp),
                backdrop = backdrop,
                onDismiss = onDismiss
            ) {
                Column(Modifier.padding(vertical = 6.dp)) { content() }
            }
        }
    }
}

/**
 * 提示词对话框（v2.8.5）：长文本可滚动预览 + 一键复制。
 * 用于「复制 Agent 提示词」——用户把提示词连同自己的 Excel/Word/PDF 材料交给 AI Agent，
 * 由 Agent 整理成题屿可导入的 CSV / ZIP。
 */
@Composable
fun GlassPromptDialog(
    backdrop: Backdrop,
    title: String,
    hint: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassOverlayRegistration(visible = true, onDismiss = onDismiss) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(
            visible = shown,
            enter = overlayEnter(160, 0.92f, 420f),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140))
        ) {
            val ui = LocalUi.current
            val scroll = rememberScrollState()
            GlassOverlayPanel(
                scrimColor = Color.Black.copy(alpha = if (ui.isDark) 0.22f else 0.10f),
                contentAlignment = Alignment.Center,
                panelShape = RoundedCornerShape(26.dp),
                panelModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    // 提示词很长：整体限高，正文区内部滚动
                    .heightIn(max = 560.dp),
                backdrop = backdrop,
                onDismiss = onDismiss
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text(
                        title,
                        color = ui.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (hint.isNotBlank()) {
                        Text(
                            hint,
                            color = ui.textSub,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ui.ink.copy(alpha = 0.05f))
                            .heightIn(max = 320.dp)
                            .verticalScroll(scroll)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            body,
                            color = ui.text,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(dismissText, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        GlassButton(
                            onClick = onConfirm,
                            backdrop = backdrop,
                            surfaceColor = ui.ink.copy(alpha = 0.92f),
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(confirmText, color = ui.onInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 玻璃输入对话框（v2.8.7 题库重命名用）：单行输入 + 确认/取消。
 * 与 GlassPromptDialog（纯展示）不同，本对话框携带用户输入回调。
 */
@Composable
fun GlassInputDialog(
    backdrop: Backdrop,
    title: String,
    initialText: String,
    hint: String = "",
    maxLength: Int = 16,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    GlassOverlayRegistration(visible = true, onDismiss = onDismiss) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(
            visible = shown,
            enter = overlayEnter(160, 0.92f, 420f),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140))
        ) {
            val ui = LocalUi.current
            var draft by remember { mutableStateOf(initialText) }
            GlassOverlayPanel(
                scrimColor = Color.Black.copy(alpha = if (ui.isDark) 0.22f else 0.10f),
                contentAlignment = Alignment.Center,
                panelShape = RoundedCornerShape(26.dp),
                panelModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                backdrop = backdrop,
                onDismiss = onDismiss
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                    Text(
                        title,
                        color = ui.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (hint.isNotBlank()) {
                        Text(
                            hint,
                            color = ui.textSub,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ui.ink.copy(alpha = 0.05f))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it.take(maxLength) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = ui.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(ui.text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (draft.isBlank()) {
                            Text(
                                "输入新名称",
                                color = ui.textSub.copy(alpha = 0.55f),
                                fontSize = 14.sp
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(dismissText, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        GlassButton(
                            // 空名禁点：GlassButton 无 enabled 参数，onClick 内守卫
                            onClick = { if (draft.isNotBlank()) onConfirm(draft.trim()) },
                            backdrop = backdrop,
                            surfaceColor = ui.ink.copy(alpha = 0.92f),
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(confirmText, color = ui.onInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * iOS 26 风玻璃对话框（替代 AlertDialog）。
 * 保持 `if (show) GlassConfirmDialog(...)` 的调用形式：进组合即注册传送门，
 * 出组合即注销（面板缩放淡入；退场即时，与既有行为一致）。
 */
@Composable
fun GlassConfirmDialog(
    backdrop: Backdrop,
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    confirmColor: Color? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassOverlayRegistration(visible = true, onDismiss = onDismiss) {
        // 对话框缩放淡入（退场因组合销毁即时消失，与旧行为一致）
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(
            visible = shown,
            enter = overlayEnter(160, 0.92f, 420f),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140))
        ) {
            val ui = LocalUi.current
            GlassOverlayPanel(
                scrimColor = Color.Black.copy(alpha = if (ui.isDark) 0.22f else 0.10f),
                contentAlignment = Alignment.Center,
                panelShape = RoundedCornerShape(26.dp),
                panelModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                backdrop = backdrop,
                onDismiss = onDismiss
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                    Text(
                        title,
                        color = ui.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        body,
                        color = ui.textSub,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(dismissText, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        GlassButton(
                            onClick = onConfirm,
                            backdrop = backdrop,
                            surfaceColor = (confirmColor ?: ui.ink).copy(alpha = 0.92f),
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                confirmText,
                                color = if (confirmColor != null) Color.White else ui.onInk,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * iOS 26 风玻璃内容对话框（v2.16.0）：标题 + 自定义内容槽 + 可选双按钮。
 * 与 [GlassConfirmDialog] 同构，区别是 body 换成任意 Composable 内容
 * （模型下载进度条等），确认/取消按钮可分别隐藏。
 */
@Composable
fun GlassContentDialog(
    backdrop: Backdrop,
    title: String,
    dismissText: String,
    confirmText: String? = null,
    onDismiss: () -> Unit = {},
    onConfirm: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    GlassOverlayRegistration(visible = true, onDismiss = onDismiss) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(
            visible = shown,
            enter = overlayEnter(160, 0.92f, 420f),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140))
        ) {
            val ui = LocalUi.current
            GlassOverlayPanel(
                scrimColor = Color.Black.copy(alpha = if (ui.isDark) 0.22f else 0.10f),
                contentAlignment = Alignment.Center,
                panelShape = RoundedCornerShape(26.dp),
                panelModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                backdrop = backdrop,
                onDismiss = onDismiss
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                    Text(
                        title,
                        color = ui.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    content()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            heightDp = 44.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(dismissText, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (confirmText != null) {
                            GlassButton(
                                onClick = onConfirm,
                                backdrop = backdrop,
                                surfaceColor = ui.ink.copy(alpha = 0.92f),
                                heightDp = 44.dp,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    confirmText,
                                    color = ui.onInk,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
