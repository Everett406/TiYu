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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.optionLabel
import com.drone.quiz.ocr.HIT_SOLID
import com.drone.quiz.ocr.OcrItem
import com.drone.quiz.ocr.OcrModels
import com.drone.quiz.ocr.PhotoBox
import com.drone.quiz.ocr.PhotoMatch
import com.drone.quiz.ocr.linesInRegion
import com.drone.quiz.ocr.matchQuestions
import com.drone.quiz.ocr.recognizeImage
import com.drone.quiz.ocr.segmentQuestions
import com.drone.quiz.screens.common.TagChip
import com.drone.quiz.screens.common.displayCategory
import com.drone.quiz.screens.common.rememberBankName
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassContentDialog
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.theme.LocalUi
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 拍照搜题结果页（v2.14.0，全屏非 Tab 页）。
 *
 * v2.14.0 单题模式（默认，用户口径：默认单体单体搜，整页多题列表退居可选）：
 * - 自动框住一道题（默认选第一个命中的题块），下方只出这一张结果卡（常展开）；
 * - 「上一题 / 下一题」在自动题块间切换；
 * - 图上直接拖动手指画框（拖拽手势，与点块点击共存）→ 框内 OCR 行重切 + 匹配，
 *   手框兜底一切自动切题的漏题/连体（用户实测残留问题）；
 * - 右上「整页」切回 v2.13 全部框选 + 列表视图。
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
    // 手动框选（单题模式）：框区域 + 框内重切匹配结果
    var allLines by remember { mutableStateOf<List<OcrItem>>(emptyList()) }
    var bankList by remember { mutableStateOf<List<Question>>(emptyList()) }
    var manualRegion by remember { mutableStateOf<PhotoBox?>(null) }
    var manualMatches by remember { mutableStateOf<List<PhotoMatch>>(emptyList()) }
    var manualBusy by remember { mutableStateOf(false) }
    // 单题 / 整页模式（默认单题）
    var singleMode by remember { mutableStateOf(true) }
    // 自动题块选中（PhotoQuestion.index）；expanded 仅整页模式用
    var selected by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableIntStateOf(-1) }
    // v2.16.0 模型按需下载（首次使用拍照搜题触发；phase: 0 无/1 下载中/2 失败）
    var modelPhase by remember { mutableIntStateOf(0) }
    var modelProgress by remember { mutableStateOf<OcrModels.Progress?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var modelJob by remember { mutableStateOf<Job?>(null) }

    val listState = rememberLazyListState()

    val manualActive = singleMode && manualMatches.isNotEmpty()

    fun selectBlock(i: Int) {
        manualRegion = null
        manualMatches = emptyList()
        selected = i.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
        expanded = selected
    }

    // 手动框选 → 框内行重切 + 匹配（题库复用缓存，毫秒级）
    fun applyRegion(region: PhotoBox) {
        if (allLines.isEmpty()) return
        scope.launch {
            manualBusy = true
            val bank = bankList.ifEmpty {
                runCatching {
                    val bankId = ServiceLocator.settings.settings
                        .firstOrNull()?.currentBank ?: "drone"
                    ServiceLocator.repo.loadAllQuestions(bankId)
                }.getOrDefault(emptyList())
            }
            bankList = bank
            val photos = segmentQuestions(linesInRegion(allLines, region))
            manualMatches = matchQuestions(photos, bank)
            manualRegion = region
            manualBusy = false
        }
    }

    // ---- 识别 + 切题 + 匹配（一次性；失败给错误态可重试） ----
    var runTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(uri, runTick) {
        loading = true
        errorMsg = null
        matches = emptyList()
        allLines = emptyList()
        manualRegion = null
        manualMatches = emptyList()
        selected = 0
        expanded = -1
        runCatching {
            val parsed = android.net.Uri.parse(uri)
            // v2.16.0 首次使用：按需下载 OCR 模型（国内线路，仅一次，约 15MB）
            if (!OcrModels.isReady(context)) {
                modelPhase = 1
                modelProgress = null
                modelError = null
                val job = launch {
                    try {
                        OcrModels.ensure(context) { modelProgress = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        modelError = e.message ?: "模型下载失败"
                    }
                }
                modelJob = job
                job.join()
                if (job.isCancelled) {
                    // 用户取消：不出错误态，留在空页（可点重试重新进入）
                    loading = false
                    return@LaunchedEffect
                }
                if (modelError != null || !OcrModels.isReady(context)) {
                    modelPhase = 2
                    loading = false
                    return@LaunchedEffect
                }
                modelPhase = 0
            }
            val outcome = recognizeImage(context, parsed)
            val photos = segmentQuestions(outcome.lines)
            val bank = runCatching {
                val bankId = ServiceLocator.settings.settings
                    .firstOrNull()?.currentBank ?: "drone"
                ServiceLocator.repo.loadAllQuestions(bankId)
            }.getOrDefault(emptyList())
            allLines = outcome.lines
            bankList = bank
            outcome.bitmap to matchQuestions(photos, bank)
        }.onSuccess { (bmp, ms) ->
            bitmap = bmp
            matches = ms
            // 自动框住一道题：优先第一个命中的题块，否则第一块
            selected = ms.indexOfFirst { it.matched != null }.takeIf { it >= 0 } ?: 0
        }.onFailure { e ->
            errorMsg = e.message ?: "识别失败"
        }
        loading = false
    }

    // v2.16.0 模型下载弹层（下载中可取消 / 失败可重试）；仅拍照搜题入口触发，不入设置页
    if (modelPhase == 1 || modelPhase == 2) {
        OcrModelDownloadDialog(
            backdrop = backdrop,
            downloading = modelPhase == 1,
            progress = modelProgress,
            error = modelError,
            onCancel = {
                modelJob?.cancel()
                modelJob = null
                modelPhase = 0
            },
            onRetry = {
                modelPhase = 0
                modelError = null
                runTick += 1
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---- 顶部：返回 + 标题 + 统计 + 重试 ----
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
                        manualBusy -> "正在搜框选区域…"
                        manualActive -> "手选区域 · ${manualMatches.size} 题结果"
                        singleMode -> {
                            val m = matches.getOrNull(selected)
                            val hit = matches.count { it.matched != null }
                            "第 ${selected + 1}/${matches.size} 题" +
                                (m?.let { if (it.matched != null) " · 相似度 ${(it.score * 100).toInt()}%" else " · 未匹配到" } ?: "") +
                                " · 命中 $hit"
                        }
                        else -> {
                            val hit = matches.count { it.matched != null }
                            "识别 ${matches.size} 题 · 命中 $hit 题"
                        }
                    },
                    color = ui.textSub, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!loading && matches.isNotEmpty()) {
                GlassIconButton(
                    onClick = { runTick++ },
                    backdrop = backdrop,
                    icon = AppIcons.Refresh
                )
            }
        }

        // ---- 模式切换（单题默认 / 整页）+ 操作提示 ----
        if (!loading && matches.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ui.ink.copy(alpha = 0.06f))
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (singleMode) ui.accent else Color.Transparent)
                            .clickable(
                                interactionSource = null, indication = null
                            ) {
                                singleMode = true
                            }
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "单题",
                            color = if (singleMode) Color.White else ui.textSub,
                            fontSize = 12.sp,
                            fontWeight = if (singleMode) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (!singleMode) ui.accent else Color.Transparent)
                            .clickable(
                                interactionSource = null, indication = null
                            ) {
                                singleMode = false
                                manualRegion = null
                                manualMatches = emptyList()
                            }
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "整页",
                            color = if (!singleMode) Color.White else ui.textSub,
                            fontSize = 12.sp,
                            fontWeight = if (!singleMode) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (singleMode) "拖动图片可手动框题" else "点框看对应题",
                    color = ui.textSub.copy(alpha = 0.8f), fontSize = 11.sp
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
                // ---- 原图 + 框选 overlay（单题：拖画框；两模式都可点块选择） ----
                val bmp = bitmap
                if (bmp != null) {
                    PhotoBoxOverlay(
                        backdrop = backdrop,
                        bitmap = bmp,
                        matches = matches,
                        selected = if (singleMode) selected else -1,
                        singleMode = singleMode,
                        manualRegion = manualRegion,
                        onSelect = { i ->
                            if (singleMode) selectBlock(i)
                            else {
                                selected = i
                                expanded = i
                                scope.launch {
                                    listState.animateScrollToItem((i + 1).coerceAtLeast(0))
                                }
                            }
                        },
                        onRegionDone = { region -> applyRegion(region) }
                    )
                }
                // ---- 单题模式导航条：上一题 / 计数 / 下一题；手框时显示清除 ----
                if (singleMode) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (manualActive) {
                            Text(
                                "框选了 ${manualMatches.size} 题（单题请框小一点）",
                                color = ui.textSub, fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "清除手选",
                                color = ui.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = null, indication = null
                                    ) {
                                        manualRegion = null
                                        manualMatches = emptyList()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        } else {
                            Text(
                                "‹ 上一题",
                                color = if (selected > 0) ui.text else ui.textSub.copy(alpha = 0.35f),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = null, indication = null,
                                        enabled = selected > 0
                                    ) { selectBlock(selected - 1) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                            Text(
                                "${selected + 1} / ${matches.size}",
                                color = ui.textSub, fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                "下一题 ›",
                                color = if (selected < matches.size - 1) ui.text else ui.textSub.copy(alpha = 0.35f),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = null, indication = null,
                                        enabled = selected < matches.size - 1
                                    ) { selectBlock(selected + 1) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                // ---- 结果列表：单题模式一张卡（手框优先）；整页模式全部 ----
                val displayList: List<PhotoMatch> =
                    if (manualActive) manualMatches
                    else if (singleMode) listOfNotNull(matches.getOrNull(selected))
                    else matches
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when {
                                    displayList.isEmpty() && manualActive ->
                                        "这个区域没有识别出题目，试着框住完整的一道题（含选项）"
                                    displayList.any { it.matched != null } ->
                                        "纸质卷选项顺序可能与题库不同，认内容不认字母"
                                    manualActive -> "没有在当前题库找到匹配的题目"
                                    else -> "没有在当前题库找到匹配的题目"
                                },
                                color = ui.textSub, fontSize = 12.sp
                            )
                        }
                    }
                    items(displayList) { m ->
                        PhotoMatchCard(
                            match = m,
                            selected = singleMode || selected == m.question.index,
                            expanded = singleMode || expanded == m.question.index,
                            backdrop = backdrop,
                            onClick = {
                                if (!singleMode) {
                                    selected = m.question.index
                                    expanded = if (expanded == m.question.index) -1 else m.question.index
                                }
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
 * 框坐标 = 像素坐标 × fit 缩放 + 居中偏移。
 * - 整页模式：全部题块正常框选，点块回调 onSelect(index)；
 * - 单题模式：非选中块淡显、选中块高亮；整图拖拽画框（touch slop 与点块点击自然区分），
 *   松手若框足够大回调 onRegionDone（bitmap 像素坐标）。
 */
@Composable
private fun PhotoBoxOverlay(
    backdrop: Backdrop,
    bitmap: android.graphics.Bitmap,
    matches: List<PhotoMatch>,
    selected: Int,
    singleMode: Boolean,
    manualRegion: PhotoBox?,
    onSelect: (Int) -> Unit,
    onRegionDone: (PhotoBox) -> Unit
) {
    val ui = LocalUi.current
    val density = LocalDensity.current.density
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

        // 拖拽画框状态（dp 坐标，overlay 容器内）
        var dragStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        var dragCur by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (singleMode) Modifier.pointerInput(matches) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragCur = it },
                            onDrag = { ch, _ -> dragCur = ch.position },
                            onDragEnd = {
                                val s = dragStart
                                val e = dragCur
                                dragStart = null
                                dragCur = null
                                if (s != null && e != null) {
                                    val l = min(s.x, e.x) / density
                                    val r = maxOf(s.x, e.x) / density
                                    val t = min(s.y, e.y) / density
                                    val b = maxOf(s.y, e.y) / density
                                    val region = PhotoBox(
                                        ((l - dx) / scale).roundToInt().coerceIn(0, bitmap.width),
                                        ((t - dy) / scale).roundToInt().coerceIn(0, bitmap.height),
                                        ((r - dx) / scale).roundToInt().coerceIn(0, bitmap.width),
                                        ((b - dy) / scale).roundToInt().coerceIn(0, bitmap.height)
                                    )
                                    if (region.width >= 30 && region.height >= 30) {
                                        onRegionDone(region)
                                    }
                                }
                            },
                            onDragCancel = { dragStart = null; dragCur = null }
                        )
                    } else Modifier
                )
        ) {
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
                // 单题模式：非选中块淡显（给手框/选中块让视觉焦点）
                val dimmed = singleMode && !isSel
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
                            when {
                                isSel -> 2.5.dp
                                dimmed -> 1.dp
                                else -> 1.5.dp
                            },
                            borderColor.copy(alpha = if (dimmed) 0.4f else 1f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(interactionSource = null, indication = null) { onSelect(m.question.index) }
                ) {
                    if (!dimmed) {
                        // 编号角标（左上角；淡显块不标避免视觉噪音）
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
            // 手动框（bitmap 像素 → dp 显示）
            manualRegion?.let { mr ->
                Box(
                    Modifier
                        .offset((dx + mr.left * scale).dp, (dy + mr.top * scale).dp)
                        .size((mr.width * scale).dp, (mr.height * scale).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ui.accent.copy(alpha = 0.10f))
                        .border(2.5.dp, ui.accent, RoundedCornerShape(6.dp))
                )
            }
            // 拖拽中的实时框
            val ds = dragStart
            val dc = dragCur
            if (ds != null && dc != null) {
                val l = min(ds.x, dc.x)
                val t = min(ds.y, dc.y)
                val w = kotlin.math.abs(ds.x - dc.x)
                val h = kotlin.math.abs(ds.y - dc.y)
                if (w > 4f && h > 4f) {
                    Box(
                        Modifier
                            .offset(l.dp, t.dp)
                            .size(w.dp, h.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ui.accent.copy(alpha = 0.08f))
                            .border(1.5.dp, ui.accent, RoundedCornerShape(6.dp))
                    )
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

/**
 * v2.16.0 OCR 模型下载弹层：仅在首次进入拍照搜题时出现（不入设置页）。
 * 下载中显示整体进度 + 可取消；失败显示原因 + 可重试。
 */
@Composable
private fun OcrModelDownloadDialog(
    backdrop: Backdrop,
    downloading: Boolean,
    progress: OcrModels.Progress?,
    error: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val ui = LocalUi.current
    GlassContentDialog(
        backdrop = backdrop,
        title = if (downloading) "下载识别模型" else "模型下载失败",
        dismissText = if (downloading) "取消" else "取消",
        confirmText = if (downloading) null else "重试",
        onDismiss = onCancel,
        onConfirm = onRetry
    ) {
        if (downloading) {
            Text(
                "首次使用拍照搜题需要下载 OCR 模型（约 15MB，仅下载一次，国内线路直连）。",
                color = ui.textSub,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            val frac = progress?.let { p ->
                if (p.total > 0) (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) else 0f
            } ?: 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ui.text.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (frac <= 0.02f) 0.02f else frac)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ui.ink)
                )
            }
            val fileLabel = progress?.let {
                "${if (it.fileIndex == 0) "检测模型" else "识别模型"} ${it.fileIndex + 1}/${it.fileCount}"
            } ?: "准备下载…"
            Text(
                "$fileLabel · " + "%.1f / %.1f MB".format(
                    (progress?.bytes ?: 0) / 1048576.0,
                    (progress?.total ?: OcrModels.TOTAL_BYTES) / 1048576.0
                ),
                color = ui.textSub,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Text(
                (error ?: "网络异常，下载未完成") + "\n\n可检查网络后重试；模型下载一次后离线可用。",
                color = ui.textSub,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
