package com.drone.quiz.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.QuestionTypes
import com.drone.quiz.data.repo.UserAnswer
import com.drone.quiz.data.repo.judgeAnswer
import com.drone.quiz.data.repo.isAnswered
import com.drone.quiz.data.repo.optionLabel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.data.settings.PracticeSession
import com.drone.quiz.screens.common.BlankInlineFields
import com.drone.quiz.screens.common.CorrectAnswerLine
import com.drone.quiz.screens.common.ParseBlock
import com.drone.quiz.screens.common.OptionRow
import com.drone.quiz.screens.common.OptionToggleRow
import com.drone.quiz.screens.common.QuestionImageStrip
import com.drone.quiz.screens.common.QuestionTypeTag
import com.drone.quiz.screens.common.ResultHeader
import com.drone.quiz.screens.common.ShortDraftField
import com.drone.quiz.screens.common.ShortReferenceCard
import com.drone.quiz.screens.common.SubmitAnswerButton
import com.drone.quiz.screens.common.TagChip
import com.drone.quiz.screens.common.UsageSignals
import com.drone.quiz.screens.common.displayCategory
import com.drone.quiz.screens.common.rememberBankName
import com.drone.quiz.screens.common.remainingBottomPx
import com.drone.quiz.screens.common.scrolledFromTopPx
import com.drone.quiz.screens.common.softVerticalEdges
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassConfirmDialog
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.glass.GlassSlider
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

internal val answerJson = Json { ignoreUnknownKeys = true }

/**
 * 全屏刷题页（由配置页进入；非 Tab destination，无底栏遮挡）。
 * 进度实时持久化：答题/翻页即写 DataStore 会话快照（后台静默保存，不丢记录）。
 * v2.8.0：支持多选（提交判）/填空（键盘避让）/简答（自评判分）；会话绑定题库，切库后旧会话作废。
 */
