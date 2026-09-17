package com.drone.quiz.util

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import com.drone.quiz.LauncherBus
import com.drone.quiz.MainActivity
import com.drone.quiz.R
import com.drone.quiz.ServiceLocator
import com.drone.quiz.data.db.ExamRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 桌面小组件引擎（v2.10.0）：四款极简杂志风 RemoteViews 小组件。
 *
 * - 继续刷题磁贴（2×2）：今日题量大数字 + 连续打卡，点按直落上次进度；
 * - 学习统计卡（4×2）：目标进度环 + 正确率 + 连击 + 「距目标还差 N 题」；
 * - 错题特训卡（2×2）：待攻克错题数，点按直落特训；
 * - 快速模考贴（2×2）：上次模考成绩/合格态，点按沿用上次配置直接开考。
 *
 * 零第三方依赖：AppWidgetProvider + RemoteViews + XML 布局。
 * 深浅色：布局默认浅色资源，运行时按系统夜间模式程序化切换背景/文字色，
 * 不依赖宿主 Launcher 的资源解析（values-night 在 RemoteViews 上不可靠）。
 *
 * 更新触发：recordAnswer / submitExam / abandonExam / clearAllRecords（Repo 钩子）
 * + updatePeriodMillis 30 分钟兜底（跨天翻日/打卡重置）+ 每日目标设置变更。
 */
object WidgetUpdater {

    data class Snapshot(
        val bankName: String,
        val todayAnswered: Int,
        val todayCorrect: Int,
        val streakDays: Int,
        val totalAnswered: Int,
        val accuracyPct: Int,
        val wrongCount: Int,
        val dailyGoal: Int,
        val lastExam: ExamRecordEntity?,
        val hasPendingExam: Boolean
    )

    /** 汇总四款小组件所需数据（全部挂 IO，逐项 runCatching，坏一项不影响其余） */
    suspend fun snapshot(): Snapshot {
        val st = runCatching { ServiceLocator.settings.settings.first() }
            .getOrDefault(com.drone.quiz.data.settings.AppSettings())
        val bank = st.currentBank
        val repo = ServiceLocator.repo
        val (_, todayAnswered, todayCorrect) = runCatching { repo.last7DaysByBank(bank) }
            .getOrDefault(Triple(emptyList(), 0, 0))
        val streak = runCatching { repo.streakDays() }.getOrDefault(0)
        val total = runCatching { repo.totalAnsweredFlow().first() }.getOrDefault(0)
        val acc = runCatching { repo.accuracy(bank) }.getOrDefault(0f)
        val wrong = runCatching { repo.wrongCount(bank) }.getOrDefault(0)
        val exams = runCatching { repo.recentExams(bank).first() }.getOrDefault(emptyList())
        return Snapshot(
            bankName = runCatching { repo.bankNameOf(bank) ?: "题库" }.getOrDefault("题库"),
            todayAnswered = todayAnswered,
            todayCorrect = todayCorrect,
            streakDays = streak,
            totalAnswered = total,
            accuracyPct = Math.round(acc * 100),
            wrongCount = wrong,
            dailyGoal = st.dailyGoal.coerceAtLeast(1),
            lastExam = exams.firstOrNull { it.score != null },
            hasPendingExam = exams.any { it.score == null }
        )
    }

    // ---- 节流推送：recordAnswer 每题都触发，滑杆调目标也会连发，600ms 合并 ----
    private var lastPushAt = 0L

