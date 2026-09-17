package com.drone.quiz.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.repo.Repo
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.util.GallerySave
import com.drone.quiz.util.GalleryShare
import com.drone.quiz.util.ShareCardRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成绩分享卡全屏浮层（v2.10.0）：模考成绩页「生成成绩卡」进入。
 *
 * 预览即最终产物：Canvas 渲染出的位图直接展示，改主题/署名/标语/强调色即时重渲染。
 * 个性化（主题/署名/标语/强调色）即改即存（DataStore），下次打开记住。
 * 保存 = MediaStore 相册「Pictures/题屿」（免权限，minSdk 31）；
 * 分享 = FileProvider + 系统分享面板（复用 v2.8.2 基建）。
 */
private data class CardMeta(
    val delta: Int?,
    val durationSec: Int,
    val attempt: Int,
    val bankName: String,
    val streakDays: Int,
    val totalAnswered: Int,
    val dateText: String
)

/** 强调色微调预设（空 = 主题默认） */
private val ACCENTS = listOf(
    "" to "默认",
    "#E0483E" to "绯红",
    "#3D7BF5" to "靛蓝",
    "#D9A44C" to "鎏金",
    "#2E9E5B" to "松绿"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareCardHost(
    outcome: Repo.ExamOutcome,
    examId: Long,
    onDismiss: () -> Unit
) {
    val ui = LocalUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var themeId by remember { mutableStateOf("sunset") }
    var cardName by remember { mutableStateOf("") }
    var slogan by remember { mutableStateOf("") }
    var accent by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<CardMeta?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onDismiss)

    // 读已保存的个性化 + 本场考试元信息（上次对比/用时/第几次/打卡/累计）
    LaunchedEffect(examId) {
        val st = runCatching { ServiceLocator.settings.settings.first() }.getOrDefault(AppSettings())
        themeId = st.shareCardTheme
        cardName = st.shareCardName
        slogan = st.shareCardSlogan
        accent = st.shareCardAccent
        val rec = runCatching { ServiceLocator.repo.examRecord(examId) }.getOrNull()
            ?: return@LaunchedEffect
        val recents = runCatching { ServiceLocator.repo.recentExams(rec.bankId).first() }
            .getOrDefault(emptyList())
        val prev = recents
            .filter { it.score != null && it.id != rec.id && it.startedAt < rec.startedAt }
            .maxByOrNull { it.startedAt }
        meta = CardMeta(
            delta = prev?.score?.let { p -> Math.round((rec.score ?: 0f) - p) },
            durationSec = rec.durationSec,
            attempt = recents.count { it.score != null && it.startedAt <= rec.startedAt },
            bankName = runCatching { ServiceLocator.repo.bankNameOf(rec.bankId) ?: "题库" }
                .getOrDefault("题库"),
            streakDays = runCatching { ServiceLocator.repo.streakDays() }.getOrDefault(0),
            totalAnswered = runCatching { ServiceLocator.repo.totalAnsweredFlow().first() }
                .getOrDefault(0),
            dateText = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date(rec.startedAt))
        )
    }

    // 渲染：任一输入变化即重绘（预览 = 最终分享产物）
    LaunchedEffect(themeId, cardName, slogan, accent, meta) {
        val m = meta ?: return@LaunchedEffect
        val data = ShareCardRenderer.CardData(
            dateText = m.dateText,
            scoreText = if (outcome.score % 1f == 0f) "${outcome.score.toInt()}"
                else String.format(Locale.US, "%.1f", outcome.score),
            passed = outcome.passed,
            passLine = outcome.passLine,
            accuracyPct = if (outcome.total == 0) 0
                else Math.round(outcome.correct * 100f / outcome.total),
            durationText = ShareCardRenderer.formatDuration(m.durationSec),
            correctText = "${outcome.correct}/${outcome.total}",
            delta = m.delta,
            attemptText = if (m.attempt > 0) "本库第 ${m.attempt} 次模考" else null,
            streakDays = m.streakDays,
            totalAnswered = m.totalAnswered,
            bankName = m.bankName,
            name = cardName.trim(),
            slogan = slogan.trim(),
            themeId = themeId,
            accentHex = accent
        )
        val ctx = context.applicationContext
        val bmp = withContext(Dispatchers.IO) {
            runCatching { ShareCardRenderer.render(ctx, data) }.getOrNull()
        }
        if (bmp != null) bitmap = bmp.asImageBitmap()
    }

    fun doSave() {
        val b = bitmap ?: return
        scope.launch {
            val name = "题屿成绩卡_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".png"
            val androidBmp = b.asAndroidBitmap()
            val ok = withContext(Dispatchers.IO) {
                runCatching { GallerySave.savePngBitmap(context, androidBmp, name) }
                    .getOrDefault(false)
            }
            msg = if (ok) "已保存到 相册 / 题屿" else "保存失败，请重试"
        }
    }

    fun doShare() {
        val b = bitmap ?: return
        val androidBmp = b.asAndroidBitmap()
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    GalleryShare.sharePngImage(
                        context,
                        androidBmp,
                        "tuyu_score_card.png",
                        "我的模考成绩单",
                        "我在「题屿」的模考拿到 ${outcome.score.toInt()} 分" +
                            "（答对 ${outcome.correct}/${outcome.total}），来和我一起刷题吧！"
                    )
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ui.bgGradient)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // 顶栏：关闭 / 保存 / 分享
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "关闭", color = ui.text, fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 6.dp, vertical = 12.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "保存相册", color = ui.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(enabled = bitmap != null) { doSave() }
                        .padding(horizontal = 6.dp, vertical = 12.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "分享", color = ui.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(enabled = bitmap != null) { doShare() }
                        .padding(horizontal = 6.dp, vertical = 12.dp)
                )
            }
            Text("成绩卡", color = ui.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "重新设计的成绩单 · 可换主题与署名，保存或分享给朋友",
                color = ui.textSub, fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )

            // 预览（即最终产物）；投影+细描边让卡片边界在深浅页面下都清晰
            val cardShape = RoundedCornerShape(24.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val b = bitmap
                if (b != null) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .shadow(16.dp, cardShape)
                            .border(1.dp, ui.ink.copy(alpha = 0.16f), cardShape)
                            .clip(cardShape)
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .shadow(16.dp, cardShape)
                            .border(1.dp, ui.ink.copy(alpha = 0.16f), cardShape)
                            .clip(cardShape)
                            .background(ui.ink.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("正在生成…", color = ui.textSub, fontSize = 14.sp)
                    }
                }
            }

            // 主题
            Text(
                "主题", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp)
            )
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShareCardRenderer.themes().forEach { (id, label) ->
                    val selected = id == themeId
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) ui.ink else ui.ink.copy(alpha = 0.06f))
                            .clickable {
                                themeId = id
                                scope.launch {
                                    runCatching { ServiceLocator.settings.setShareCardTheme(id) }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            label,
                            color = if (selected) ui.onInk else ui.text,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 强调色微调
            Text(
                "强调色", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ACCENTS.forEach { (hex, label) ->
                    val selected = hex == accent
                    val swatch = if (hex.isBlank()) Color(0xFFB9B4A8)
                    else runCatching { Color(android.graphics.Color.parseColor(hex)) }
                        .getOrDefault(Color(0xFFB9B4A8))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) ui.ink else ui.textSub.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    accent = hex
                                    scope.launch {
                                        runCatching { ServiceLocator.settings.setShareCardAccent(hex) }
                                    }
                                }
                        )
                        Text(
                            label, color = ui.textSub, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // 署名
            Text(
                "署名", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ui.ink.copy(alpha = 0.07f))
                    .border(1.dp, ui.ink.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = cardName,
                    onValueChange = { v ->
                        cardName = v.take(8)
                        scope.launch {
                            runCatching { ServiceLocator.settings.setShareCardName(cardName) }
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = ui.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(ui.ink),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (cardName.isEmpty()) {
                                Text(
                                    "落款昵称 · 留空则不显示",
                                    color = ui.textSub, fontSize = 14.sp, maxLines = 1
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            // 标语
            Text(
                "标语", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ui.ink.copy(alpha = 0.07f))
                    .border(1.dp, ui.ink.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = slogan,
                    onValueChange = { v ->
                        slogan = v.take(30)
                        scope.launch {
                            runCatching { ServiceLocator.settings.setShareCardSlogan(slogan) }
                        }
                    },
                    textStyle = TextStyle(color = ui.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(ui.ink),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (slogan.isEmpty()) {
                                Text(
                                    "一句学习目标，如「目标 90 分 · 冲刺下周模考」",
                                    color = ui.textSub, fontSize = 14.sp, maxLines = 1
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            msg?.let {
                Text(
                    it, color = ui.textSub, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}
