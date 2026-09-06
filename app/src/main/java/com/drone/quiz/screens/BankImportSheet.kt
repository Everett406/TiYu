package com.drone.quiz.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.BankImport
import com.drone.quiz.data.repo.ImportPreview
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.glass.GlassPromptDialog
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.util.GalleryShare
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.11.1：外部「其他应用打开」预解析结果。
 * AppRoot 的 ExternalImportHost 读完 URI 后打包传入 BankImportSheet，
 * 直接进入预览确认态（导入报告 + 命名 + 确认），跳过选文件步骤。
 */
data class ExternalBankFile(
    val preview: ImportPreview,
    val images: Map<String, ByteArray>,
    val fileName: String
)

/**
 * 题库导入底部弹窗。
 * v2.8.0：JSON / CSV 双通道；v2.8.5：改为 CSV / ZIP 双通道（JSON 下线），
 * ZIP 支持题目 CSV + 图片文件夹；「查看示例」改为「复制 Agent 提示词」。
 * v2.8.6：删去卡片描述与底部说明小字（用户口径：界面要干净），
 * 「复制 Agent 提示词」→「提示词」、「分享 CSV 模板」→「CSV 模板」。
 */
@Composable
fun BankImportSheet(
    visible: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onImported: (bankId: String, name: String, count: Int) -> Unit,
    external: ExternalBankFile? = null
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var zipImages by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var sourceName by remember { mutableStateOf("") }
    var nameDraft by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var templateSaved by remember { mutableStateOf<String?>(null) }

    fun reset() {
        preview = null; zipImages = emptyMap(); sourceName = ""; nameDraft = ""; importMsg = null
    }

    // v2.11.1：外部「其他应用打开」预解析结果 → 预置进入预览确认态。
    // external 为稳定引用：AppRoot 每次消费新 Uri 都产生新实例，以此触发重导入。
    LaunchedEffect(external) {
        if (external != null) {
            preview = external.preview
            zipImages = external.images
            sourceName = external.fileName.substringBeforeLast('.')
            nameDraft = sourceName
            importMsg = null
        }
    }

    fun handleParsed(p: ImportPreview, fileName: String, images: Map<String, ByteArray> = emptyMap()) {
        preview = p
        zipImages = images
        sourceName = fileName.substringBeforeLast('.')
        nameDraft = sourceName
        importMsg = null
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes == null) {
                    importMsg = "无法读取文件"
                } else {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { BankImport.parse(bytes) }
                    }
                    result.onSuccess {
                        handleParsed(it, uri.lastPathSegment ?: "bank.csv")
                    }.onFailure { e ->
                        importMsg = "解析失败：${e.message ?: "文件格式不正确"}"
                    }
                }
            }
        }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes == null) {
                    importMsg = "无法读取文件"
                } else {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { BankImport.parseZip(bytes) }
                    }
                    result.onSuccess {
                        handleParsed(it.preview, uri.lastPathSegment ?: "bank.zip", it.images)
                    }.onFailure { e ->
                        importMsg = "解析失败：${e.message ?: "文件格式不正确"}"
                    }
                }
            }
        }
    }

    GlassBottomSheet(
        visible = visible,
        backdrop = backdrop,
        onDismiss = {
            onDismiss()
            reset()
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // v2.8.2：键盘避让（此前命名输入框被键盘完全遮挡，弹窗不上移，用户反馈）
                // v2.8.3：两态（选文件→预览确认）高度变化平滑过渡
                .imePadding()
                .animateContentSize(tween(240))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            // 抓手由 GlassBottomSheet 自带（v2.8.2 删去重复把手）
            Text(
                if (preview == null) "导入题库" else "导入预览",
                color = ui.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )

            if (preview == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // v2.8.6：删去卡内灰色描述小字（“Excel / AI 生成的表格”“CSV + 图片，支持带图题目”）
                    ImportOptionCard(
                        title = "导入 CSV",
                        modifier = Modifier.weight(1f),
                        backdrop = backdrop
                    ) { csvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/plain;charset=utf-8", "application/csv")) }
                    ImportOptionCard(
                        title = "导入 ZIP",
                        modifier = Modifier.weight(1f),
                        backdrop = backdrop
                    ) { zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                }

                // v2.8.6：删去“把 Excel / Word / PDF 题库材料交给 Agent…”说明小字（用户口径）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = { showPrompt = true },
                        backdrop = backdrop,
                        surfaceColor = ui.surface.copy(alpha = 0.6f),
                        heightDp = 42.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("提示词", color = ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    GlassButton(
                        onClick = {
                            // v2.8.2：系统分享（可发微信/文件助手等，零基础友好）
                            // v2.8.5：模板新增「图片」列 + ZIP 组织方式示例行
                            val ok = GalleryShare.shareTextFile(
                                context, "tiyu_bank_template.csv", CSV_TEMPLATE
                            )
                            if (!ok) templateSaved = "分享失败，可试复制提示词"
                        },
                        backdrop = backdrop,
                        surfaceColor = ui.surface.copy(alpha = 0.6f),
                        heightDp = 42.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            templateSaved ?: "CSV 模板",
                            color = if (templateSaved?.startsWith("分享失败") == true) ui.wrong else ui.text,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                importMsg?.let {
                    Text(
                        it,
                        color = ui.wrong, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            } else {
                val p = preview!!
                // 导入报告
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Check, null, tint = ui.correct, modifier = Modifier.size(16.dp))
                    Text(
                        " 识别成功 ${p.ok.size} 题",
                        color = ui.correct, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                    if (p.errors.isNotEmpty()) {
                        Text(
                            " · 失败 ${p.errors.size} 行",
                            color = ui.wrong, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                // v2.8.5：ZIP 导入附带图片数提示
                val imageCount = p.ok.count { it.images.isNotEmpty() }
                if (zipImages.isNotEmpty()) {
                    Text(
                        "图片 ${zipImages.size} 张 · 带图题目 ${imageCount} 题",
                        color = ui.textSub, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (p.errors.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ui.wrong.copy(alpha = 0.08f))
                            .padding(10.dp)
                    ) {
                        p.errors.take(8).forEach { err ->
                            Text(
                                err,
                                color = ui.wrong, fontSize = 11.sp, lineHeight = 15.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        if (p.errors.size > 8) {
                            Text(
                                "…以及另外 ${p.errors.size - 8} 行失败",
                                color = ui.wrong.copy(alpha = 0.8f), fontSize = 11.sp
                            )
                        }
                    }
                }
                // 分类摘要
                if (p.categories.isNotEmpty()) {
                    Text(
                        "分类：${p.categories.joinToString("、")}",
                        color = ui.textSub, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // 题库名称
                Text(
                    "题库名称",
                    color = ui.textSub, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ui.ink.copy(alpha = 0.35f),
                        unfocusedBorderColor = ui.ink.copy(alpha = 0.12f),
                        cursorColor = ui.ink,
                        focusedContainerColor = ui.ink.copy(alpha = 0.03f),
                        unfocusedContainerColor = ui.ink.copy(alpha = 0.03f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = { reset() },
                        backdrop = backdrop,
                        surfaceColor = ui.surface.copy(alpha = 0.6f),
                        heightDp = 48.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重选文件", color = ui.textSub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    GlassButton(
                        onClick = {
                            if (!importing && p.ok.isNotEmpty() && nameDraft.isNotBlank()) {
                                importing = true
                                scope.launch {
                                    runCatching {
                                        ServiceLocator.repo.importParsedBank(nameDraft.trim(), p, zipImages)
                                    }.onSuccess { bankId ->
                                        onImported(bankId, nameDraft.trim(), p.ok.size)
                                        reset()
                                    }.onFailure { e ->
                                        importMsg = "导入失败：${e.message ?: "未知错误"}"
                                    }
                                    importing = false
                                }
                            }
                        },
                        backdrop = backdrop,
                        surfaceColor = if (p.ok.isNotEmpty() && nameDraft.isNotBlank()) ui.ink else ui.ink.copy(alpha = 0.12f),
                        heightDp = 48.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (importing) "导入中…" else "确认导入",
                            color = if (p.ok.isNotEmpty() && nameDraft.isNotBlank()) ui.onInk else ui.textSub,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                importMsg?.let {
                    Text(it, color = ui.wrong, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }

    if (showPrompt) {
        GlassPromptDialog(
            backdrop = backdrop,
            title = "Agent 整理提示词",
            hint = "复制后连同你的 Excel / Word / PDF 题库材料一起发给 Agent，让它整理成题屿可导入的 CSV / ZIP。",
            body = AGENT_PROMPT,
            confirmText = "复制提示词",
            dismissText = "关闭",
            onConfirm = {
                showPrompt = false
                clipboard.setText(AnnotatedString(AGENT_PROMPT))
            },
            onDismiss = { showPrompt = false }
        )
    }
}

@Composable
private fun ImportOptionCard(
    title: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    onClick: () -> Unit
) {
    val ui = LocalUi.current
    GlassButton(
        onClick = onClick,
        backdrop = backdrop,
        surfaceColor = ui.ink.copy(alpha = 0.06f),
        heightDp = 76.dp,
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Import, null, tint = ui.text, modifier = Modifier.size(18.dp))
            Text(
                title,
                color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * CSV 模板（v2.8.5）：新增「图片」列（紧跟题干），并新增一行示例说明带图题库的 ZIP 组织方式。
 * 与《无人机装调题库_清洗版_含解析.csv》同构，并示范多选/填空/简答。
 * 纯 CSV 导入时「图片」列留空；只有 ZIP 导入才能带图。
 */
const val CSV_TEMPLATE = """题号,题型,题干,图片,选项A,选项B,选项C,选项D,答案,解析,备注
1,单选,时间压力可能会导致人为差错，是因为____。,,人的工作速度不比机器,期限是一种激励方式,可能会因为赶时间而出错,,C,选C。赶时间会压缩人的检查与思考流程。,备注可留空
2,多选,下列属于无人机安全飞行做法的有____。,,避开人群上空,雨天户外飞行,保持视距内飞行,远离机场净空区,"ACD",多选题答案写字母（全对才算对），至少两个。,
3,判断,植保无人机可以用来电力巡线、航拍摄影。,,对,错,,,错,判断题：选项A填「对」、选项B填「错」，答案写「对/错/正确/错误」均可。,
4,填空,水的化学式是____，一个水分子中含有____个氢原子。,,,,,,"H2O||2",填空：题干用 ____ 占位（3个以上下划线）；多空用 || 分隔；一空多个可接受写法用 | 分隔（如：汉|漢）。,
5,简答,请简述什么是视距内（VLOS）飞行。,,,,,,指驾驶员或观测员在目视距离范围内操纵和观察无人机的飞行方式。,简答题需填写参考答案；作答后对照自评，计入正确率。,
6,单选,（示例行）如果题目带图片，应该怎么打包导入？,,把题目 CSV 和 images 图片文件夹一起压缩成 ZIP,只发 CSV 文件,把图片插进 Word 文档里,,A,"带图片的题库这样组织 ZIP：新建文件夹，放入题目 CSV 和 images 文件夹（图片放里面，如 images/1.png），整体压缩后在 App 里选「导入 ZIP」；CSV 的「图片」列填文件名（如 1.png），多张用 | 分隔。支持 jpg/png/webp/gif/bmp。",本行仅作说明，导入后可删除
"""

/**
 * Agent 整理提示词（v2.8.5）：用户复制后连同自己的题库材料（Excel / Word / PDF / 图片等）
 * 一起发给 AI Agent，由 Agent 输出题屿可直接导入的 CSV（纯文字）或 ZIP（带图片）。
 * 面向 Agent 的完整作业规范：输出形式、列格式、各题型答案写法、图片规则、硬性要求。
 */
const val AGENT_PROMPT = """你是题库整理助手。请把我提供的题库材料（Excel / Word / PDF / 文本 / 图片等）整理成「题屿」刷题 App 可直接导入的题库文件。忠实原意，不要编造答案。

【输出形式 · 二选一】
1. 所有题目均为纯文字 → 交付一个 CSV 文件（UTF-8 编码）；
2. 有任何题目带图片 → 交付一个 ZIP 压缩包，结构如下（也可再加说明文件，但 CSV 只能有这一个）：
   题库.zip
   ├─ 题目.csv      ← 有且仅有一个，UTF-8 编码
   └─ images/       ← 图片统一放这里，文件名与 CSV「图片」列一致
   图片不要插进 Word/Excel 里交差，必须作为独立文件放进 images/ 文件夹。

【CSV 列格式（建议按此顺序，第 1 行为表头）】
题号,题型,题干,图片,选项A,选项B,选项C,选项D,选项E,选项F,选项G,选项H,答案,解析,备注

1. 题号：数字，从 1 开始；
2. 题型：只允许 单选 / 多选 / 判断 / 填空 / 简答 五种；
3. 题干：完整题目。填空题用 ____（连续 4 个以上下划线）标出空位，多个空写多个 ____，空位数必须与答案数量一致；
4. 图片：填图片文件名（如 1.png），多张用 | 分隔（如 1.png|2.png）；纯文字题留空。支持 jpg / jpeg / png / webp / gif / bmp，建议用英文+数字命名（如 1.png、2.png），并保证与 images/ 里的文件名完全一致；
5. 选项：单选/多选从 选项A 起依次填写，用不到的选项列留空；判断题在 选项A 填「对」、选项B 填「错」；填空/简答不需要选项；
6. 答案：
   - 单选：字母 A–H（如 C）；
   - 多选：至少两个字母，如 ABD；
   - 判断：对 / 错（或 正确 / 错误）；
   - 填空：各空答案用 || 分隔（如 H2O||2）；同一空有多个可接受写法用 | 分隔（如 汉|漢）；
   - 简答：参考答案全文（作答后对照自评）；
7. 解析：尽量给出，没有可留空；
8. 备注：可留空。

【硬性要求】
- 一行一题；字段内含英文逗号、双引号或换行时，整段用英文双引号包裹，内部双引号写成两个 ""；
- 文件必须为 UTF-8 编码；
- 不确定的答案在备注里标「存疑」，禁止凭空编造；
- 题干与选项中的图片描述（如"下图所示"）应与实际图片对应，缺失图片的题不要保留图片引用；
- 交付时报告：题目总数、各题型数量、带图片题数、输出文件清单。"""
