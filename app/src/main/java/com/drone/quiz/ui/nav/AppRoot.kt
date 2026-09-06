package com.drone.quiz.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.drone.quiz.LauncherBus
import com.drone.quiz.R
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.settings.RootSettings
import com.drone.quiz.screens.ExamSessionHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.drone.quiz.screens.ExamConfigScreen
import com.drone.quiz.screens.ExamResultScreen
import com.drone.quiz.screens.ExamScreen
import com.drone.quiz.screens.HomeScreen
import com.drone.quiz.screens.PracticeConfigScreen
import com.drone.quiz.screens.PracticeRunScreen
import com.drone.quiz.screens.SearchScreen
import com.drone.quiz.screens.SettingsScreen
import com.drone.quiz.screens.WrongBookScreen
import com.drone.quiz.screens.common.LocalNavAnimatedVisibilityScope
import com.drone.quiz.screens.common.LocalSharedTransitionScope
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassBottomSheet
import com.drone.quiz.ui.glass.GlassBottomTabs
import com.drone.quiz.ui.glass.GlassButton
import com.drone.quiz.ui.glass.GlassOverlayPortal
import com.drone.quiz.ui.glass.LocalBgBackdrop
import com.drone.quiz.ui.glass.LocalContentBackdrop
import com.drone.quiz.ui.glass.OverlayBlur
import com.drone.quiz.ui.glass.TabIconSlot
import com.drone.quiz.ui.onboarding.OnboardingBus
import com.drone.quiz.ui.onboarding.TourHost
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.util.GallerySave
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val PRACTICE_PATTERN = "practice?src={src}"
    const val PRACTICE = "practice"
    const val PRACTICE_RUN_PATTERN =
        "practiceRun?src={src}&type={type}&cat={cat}&resume={resume}&limit={limit}"
    const val EXAM_CONFIG = "examConfig"
    const val EXAM_RUN_PATTERN = "examRun/{examId}"
    const val EXAM_RESULT_PATTERN = "examResult/{examId}"
    const val WRONG = "wrong"
    const val SETTINGS = "settings"
    const val SEARCH = "search"

    /** Tab 页对应的 destination route（practice 的 destination route 是 pattern 形式） */
    val tabDestinations = listOf(HOME, PRACTICE_PATTERN, EXAM_CONFIG, WRONG, SETTINGS)

    /** Tab 顺序对应的导航目标 */
    val tabTargets = listOf(HOME, PRACTICE, EXAM_CONFIG, WRONG, SETTINGS)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(settings: RootSettings) {
    val ui = LocalUi.current
    val navController: NavHostController = rememberNavController()
    val appScope = rememberCoroutineScope()

    // 双记录层架构（对齐 Kyant0 官方 demo）:
    // 1. bgBackdrop —— 只记录背景渐变（"壁纸"）。内容流玻璃卡片折射它，
    //    卡片不在该记录层内 → 无循环采样，无 SIGSEGV 风险。
    // 2. contentBackdrop —— 记录 NavHost 内容。底栏折射"背景+内容"，
    //    底栏在其记录层之外 → 安全。
    val bgBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabIndex = Routes.tabDestinations.indexOf(route)
    val isTabRoute = tabIndex >= 0

    fun navigateTab(index: Int) {
        if (index == tabIndex) return
        val target = Routes.tabTargets[index]
        navController.navigate(target) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // v2.10.0：快捷方式/小组件启动目标（LauncherBus）→ 导航路由。
    // singleTask 热启动走 onNewIntent，冷启动走 onCreate intent，两处都汇入 LauncherBus。
    LaunchedEffect(Unit) {
        snapshotFlow { LauncherBus.pending }.collect { target ->
            if (target == null) return@collect
            LauncherBus.pending = null
            when (target) {
                LauncherBus.NAV_CONTINUE ->
                    navController.navigate("practiceRun?src=all&type=all&cat=all&resume=true") {
                        launchSingleTop = true
                    }
                LauncherBus.NAV_WRONG -> {
                    // 沿用上次特训筛选（错题本开特训时记忆），无记忆则全部错题
                    val st = runCatching { ServiceLocator.settings.settings.first() }.getOrNull()
                    val type = android.net.Uri.encode(st?.wrongLastType?.ifEmpty { "all" } ?: "all")
                    val cat = android.net.Uri.encode(st?.wrongLastCat?.ifEmpty { "all" } ?: "all")
                    navController.navigate("practiceRun?src=wrong&type=$type&cat=$cat&resume=false") {
                        launchSingleTop = true
                    }
                }
                LauncherBus.NAV_RANDOM ->
                    navController.navigate(
                        "practiceRun?src=random&type=all&cat=all&resume=false&limit=20"
                    ) {
                        launchSingleTop = true
                    }
                LauncherBus.NAV_QUICK_EXAM -> appScope.launch {
                    // 快速模考：沿用上次开考配置直接组卷，无配置/组卷失败回模考配置页兜底
                    val st = runCatching { ServiceLocator.settings.settings.first() }.getOrNull()
                    if (st == null) {
                        navigateTab(2)
                        return@launch
                    }
                    val cfg = runCatching { ServiceLocator.settings.examQuickConfig() }.getOrNull()
                    if (cfg == null) {
                        navigateTab(2)
                        return@launch
                    }
                    val started = runCatching {
                        ServiceLocator.repo.startExam(
                            bankId = st.currentBank,
                            counts = cfg.counts,
                            durationSec = cfg.durationMin * 60,
                            typeOrder = cfg.typeOrder,
                            passLine = st.passScore
                        )
                    }.getOrNull()
                    val id = started?.first ?: 0L
                    val qs = started?.second ?: emptyList()
                    if (id <= 0L || qs.isEmpty()) {
                        navigateTab(2)
                        return@launch
                    }
                    ExamSessionHolder.examId = id
                    ExamSessionHolder.questions = qs
                    ExamSessionHolder.durationSec = cfg.durationMin * 60
                    ExamSessionHolder.outcome = null
                    navController.navigate("examRun/$id") { launchSingleTop = true }
                }
            }
        }
    }

    // 弹窗打开时内容层真模糊（iOS 风格），替代深色遮罩。
    // 弹窗面板自身渲染在 PortalHost（模糊区之外），不再被连帶模糊。
    // v2.8.3 提帧：半径 16→12dp、时长 220→160ms（模糊逐帧重算是弹窗掉帧主因，用户反馈）；
    // 壁纸层单独 8dp（壁纸已被自身模糊+主题纱处理，无需同步全强度）
    val overlayBlur by animateDpAsState(
        targetValue = if (OverlayBlur.active) 12.dp else 0.dp,
        animationSpec = tween(160),
        label = "overlayBlur"
    )
    val overlayBgBlur by animateDpAsState(
        targetValue = if (OverlayBlur.active) 8.dp else 0.dp,
        animationSpec = tween(160),
        label = "overlayBgBlur"
    )

    // v2.8.4：壁纸平均亮度（提交按钮等前景自适应对比用），随壁纸变化在 IO 线程重算
    var wallLuminance by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(settings.wallpaper) {
        wallLuminance = if (settings.wallpaper.isBlank()) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val raw = android.graphics.BitmapFactory.decodeFile(settings.wallpaper)
                    ?: return@runCatching null
                // 降采样 12x12 后取平均亮度（Rec.709 加权），避免全图逐像素开销
                val small = android.graphics.Bitmap.createScaledBitmap(raw, 12, 12, true)
                var acc = 0.0
                val n = small.width * small.height
                for (x in 0 until small.width) for (y in 0 until small.height) {
                    val c = small.getPixel(x, y)
                    acc += (0.2126 * android.graphics.Color.red(c) +
                            0.7152 * android.graphics.Color.green(c) +
                            0.0722 * android.graphics.Color.blue(c)) / 255.0
                }
                (acc / n).toFloat()
            }.getOrNull()
        }
    }

    CompositionLocalProvider(
        LocalBgBackdrop provides bgBackdrop,
        LocalContentBackdrop provides contentBackdrop,
        com.drone.quiz.ui.theme.LocalWallpaperLuminance provides wallLuminance
    ) {
        Box(Modifier.fillMaxSize()) {
        // 层 1：背景（默认渐变 / 可选全局壁纸作"纹路"，可模糊）
        var wallBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        androidx.compose.runtime.LaunchedEffect(settings.wallpaper) {
            wallBmp = if (settings.wallpaper.isBlank()) null
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(settings.wallpaper)?.asImageBitmap()
                }.getOrNull()
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(bgBackdrop)
                // v2.8.2：弹窗打开时壁纸层同步真模糊（此前只模糊内容层，
                // 自定义壁纸用户能明显看到玻璃后的壁纸仍是清晰的，用户反馈）
                // v2.8.3：壁纸层降到 8dp（配合自身 24dp 壁纸模糊足够，减轻实时开销）
                .blur(overlayBgBlur)
                .background(ui.bgGradient)
        ) {
            val bmp = wallBmp
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .then(if (settings.wallpaperBlur) Modifier.blur(24.dp) else Modifier)
                )
                // 主题纱：字体颜色自适应的等价实现——任意壁纸上保证文字对比
                // （亮色叠米白纱、深色叠墨色纱；开启模糊后壁纸干扰更低，纱可更透）
                val scrim by animateColorAsState(
                    targetValue = if (ui.isDark)
                        Color(0xFF161310).copy(alpha = if (settings.wallpaperBlur) 0.40f else 0.54f)
                    else
                        Color(0xFFF6F1E6).copy(alpha = if (settings.wallpaperBlur) 0.36f else 0.50f),
                    animationSpec = tween(300),
                    label = "wallScrim"
                )
                Box(Modifier.matchParentSize().background(scrim))
            }
        }

        // 层 2：内容层（透明背景，浮于背景之上；同时作为底栏折射的内容源）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(contentBackdrop)
                .blur(overlayBlur)
        ) {
            SharedTransitionLayout {
            // Hero 根因修复：scope 从未 provide → heroSearchField() 永远走
            // "current==null 则原样返回"的兜底，sharedElement 从未挂上（v2.7.0 病灶）
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                // 统一转场：淡入 + 轻缩放，单一动画源，杜绝重叠
                enterTransition = {
                    fadeIn(tween(280)) + scaleIn(initialScale = 0.94f, animationSpec = tween(280))
                },
                exitTransition = {
                    fadeOut(tween(200)) + scaleOut(targetScale = 0.97f, animationSpec = tween(200))
                },
                popEnterTransition = {
                    fadeIn(tween(280)) + scaleIn(initialScale = 0.94f, animationSpec = tween(280))
                },
                popExitTransition = {
                    fadeOut(tween(200)) + scaleOut(targetScale = 0.97f, animationSpec = tween(200))
                }
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        backdrop = bgBackdrop,
                        onPractice = { navigateTab(1) },
                        onExam = { navigateTab(2) },
                        onWrong = { navigateTab(3) },
                        // 与底栏切 tab 行为完全一致（保存页面状态、栈可预测）
                        onSettings = { navigateTab(4) }
                    )
                }
                // 刷题 Tab：配置入口页（选范围/分类后进入全屏刷题，不再直接刷）。
                // 此 route 是 tab destination（有底栏），只承载配置页；
                // 全屏刷题一律走 practiceRun（非 tab route，无底栏遮挡）
                composable(Routes.PRACTICE_PATTERN) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                    PracticeConfigScreen(
                        backdrop = bgBackdrop,
                        onSearch = {
                            navController.navigate(Routes.SEARCH) { launchSingleTop = true }
                        },
                        onStart = { src2, type, cat, resume ->
                            val catEnc = android.net.Uri.encode(cat)
                            // v2.8.4：type 可能是多选题型逗号串（"single,judge"），统一 URL 编码更稳
                            val typeEnc = android.net.Uri.encode(type)
                            navController.navigate(
                                "practiceRun?src=$src2&type=$typeEnc&cat=$catEnc&resume=$resume"
                            ) { launchSingleTop = true }
                        }
                    )
                    }
                }
                // 题目搜索（题干/选项/解析全文检索）
                composable(Routes.SEARCH) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                    SearchScreen(
                        backdrop = bgBackdrop,
                        onBack = { navController.popBackStack() }
                    )
                    }
                }
                // 全屏刷题页（非 Tab destination → 无底栏遮挡；返回即回到配置页）
                composable(
                    Routes.PRACTICE_RUN_PATTERN,
                    arguments = listOf(
                        navArgument("src") { defaultValue = "all" },
                        navArgument("type") { defaultValue = "all" },
                        navArgument("cat") { defaultValue = "all" },
                        navArgument("resume") { defaultValue = "false" },
                        navArgument("limit") { defaultValue = "0" }
                    )
                ) { entry ->
                    val src = entry.arguments?.getString("src") ?: "all"
                    val type = entry.arguments?.getString("type") ?: "all"
                    val cat = entry.arguments?.getString("cat") ?: "all"
                    val resume = entry.arguments?.getString("resume") == "true"
                    val limit = entry.arguments?.getString("limit")?.toIntOrNull() ?: 0
                    PracticeRunScreen(
                        backdrop = bgBackdrop,
                        src = src,
                        type = type,
                        cat = cat,
                        resume = resume,
                        limit = limit,
                        onExit = { navController.popBackStack() }
                    )
                }
                composable(Routes.EXAM_CONFIG) {
                    ExamConfigScreen(
                        backdrop = bgBackdrop,
                        onStart = { examId ->
                            navController.navigate("examRun/$examId") { launchSingleTop = true }
                        },
                        onResumeExam = { examId ->
                            navController.navigate("examRun/$examId") { launchSingleTop = true }
                        },
                        // 点击已完成的历史记录 → 重进该场模考成绩页（成绩从 DB 重建）
                        onOpenResult = { examId ->
                            navController.navigate("examResult/$examId") { launchSingleTop = true }
                        }
                    )
                }
                composable(Routes.EXAM_RUN_PATTERN) { entry ->
                    val examId = entry.arguments?.getString("examId")?.toLongOrNull() ?: 0L
                    ExamScreen(
                        backdrop = bgBackdrop,
                        examId = examId,
                        onSubmit = { id ->
                            navController.navigate("examResult/$id") {
                                popUpTo(Routes.EXAM_CONFIG)
                                launchSingleTop = true
                            }
                        },
                        // 放弃考试：删除该次模考记录（此前只退页面，DB 残留"进行中"记录）
                        onExit = {
                            appScope.launch {
                                runCatching { ServiceLocator.repo.abandonExam(examId) }
                            }
                            navController.popBackStack()
                        }
                    )
                }
                composable(Routes.EXAM_RESULT_PATTERN) { entry ->
                    val examId = entry.arguments?.getString("examId")?.toLongOrNull() ?: 0L
                    ExamResultScreen(
                        backdrop = bgBackdrop,
                        examId = examId,
                        onHome = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.HOME) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onWrong = {
                            navController.navigate(Routes.WRONG) { launchSingleTop = true }
                        },
                        // 删除记录成功 → 返回上一页（模考配置页，列表已自动刷新）
                        onDeleted = { navController.popBackStack() }
                    )
                }
                composable(Routes.WRONG) {
                    WrongBookScreen(
                        backdrop = bgBackdrop,
                        // 特训按当前筛选刷（v2.8.0 修复：此前无论筛选什么都刷全部错题）
                        onPractice = { type, cat ->
                            val catEnc = android.net.Uri.encode(cat)
                            val typeEnc = android.net.Uri.encode(type)
                            navController.navigate(
                                "practiceRun?src=wrong&type=$typeEnc&cat=$catEnc&resume=false"
                            ) { launchSingleTop = true }
                        }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(backdrop = bgBackdrop)
                }
            }
            }
            }
        }

        // 浮动玻璃底栏（仅 Tab 页显示；离场下滑独立动画，不与页面转场叠加）
        // 注：不给底栏套 Modifier.blur——方形 blur 层会在胶囊四周留下方框裁剪痕迹，
        // 底栏玻璃本身折射的就是已模糊的内容层，视觉已一致
        AnimatedVisibility(
            visible = isTabRoute,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(
                    bottom = 10.dp + WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding()
                ),
            enter = slideInVertically(
                animationSpec = tween(260),
                initialOffsetY = { it }
            ) + fadeIn(tween(260)),
            exit = slideOutVertically(
                animationSpec = tween(200),
                targetOffsetY = { it }
            ) + fadeOut(tween(180))
        ) {
            GlassBottomTabs(
                selectedTabIndex = { tabIndex.coerceAtLeast(0) },
                onTabSelected = { index -> navigateTab(index) },
                // 折射"背景 + 滚动内容"：内容从底栏下方滑过时透过玻璃可见
                backdrop = rememberCombinedBackdrop(bgBackdrop, contentBackdrop),
                tabsCount = 5
            ) { index ->
                // index == -1：玻璃底胶囊与隐藏染色层要求渲染"整排 5 个图标位"（官方约定）；
                // 此前 -1 误入 else 分支只渲染了一个全宽 Tune 图标，导致底栏 5 个图标全部消失
                // 单击 tab 即切换（官方 LiquidBottomTab 同款 Role.Tab），拖拽选中块仍可用
                val slot: @Composable RowScope.(Int) -> Unit = { i ->
                    when (i) {
                        0 -> TabIconSlot(0, AppIcons.Home) { navigateTab(0) }
                        1 -> TabIconSlot(1, AppIcons.Cards) { navigateTab(1) }
                        2 -> TabIconSlot(2, AppIcons.Timer) { navigateTab(2) }
                        3 -> TabIconSlot(3, AppIcons.BookWrong) { navigateTab(3) }
                        else -> TabIconSlot(4, AppIcons.Tune) { navigateTab(4) }
                    }
                }
                if (index == -1) {
                    repeat(5) { i -> slot(i) }
                } else {
                    slot(index)
                }
            }
        }

        // 层 4：弹窗传送门宿主（内容模糊区 + 底栏之上）。
        // 面板在此渲染 → 永远清晰；独立读作用域，避免弹窗高频重组拖动整个 AppRoot。
        PortalHost()

        // 层 5：支持作者弹窗（累计使用 2h 触发一次；考试中不弹，出考试再弹）
        // v2.8.8：不再从根级传 RootSettings（usageMs 每分钟变化会拖动全根重组）
        SupportPromptHost(backdrop = bgBackdrop, currentRoute = route)

        // 层 6：首启功能引导（v2.9.0，用户口径：页面高亮气泡带着逛一圈）。
        // 首启（onboarding_done=false）自动开演，跳过/翻完即落标记不再弹；
        // 设置页「使用引导」入口随时重看（OnboardingBus.start(replay=true)）。
        LaunchedEffect(Unit) {
            val done = runCatching {
                ServiceLocator.settings.settings.first().onboardingDone
            }.getOrDefault(true)
            if (!done) {
                delay(900) // 等首帧、壁纸、底栏动画稳定后再开演
                OnboardingBus.start(replay = false)
            }
        }
        TourHost(
            backdrop = bgBackdrop,
            currentTab = tabIndex.coerceAtLeast(0),
            onNavigateTab = { navigateTab(it) },
            onFinish = {
                appScope.launch { runCatching { ServiceLocator.settings.completeOnboarding() } }
            }
        )
    }
    }
}

