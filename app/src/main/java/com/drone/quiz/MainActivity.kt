package com.drone.quiz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.drone.quiz.data.settings.AppSettings
import com.drone.quiz.data.settings.RootSettings
import com.drone.quiz.data.settings.conflateForRoot
import com.drone.quiz.screens.common.UsageSignals
import com.drone.quiz.ui.glass.GlassRuntime
import com.drone.quiz.ui.nav.AppRoot
import com.drone.quiz.ui.theme.DroneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    // 刷题时长计时（打赏弹窗门槛：累计 2 小时）：只有刷题答题页在前台时每分钟 +1 分钟，
    // 与弹窗文案「累计刷题超过 2 小时」对齐（v2.9.2 前统计的是 App 全程前台时间）
    private var usageTicker: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        usageTicker?.cancel()
        usageTicker = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                if (UsageSignals.onPracticeScreen) {
                    runCatching { ServiceLocator.settings.addUsageMs(60_000) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavIntent(intent)
    }

    private fun consumeNavIntent(intent: Intent?) {
        // v2.11.1：系统「其他应用打开」（微信/文件管理器 → 题库 ZIP/CSV）
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { ImportBus.push(it) }
            return
        }
        val target = intent?.getStringExtra(LauncherBus.EXTRA_NAV) ?: return
        LauncherBus.push(target)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        BootGuard.log(this, "activity", "MainActivity.onCreate")
        enableEdgeToEdge()
        consumeNavIntent(intent)
        setContent {
            // v2.8.6 性能收敛：根组合只订阅它真正响应的字段（去重后）。
            // 完整 settings flow 对 DataStore 任意 key 写入都会重发——刷题会话快照
            // 每次翻页/作答都落盘，此前每次都会触发「根→AppRoot→整个 NavHost」连锁重组，
            // 叠加液态玻璃渲染造成可感知卡顿（大题库导入后尤为明显）。
            val settings by ServiceLocator.settings.settings
                .conflateForRoot()
                .collectAsStateWithLifecycle(initialValue = RootSettings())
            DroneTheme(
                themeMode = settings.themeMode,
                fontLevel = settings.fontLevel,
                readingFont = settings.readingFont
            ) {
                val context = LocalContext.current
                val snapshot = ServiceLocator.bootSnapshot
                var crashReport by remember { mutableStateOf(CrashGuard.readLast(context)) }
                var ready by remember { mutableStateOf(false) }
                var diagDismissed by remember { mutableStateOf(false) }
                var bannerDismissed by remember { mutableStateOf(false) }

                // 特效三级决策（v2.9.3 果冻模式）：
                // - 上次启动异常（autoSafeMode）→ 无条件安全平涂兜底，独立于用户设置；
                // - 用户开关仅在非崩溃时区分：开 = 液态玻璃（真折射）/ 关 = 果冻（亚克力+gooey）。
                // GlassRuntime 是 mutableState，赋值即全局重组，切换即时生效无需重启。
                GlassRuntime.mode = when {
                    snapshot.autoSafeMode -> GlassRuntime.MODE_SAFE
                    settings.effects -> GlassRuntime.MODE_GLASS
                    else -> GlassRuntime.MODE_GOOEY
                }

                LaunchedEffect(Unit) {
                    BootGuard.log(context, "load", "开始加载题库")
                    // 等待 DataStore 首次真实发射，避免用组合态默认值误判题库版本
                    val persisted = runCatching {
                        ServiceLocator.settings.settings.first()
                    }.getOrDefault(AppSettings())
                    // 导入放入独立协程：不随 UI 等待取消，超时后仍在后台完成并持久化版本
                    val importJob = launch {
                        val result = runCatching {
                            ServiceLocator.repo.ensureBankLoaded(applicationContext, persisted.bankVersion)
                        }
                        result.onSuccess { loadedVersion ->
                            if (loadedVersion > 0) {
                                BootGuard.log(
                                    context, "load",
                                    "题库就绪（版本 $loadedVersion，学习数据已随题库升级重置）"
                                )
                                runCatching { ServiceLocator.settings.setBankVersion(loadedVersion) }
                                // 题库升级后旧题目 id 全部失配，刷题进度快照（v2.11.0 多槽）整体作废
                                runCatching { ServiceLocator.settings.clearAllPracticeSessions() }
                            } else {
                                BootGuard.log(context, "load", "题库就绪")
                            }
                        }
                        result.onFailure {
                            BootGuard.log(context, "load", "题库加载失败: ${it.javaClass.name}: ${it.message}")
                        }
                        // v2.8.0：内置示例题库（多选/填空/简答演示）播种（墓碑内不重生）
                        runCatching {
                            ServiceLocator.repo.ensureSampleLoaded(
                                applicationContext,
                                ServiceLocator.settings.deletedBanks()
                            )
                        }
                    }
                    // 最多等 8 秒：超时也放行主界面，绝不停留在"正在加载题库"死等
                    // （刷题页检测到题数 0→N 会自动重载）
                    val finished = withTimeoutOrNull(8_000) {
                        importJob.join()
                        true
                    } ?: false
                    if (!finished) {
                        BootGuard.log(context, "load", "题库加载超时(>8s)，先行进入主界面，导入后台继续")
                    }
                    ready = true
                    BootGuard.log(context, "load", "主界面开始渲染")
                    // 首帧渲染稳定后标记本次启动健康（此后崩溃不再触发自动安全模式）
                    delay(700)
                    BootGuard.markHealthy(context)
                }

                when {
                    // 上次崩溃报告优先展示
                    crashReport != null -> CrashReportScreen(
                        report = crashReport!!,
                        onClose = {
                            CrashGuard.clear(context)
                            crashReport = null
                        }
                    )
                    ready && snapshot.showDiagnostics && !diagDismissed -> DiagnosticsScreen(
                        fails = snapshot.fails,
                        hasCrashFile = false,
                        effectsOn = GlassRuntime.enabled,
                        onContinue = { diagDismissed = true }
                    )
                    ready -> Box(Modifier.fillMaxSize()) {
                        AppRoot(settings = settings)
                        if (snapshot.autoSafeMode && !bannerDismissed) {
                            SafeModeBanner(
                                effectsOn = GlassRuntime.enabled,
                                onDismiss = { bannerDismissed = true },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }
                    // 加载期给出可见的启动画面
                    else -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("正在加载题库…", fontSize = 14.sp)
                    }
                }
            }
        }
    }

    override fun onStop() {
        usageTicker?.cancel()
        usageTicker = null
        super.onStop()
        // 离开前台视为一次正常使用，兜底标记健康（覆盖"打开即关"的场景）
        BootGuard.markHealthy(this)
    }
}

