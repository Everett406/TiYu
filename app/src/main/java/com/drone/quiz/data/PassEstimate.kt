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

    // ── 考试波动 σ（v2.19.51）──────────────────────────────────
    // 通过率是「单次考试会不会过这条线」，所以必须考虑发挥的波动：
    // 同一个人，同样水平，状态好时多考几分、状态差时少考几分。
    // σ 越小 = 发挥越稳，通过率曲线越陡（差一分就必然过或必然不过）。
    //
    // 默认值取 0.09：大致是「一次模考比另一��低 9 分」这个量级。
    // 样本不足时用它；样本够了就换成用户**自己**的成绩波动。
    const val SIGMA_DEFAULT = 0.09f

    /** σ 的先验强度（以"次"计）：样本少时向默认值收缩 */
    const val SIGMA_PRIOR_N = 8f

    /** σ 的硬边界：再稳也不该零波动，再飘也不该离谱 */
    const val SIGMA_MIN = 0.04f
    const val SIGMA_MAX = 0.22f

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
        val estimate: Float,        // 0..1 预估通过率（正态分布尾部概率）
        val ability: Float,         // 0..1 估计的期望正确率（收缩后）
        val sigma: Float,           // 0..1 单次考试波动
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
        fun basis(passLine: Int = 60): String = when {
            coldStart -> "暂无成绩，先练几道就能估了"
            examCount == 0 -> "还没有模考记录，先去刷题"
            questionWeight < 20f ->
                "估计水平 ${pct(ability)} · 波动 ±${pct(sigma)} · $examCount 次模考，样本偏少"
            else ->
                "估计水平 ${pct(ability)} · 波动 ±${pct(sigma)} · 基于 $examCount 次模考"
        }

        /** 0.82 → "82" */
        private fun pct(v: Float) = (v * 100).toInt().toString()
    }

    /**
     * @param exams        模考样本（调用方已按时间倒序，最多 [MAX_EXAMS] 场）
     * @param bankTypeMix  题库中各题型的真实占比，合计 1（默认库 = 单选 .8 / 判断 .2）
     * @param passLine     及格分（百分制）
     * @param practiceAcc  刷题的各题型正确率
     * @param practiceN    刷题覆盖的**去重题数**（不是作答次数）
     * @param coverage     题库覆盖率（仅冷启动时用作起步提示）
     */
    /**
     * 从用户自己的模考成绩估计单次波动 σ。
     *
     * 样本少时向 [SIGMA_DEFAULT] 收缩——一个人只考过两场，两场分数之差
     * 一半来自实力变化、一半来自运气，直接当 σ 会严重低估波动。
     */
    fun estimateSigma(exams: List<ExamSample>): Float {
        val scores = exams.take(MAX_EXAMS).mapNotNull { it.rawAccuracy() }
        if (scores.size < 2) return SIGMA_DEFAULT
        val mean = scores.average()
        val varSum = scores.sumOf { (it - mean) * (it - mean).toDouble() }.toFloat()
        // 样本标准差（Bessel 校正）
        val sample = kotlin.math.sqrt(varSum / (scores.size - 1)).toFloat()
        val n = scores.size.toFloat()
        val blended = kotlin.math.sqrt(
            (n * sample * sample + SIGMA_PRIOR_N * SIGMA_DEFAULT * SIGMA_DEFAULT) /
                (n + SIGMA_PRIOR_N)
        ).toFloat()
        return blended.coerceIn(SIGMA_MIN, SIGMA_MAX)
    }

    /**
     * 标准正态分布的累积分布函数 Φ(z)。
     *
     * 用 Abramowitz & Stegun 7.1.26 的误差函数近似，
     * 最大绝对误差约 7.5e-8 —— 远小于百分位显示精度。
     * 不引第三方库：为一个 erf 拉一个依赖不划算。
     */
    fun normalCdf(z: Float): Float {
        val az = kotlin.math.abs(z)
        val t = 1f / (1f + 0.2316419f * az)
        val poly = t * (0.319381530f + t * (-0.356563782f +
            t * (1.781477937f + t * (-1.821255978f + t * 1.330274429f))))
        val d = 0.3989423f * kotlin.math.exp(-0.5f * az * az)
        val tail = d * poly
        return if (z >= 0f) 1f - tail else tail
    }

    fun compute(
        exams: List<ExamSample>,
        bankTypeMix: Map<String, Float>,
        passLine: Int,
        practiceAcc: Map<String, Float> = emptyMap(),
        practiceN: Int = 0,
        coverage: Float = 0f,
        now: Long = System.currentTimeMillis()
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
                estimate = normalCdf((seed - pass) / SIGMA_DEFAULT).coerceIn(0f, 1f),
                ability = seed,
                sigma = SIGMA_DEFAULT,
                examCount = 0, questionWeight = 0f,
                enough = false, coldStart = true
            )
        }

        // ── 第三层：向先验收缩，样本越少拉得越近 ──
        val prior = PRIOR_PASS * pass          // 先验按及格线折算成「期望正确率」
        val ability = ((correctW + prior * PRIOR_STRENGTH) /
                (totalW + PRIOR_STRENGTH)).coerceIn(0f, 1f)

        // ── 通过率 = P(单次考试得分 ≥ 及格线) ──
        //
        // v2.19.50 之前用的是 `ability / pass`，那是「能力 ÷ 及格线」的**比值**，
        // 不是概率。后果是及格线越高、通过率反而越接近 1：
        //   能力 82 分、及格 60 → 137% → clamp 成 100%（还算合理）
        //   能力 82 分、及格 95 → 86%（荒谬——差 13 分还报 86%）
        // 用户原话：「考到 95 分时通过率依然有 81%」。
        //
        // 差 0.001 和差 0.10 报出来的数字几乎一样，因为它压根不是在回答
        // 「能不能过」，只是把「离及格线还有多远」除了一下。
        //
        // 正确做法是把它当成概率问题：单次考试发挥有波动 σ，
        // 于是通过率 = Φ((能力 − 及格线) / σ)。
        val sigma = estimateSigma(exams)
        val z = (ability - pass) / sigma
        val estimate = normalCdf(z).coerceIn(0f, 1f)
        return Result(
            estimate = estimate,
            ability = ability,
            sigma = sigma,
            examCount = used,
            questionWeight = totalW,
            enough = used > 0 && totalW >= 20f,
            coldStart = false
        )
    }
}
