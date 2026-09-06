package com.drone.quiz.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.data.settings.PracticeSession
import com.drone.quiz.data.repo.QuestionTypes
import com.drone.quiz.screens.common.ScreenTitle
import com.drone.quiz.screens.common.SectionLabel
import com.drone.quiz.screens.common.SegmentedRow
import com.drone.quiz.screens.common.heroSearchField
import com.drone.quiz.screens.common.scrolledFromTopPx
import com.drone.quiz.screens.common.softTopFade
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.onboarding.onboardingAnchor
import com.drone.quiz.ui.glass.BounceContainer
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassConfirmDialog
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.ui.theme.readableSubColor
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 刷题配置入口页（刷题 Tab 首页）。
 * 进入刷题 Tab 不再直接开刷：先选题型范围，点「开始刷题」进入全屏刷题页。
 * v2.8.3：分类筛选移除（切题库即换内容，分类维度冗余，用户口径）；题型 chips 自动换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeConfigScreen(
    backdrop: Backdrop,
    onSearch: () -> Unit = {},
    onStart: (src: String, type: String, cat: String, resume: Boolean) -> Unit
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val settings by ServiceLocator.settings.settings
        .collectAsState(initial = AppSettings())

    // 题型筛选（v2.8.0 自适应：题型选项 = 当前题库实际拥有的题型）
    // v2.8.4 改多选（用户口径：保留「全部」，其余可任意组合，不再单选）：
    // 空集 = 全部；开始刷题时按规范序编码为逗号串（"single,judge"），下游 splitTypeFilter 解析
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var types by remember { mutableStateOf<List<String>>(emptyList()) }
    // 当前题型筛选参数（多选规范序逗号串；空集 = all）——既是开考参数，也是 v2.11.0 会话槽位键的一部分
    val typeArgNow = QuestionTypes.canonicalOrder
        .filter { it in selectedTypes }.joinToString(",").ifEmpty { "all" }
    // 当前上下文的会话快照，响应式——切题库/换题型范围/切模式都会切到各自的槽；
    // 也用于“重新开始”按钮与二次确认（v2.7.4）。
    // v2.11.0 多槽：按「题库+模式+范围」精确取槽，互不覆盖（旧版单槽：切库开刷即把原库进度覆盖销毁）
    var showResetConfirm by remember { mutableStateOf(false) }
    val sessionFlow = remember(settings.currentBank, settings.practiceOrder, typeArgNow) {
        ServiceLocator.settings.practiceSession(settings.currentBank, settings.practiceOrder, typeArgNow, "all")
    }
    val lastSession by sessionFlow.collectAsState(initial = null)
    var total by remember { mutableIntStateOf(0) }
    var accuracy by remember { mutableIntStateOf(0) }
    var wrongCount by remember { mutableIntStateOf(0) }
    var todayAnswered by remember { mutableIntStateOf(0) }

    // v2.8.2 修复题库隔离 bug：原先 LaunchedEffect(Unit) 在首帧读到的是 collectAsState 的
    // 默认值 currentBank="drone" 且永不重跑 → 切库后分类/概览永远加载旧题库。
    // 改为持续收集 settings（按 currentBank 去重）：首发射就是 DataStore 真值，切库后自动重载。
    LaunchedEffect(Unit) {
        ServiceLocator.settings.settings
            .distinctUntilChangedBy { it.currentBank }
            .collectLatest { st ->
                val bank = st.currentBank
                runCatching { types = ServiceLocator.repo.typesInBank(bank) }
                runCatching { total = ServiceLocator.repo.bankCount(bank) }
                runCatching { accuracy = (ServiceLocator.repo.accuracy(bank) * 100).toInt() }
                runCatching { wrongCount = ServiceLocator.repo.wrongCount(bank) }
                // 今日已刷同步按题库隔离（v2.8.2）；连击/近 7 天为全局习惯数据
                runCatching {
                    val (_, today, _) = ServiceLocator.repo.last7DaysByBank(bank)
                    todayAnswered = today
                }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ---- 固定标题 + 搜索入口（不随滚动） ----
        Column(Modifier.padding(horizontal = 20.dp)) {
            ScreenTitle("刷题", "选择范围，开始你的练习", Modifier.padding(vertical = 16.dp))
            GlassCard(
                backdrop = backdrop,
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .heroSearchField()
                    .onboardingAnchor("practice_search"),
                cornerRadius = 22.dp,
                onClick = onSearch
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        AppIcons.Search, null,
                        tint = ui.textSub, modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "搜索题目 / 选项 / 解析",
                        color = ui.textSub, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        AppIcons.ChevronRight, null,
                        tint = ui.textSub, modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 标题柔化：draw 阶段直读滚动像素（Modifier 稳定无伪影，滑出渐显跟手）
        val cfgScroll = rememberScrollState()
        BounceContainer(
            Modifier
                .weight(1f)
                .softTopFade(36.dp) { cfgScroll.scrolledFromTopPx() }
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(cfgScroll)
                .padding(horizontal = 20.dp)
        ) {

            // ---- 题型范围（自适应：只列出当前题库拥有的题型；v2.8.3 改自动换行） ----
            SectionLabel("题目范围")
            GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        when {
                            selectedTypes.isEmpty() -> "全部题型"
                            else -> "只刷" + QuestionTypes.canonicalOrder
                                .filter { it in selectedTypes }
                                .joinToString("、") { QuestionTypes.label(it) } + "题"
                        },
                        color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                    // v2.8.3：横向滚动 → FlowRow 自动换行（题型凑满时不用左右滑，用户反馈）
                    // v2.8.4：单选 → 多选；全选时自动归位到「全部」
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConfigChip("全部", selectedTypes.isEmpty()) { selectedTypes = emptySet() }
                        types.forEach { t ->
                            ConfigChip(
                                QuestionTypes.label(t),
                                t in selectedTypes
                            ) {
                                val next =
                                    if (t in selectedTypes) selectedTypes - t
                                    else selectedTypes + t
                                selectedTypes =
                                    if (types.isNotEmpty() && next.size == types.size) emptySet()
                                    else next
                            }
                        }
                    }
                }
            }

            // ---- 顺序 ----
            SectionLabel("题目顺序", Modifier.padding(top = 14.dp))
            GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        if (settings.practiceOrder == 1) "随机顺序" else "题库顺序",
                        color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                    SegmentedRow(
                        options = listOf("顺序", "随机"),
                        selectedIndex = settings.practiceOrder,
                        onSelect = { scope.launch { ServiceLocator.settings.setPracticeOrder(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }

            // ---- 开始刷题 ----
            GlassButton(
                onClick = {
                    // 多选编码为逗号串：空集 = "all"；规范序保证快照匹配稳定（v2.8.4）
                    val typeArg = QuestionTypes.canonicalOrder
                        .filter { it in selectedTypes }
                        .joinToString(",")
                        .ifEmpty { "all" }
                    onStart("all", typeArg, "all", false)
                },
                backdrop = backdrop,
                surfaceColor = ui.ink,
                heightDp = 54.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp)
                    .onboardingAnchor("practice_start")
            ) {
                Icon(AppIcons.Cards, null, tint = ui.onInk, modifier = Modifier.size(18.dp))
                Text(
                    "开始刷题",
                    color = ui.onInk,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ---- 续刷提示：当前上下文有未完成进度时展示；"重新开始"胶囊按钮 + 二次确认（v2.7.4） ----
            val snap = lastSession
            // v2.8.8：切库隔离双保险——读口已按「题库+范围」精确取槽，这里再显式校验一次
            // 防「523/2000」这种异库旧进度漏进续刷提示
            val snapReusable = snap != null && snap.src == "all" &&
                snap.bankId == settings.currentBank &&
                snap.type == typeArgNow && snap.cat == "all" &&
                // v2.8.6：刷到末题即视为本轮结束（不再提示接续，进入后自动补漏/开新一轮）
                !sessionAtEnd(snap) && (snap.index > 0 || snap.answers.isNotEmpty())
            if (snap != null && snapReusable) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "将接续上次进度 · 第 ${(snap.index + 1).coerceAtMost(snap.ids.size)} / ${snap.ids.size} 题",
                        // v2.8.7 页脚小字直接坐在壁纸上，改自适应色（壁纸翻暗提亮）
                        color = readableSubColor(), fontSize = 12.sp
                    )
                    // 重新设计：浅红底描边胶囊（原裸文字偏丑），点击弹二次确认，不再一键直清
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ui.wrong.copy(alpha = 0.10f))
                            .border(1.dp, ui.wrong.copy(alpha = 0.30f), RoundedCornerShape(50))
                            .clickable { showResetConfirm = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "重新开始",
                            color = ui.wrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ---- 学习概览（填充页面 + 有用信息） ----
            SectionLabel("学习概览")
            GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 18.dp)
                ) {
                    OverviewCell("题库", "$total 题", Modifier.weight(1f))
                    OverviewCell("今日已刷", "$todayAnswered 题", Modifier.weight(1f))
                    OverviewCell("总正确率", "$accuracy%", Modifier.weight(1f))
                    OverviewCell("待复习", "$wrongCount 题", Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(130.dp))
        }
        
}
    }

    // 重新开始二次确认：双保险，防误触一键清进度（v2.7.4）
    if (showResetConfirm) {
        GlassConfirmDialog(
            backdrop = backdrop,
            title = "重新开始？",
            body = "将清空${if (settings.practiceOrder == 1) "随机" else "顺序"}模式的本次刷题进度（含作答记录），下次从开头开始。此操作不可撤销。",
            confirmText = "清空重开",
            dismissText = "取消",
            confirmColor = ui.wrong,
            onConfirm = {
                showResetConfirm = false
                scope.launch {
                    runCatching {
                        // v2.11.0 多槽：只清当前「题库+模式+范围」这一份进度，其他槽不动
                        ServiceLocator.settings.setPracticeSession(
                            null, settings.currentBank, settings.practiceOrder, typeArgNow, "all"
                        )
                    }
                }
            },
            onDismiss = { showResetConfirm = false }
        )
    }
}

@Composable
private fun OverviewCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = LocalUi.current.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = LocalUi.current.textSub,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun ConfigChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) ui.ink else ui.ink.copy(alpha = 0.06f))
            .border(
                1.dp,
                if (selected) ui.ink else ui.ink.copy(alpha = 0.12f),
                RoundedCornerShape(50)
            )
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (selected) ui.onInk else ui.textSub,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
