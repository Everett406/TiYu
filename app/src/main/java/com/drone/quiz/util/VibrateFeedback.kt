package com.drone.quiz.util

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * 答对震动反馈（v2.11.3）：刷题/错题特训即时判定「回答正确」时轻震一下。
 * 考试为整卷提交、出分才有对错，没有即时判定点，不接入（见 PracticeScreen.onCommit 唯一调用处）。
 * minSdk 31：直接走 VibratorManager（无旧版 Vibrator 兼容分支）；Manifest 已持有 VIBRATE 权限。
 */
object VibrateFeedback {

    /**
     * 答对触感：平台标准 CLICK 预设（跟随系统触感强度设置，各家 ROM 出厂调校，
     * 比自写 waveform 更不易在异构设备上过重/过轻）。异常静默吞掉——震动失败
     * 绝不影响答题记账与页面流程。
     */
    fun onCorrect(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vib = manager?.defaultVibrator
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        }
    }
}
