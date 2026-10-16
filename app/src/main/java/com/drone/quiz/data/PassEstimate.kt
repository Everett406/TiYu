package com.drone.quiz.data

import kotlin.math.pow

/**
 * 预估通过率（v2.19.48 重写）。
 *
 * ── 旧算法错在哪 ──
 * ```
 * passRatio = 累计刷题正确率 / (及格分/100)
 * estimate  = passRatio * 0.7 + 刷题覆盖率 * 0.3
 * ```
 * 五个问题：
 *  ① **压根没引用模考成绩**。模考有倒计时、有完整流程，是最接近真实考试的信号，
 *     却被完全排除在外，只用了刷题的累计正确率。
 *  ② **无题数权重**。累计所有作答、一题一票，于是「考 10 道题」和
 *     「考 100 道题」对数字的影响一样大。小样本的方差本来就大得多。
 *  ③ **无题型区分**。把判断题占比调到 80% 去刷分，正确率会跟着涨，
 *     但真实考试是 640 单选 : 160 判断。偏难怪的卷子不能代表真实水平。
 *  ④ **无时间衰减**。三个月前的状态和昨天一样重。
 *  ⑤ **「刷了多少题」占了 30% 权重**。刷了 800 道但一直答错的人，
 *     覆盖率 100% 会硬把预估拉上去——覆盖率衡量的是「练过多少」，
 *     不是「练得怎么样」，与能否考过没有因果关系。
 *
 * ── 新算法 ──
 * 三层：
 *  · **题型校正**：按题库**真实配比**把各题型正确率重新加权，而不是按某次
 *    模考当次的配比。拿偏难怪的卷子练手，抬不高整体估计。
 *  · **题数加权**：每次模考的权重 ∝ 题数。这不是加权平均的技巧，而是
 *    「答对题数 / 总题数」本身——10 道题里对 5 道就是 5 分证据，
 *    100 道题里对 50 道就是 50 分证据，天然按题数累积。
 *  · **小样本收缩**：向先验收缩，强度相当于 [PRIOR_STRENGTH] 道题。
 *    只考过 10 道题时，输出会被拉回先验附近而不是跟着 90 分跑。
 * 刷题记录作为辅助信号，按 [PRACTICE_WEIGHT] 折权（无时间压力）。
 */
object PassEstimate {

    /** 先验强度，约相当于「再考这么多道题」的证据量 */
    const val PRIOR_STRENGTH = 24f

    /** 冷启动先验通过率（没有任何数据时的默认看法） */
    const val PRIOR_PASS = 0.65f

    /**
     * 刷题相对模考的折权：无倒计时、无完整流程，证据力弱一档。
     * 注意 [practiceN] 传的是**去重题数**而非作答次数——同一道题反复做
     * 不会带来新的信息量，按次数加权会让「刷 300 次同一批题」看起来
     * 练了 300 道，证据量直接压过一场 60 题模考。
     */
    const val PRACTICE_WEIGHT = 0.5f

    /** 时间衰减半衰期（天）：半衰期前的模考，权重减半 */
    const val HALF_LIFE_DAYS = 30f

    /** 最多回看多少场模考 */
    const val MAX_EXAMS = 8

    /** 刷题证据的封顶**去重题数**，防止大量刷题把模考信号淹没 */
    const val PRACTICE_CAP = 300

    /** 单场模考：各题型的 (对, 总) */
    data class ExamSample(
        val startedAt: Long,
        val total: Int,
        val byType: Map<String, Pair<Int, Int>>   // type -> (correct, answered)
    ) {
        /** 该场的整体正确率；answered=0 时返回 null */
        fun rawAccuracy(): Float? {
            val c = byType.values.sumOf { it.first }
            val t = byType.values.sumOf { it.second }
            return if (t > 0) c.toFloat() / t else null
        }
    }

