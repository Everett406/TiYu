package com.drone.quiz.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.Repo
import com.drone.quiz.data.db.BankEntity
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.screens.common.scrolledFromTopPx
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.onboarding.onboardingAnchor
import com.drone.quiz.ui.glass.GlassAnchorMenu
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.glass.BounceLazyColumn
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.core.animateDpAsState

import androidx.compose.foundation.layout.offset


@Composable
fun HomeScreen(
    backdrop: Backdrop,
    onPractice: () -> Unit,
    onExam: () -> Unit,
    onWrong: () -> Unit,
    onSettings: () -> Unit
) {
    val ui = LocalUi.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by ServiceLocator.settings.settings.collectAsState(initial = AppSettings())

    data class HomeStats(
        val total: Int = 0,
        val answeredDistinct: Int = 0,
        val totalAnswered: Int = 0,
        val wrongCount: Int = 0,
        val accuracy: Float = 0f,
        val streak: Int = 0,
        val days: List<Repo.DayStat> = emptyList(),
        val exams: List<com.drone.quiz.data.db.ExamRecordEntity> = emptyList(),
        val todayAnswered: Int = 0,
        val todayCorrect: Int = 0,
        // 打卡热力图：近 154 天「日期 / 当日题量 / 当日答对」，全局数据（不按题库隔离）
        val heat: List<com.drone.quiz.data.repo.Repo.HeatDay> = emptyList(),
        val lastExam: com.drone.quiz.data.db.ExamRecordEntity? = null
    )

    var stats by remember { mutableStateOf(HomeStats()) }
    // v2.8.0：统计按当前题库隔离（v2.8.2：今日/近 7 天也改为按库；打卡连击为全局习惯数据）
    LaunchedEffect(settings.currentBank) {
        val bank = settings.currentBank
        combine(
            ServiceLocator.repo.countByBankFlow(bank),
            ServiceLocator.repo.answeredDistinctFlow(bank),
            ServiceLocator.repo.totalAnsweredFlow(),
            ServiceLocator.repo.activeWrong(bank).map { it.size },
            ServiceLocator.repo.recentScoredExams(bank, 8)
        ) { total, distinct, answered, wrong, exams ->
            HomeStats(
                total = total,
                answeredDistinct = distinct,
                totalAnswered = answered,
                wrongCount = wrong,
                lastExam = exams.firstOrNull(),
                exams = exams
            )
        }.collect { s0 ->
            val acc = ServiceLocator.repo.accuracy(bank)
            val streak = ServiceLocator.repo.streakDays()
            // 今日/近 7 天按题库隔离（practice_records JOIN questions），连击仍全局
            val (days, todayAns, todayCor) = runCatching {
                ServiceLocator.repo.last7DaysByBank(bank)
            }.getOrElse { Triple(ServiceLocator.repo.last7Days(), 0, 0) }
            stats = s0.copy(
                accuracy = acc,
                streak = streak,
                days = days,
                todayAnswered = todayAns,
                todayCorrect = todayCor,
                heat = runCatching { ServiceLocator.repo.heatmapDays(160) }
                    .getOrElse { emptyList() }
            )
        }
    }

    val coverage = if (stats.total > 0) stats.answeredDistinct.toFloat() / stats.total else 0f

    // 预估通过率（v2.19.48 重写，见 data/PassEstimate.kt）。
    //
    // 旧式只有一行：累计刷题正确率 ÷ 及格分，再和「刷题覆盖率」三七分。
    // 五个问题都在那里：压根没引用模考成绩、每题一票不分卷子大小、
    // 不分题型（把判断题占比调高去刷分会跟着涨）、没有时间衰减，
    // 以及「刷了多少题」占 30% 权重——练得多不等于练得好。
    //
    // 现在：模考为主（按题数累积 + 时间衰减）、题型按题库真实配比校正、
    // 样本少时向先验收缩、刷题按**去重题数**折权作辅助。
    val estBank = settings.currentBank
    var passResult by remember(estBank, settings.passScore) {
        mutableStateOf<com.drone.quiz.data.PassEstimate.Result?>(null)
    }
    LaunchedEffect(estBank, settings.passScore) {
        passResult = runCatching {
            val inp = ServiceLocator.repo.passInputs(estBank)
            com.drone.quiz.data.PassEstimate.compute(
                exams = inp.exams,
                bankTypeMix = inp.bankTypeMix,
                passLine = settings.passScore,
                practiceAcc = inp.practiceAcc,
                practiceN = inp.practiceDistinct,
                coverage = inp.coverage,
                now = System.currentTimeMillis()
            )
        }.getOrNull()
    }
    val estimate = (passResult?.estimate ?: 0f).coerceIn(0f, 1f)

    // 打卡里程碑：3/7/14/21/30/50/100 天逐级递进
    val milestone = listOf(3, 7, 14, 21, 30, 50, 100).firstOrNull { it > stats.streak } ?: (stats.streak + 10)
    val streakHint = when {
        stats.streak == 0 -> "今天开刷，把连击续上"
        stats.streak < 3 -> "开局顺利，再接再厉"
        stats.streak < 7 -> "手感正热，冲一周连击"
        else -> "稳定输出，保持节奏"
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ---- 固定标题（问候语 + 昵称，不随滚动；内容滚入时在下缘柔化渐隐） ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
                val greeting = when (hour) {
                    in 5..10 -> "早上好"
                    in 11..12 -> "中午好"
                    in 13..17 -> "下午好"
                    else -> "晚上好"
                }
                Text(
                    // 默认不取名（用户反馈"机长"出戏）：未设置昵称时只按时间问候
                    settings.nickname.trim().takeIf { it.isNotEmpty() }
                        ?.let { "$greeting，$it" } ?: greeting,
                    color = ui.text, fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                // v2.8.0：副标题 = 当前题库切换入口（v2.8.2 起为玻璃锚点小菜单）
                BankSwitchChip(
                    backdrop = backdrop,
                    currentBankId = settings.currentBank,
                    onPick = { id ->
                        if (id != settings.currentBank) {
                            scope.launch { runCatching { ServiceLocator.settings.setCurrentBank(id) } }
                        }
                    },
                    onManage = onSettings
                )
            }
            GlassIconButton(
                onClick = onSettings,
                backdrop = backdrop,
                icon = AppIcons.Tune
            )
        }

        // 滚离顶部才渐显，停在顶部时无效果、进度环等首屏内容不被遮挡
        val homeListState = rememberLazyListState()
        BounceLazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            listState = homeListState
        ) {
            // ---- 总览：进度环 + 预估通过率 ----
        // 顶部留 6dp：玻璃卡上溢阴影不再被容器上缘/问候语区域裁切（用户反馈）
        item {
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp)
                    .fillMaxWidth()
                    .onboardingAnchor("home_overview")
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProgressRing(
                        progress = coverage,
                        sizeDp = 96.dp,
                        strokeDp = 10.dp,
                        ringColor = ui.ink,
                        trackColor = ui.ink.copy(alpha = 0.1f),
                        centerText = "${(coverage * 100).toInt()}%",
                        subText = "总进度"
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已刷 ${stats.answeredDistinct} / ${stats.total} 题",
                            color = ui.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "总正确率 ${(stats.accuracy * 100).toInt()}%",
                            color = ui.textSub,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        // 预估通过率条
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            Text(
                                "预估通过率",
                                color = ui.textSub,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(ui.ink.copy(alpha = 0.1f))
                            ) {
                                val barProgress by animateFloatAsState(
                                    estimate,
                                    tween(700), label = "estimate"
                                )
                                Box(
                                    Modifier
                                        .fillMaxWidth(barProgress)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(ui.accent, ui.accent.copy(alpha = 0.7f))
                                            )
                                        )
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${(estimate * 100).toInt()}%",
                                color = ui.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // v2.19.48：把估计的**依据**摊开。
                        // 一个凭空而来的百分数最容易被误解成「定论」，
                        // 而题数少的时候它本来就只是参考——写清楚来源与
                        // 样本量，用户才知道该信几分、什么时候会变。
                        passResult?.let { r ->
                            if (!r.coldStart) {
                                Text(
                                    r.basis(settings.passScore),
                                    color = ui.textSub.copy(alpha = 0.85f),
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(top = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 打卡单卡：连击 + 今日 + 13 周热力图 ----
        // v2.19.7 重构：原先「连击卡 + 今日卡」并排两半，热力图塞不进半屏宽度，
        // 故合并为一张全宽卡 —— 顶行两个数字，中部热力图，底行脚注。
        // 同时按用户裁定去掉了里程碑进度条（那是倒计时桩，热力图已接管
        // 「我这阵子状态如何」这件事），只保留脚注文字。
        item {
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                cornerRadius = 22.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    // ---- 顶行：连击（左） / 今日（右）----
                    // v2.19.10 信息重排。此前用户反馈"今日四十题，百分之七十"未明何意、
                    // "答对二十八·答错十二"亦未解其义：70% 悬着没有标签，答对/答错也没说
                    // 是哪一天的；而这三条其实是同一份数据（28+12=40，28/40=70%），
                    // 摆两遍还都不说清，反而添乱。
                    // 现改为左右**三行同构**：各「一行小标签 + 一行大数字 + 一行注解」，
                    // 注解自带单位与主语（"再练 4 天…"挂在连击下，"正确率 70%"挂在今日下），
                    // 底部脚注只留一句带主语的"今日答对…"，图例挪到同一行右侧。
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Flame, null, tint = ui.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "连击",
                                    color = ui.textSub, fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            Text(
                                "${stats.streak} 天",
                                color = ui.text, fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                if (stats.streak < milestone) {
                                    "再练 ${milestone - stats.streak} 天达成 $milestone 天"
                                } else {
                                    streakHint
                                },
                                color = ui.textSub, fontSize = 10.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "今日",
                                    color = ui.textSub, fontSize = 11.sp,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Icon(
                                    AppIcons.Check, null, tint = ui.correct,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                "${stats.todayAnswered} 题",
                                color = ui.text, fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            // 主语 + 单位都写全：0 题时不显示（0 题不存在正确率）
                            Text(
                                if (stats.todayAnswered > 0) {
                                    "正确率 ${(stats.todayCorrect * 100) / stats.todayAnswered}%"
                                } else {
                                    "还没开始"
                                },
                                color = if (stats.todayAnswered > 0) ui.correct else ui.textSub,
                                fontSize = 10.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }

                    // ---- 18 周热力图（全局打卡数据，与连击同源）----
                    StreakHeatmap(
                        days = stats.heat,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )

                    // ---- 脚注：主语写全的答对/答错 + 图例同处一行 ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (stats.todayAnswered > 0) {
                                "今日答对 ${stats.todayCorrect} · 答错 ${stats.todayAnswered - stats.todayCorrect}"
                            } else {
                                "今天还没开始，来几题热热手"
                            },
                            color = ui.textSub, fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        StreakHeatmapLegend()
                    }
                }
            }
        }

        // ---- 图表卡：近 7 天练习 ↔ 模考走势（v2.19.50 可切换，共用同一张图）----
        item {
            var examMode by remember(settings.currentBank) { mutableStateOf(false) }
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (examMode) "模考走势" else "近 7 天练习",
                            color = ui.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        // v2.19.53：切换按钮挪到标题行右侧。
                        // 原先它独占一整行（下面那个 132dp 的分段开关），
                        // 而这卡横向宽度本来就吃紧——用户明确要求收成一个按钮。
                        ChartModeSwitch(
                            examMode = examMode,
                            onChange = { examMode = it }
                        )
                    }
                    // v2.19.53：图例让位给切换按钮，下移一行。
                    // 图例：柱的含义随模式切换（题量 / 得分），折线始终是正确率
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ui.ink)
                        )
                        Text(
                            if (examMode) " 得分  " else " 题量  ",
                            color = ui.textSub, fontSize = 11.sp
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ui.accent)
                        )
                        Text(" 正确率", color = ui.textSub, fontSize = 11.sp)
                    }

                    val examPoints = stats.exams.toExamChartPoints()
                    val points = if (examMode) examPoints else stats.days.toChartPoints()
                    if (points.isEmpty()) {
                        Text(
                            if (examMode) "还没有模考记录，考一场就能看到走势" else "近 7 天还没有练习记录",
                            color = ui.textSub, fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                                .height(140.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Chart(
                            points = points,
                            barColor = ui.ink,
                            lineColor = ui.accent,
                            trackColor = ui.ink.copy(alpha = 0.08f),
                            textColor = ui.textSub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .height(140.dp)
                        )
                    }
                }
            }
        }

        // ---- 快捷操作 ----
        item {
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButton(
                    onClick = onPractice,
                    backdrop = backdrop,
                    surfaceColor = ui.ink,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AppIcons.Cards, null, tint = ui.onInk, modifier = Modifier.size(18.dp))
                    Text("继续刷题", color = ui.onInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                GlassButton(
                    onClick = onExam,
                    backdrop = backdrop,
                    surfaceColor = ui.surface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AppIcons.Timer, null, tint = ui.text, modifier = Modifier.size(18.dp))
                    Text("模拟考试", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---- 错题提醒 / 上次模考：同规格信息卡（间距/圆角统一，不再贴叠） ----
        if (stats.wrongCount > 0) {
            item {
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    cornerRadius = 22.dp,
                    onClick = onWrong
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.BookWrong, null, tint = ui.wrong, modifier = Modifier.size(20.dp))
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        ) {
                            Text(
                                "错题本还有 ${stats.wrongCount} 题",
                                color = ui.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("去复习巩固，答对自动移除", color = ui.textSub, fontSize = 12.sp)
                        }
                        Icon(AppIcons.ChevronRight, null, tint = ui.textSub, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // ---- 上次模考 ----
        stats.lastExam?.let { exam ->
            if (exam.score != null) {
                item {
                    GlassCard(
                        backdrop = backdrop,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .fillMaxWidth(),
                        cornerRadius = 22.dp,
                        onClick = onExam
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(AppIcons.Timer, null, tint = ui.textSub, modifier = Modifier.size(20.dp))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    "上次模考 ${exam.score!!.toInt()} 分",
                                    color = ui.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                                        .format(Date(exam.finishedAt ?: exam.startedAt)),
                                    color = ui.textSub, fontSize = 12.sp
                                )
                            }
                            TagPass(exam.passed == true)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(130.dp)) }
    }
        }
}

@Composable
private fun TagPass(passed: Boolean) {
    val ui = LocalUi.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background((if (passed) ui.correct else ui.wrong).copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            if (passed) "通过" else "未通过",
            color = if (passed) ui.correct else ui.wrong,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 进度环。
 */
@Composable
fun ProgressRing(
    progress: Float,
    sizeDp: androidx.compose.ui.unit.Dp,
    strokeDp: androidx.compose.ui.unit.Dp,
    ringColor: Color,
    trackColor: Color,
    centerText: String,
    subText: String
) {
    val ui = LocalUi.current
    val animated by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        spring(dampingRatio = 0.85f, stiffness = 60f),
        label = "ring"
    )
    Box(
        Modifier.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeDp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, color = ui.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subText, color = ui.textSub, fontSize = 10.sp)
        }
    }
}

/**
 * 图表的一个数据点（v2.19.50 泛化）。
 *
 * 原先 [WeekChart] 只吃 [Repo.DayStat]，于是「近 7 天练习」和
 * 「模考走势」得各画一份。这里把两种来源都归一成同一种形状，
 * 底下的绘制逻辑一行都不用改——用户要的正是"不创作新图表，直接切换"。
 */
data class ChartPoint(
    val label: String,      // X 轴标签
    val bar: Float,         // 柱状值（题量 / 得分）
    val line: Float,        // 折线值，0..1（正确率）
    val barZero: Boolean = false,   // 柱为 0 → 淡显
    val emphasize: Boolean = false   // 高亮（今天 / 最近一次）
)

/** 近 7 天练习 → 图表数据点 */
fun List<Repo.DayStat>.toChartPoints(): List<ChartPoint> = map {
    ChartPoint(
        label = it.label,
        bar = it.answered.toFloat(),
        line = if (it.answered > 0) it.correct.toFloat() / it.answered else 0f,
        barZero = it.answered == 0,
        emphasize = it.isToday
    )
}

/** 最近若干次模考 → 图表数据点（时间正序，越靠右越新） */
fun List<com.drone.quiz.data.db.ExamRecordEntity>.toExamChartPoints(): List<ChartPoint> =
    asReversed().filter { it.score != null }.map { e ->
        val sc = e.score!!
        ChartPoint(
            label = shortDate(e.startedAt),
            // 得分与正确率同向（分数就是按正确率算的），柱与线不会互相打架
            bar = sc,
            line = (sc / 100f).coerceIn(0f, 1f),
            barZero = sc <= 0f,
            emphasize = false
        )
    }

/** MM/DD（同年不写年） */
private fun shortDate(ts: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val m = c.get(java.util.Calendar.MONTH) + 1
    val d = c.get(java.util.Calendar.DAY_OF_MONTH)
    val y = c.get(java.util.Calendar.YEAR)
    val now = java.util.Calendar.getInstance()
    return if (y == now.get(java.util.Calendar.YEAR)) "$m/$d" else "$y/$m"
}

/**
 * 柱状图 + 折线（Canvas 自绘）。
 * 两种模式共用：**近 7 天练习**（柱=题量）与**模考走势**（柱=得分）。
 */
@Composable
fun Chart(
    points: List<ChartPoint>,
    barColor: Color,
    lineColor: Color,
    trackColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val n = points.size
        val labelSpace = labelPx * 2.2f
        val chartH = size.height - labelSpace
        val slot = size.width / n
        val barW = slot * 0.42f
        val maxV = points.maxOf { it.bar }.coerceAtLeast(10f)

        // 柱
        points.forEachIndexed { i, d ->
            val h = chartH * 0.82f * (d.bar / maxV)
            val left = slot * i + (slot - barW) / 2
            val top = chartH - h
            drawRoundRect(
                color = if (d.emphasize) barColor else barColor.copy(alpha = if (d.barZero) 0.12f else 0.32f),
                topLeft = Offset(left, top),
                size = Size(barW, h.coerceAtLeast(3f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2)
            )
        }

        // 正确率折线
        val linePts = points.mapIndexed { i, d ->
            Offset(slot * i + slot / 2, chartH - chartH * 0.82f * d.line - chartH * 0.02f)
        }
        if (points.any { !it.barZero }) {
            val path = androidx.compose.ui.graphics.Path().apply {
                linePts.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x, p.y) else cubicTo(
                        (linePts[i - 1].x + p.x) / 2, linePts[i - 1].y,
                        (linePts[i - 1].x + p.x) / 2, p.y,
                        p.x, p.y
                    )
                }
            }
            drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            linePts.forEachIndexed { i, p ->
                if (!points[i].barZero) {
                    drawCircle(lineColor, 3.5f, p)
                    drawCircle(Color.White, 1.5f, p)
                }
            }
        }

        // 星期标签
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(
                (textColor.alpha * 255).toInt(),
                (textColor.red * 255).toInt(),
                (textColor.green * 255).toInt(),
                (textColor.blue * 255).toInt()
            )
            textSize = labelPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        points.forEachIndexed { i, d ->
            drawContext.canvas.nativeCanvas.drawText(
                d.label,
                slot * i + slot / 2,
                size.height - labelPx * 0.4f,
                textPaint
            )
        }
    }
}