/** 弹窗传送门宿主：单独提取以隔离 GlassOverlayPortal.entries 的读取重组范围。 */
@Composable
private fun PortalHost() {
    GlassOverlayPortal.entries.forEach { entry ->
        // key 稳定槽位：列表增减时不串位、不丢 remember 状态
        key(entry.id) { entry.content() }
    }
}

// ==================== 支持作者（累计使用 2h 打赏弹窗，v2.7.4） ====================

private const val SUPPORT_USAGE_THRESHOLD_MS = 2 * 60 * 60 * 1000L

/** 手动打开打赏弹窗的全局开关（设置页「请作者喝杯奶茶」入口用，v2.8.0）。 */
object SupportBus {
    var manualOpen by mutableStateOf(false)
}

/**
 * 触发宿主：累计使用超 2h 且未弹过、未拒绝 → 弹一次。
 * 用户正在考试（examRun 路由）时不弹——面板收起且不标记"已弹"，出考试后本 effect 自动拉起。
 * 手动打开（SupportBus）不受 2h/考试路由限制，且关闭不标记"已弹"。
 *
 * v2.8.8：usageMs/supportPrompted/supportRefused 从 RootSettings 剔除后由本组件内部
 * 自行收集——它们每分钟都在变（前台计时 +60s），挂在根级会让 MainActivity → AppRoot
 * → NavHost 每分钟全量连锁重组（滚动中偶发掉帧）。重组范围收敛到这个小组件自身。
 */
