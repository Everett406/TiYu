package com.drone.quiz

import com.drone.quiz.data.PassEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通过率的**模型**（v2.19.51）。
 *
 * 全部围绕用户报的那个真实场景：
 *   「及格分调到 60 分给我报 100% 通过率，这个我比较相信。
 *     但我把及格分调到 95 分，通过率依然有 81%。我最高就考到过 86，
 *     还没考到过 90 分往上。」
 *
 * 旧公式是 `estimate = ability / pass` —— 「能力 ÷ 及格线」的**比值**，
 * 不是概率。后果是及格线越高，通过率反而越接近 1：
 *   能力 82 分、及格 60 → 137% → clamp 成 100%
 *   能力 82 分、及格 95 → 86%
 * 差 0.001 和差 0.10 报出来的数字几乎一样，因为它压根不在回答
 * 「能不能过」，只是把「离及格线还有多远」除了一下。
 *
 * 正确模型：通过率 = Φ((能力 − 及格线) / σ)，即「单次考试发挥波动下，
 * 分数落在及格线之上的概率」。
 */
class PassRateModelTest {

    private val DAY = 86_400_000L
    private val MIX = mapOf("single" to 0.8f, "judge" to 0.2f)

    /** 单选题全部按同一正确率铺开 */
    private fun examAt(level: Float, daysAgo: Long = 1, now: Long = 100 * DAY): PassEstimate.ExamSample =
        PassEstimate.ExamSample(
            startedAt = now - daysAgo * DAY,
            total = 100,
            // 显式写成 Pair：Kotlin 会把 `a to b to c` 关联成 Map
            byType = mapOf<String, Pair<Int, Int>>(
                "single" to Pair(Math.round(level * 80).toInt(), 80),
                "judge" to Pair(Math.round(level * 20).toInt(), 20)
            )
        )

    private fun rateAt(level: Float, passLine: Int, now: Long = 100 * DAY) =
        PassEstimate.compute(listOf(examAt(level, 1, now)), MIX, passLine, now = now).estimate

    /** 若干场同水平模考的时间轴（默认都以 now 为基准，避免时间衰减误伤） */
    private fun examsAt(levels: List<Float>, now: Long = 100 * DAY) =
        levels.mapIndexed { i, v -> examAt(v, i.toLong() + 1, now) }

    // ── 标准正态 CDF 本身 ────────────────────────────────────────

    @Test
    fun `正态CDF 基本点正确`() {
        assertEquals(0.5f, PassEstimate.normalCdf(0f), 1e-4f)
        assertEquals(0.8413f, PassEstimate.normalCdf(1f), 1e-3f)      // 一倍标准差
        assertEquals(0.9750f, PassEstimate.normalCdf(1.96f), 1e-3f)   // 95 分位
        assertEquals(0.9987f, PassEstimate.normalCdf(3f), 1e-3f)
        // 对称性
        assertEquals(
            PassEstimate.normalCdf(1.7f) + PassEstimate.normalCdf(-1.7f), 1f, 1e-4f
        )
    }

    @Test
    fun `正态CDF 极端值不越界`() {
        for (z in listOf(-50f, -10f, 0f, 10f, 50f)) {
            val v = PassEstimate.normalCdf(z)
            assertTrue("z=$z → $v", v in 0f..1f)
        }
    }

    // ── 用户报的那个 bug ──────────────────────────────────────────

    /**
     * 用户原话：「及格 95 分时通过率依然 81%。我最高考到 86，没到过 90。」
     *
     * 这条是整个改动的核心断言：分数离及格线远，通过率就必须低。
     */
    @Test
    fun `从没考到过的分数线通过率必须很低`() {
        // 真实水平 82 分，波动按默认 ±9 分
        val r95 = rateAt(0.82f, 95)
        assertTrue(
            "水平 82 分、及格 95 分，通过率应低于 20%，实得 ${(r95 * 100).toInt()}%",
            r95 < 0.20f
        )
        // 且不应超过 15%——用户从没考过 90 以上
        assertTrue("应低于 15%，实得 ${(r95 * 100).toInt()}%", r95 < 0.15f)
    }

    @Test
    fun `及格分从60调到95 通过率必须断崖式下跌`() {
        val low = rateAt(0.82f, 60)
        val high = rateAt(0.82f, 95)
        assertTrue("及格 60 应给出高通过率（实得 ${(low * 100).toInt()}%）", low > 0.90f)
        assertTrue(
            "及格 95（实得 ${(high * 100).toInt()}%）应远低于及格 60（${(low * 100).toInt()}%）",
            high < low * 0.2f
        )
    }

    /**
     * 反向锚点：旧公式在这里必然失败。
     * ability/pass 是比值，clamp 前 0.82/0.95 = 0.86 —— 报 86%。
     */
    @Test
    fun `旧公式会给出荒谬结果`() {
        val oldStyle = (0.82f / 0.95f).coerceIn(0f, 1f)
        assertTrue("旧公式在及格 95 时报 ${(oldStyle * 100).toInt()}%，正是用户看到的", oldStyle > 0.80f)
        val now = rateAt(0.82f, 95)
        assertTrue("新公式应远低于旧公式：${(now * 100).toInt()}%", now < oldStyle * 0.2f)
    }

