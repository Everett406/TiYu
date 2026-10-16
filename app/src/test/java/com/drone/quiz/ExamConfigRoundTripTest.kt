package com.drone.quiz

import com.drone.quiz.data.settings.ExamQuickConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模考设置记忆的**往返一致性**（v2.19.47）。
 *
 * 背景：用户实机发现把判断题占比从 30% 调到 20%，杀 App 重进又变回 30%。
 * 根因是存了 `counts`（题型→题数）——那是「占比 × 总题数」算完之后、
 * 再被题库可用题量裁剪过的**结果**，从它反推占比必然失真：
 *
 *   默认库 = 640 单选 + 160 判断，总题数 50：
 *     30% → judge = 15，single = 35
 *     20% → judge = 10，single = 40
 *   两者在 counts 里明明不同；但如果总题数再小一点、或题库某型不足，
 *   两者会被裁到同一个数，反推就彻底失真了。
 *
 * 所以记忆必须记**用户意图**（judgeRatio），不是派生结果。
 * 本测试锁死「存什么、读回来就还是什么」这条往返链。
 */
class ExamConfigRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `判断题占比存进去读出来不变`() {
        val saved = ExamQuickConfig(
            counts = mapOf("single" to 40, "judge" to 10),
            durationMin = 60,
            typeOrder = listOf("single", "judge"),
            judgeRatio = 0.20f
        )
        // 经 JSON 往返（DataStore 实际走的路径）
        val restored = json.decodeFromString<ExamQuickConfig>(json.encodeToString(saved))

        assertEquals(
            0.20f, restored.judgeRatio, 0.0001f
        )
        assertTrue(
            "旧实现只存 counts，从它反推会得到 " +
                "${restored.counts["judge"]} / ${restored.counts.values.sum()}",
            restored.judgeRatio < 0.25f
        )
    }

    @Test
    fun `手动比例与自动配比开关同样往返`() {
        val saved = ExamQuickConfig(
            counts = mapOf("single" to 25, "judge" to 25),
            durationMin = 90,
            judgeRatio = 0.2f,
            manualRatios = mapOf("single" to 50, "judge" to 50),
            autoMix = false
        )
        val r = json.decodeFromString<ExamQuickConfig>(json.encodeToString(saved))
        assertEquals(mapOf("single" to 50, "judge" to 50), r.manualRatios)
        assertEquals(false, r.autoMix)
        assertEquals(90, r.durationMin)
    }

    /**
     * 反向锚点：老的存档没有 judgeRatio 字段，解析时必须给出可用默认值而不是崩。
     */
    @Test
    fun `旧存档缺少新字段时回落默认值`() {
        val legacy = """{"counts":{"single":35,"judge":15},"durationMin":60,"typeOrder":["single","judge"]}"""
        val r = json.decodeFromString<ExamQuickConfig>(legacy)
        assertEquals(0.3f, r.judgeRatio, 0.0001f)      // 默认 30%
        assertTrue(r.manualRatios.isEmpty())          // 空 → 调用方走 counts 折算兼容分支
        assertTrue(r.autoMix)                        // 默认自动配比
        assertEquals(50, r.counts.values.sum())
    }

    /**
     * 从 counts 反推占比会撞车——这正是不能靠 counts 记忆的根因。
     *
     * 存 counts = min(占比 × 总题数, 该题型可用题数)；
     * 反推 = 该题数 / 总题数。当题库该题型不足时，不同占比被裁到同一题数，
     * 信息不可逆丢失：
     *
     *   总题数 20、判断题仅剩 3 道：30% 与 20% 都只要 3 题 → 都存成 3。
     *   重进页面反推得到 15%，既不是 30% 也不是 20%——用户拖的滑杆"自己动了"。
     *
     * 所以判断题占比必须原样存 judgeRatio，而不是事后从 counts 猜。
     */
    @Test
    fun `counts 反推占比在题库不足时会撞车`() {
        fun stored(judgePct: Int, total: Int, judgeAvail: Int) =
            minOf(total * judgePct / 100, judgeAvail)
        fun derived(judgeCount: Int, total: Int) = judgeCount * 100 / total

        // 总题数 20、判断题只剩 3 道：30% 与 20% 都被裁到 3 题
        assertEquals(3, stored(judgePct = 30, total = 20, judgeAvail = 3))
        assertEquals(3, stored(judgePct = 20, total = 20, judgeAvail = 3))
        // 反推只能得到 15% —— 既不是用户要的 30%，也不是 20%
        assertEquals(15, derived(3, 20))
    }

}
