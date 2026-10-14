package com.drone.quiz.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.data.settings.SettingsStore
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.glass.GlassButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import com.drone.quiz.ui.theme.LocalUi

import com.kyant.backdrop.Backdrop


/**
 * AI 助手设置。
 *
 * 为什么不塞进主设置页：主设置项已经很长（用户原话），再往里加六七行会把页面拖垮。
 * 这里做��二级弹窗，主设置页只留一个「AI 助手」入口。
 */
@Composable
fun AiSettingsSheet(
    visible: Boolean,
    backdrop: Backdrop,
    store: SettingsStore,
    onDismiss: () -> Unit
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val current by store.settings.collectAsState(
        initial = com.drone.quiz.data.settings.AppSettings()
    )

    // 编辑草稿：点「保存」才落盘，中途取消不污染真实配置
    var key by remember(visible) { mutableStateOf(current.aiApiKey) }
    var baseUrl by remember(visible) { mutableStateOf(current.aiBaseUrl) }
    var model by remember(visible) { mutableStateOf(current.aiModel) }
    var turns by remember(visible) { mutableStateOf(current.aiContextTurns.toString()) }
    var temp by remember(visible) { mutableStateOf(current.aiTemperature.toString()) }
    var maxTok by remember(visible) { mutableStateOf(current.aiMaxTokens.toString()) }
    var systemPrompt by remember(visible) { mutableStateOf(current.aiSystemPrompt) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    GlassBottomSheet(
        visible = visible,
        backdrop = backdrop,
        onDismiss = onDismiss
    ) {
        Text(
            "AI 助手",
            color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Text(
            "接任意 OpenAI 兼容接口，默认 DeepSeek。Key 只存在你本机。",
            color = ui.textSub, fontSize = 11.sp
        )

        Column(
            Modifier
                .fillMaxWidth()
                .height(400.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Field("API Key", key, { key = it }, password = !showKey)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = null, indication = null) {
                        showKey = !showKey
                    }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    if (showKey) "隐藏 Key" else "显示 Key",
                    color = ui.accent, fontSize = 11.sp
                )
            }

            Field("接口地址", baseUrl, { baseUrl = it })
            Field("模型名", model, { model = it })

            Field(
                "带入最近几轮对话", turns, { turns = it.filter(Char::isDigit).take(2) },
                hint = "0 = 只带当前题不带历史"
            )
            Field(
                "创造性（temperature）", temp,
                { temp = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                hint = "0 最稳，1~2 更发散"
            )
            Field(
                "单次回复上限（tokens）", maxTok,
                { maxTok = it.filter(Char::isDigit).take(4) },
                hint = "越大越详细也越费额度"
            )
            Field(
                "自定义人设（可留空）", systemPrompt,
                { systemPrompt = it },
                singleLine = false, hint = "留空则用内置：耐心的老师，中文，先结论后道理"
            )

            testMsg?.let {
                Text(
                    it,
                    color = if (it.startsWith("✓")) ui.correct else ui.wrong,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            (if (it.startsWith("✓")) ui.correct else ui.wrong).copy(alpha = 0.10f)
                        )
                        .padding(12.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        // 按钮行
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                onClick = {
                    if (testing) return@GlassButton
                    testing = true
                    testMsg = null
                    scope.launch {
                        val k = key.trim()
                        if (k.isEmpty()) {
                            testMsg = "✗ 先填 API Key"
                        } else {
                            val client = AiClient(
                                baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com/v1" },
                                apiKey = k,
                                model = model.trim().ifBlank { "deepseek-chat" },
                                temperature = temp.toDoubleOrNull() ?: 0.7,
                                maxTokens = maxTok.toIntOrNull() ?: 1024
                            )
                            testMsg = client.test().fold(
                                onSuccess = { "✓ 连通正常，模型回了：$it" },
                                onFailure = { "✗ ${it.message ?: "连接失败"}" }
                            )
                        }
                        testing = false
                    }
                },
                backdrop = backdrop,
                surfaceColor = ui.surface.copy(alpha = 0.6f),
                heightDp = 44.dp,
                modifier = Modifier.weight(1f),
                isInteractive = !testing
            ) {
                Text(
                    if (testing) "测试中…" else "测试连接",
                    color = ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
            GlassButton(
                onClick = {
                    scope.launch {
                        store.setAiApiKey(key)
                        store.setAiBaseUrl(baseUrl)
                        store.setAiModel(model)
                        store.setAiContextTurns(turns.toIntOrNull() ?: 6)
                        store.setAiTemperature(temp.toDoubleOrNull() ?: 0.7)
                        store.setAiMaxTokens(maxTok.toIntOrNull() ?: 1024)
                        store.setAiSystemPrompt(systemPrompt)
                        onDismiss()
                    }
                },
                backdrop = backdrop,
                surfaceColor = ui.ink,
                heightDp = 44.dp,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "保存",
                    color = ui.onInk, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 一行配置项：标题 + 输入框 + 灰色提示。 */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    singleLine: Boolean = true,
    hint: String = ""
) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = ui.textSub, fontSize = 11.sp)
        BoxedInput(
            value = value,
            onChange = onChange,
            password = password,
            singleLine = singleLine
        )
        if (hint.isNotBlank()) {
            Text(
                hint, color = ui.textSub.copy(alpha = 0.75f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun BoxedInput(
    value: String,
    onChange: (String) -> Unit,
    password: Boolean,
    singleLine: Boolean
) {
    val ui = LocalUi.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ui.ink.copy(alpha = 0.05f))
            .border(1.dp, ui.ink.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            textStyle = TextStyle(color = ui.text, fontSize = 13.sp),
            cursorBrush = SolidColor(ui.text),
            visualTransformation =
                if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
