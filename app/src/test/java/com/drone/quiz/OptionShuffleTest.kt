package com.drone.quiz

import com.drone.quiz.data.repo.OptionShuffle
import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.QuestionTypes
import com.drone.quiz.data.repo.UserAnswer
import com.drone.quiz.data.repo.judgeAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 随机选项（v2.11.0）答案映射正确性测试。
 * 核心命题：选项打乱后，用户选「显示空间里的某项」，换算回原始空间判分——
 * 选对的内容必判对、选错的内容必判错，且与「在显示题目上直接判分」全等。
 */
class OptionShuffleTest {

    private fun single(n: Int, answer: Int, id: Long = 1L) = Question(
        id = id, bankId = "b", category = "c", type = QuestionTypes.SINGLE,
        text = "题干", options = (1..n).map { "选项$it" }, answer = answer, answerText = "", explanation = "解析"
    )

    private fun multi(n: Int, answerMask: Int, id: Long = 1L) = Question(
        id = id, bankId = "b", category = "c", type = QuestionTypes.MULTI,
        text = "题干", options = (1..n).map { "选项$it" }, answer = answerMask, answerText = "", explanation = "解析"
    )

    private fun judge(answer: Int, id: Long = 1L) = Question(
        id = id, bankId = "b", category = "c", type = QuestionTypes.JUDGE,
        text = "题干", options = listOf("正确", "错误"), answer = answer, answerText = "", explanation = ""
    )

    private fun blank(id: Long = 1L) = Question(
        id = id, bankId = "b", category = "c", type = QuestionTypes.BLANK,
        text = "____", options = emptyList(), answer = 0, answerText = "答案", explanation = ""
    )

    // ---------- 1. permutation 双射性 ----------

    @Test
    fun permIsBijective_forAllCountsAndSalts() {
        val rng = Random(42)
        repeat(200) {
            val n = 2 + rng.nextInt(7)               // 2..8 个选项
            val salt = rng.nextLong(1, Long.MAX_VALUE)
            val q = single(n, rng.nextInt(n), id = rng.nextLong())
            val perm = OptionShuffle.permOf(q, salt)!!
            assertEquals(perm.size, n)
            assertEquals(perm.sorted(), (0 until n).toList())   // 双射 = 排列
        }
    }

    // ---------- 2. 稳定性：会话内顺序不漂移 ----------

    @Test
    fun permIsStable_forSameSaltAndQuestion() {
        val q = single(5, 2, id = 777L)
        val salt = 987654321L
        val p1 = OptionShuffle.permOf(q, salt)!!
        val p2 = OptionShuffle.permOf(q, salt)!!
        assertEquals(p1, p2)
    }

    @Test
    fun permDiffers_acrossSaltsMostly() {
        val q = single(6, 3, id = 5L)
        val perms = (1L..20L).map { OptionShuffle.permOf(q, it * 7919L)!! }
        val distinct = perms.toSet()
        // 20 个不同盐几乎不可能只有少量相同排列（6! = 720 种）
        assertTrue("不同盐应产生不同排列", distinct.size >= 18)
    }

    // ---------- 3. 判分一致性（核心）：显示空间判定 ≡ 原始空间判定 ----------

    @Test
    fun single_shuffledJudgingNeverWrong() {
        val rng = Random(7)
        repeat(500) { iter ->
            val n = 2 + rng.nextInt(6)
            val answer = rng.nextInt(n)
            val q = single(n, answer, id = 100L + iter)
            val salt = rng.nextLong(1, Long.MAX_VALUE)
            val perm = OptionShuffle.permOf(q, salt)!!
            val qDisp = OptionShuffle.display(q, perm)
            // 穷举所有显示位置：用户点显示位置 i
            for (d in 0 until n) {
                val uaDisp = UserAnswer(picked = d)
                val uaOrig = OptionShuffle.uaToOriginal(uaDisp, q, perm)
                // 命题 A：换算回原始空间判分 == 点中的显示位置确实是正确项
                assertEquals(
                    "perm=$perm disp=$d answer=$answer",
                    perm[d] == answer,
                    judgeAnswer(q, uaOrig) == true
                )
                // 命题 B：在显示题目上直接判分 == 换算后判分（UI 与数据两空间全等）
                assertEquals(
                    judgeAnswer(qDisp, uaDisp), judgeAnswer(q, uaOrig)
                )
                // 命题 C：往返互逆（恢复高亮回显链路）
                assertEquals(uaDisp, OptionShuffle.uaToDisplay(uaOrig, q, perm))
            }
            // 显示题目内容守恒：正确项文本不变，只是位置变了
            assertEquals(q.options[answer], qDisp.options[qDisp.answer])
            assertEquals(q.options.toSet(), qDisp.options.toSet())
        }
    }