    fun updateAllAsync(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPushAt < 600) return
        lastPushAt = now
        ServiceLocator.appScope.launch {
            runCatching { updateAll(context.applicationContext) }
        }
    }

    suspend fun updateAll(context: Context) {
        val snap = snapshot()
        val manager = AppWidgetManager.getInstance(context) ?: return
        push(context, manager, ContinueWidgetProvider::class.java, continueViews(context, snap))
        push(context, manager, StatsWidgetProvider::class.java, statsViews(context, snap))
        push(context, manager, WrongWidgetProvider::class.java, wrongViews(context, snap))
        push(context, manager, ExamWidgetProvider::class.java, examViews(context, snap))
    }

    private fun push(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<out AppWidgetProvider>,
        views: RemoteViews
    ) {
        runCatching {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }

    // ---- 极简杂志风调色（浅/深） ----
    private class Pal(
        val bgRes: Int,
        val ink: Int,
        val sub: Int,
        val hair: Int,
        val red: Int,
        val green: Int,
        val ringLight: Boolean
    )

    private fun pal(context: Context): Pal {
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) Pal(
            R.drawable.widget_bg_dark,
            0xFFF4F2EA.toInt(), 0xFF8F8F89.toInt(), 0xFF2A2A28.toInt(),
            0xFFE2635A.toInt(), 0xFF57B87E.toInt(), ringLight = false
        ) else Pal(
            R.drawable.widget_bg_light,
            0xFF17171B.toInt(), 0xFF8A8A85.toInt(), 0xFFE9E9E4.toInt(),
            0xFFD9453A.toInt(), 0xFF2E9E5B.toInt(), ringLight = true
        )
    }

    /** 目标进度环 PNG 档位（0~100 每 10% 一档，浅/深两套；v2.10.2 由矢量改为预渲染 PNG） */
    private fun ringRes(pct: Int, light: Boolean): Int {
        val step = (pct / 10).coerceIn(0, 10) * 10
        return if (light) when (step) {
            10 -> R.drawable.widget_ring_l_10; 20 -> R.drawable.widget_ring_l_20
            30 -> R.drawable.widget_ring_l_30; 40 -> R.drawable.widget_ring_l_40
            50 -> R.drawable.widget_ring_l_50; 60 -> R.drawable.widget_ring_l_60
            70 -> R.drawable.widget_ring_l_70; 80 -> R.drawable.widget_ring_l_80
            90 -> R.drawable.widget_ring_l_90; 100 -> R.drawable.widget_ring_l_100
            else -> R.drawable.widget_ring_l_0
        } else when (step) {
            10 -> R.drawable.widget_ring_d_10; 20 -> R.drawable.widget_ring_d_20
            30 -> R.drawable.widget_ring_d_30; 40 -> R.drawable.widget_ring_d_40
            50 -> R.drawable.widget_ring_d_50; 60 -> R.drawable.widget_ring_d_60
            70 -> R.drawable.widget_ring_d_70; 80 -> R.drawable.widget_ring_d_80
            90 -> R.drawable.widget_ring_d_90; 100 -> R.drawable.widget_ring_d_100
            else -> R.drawable.widget_ring_d_0
        }
    }

    /** 小组件/快捷方式统一入口：MainActivity 收 tuyu_nav extra 后交给 AppRoot 路由 */
    private fun tapIntent(context: Context, nav: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            nav.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(LauncherBus.EXTRA_NAV, nav)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun RemoteViews.theme(id: Int, color: Int) = setTextColor(id, color)

    // ---- ① 继续刷题磁贴（2×2） ----
    fun continueViews(context: Context, s: Snapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_continue).apply {
            val p = pal(context)
            setInt(R.id.wcRoot, "setBackgroundResource", p.bgRes)
            theme(R.id.wcLabel, p.ink)
            theme(R.id.wcNum, p.ink)
            theme(R.id.wcUnit, p.sub)
            theme(R.id.wcFoot, p.sub)
            setInt(R.id.wcHair, "setBackgroundColor", p.hair)
            setTextViewText(R.id.wcLabel, "继续刷题")
            setTextViewText(R.id.wcNum, "${s.todayAnswered}")
            setTextViewText(R.id.wcUnit, " 题 · 今日")
            setTextViewText(R.id.wcFoot, "连续打卡 ${s.streakDays} 天 · ${s.bankName}")
            setOnClickPendingIntent(R.id.wcRoot, tapIntent(context, LauncherBus.NAV_CONTINUE))
        }

    // ---- ② 学习统计卡（4×2） ----
    fun statsViews(context: Context, s: Snapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_stats).apply {
            val p = pal(context)
            setInt(R.id.wsRoot, "setBackgroundResource", p.bgRes)
            val pct = ((s.todayAnswered * 100f) / s.dailyGoal).toInt().coerceIn(0, 100)
            setImageViewResource(R.id.wsRing, ringRes(pct, p.ringLight))
            listOf(R.id.wsLabelM, R.id.wsLabelR, R.id.wsFoot).forEach {
                theme(it, p.sub)
            }
            theme(R.id.wsToday, p.ink)
            theme(R.id.wsAcc, p.ink)
            theme(R.id.wsAccSub, p.sub)
            theme(R.id.wsStreak, p.ink)
            theme(R.id.wsGoalSub, p.sub)
            setInt(R.id.wsHairV1, "setBackgroundColor", p.hair)
            setInt(R.id.wsHairV2, "setBackgroundColor", p.hair)
            setTextViewText(R.id.wsToday, "${s.todayAnswered}")
            setTextViewText(R.id.wsAcc, "${s.accuracyPct}%")
            setTextViewText(R.id.wsAccSub, "累计 ${s.totalAnswered} 题")
            setTextViewText(R.id.wsStreak, "${s.streakDays} 天")
            setTextViewText(R.id.wsGoalSub, "答对 ${s.todayCorrect} 题")
            val remain = (s.dailyGoal - s.todayAnswered).coerceAtLeast(0)
            if (remain > 0) {
                setTextViewText(R.id.wsFoot, "距今日目标还差 $remain 题（目标 ${s.dailyGoal} 题）")
                theme(R.id.wsFoot, p.sub)
            } else {
                setTextViewText(R.id.wsFoot, "今日目标已完成 · 连续打卡 ${s.streakDays} 天")
                theme(R.id.wsFoot, p.green)
            }
            setOnClickPendingIntent(R.id.wsRoot, tapIntent(context, LauncherBus.NAV_CONTINUE))
        }

    // ---- ③ 错题特训卡（2×2） ----
    fun wrongViews(context: Context, s: Snapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_wrong).apply {
            val p = pal(context)
            setInt(R.id.wgRoot, "setBackgroundResource", p.bgRes)
            theme(R.id.wgLabel, p.ink)
            theme(R.id.wgNum, if (s.wrongCount > 0) p.red else p.green)
            theme(R.id.wgUnit, p.sub)
            theme(R.id.wgFoot, p.sub)
            setInt(R.id.wgHair, "setBackgroundColor", p.hair)
            setTextViewText(R.id.wgLabel, "错题特训")
            setTextViewText(R.id.wgNum, "${s.wrongCount}")
            setTextViewText(R.id.wgUnit, if (s.wrongCount > 0) " 道待攻克" else " 道错题")
            setTextViewText(
                R.id.wgFoot,
                if (s.wrongCount > 0) "点击进入特训 · 各个击破" else "太棒了 · 保持住"
            )
            setOnClickPendingIntent(R.id.wgRoot, tapIntent(context, LauncherBus.NAV_WRONG))
        }

    // ---- ④ 快速模考贴（2×2） ----
    fun examViews(context: Context, s: Snapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_exam).apply {
            val p = pal(context)
            setInt(R.id.weRoot, "setBackgroundResource", p.bgRes)
            theme(R.id.weLabel, p.ink)
            theme(R.id.weNum, p.ink)
            theme(R.id.weUnit, p.sub)
            theme(R.id.weFoot, p.sub)
            setInt(R.id.weHair, "setBackgroundColor", p.hair)
            val last = s.lastExam
            if (last != null) {
                val score = last.score ?: 0f
                setTextViewText(R.id.weLabel, "快速模考")
                setTextViewText(R.id.weNum, if (score % 1f == 0f) "${score.toInt()}" else "$score")
                setTextViewText(R.id.weUnit, " 分 · 上次模考")
                setTextViewText(
                    R.id.weFoot,
                    buildString {
                        append(if (last.passed == true) "合格" else "未合格")
                        append(" · 共 ${last.total} 题 · 点击再考")
                    }
                )
                theme(R.id.weFoot, if (last.passed == true) p.green else p.red)
            } else {
                setTextViewText(R.id.weLabel, "快速模考")
                setTextViewText(R.id.weNum, "开考")
                setTextViewText(R.id.weUnit, " 沿用上次配置")
                setTextViewText(
                    R.id.weFoot,
                    if (s.hasPendingExam) "有一场模考进行中 · 点击继续" else "还没考过 · 去配置一卷"
                )
            }
            setOnClickPendingIntent(R.id.weRoot, tapIntent(context, LauncherBus.NAV_QUICK_EXAM))
    }
}

/** ① 继续刷题磁贴 */
class ContinueWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                val snap = WidgetUpdater.snapshot()
                val views = WidgetUpdater.continueViews(context, snap)
                ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** ② 学习统计卡 */
class StatsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                val snap = WidgetUpdater.snapshot()
                val views = WidgetUpdater.statsViews(context, snap)
                ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** ③ 错题特训卡 */
class WrongWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                val snap = WidgetUpdater.snapshot()
                val views = WidgetUpdater.wrongViews(context, snap)
                ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** ④ 快速模考贴 */
class ExamWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                val snap = WidgetUpdater.snapshot()
                val views = WidgetUpdater.examViews(context, snap)
                ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