@Composable
private fun SafeModeBanner(effectsOn: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1811).copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                "检测到上次启动异常退出，已自动关闭画面特效以保证可用",
                color = Color(0xFFF2EBDD),
                fontSize = 12.sp
            )
            Text(
                if (effectsOn) "点击此条不再提示 · 可在 设置-画面特效 重新开启"
                else "特效当前已关闭（设置-画面特效） · 点击此条不再提示",
                color = Color(0xFF9A907F),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun DiagnosticsScreen(
    fails: Int,
    hasCrashFile: Boolean,
    effectsOn: Boolean,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val fullReport = buildString {
        appendLine("== 启动诊断 ==")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("连续异常退出: $fails 次")
        appendLine("画面特效: ${if (effectsOn) "开启" else "已关闭（安全模式）"}")
        appendLine("说明: 未捕获到 Java 崩溃栈时，异常退出通常发生在渲染层（GPU 驱动/着色器），已自动降级保证可用")
        appendLine()
        appendLine("== 启动轨迹 ==")
        appendLine(BootGuard.readLog(context))
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1811))
            .padding(20.dp)
    ) {
        Text(
            "启动诊断",
            color = Color(0xFFEFE9DC),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "连续 $fails 次异常退出，已进入安全模式。以下信息可截图或复制反馈",
            color = Color(0xFF9A907F),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF26221C))
        ) {
            Text(
                fullReport,
                color = Color(0xFFE06666),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
        Row {
            OutlinedButton(
                onClick = {
                    runCatching {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("diag", fullReport))
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制全部") }
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) { Text("继续使用") }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CrashReportScreen(report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val fullReport = buildString {
        appendLine(report.trimEnd())
        appendLine()
        appendLine("== 启动轨迹 ==")
        appendLine(BootGuard.readLog(context))
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1811))
            .padding(20.dp)
    ) {
        Text(
            "启动报告",
            color = Color(0xFFEFE9DC),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "上次异常退出，以下是详细信息（可截图反馈）",
            color = Color(0xFF9A907F),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF26221C))
        ) {
            Text(
                fullReport,
                color = Color(0xFFE06666),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
        Row {
            OutlinedButton(
                onClick = {
                    runCatching {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("crash", fullReport))
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制") }
            Spacer(Modifier.height(0.dp))
            Button(
                onClick = onClose,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) { Text("清除并继续") }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 「其他应用打开」导入中继（v2.11.1）：微信/文件管理器以 ACTION_VIEW 打开题库 ZIP/CSV 时，
 * MainActivity（singleTask）冷启动 onCreate / 热启动 onNewIntent 把 data Uri 写进来，
 * AppRoot 的 ExternalImportHost 消费后读流、内容嗅探解析并弹出导入预览。
 * mutableStateOf 保证组合内可观察；pending 保留到被消费，冷启动早期写入不丢失。
 */
object ImportBus {
    var pending by androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    fun push(uri: android.net.Uri) {
        pending = uri
    }
}

/**
 * 快捷方式/小组件启动目标中继（v2.10.0）：
 * MainActivity（singleTask）冷启动 onCreate / 热启动 onNewIntent 把 tuyu_nav extra 写进来，
 * AppRoot 用 snapshotFlow 消费后导航。mutableStateOf 保证组合内可观察。
 */
object LauncherBus {
    const val EXTRA_NAV = "tuyu_nav"
    const val NAV_CONTINUE = "continue"
    const val NAV_WRONG = "wrong"
    const val NAV_RANDOM = "random"
    const val NAV_QUICK_EXAM = "quickexam"

    private val VALID = setOf(NAV_CONTINUE, NAV_WRONG, NAV_RANDOM, NAV_QUICK_EXAM)

    var pending by androidx.compose.runtime.mutableStateOf<String?>(null)

    fun push(target: String) {
        if (target in VALID) pending = target
    }
}