@Composable
fun PracticeRunScreen(
    backdrop: Backdrop,
    src: String = "all",
    type: String = "all",
    cat: String = "all",
    resume: Boolean = false,
    // v2.10.0 随机小练：>0 时只抽 limit 道新题（配合 src="random"，不恢复/不落盘快照）
    limit: Int = 0,
    onExit: () -> Unit
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()

    // v2.8.6 性能：不再整体收集 settings——DataStore 任意 key 写入（含本页每次翻页/作答的
    // 会话快照落盘）都会重发完整 settings，导致本页与可见题卡连锁重组。
    // 只订阅本页真正用到的字段（去重后），无关写入不再穿透。
    val autoNext by ServiceLocator.settings.settings
        .map { it.autoNext }
        .distinctUntilChanged()
        .collectAsState(initial = true)
    val removeThreshold by ServiceLocator.settings.settings
        .map { it.removeThreshold }
        .distinctUntilChanged()
        .collectAsState(initial = 2)
    val eyeCareOn by ServiceLocator.settings.settings
        .map { it.eyeCareReminder }
        .distinctUntilChanged()
        .collectAsState(initial = false)

    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var sessionMode by remember { mutableStateOf(resume) }
    // 恢复中：抑制翻页持久化，防止 snapshotFlow 初始发射把快照 index 覆写回 0
    var restoring by remember { mutableStateOf(false) }
    // 统一作答状态：picked 为规范值（multi 位掩码 / blank·short 1），details 保存完整 UserAnswer
    val answers = remember { mutableStateMapOf<Long, Int>() }
    val details = remember { mutableStateMapOf<Long, UserAnswer>() }
    var showPanel by remember { mutableStateOf(false) }
    var restoredTick by remember { mutableIntStateOf(0) }
    // 恢复会话的目标页（快照 index）：恢复时捕获，跳页用（v2.11.0 前是跳页时重读
    // DataStore，多槽后同上下文可能已变化，且多一次无谓读取）
    var restoredIndex by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }
    // 本会话所属模式槽（0 顺序 / 1 随机）与题库
    var sessionOrder by remember { mutableIntStateOf(0) }
    var sessionBank by remember { mutableStateOf("drone") }
    // v2.8.6 循环补漏：本轮周期内已覆盖题目累计（不含本轮 answers），随快照落盘
    var roundCovered by remember { mutableStateOf<List<Long>>(emptyList()) }

    val pagerState = rememberPagerState { questions.size }

    // 打赏门槛计时口径：只有真实停在答题页才累计刷题时长（v2.9.2，与弹窗文案「累计刷题」对齐）
    DisposableEffect(Unit) {
        UsageSignals.onPracticeScreen = true
        onDispose { UsageSignals.onPracticeScreen = false }
    }

    // ---- 题库就绪自动重载：启动门控超时放行/导入后台完成后，题数 0→N 触发重试 ----
    LaunchedEffect(Unit) {
        var prev = -1
        ServiceLocator.repo.countFlow().collect { c ->
            if (c > 0 && prev == 0 && questions.isEmpty() && !loading) {
                reloadTick++
            }
            prev = c
        }
    }

    // ---- 护眼提醒（v2.8.6，防沉迷口径）：连续刷题满 20 分钟弹窗提醒看看远处 ----
    // 仅刷题页生效（考试/模考不受影响）；只在应用前台（RESUMED）时累计，
    // 切后台自动暂停、退出本页计时即止；弹窗后重新计时，每满 20 分钟可再次触发。
    var showEyeCare by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(eyeCareOn) {
        if (!eyeCareOn) return@LaunchedEffect
        val interval = 20 * 60_000L
        var acc = 0L
        while (true) {
            delay(15_000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                acc += 15_000
                if (acc >= interval) {
                    acc = 0
                    showEyeCare = true
                }
            }
        }
    }

    // ---- 加载：优先恢复会话快照，否则按筛选参数新开；超时兜底永不定死 ----
    LaunchedEffect(src, type, cat, resume, limit, reloadTick) {
        loading = true
        loadError = false
        // 关键：必须挂起读 DataStore 真值——collectAsState 首帧是 AppSettings() 默认值
        val st = runCatching { ServiceLocator.settings.settings.first() }.getOrDefault(AppSettings())
        val order = st.practiceOrder
        val bank = st.currentBank
        sessionOrder = order
        sessionBank = bank
        val result = withTimeoutOrNull(10_000) {
            runCatching {
                if (src == "random" && limit > 0) {
                    // v2.10.0 随机小练（快捷方式）：随机抽 N 道新题，
                    // 不恢复/不补漏/不落盘快照（内存会话，退出即止，不污染顺序/随机双槽）
                    sessionMode = false
                    answers.clear(); details.clear()
                    roundCovered = emptyList()
                    ServiceLocator.repo.loadPractice(bank, null, null, random = true, limit = limit)
                } else if (resume) {
                    // v2.11.0 多槽：「继续刷题」接续该题库+模式下最近保存的会话
                    //（任意题型范围；配置页换过范围也能续回最新那份进度）
                    val s = ServiceLocator.settings.latestPracticeSession(bank, order)
                    if (s != null && s.ids.isNotEmpty() && s.bankId == bank && !sessionAtEnd(s)) {
                        sessionMode = true
                        restoring = true
                        roundCovered = s.covered
                        val qs = ServiceLocator.repo.loadPracticeByIds(s.ids)
                        answers.clear(); details.clear()
                        s.answers.forEach { (k, v) -> k.toLongOrNull()?.let { answers[it] = v } }
                        s.details.forEach { (k, v) ->
                            k.toLongOrNull()?.let { id ->
                                runCatching { answerJson.decodeFromString<UserAnswer>(v) }
                                    .getOrNull()?.let { ua -> details[id] = ua }
                            }
                        }
                        restoredIndex = s.index
                        restoredTick++          // 恢复完成后跳转进度
                        qs
                    } else {
                        // 无会话可恢复 / 已切库 / 上轮已刷到末题：退化为按参数新开
                        sessionMode = false
                        answers.clear(); details.clear()
                        // v2.8.6 循环补漏：上轮已刷到末题 → 新一轮只挑没刷过的题
                        val catchUp = loadCatchUpRound(bank, src, type, cat, order, s)
                        if (catchUp != null) {
                            roundCovered = catchUp.covered
                            catchUp.questions
                        } else {
                            roundCovered = emptyList()
                            loadByFilter(bank, src, type, cat, order == 1)
                        }
                    }
                } else {
                    sessionMode = false
                    answers.clear(); details.clear()
                    // 自动接续：存在同筛选参数、同题库的会话快照 → 无缝恢复
                    //（v2.11.0 多槽：按「题库+模式+范围」精确取槽，不会误接别的库/范围）
                    val snap = runCatching {
                        ServiceLocator.settings.currentPracticeSession(bank, order, type, cat)
                    }.getOrNull()
                    if (src == "all" && snap != null && snap.src == "all" && snap.bankId == bank &&
                        !sessionAtEnd(snap) &&
                        snap.type == type && snap.cat == cat
                    ) {
                        val qs = ServiceLocator.repo.loadPracticeByIds(snap.ids)
                        if (qs.isNotEmpty()) {
                            sessionMode = true
                            restoring = true
                            roundCovered = snap.covered
                            snap.answers.forEach { (k, v) -> k.toLongOrNull()?.let { answers[it] = v } }
                            snap.details.forEach { (k, v) ->
                                k.toLongOrNull()?.let { id ->
                                    runCatching { answerJson.decodeFromString<UserAnswer>(v) }
                                        .getOrNull()?.let { ua -> details[id] = ua }
                                }
                            }
                            restoredIndex = snap.index
                            restoredTick++
                            qs
                        } else {
                            roundCovered = emptyList()
                            loadByFilter(bank, src, type, cat, order == 1)
                        }
                    } else {
                        // v2.8.6 循环补漏：无快照/参数变化 → 整单新开；上轮已刷到末题 → 只挑没刷过的题
                        val catchUp = loadCatchUpRound(bank, src, type, cat, order, snap)
                        if (catchUp != null) {
                            roundCovered = catchUp.covered
                            catchUp.questions
                        } else {
                            roundCovered = emptyList()
                            loadByFilter(bank, src, type, cat, order == 1)
                        }
                    }
                }
            }
        }
        questions = result?.getOrElse { emptyList() }.orEmpty()
        loadError = result == null || result.isFailure
        loading = false
        // 注意：恢复路径不在此处落盘——落盘只发生在真实翻页/作答时
    }

    // 恢复会话：等 Pager 上屏后跳到上次进度；跳转完成后才恢复持久化（restoring 解除）
    LaunchedEffect(restoredTick) {
        if (restoredTick > 0 && questions.isNotEmpty()) {
            pagerState.scrollToPage(restoredIndex.coerceIn(0, questions.size - 1))
            restoring = false
        }
    }

    // 新会话首次落盘（拿到题目列表即建快照，答第一题前退出也能续）
    var sessionSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(questions) {
        if (questions.isNotEmpty() && !sessionSeeded && !sessionMode) {
            sessionSeeded = true
            pagerState.scrollToPage(0)
            persistSession(src, type, cat, sessionOrder, sessionBank, questions, answers, details, 0, roundCovered)
        }
    }

    // 翻页进度实时保存（恢复跳页期间静默，防止把进度覆写回 0）
    LaunchedEffect(questions) {
        if (questions.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .collectLatest { page ->
                if (!loading && !restoring) {
                    persistSession(src, type, cat, sessionOrder, sessionBank, questions, answers, details, page, roundCovered)
                }
            }
    }

    /**
     * 统一提交入口：记录作答 + 落盘 + （答对且自动切题时）翻页。
     * 记账只在"正确性首次确定"时发生一次（简答：提交草稿不记，自评后才记）。
     */
    fun onCommit(q: Question, ua: UserAnswer) {
        val prev = details[q.id]
        // 幂等：单选/判断/多选/填空已提交后再触发不重复记账
        if (prev != null && prev.picked == ua.picked && prev.graded == ua.graded) return
        details[q.id] = ua
        answers[q.id] = ua.picked
            ?: if (ua.texts.isNotEmpty() || ua.text.isNotBlank()) 1 else 0
        val correct = judgeAnswer(q, ua)
        if (correct != null && (prev == null || judgeAnswer(q, prev) == null)) {
            ServiceLocator.appScope.launch {
                runCatching {
                    ServiceLocator.repo.recordAnswer(
                        qid = q.id,
                        isCorrect = correct,
                        mode = if (src == "wrong") "wrong" else "practice",
                        removeThreshold = removeThreshold
                    )
                }
            }
        }
        persistSession(src, type, cat, sessionOrder, sessionBank, questions, answers, details, pagerState.currentPage, roundCovered)
        if (correct == true && q.type != QuestionTypes.SHORT &&
            autoNext && pagerState.currentPage < questions.size - 1
        ) {
            scope.launch {
                delay(700)
                val target = pagerState.currentPage + 1
                pagerState.animateScrollToPage(target)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        // ---- 顶部：返回 + 标题 + 题号面板 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                onClick = onExit,
                backdrop = backdrop,
                icon = AppIcons.ChevronLeft
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    if (src == "wrong") "错题特训" else "刷题",
                    color = ui.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (questions.isEmpty()) "加载中…"
                    else "第 ${pagerState.currentPage + 1} / ${questions.size} 题" +
                        if (src == "wrong" || cat == "all") "" else " · $cat",
                    color = ui.textSub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            GlassIconButton(
                onClick = { showPanel = true },
                backdrop = backdrop,
                icon = AppIcons.Grid
            )
        }

        // ---- 题目 Pager ----
        when {
            loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("正在加载题库…", color = ui.textSub, fontSize = 14.sp)
            }
            questions.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        AppIcons.Cards, null,
                        tint = ui.textSub, modifier = Modifier.size(40.dp)
                    )
                    Text(
                        when {
                            loadError -> "题库加载失败"
                            src == "wrong" -> "这个筛选下没有错题，继续保持！"
                            else -> "没有符合条件的题目"
                        },
                        color = ui.textSub,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    if (loadError) {
                        GlassButton(
                            onClick = { reloadTick++ },
                            backdrop = backdrop,
                            heightDp = 40.dp,
                            modifier = Modifier.padding(top = 14.dp)
                        ) {
                            Text("重试", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    key = { questions[it].id }
                ) { page ->
                    val q = questions[page]
                    // 切页纵深感：离场页轻微后缩（v2.5.1 简化）
                    Box(
                        Modifier.graphicsLayer {
                            val off = ((page - pagerState.currentPage) -
                                pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
                            val s = 1f - abs(off) * 0.03f
                            scaleX = s
                            scaleY = s
                        }
                    ) {
                        QuestionCard(
                            q = q,
                            ua = details[q.id],
                            index = page + 1,
                            backdrop = backdrop,
                            onCommit = { onCommit(q, it) }
                        )
                    }
                }

                // ---- 底部玻璃操作条 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                        },
                        backdrop = backdrop,
                        icon = AppIcons.ChevronLeft,
                        sizeDp = 44.dp
                    )
                    GlassSlider(
                        value = { (pagerState.currentPage + 1).toFloat() },
                        onValueChange = { v ->
                            scope.launch {
                                pagerState.scrollToPage((v - 1f).roundToInt().coerceIn(0, questions.size - 1))
                            }
                        },
                        valueRange = 1f..questions.size.toFloat().coerceAtLeast(1f),
                        step = 1f,
                        backdrop = backdrop,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp)
                    )
                    GlassIconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage + 1).coerceAtMost(questions.size - 1)
                                )
                            }
                        },
                        backdrop = backdrop,
                        icon = AppIcons.ChevronRight,
                        sizeDp = 44.dp
                    )
                }
            }
        }
    }

    // ---- 题号面板（渲染在 AppRoot 顶层传送门：内容层模糊不波及面板自身） ----
    GlassBottomSheet(
        visible = showPanel,
        backdrop = backdrop,
        onDismiss = { showPanel = false }
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "题目面板",
                    color = ui.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                PanelLegend(ui.correct, "答对")
                PanelLegend(ui.wrong, "答错")
                if (questions.any { it.type == QuestionTypes.SHORT }) {
                    PanelLegend(null, "已答待判")
                }
                PanelLegend(null, "未答")
            }
            val panelGridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                state = panelGridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .padding(vertical = 14.dp)
                    .softVerticalEdges(
                        top = 16.dp, bottom = 22.dp,
                        topScrolledPx = { panelGridState.scrolledFromTopPx() },
                        bottomRemainingPx = { panelGridState.remainingBottomPx() }
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(questions.size) { i ->
                    val q = questions[i]
                    val ua = details[q.id]
                    val judged = judgeAnswer(q, ua)
                    PanelCell(
                        number = i + 1,
                        state = when {
                            judged == true -> 1
                            judged == false -> 2
                            isAnswered(q, ua) -> 3
                            else -> 0
                        },
                        isCurrent = pagerState.currentPage == i,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(i) }
                            showPanel = false
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ---- 护眼提醒弹窗（v2.8.6）：关掉即重新计时，每满 20 分钟可再次触发 ----
    if (showEyeCare) {
        GlassConfirmDialog(
            backdrop = backdrop,
            title = "该让眼睛休息一下啦",
            body = "你已连续刷题 20 分钟。抬头看看 6 米外的远处 20 秒，让眼睛放松一下吧（20-20-20 护眼法则）。",
            confirmText = "好的，继续刷题",
            dismissText = "知道了",
            onConfirm = { showEyeCare = false },
            onDismiss = { showEyeCare = false }
        )
    }
}

/**
 * 题型筛选参数解析（v2.8.4 题型多选）：
 * "all"/空 → null（不限题型）；逗号分隔 → 多题型列表；单个 → 单元素列表。
 * 会话快照 type 字段存同样的逗号串，恢复时按字符串相等比对，无需迁移。
 */
internal fun splitTypeFilter(type: String): List<String>? = when {
    type.isBlank() || type == "all" -> null
    "," in type -> type.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    else -> listOf(type)
}

private suspend fun loadByFilter(
    bank: String,
    src: String,
    type: String,
    cat: String,
    random: Boolean
): List<Question> {
    val types = splitTypeFilter(type)
    return if (src == "wrong") {
        if (types != null && types.size > 1) {
            // 错题特训无多题型 DAO 查询：先按不限题型取活跃错题，再内存过滤（错题量级小）
            ServiceLocator.repo.loadWrongPractice(bank, null, cat.takeIf { it != "all" })
                .filter { it.type in types }
        } else {
            ServiceLocator.repo.loadWrongPractice(
                bank,
                types?.firstOrNull(),
                cat.takeIf { it != "all" }
            )
        }
    } else {
        ServiceLocator.repo.loadPractice(
            bankId = bank,
            category = if (cat == "all") null else cat,
            type = types?.singleOrNull(),
            types = types?.takeIf { it.size > 1 },
            random = random
        )
    }
}

/** 会话是否已刷到最后一题（v2.8.6 口径：到尾即视为本轮结束，下次进入自动补漏/开新一轮）。 */
internal fun sessionAtEnd(s: PracticeSession): Boolean =
    s.ids.isNotEmpty() && s.index >= s.ids.size - 1

/** 顺序刷题补漏轮（v2.8.6）：题目列表 + 累计已覆盖集合（写回会话 covered 字段）。 */
private class CatchUpRound(val questions: List<Question>, val covered: List<Long>)

/**
 * 顺序刷题循环补漏（v2.8.6，用户口径）：上一轮刷到末题即视为本轮结束；
 * 下次进入时新开一轮，只挑「总题单 − covered − 上轮 answers」里没刷过的题
 * （例如先跳到第 200 题往后刷，下轮自动从第 1 题补刷没碰过的；补漏轮也刷完后全覆盖 →
 * 返回 null，调用方回退整单加载 = 开完整新一轮）。
 * 仅顺序模式（order==0）且纯刷题（src=="all"）且筛选参数与上轮一致时适用。
 */
private suspend fun loadCatchUpRound(
    bank: String,
    src: String,
    type: String,
    cat: String,
    order: Int,
    prev: PracticeSession?
): CatchUpRound? {
    if (prev == null || order != 0 || src != "all" || prev.src != "all") return null
    if (prev.bankId != bank || prev.type != type || prev.cat != cat) return null
    if (!sessionAtEnd(prev)) return null
    val covered = prev.covered.toSet() + prev.answers.keys.mapNotNull { it.toLongOrNull() }
    val all = loadByFilter(bank, src, type, cat, random = false)
    val rest = all.filterNot { it.id in covered }
    if (rest.isEmpty()) return null
    return CatchUpRound(rest, covered.toList())
}

/**
 * 会话快照落盘（挂 appScope，静默失败不影响 UI）。
 * - 错题特训（src="wrong"）不落盘：特训进度由 recordAnswer 记账，本就不接续；
 * - 随机小练（src="random"）不落盘：碎片化一次性练习，不污染双槽（v2.10.0）；
 * - 会话绑定题库（bankId），切库后旧会话自然作废；
 * - details 保存完整 UserAnswer（填空文本 / 简答草稿与自评），恢复后原样还原；
 * - covered 保存本轮周期内已覆盖题目累计（v2.8.6 循环补漏）。
 */
internal fun persistSession(
    src: String,
    type: String,
    cat: String,
    order: Int,
    bankId: String,
    questions: List<Question>,
    answers: Map<Long, Int>,
    details: Map<Long, UserAnswer>,
    index: Int,
    covered: List<Long> = emptyList()
) {
    if (questions.isEmpty() || src == "wrong" || src == "random") return
    ServiceLocator.appScope.launch {
        runCatching {
            ServiceLocator.settings.setPracticeSession(
                PracticeSession(
                    src = src,
                    type = type,
                    cat = cat,
                    ids = questions.map { it.id },
                    answers = answers.mapKeys { it.key.toString() },
                    details = details.entries.associate { it.key.toString() to answerJson.encodeToString(it.value) },
                    index = index,
                    bankId = bankId,
                    covered = covered
                ),
                bankId, order, type, cat
            )
        }
    }
}

@Composable
internal fun PanelLegend(color: androidx.compose.ui.graphics.Color?, label: String) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color ?: ui.ink.copy(alpha = 0.12f))
        )
        Text(label, color = ui.textSub, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
    }
}