    /** 离及格线越近，通过率应单调变化——旧公式在这段几乎没有区分度 */
    @Test
    fun `通过率随及格线单调下降且有区分度`() {
        val seq = listOf(60, 70, 80, 85, 90, 95).map { it to rateAt(0.82f, it) }
        for (i in 0 until seq.size - 1) {
            val (la, va) = seq[i]
            val (lb, vb) = seq[i + 1]
            assertTrue("及格 $la→$lb 通过率应下降（$va→$vb）", vb < va)
        }
        // 相邻档位必须**有可见差异**，否则等于没区分
        assertTrue(
            "80→85 应有明显差异（${seq[2].second} → ${seq[3].second}）",
            seq[2].second - seq[3].second > 0.05f
        )
    }

    // ── σ 的含义 ────────────────────────────────────────────────

    /**
     * 发挥越稳（σ 小），**高**分线的通过率越低——稳的人不会侥幸。
     *
     * 两组均值必须**相同**（都是 0.84），只有波动不同；否则测到的
     * 是水平差异而不是稳定性差异。
     *
     * 及格线取 90 而非 95：95 时 z ≈ −1.5，两组的通过率都被指数压到
     * 1e-13 以下，σ 的差异根本显现不出来。90 让 z 落在敏感区。
     */
    @Test
    fun `发挥越稳则高分线通过率越低`() {
        val now = 100 * DAY
        val stable = PassEstimate.compute(
            (0..5).map { examAt(0.84f, it.toLong() + 1, now) },
            MIX, 90, now = now
        )
        // 对称波动，均值同为 0.84
        val shaky = PassEstimate.compute(
            listOf(0.88f, 0.80f, 0.86f, 0.82f, 0.87f, 0.81f).mapIndexed { i, v ->
                examAt(v, i.toLong() + 1, now)
            },
            MIX, 90, now = now
        )
        assertTrue("两组水平应接近：stable=${stable.ability} shaky=${shaky.ability}",
            kotlin.math.abs(stable.ability - shaky.ability) < 0.06f)
        assertTrue("飘的那组 σ 应更大：${stable.sigma} vs ${shaky.sigma}",
            shaky.sigma > stable.sigma)
        assertTrue(
            "同样 84 分水平，稳定发挥冲 90 分应更难：stable=${stable.estimate} shaky=${shaky.estimate}",
            stable.estimate < shaky.estimate
        )
    }

    /** σ 估计须向默认值收缩：两场考试不足以代表一个人的波动 */
    @Test
    fun `样本少时sigma向默认值收缩`() {
        val two = PassEstimate.estimateSigma(
            listOf(examAt(0.95f, 1), examAt(0.75f, 2))
        )
        assertTrue("两场（差 20 分）不该得到很大的 σ：$two", two < 0.15f)
        assertTrue("两场不该小于默认附近：$two", two >= PassEstimate.SIGMA_MIN)
    }

    /** σ 必须被夹在硬边界内 */
    @Test
    fun `sigma夹在硬边界内`() {
        val identical = PassEstimate.estimateSigma(
            (0..9).map { examAt(0.80f, it.toLong() + 1) }
        )
        assertTrue("十场同分不该得到 σ=0：$identical", identical >= PassEstimate.SIGMA_MIN)
        val wild = PassEstimate.estimateSigma(
            (0..9).map { examAt(if (it % 2 == 0) 0.05f else 0.95f, it.toLong() + 1) }
        )
        assertTrue("极端波动不该超过上限：$wild", wild <= PassEstimate.SIGMA_MAX)
    }

    /** 不足两场时直接用默认值 */
    @Test
    fun `不足两场用默认sigma`() {
        assertEquals(PassEstimate.SIGMA_DEFAULT, PassEstimate.estimateSigma(emptyList()), 1e-6f)
        assertEquals(
            PassEstimate.SIGMA_DEFAULT,
            PassEstimate.estimateSigma(listOf(examAt(0.8f, 1))), 1e-6f
        )
    }

    // ── 边界 ────────────────────────────────────────────────────

    @Test
    fun `及格分边界不崩`() {
        for (line in listOf(0, 1, 50, 100, 200)) {
            val r = rateAt(0.82f, line)
            assertTrue("及格 $line → $r", r in 0f..1f)
        }
    }

    @Test
    fun `能力远高于及格线时应接近满格`() {
        val r = rateAt(0.99f, 60)
        assertTrue("水平 99 分、及格 60 应接近 100%，实得 ${(r * 100).toInt()}%", r > 0.98f)
    }

    /** 副标题应带上水平与波动，让用户能自己判断这个数字可不可信 */
    @Test
    fun `副标题含水平与波动`() {
        // 注意 now 必须与 examAt 一致，否则时间衰减会把权重归零、误判成冷启动
        val now = 100 * DAY
        val r = PassEstimate.compute(listOf(examAt(0.82f, 1, now)), MIX, 95, now = now)
        val b = r.basis(95)
        assertTrue("副标题应含波动：$b", b.contains("波动"))
        assertTrue("副标题应含模考场次：$b", b.contains("模考"))
        // 单场 100 题会被先验收缩到 ~78（原水平 82），所以断言区间而非具体值
        val level = Regex("估计水平 (\\d+)").find(b)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("副标题应带出估计水平分值：$b", level != null)
        assertTrue("单场样本收缩后应在 70~82 之间，实际 $level", level in 70..82)
    }
}