/**
 * 图表模式切换（v2.19.50）：练习 / 模考。
 *
 * 用紧凑的滑动胶囊而不是 [SegmentedRow]——后者 fillMaxWidth，
 * 放进卡片标题行会显得头重脚轻，这里只需要占标题宽度的一半。
 */
@Composable
private fun ChartModeSwitch(
    examMode: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val ui = LocalUi.current
    // v2.19.53：从「两段滑动开关」改成**单个按钮，点一下翻到另一个**。
    // 用户口径：两个并排的分段开关要占 132dp，这卡的横向宽度不够，
    // 挤到图例甚至挤到标题。所以只留一个按钮，显示当前模式，
    // 点一下在「练习 ⇄ 模考」之间来回切。
    val label = if (examMode) "模考" else "练习"
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(ui.ink)
            .clickable(
                interactionSource = null,
                indication = null
            ) { onChange(!examMode) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (examMode) AppIcons.Timer else AppIcons.Cards,
            null,
            tint = ui.onInk,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = ui.onInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 首页当前题库切换入口（v2.8.0）：
 * 副标题位置展示「每日精进 · 当前题库名 ▾」，点击弹出右键菜单式小窗：
 * 题库列表（✓ 标记当前项）+「管理题库」入口。
 * v2.8.2：改用 GlassAnchorMenu（传送门液态玻璃 + 入场动画，替代 Popup 原生 surface——
 * 用户反馈"不像玻璃、没动画"）；文本左缘与问候语对齐（去掉胶囊内水平 padding）。
 */
@Composable
private fun BankSwitchChip(
    backdrop: Backdrop,
    currentBankId: String,
    onPick: (String) -> Unit,
    onManage: () -> Unit
) {
    val ui = LocalUi.current
    var open by remember { mutableStateOf(false) }
    var banks by remember { mutableStateOf<List<com.drone.quiz.data.db.BankEntity>>(emptyList()) }
    // 锚点（窗口坐标 px）：玻璃菜单出现在副标题正下方
    var anchorX by remember { mutableFloatStateOf(0f) }
    var anchorY by remember { mutableFloatStateOf(0f) }
    var anchorH by remember { mutableFloatStateOf(0f) }

    // 题库列表：打开时拉取一次即可（删除/导入在设置页完成，回来再开窗会重拉）
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) {
            runCatching {
                ServiceLocator.repo.bankListWithCounts().map { it.first }
            }.getOrNull()?.let { banks = it }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 4.dp)
            .onGloballyPositioned { coords ->
                anchorX = coords.positionInWindow().x
                anchorY = coords.positionInWindow().y
                anchorH = coords.size.height.toFloat()
            }
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = null, indication = null) { open = true }
            .padding(vertical = 2.dp)
    ) {
        Text(
            "每日精进 · ",
            // v2.8.7 页面级裸文字坐壁纸，改自适应色
            color = com.drone.quiz.ui.theme.readableSubColor(), fontSize = 12.sp
        )
        Text(
            banks.firstOrNull { it.id == currentBankId }?.name ?: "题库",
            color = com.drone.quiz.ui.theme.readableSubColor(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        Text(
            " ▾",
            color = com.drone.quiz.ui.theme.readableSubColor(), fontSize = 11.sp
        )
    }

    GlassAnchorMenu(
        visible = open,
        onDismiss = { open = false },
        anchorXpx = anchorX,
        anchorYpx = anchorY,
        anchorHeightPx = anchorH,
        backdrop = backdrop
    ) {
        if (banks.isEmpty()) {
            Text(
                "加载中…",
                color = ui.textSub, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        banks.forEach { b ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) {
                        open = false
                        onPick(b.id)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        b.name,
                        color = ui.text,
                        fontSize = 14.sp,
                        fontWeight = if (b.id == currentBankId) FontWeight.Bold else FontWeight.Medium
                    )
                }
                if (b.id == currentBankId) {
                    Icon(
                        AppIcons.Check, null,
                        tint = ui.correct, modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .height(1.dp)
                .background(ui.ink.copy(alpha = 0.08f))
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = null) {
                    open = false
                    onManage()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                AppIcons.Tune, null,
                tint = ui.textSub, modifier = Modifier.size(15.dp)
            )
            Text(
                "管理题库",
                color = ui.text, fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
