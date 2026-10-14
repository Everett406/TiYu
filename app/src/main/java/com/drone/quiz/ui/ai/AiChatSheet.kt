package com.drone.quiz.ui.ai

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.drone.quiz.data.settings.SettingsStore
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.glass.GlassButton
import kotlinx.coroutines.launch
import com.drone.quiz.ui.theme.LocalUi

import com.kyant.backdrop.Backdrop


/**
 * 刷题页「问 AI」弹窗。
 *
 * **定位**（用户口径）：AI 是旁边的讲解，不是主角。所以这个弹窗只在刷题页由
 * 「问 AI」进入，不进首页、不做 tab、不抢任何主流程；关闭后不留任何痕迹。
 *
 * **上下文**：当前题自动进 system（题干 / 选项 / 正确答案 / 我的作答 / 题库解析），
 * 换题自动跟着换，用户不用贴任何东西。多轮靠保留最近 N 轮（设置项可调）。
 *
 * **吐字**：SSE 是按包回来的，一包可能十几个字，直接贴会一跳一跳。这里用
 * `produceState` 把已收到的文本按 ~14ms 一字地"追"出来，看着像人在打字。
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

    // 每个会话：role + content。最后一条 assistant 可能是流式中途，改它就是"继续吐字"。
    val messages = remember { mutableStateListOf<AiMessage>() }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var curQuestionId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    val configured = settings.aiApiKey.isNotBlank()

    // 换题了：上一题的对话留着没意义（旧题上下文还挂在脑子里），直接清空重来。
    LaunchedEffect(question.id, visible) {
        if (visible && curQuestionId != question.id) {
            messages.clear()
            errorMsg = null
            input = ""
            curQuestionId = question.id
        }
    }

    // 新消息/流式更新时滚到底
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || streaming) return
        if (!configured) {
            errorMsg = "还没填 API Key，先去设置里配一下"
            return
        }
        errorMsg = null
        input = ""
        messages.add(AiMessage("user", q))
        messages.add(AiMessage("assistant", ""))   // 占位，后续往这条追加

        streaming = true
        scope.launch {
            val cfg = settings   // 弹窗入口处 collectAsState 的快照，即最新值
            val client = AiClient(
                baseUrl = cfg.aiBaseUrl.ifBlank { "https://api.deepseek.com/v1" },
                apiKey = cfg.aiApiKey,
                model = cfg.aiModel.ifBlank { "deepseek-chat" },
                temperature = cfg.aiTemperature,
                maxTokens = cfg.aiMaxTokens
            )
            // 组装：system(当前题) + 最近 N 轮 + 本次新问题
            val turns = cfg.aiContextTurns.coerceIn(0, 20)
            val history = messages.dropLast(1)          // 去掉刚加的 assistant 占位
            val sysBase = cfg.aiSystemPrompt.ifBlank {
                QuestionContext.of(question, myAnswer, hasSubmitted)
            }
            val payload = buildList {
                add(AiMessage("system", sysBase))
                // 按"轮"截取：N 轮 = N*2 条，末尾对齐到 user 开始
                val take = turns * 2
                val slice = if (history.size <= take) history
                else history.subList(history.size - take, history.size)
                addAll(slice)
            }
            runCatching {
                client.stream(payload) { delta ->
                    messages[messages.lastIndex] =
                        messages[messages.lastIndex].copy(
                            content = messages[messages.lastIndex].content + delta
                        )
                }
            }.onFailure { e ->
                // 出错时把空的 assistant 占位移除，避免留一条空气泡
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
        // 顶部条
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Text(
                if (messages.isEmpty()) "清空" else "",
                color = ui.textSub, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = null, indication = null,
                        enabled = messages.isNotEmpty() && !streaming
                    ) { messages.clear() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                "设置",
                color = ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = null, indication = null, onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 消息区
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                // 未配置：先给引导，别让用户对着空面板发呆
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
                // 空会话：给几个现成的问题，省得不知道能问啥
                if (messages.isEmpty() && configured) {
                    Column(Modifier.padding(top = 4.dp)) {
                        QUICK_ASKS.forEach { ask ->
                            Text(
                                ask,
                                color = ui.text,
                                fontSize = 13.sp,
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
                // 最后一条且正在流 → 平滑吐字
                val isLastStreaming = streaming && i == messages.lastIndex
                AiBubble(
                    user = m.role == "user",
                    text = m.content,
                    smooth = isLastStreaming
                )
            }
            item {
                if (streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ui.textSub.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (messages.lastOrNull()?.content.isNullOrEmpty()) "正在思考…" else "",
                            color = ui.textSub, fontSize = 11.sp
                        )
                    }
                }
                errorMsg?.let { msg ->
                    Column(
                        Modifier
                            .fillMaxWidth()
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

        // 输入区
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ui.ink.copy(alpha = 0.05f))
                    .border(1.dp, ui.ink.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (input.isEmpty()) {
                    Text("就这道题问点什么…", color = ui.textSub, fontSize = 13.sp)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = ui.text, fontSize = 13.sp),
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
                heightDp = 40.dp,
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

/** 一条气泡。user 右对齐墨色，assistant 左对齐浅底。 */
@Composable
private fun AiBubble(user: Boolean, text: String, smooth: Boolean) {
    val ui = LocalUi.current
    if (text.isEmpty() && !smooth) return

    // 吐字：已收到的文本按 ~14ms 一字追出来，最后一个字闪一下光标
    val shown by produceState(initialValue = text, key1 = text, key2 = smooth) {
        if (!smooth) { value = text; return@produceState }
        var n = 0
        while (n < text.length) {
            n++
            value = text.take(n)
            kotlinx.coroutines.delay(if (text.length - n > 20) 6 else 14)
        }
    }
    val caret by animateFloatAsState(
        targetValue = if (smooth) 1f else 0f, label = "caret"
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (user) 18.dp else 5.dp,
                        bottomEnd = if (user) 5.dp else 18.dp
                    )
                )
                .background(
                    if (user) ui.ink else ui.surfaceStrong.copy(alpha = 0.55f)
                )
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Text(
                text = if (smooth && caret > 0.5f && shown.isNotEmpty())
                    shown.dropLast(1) + "▍" else shown,
                color = if (user) ui.onInk else ui.text,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

/** 空会话时的现成问法。刻意贴着用户真实困惑写，不写"介绍一下"这种废问题。 */
private val QUICK_ASKS = listOf(
    "这道题考的是什么知识点？",
    "为什么这个答案是对的？",
    "给我讲讲背后的原理",
    "出几道同类的题让我练练"
)
