package com.drone.quiz.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.drone.quiz.BuildConfig
import com.drone.quiz.R
import com.drone.quiz.ServiceLocator
import com.drone.quiz.util.AppUpdater
import com.drone.quiz.screens.common.ScreenTitle
import com.drone.quiz.screens.common.scrolledFromTopPx
import com.drone.quiz.screens.common.SectionLabel
import com.drone.quiz.screens.common.SegmentedRow
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassToggle
import com.drone.quiz.ui.glass.GlassSlider
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.glass.GlassConfirmDialog
import com.drone.quiz.ui.glass.GlassInputDialog
import com.drone.quiz.ui.glass.BounceContainer
import com.drone.quiz.ui.onboarding.OnboardingBus
import com.drone.quiz.ui.onboarding.onboardingAnchor
import com.drone.quiz.ui.theme.LocalReadingFont
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.ui.theme.ReadingFontOptions
import com.drone.quiz.ui.theme.readingFontOption
import com.drone.quiz.work.ReminderScheduler
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(backdrop: Backdrop) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by ServiceLocator.settings.settings.collectAsState(initial = com.drone.quiz.data.settings.AppSettings())

    var bankInfo by remember { mutableStateOf("暂无题库，点下方「导入题库」") }
    var nameDraft by remember(settings.nickname) { mutableStateOf(settings.nickname) }
    val nameDirty = nameDraft.trim() != settings.nickname
    var importMsg by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // ---- 题库管理（v2.8.0） ----
    var banksState by remember { mutableStateOf<List<Pair<com.drone.quiz.data.db.BankEntity, Int>>>(emptyList()) }
    var banksRefreshTick by remember { mutableIntStateOf(0) }
    var showImportSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<com.drone.quiz.data.db.BankEntity?>(null) }
    var renameTarget by remember { mutableStateOf<com.drone.quiz.data.db.BankEntity?>(null) } // v2.8.7 重命名

    // v2.8.8 检查更新（用户口径：不做自动下载）：查 GitHub Releases 最新版，
    // 有新版弹窗引导浏览器去发布页；无新版/网络失败走轻提示
    var updateChecking by remember { mutableStateOf(false) }
    var updateFound by remember { mutableStateOf<String?>(null) }
    // v2.8.9 修复：检查结果原本写进题库管理区的 importMsg（用户反馈“已是最新版本
    // 跑到题库管理那个地方”），改用独立状态、只渲染在关于弹窗内
    var updateMsg by remember { mutableStateOf<String?>(null) }
    // v2.8.9 关于页由内联卡片改为底部弹窗（用户口径：从下面弹出来的那种）
    var showAboutSheet by remember { mutableStateOf(false) }

    fun checkUpdate() {
        if (updateChecking) return
        updateChecking = true
        scope.launch {
            runCatching { AppUpdater.fetchLatestVersion() }
                .onSuccess { remote ->
                    if (AppUpdater.isNewer(remote, BuildConfig.VERSION_NAME)) {
                        updateFound = remote
                    } else {
                        updateMsg = "已是最新版本 v${BuildConfig.VERSION_NAME}"
                    }
                }
                .onFailure { updateMsg = "检查失败，请稍后重试" }
            updateChecking = false
        }
    }

    LaunchedEffect(banksRefreshTick, settings.currentBank) {
        runCatching { banksState = ServiceLocator.repo.bankListWithCounts() }
    }

    // 通知权限：Android 13+ 系统弹窗请求；拒绝则静默不置位，不再展示说教文案
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                ServiceLocator.settings.setDailyNotify(true)
                ReminderScheduler.ensureChannel(context)
                ReminderScheduler.schedule(context)
                // v2.15.0：顺手请求「后台运行」（电池优化白名单）——
                // 通知走系统精确闹钟，白名单后 ROM 一键清后台也拦不住
                ReminderScheduler.requestRunInBackground(context)
            }
        }
    }

    fun enableDailyNotify() {
        scope.launch {
            ServiceLocator.settings.setDailyNotify(true)
            ReminderScheduler.ensureChannel(context)
            ReminderScheduler.schedule(context)
            ReminderScheduler.requestRunInBackground(context)
        }
    }

    // 壁纸选择器：导入后复制到私有目录（重启/源文件删除后仍可用）。
    // v2.6.3 修复"更换壁纸不生效"：此前固定写 wallpaper.jpg，DataStore 路径字符串不变，
    // 背景层的 LaunchedEffect(settings.wallpaper) 不重触发，永远显示第一次的图；
    // 现在文件名带唯一时间戳（路径必变→必然重新解码），并在导入成功后清理旧文件。

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val dst = java.io.File(context.filesDir, "custom_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { input.copyTo(it) }
                    } ?: error("无法读取图片")
                    val old = settings.wallpaper
                    ServiceLocator.settings.setWallpaper(dst.absolutePath)
                    // 新路径已生效，清理旧文件（内置/导入均存私有目录）
                    if (old.startsWith(context.filesDir.absolutePath) && old != dst.absolutePath) {
                        java.io.File(old).delete()
                    }
                }
            }
        }
    }

    // 内置壁纸：选择后同样复制到私有目录（复用同一条加载链路，支持模糊/纱/折射）
    fun useBuiltinWallpaper(resId: Int, key: String) {
        scope.launch {
            runCatching {
                val dst = java.io.File(context.filesDir, "builtin_${key}_${System.currentTimeMillis()}.jpg")
                context.resources.openRawResource(resId).use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
                val old = settings.wallpaper
                ServiceLocator.settings.setWallpaper(dst.absolutePath)
                if (old.startsWith(context.filesDir.absolutePath) && old != dst.absolutePath) {
                    java.io.File(old).delete()
                }
            }
        }
    }

    // 全局同款 iOS 回弹：与首页/错题本/刷题页一致（此前设置页是硬边界）
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ---- 固定标题（不随滚动；内容滚入时在其下缘柔化渐隐） ----
        Column(Modifier.padding(horizontal = 20.dp)) {
            ScreenTitle("设置", "外观 / 刷题 / 数据", Modifier.padding(vertical = 16.dp))
        }

        // 标题柔化：draw 阶段直读滚动像素（Modifier 稳定无伪影，滑出渐显跟手）
        val scrollState = rememberScrollState()
        BounceContainer(
            Modifier
                .weight(1f)
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {

        // ---- 外观 ----
        SectionLabel("外观")
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth().onboardingAnchor("settings_look"), cornerRadius = 22.dp) {
            Column(Modifier.padding(18.dp)) {
                // ---- 昵称（首页问候语用；默认不取名，只按时间问候；可自定义≤5字，确认生效） ----
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("昵称", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "首页会按时间向你问候",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        // v2.7.2：光标位置随文字起点（此前 TextAlign.End 导致光标顶到最右）；
                        // 宽度 140→112dp（用户反馈"太宽"）
                        Box(
                            Modifier
                                .width(112.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ui.ink.copy(alpha = if (ui.isDark) 0.10f else 0.05f))
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            BasicTextField(
                                value = nameDraft,
                                onValueChange = { nameDraft = it.take(5) },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = ui.text, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = LocalReadingFont.current
                                ),
                                // 光标用正文墨色：accent 橙过扎眼（用户反馈）
                                cursorBrush = SolidColor(ui.text),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (nameDraft.isBlank()) {
                                Text(
                                    "未设置",
                                    color = ui.textSub.copy(alpha = 0.55f), fontSize = 14.sp,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                        // 有未保存修改时出现"确认"（显式生效，替代此前的防抖自动保存）
                        androidx.compose.animation.AnimatedVisibility(nameDirty) {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(ui.accent)
                                    .clickable(
                                        interactionSource = null,
                                        indication = null
                                    ) {
                                        // setNickname 是 suspend：clickable 普通上下文需挂协程（CI #4 教训）
                                        scope.launch {
                                            ServiceLocator.settings.setNickname(nameDraft.trim())
                                        }
                                        focusManager.clearFocus(force = true)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "确认",
                                    color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("主题", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (settings.themeMode) {
                                1 -> "浅色（奶油）"
                                2 -> "深色（暖夜）"
                                else -> "跟随系统"
                            },
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    SegmentedRow(
                        options = listOf("跟随", "浅色", "深色"),
                        selectedIndex = settings.themeMode,
                        onSelect = { scope.launch { ServiceLocator.settings.setThemeMode(it) } },
                        modifier = Modifier.width(180.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("字号", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf("小", "标准", "大", "特大").getOrElse(settings.fontLevel) { "标准" },
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    SegmentedRow(
                        options = listOf("小", "标准", "大", "特大"),
                        selectedIndex = settings.fontLevel,
                        onSelect = { scope.launch { ServiceLocator.settings.setFontLevel(it) } },
                        modifier = Modifier.width(200.dp)
                    )
                }
                // ---- 阅读字体（v2.7.2 新增）：系统默认 + 三款内嵌阅读字体，切换全局生效 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("阅读字体", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            readingFontOption(settings.readingFont).desc,
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    SegmentedRow(
                        options = ReadingFontOptions.map { it.label },
                        selectedIndex = ReadingFontOptions
                            .indexOfFirst { it.id == settings.readingFont }
                            .coerceAtLeast(0),
                        onSelect = { i ->
                            scope.launch {
                                ServiceLocator.settings.setReadingFont(ReadingFontOptions[i].id)
                            }
                        },
                        modifier = Modifier.width(196.dp)
                    )
                }
                // 所见即所得：示例行用当前选中字体渲染（全局切换后整页即预览）
                Text(
                    "这是一段示例文本 Aa 0123",
                    color = ui.text,
                    fontSize = 14.sp,
                    fontFamily = LocalReadingFont.current,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("画面特效", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            // v2.11.2：特效二选一「液态玻璃 / 亚克力模糊」（原果冻，goo 动效已除）。
                            // 副标仍守 v2.8.7 口径（≤10 字，硬上限 15，不换行）
                            if (settings.effects) "液态玻璃 · 推荐"
                            else "亚克力模糊 · 省电",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    GlassToggle(
                        checked = { settings.effects },
                        onCheckedChange = { v ->
                            scope.launch { ServiceLocator.settings.setEffects(v) }
                        },
                        backdrop = backdrop
                    )
                }
                // 底栏玻璃模糊度：三档可调
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("底栏玻璃模糊", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf("轻透", "适中", "朦胧").getOrElse(settings.glassBlur) { "适中" },
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    SegmentedRow(
                        options = listOf("低", "中", "高"),
                        selectedIndex = settings.glassBlur,
                        onSelect = { scope.launch { ServiceLocator.settings.setGlassBlur(it) } },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }

        // ---- 刷题 ----
        SectionLabel("刷题", Modifier.padding(top = 16.dp))
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("题目顺序", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.practiceOrder == 1) "随机" else "顺序",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    SegmentedRow(
                        options = listOf("顺序", "随机"),
                        selectedIndex = settings.practiceOrder,
                        onSelect = { scope.launch { ServiceLocator.settings.setPracticeOrder(it) } },
                        modifier = Modifier.width(150.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("答对自动下一题", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.autoNext) "答对后自动跳转" else "手动切换",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    GlassToggle(
                        checked = { settings.autoNext },
                        onCheckedChange = { v ->
                            scope.launch { ServiceLocator.settings.setAutoNext(v) }
                        },
                        backdrop = backdrop
                    )
                }
                // v2.11.3 答对震动：刷题/特训即时判定答对时轻震一下（考试不受影响）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("答对震动", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.vibrateOnCorrect) "答对时轻震一下（考试不受影响）" else "已关闭",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    GlassToggle(
                        checked = { settings.vibrateOnCorrect },
                        onCheckedChange = { v ->
                            scope.launch { ServiceLocator.settings.setVibrateOnCorrect(v) }
                        },
                        backdrop = backdrop
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("错题移除档位", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "连续答对 ${settings.removeThreshold} 次移除",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    SegmentedRow(
                        options = listOf("1次", "2次", "3次"),
                        selectedIndex = settings.removeThreshold - 1,
                        onSelect = {
                            scope.launch { ServiceLocator.settings.setRemoveThreshold(it + 1) }
                        },
                        modifier = Modifier.width(150.dp)
                    )
                }
                Column(Modifier.padding(top = 18.dp)) {
                    Text("及格分", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${settings.passScore} 分（50–95）",
                        color = ui.textSub, fontSize = 12.sp
                    )
                    GlassSlider(
                        value = { settings.passScore.toFloat() },
                        onValueChange = { v ->
                            scope.launch { ServiceLocator.settings.setPassScore(v.roundToInt()) }
                        },
                        valueRange = 50f..95f,
                        step = 5f,
                        backdrop = backdrop,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }

        // ---- 全局壁纸（背景纹路，可模糊） ----
        SectionLabel("全局壁纸", Modifier.padding(top = 16.dp))
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    if (settings.wallpaper.isBlank()) "未设置 · 默认渐变"
                    else "已设置 · 全局背景",
                    color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "导入后作为全局背景",
                    color = ui.textSub, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // ---- 内置纹路：点击即设为全局背景（复制进私有目录，支持模糊/纱/折射） ----
                val builtins = listOf(
                    Triple("山野清晨", R.drawable.wp_forest_light, "forest_light"),
                    Triple("林海深处", R.drawable.wp_forest_deep, "forest_deep"),
                    Triple("云上航线", R.drawable.wp_sky, "sky"),
                    Triple("黄昏原野", R.drawable.wp_dusk, "dusk")
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    builtins.forEach { (label, resId, key) ->
                        val selected = settings.wallpaper.contains("builtin_$key")
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .then(
                                        if (selected)
                                            Modifier.border(2.dp, ui.accent, RoundedCornerShape(14.dp))
                                        else Modifier
                                    )
                                    .clickable(
                                        interactionSource = null,
                                        indication = null
                                    ) { useBuiltinWallpaper(resId, key) }
                            ) {
                                Image(
                                    painter = painterResource(resId),
                                    contentDescription = label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                            Text(
                                label,
                                color = if (selected) ui.accent else ui.textSub,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(
                        onClick = { wallpaperPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        ) },
                        backdrop = backdrop,
                        surfaceColor = ui.ink,
                        heightDp = 40.dp
                    ) {
                        Text(
                            if (settings.wallpaper.isBlank()) "导入壁纸" else "更换壁纸",
                            color = ui.onInk, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    if (settings.wallpaper.isNotBlank()) {
                        GlassButton(
                            onClick = { scope.launch { ServiceLocator.settings.setWallpaper("") } },
                            backdrop = backdrop,
                            surfaceColor = ui.surface.copy(alpha = 0.6f),
                            heightDp = 40.dp
                        ) {
                            Text("恢复默认", color = ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (settings.wallpaper.isNotBlank()) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("壁纸模糊", color = ui.textSub, fontSize = 11.sp)
                            GlassToggle(
                                checked = { settings.wallpaperBlur },
                                onCheckedChange = { v ->
                                    scope.launch { ServiceLocator.settings.setWallpaperBlur(v) }
                                },
                                backdrop = backdrop
                            )
                        }
                    }
                }
            }
        }

        // ---- 提醒 ----
        SectionLabel("提醒", Modifier.padding(top = 16.dp))
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每日提醒", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.dailyNotify) "已开启 · 刷过不打扰"
                            else "每天定时提醒",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    GlassToggle(
                        checked = { settings.dailyNotify },
                        onCheckedChange = { v ->
                            when {
                                !v -> scope.launch {
                                    ServiceLocator.settings.setDailyNotify(false)
                                    ReminderScheduler.cancel(context)
                                }

                                Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED ->
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                                else -> enableDailyNotify()
                            }
                        },
                        backdrop = backdrop
                    )
                }

                // ---- 护眼提醒（v2.8.6，防沉迷口径）：连续刷题 20 分钟弹窗提醒看看远处 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        // v2.8.7 改名："护眼提醒"→"防沉迷"（用户口径），说明字同步压缩 ≤10 字
                        Text("防沉迷", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.eyeCareReminder) "已开启 · 20 分钟提醒"
                            else "刷题 20 分钟提醒休息",
                            color = ui.textSub, fontSize = 12.sp
                        )
                    }
                    GlassToggle(
                        checked = { settings.eyeCareReminder },
                        onCheckedChange = { v ->
                            scope.launch { ServiceLocator.settings.setEyeCareReminder(v) }
                        },
                        backdrop = backdrop
                    )
                }
            }
        }

        // ---- 题库管理（v2.8.0 多题库） ----
        SectionLabel("题库管理", Modifier.padding(top = 16.dp))
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth().onboardingAnchor("settings_banks"), cornerRadius = 22.dp) {
            Column(Modifier.padding(18.dp)) {
                banksState.forEachIndexed { idx, (bank, cnt) ->
                    val isCurrent = bank.id == settings.currentBank
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (idx > 0) Modifier.padding(top = 8.dp) else Modifier)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isCurrent) ui.ink.copy(alpha = 0.06f) else Color.Transparent)
                            .clickable(
                                interactionSource = null,
                                indication = null
                            ) {
                                if (!isCurrent) {
                                    scope.launch {
                                        runCatching { ServiceLocator.settings.setCurrentBank(bank.id) }
                                        importMsg = "已切换到「${bank.name}」"
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    bank.name,
                                    color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                )
                                if (isCurrent) {
                                    Text(
                                        "  当前使用",
                                        color = ui.correct, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "$cnt 题 · " + if (bank.source == "imported") "导入" else "内置",
                                color = ui.textSub, fontSize = 11.sp
                            )
                        }
                        Icon(
                            AppIcons.Edit, null,
                            tint = ui.textSub,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(
                                    interactionSource = null,
                                    indication = null
                                ) { renameTarget = bank } // v2.8.7 重命名入口
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            AppIcons.Trash, null,
                            tint = ui.textSub,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(
                                    interactionSource = null,
                                    indication = null
                                ) { deleteTarget = bank }
                        )
                    }
                }
                if (banksState.isEmpty()) {
                    Text(
                        bankInfo,
                        color = ui.textSub, fontSize = 12.sp
                    )
                }
                Text(
                    // v2.8.8 说明字 ≤15 字（用户口径），原文 17 字
                    "点名称切换 · 铅笔 · 垃圾桶",
                    color = ui.textSub, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                importMsg?.let {
                    Text(
                        it,
                        color = if (it.startsWith("导入成功") || it.startsWith("已切换") || it.startsWith("记录已清空") || it.startsWith("已删除") || it.startsWith("已重命名")) ui.correct else ui.wrong,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = { showImportSheet = true },
                        backdrop = backdrop,
                        surfaceColor = ui.ink,
                        heightDp = 44.dp
                    ) {
                        Icon(AppIcons.Import, null, tint = ui.onInk, modifier = Modifier.size(16.dp))
                        Text("导入题库", color = ui.onInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    GlassButton(
                        onClick = { showClearConfirm = true },
                        backdrop = backdrop,
                        surfaceColor = ui.wrong.copy(alpha = 0.16f),
                        heightDp = 44.dp
                    ) {
                        Icon(AppIcons.Trash, null, tint = ui.wrong, modifier = Modifier.size(16.dp))
                        Text("清空记录", color = ui.wrong, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---- 使用引导（v2.9.0：随时重走功能导览；首启未看完也会自动弹出，可跳过） ----
        SectionLabel("使用引导", Modifier.padding(top = 16.dp))
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) {
                        OnboardingBus.start(replay = true)
                    }
                    .padding(horizontal = 18.dp, vertical = 13.dp)
            ) {
                Icon(AppIcons.Grid, null, tint = ui.accent, modifier = Modifier.size(18.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                ) {
                    Text("使用引导", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "重新走一遍功能导览，可随时跳过",
                        color = ui.textSub, fontSize = 11.sp
                    )
                }
                Icon(AppIcons.ChevronRight, null, tint = ui.textSub, modifier = Modifier.size(16.dp))
            }
        }

        // ---- 关于（v2.8.9 由内联卡片改为底部弹窗入口，用户口径：从下面弹出来的那种） ----
        SectionLabel("关于", Modifier.padding(top = 16.dp))
        GlassCard(backdrop = backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) {
                        updateMsg = null
                        showAboutSheet = true
                    }
                    .padding(horizontal = 18.dp, vertical = 13.dp)
            ) {
                Icon(AppIcons.Bell, null, tint = ui.accent, modifier = Modifier.size(18.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                ) {
                    Text("关于题屿", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "v${BuildConfig.VERSION_NAME} · 版本与支持",
                        color = ui.textSub, fontSize = 11.sp
                    )
                }
                Icon(AppIcons.ChevronRight, null, tint = ui.textSub, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(130.dp))
        }
        
}
    }

    if (showClearConfirm) {
        GlassConfirmDialog(
            backdrop = backdrop,
            title = "清空做题记录？",
            body = "将清空刷题记录、统计、打卡、错题本与最近模考（含未完成的考试）；已删除的内置题库也会重新恢复。此操作不可撤销。",
            confirmText = "清空",
            dismissText = "取消",
            confirmColor = ui.wrong,
            onConfirm = {
                showClearConfirm = false
                scope.launch {
                    runCatching {
                        ServiceLocator.repo.clearAllRecords()
                        // 墓碑清零 + 版本归零 → 内置题库（无人机 + 示例）立即重新播种
                        ServiceLocator.settings.clearDeletedBanks()
                        ServiceLocator.settings.setBankVersion(0)
                        val v = ServiceLocator.repo.ensureBankLoaded(context, 0)
                        if (v > 0) ServiceLocator.settings.setBankVersion(v)
                        ServiceLocator.repo.ensureSampleLoaded(context, emptyList())
                        ServiceLocator.settings.setCurrentBank(com.drone.quiz.data.repo.Repo.BANK_DRONE)
                        // 刷题进度快照（v2.11.0 多槽全部槽位）一并清掉
                        ServiceLocator.settings.clearAllPracticeSessions()
                    }
                    banksRefreshTick++
                    importMsg = "记录已清空，内置题库已恢复"
                }
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    // 删除题库确认
    deleteTarget?.let { target ->
        GlassConfirmDialog(
            backdrop = backdrop,
            title = "删除题库「${target.name}」？",
            body = buildString {
                append("将删除该题库的全部题目与相关学习记录，不可恢复。")
                if (target.source != "imported") {
                    append("内置题库删除后，可通过「清空记录」恢复。")
                }
                if (target.id == settings.currentBank) {
                    append("它是当前使用的题库，删除后将自动切换到其他题库。")
                }
            },
            confirmText = "删除",
            dismissText = "取消",
            confirmColor = ui.wrong,
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    runCatching {
                        val wasCurrent = target.id == settings.currentBank
                        ServiceLocator.repo.deleteBankData(target.id)
                        // 该库的刷题会话槽一并清掉（v2.11.0 多槽）
                        ServiceLocator.settings.purgeBankSessions(target.id)
                        if (target.source != "imported") {
                            ServiceLocator.settings.addDeletedBank(target.id)
                        }
                        if (wasCurrent) {
                            val rest = ServiceLocator.repo.bankListWithCounts()
                            ServiceLocator.settings.setCurrentBank(
                                rest.firstOrNull()?.first?.id ?: com.drone.quiz.data.repo.Repo.BANK_DRONE
                            )
                        }
                    }
                    banksRefreshTick++
                    importMsg = "已删除「${target.name}」"
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // 重命名题库（v2.8.7）：空名拒绝（对话框内已守卫），Repo 层再兜一层
    renameTarget?.let { target ->
        GlassInputDialog(
            backdrop = backdrop,
            title = "重命名题库",
            initialText = target.name,
            hint = "最长 16 字，留空则不改",
            confirmText = "确定",
            dismissText = "取消",
            onConfirm = { newName ->
                renameTarget = null
                if (newName.isNotEmpty()) {
                    scope.launch {
                        runCatching { ServiceLocator.repo.renameBank(target.id, newName) }
                        banksRefreshTick++
                        importMsg = "已重命名为「$newName」"
                    }
                }
            },
            onDismiss = { renameTarget = null }
        )
    }

    // 发现新版本（v2.8.8）：引导浏览器打开 Releases 页，用户自行下载安装包
    updateFound?.let { v ->
        GlassConfirmDialog(
            backdrop = backdrop,
            title = "发现新版本 v$v",
            body = "当前版本 v${BuildConfig.VERSION_NAME}。将用浏览器打开 GitHub Releases 页面，在新版说明下下载 APK 安装即可（覆盖安装，数据保留）。",
            confirmText = "打开发布页",
            dismissText = "取消",
            onConfirm = {
                updateFound = null
                AppUpdater.openReleases(context)
            },
            onDismiss = { updateFound = null }
        )
    }

    // 关于弹窗（v2.8.9）：内容/状态提升在设置页，关闭重开不丢检查结果；打开时清旧提示
    AboutSheet(
        visible = showAboutSheet,
        backdrop = backdrop,
        updateChecking = updateChecking,
        updateMsg = updateMsg,
        onDismiss = { showAboutSheet = false },
        onOpenReleases = { AppUpdater.openReleases(context) },
        onCheckUpdate = { checkUpdate() },
        // v2.8.10：先关关于弹窗，等退场动画走完再拉起打赏弹窗。
        // 根因：打赏弹窗宿主在 App 启动时就注册进 GlassOverlayPortal（列表底部），
        // 关于弹窗在进设置页时才注册（列表顶部，后组合者在上）——同屏时打赏弹窗
        // 被压在关于弹窗下面，且其上方关于层的透明点击层还会挡住打赏面板操作。
        // 两者不同屏后层级问题自然消失（用户口径：关了关于，再弹奶茶）。
        onSupport = {
            showAboutSheet = false
            scope.launch {
                delay(420) // 关于弹窗退场（滑出 spring≈300ms 视觉完成 + 余量）
                com.drone.quiz.ui.nav.SupportBus.manualOpen = true
            }
        }
    )

    BankImportSheet(
        visible = showImportSheet,
        backdrop = backdrop,
        onDismiss = { showImportSheet = false },
        onImported = { bankId, name, cnt ->
            showImportSheet = false
            scope.launch {
                runCatching { ServiceLocator.settings.setCurrentBank(bankId) }
                banksRefreshTick++
                importMsg = "导入成功：$name（$cnt 题），已切换为新题库"
            }
        }
    )
}

// ==================== 关于弹窗（v2.8.9，用户口径：从下面弹出来的那种） ====================

/** 文楷（应用内置字体）：关于弹窗的文档风标题与正文专用，不随阅读字体设置变化。 */
private val AboutKai = FontFamily(
    Font(R.font.lxgwwenkai_regular, FontWeight.Normal),
    Font(R.font.lxgwwenkai_medium, FontWeight.Bold)
)

@Composable
private fun AboutSheet(
    visible: Boolean,
    backdrop: Backdrop,
    updateChecking: Boolean,
    updateMsg: String?,
    onDismiss: () -> Unit,
    onOpenReleases: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSupport: () -> Unit
) {
    val ui = LocalUi.current
    GlassBottomSheet(visible = visible, backdrop = backdrop, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // 小屏/横屏保底：内容超高时内部滚动；把手拖拽仅顶部热区、不冲突
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            Text(
                "关于题屿",
                color = ui.text, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                fontFamily = AboutKai,
                modifier = Modifier.padding(top = 8.dp)
            )
            // 虚线分隔（参考用户旧版刷题本的文档式排版）
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(1.dp)
            ) {
                drawLine(
                    color = ui.textSub.copy(alpha = 0.40f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                )
            }
            Text(
                "题屿把无人机装调修理的题库搬进了手机，顺序刷题、随机刷题、模拟考试和错题本，都在这座小岛上。",
                color = ui.text, fontSize = 14.sp, lineHeight = 25.sp, fontFamily = AboutKai,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                "做它的原因很简单：刷题不想一直抱着厚厚的文档，想在手机上随时做几道题。做着做着，就长成了现在的样子。",
                color = ui.text, fontSize = 14.sp, lineHeight = 25.sp, fontFamily = AboutKai,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "如果发现题目有错、选项不对，或者想要新功能，欢迎告诉我。人生的意义在于折腾，希望这座小岛也能陪你顺利通过考试。",
                color = ui.text, fontSize = 14.sp, lineHeight = 25.sp, fontFamily = AboutKai,
                modifier = Modifier.padding(top = 12.dp)
            )
            // 版本行可点：直达 GitHub Releases 页（沿用 v2.8.8 行为）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onOpenReleases
                    )
            ) {
                Text(
                    "v${BuildConfig.VERSION_NAME} · GitHub 发布页",
                    color = ui.textSub, fontSize = 11.sp
                )
                Icon(AppIcons.ChevronRight, null, tint = ui.textSub, modifier = Modifier.size(12.dp))
            }
            // 等高双胶囊（v2.8.8 的「请作者喝杯奶茶」换行导致不等高，在此根治：
            // GlassButton .height() 固定高度 + maxLines(1) + 文案缩短）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 支持作者（v2.8.0）：复用打赏弹窗，手动打开不占用自动触达机会
                GlassButton(
                    onClick = onSupport,
                    backdrop = backdrop,
                    surfaceColor = ui.accent.copy(alpha = 0.14f),
                    heightDp = 44.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "☕ 请喝奶茶",
                        color = ui.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                // 检查更新（v2.8.8）：查最新 Release，有新版弹窗引导浏览器打开
                GlassButton(
                    onClick = onCheckUpdate,
                    backdrop = backdrop,
                    surfaceColor = ui.ink,
                    heightDp = 44.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AppIcons.Refresh, null, tint = ui.onInk, modifier = Modifier.size(15.dp))
                    Text(
                        if (updateChecking) "检查中…" else "检查更新",
                        color = ui.onInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            // 检查结果只落在本弹窗内（v2.8.8 bug：结果误写入题库管理区的 importMsg）
            updateMsg?.let { msg ->
                Text(
                    msg,
                    color = if (msg.startsWith("已是最新")) ui.correct else ui.wrong,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
        }
    }
}
