package com.drone.quiz.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drone.quiz.MainActivity
import com.drone.quiz.R
import com.drone.quiz.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 每日智能提醒 v2（v2.15.0 调度引擎全面重写）。
 *
 * 旧实现 = WorkManager OneTime 自续：job 随进程存活，国产 ROM 杀后台/深度休眠
 * 下 job 蒸发，只有下次打开 APP 重排才能复活——用户实测「每次打开 APP 才会通知」。
 *
 * 新实现 = AlarmManager 精确闹钟：时刻由系统保管，进程死了也准时触发——
 * - [ReminderReceiver] 收到触发广播后直接发通知 + 排明天（goAsync 短事务）；
 * - 开机 / 覆盖安装后自动重排（BOOT_COMPLETED / MY_PACKAGE_REPLACED）；
 * - 开启提醒时请求电池优化白名单（系统「后台运行」弹窗，[ReminderScheduler.requestRunInBackground]），
 *   治国产 ROM 一键清后台；不驻留前台服务——省电且不受 Android 14 FGS 限制；
 * - APP 每次打开自愈式补排（MainActivity，schedule 幂等：时刻未到不变化）；
 * - 精确权限：SCHEDULE_EXACT_ALARM（31–32 默认授予）+ USE_EXACT_ALARM（33+ 自动授予），
 *   被收回时降级 setAndAllowWhileIdle（Doze 窗口内仍会触发）。
 * 智能时刻口径不变：最近 10 天每天首次刷题时刻的中位数（无数据默认 19:30），
 * 夹在 10:00–21:30；当天已经刷过题保持安静。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val fire = action == ReminderScheduler.ACTION_FIRE
        val rearms = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!fire && !rearms) return

        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                if (fire) {
                    fireReminder(context)
                } else {
                    // 开机/升级后闹钟已清空 → 开关仍开则重排
                    val enabled = runCatching {
                        ServiceLocator.settings.settings.first().dailyNotify
                    }.getOrDefault(false)
                    if (enabled) ReminderScheduler.schedule(context)
                }
            } catch (_: Throwable) {
                // 通知失败不崩溃；明天闹钟已在 fireReminder / schedule 内尽力续排
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fireReminder(context: Context) {
        val repo = ServiceLocator.repo

        // 今天已刷过 → 不打扰，直接排明天
        val todayAnswered = runCatching { repo.todayAnsweredCount() }.getOrDefault(0)
        if (todayAnswered > 0) {
            ReminderScheduler.schedule(context)
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ReminderScheduler.schedule(context)
            return
        }

        val streak = runCatching { repo.streakDays() }.getOrDefault(0)
        val (title, text) = if (streak > 0) {
            "连击 $streak 天进行中" to "今天还没刷题，别把连击断了"
        } else {
            "今天还没刷题" to "花几分钟，刷几道题吧"
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drone)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        runCatching { manager.notify(1001, notification) }

        ReminderScheduler.schedule(context)
    }
}

object ReminderScheduler {

    const val CHANNEL_ID = "streak_reminder"
    const val ACTION_FIRE = "com.drone.quiz.action.REMINDER_FIRE"
    private const val REQUEST_CODE = 2001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "每日提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 排下一次提醒（智能时刻）：最近 10 天每天首次刷题时刻的中位数 →
     * 无数据默认 19:30，夹在 10:00–21:30。若今天的时刻已过，则排明天。
     * 精确闹钟优先（Doze 也准时），无精确权限降级 setAndAllowWhileIdle。
     */
    suspend fun schedule(context: Context) {
        val hours = runCatching { ServiceLocator.repo.habitStartHours(10) }.getOrDefault(emptyList())
        val median = if (hours.isEmpty()) 19.5f
        else hours.sorted()[hours.size / 2]
        val smart = median.coerceIn(10f, 21.5f)
        val hour = smart.toInt()
        val minute = ((smart - hour) * 60f).roundToInt().coerceIn(0, 59)

        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, hour)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = firePendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, target.timeInMillis, pi
            )
        } else {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, target.timeInMillis, pi
            )
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context))
    }

    /**
     * 请求「后台运行」：电池优化白名单系统弹窗（用户开启每日提醒时请求一次）。
     * 白名单后通知由系统闹钟投递，ROM 一键清后台也拦不住；已加白则静默跳过。
     */
    @SuppressLint("BatteryLife")
    fun requestRunInBackground(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