    @Test
    fun multi_shuffledJudgingNeverWrong() {
        val rng = Random(11)
        repeat(500) { iter ->
            val n = 3 + rng.nextInt(5)
            val mask = (1 until n).fold(1) { acc, i -> if (rng.nextBoolean()) acc or (1 shl i) else acc }
            val q = multi(n, mask, id = 200L + iter)
            val salt = rng.nextLong(1, Long.MAX_VALUE)
            val perm = OptionShuffle.permOf(q, salt)!!
            val qDisp = OptionShuffle.display(q, perm)
            // 穷举所有显示空间子集组合（n ≤ 7，全子集穷举）
            for (sel in 0 until (1 shl n)) {
                val uaDisp = UserAnswer(picked = sel)
                val uaOrig = OptionShuffle.uaToOriginal(uaDisp, q, perm)
                assertEquals(
                    "perm=$perm sel=$sel mask=$mask",
                    sel == qDisp.answer,
                    judgeAnswer(q, uaOrig) == true
                )
                assertEquals(judgeAnswer(qDisp, uaDisp), judgeAnswer(q, uaOrig))
                assertEquals(uaDisp, OptionShuffle.uaToDisplay(uaOrig, q, perm))
            }
            // 显示空间正确项集合 = 原始正确项集合（按内容）
            val origSet = (0 until n).filter { mask and (1 shl it) != 0 }.map { q.options[it] }.toSet()
            val dispSet = (0 until n).filter { qDisp.answer and (1 shl it) != 0 }.map { qDisp.options[it] }.toSet()
            assertEquals(origSet, dispSet)
        }
    }

    // ---------- 4. 兼容性：salt=0 / 不适用题型全透传 ----------

    @Test
    fun zeroSaltMeansNoShuffle_everythingPassesThrough() {
        val q = single(4, 1)
        assertNull(OptionShuffle.permOf(q, 0L))
        val ua = UserAnswer(picked = 3)
        assertEquals(ua, OptionShuffle.uaToOriginal(ua, q, null))
        assertEquals(ua, OptionShuffle.uaToDisplay(ua, q, null))
    }

    @Test
    fun judgeBlankShortNeverShuffled() {
        val rng = Random(3)
        repeat(50) {
            val salt = rng.nextLong(1, Long.MAX_VALUE)
            assertNull(OptionShuffle.permOf(judge(0, id = it.toLong()), salt))
            assertNull(OptionShuffle.permOf(blank(id = it.toLong()), salt))
        }
    }

    @Test
    fun newSaltIsAlwaysNonZero() {
        repeat(1000) {
            assertNotEquals(0L, OptionShuffle.newSalt())
        }
    }

    // ---------- 5. 端到端场景：随机选答案重放，正确率应与选项内容一致 ----------

    @Test
    fun endToEnd_userAlwaysPicksCorrectContent_scoresAllCorrect() {
        val rng = Random(99)
        var correctCount = 0
        val rounds = 300
        repeat(rounds) { iter ->
            val n = 4
            val answer = rng.nextInt(n)
            val q = single(n, answer, id = 300L + iter)
            val salt = OptionShuffle.newSalt()
            val perm = OptionShuffle.permOf(q, salt)!!
            val qDisp = OptionShuffle.display(q, perm)
            // 用户「认得正确内容」，在打乱后的界面点正确内容所在位置
            val dispOfAnswer = qDisp.answer
            val uaOrig = OptionShuffle.uaToOriginal(UserAnswer(picked = dispOfAnswer), q, perm)
            if (judgeAnswer(q, uaOrig) == true) correctCount++
        }
        assertEquals(rounds, correctCount)
    }

    @Test
    fun endToEnd_userPicksWrongContent_scoresWrong() {
        val rng = Random(101)
        var wrongPickedWrong = 0
        val rounds = 300
        repeat(rounds) { iter ->
            val n = 4
            val answer = rng.nextInt(n)
            val q = single(n, answer, id = 400L + iter)
            val salt = OptionShuffle.newSalt()
            val perm = OptionShuffle.permOf(q, salt)!!
            val qDisp = OptionShuffle.display(q, perm)
            // 用户点了非正确内容的某项
            val wrongDisp = (0 until n).first { it != qDisp.answer }
            val uaOrig = OptionShuffle.uaToOriginal(UserAnswer(picked = wrongDisp), q, perm)
            if (judgeAnswer(q, uaOrig) == false) wrongPickedWrong++
        }
        assertEquals(rounds, wrongPickedWrong)
    }

    // ---------- 6. 基线守护 ----------

    @Test
    fun sanityJudgeBaseline() {
        val q = single(3, 1)
        assertTrue(judgeAnswer(q, UserAnswer(picked = 1)) == true)
        assertFalse(judgeAnswer(q, UserAnswer(picked = 0)) == true)
        assertNull(judgeAnswer(q, null))
    }
}