@Composable
private fun SupportPromptHost(
    backdrop: Backdrop,
    currentRoute: String?
) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var usageMs by remember { mutableStateOf(0L) }
    var prompted by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ServiceLocator.settings.settings
            .map { Triple(it.usageMs, it.supportPrompted, it.supportRefused) }
            .distinctUntilChanged()
            .collect { (u, p, r) -> usageMs = u; prompted = p; refused = r }
    }

    // 手动入口：设置页点击立即拉起（考试中也不打断——考试页无此入口，仅作防御）
    LaunchedEffect(SupportBus.manualOpen) {
        if (SupportBus.manualOpen) {
            SupportBus.manualOpen = false
            if (currentRoute?.startsWith("examRun") != true) {
                manual = true
                show = true
            }
        }
    }

    LaunchedEffect(usageMs, prompted, refused, currentRoute) {
        val inExam = currentRoute?.startsWith("examRun") == true
        if (inExam) {
            show = false
            return@LaunchedEffect
        }
        val eligible = usageMs >= SUPPORT_USAGE_THRESHOLD_MS && !prompted && !refused
        if (eligible) {
            manual = false
            show = true
        }
    }

    GlassBottomSheet(
        visible = show,
        backdrop = backdrop,
        onDismiss = {
            show = false
            // 自动触发的关闭视为"已弹过"；手动打开不标记，保留自动触达机会
            if (!manual) {
                scope.launch { runCatching { ServiceLocator.settings.setSupportPrompted() } }
            }
        }
    ) {
        SupportSheetContent(
            backdrop = backdrop,
            usageMs = usageMs,
            onRefuse = {
                show = false
                scope.launch { runCatching { ServiceLocator.settings.setSupportRefused() } }
            },
            onClose = {
                show = false
                if (!manual) {
                    scope.launch { runCatching { ServiceLocator.settings.setSupportPrompted() } }
                }
            }
        )
    }
}