/** 单张题目卡片：按题型分流（单选/判断 / 多选 / 填空 / 简答）。 */
@Composable
internal fun QuestionCard(
    q: Question,
    ua: UserAnswer?,
    index: Int,
    backdrop: Backdrop,
    onCommit: (UserAnswer) -> Unit
) {
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        cornerRadius = 28.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagChip(displayCategory(q.category, rememberBankName(q.bankId)))
                Spacer(Modifier.width(6.dp))
                QuestionTypeTag(q.type)
                Spacer(Modifier.weight(1f))
                Text("第 $index 题", color = LocalUi.current.textSub, fontSize = 11.sp)
            }
            // v2.8.4：填空题题干由 BlankInlineFields 拆段内嵌渲染，不再重复展示（题目重复 bug）
            if (q.type != QuestionTypes.BLANK) {
                Text(
                    q.text,
                    color = LocalUi.current.text,
                    fontSize = 16.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            // v2.8.5：题目图片（ZIP 导入的带图题库）——题干后、作答区前，小图点按展开
            QuestionImageStrip(q)
            when (q.type) {
                QuestionTypes.MULTI -> MultiSection(q, ua, backdrop, onCommit)
                QuestionTypes.BLANK -> BlankSection(q, ua, backdrop, onCommit)
                QuestionTypes.SHORT -> ShortSection(q, ua, backdrop, onCommit)
                else -> ChoiceSection(q, ua, onCommit)
            }
        }
    }
}

