package com.drone.quiz.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.LocalBgBackdrop
import com.drone.quiz.ui.glass.LocalContentBackdrop
import com.drone.quiz.ui.glass.glass
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首启功能引导（v2.9.1，用户口径：文案精简 + 步骤间 Hero 式连贯转场）。
 *
 * 结构：
 * - [Tour]：8 步导览定义（欢迎卡 + 各功能页锚点步骤，正文一句话）；
 * - [OnboardingBus]：全局开关（首启自动触发与设置页「使用引导」重看入口共用）；
 * - [OnboardingAnchors]：锚点注册表（屏幕坐标 + bringIntoView 请求器）；
 * - [onboardingAnchor]：埋点 Modifier，各屏把引导要强调的元素挂上即可；
 * - [TourHost]：覆盖层（AppRoot 第 6 层）——挖孔遮罩 + 玻璃气泡。
 *
 * Hero 式转场（v2.9.1）：
 * - 换步时挖孔不再「消失-重现」，ready 后从上一步位置平滑飞向新锚点；
 * - 玻璃气泡与挖孔同节奏飞行，标题/正文按行进方向滑动切换；
 * - 整个引导层随开始/结束淡入淡出，不再瞬间出现/消失。
 *
 * 设计约定：
 * - 引导层接管全部触控（本层成为命中目标 + Final 段消费），App 本体只读，
 *   切页由引导自己驱动，状态不会脱钩；
 * - 锚点不在屏内时先 bringIntoView 滚进屏幕再放气泡；锚点始终缺席（如空错题本
 *   没有「开始特训」按钮）则退化为整屏变暗 + 气泡居中，绝不空转卡死；
 * - 「跳过即不再弹」：跳过/翻完都由宿主 onFinish 落 onboarding_done 标记；
 *   设置页重看（replay 模式）只影响本次会话。
 */

// ==================== 步骤定义 ====================

data class TourStep(
    val anchor: String?,   // 高亮锚点 key；null = 欢迎卡（无锚点，居中展示）
    val tab: Int,          // 目标底栏 tab（-1 = 不切页，欢迎卡用）
    val title: String,
    val body: String,      // 多段用 \n 分隔
    val cta: String = "下一步"
)

object Tour {
    val steps = listOf(
        TourStep(
            null, -1,
            "欢迎来到题屿",
            "800 道题、全真模考、自动记错的错题本。\n花一分钟带你逛一圈，随时可以跳过。",
            "开始逛一逛"
        ),
        TourStep(
            "home_overview", 0,
            "首页 · 学习日志",
            "进度、预估通过率和近 7 天曲线，一眼看清刷到哪了。"
        ),
        TourStep(
            "practice_search", 1,
            "搜题 · 快人一步",
            "题干、选项、解析都能搜，最近搜过的也记得住。"
        ),
        TourStep(
            "practice_start", 1,
            "刷题 · 想怎么刷都行",
            "选好题型和数量，点这里马上开刷。"
        ),
        TourStep(
            "exam_start", 2,
            "模考 · 全真演练",
            "题数时长自己定，到点自动交卷出分。"
        ),
        TourStep(
            "wrong_title", 3,
            "错题本 · 专治不会",
            "答错的题自动收进来，练对了才放走。"
        ),
        TourStep(
            "settings_banks", 4,
            "题库 · 想加就加",
            "CSV、ZIP 一键导入；Excel / Word 交给 Agent 整理。"
        ),
        TourStep(
            "settings_look", 4,
            "把它变成你的题屿",
            "字体、壁纸、深色模式随你换。好了，去刷题吧！",
            "开始刷题"
        )
    )
}

// ==================== 全局总线 ====================

/** 引导开关（首启自动触发与设置页「使用引导」重看入口共用）。 */
object OnboardingBus {
    var active by mutableStateOf(false)
        private set
    var stepIndex by mutableIntStateOf(0)
        private set
    var replayMode by mutableStateOf(false)
        private set

    fun start(replay: Boolean) {
        replayMode = replay
        stepIndex = 0
        active = true
    }

    fun next() {
        if (stepIndex < Tour.steps.lastIndex) stepIndex++ else active = false
    }

    fun skip() {
        active = false
    }
}

/** 锚点注册表：各屏 [onboardingAnchor] 上报坐标，TourHost 读取定位挖孔与气泡。 */
object OnboardingAnchors {
    val rects = mutableStateMapOf<String, Rect>()

