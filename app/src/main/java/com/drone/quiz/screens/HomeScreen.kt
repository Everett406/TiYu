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
        val todayAnswered: Int = 0,
        val todayCorrect: Int = 0,
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
            ServiceLocator.repo.recentExams(bank)
        ) { total, distinct, answered, wrong, exams ->
            HomeStats(
                total = total,
                answeredDistinct = distinct,
                totalAnswered = answered,
                wrongCount = wrong,
                lastExam = exams.firstOrNull()
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
                todayCorrect = todayCor
            )
        }
    }

    val coverage = if (stats.total > 0) stats.answeredDistinct.toFloat() / stats.total else 0f
    // 预估通过率：参考设置中的及格线 —— 正确率相对及格分的达标程度为主，覆盖率加权
    // （此前固定阈值不随及格分变化，用户改及格分后无反馈）
    val passRatio = (stats.accuracy / (settings.passScore.coerceAtLeast(1) / 100f)).coerceIn(0f, 1f)
    val estimate = (passRatio * 0.7f + coverage * 0.3f).coerceIn(0f, 1f)

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
                    }
                }
            }
        }

        // ---- 打卡双卡（连击里程碑 + 今日；IntrinsicSize 等高 + 完全同构排版） ----
        // v2.7.1 统一：两卡同为"图标+标签 / 大数字 / 副行 / 进度条 / 底注"五行结构，
        // 字号、间距、条高逐行对齐，仅图标与条色按语义区分（用户反馈排版凌乱）
        item {
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    cornerRadius = 22.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                AppIcons.Flame,
                                null,
                                tint = ui.accent,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "连击",
                                color = ui.textSub,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 5.dp)
                            )
                        }
                        Text(
                            "${stats.streak} 天",
                            color = ui.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            streakHint,
                            color = ui.textSub, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        // 距下一里程碑进度条（与今日卡同高同位）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ui.ink.copy(alpha = 0.1f))
                        ) {
                            val p = (stats.streak.toFloat() / milestone).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth(p)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(ui.accent, ui.accent.copy(alpha = 0.7f))
                                        )
                                    )
                            )
                        }
                        Text(
                            "再练 ${milestone - stats.streak} 天达成 $milestone 天连击",
                            color = ui.textSub, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    cornerRadius = 22.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                AppIcons.Check,
                                null,
                                tint = ui.correct,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "今日",
                                color = ui.textSub,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 5.dp)
                            )
                        }
                        Text(
                            "${stats.todayAnswered} 题",
                            color = ui.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        val accToday = if (stats.todayAnswered > 0) {
                            (stats.todayCorrect * 100) / stats.todayAnswered
                        } else 0
                        Text(
                            "正确 $accToday%",
                            color = ui.textSub, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        // 对错占比条（与连击卡同高同位）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ui.ink.copy(alpha = 0.08f))
                        ) {
                            val done = stats.todayAnswered.coerceAtLeast(1)
                            val goodRatio = (stats.todayCorrect.toFloat() / done).coerceIn(0f, 1f)
                            if (stats.todayAnswered > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(goodRatio)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .background(ui.correct)
                                )
                            }
                        }
                        Text(
                            if (stats.todayAnswered > 0)
                                "答对 ${stats.todayCorrect} · 答错 ${stats.todayAnswered - stats.todayCorrect}"
                            else "今天还没开始，来几题热热手",
                            color = ui.textSub, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }
            }
        }

        // ---- 近 7 天图表 ----
        item {
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
                            "近 7 天练习",
                            color = ui.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        // 范围标记（v2.8.2：今日/近 7 天已按题库隔离，与全局打卡区分）
                        Text(
                            "本题库",
                            color = ui.textSub, fontSize = 10.sp,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ui.ink.copy(alpha = 0.06f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        // 图例
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ui.ink)
                        )
                        Text(" 题量  ", color = ui.textSub, fontSize = 11.sp)
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ui.accent)
                        )
                        Text(" 正确率", color = ui.textSub, fontSize = 11.sp)
                    }
                    WeekChart(
                        days = stats.days,
                        barColor = ui.ink,
                        lineColor = ui.accent,
                        trackColor = ui.ink.copy(alpha = 0.08f),
                        textColor = ui.textSub,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .height(140.dp)
                    )
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
 * 近 7 天柱状图 + 正确率折线（Canvas 自绘）。
 */
@Composable
fun WeekChart(
    days: List<Repo.DayStat>,
    barColor: Color,
    lineColor: Color,
    trackColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val n = days.size
        val labelSpace = labelPx * 2.2f
        val chartH = size.height - labelSpace
        val slot = size.width / n
        val barW = slot * 0.42f
        val maxV = (days.maxOf { it.answered }).coerceAtLeast(10).toFloat()

        // 柱
        days.forEachIndexed { i, d ->
            val h = chartH * 0.82f * (d.answered / maxV)
            val left = slot * i + (slot - barW) / 2
            val top = chartH - h
            drawRoundRect(
                color = if (d.isToday) barColor else barColor.copy(alpha = if (d.answered == 0) 0.12f else 0.32f),
                topLeft = Offset(left, top),
                size = Size(barW, h.coerceAtLeast(3f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2)
            )
        }

        // 正确率折线
        val points = days.mapIndexed { i, d ->
            val acc = if (d.answered > 0) d.correct.toFloat() / d.answered else 0f
            Offset(slot * i + slot / 2, chartH - chartH * 0.82f * acc - chartH * 0.02f)
        }
        if (days.any { it.answered > 0 }) {
            val path = androidx.compose.ui.graphics.Path().apply {
                points.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x, p.y) else cubicTo(
                        (points[i - 1].x + p.x) / 2, points[i - 1].y,
                        (points[i - 1].x + p.x) / 2, p.y,
                        p.x, p.y
                    )
                }
            }
            drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, p ->
                if (days[i].answered > 0) {
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
        days.forEachIndexed { i, d ->
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