// ---------- 单选 / 判断 ----------

@Composable
private fun ChoiceSection(q: Question, ua: UserAnswer?, onCommit: (UserAnswer) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        q.optionsOrJudge.forEachIndexed { i, opt ->
            val state = if (ua == null) 0
            else when {
                i == q.answer -> 1
                i == ua.picked -> 2
                else -> 0
            }
            OptionRow(
                label = optionLabel(i, q.isJudge),
                text = opt,
                state = state,
                enabled = ua == null,
                onClick = { onCommit(UserAnswer(picked = i)) }
            )
        }
    }
    // v2.8.3：答完判定后解析区丝滑展开（此前 v2.8.0 重构时丢失，用户反馈）；
    // 注意 AnimatedVisibility 必须常驻组合，由 ua 空值变化触发入场动画
    AnimatedVisibility(
        visible = ua != null,
        enter = expandVertically(tween(260)) + fadeIn(tween(220))
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            ResultHeader(
                judgeAnswer(q, ua),
                " · 正确答案：${q.correctAnswerText()}"
            )
            ParseBlock(q.explanation)
        }
    }
}

// ---------- 多选（提交后判定，全对才算对） ----------

@Composable
private fun MultiSection(q: Question, ua: UserAnswer?, backdrop: Backdrop, onCommit: (UserAnswer) -> Unit) {
    val ui = LocalUi.current
    val submitted = ua != null
    var sel by remember(q.id, submitted) { mutableStateOf(setOf<Int>()) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "多选题 · 全对才算对，漏选 / 错选都算错",
            color = ui.textSub, fontSize = 11.sp
        )
        q.options.forEachIndexed { i, opt ->
            val resultState = if (!submitted) -1
            else when {
                i in q.multiAnswerSet -> 1
                ua!!.picked?.let { it and (1 shl i) != 0 } == true -> 2
                else -> 0
            }
            OptionToggleRow(
                label = optionLabel(i, false),
                text = opt,
                selected = if (submitted) {
                    ua!!.picked?.let { it and (1 shl i) != 0 } == true
                } else i in sel,
                resultState = resultState,
                enabled = !submitted,
                onToggle = {
                    sel = if (i in sel) sel - i else sel + i
                }
            )
        }
    }
    if (!submitted) {
        SubmitAnswerButton(
            enabled = sel.isNotEmpty(),
            hint = if (sel.isEmpty()) "提交答案（至少选 1 项）" else "提交答案",
            backdrop = backdrop
        ) {
            val mask = sel.fold(0) { acc, i -> acc or (1 shl i) }
            onCommit(UserAnswer(picked = mask))
        }
    }
    // v2.8.3：提交后判定结果+解析丝滑展开
    AnimatedVisibility(
        visible = submitted,
        enter = expandVertically(tween(260)) + fadeIn(tween(220))
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            ResultHeader(judgeAnswer(q, ua))
            CorrectAnswerLine(q.correctAnswerText())
            ParseBlock(q.explanation)
        }
    }
}