    /**
     * 每个 key 一个共享 requester（requester 只是指向当前附着节点的句柄，
     * 离开组合再回来重新附着即可复用，无需 remember）。
     */
    internal val requesters = mutableMapOf<String, BringIntoViewRequester>()

    internal fun requesterFor(key: String): BringIntoViewRequester =
        requesters.getOrPut(key) { BringIntoViewRequester() }
}

/**
 * 引导锚点埋点：上报 boundsInRoot 供挖孔定位；引导切到该步时把元素滚进屏幕。
 * 纯修饰符工厂（ui 1.9 起 Modifier.composed 已隐藏，不再用 remember+DisposableEffect）。
 */
fun Modifier.onboardingAnchor(key: String): Modifier =
    this
        .bringIntoViewRequester(OnboardingAnchors.requesterFor(key))
        .onGloballyPositioned { OnboardingAnchors.rects[key] = it.boundsInRoot() }

// ==================== 覆盖层宿主 ====================

/**
 * 引导覆盖层（AppRoot 第 6 层：PortalHost / 打赏弹窗之上）。
 * 整层随引导开/关淡入淡出；内部 [TourOverlay] 存续期间保持状态做 Hero 转场。
 */
@Composable
fun TourHost(
    backdrop: Backdrop,
    currentTab: Int,
    onNavigateTab: (Int) -> Unit,
    onFinish: () -> Unit
) {
    AnimatedVisibility(
        visible = OnboardingBus.active,
        enter = fadeIn(tween(240)),
        exit = fadeOut(tween(280))
    ) {
        TourOverlay(backdrop, currentTab, onNavigateTab, onFinish)
    }
}

/**
 * 编排：步骤切换 →（需要时）切底栏 tab 等转场 → bringIntoView 滚锚点 →
 * ready 后挖孔与气泡从当前位置一起飞向新目标（Hero 转场）。
 */
