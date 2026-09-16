package com.drone.quiz.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.optionLabel
import com.drone.quiz.ocr.HIT_SOLID
import com.drone.quiz.ocr.PhotoMatch
import com.drone.quiz.ocr.matchQuestions
import com.drone.quiz.ocr.recognizeImage
import com.drone.quiz.ocr.segmentQuestions
import com.drone.quiz.screens.common.TagChip
import com.drone.quiz.screens.common.displayCategory
import com.drone.quiz.screens.common.rememberBankName
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 拍照搜题结果页（v2.12.0，全屏非 Tab 页）：
 * 上半 = 原图 + 识别题块框选 overlay（小猿搜题式，点框 ↔ 下方卡片双向联动）；
 * 下半 = 结果卡片列表（命中/疑似/未命中三态）。
 * 识别与匹配在进入本页时一次性执行；图片已是「拍照产物 / 相册选图」的 content uri。
 */
@Composable
fun PhotoSearchScreen(
    backdrop: Backdrop,
    uri: String,
    onBack: () -> Unit,
    onTextSearch: (String) -> Unit
) {
    val ui = LocalUi.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf<List<PhotoMatch>>(emptyList()) }
    // 框 ↔ 卡片联动选中（PhotoQuestion.index）
    var selected by remember { mutableIntStateOf(-1) }
    var expanded by remember { mutableIntStateOf(-1) }

    val listState = rememberLazyListState()

    // ---- 识别 + 切题 + 匹配（一次性；失败给错误态可重试） ----
    var runTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(uri, runTick) {
        loading = true
        errorMsg = null
        matches = emptyList()
        selected = -1
        expanded = -1
        runCatching {
            val parsed = android.net.Uri.parse(uri)
            val outcome = recognizeImage(context, parsed)
            val photos = segmentQuestions(outcome.lines)
            val bank = runCatching {
                val bankId = ServiceLocator.settings.settings
                    .firstOrNull()?.currentBank ?: "drone"
                ServiceLocator.repo.loadAllQuestions(bankId)
            }.getOrDefault(emptyList())
            outcome.bitmap to matchQuestions(photos, bank)
        }.onSuccess { (bmp, ms) ->
            bitmap = bmp
            matches = ms
        }.onFailure { e ->
            errorMsg = e.message ?: "识别失败"
        }
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---- 顶部：返回 + 标题 + 统计 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(onClick = onBack, backdrop = backdrop, icon = AppIcons.ChevronLeft)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("拍照搜题", color = ui.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        loading -> "正在识别题目…"
                        errorMsg != null -> "识别失败"
                        matches.isEmpty() -> "没有识别出题目"
                        else -> {
                            val hit = matches.count { it.matched != null }
                            "识别 ${matches.size} 题 · 命中 $hit 题"
                        }
                    },
                    color = ui.textSub, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 重新识别（换一张时先返回搜索页重选，这里兜底重试）
            if (!loading && matches.isNotEmpty()) {
                GlassIconButton(
                    onClick = { runTick++ },
                    backdrop = backdrop,
                    icon = AppIcons.Refresh
                )
            }
        }

        when {
            loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.Camera, null, tint = ui.textSub, modifier = Modifier.size(40.dp))
                    Text(
                        "正在识别题目…\n斜拍会自动矫正，约 1~5 秒",
                        color = ui.textSub, fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            errorMsg != null -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg ?: "识别失败", color = ui.textSub, fontSize = 14.sp)
                    GlassButton(
                        onClick = { runTick++ },
                        backdrop = backdrop,
                        heightDp = 40.dp,
                        modifier = Modifier.padding(top = 14.dp)
                    ) {
                        Text("重试", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            matches.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "没有识别出题目，试试对准一页题、光线充足时重拍",
                    color = ui.textSub, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 30.dp)
                )
            }
            else -> {
                // ---- 原图 + 框选 overlay（点框滚动到对应卡片） ----
                val bmp = bitmap
                if (bmp != null) {
                    PhotoBoxOverlay(
                        backdrop = backdrop,
                        bitmap = bmp,
                        matches = matches,
                        selected = selected,
                        onSelect = { i ->
                            selected = i
                            expanded = i
                            scope.launch {
                                // 列表结构：item(提示条) + items(matches) → 卡片位 = i + 1
                                listState.animateScrollToItem((i + 1).coerceAtLeast(0))
                            }
                        }
                    )
                }
                // ---- 结果列表 ----
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (matches.any { it.matched != null })
                                    "纸质卷选项顺序可能与题库不同，认内容不认字母"
                                else "没有在当前题库找到匹配的题目",
                                color = ui.textSub, fontSize = 12.sp
                            )
                        }
                    }
                    items(matches, key = { it.question.index }) { m ->
                        PhotoMatchCard(
                            match = m,
                            selected = selected == m.question.index,
                            expanded = expanded == m.question.index,
                            backdrop = backdrop,
                            onClick = {
                                selected = m.question.index
                                expanded = if (expanded == m.question.index) -1 else m.question.index
                            },
                            onTextSearch = onTextSearch
                        )
                    }
                    item { Spacer(Modifier.height(60.dp)) }
                }
            }
        }
    }
}

