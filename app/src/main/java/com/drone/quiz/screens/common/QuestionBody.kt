package com.drone.quiz.screens.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.QuestionImages
import com.drone.quiz.data.repo.QuestionTypes
import com.drone.quiz.data.repo.UserAnswer
import com.drone.quiz.data.repo.displayUserAnswer
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.theme.LocalReadingFont
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.ui.theme.backdropIsDark
import com.kyant.backdrop.Backdrop
import com.drone.quiz.data.repo.JUDGE_CHECK
import com.drone.quiz.data.repo.JUDGE_CROSS

/**
 * 题型作答组件族（v2.8.0）：刷题页与模考页共用。
 * 设计要点：
 * - 多选：点选切换（不判分），底部「提交」后一次性判定（全对才算对）；
 * - 填空：每空一个输入行，键盘弹出时由页面根级 imePadding 避让；全部填完才可提交；
 * - 简答：多行草稿 → 提交看参考答案 → 自评「答对了/答错了」计分（用户选定口径）。
 */

@Composable
fun QuestionTypeTag(type: String) {
    TagChip(QuestionTypes.label(type))
}

// ---------- 选项行（single/judge 保持原样式；multi 为可多选变体） ----------

@Composable
fun OptionRow(
    label: String,
    text: String,
    // 0 默认 / 1 正确 / 2 误选 / 3 已选中但**未判定**
    // state 3 是 v2.19.22 加的：模考关掉「即时判定」时，点完选项 verdict 恒为 null，
    // 若仍只用 0/1/2 则选中态会一并消失——点下去毫无反应，用户会以为选项点不动。
    // 3 只表达"你选了这个"，**不泄露对错**，交卷判分口径不变。
    state: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val ui = LocalUi.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == 2) {
            listOf(-14f, 12f, -9f, 6f, -3f, 0f).forEach { v ->
                shake.animateTo(v, spring(dampingRatio = 0.6f, stiffness = 900f))
            }
        }
    }
    val (bg, borderColor) = optionColors(state)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(interactionSource = null, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OptionBadge(label, state)
        Text(
            text,
            color = ui.text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        )
        ResultTag(state)
    }
}

/**
 * 多选选项行。
 *
 * **v2.19.23：resultState 扩到四态，并刻意不与 OptionRow 共用配色/标签。**
 *   -1 未提交 · 0 无关（既没选也不是答案）· 1 正确（选了且命中）· 2 错选（选了但没命中）· 3 漏选（该选却没选）
 *
 * 旧版只有三态，且「是正确答案」一律记 1 —— 于是**漏选的项也显示"正确"**
 * （用户反馈：选了 A、B、C 漏了 D，四个选项全标"正确"，完全看不出漏在哪）。
 * 2 的文案也从"你选的"改成 **"错选"**（用户口径：漏选 / 错选 要能一眼分开）。
 *
 * ⚠️ 这里的 3 与 OptionRow 的 3 **语义不同**（那里是「已选中但未判定」）。
 * 共用 optionColors/ResultTag 会把单选的"刚选中"显示成"漏选"，故各自配色各自标签。
 */
