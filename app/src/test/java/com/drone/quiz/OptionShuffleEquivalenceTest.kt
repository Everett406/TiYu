package com.drone.quiz

import com.drone.quiz.data.repo.OptionShuffle
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.QuestionTypes
import com.drone.quiz.data.repo.UserAnswer
import com.drone.quiz.data.repo.judgeAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.19.33 选项打乱（仅模考）的答案映射等价性回归。
 *
 * 核心命题：用户在**显示空间**点选，经 uaToOriginal 换回原始空间判分，
 * 必须与「直接在乱序题上判分」完全一致——即选对的内容必判对、选错的内容必判错。
 * 错题本、统计、交卷计分全部只见原始空间，这一条不成立就等于整套逻辑错位。
 *
 * 附带验证：判断题/简答不参与打乱、salt=0 天然不打乱、回显与换算互逆。
 */
class OptionShuffleEquivalenceTest {

    private fun single(n: Int, ans: Int, id: Long) = Question(
        id = id, bankId = "b", category = "c", type = QuestionTypes.SINGLE,
        text = "题干", options = (1..n).map { "选项$it" }, answer = ans, answerText = "", explanation = "解析"
    )

    private fun multi(n: Int, ansMask: Int, id: Long) = Question(
        id = id, bankId = "b", category = "c", type = QuestionTypes.MULTI,
        text = "题干", options = (1..n).map { "选项$it" }, answer = ansMask, answerText = "", explanation = "解析"
    )

    /** 单选：4 选项 × 4 个正确答案 × 1000 个盐 × 每个显示位都点一遍 */
    @Test
    fun single_shuffled_isEquivalentToOriginal() {
        for (ans in 0..3) {
            for (salt in 1L..1000L) {
                val q = single(4, ans, 42L)
                val perm = OptionShuffle.permOf(q, salt)!!
                val dq = OptionShuffle.display(q, perm)
                for (dp in 0..3) {
                    val uaD = UserAnswer(picked = dp)
                    val uaO = OptionShuffle.uaToOriginal(uaD, q, perm)!!
                    assertEquals(
                        "单选 ans=$ans salt=$salt dp=$dp",
                        judgeAnswer(dq, uaD) ?: false,
                        judgeAnswer(q, uaO) ?: false
                    )
                }
            }
        }
    }

    /** 多选：4 选项答案掩码 0..15 全枚举 × 200 个盐 × 16 种选择掩码 */
    @Test
    fun multi_shuffled_isEquivalentToOriginal() {
        for (mask in 0..15) {
            for (salt in 1L..200L) {
                val q = multi(4, mask, 7L)
                val perm = OptionShuffle.permOf(q, salt)!!
                val dq = OptionShuffle.display(q, perm)
                for (dm in 0..15) {
                    val uaD = UserAnswer(picked = dm)
                    val uaO = OptionShuffle.uaToOriginal(uaD, q, perm)!!
                    assertEquals(
                        "多选 mask=$mask salt=$salt dm=$dm",
                        judgeAnswer(dq, uaD) ?: false,
                        judgeAnswer(q, uaO) ?: false
                    )
                }
            }
        }
    }

    /** 已作答状态回显：uaToDisplay 与 uaToOriginal 必须互逆 */
    @Test
    fun displayAndOriginalAreInverse() {
        for (salt in 1L..500L) {
            val q = multi(4, 5, 3L)
            val perm = OptionShuffle.permOf(q, salt)!!
            for (m in 0..15) {
                val uaO = UserAnswer(picked = m)
                val round = OptionShuffle.uaToOriginal(OptionShuffle.uaToDisplay(uaO, q, perm), q, perm)
                assertEquals("回显非互逆 m=$m salt=$salt", m, round?.picked)
            }
        }
    }

    /** 判断题（正确/错误 位置语义固定）与简答（无选项）一律不参与打乱 */
    @Test
    fun judgeAndShortAreNeverShuffled() {
        val jq = Question(1L, "b", "c", QuestionTypes.JUDGE, "t", listOf("正确", "错误"), 1, "", "")
        assertFalse(OptionShuffle.applicable(jq))
        assertNull(OptionShuffle.permOf(jq, 99L))
        val sq = Question(2L, "b", "c", QuestionTypes.SHORT, "t", emptyList(), 0, "参考", "")
        assertFalse(OptionShuffle.applicable(sq))
    }

    /** salt=0 是「不打乱」哨兵值：旧模考记录与开关关闭的记录都走这条 */
    @Test
    fun zeroSaltMeansNoShuffle() {
        assertNull(OptionShuffle.permOf(single(4, 1, 5L), 0L))
    }
}
