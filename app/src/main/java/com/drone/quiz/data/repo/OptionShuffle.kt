package com.drone.quiz.data.repo

import kotlin.random.Random

/**
 * 随机选项（v2.11.0）：**视图层乱序，数据层恒为原始空间**。
 *
 * 核心不变量（答案绝不乱的结构性保证）：
 * - [permOf] 生成的 permutation 满足 `perm[显示位置] = 原始下标`，是双射；
 * - UI 只看「显示题目」（[display]：options 重排 + answer 重映射到显示空间），
 *   用户在显示空间作答；
 * - 落库/判分/错题本/统计前，一律经 [uaToOriginal] 把 UserAnswer 换算回原始空间——
 *   [judgeAnswer]（Repo 层）与数据库存储永远只见原始下标/位掩码，零改动、零污染；
 * - 判定等价性：`judgeAnswer(q, uaToOriginal(uaD))` ≡ `judgeAnswer(display(q,perm), uaD)`
 *   （同一选项内容在两个空间的相等比较），单测 OptionShuffleTest 全量覆盖。
 *
 * salt 语义：0 = 不打乱（旧快照/旧模考记录/开关关闭的天然兼容值）；
 * 非 0 会话盐由 [newSalt] 生成，同一会话内「salt+qid」确定稳定顺序——
 * 翻页回看顺序不变，恢复会话（快照存 salt）顺序也不变；
 * 重新开始/下次进入生成新 salt → 每次做的时候打乱结果不同（用户口径）。
 *
 * 适用范围：single/multi 且选项 ≥ 2；判断题（√/× 位置语义固定、打乱无意义）、
 * 填空/简答（无选项）不参与。错题本/错题特训入口不生成 salt（用户口径：保持原始顺序）。
 */
object OptionShuffle {

    /** 是否适用打乱：single/multi 且选项数 ≥ 2。 */
    fun applicable(q: Question): Boolean =
        (q.type == QuestionTypes.SINGLE || q.type == QuestionTypes.MULTI) && q.options.size >= 2

    /**
     * 稳定 permutation：`perm[显示位置] = 原始下标`。
     * salt == 0 或题型不适用 → null（不打乱）。
     * 同一 (salt, qid, 选项数) 恒返回同一排列——会话内翻页/恢复一致的关键。
     */
    fun permOf(q: Question, salt: Long): List<Int>? {
        if (salt == 0L || !applicable(q)) return null
        return q.options.indices.shuffled(Random(salt * 1_000_003L + q.id))
    }

    /** 新会话盐（恒非 0：0 是「不打乱」哨兵值，不可与之冲突）。 */
    fun newSalt(): Long = Random.nextLong(1, Long.MAX_VALUE)

    /**
     * 显示题目：options 按展示顺序重排，answer 重映射到显示空间
     * （single/judge：正确项的显示位置；multi：正确项的显示空间位掩码）。
     * Question 的其余字段（题干/解析/答案文本）原样保留。
     * 仅在 perm != null 时有意义；perm == null 请直接透传原始题目。
     */
    fun display(q: Question, perm: List<Int>): Question {
        val newAnswer = when (q.type) {
            QuestionTypes.MULTI -> perm.indices.fold(0) { acc, d ->
                if (q.answer and (1 shl perm[d]) != 0) acc or (1 shl d) else acc
            }
            else -> perm.indexOf(q.answer).coerceAtLeast(0)
        }
        return q.copy(options = perm.map { q.options[it] }, answer = newAnswer)
    }

    /**
     * UserAnswer：显示空间 → 原始空间（onCommit 落库/判分前的唯一换算口）。
     * perm == null 或 picked == null 原样返回；blank/short 的 picked 无位置语义（1 = 已提交），透传。
     */
    fun uaToOriginal(ua: UserAnswer?, q: Question, perm: List<Int>?): UserAnswer? {
        val p = ua?.picked ?: return ua
        if (perm == null) return ua
        val orig = when (q.type) {
            QuestionTypes.MULTI -> perm.indices.fold(0) { acc, d ->
                if (p and (1 shl d) != 0) acc or (1 shl perm[d]) else acc
            }
            QuestionTypes.SINGLE -> perm.getOrNull(p) ?: p
            else -> p
        }
        return if (orig == p) ua else ua.copy(picked = orig)
    }

    /**
     * UserAnswer：原始空间 → 显示空间（已答状态回显高亮用，与 [uaToOriginal] 互逆）。
     */
    fun uaToDisplay(ua: UserAnswer?, q: Question, perm: List<Int>?): UserAnswer? {
        val p = ua?.picked ?: return ua
        if (perm == null) return ua
        val disp = when (q.type) {
            QuestionTypes.MULTI -> (0..31).fold(0) { acc, o ->
                if (p and (1 shl o) != 0 && perm.contains(o)) acc or (1 shl perm.indexOf(o)) else acc
            }
            QuestionTypes.SINGLE -> {
                val d = perm.indexOf(p)
                if (d >= 0) d else p
            }
            else -> p
        }
        return if (disp == p) ua else ua.copy(picked = disp)
    }
}
