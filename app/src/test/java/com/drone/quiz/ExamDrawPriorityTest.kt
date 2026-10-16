package com.drone.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.19.35 模考抽题优先级（错题 → 未答 → 其余）的核心算法回归。
 *
 * 抽题逻辑在 `Repo.startExam` 里内联（要拿 DAO），不便直接单测，
 * 所以这里把同一套优先级逻辑原样复刻一份来锁行为。
 * 改动算法时**两边都要改**——static_check 7.z3 段会核对两处代码是否一致。
 */
class ExamDrawPriorityTest {

    /**
     * 与 Repo.startExam 中的抽题段保持一致：
     * 错题（removed=0）→ 未答（attempts=0）→ 其余已答，逐级回填取满 want。
     */
    private fun draw(
        all: List<Long>,
        wrong: Set<Long>,
        unseen: Set<Long>,
        want: Int,
        preferWrong: Boolean,
        preferUnseen: Boolean
    ): List<Long> {
        val w = if (preferWrong) wrong else emptySet()
        val u = if (preferUnseen) unseen else emptySet()
        val rest = all.filter { it !in w && it !in u }

        val chosen = ArrayList<Long>(want)
        for (pool in listOf(w, u, rest.toSet())) {
            if (chosen.size >= want) break
            pool.filter { it !in chosen }.shuffled().forEach { if (chosen.size < want) chosen += it }
        }
        return chosen
    }

    @Test
    fun 错题优先_错题够时全抽错题() {
        val all = (1L..100L).toList()
        val wrong = (1L..30L).toSet()
        val unseen = (31L..60L).toSet()
        val r = draw(all, wrong, unseen, 20, preferWrong = true, preferUnseen = true)
        assertEquals(20, r.size)
        assertTrue("应全来自错题池，实际漏了 ${r.filter { it !in wrong }}", r.all { it in wrong })
    }

    @Test
    fun 错题优先_错题不够时回填未答() {
        val all = (1L..100L).toList()
        val wrong = (1L..8L).toSet()
        val unseen = (31L..60L).toSet()
        val r = draw(all, wrong, unseen, 20, preferWrong = true, preferUnseen = true)
        assertEquals(20, r.size)
        assertEquals("8 道错题应全部入选", 8, r.count { it in wrong })
        assertEquals("其余 12 道应来自未答池", 12, r.count { it in unseen })
    }

    @Test
    fun 两层都不够时回填其余已答() {
        val all = (1L..40L).toList()
        val wrong = (1L..5L).toSet()
        val unseen = (11L..15L).toSet()
        val r = draw(all, wrong, unseen, 30, preferWrong = true, preferUnseen = true)
        assertEquals("必须抽满，不能组不出卷", 30, r.size)
        assertEquals(5, r.count { it in wrong })
        assertEquals(5, r.count { it in unseen })
        assertEquals("其余 20 道来自已答池", 20, r.count { it !in wrong && it !in unseen })
    }

    @Test
    fun 错题压过未答_两者都开时错题优先() {
        val all = (1L..100L).toList()
        val wrong = (1L..30L).toSet()
        val unseen = (31L..60L).toSet()
        // 只开未答：抽到的 20 道应全在未答池
        val onlyUnseen = draw(all, wrong, unseen, 20, preferWrong = false, preferUnseen = true)
        assertTrue(onlyUnseen.all { it in unseen })
        // 两个都开：同样这 20 道应该变成错题
        val both = draw(all, wrong, unseen, 20, preferWrong = true, preferUnseen = true)
        assertTrue(both.all { it in wrong })
    }

    @Test
    fun 错题本为空时不干扰未答优先() {
        val all = (1L..100L).toList()
        val unseen = (1L..40L).toSet()
        // 注意：不能断言两次结果相等——全随机下每次 shuffled() 结果本就可以不同。
        // 该断言的是「开着错题优先但错题本为空」时，未答优先的行为没有被破坏。
        val r = draw(all, emptySet(), unseen, 20, preferWrong = true, preferUnseen = true)
        assertEquals(20, r.size)
        assertTrue(
            "错题本为空时应退回未答优先，实际抽到了 ${r.filter { it !in unseen }}",
            r.all { it in unseen }
        )
    }

    @Test
    fun 全部关闭时退回全随机() {
        val all = (1L..100L).toList()
        val r = draw(all, (1L..30L).toSet(), (31L..60L).toSet(), 20, false, false)
        assertEquals(20, r.size)
        // 全随机可能命中任一池，只要求不重复且都是库内题
        assertEquals(20, r.toSet().size)
        assertTrue(r.all { it in all })
    }

    @Test
    fun want超过题库总量时只给实际有的() {
        val all = (1L..50L).toList()
        val r = draw(all, (1L..10L).toSet(), (11L..30L).toSet(), 200, true, true)
        assertEquals("不抛异常，返回全部 50 题", 50, r.size)
    }

    @Test
    fun 无重复() {
        val all = (1L..100L).toList()
        val wrong = (1L..30L).toSet()
        val unseen = (31L..60L).toSet()
        // 错题/未答有重叠的历史脏数据也不能抽重
        val overlap = (51L..60L).toSet()
        val r = draw(all, wrong, unseen + overlap, 50, true, true)
        assertEquals("错题与未答池重叠时仍不得重复", 50, r.toSet().size)
    }
}