/**
 * 原图 + 框选 overlay：图按 ContentScale.Fit 居中显示（高上限 300dp），
 * 框坐标 = 像素坐标 × fit 缩放 + 居中偏移。点框回调 onSelect(index)。
 */
@Composable
private fun PhotoBoxOverlay(
    backdrop: Backdrop,
    bitmap: android.graphics.Bitmap,
    matches: List<PhotoMatch>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val ui = LocalUi.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .heightIn(max = 300.dp)
    ) {
        val boxW = maxWidth.value
        val boxH = maxHeight.value
        val scale = min(boxW / bitmap.width, boxH / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dx = (boxW - dw) / 2f
        val dy = (boxH - dh) / 2f

        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset(dx.dp, dy.dp)
                    .size(dw.dp, dh.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            matches.forEach { m ->
                val isSel = selected == m.question.index
                val borderColor = when {
                    isSel -> ui.accent
                    m.matched == null -> ui.textSub.copy(alpha = 0.8f)
                    m.solid -> ui.correct
                    else -> ui.wrong
                }
                Box(
                    Modifier
                        .offset((dx + m.question.box.left * scale).dp, (dy + m.question.box.top * scale).dp)
                        .size((m.question.box.width * scale).dp, (m.question.box.height * scale).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) ui.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .border(
                            if (isSel) 2.5.dp else 1.5.dp,
                            borderColor,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(interactionSource = null, indication = null) { onSelect(m.question.index) }
                ) {
                    // 编号角标（左上角）
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(borderColor.copy(alpha = 0.92f))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${m.question.number ?: m.question.index + 1}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/** 单张结果卡片：命中（绿）/ 疑似（黄）/ 未命中（灰）三态。 */
@Composable
private fun PhotoMatchCard(
    match: PhotoMatch,
    selected: Boolean,
    expanded: Boolean,
    backdrop: Backdrop,
    onClick: () -> Unit,
    onTextSearch: (String) -> Unit
) {
    val ui = LocalUi.current
    val q = match.matched
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth(),
        cornerRadius = 22.dp,
        onClick = onClick
    ) {
        Column(Modifier.padding(16.dp)) {
            // ---- 头行：状态徽章 + 试卷题号 + 置信度 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (badgeText, badgeColor) = when {
                    q == null -> "未找到" to ui.textSub
                    match.solid -> "已匹配" to ui.correct
                    else -> "疑似" to ui.wrong
                }
                Text(
                    badgeText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor)
                        .padding(horizontal = 9.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "试卷第 ${match.question.number ?: "-"} 题",
                    color = ui.textSub, fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                if (q != null) {
                    Text(
                        "相似度 ${(match.score * 100).toInt()}%",
                        color = ui.textSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (q != null) {
                // ---- 题库原题 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(0.dp))
                    TagChip(displayCategory(q.category, rememberBankName(q.bankId)))
                    Spacer(Modifier.width(6.dp))
                    TagChip(com.drone.quiz.data.repo.QuestionTypes.label(q.type))
                }
                Text(
                    q.text,
                    color = ui.text,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
                    ) + fadeIn(),
                    exit = shrinkVertically(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
                    ) + fadeOut()
                ) {
                    Column(Modifier.padding(top = 10.dp)) {
                        if (q.type == "blank" || q.type == "short") {
                            Text(
                                if (q.type == "short") "参考答案：${q.answerText}"
                                else "各空答案：${q.blankAnswers.joinToString("；") { vs -> vs.joinToString(" / ") }}",
                                color = ui.correct,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        } else q.optionsOrJudge.forEachIndexed { i, opt ->
                            val isAnswer = when (q.type) {
                                "multi" -> (q.answer and (1 shl i)) != 0
                                else -> i == q.answer
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isAnswer) ui.correct else ui.ink.copy(alpha = 0.08f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        optionLabel(i, q.isJudge),
                                        color = if (isAnswer) Color.White else ui.textSub,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    opt,
                                    color = if (isAnswer) ui.correct else ui.text,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                            }
                        }
                        if (q.explanation.isNotBlank()) {
                            Text(
                                q.explanation,
                                color = ui.textSub,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ui.ink.copy(alpha = 0.05f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            } else {
                // ---- 未命中：展示 OCR 原文 + 引导文字搜索 ----
                Text(
                    match.question.stem.ifBlank { match.question.rawText },
                    color = ui.text,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (expanded) {
                    GlassButton(
                        onClick = { onTextSearch(match.question.stem.ifBlank { match.question.rawText }) },
                        backdrop = backdrop,
                        heightDp = 40.dp,
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        Text("用这段文字搜索", color = ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
