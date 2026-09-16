package com.drone.quiz.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.optionLabel
import kotlinx.coroutines.flow.first
import com.drone.quiz.screens.common.TagChip
import com.drone.quiz.screens.common.displayCategory
import com.drone.quiz.screens.common.rememberBankName
import com.drone.quiz.screens.common.heroSearchField
import com.drone.quiz.screens.common.scrolledFromTopPx
import com.drone.quiz.screens.common.softTopFade
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.theme.LocalReadingFont
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 题目搜索页：题干 / 选项 / 解析 全文检索（防抖 300ms，最多返回 80 条）。
 * 结果点开可看全部选项、正确答案与解析。
 * v2.12.0：搜索框旁新增「拍照搜题」入口。
 * v2.13.0：相机按钮直连自建 CameraX 拍照页（取景框引导，斜拍可自动矫正），
 *          相册入口移至拍照页内，本页不再弹选拍照/相册弹层。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    backdrop: Backdrop,
    initialQuery: String = "",
    onBack: () -> Unit,
    onOpenCapture: () -> Unit
) {
    val ui = LocalUi.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by ServiceLocator.settings.settings.collectAsState(
        initial = com.drone.quiz.data.settings.AppSettings()
    )
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Question>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    // 输入防抖搜索（有结果时记入搜索历史）
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        val bank = runCatching { ServiceLocator.settings.settings.first().currentBank }.getOrDefault("drone")
        results = runCatching { ServiceLocator.repo.searchQuestions(bank, q) }.getOrDefault(emptyList())
        searching = false
        if (results.isNotEmpty()) {
            runCatching { ServiceLocator.settings.addSearchHistory(q) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ---- 固定头部：返回 + 搜索框 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                onClick = onBack,
                backdrop = backdrop,
                icon = AppIcons.ChevronLeft
            )
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .heroSearchField(),
                cornerRadius = 22.dp
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        AppIcons.Search, null,
                        tint = ui.textSub, modifier = Modifier.size(18.dp)
                    )
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索题目 / 选项 / 解析",
                                color = ui.textSub, fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = ui.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = LocalReadingFont.current
                            ),
                            // 光标用正文墨色：accent 橙在浅底上过扎眼，用户反馈"光标有问题"
                            cursorBrush = SolidColor(ui.text),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            AppIcons.Close, null,
                            tint = ui.textSub,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(
                                    interactionSource = null,
                                    indication = null
                                ) { query = "" }
                        )
                    }
                }
            }
            // 拍照搜题入口：搜索的另一种输入方式（直连自建相机页，取景框引导）
            GlassIconButton(
                onClick = onOpenCapture,
                backdrop = backdrop,
                icon = AppIcons.Camera
            )
        }

        // ---- 结果列表 ----
        // 顶部柔化：draw 阶段直读滚动像素（Modifier 稳定无伪影，滑出渐显跟手）
        val resultListState = rememberLazyListState()
        LazyColumn(
            Modifier
                .fillMaxSize()
                .softTopFade(28.dp) { resultListState.scrolledFromTopPx() },
            state = resultListState
        ) {
            item {
                if (query.isBlank()) {
                    // ---- 搜索历史 ----
                    val history = settings.searchHistory
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (history.isEmpty()) "输入关键词，如「升阻比」「锂电池」「迫降」"
                                else "搜索历史",
                                color = ui.textSub, fontSize = 12.sp
                            )
                            if (history.isNotEmpty()) {
                                Text(
                                    "清空",
                                    color = ui.textSub.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = null, indication = null
                                        ) {
                                            scope.launch {
                                                runCatching { ServiceLocator.settings.clearSearchHistory() }
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (history.isNotEmpty()) {
                            // 搜索历史流式排布（chips ≤ 8）
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 10.dp)
                            ) {
                                history.forEach { term ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(ui.ink.copy(alpha = 0.06f))
                                            .border(
                                                1.dp, ui.ink.copy(alpha = 0.12f),
                                                RoundedCornerShape(50)
                                            )
                                            .clickable(
                                                interactionSource = null, indication = null
                                            ) { query = term }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(term, color = ui.textSub, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        when {
                            searching -> "搜索中…"
                            results.isEmpty() -> "没有匹配的题目"
                            else -> "找到 ${results.size} 条（最多展示 80 条）"
                        },
                        color = ui.textSub, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }
            items(results, key = { it.id }) { q ->
                SearchResultItem(
                    q = q,
                    expanded = expandedId == q.id,
                    backdrop = backdrop,
                    onClick = { expandedId = if (expandedId == q.id) null else q.id }
                )
            }
            item { Spacer(Modifier.height(130.dp)) }
        }

    }
}

@Composable
private fun SearchResultItem(
    q: Question,
    expanded: Boolean,
    backdrop: Backdrop,
    onClick: () -> Unit
) {
    val ui = LocalUi.current
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth(),
        cornerRadius = 22.dp,
        onClick = onClick
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagChip(displayCategory(q.category, rememberBankName(q.bankId)))
                Spacer(Modifier.width(6.dp))
                TagChip(com.drone.quiz.data.repo.QuestionTypes.label(q.type))
                Spacer(Modifier.weight(1f))
                Text(
                    "正确答案：${q.correctAnswerText()}",
                    color = ui.correct, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                q.text,
                color = ui.text,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp)
            )
            // v2.7.2：选项与解析展开加弹性动画（此前直出直隐，用户反馈"没有动画"）
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
                                // v2.7.2 修复：正确项绿底上字母原先是绿色（同色隐形），
                                // 用户反馈"绿色选项里 ABC 不显示"——改白字
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
                                .padding(10.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            }
            if (!expanded) {
                Text(
                    "点开查看全部选项与解析",
                    color = ui.textSub, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
