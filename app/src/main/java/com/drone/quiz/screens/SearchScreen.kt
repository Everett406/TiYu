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
import com.drone.quiz.screens.common.JudgeBadgeGlyph
import com.drone.quiz.screens.common.ParseBlock
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlatformTextStyle
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
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.theme.LocalReadingFont
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable

import androidx.compose.animation.core.FastOutSlowInEasing

import androidx.compose.animation.core.tween

import androidx.compose.ui.draw.blur

import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.lazy.itemsIndexed


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
    onBack: () -> Unit
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
            // v2.17.0：拍照搜题下线，搜索框右侧的相机入口随之移除。
        }

        // ---- 结果列表 ----
        // 顶部柔化：draw 阶段直读滚动像素（Modifier 稳定无伪影，滑出渐显跟手）
        val resultListState = rememberLazyListState()
        LazyColumn(
            Modifier
                .fillMaxSize(),
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
            // v2.19.39：逐条渐入。错峰延迟 + alpha 0→1 + blur 12→0，三个并行，
            // 一条一条从上往下接力（用户口径：「依次从上到下，一个一个显示出来」）。
            // 见 SearchResultItem 内的 revealProgress——这里只传下标。
            itemsIndexed(results, key = { _, it -> it.id }) { idx, q ->
                SearchResultItem(
                    q = q,
                    index = idx,
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
    index: Int,
    expanded: Boolean,
    backdrop: Backdrop,
    onClick: () -> Unit
) {
    val ui = LocalUi.current

    // v2.19.39 逐条进场。
    //
    // 三件事**并行**跑（用户口径）：alpha 从 0 到 1、blur 从 12dp 到 0、
    // 整卡从 translateY(14.dp) 归零。blur 让文字"从模糊里长出来"，
    // 比单纯淡入有质感得多。
    //
    // 关键取舍：**blur 不能挂在 GlassCard 的 Modifier 上**。玻璃卡的折射
    // (drawBackdrop) 需要读它背后的内容，若自身被 blur，折射源与渲染目标
    // 重叠，会出现边缘发虚/采样错位。所以 blur + alpha 走外层 Box 的
    // graphicsLayer，GlassCard 保持干净——折射照常，只让整卡看起来在"浮现"。
    //
    // 错峰：每条延后 index * 55ms，上限 12 条（再往后就等太久了，用户会以为卡住）。
    // 用 Animatable 而非 animate*AsState，因为要"延迟启动 + 可被打断"。
    val progress = remember(q.id) { Animatable(0f) }
    LaunchedEffect(q.id) {
        val delayMs = (index * 55L).coerceAtMost(12 * 55L)
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
        )
    }
    val p = progress.value


    Box(
        Modifier.graphicsLayer {
            alpha = p
            // blur 每帧新建 BlurEffect 有分配开销，但只在这 420ms 里、且仅可见条目，
            // 可接受；p≈1 时直接不挂，省掉稳态开销。
            if (p < 0.995f) {
                // radiusX/Y 是 **px(Float)**，不是 Dp——12dp 先转 px 再随进度收拢。
                // 本版 Compose 的 BlurEffect 只有 (x, y, TileMode) 签名，
                // 没有 BlurEdgeTreatment 参数（那是更晚版本才加的边缘策略）。
                // TileMode.Clamp 即"边缘延展"，卡片渐显时不会露出透明边——
                // 正是这里要的效果。
                val r = 12.dp.toPx() * (1f - p)
                renderEffect = BlurEffect(r, r, TileMode.Clamp)
            } else {
                renderEffect = null
            }
            translationY = 14.dp.toPx() * (1f - p)
        }
    ) {
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
                // v2.19.36：折叠态只给字母。原样带全文有两个问题：
                //   ① 与展开后的选项列表重复抄了一遍；
                //   ② 选项文字长时不换行，把整张卡片横向撑高、行高暴涨。
                // 折叠态只显示题干、选项要点开才看得到——所以这里留字母做"这条是答案"的
                // 提示是必要的（否则用户搜出来看不出哪题对），但全文交给展开区去显示。
                Text(
                    "正确答案：${q.correctAnswerText(brief = true)}",
                    color = ui.correct, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
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
                                // 判断题与刷题页同样用自绘 √/×：系统字符 √ 是根号、字重随字体变，
                                // 视觉重量对不上（v2.19.16 的结论，此处一并对齐）
                                if (q.isJudge) {
                                    JudgeBadgeGlyph(
                                        isCheck = (i == 0) == isAnswer,
                                        color = if (isAnswer) Color.White else ui.textSub
                                    )
                                    return@Box
                                }
                                // v2.7.2 修复：正确项绿底上字母原先是绿色（同色隐形），
                                // 用户反馈"绿色选项里 ABC 不显示"——改白字
                                Text(
                                    optionLabel(i, q.isJudge),
                                    color = if (isAnswer) Color.White else ui.textSub,
                                    fontSize = 11.sp,
                                    // 行盒收到与字号相同 + 关掉系统字体的额外行距——
                                    // 否则字形在圆圈里偏下（v2.19.16 在 QuestionBody 修过一次，
                                    // 搜索页这处是同一段代码的另一份拷贝，当时漏了）
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                    )
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
                }
            }
            if (!expanded) {
                Text(
                    "点开查看全部选项与解析",
                    color = ui.textSub, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // 解析交给共用的 ParseBlock：默认折叠、点开逐字「对焦」揭示。
            // 与刷题页/模考页/错题本完全同一套观感与交互，全 app 只此一份实现。
            ParseBlock(q.explanation, autoOpen = true)
        }
    }
    }
}