@Composable
private fun TourOverlay(
    backdrop: Backdrop,
    currentTab: Int,
    onNavigateTab: (Int) -> Unit,
    onFinish: () -> Unit
) {
    val ui = LocalUi.current
    val density = LocalDensity.current
    val stepIdx = OnboardingBus.stepIndex.coerceIn(0, Tour.steps.lastIndex)
    val step = Tour.steps[stepIdx]
    val isLast = stepIdx == Tour.steps.lastIndex

    fun endTour() {
        OnboardingBus.skip()
        onFinish()
    }

    // 系统返回 = 跳过（与跳过按钮同语义）；退场动画期间不再拦截
    BackHandler(enabled = OnboardingBus.active, onBack = { endTour() })

    // 步骤编排：ready 之前转场目标冻结在原地，ready 之后才开始追新锚点
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(stepIdx) {
        ready = false
        if (step.tab >= 0 && step.tab != currentTab) {
            onNavigateTab(step.tab)
            delay(430)          // 页面转场 280ms + 余量
        } else {
            delay(190)
        }
        step.anchor?.let { key ->
            OnboardingAnchors.requesters[key]?.let { r ->
                runCatching { r.bringIntoView() }
            }
            delay(280)          // 滚动动画 + 布局稳定
        }
        ready = true
    }

    // 覆盖层尺寸（= 组合根尺寸），气泡定位/夹边全用它，与 boundsInRoot 同坐标系
    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { layerSize = it }
            // 接管全部触控：本层带 pointerInput 即成为命中目标，下层节点收不到事件；
            // 只在 Final 段消费，气泡内按钮已在 Main 段完成判定不受影响
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    ) {
        val layerW = layerSize.width.toFloat()
        val layerH = layerSize.height.toFloat()
        val growPx = with(density) { 10.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val gapPx = with(density) { 14.dp.toPx() }
        val minPadPx = with(density) { 12.dp.toPx() }
        val bubbleWpx = layerSize.width - marginPx * 2

        fun holeFor(r: Rect): Rect? {
            val l = (r.left - growPx).coerceAtLeast(0f)
            val t = (r.top - growPx).coerceAtLeast(0f)
            val rr = (r.right + growPx).coerceAtMost(layerW)
            val b = (r.bottom + growPx).coerceAtMost(layerH)
            return if (rr - l > 1f && b - t > 1f) Rect(l, t, rr, b) else null
        }

        // ---- Hero 转场 · 挖孔：ready 前冻结原地，ready 后从当前位置飞向新锚点 ----
        var holeFrom by remember { mutableStateOf<Rect?>(null) }
        var holeTo by remember { mutableStateOf<Rect?>(null) }
        val holeMorph = remember { Animatable(0f) }
        val holeAlpha = remember { Animatable(0f) }

        // 挖孔当前绘制位置：由 from/to/morph 进度推导（终态 = to，退场中停在原地）
        val displayHole: Rect? = run {
            val f = holeFrom
            val t = holeTo
            when {
                f == null -> t
                t == null -> f
                else -> lerp(f, t, holeMorph.value)
            }
        }
        val targetHole: Rect? =
            if (ready) {
                step.anchor?.let { key -> OnboardingAnchors.rects[key]?.let { holeFor(it) } }
            } else {
                displayHole
            }

        LaunchedEffect(targetHole) {
            val to = targetHole
            val from = displayHole
            when {
                to == null && from == null -> Unit
                to == null -> {
                    // 挖孔退场（锚点缺席兜底）：停在原地淡出而不是瞬间消失
                    holeFrom = from
                    holeTo = null
                    holeMorph.snapTo(1f)
                    holeAlpha.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                    holeFrom = null
                }
                from == null -> {
                    // 挖孔登场：原地淡入
                    holeFrom = null
                    holeTo = to
                    holeMorph.snapTo(1f)
                    holeAlpha.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                }
                from == to -> {
                    holeFrom = to
                    holeTo = to
                    holeMorph.snapTo(1f)
                    holeAlpha.animateTo(1f, tween(120))
                }
                else -> {
                    // Hero morph：挖孔在两步之间平滑飞行
                    holeFrom = from
                    holeTo = to
                    holeMorph.snapTo(0f)
                    launch { holeAlpha.animateTo(1f, tween(200)) }
                    holeMorph.animateTo(1f, tween(430, easing = FastOutSlowInEasing))
                }
            }
        }

        // ---- 挖孔遮罩：整屏变暗 + 当前挖孔（飞行中实时跟随） + 呼吸描边 ----
        val breath by rememberInfiniteTransition(label = "tourBreath").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
            label = "tourBreath"
        )
        Canvas(Modifier.fillMaxSize()) {
            val scrimAlpha = 0.45f
            val t = holeAlpha.value
            // alpha < 1 时全屏 scrim 与挖孔版本交叉淡化，孔外亮度保持恒定
            if (t < 1f) drawRect(Color.Black.copy(alpha = scrimAlpha * (1f - t)))
            val hole = displayHole
            if (hole != null && t > 0f) {
                val corner = CornerRadius(22.dp.toPx())
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(hole, corner))
                }
                drawPath(path, Color.Black.copy(alpha = scrimAlpha * t))
                // 柔光外圈 + 呼吸描边（微动效提示"看这里"）
                drawRoundRect(
                    color = ui.accent.copy(alpha = 0.30f * breath * t),
                    topLeft = Offset(hole.left - 4.dp.toPx(), hole.top - 4.dp.toPx()),
                    size = Size(hole.width + 8.dp.toPx(), hole.height + 8.dp.toPx()),
                    cornerRadius = CornerRadius(26.dp.toPx()),
                    style = Stroke(width = 5.dp.toPx())
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f * breath * t),
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = corner,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // ---- 气泡纵向定位（与挖孔同一节奏飞行） ----
        var bubbleHpx by remember { mutableStateOf(0) }
        var bubbleFrom by remember { mutableStateOf<Float?>(null) }
        var bubbleTo by remember { mutableStateOf<Float?>(null) }
        val bubbleFly = remember { Animatable(0f) }

        val anchorRectNow = step.anchor?.let { OnboardingAnchors.rects[it] }
        // 纵向位置：锚点下方优先，空间不足放上方；欢迎卡垂直居中；锚点缺席兜底中下部
        val computedBubbleY: Float? = if (!ready) {
            null
        } else when {
            step.anchor == null ->
                layerH * 0.5f - bubbleHpx * 0.5f
            anchorRectNow != null -> {
                val below = anchorRectNow.bottom + gapPx
                val above = anchorRectNow.top - gapPx - bubbleHpx
                val y = if (below + bubbleHpx <= layerH - minPadPx) below else above
                y.coerceIn(minPadPx, (layerH - bubbleHpx - minPadPx).coerceAtLeast(minPadPx))
            }
            else ->
                layerH * 0.60f - bubbleHpx * 0.5f
        }

        // 气泡当前纵向位置：由 from/to/飞行进度推导（终态 = to）
        val displayBubbleY: Float? = run {
            val f = bubbleFrom
            val t = bubbleTo
            when {
                f == null -> t
                t == null -> f
                else -> f + (t - f) * bubbleFly.value
            }
        }

        LaunchedEffect(computedBubbleY) {
            val to = computedBubbleY ?: return@LaunchedEffect
            val from = displayBubbleY
            when {
                from == null -> {
                    bubbleFrom = null
                    bubbleTo = to
                    bubbleFly.snapTo(1f)
                }
                from == to -> {
                    bubbleFrom = to
                    bubbleTo = to
                    bubbleFly.snapTo(1f)
                }
                else -> {
                    // Hero 转场：气泡与挖孔同节奏飞行
                    bubbleFrom = from
                    bubbleTo = to
                    bubbleFly.snapTo(0f)
                    bubbleFly.animateTo(1f, tween(430, easing = FastOutSlowInEasing))
                }
            }
        }

        // 玻璃折射源：与弹窗面板同款（背景层 + 内容层合成）
        val bgLayer = LocalBgBackdrop.current
        val contentLayer = LocalContentBackdrop.current
        val panelBackdrop = if (bgLayer != null && contentLayer != null) {
            rememberCombinedBackdrop(bgLayer, contentLayer)
        } else {
            backdrop
        }

        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }

        AnimatedVisibility(
            visible = appeared,
            enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 5 },
            exit = fadeOut(tween(150)),
            modifier = Modifier.offset(
                x = with(density) { marginPx.toInt().toDp() },
                y = with(density) {
                    (displayBubbleY ?: computedBubbleY ?: (layerH * 0.5f - bubbleHpx * 0.5f))
                        .toInt().toDp()
                }
            )
        ) {
            Column(
                Modifier
                    .width(with(density) { bubbleWpx.toInt().toDp() })
                    .glass(
                        backdrop = panelBackdrop,
                        shape = RoundedCornerShape(24.dp),
                        blurDp = 18.dp,
                        lensHeightDp = 14.dp,
                        lensAmountDp = 18.dp,
                        surfaceColor = ui.surface.copy(alpha = 0.78f)
                    )
                    .onSizeChanged { bubbleHpx = it.height }
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                // 步骤圆点（当前步加宽成胶囊，宽度渐变）
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Tour.steps.forEachIndexed { i, _ ->
                        val cur = i == stepIdx
                        val dotW by animateDpAsState(
                            targetValue = if (cur) 16.dp else 5.dp,
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            label = "tourDot"
                        )
                        Box(
                            Modifier
                                .size(width = dotW, height = 5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (cur) ui.accent else ui.textSub.copy(alpha = 0.35f))
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 标题 + 正文：随行进方向滑动切换（Hero 转场的内容部分）
                AnimatedContent(
                    targetState = stepIdx,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val slideSpec = tween<IntOffset>(340, easing = FastOutSlowInEasing)
                        val fadeSpec = tween<Float>(340, easing = FastOutSlowInEasing)
                        if (forward) {
                            (slideInVertically(slideSpec) { it / 4 } + fadeIn(fadeSpec)) togetherWith
                                (slideOutVertically(slideSpec) { -it / 4 } + fadeOut(fadeSpec))
                        } else {
                            (slideInVertically(slideSpec) { -it / 4 } + fadeIn(fadeSpec)) togetherWith
                                (slideOutVertically(slideSpec) { it / 4 } + fadeOut(fadeSpec))
                        }
                    },
                    label = "tourBubbleContent"
                ) { idx ->
                    val s = Tour.steps[idx]
                    Column {
                        Text(
                            s.title,
                            color = ui.text, fontSize = 20.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            s.body,
                            color = ui.text.copy(alpha = 0.86f),
                            fontSize = 14.sp, lineHeight = 23.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 跳过：文字按钮（垂直 8dp padding 保证 ~30dp 触达高度）
                    Text(
                        "跳过引导",
                        color = ui.textSub, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = null,
                                indication = null
                            ) { endTour() }
                            .padding(horizontal = 6.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    GlassButton(
                        onClick = { if (isLast) endTour() else OnboardingBus.next() },
                        backdrop = backdrop,
                        surfaceColor = ui.ink,
                        heightDp = 44.dp
                    ) {
                        Text(
                            step.cta,
                            color = ui.onInk, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