/** 累计前台使用时长的可读文本（v2.8.3：文案随真实时长变化，不再写死"2 小时"）。 */
private fun formatUsage(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h <= 0L -> "$totalMin 分钟"
        m == 0L -> "$h 小时"
        else -> "$h 小时 $m 分钟"
    }
}

/** 弹窗内容两态：0 = 询问（看码/拒绝），1 = 收款码（保存到相册/完成）。 */
@Composable
private fun SupportSheetContent(
    backdrop: Backdrop,
    usageMs: Long,
    onRefuse: () -> Unit,
    onClose: () -> Unit
) {
    val ui = LocalUi.current
    val context = LocalContext.current
    var stage by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            // v2.8.3：两态内容高度变化平滑过渡（面板底缘固定，向上生长）
            .animateContentSize(tween(240))
    ) {
        // v2.8.3：两态切换加推移动画——旧内容向上滑出、新内容从下滑入
        //（原先硬切换"跟放 PPT 似的"，用户口径：原内容被拉上去一点）
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(240))) togetherWith
                        (slideOutVertically(tween(200)) { -it / 3 } + fadeOut(tween(160)))
                } else {
                    (slideInVertically(tween(280)) { -it / 3 } + fadeIn(tween(240))) togetherWith
                        (slideOutVertically(tween(200)) { it / 3 } + fadeOut(tween(160)))
                }
            },
            label = "supportStage"
        ) { s ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (s == 0) "喜欢题屿吗？" else "感谢支持",
                    color = ui.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )

                if (s == 0) {
                    Text(
                        "你已在题屿累计使用 ${formatUsage(usageMs)}。如果这个 APP 对你有帮助，可以请作者喝杯奶茶，支持一下持续更新~",
                        color = ui.textSub, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onRefuse,
                            backdrop = backdrop,
                            surfaceColor = ui.surface.copy(alpha = 0.6f),
                            heightDp = 48.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("以后别提醒我", color = ui.textSub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        GlassButton(
                            onClick = { stage = 1 },
                            backdrop = backdrop,
                            surfaceColor = ui.ink,
                            heightDp = 48.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("看看收款码", color = ui.onInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // 收款码：原图 RGBA 可能透明底，必须垫白底圆角保证扫码对比度
                    val qrBitmap = remember {
                        runCatching {
                            android.graphics.BitmapFactory.decodeStream(
                                context.resources.openRawResource(R.raw.support_qr)
                            )?.asImageBitmap()
                        }.getOrNull()
                    }
                    Box(
                        Modifier
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    ) {
                        val bmp = qrBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "收款码",
                                contentScale = ContentScale.Fit,
                                // v2.8.6：截图素材本身是直角的，不裁切的话圆角白卡里会露出
                                // 一张四角见方的收款码截图（用户反馈"没有被外面那层圆角裁切"），
                                // 给图片自身加内层圆角，与外层白卡同心
                                modifier = Modifier
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Text(
                                "收款码加载失败",
                                color = Color.Black.copy(alpha = 0.6f), fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 40.dp, vertical = 60.dp)
                            )
                        }
                    }
                    Text(
                        when {
                            saved -> "已保存到相册「题屿」文件夹，扫码即可支持"
                            saveFailed -> "保存失败，可截图本页收款码"
                            else -> "长按图片或保存后扫码即可支持"
                        },
                        color = if (saved) ui.correct else ui.textSub,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = {
                                if (!saved) {
                                    saved = GallerySave.savePngFromRaw(
                                        context, R.raw.support_qr, "tiyu_support_qr.png"
                                    )
                                    saveFailed = !saved
                                }
                            },
                            backdrop = backdrop,
                            surfaceColor = if (saved) ui.surface.copy(alpha = 0.6f) else ui.ink,
                            heightDp = 48.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (saved) "已保存" else "保存到相册",
                                color = if (saved) ui.textSub else ui.onInk,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        GlassButton(
                            onClick = onClose,
                            backdrop = backdrop,
                            surfaceColor = ui.surface.copy(alpha = 0.6f),
                            heightDp = 48.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("完成", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