// ---------- 填空（键盘避让由页面根级 imePadding 处理） ----------

@Composable
private fun BlankSection(q: Question, ua: UserAnswer?, backdrop: Backdrop, onCommit: (UserAnswer) -> Unit) {
    val submitted = ua != null
    var texts by remember(q.id, submitted) {
        mutableStateOf(List(q.blankCount.coerceAtLeast(1)) { "" })
    }
    Column(Modifier.padding(top = 14.dp)) {
        // v2.8.3：题干内嵌输入（在 ____ 空位处直接点入），替代第一空/第二空分行
        BlankInlineFields(
            text = q.text,
            values = if (submitted) ua!!.texts else texts,
            onValueChange = { i, v -> texts = texts.toMutableList().also { it[i] = v } },
            enabled = !submitted,
            showResult = submitted,
            answers = q.blankAnswers
        )
        if (!submitted) {
            SubmitAnswerButton(
                enabled = texts.all { it.isNotEmpty() },
                hint = if (texts.all { it.isNotEmpty() }) "提交答案" else "填完所有空后提交",
                backdrop = backdrop
            ) {
                onCommit(UserAnswer(picked = 1, texts = texts))
            }
        }
        // v2.8.3：提交后判定结果+解析丝滑展开
        AnimatedVisibility(
            visible = submitted,
            enter = expandVertically(tween(260)) + fadeIn(tween(220))
        ) {
            Column(Modifier.padding(top = 16.dp)) {
                ResultHeader(judgeAnswer(q, ua))
                CorrectAnswerLine(q.correctAnswerText())
                ParseBlock(q.explanation)
            }
        }
    }
}