@Composable
fun OptionToggleRow(
    label: String,
    text: String,
    selected: Boolean,
    resultState: Int, // -1 未提交（勾选态），0/1/2/3 已提交
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val ui = LocalUi.current
    val submitted = resultState >= 0
    val missed = submitted && resultState == 3
    val (bg, borderColor) = if (submitted) when (resultState) {
        1 -> ui.correct.copy(alpha = 0.16f) to ui.correct.copy(alpha = 0.5f)
        2 -> ui.wrong.copy(alpha = 0.16f) to ui.wrong.copy(alpha = 0.5f)
        // 漏选走 accent（琥珀）：它既是"该选的"又是"你没选的"，用绿会和"正确"混、
        // 用红又会被误读成"你选错了"。琥珀是唯一的第三色。
        3 -> ui.accent.copy(alpha = 0.12f) to ui.accent.copy(alpha = 0.55f)
        else -> ui.ink.copy(alpha = 0.05f) to ui.ink.copy(alpha = 0.08f)
    } else if (selected) ui.ink.copy(alpha = 0.10f) to ui.ink.copy(alpha = 0.35f)
    else ui.ink.copy(alpha = 0.05f) to ui.ink.copy(alpha = 0.08f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(interactionSource = null, indication = null, onClick = onToggle) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选用方框勾选，区别于单选圆徽章（v2.11.2：goo 融合分支已除）
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        submitted && resultState == 1 -> ui.correct
                        submitted && resultState == 2 -> ui.wrong
                        missed -> ui.accent.copy(alpha = 0.18f)   // 空心琥珀：该勾没勾
                        selected -> ui.ink
                        else -> ui.ink.copy(alpha = 0.08f)
                    }
                )
                .border(
                    1.dp,
                    if (missed) ui.accent.copy(alpha = 0.55f) else ui.ink.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected || (submitted && resultState == 1)) {
                Icon(
                    AppIcons.Check, null,
                    tint = if (submitted && resultState == 2) Color.White else ui.onInk,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            text,
            color = ui.text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        )
        // 多选专用标签（不走 ResultTag：那里的 3 是"已选中未判定"，语义不同）
        if (submitted) {
            when (resultState) {
                1 -> Text("正确", color = ui.correct, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                2 -> Text("错选", color = ui.wrong, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                3 -> Text("漏选", color = ui.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 选项圆标。
 *
 * **字母为什么看着偏下**：Android 的 `PlatformTextStyle.includeFontPadding` 默认是
 * `true`——那是为中日韩字形预留的上下留白。圆标只有 24.dp，行盒被这层留白撑高后
 * 再由 `contentAlignment = Center` 居中，于是**字形本身被整体压低了约半号**。
 * 置 `includeFontPadding = false` 并把 `lineHeight` 收到与 `fontSize` 相同，
 * 字形才真正落在圆心。
 *
 * **判断题的 √/× 自绘**（用户反馈系统字形太细、不好看）：
 * 原先直接用字符 `√` / `×`，前者是根号而非对勾、后者字重也随系统字体而变。
 * 现改为 Canvas 描边绘制：圆头圆角、粗细可控，两个符号视觉重量一致。
 * ABCD 仍用默认字母，不自绘。
 */
@Composable
private fun OptionBadge(label: String, state: Int) {
    val ui = LocalUi.current
    val fg = if (state > 0) Color.White else ui.textSub
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                when (state) {
                    1 -> ui.correct
                    2 -> ui.wrong
                    3 -> ui.ink                      // 已选中未判定：实心 ink 底 + 白字
                    else -> ui.ink.copy(alpha = 0.08f)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when (label) {
            JUDGE_CHECK -> JudgeGlyph(isCheck = true, color = fg)
            JUDGE_CROSS -> JudgeGlyph(isCheck = false, color = fg)
            else -> Text(
                label,
                color = fg,
                fontSize = 11.sp,
                // lineHeight 收到与 fontSize 相同，行盒不再被字体行高撑高
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

/**
 * 判断题圆标：**自绘**的对勾 / 叉号。
 *
 * **为什么是填充多边形而不是描边折线**：v2.19.16 那一版用 `drawLine`/`drawPath`
 * 手搓折线，坐标是凭感觉拍的，结果真机上是「极丑无比，而且还不居中」——
 * 折线的**几何包围盒居中 ≠ 视觉重心居中**：勾形的长臂墨量远大于短臂，
 * 整块墨的重心被拽到左下，看着就是歪的。描边还额外受 `stroke/2` 的圆头外扩影响。
 *
 * 现改为**填充多边形**，按 24 单位视口里的标准比例作图再等比缩放：
 * 勾的墨迹在 x∈[3.4,21]、y∈[5.6,19]，叉在 x,y∈[5,19]，两者都以视口中心对称，
 * 缩放后自然落在圆心，且笔画粗实、不受系统字重影响。
 */
@Composable
private fun JudgeGlyph(isCheck: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp)) {
        val k = size.minDimension / 24f
        val path = Path()
        if (isCheck) {
            path.moveTo(9f * k, 16.2f * k)
            path.lineTo(4.8f * k, 12f * k)
            path.lineTo(3.4f * k, 13.4f * k)
            path.lineTo(9f * k, 19f * k)
            path.lineTo(21f * k, 7f * k)
            path.lineTo(19.6f * k, 5.6f * k)
            path.close()
        } else {
            path.moveTo(19f * k, 6.4f * k)
            path.lineTo(17.6f * k, 5f * k)
            path.lineTo(12f * k, 10.6f * k)
            path.lineTo(6.4f * k, 5f * k)
            path.lineTo(5f * k, 6.4f * k)
            path.lineTo(10.6f * k, 12f * k)
            path.lineTo(5f * k, 17.6f * k)
            path.lineTo(6.4f * k, 19f * k)
            path.lineTo(12f * k, 13.4f * k)
            path.lineTo(17.6f * k, 19f * k)
            path.lineTo(19f * k, 17.6f * k)
            path.lineTo(13.4f * k, 12f * k)
            path.close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ResultTag(state: Int?) {
    val ui = LocalUi.current
    when (state) {
        1 -> Text("正确", color = ui.correct, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        2 -> Text("你选的", color = ui.wrong, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun optionColors(state: Int): Pair<Color, Color> {
    val ui = LocalUi.current
    return when (state) {
        1 -> ui.correct.copy(alpha = 0.14f) to ui.correct.copy(alpha = 0.6f)
        2 -> ui.wrong.copy(alpha = 0.14f) to ui.wrong.copy(alpha = 0.6f)
        // 已选中未判定：走 ink 系而非绿/红，避免在关掉即时判定时提前泄露对错。
        // 数值刻意与模考多选的"selected"分支保持一致（ui.ink 0.10 / 0.35），
        // 两处视觉语言统一。
        3 -> ui.ink.copy(alpha = 0.10f) to ui.ink.copy(alpha = 0.35f)
        else -> ui.ink.copy(alpha = 0.05f) to ui.ink.copy(alpha = 0.08f)
    }
}

// ---------- 判定结果头 + 解析 ----------

@Composable
fun ResultHeader(isCorrect: Boolean?, suffix: String = "") {
    val ui = LocalUi.current
    if (isCorrect == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (isCorrect) AppIcons.Check else AppIcons.Close,
            null,
            tint = if (isCorrect) ui.correct else ui.wrong,
            modifier = Modifier.size(18.dp)
        )
        Text(
            if (isCorrect) "回答正确" else "回答错误",
            color = if (isCorrect) ui.correct else ui.wrong,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp)
        )
        if (suffix.isNotBlank()) {
            Text(suffix, color = ui.textSub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun CorrectAnswerLine(text: String) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("正确答案：", color = ui.textSub, fontSize = 12.sp)
        Text(
            text,
            color = ui.correct,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun YourAnswerLine(text: String) {
    val ui = LocalUi.current
    Text("你的答案：$text", color = ui.textSub, fontSize = 12.sp)
}

@Composable
fun ParseBlock(explanation: String) {
    val ui = LocalUi.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ui.ink.copy(alpha = 0.05f))
            .padding(14.dp)
    ) {
        Text("解析", color = ui.textSub, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            explanation.ifBlank { "暂无解析" },
            color = ui.text,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ---------- 题目图片（v2.8.5，ZIP 导入的带图题库） ----------

/**
 * 题目图片条：默认每张图显示为小缩略图，点按在小图/大图间平滑过渡（Hero 式缩放）。
 * 图片来自导入 ZIP 里的文件，按题库隔离存放在 bank_images/<bankId>/。
 * 插入位置：题卡题干之后、作答区之前（填空题图片显示在题干上方，同一插入点）。
 * v2.8.6：小图进一步缩小（64–96dp），且宽度贴合图片不再整行铺满（用户反馈“小图还是不够小”），
 * 并删去“点按放大”角标。
 */
@Composable
fun QuestionImageStrip(q: Question) {
    if (q.images.isEmpty()) return
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        q.images.forEach { name ->
            ExpandableQuestionImage(
                path = remember(q.bankId, name) { QuestionImages.resolve(context, q.bankId, name) }
            )
        }
    }
}

/**
 * 单张可展开题目图片：小图宽度/高度贴合图片比例（限高 64–96dp），
 * 点开后宽度展开到整卡、高度到适配大图——宽高同步 spring 插值（Hero 式转场）。
 * 再点一下收起回小图；加载失败降级为占位文案。
 */
@Composable
private fun ExpandableQuestionImage(path: String) {
    val ui = LocalUi.current
    var expanded by remember(path) { mutableStateOf(false) }
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val b = QuestionImages.load(path)
        bitmap = b?.asImageBitmap()
        failed = b == null
    }
    // 展开/收起进度（0 小图 → 1 大图），驱动高度与圆角平滑过渡
    val expand = remember(path) { Animatable(0f) }
    LaunchedEffect(expanded) {
        expand.animateTo(if (expanded) 1f else 0f, spring(dampingRatio = 0.92f, stiffness = 400f))
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = bitmap != null
            ) { expanded = !expanded }
    ) {
        val aspect = bitmap?.let {
            it.width.coerceAtLeast(1).toFloat() / it.height.coerceAtLeast(1).toFloat()
        } ?: 1.4f
        val fitH = maxWidth / aspect
        // v2.8.6：小图限高 96–150 → 64–96dp，观感即“缩略图”而非“横幅”
        val smallH = fitH.coerceIn(64.dp, 96.dp)
        val largeH = fitH.coerceAtLeast(smallH * 1.8f).coerceAtMost(440.dp)
        // 小图宽度贴合图片（限高后的等比宽），展开后拉到整卡宽——宽高同步过渡
        val smallW = (smallH * aspect).coerceAtMost(maxWidth)
        val wFraction = (lerp(smallW, maxWidth, expand.value) / maxWidth).coerceIn(0f, 1f)
        val corner = lerp(10.dp, 20.dp, expand.value)
        Box(
            Modifier
                .fillMaxWidth(wFraction)
                .height(lerp(smallH, largeH, expand.value))
                .clip(RoundedCornerShape(corner))
                .background(ui.ink.copy(alpha = 0.05f))
                .border(1.dp, ui.ink.copy(alpha = 0.10f), RoundedCornerShape(corner)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = "题目图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                failed -> Text("图片未能加载", color = ui.textSub, fontSize = 11.sp)
                else -> Text("图片加载中…", color = ui.textSub, fontSize = 11.sp)
            }
        }
    }
}

/** 填空题题干占位符：连续 3 个以上下划线记一空（与导入校验口径一致） */
private val BLANK_PLACEHOLDER = Regex("_{3,}")

/**
 * 填空作答区（v2.8.3 重做，用户口径）：不再「第一空/第二空」分行输入，
 * 而是把题干按 ____ 占位符拆开，在空位处原位嵌入输入框——点哪个空就在哪里输入，
 * 填完提交。提交后逐空着色显示对错（可接受答案见下方「正确答案」行）。
 * 键盘避让仍由页面根级 Modifier.imePadding() 统一处理。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlankInlineFields(
    text: String,
    values: List<String>,
    onValueChange: (index: Int, value: String) -> Unit,
    enabled: Boolean,
    showResult: Boolean = false,
    answers: List<List<String>> = emptyList()
) {
    val ui = LocalUi.current
    // 题干按占位符拆段；段数-1 = 空位数；脏数据兑底：题干无占位但答案有 N 空 → 末尾补输入位
    val segments = remember(text) { text.split(BLANK_PLACEHOLDER) }
    val inlineCount = (segments.size - 1).coerceAtLeast(0)
    val extra = (values.size - inlineCount).coerceAtLeast(0)

    FlowRow(
        Modifier.fillMaxWidth(),
        // v2.8.4：空位变紧凑后同步收紧流式排布间距
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        segments.forEachIndexed { si, seg ->
            if (seg.isNotEmpty()) {
                Text(
                    seg,
                    color = ui.text,
                    fontSize = 16.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            if (si < inlineCount) {
                val i = si
                InlineBlankField(
                    index = i,
                    value = values.getOrNull(i).orEmpty(),
                    enabled = enabled,
                    showResult = showResult,
                    ok = showResult && answers.getOrNull(i)?.contains(values.getOrNull(i).orEmpty()) == true,
                    onValueChange = onValueChange
                )
            }
        }
        repeat(extra) { k ->
            val i = inlineCount + k
            InlineBlankField(
                index = i,
                value = values.getOrNull(i).orEmpty(),
                enabled = enabled,
                showResult = showResult,
                ok = showResult && answers.getOrNull(i)?.contains(values.getOrNull(i).orEmpty()) == true,
                onValueChange = onValueChange
            )
        }
    }
}

/**
 * 原位空位输入框（v2.8.4 重设计，用户反馈「空太大太宽」）：
 * - 宽度自适应内容：空态最小 60dp 随输入增长，上限 170dp（原 110–220dp 固定宽）；
 * - 高度紧凑：字号 15→14sp、内边距收窄，与正文行高衔接；
 * - 占位「第N空」缩小居中，提交后逐空红绿胶囊不变。
 */
@Composable
private fun InlineBlankField(
    index: Int,
    value: String,
    enabled: Boolean,
    showResult: Boolean,
    ok: Boolean,
    onValueChange: (index: Int, value: String) -> Unit
) {
    val ui = LocalUi.current
    val bg = when {
        showResult && ok -> ui.correct.copy(alpha = 0.14f)
        showResult -> ui.wrong.copy(alpha = 0.14f)
        else -> ui.ink.copy(alpha = 0.06f)
    }
    val borderColor = when {
        showResult && ok -> ui.correct.copy(alpha = 0.55f)
        showResult -> ui.wrong.copy(alpha = 0.55f)
        else -> ui.ink.copy(alpha = 0.16f)
    }
    BasicTextField(
        value = value,
        onValueChange = { if (enabled) onValueChange(index, it) },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = ui.text,
            fontSize = 14.sp,
            fontFamily = LocalReadingFont.current,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold
        ),
        cursorBrush = SolidColor(ui.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .widthIn(min = 60.dp, max = 170.dp)
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.widthIn(min = 42.dp)) {
                if (value.isEmpty()) {
                    Text(
                        "第${index + 1}空",
                        color = ui.textSub.copy(alpha = 0.45f), fontSize = 10.sp
                    )
                }
                inner()
            }
        }
    )
}

// ---------- 简答 ----------

/** 简答草稿输入（多行）；提交后锁定。 */
@Composable
fun ShortDraftField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String = "写下你的答案…"
) {
    val ui = LocalUi.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = { if (enabled) onValueChange(it) },
        enabled = enabled,
        minLines = 3,
        maxLines = 6,
        placeholder = { Text(placeholder, color = ui.textSub.copy(alpha = 0.6f), fontSize = 13.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = ui.text, fontSize = 14.sp, lineHeight = 21.sp,
            fontFamily = LocalReadingFont.current
        ),
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ui.ink.copy(alpha = 0.35f),
            unfocusedBorderColor = ui.ink.copy(alpha = 0.12f),
            disabledBorderColor = ui.ink.copy(alpha = 0.08f),
            cursorColor = ui.ink,
            focusedContainerColor = ui.ink.copy(alpha = 0.03f),
            unfocusedContainerColor = ui.ink.copy(alpha = 0.03f),
            disabledContainerColor = ui.ink.copy(alpha = 0.03f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 简答参考答案 + 自评（用户选定口径：自评判分）。
 * graded 前展示「答对了/答错了」两个按钮；graded 后展示自评结论。
 */
@Composable
fun ShortReferenceCard(
    reference: String,
    yourText: String,
    graded: Boolean,
    selfCorrect: Boolean?,
    onGrade: (correct: Boolean) -> Unit
) {
    val ui = LocalUi.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ui.ink.copy(alpha = 0.05f))
            .padding(14.dp)
    ) {
        Text("参考答案", color = ui.textSub, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            reference,
            color = ui.text,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (yourText.isNotBlank()) {
            Text(
                "你的回答：$yourText",
                color = ui.textSub, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (!graded) {
            Text(
                "对照参考答案，给自己打个分吧（计入统计）",
                color = ui.textSub, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ui.correct.copy(alpha = 0.14f))
                        .border(1.dp, ui.correct.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .clickable(interactionSource = null, indication = null) { onGrade(true) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("答对了", color = ui.correct, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ui.wrong.copy(alpha = 0.14f))
                        .border(1.dp, ui.wrong.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .clickable(interactionSource = null, indication = null) { onGrade(false) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("答错了", color = ui.wrong, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(
                    if (selfCorrect == true) AppIcons.Check else AppIcons.Close,
                    null,
                    tint = if (selfCorrect == true) ui.correct else ui.wrong,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    if (selfCorrect == true) "自评：答对了" else "自评：答错了",
                    color = if (selfCorrect == true) ui.correct else ui.wrong,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/**
 * 通用提交按钮。
 * v2.8.4 自适应对比：按实际背景（壁纸×主题纱）明暗选择墨色/奶色底——
 * 修复自定义壁纸（如亮色主题+深色壁纸）下墨色按钮融进背景看不见。
 * 禁用态保持 v2.8.3 口径（更浅底+次级字），仅把文字换成与背景同向的高对比色。
 */
@Composable
fun SubmitAnswerButton(
    enabled: Boolean,
    hint: String = "提交答案",
    backdrop: Backdrop,
    onClick: () -> Unit
) {
    val ui = LocalUi.current
    val bgDark = backdropIsDark()
    val mismatch = bgDark != ui.isDark   // 背景明暗与主题相反 → 底/字反色
    val surface = if (mismatch) ui.onInk else ui.ink
    val onSurface = if (mismatch) ui.ink else ui.onInk
    GlassButton(
        onClick = onClick,
        backdrop = backdrop,
        surfaceColor = if (enabled) surface else surface.copy(alpha = 0.14f),
        heightDp = 46.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        isInteractive = enabled
    ) {
        Text(
            hint,
            color = when {
                enabled -> onSurface
                bgDark -> ui.onInk.copy(alpha = 0.55f)   // 深背景 → 亮灰字（暗底不可用 textSub）
                else -> ui.textSub                        // 浅背景 → 次级灰（v2.8.3 口径）
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------- 错题/成绩条目里的参考答案（多题型） ----------

@Composable
fun WrongAnswerBlock(q: Question, userAnswer: String?) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            q.text,
            color = ui.text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium
        )
        if (userAnswer != null) {
            Text(
                "你的答案：$userAnswer",
                color = ui.wrong,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            "正确答案：${q.correctAnswerText(brief = true)}",
            color = ui.correct,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (q.explanation.isNotBlank()) {
            Text(
                q.explanation,
                color = ui.textSub,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
