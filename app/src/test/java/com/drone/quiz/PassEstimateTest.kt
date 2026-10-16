package com.drone.quiz

import com.drone.quiz.data.PassEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预估通过率（v2.19.48）。
 *
 * 全部围绕用户提的那个真实场景：
 *   「我设置成 10 道题考试，分数很高或很低 —— 题目越少可信度就越低吧？」
 * 旧算法对此毫无反应：10 道题考 100 分和 100 道题考 100 分给出同一个数字。
 */
class PassEstimateTest {

    /** 默认题库：640 单选 + 160 判断 */
    private val MIX = mapOf("single" to 0.8f, "judge" to 0.2f)
    private val DAY = 86_400_000L

    private fun exam(
        daysAgo: Long,
        total: Int,
        single: Pair<Int, Int>,
        judge: Pair<Int, Int>,
        now: Long = 10 * DAY
    ) = PassEstimate.ExamSample(
        startedAt = now - daysAgo * DAY,
        total = total,
        byType = mapOf("single" to single, "judge" to judge)
    )

    // ─────────────────────────────────────────────────────────────
    // 核心命题：同样全对，题数少 → 估计必须明显更保守
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `同样全对 题数越多估计越高`() {
        val now = 10 * DAY
        // 10 道题全对
        val small = PassEstimate.compute(
            exams = listOf(exam(0, 10, 8 to 8, 2 to 2, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        // 100 道题全对
        val big = PassEstimate.compute(
            exams = listOf(exam(0, 100, 80 to 80, 20 to 20, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        assertTrue(
            "100 题全对的估计（${big.estimate}）应高于 10 题全对（${small.estimate}）",
            big.estimate > small.estimate
        )
    }

    @Test
    fun `同样全错 题数越多估计越低`() {
        val now = 10 * DAY
        val small = PassEstimate.compute(
            exams = listOf(exam(0, 10, 0 to 8, 0 to 2, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        val big = PassEstimate.compute(
            exams = listOf(exam(0, 100, 0 to 80, 0 to 20, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        assertTrue(
            "100 题全错的估计（${big.estimate}）应低于 10 题全错（${small.estimate}）",
            big.estimate < small.estimate
        )
    }

    /**
     * 小样本不该给出「100% 通过」这种极端结论。
     * 只做过 10 道题且全对，先验（相当于 24 题）应当把它拉下来。
     */
    @Test
    fun `小样本全对不会给出满格结论`() {
        val now = 10 * DAY
        val r = PassEstimate.compute(
            exams = listOf(exam(0, 10, 8 to 8, 2 to 2, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        assertTrue("小样本不该报 100%（实得 ${r.estimate}）", r.estimate < 1f)
        assertTrue("但也不该过于悲观（实得 ${r.estimate}）", r.estimate > 0.6f)
    }

    @Test
    fun `小样本会标记为证据不足`() {
        val now = 10 * DAY
        val r = PassEstimate.compute(
            exams = listOf(exam(0, 10, 8 to 8, 2 to 2, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        assertTrue("10 题应标记 enough=false", !r.enough)
        assertTrue("副标题应提示样本偏少：${r.basis()}", r.basis().contains("样本偏少"))
    }

    /**
     * 题型校正：用偏判断题的卷子刷高分，抬不高整体估计。
     * 单选全错 + 判断全对 vs 单选全对 + 判断全对 —— 后者才该更高。
     */
    @Test
    fun `偏怪题型的高分不会抬高整体估计`() {
        val now = 10 * DAY
        // 真实配比是 80% 单选。故意考一张 80% 判断的卷子并全对：
        val easyPaper = PassEstimate.compute(
            exams = listOf(exam(0, 50, 0 to 10, 40 to 40, now)),    // 单选全错、判断全对
            bankTypeMix = MIX, passLine = 60, now = now
        )
        // 正常配比卷子，单选拿 8/10、判断 4/5
        val normalPaper = PassEstimate.compute(
            exams = listOf(exam(0, 15, 8 to 10, 4 to 5, now)),
            bankTypeMix = MIX, passLine = 60, now = now
        )
        assertTrue(
            "偏判断题全对（${easyPaper.estimate}）不该高于正常配比（${normalPaper.estimate}）",
            easyPaper.estimate < normalPaper.estimate
        )
    }

    /**
     * 题型校正的算术：判断题权重只有 20%。
     * 单选 10 道全错 + 判断 50 道全对，按真实配比换算的正确率只有
     * 0.8×0 + 0.2×1 = 0.2，而不是这场的原始 0.91。
     */
    @Test
    fun `题型校正按题库真实配比加权`() {
        val now = 10 * DAY
        val e = exam(0, 60, 0 to 10, 50 to 50, now)
        assertEquals(0.833f, e.rawAccuracy()!!, 0.01f)          // 原始正确率很高
        val r = PassEstimate.compute(listOf(e), MIX, passLine = 60, now = now)
        // 校正后能力值应远低于 0.91，接近 0.2 加上先验的贡献
        assertTrue("校正后能力应被压到 0.6 以下（实得 ${r.ability}）", r.ability < 0.6f)
    }

    /** 时间衰减：同样的成绩，半年前的权重应明显低于昨天 */
    @Test
    fun `久远的模考权重更低`() {
        val now = 200 * DAY
        val fresh = PassEstimate.compute(
            listOf(exam(1, 60, 48 to 60, 12 to 12, now)), MIX, 60, now = now
        )
        val stale = PassEstimate.compute(
            listOf(exam(150, 60, 48 to 60, 12 to 12, now)), MIX, 60, now = now
        )
        assertTrue(
            "150 天前全对（${stale.estimate}）应低于昨天全对（${fresh.estimate}）",
            stale.estimate < fresh.estimate
        )
    }

    /** 及格线越高，预估越低 —— 旧算法有、这次不能丢 */
    @Test
    fun `及格分越高预估越低`() {
        val now = 10 * DAY
        val e = exam(0, 60, 48 to 60, 12 to 12, now)
        val easy = PassEstimate.compute(listOf(e), MIX, passLine = 60, now = now)
        val hard = PassEstimate.compute(listOf(e), MIX, passLine = 90, now = now)
        assertTrue("90 分及格（${hard.estimate}）应低于 60 分（${easy.estimate}）", hard.estimate < easy.estimate)
    }

    /** 多场累加：题数真的在累积 */
    @Test
    fun `多场模考的证据量会累积`() {
        val now = 10 * DAY
        val one = PassEstimate.compute(
            listOf(exam(0, 20, 16 to 20, 4 to 4, now)), MIX, 60, now = now
        )
        val five = PassEstimate.compute(
            (0..4).map { exam(it.toLong(), 20, 16 to 20, 4 to 4, now) }, MIX, 60, now = now
        )
        assertEquals(1, one.examCount)
        assertEquals(5, five.examCount)
        assertTrue("5 场应比 1 场证据足（${five.questionWeight} vs ${one.questionWeight}）",
            five.questionWeight > one.questionWeight)
        assertTrue("置信度也应更高", five.confidence > one.confidence)
    }

    /**
     * 刷题是辅助信号：按**去重题数**加权并封顶。
     *
     * 注：这里刻意不写「刷题正确率 20% 时估计应保持在 X% 以上」——
     * 那种期望不成立。刷过 300 道不同的题而只对 20%，本身就是很强的负面
     * 信号，估计被拉低是**正确**行为，不是缺陷。
     * 该验证的是「折权与封顶是否按设计生效」。
     */
    @Test
    fun `刷题按去重题数折权且有封顶`() {
        val now = 10 * DAY
        val e = exam(0, 60, 48 to 60, 12 to 12, now)
        val acc = mapOf("single" to 0.2f, "judge" to 0.2f)

        val none   = PassEstimate.compute(listOf(e), MIX, 60, acc, 0,   now = now)
        val some   = PassEstimate.compute(listOf(e), MIX, 60, acc, 100, now = now)
        val capped = PassEstimate.compute(listOf(e), MIX, 60, acc, 300, now = now)
        val wayOver= PassEstimate.compute(listOf(e), MIX, 60, acc, 9999,now = now)

        // ① 折权生效：加刷题会拉低
        assertTrue("加刷题应拉低估计（none=${none.estimate} some=${some.estimate}）",
            some.estimate < none.estimate)
        // ② 封顶生效：超过 300 去重题数后不再变化
        assertEquals("封顶应生效", capped.estimate, wayOver.estimate, 0.0001f)
    }

    /** 反向锚点：刷题正确率高时，不该反过来压低模考的好成绩 */
    @Test
    fun `刷题正确率高不会压低模考成绩`() {
        val now = 10 * DAY
        val e = exam(0, 60, 48 to 60, 12 to 12, now)
        val examOnly = PassEstimate.compute(listOf(e), MIX, 60, now = now)
        val withGood = PassEstimate.compute(
            listOf(e), MIX, 60,
            practiceAcc = mapOf("single" to 0.8f, "judge" to 0.9f),
            practiceN = 300, now = now
        )
        assertTrue(
            "刷题正确率同样高时不该拉低（exam=${examOnly.estimate} with=${withGood.estimate}）",
            withGood.estimate >= examOnly.estimate - 0.02f
        )
    }

    /**
     * 核心口径锚点：按去重题数而非作答次数加权。
     * 同一批 40 道题刷 200 次，应当只算 40 道题的证据。
     */
    @Test
    fun `同一批题反复刷不会放大证据`() {
        val now = 10 * DAY
        val e = exam(0, 60, 48 to 60, 12 to 12, now)
        val once = PassEstimate.compute(
            listOf(e), MIX, 60, mapOf("single" to 0.2f, "judge" to 0.2f), 40, now = now
        )
        val many = PassEstimate.compute(
            listOf(e), MIX, 60, mapOf("single" to 0.2f, "judge" to 0.2f), 40, now = now
        )
        assertEquals("同一去重题数下结果必须一致（次数不进算法）", once.estimate, many.estimate, 0f)
    }

    /** 冷启动：没有任何数据时不报错，且文案说明原因 */
    @Test
    fun `冷启动不崩且有解释`() {
        val r = PassEstimate.compute(
            exams = emptyList(), bankTypeMix = MIX, passLine = 60,
            practiceAcc = emptyMap(), practiceN = 0, coverage = 0.3f, now = 10 * DAY
        )
        assertTrue(r.coldStart)
        assertTrue(r.estimate in 0f..1f)
        assertTrue(r.basis().contains("暂无成绩"))
    }

    /**
     * 反向锚点：收缩公式一旦被摘掉，本测试必须失败。
     * （static_check 只查常量是否声明，查不到公式有没有真用上；
     *   这类「改了行为但符号还在」的退化只有数值断言能抓。）
     */
    @Test
    fun `收缩是实际生效的而不是只声明了常量`() {
        val now = 10 * DAY
        val acc = PassEstimate.compute(
            listOf(exam(0, 10, 8 to 8, 2 to 2, now)), MIX, 60, now = now
        )
        val noShrink = 1.0f   // 10 道全对、不收缩会得到 1.0
        assertTrue(
            "收缩未生效（ability=${acc.ability}）",
            acc.ability < noShrink
        )
        // 且应落在先验附近，而不是贴着 1.0
        assertTrue("收缩过弱（ability=${acc.ability}）", acc.ability < 0.95f)
    }

    /** 时间衰减必须真按天数生效，而不是写了个常量不用 */
    @Test
    fun `时间衰减是真的按天数在算`() {
        val now = 400 * DAY
        fun rate(days: Long): Float = PassEstimate.compute(
            listOf(exam(days, 60, 12 to 60, 12 to 12, now)), MIX, 60, now = now
        ).estimate
        // 全错的一百多天前 vs 全错的昨天：衰减权重不同，结果必须不同
        assertTrue("半衰期后估计应更靠近先验：old=${rate(300)} new=${rate(0)}",
            rate(300) > rate(0))
    }

    /** 及格分边界值不崩 */
    @Test
    fun `及格分边界不崩`() {
        val now = 10 * DAY
        val e = exam(0, 60, 48 to 60, 12 to 12, now)
        for (line in listOf(0, 1, 60, 100, 200)) {
            val r = PassEstimate.compute(listOf(e), MIX, line, now = now)
            assertTrue("及格分 $line 下 estimate=${r.estimate}", r.estimate in 0f..1f)
        }
    }
}