// ---------- 简答（自评判分） ----------

@Composable
private fun ShortSection(q: Question, ua: UserAnswer?, backdrop: Backdrop, onCommit: (UserAnswer) -> Unit) {
    val submitted = ua != null
    var draft by remember(q.id, submitted) { mutableStateOf("") }
    Column(Modifier.padding(top = 14.dp)) {
        ShortDraftField(
            value = if (submitted) ua!!.text else draft,
            onValueChange = { draft = it },
            enabled = !submitted
        )
        if (!submitted) {
            SubmitAnswerButton(
                enabled = draft.isNotBlank(),
                hint = "提交，看参考答案",
                backdrop = backdrop
            ) {
                // 提交草稿：已答但正确性未定（自评后才记账）
                onCommit(UserAnswer(picked = 1, text = draft, graded = false))
            }
            Text(
                "提交后对照参考答案自评，结果计入正确率",
                color = LocalUi.current.textSub, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            ShortReferenceCard(
                reference = q.answerText,
                yourText = ua!!.text,
                graded = ua.graded,
                selfCorrect = if (ua.graded) ua.picked == 1 else null,
                onGrade = { correct ->
                    onCommit(ua.copy(picked = if (correct) 1 else 0, graded = true))
                }
            )
            // v2.8.3：自评后判定+解析丝滑展开
            AnimatedVisibility(
                visible = ua.graded,
                enter = expandVertically(tween(260)) + fadeIn(tween(220))
            ) {
                Column(Modifier.padding(top = 10.dp)) {
                    ResultHeader(judgeAnswer(q, ua), " · 自评")
                    ParseBlock(q.explanation)
                }
            }
        }
    }
}

/**
 * 题号面板格子。state：0 未答 / 1 答对 / 2 答错 / 3 已答待判（简答自评前）
 */
@Composable
private fun PanelCell(number: Int, state: Int, isCurrent: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                when (state) {
                    1 -> ui.correct.copy(alpha = 0.2f)
                    2 -> ui.wrong.copy(alpha = 0.2f)
                    3 -> ui.ink.copy(alpha = 0.16f)
                    else -> ui.ink.copy(alpha = 0.06f)
                }
            )
            .then(
                if (isCurrent) Modifier.border(2.dp, ui.ink, CircleShape)
                else Modifier.border(1.dp, ui.ink.copy(alpha = 0.08f), CircleShape)
            )
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$number",
            color = when (state) {
                1 -> ui.correct
                2 -> ui.wrong
                else -> ui.text
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

