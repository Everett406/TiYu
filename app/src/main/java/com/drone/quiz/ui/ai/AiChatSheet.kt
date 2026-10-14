package com.drone.quiz.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 刷题页「问 AI」弹窗。
 *
 * **定位**（用户口径）：AI 是旁边的讲解，不是主角。这个弹窗只在刷题页由「问 AI」
 * 进入，不进首页、不做 tab，关闭后不留痕迹。
 *
 * **三条渲染铁律**（都是踩过的坑）：
 * 1. **Markdown 必须全程正确渲染**，不能因为"正在吐字"就退回纯文本——
 *    那会让屏幕上出现半屏裸的 `**` 和没闭合的 `|`。做法是**先解析再按可见字符截断**
 *    （`Md.build`），而不是把 Markdown 源码本身截断。
 * 2. **思考与正文是两条流**：`delta.reasoning_content` 和 `delta.content` 分开收。
 *    思考期间显示思考正文，思考结束后折叠成一行「已思考 N 秒」，点开可回看。
 * 3. **思考计时不能放 widget State**——LazyColumn 会把行滚出缓存再销毁重建，
 *    State 里的秒数每次都从 0 开始。计时放 `ThinkingClock`（静态表 + SystemClock）。
 */
@Composable
fun AiChatSheet(
    visible: Boolean,
    backdrop: Backdrop,
    question: Question,
    myAnswer: Int?,
    hasSubmitted: Boolean,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<AiMessage>() }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var curQuestionId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    val configured = settings.aiApiKey.isNotBlank()

    LaunchedEffect(question.id, visible) {
        if (visible && curQuestionId != question.id) {
            messages.clear()
            errorMsg = null
            input = ""
            curQuestionId = question.id
            ThinkingClock.clearAll()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(text: String) {
        val ask = text.trim()
        if (ask.isEmpty() || streaming) return
        if (!configured) {
            errorMsg = "还没填 API Key，点右上角「设置」配一下"
            return
        }
        errorMsg = null
        input = ""
        messages.add(AiMessage("user", ask))

        messages.add(AiMessage("assistant", ""))
        val slot = messages.lastIndex            // assistant 占位的下标 = 思考槽号
        ThinkingClock.start(slot)

        streaming = true
        scope.launch {
            val cfg = settings
            val client = AiClient(
                baseUrl = cfg.aiBaseUrl.ifBlank { "https://api.deepseek.com/v1" },
                apiKey = cfg.aiApiKey,
                model = cfg.aiModel.ifBlank { "deepseek-chat" },
                temperature = cfg.aiTemperature,
                maxTokens = cfg.aiMaxTokens
            )
            val turns = cfg.aiContextTurns.coerceIn(0, 20)
            val history = messages.dropLast(1)
            val sysBase = cfg.aiSystemPrompt.ifBlank {
                QuestionContext.of(question, myAnswer, hasSubmitted)
            }
            val payload = buildList {
                add(AiMessage("system", sysBase))
                val take = turns * 2
                val slice = if (history.size <= take) history
                else history.subList(history.size - take, history.size)
                addAll(slice)
            }
            runCatching {
                client.stream(
                    messages = payload,
                    onReasoning = { r -> ThinkingClock.appendReasoning(slot, r) },
                    onDelta = { d ->
                        // 正文开始 → 冻结思考耗时，别把"吐正文"的时间算成思考时间
                        ThinkingClock.close(slot)
                        if (slot - 1 in messages.indices) {
                            messages[slot - 1] = messages[slot - 1].copy(
                                content = messages[slot - 1].content + d
                            )
                        }
                    }
                )
            }.onFailure { e ->
                ThinkingClock.close(slot)
                if (messages.lastOrNull()?.content.isNullOrEmpty()) messages.removeLast()
                errorMsg = e.message ?: "出错了"
            }
            streaming = false
        }
    }

    GlassBottomSheet(
        visible = visible,
        backdrop = backdrop,
        onDismiss = { if (!streaming) onDismiss() }
    ) {
        // v2.19.25：面板左右统一留白。之前内容直接贴边，「问 AI」两个字都顶到圆角了。
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "问 AI",
                        color = ui.text, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (configured) settings.aiModel.ifBlank { "deepseek-chat" } else "未配置 API Key",
                        color = ui.textSub, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (messages.isNotEmpty()) {
                    Text(
                        "清空",
                        color = ui.textSub, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = null, indication = null,
                                enabled = !streaming
                            ) {
                                messages.clear()
                                ThinkingClock.clearAll()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    "设置",
                    color = ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = null, indication = null, onClick = onOpenSettings)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    if (!configured) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(ui.accent.copy(alpha = 0.10f))
                                .padding(14.dp)
                        ) {
                            Text(
                                "还没配置 API Key",
                                color = ui.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                            Text(
                                "右上角「设置」里填入即可，默认接 DeepSeek。Key 只存在你本机，" +
                                    "不会上传到任何第三方（除了你自己填的那家接口）。",
                                color = ui.textSub, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    if (messages.isEmpty() && configured) {
                        Column(Modifier.padding(top = 2.dp)) {
                            QUICK_ASKS.forEach { ask ->
                                Text(
                                    ask,
                                    color = ui.text, fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(ui.ink.copy(alpha = 0.05f))
                                        .border(
                                            1.dp, ui.ink.copy(alpha = 0.08f), RoundedCornerShape(14.dp)
                                        )
                                        .clickable(
                                            interactionSource = null, indication = null
                                        ) { send(ask) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                items(messages.size) { i ->
                    val m = messages[i]
                    AiBubble(
                        user = m.role == "user",
                        text = m.content,
                        smooth = streaming && i == messages.lastIndex
                    )
                    // 思考区挂在对应 assistant 气泡下面：只有该条有思考才渲染
                    val t = ThinkingClock.peek(i)
                    if (t != null && (t.reasoning.isNotBlank() || t.closed)) {
                        ThinkingPanel(
                            slot = i,
                            active = streaming && i == messages.lastIndex && t.reasoning.isNotBlank()
                        )
                    }
                }
                item {
                    if (streaming) {
                        // 还没吐正文、也没思考 → 呼吸点（不是转圈：转圈代表"在算"，
                        // 此刻其实还没开始，语义不对）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BreathingDot()
                            Spacer(Modifier.width(8.dp))
                            Text("正在思考…", color = ui.textSub, fontSize = 12.sp)
                        }
                    }
                    errorMsg?.let { msg ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ui.wrong.copy(alpha = 0.10f))
                                .padding(12.dp)
                        ) {
                            Text(msg, color = ui.wrong, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // 输入区：v2.19.25 修排版——输入框与发送按钮之前高度不一致（按钮 40dp、
            // 输入框靠 padding 撑出来的 ~46dp），视觉上按钮矮一截。统一用 44dp。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(ui.ink.copy(alpha = 0.05f))
                        .border(1.dp, ui.ink.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isEmpty()) {
                        Text("就这道题问点什么…", color = ui.textSub, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = TextStyle(color = ui.text, fontSize = 13.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(ui.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(input) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlassButton(
                    onClick = { send(input) },
                    backdrop = backdrop,
                    surfaceColor = ui.ink,
                    heightDp = 44.dp,
                    isInteractive = input.isNotBlank() && !streaming && configured
                ) {
                    Text(
                        if (streaming) "…" else "发送",
                        color = ui.onInk, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 一条气泡。正文走 Markdown 渲染 + 逐字吐，**无光标**（用户口径：正常一个字一个字
 * 往外吐就行，不要那个一闪一闪的竖线）。
 */
@Composable
private fun AiBubble(user: Boolean, text: String, smooth: Boolean) {
    val ui = LocalUi.current
    if (text.isEmpty() && !smooth) return

    val lines = remember(text) { Md.parse(text) }
    val target = remember(lines) { Md.visibleLen(lines) }

    // 吐字：按**可见字符数**追（不是按源码长度——否则 `**bold**` 会 6 个字符一起蹦出来）。
    // 每次 target 增长就重启追赶，落后太多时直接跳到当前值（模型吐得比动画快时不该卡住）。
    val shown by produceState(initialValue = if (smooth) 0 else target, key1 = target, key2 = smooth) {
        if (!smooth) { value = target; return@produceState }
        var n = 0
        while (n < target) {
            n = if (target - n > 24) n + 3 else n + 1
            value = n
            delay(if (target - n > 24) 8 else 16)
        }
        value = target
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        val baseStyle = TextStyle(
            color = if (user) ui.onInk else ui.text,
            fontSize = 13.sp,
            lineHeight = 21.sp
        )
        // 用户消息是纯回显，不必 Markdown；AI 回复才渲染
        val content: androidx.compose.ui.text.AnnotatedString = if (user) {
            androidx.compose.ui.text.AnnotatedString(text)
        } else {
            Md.build(
                lines = lines,
                showChars = shown,
                base = baseStyle,
                color = ui.text,
                accent = ui.accent,
                codeBg = ui.accent
            )
        }
        Text(
            text = content,
            color = baseStyle.color,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (user) 18.dp else 5.dp,
                        bottomEnd = if (user) 5.dp else 18.dp
                    )
                )
                .background(if (user) ui.ink else ui.surfaceStrong.copy(alpha = 0.5f))
                .padding(horizontal = 13.dp, vertical = 9.dp)
        )
    }
}

/**
 * 思考区。
 *
 * 三态（语义不同，别混）：
 * · 有 reasoning 且正在流 → **思考正文直接可见**（用户要的就是"能看到思考"）
 * · 思考结束（正文开始）→ **折叠成一行「已思考 N 秒」**，点开可回看全文
 * · 还没开始 → 不渲染（外层用一个呼吸点表示"正在思考…"）
 *
 * 展开**内联**而非弹窗：思考通常只有几行，内联不会像 servllama 那样把正文顶出屏幕。
 * 思考特别长（> 12 行）时才限高滚动。
 */
@Composable
private fun ThinkingPanel(slot: Int, active: Boolean) {
    val ui = LocalUi.current
    var expanded by remember(slot) { mutableStateOf(false) }

    val elapsed by produceState(initialValue = 0L, slot, active) {
        while (true) {
            val e = ThinkingClock.peek(slot)
            value = e?.elapsedMs() ?: 0L
            if (!active && (e == null || e.closed)) break
            delay(200)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ui.ink.copy(alpha = 0.035f))
            .clickable(
                interactionSource = null, indication = null,
                enabled = !active
            ) { expanded = !expanded }
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) BreathingDot()
            else Icon(
                com.drone.quiz.ui.glass.AppIcons.Filter, null,   // 复用现有小图标当"已折叠"标记
                tint = ui.textSub, modifier = Modifier.size(11.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                // 明确写 TextAlign 风格：不用 AnimatedSwitcher，避免子控件被居中
                text = if (active) "思考中 · ${fmtSec(elapsed)}"
                else "已思考 ${fmtSec(elapsed)}",
                color = if (active) ui.textSub else ui.text.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (!active) {
                Text(
                    if (expanded) "收起" else "查看",
                    color = ui.textSub, fontSize = 11.sp
                )
            }
        }
        AnimatedVisibility(
            visible = active || expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            val t = ThinkingClock.peek(slot)
            val body = t?.reasoning?.toString().orEmpty()
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    color = ui.textSub,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = if (active) Int.MAX_VALUE else 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/** 呼吸点：中性色，scale 0.55↔1.30，单程 1000ms，reverse 无限循环。 */
@Composable
private fun BreathingDot(sizeDp: Int = 6) {
    val ui = LocalUi.current
    val t = rememberInfiniteTransition(label = "breath")
    val scale by t.animateFloat(
        initialValue = 0.55f, targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    Box(
        Modifier
            .size((sizeDp * scale).dp)
            .clip(CircleShape)
            .background(ui.textSub.copy(alpha = 0.75f))
    )
}

private fun fmtSec(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "${s} 秒" else "${s / 60} 分 ${s % 60} 秒"
}

/** 空会话时的现成问法。刻意贴着用户真实困惑写。 */
private val QUICK_ASKS = listOf(
    "这道题考的是什么知识点？",
    "为什么这个答案是对的？",
    "给我讲讲背后的原理",
    "出几道同类的题让我练练"
)