    data class Result(
        val estimate: Float,        // 0..1 预估通过率
        val ability: Float,         // 0..1 估计的期望正确率（收缩后）
        val examCount: Int,         // 用到几场模考
        val questionWeight: Float,  // 模考证据总量（加权题数）
        val enough: Boolean,        // 证据是否充分
        val coldStart: Boolean      // 是否完全没有数据
    ) {
        /** 置信度：模考场次与题数共同决定，0..1 */
        val confidence: Float
            get() = if (coldStart) 0f
            else (1f - 1f / (1f + questionWeight / 12f)) *
                    (1f - 1f / (1f + examCount / 2.5f))

        /** 副标题：把依据摊开给用户看，而不是给一个凭空而来的百分数 */
        fun basis(): String = when {
            coldStart -> "暂无成绩，先练几道就能估了"
            examCount == 0 -> "还没有模考记录，先去刷题"
            questionWeight < 20f ->
                "基于 $examCount 次模考 · 约 ${questionWeight.toInt()} 题，样本偏少仅供参考"
            else ->
                "基于 $examCount 次模考 · 约 ${questionWeight.toInt()} 题"
        }
    }

    /**
     * @param exams        模考样本（调用方已按时间倒序，最多 [MAX_EXAMS] 场）
     * @param bankTypeMix  题库中各题型的真实占比，合计 1（默认库 = 单选 .8 / 判断 .2）
     * @param passLine     及格分（百分制）
     * @param practiceAcc  刷题的各题型正确率
     * @param practiceN    刷题覆盖的**去重题数**（不是作答次数）
     * @param coverage     题库覆盖率（仅冷启动时用作起步提示）
     */
    fun compute(
        exams: List<ExamSample>,
        bankTypeMix: Map<String, Float>,
        passLine: Int,
        practiceAcc: Map<String, Float> = emptyMap(),
        practiceN: Int = 0,
        coverage: Float = 0f,
        now: Long
    ): Result {
        val pass = (passLine.coerceIn(1, 100)) / 100f

        // ── 第一层：模考，逐场按题型校正 + 按题数累积 + 时间衰减 ──
        var correctW = 0f          // 加权「答对题数」
        var totalW = 0f            // 加权「总题数」
        var used = 0

        for (e in exams.take(MAX_EXAMS)) {
            val answered = e.byType.values.sumOf { it.second }
            if (answered <= 0) continue
            val raw = e.rawAccuracy() ?: continue

            // 题型校正：按题库真实配比加权；该场没考的题型用整体正确率兜底
            var acc = 0f
            for ((type, mix) in bankTypeMix) {
                val (c, t) = e.byType[type] ?: continue
                val typeAcc = if (t > 0) c.toFloat() / t else raw
                acc += typeAcc * mix
            }
            // 题库配比与该场实际题型没有交集时（极少见）退回整体
            if (acc <= 0f) acc = raw

            val ageDays = ((now - e.startedAt).coerceAtLeast(0L)) / 86_400_000f
            val w = 0.5f.pow(ageDays / HALF_LIFE_DAYS)   // 时间衰减

            // 权重 ∝ 实际作答题数 —— 题少的那场自然就轻
            correctW += acc * answered * w
            totalW += answered * w
            used++
        }

        // ── 第二层：刷题，辅助信号，同样做题型校正 ──
        var practiceW = 0f
        if (practiceN > 0 && practiceAcc.isNotEmpty()) {
            var pAcc = 0f
            for ((type, mix) in bankTypeMix) {
                pAcc += (practiceAcc[type] ?: 0f) * mix
            }
            if (pAcc > 0f) {
                practiceW = practiceN.coerceAtMost(PRACTICE_CAP) * PRACTICE_WEIGHT
                correctW += pAcc * practiceW
                totalW += practiceW
            }
        }

        // ── 冷启动：完全没数据，用覆盖率给个起步提示 ──
        if (totalW <= 0f) {
            val seed = (PRIOR_PASS * 0.45f + coverage.coerceIn(0f, 1f) * 0.55f)
            return Result(
                estimate = (seed / pass).coerceIn(0f, 1f),
                ability = seed,
                examCount = 0, questionWeight = 0f,
                enough = false, coldStart = true
            )
        }

        // ── 第三层：向先验收缩，样本越少拉得越近 ──
        val prior = PRIOR_PASS * pass          // 先验按及格线折算成「期望正确率」
        val ability = ((correctW + prior * PRIOR_STRENGTH) /
                (totalW + PRIOR_STRENGTH)).coerceIn(0f, 1f)

        val estimate = (ability / pass).coerceIn(0f, 1f)
        return Result(
            estimate = estimate,
            ability = ability,
            examCount = used,
            questionWeight = totalW,
            enough = used > 0 && totalW >= 20f,
            coldStart = false
        )
    }
}
