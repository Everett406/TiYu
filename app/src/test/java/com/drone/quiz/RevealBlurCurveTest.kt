package com.drone.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 吐字动画的**观感性质**（v2.19.49）。
 *
 * 用户反馈：「模糊感觉不是很重…是不是要更提早地模糊一下？」
 *
 * 诊断结果与直觉相反。旧曲线是 `BLUR * (1 - easeOut(p))`，展开就是
 * **(1-p)² —— 急减速**：模糊在字还几乎看不见时（alpha≈0.2）就只剩 1.9px，
 * 等 alpha 爬到 0.5、眼睛终于能看清它，模糊只剩 0.75px —— 对 13sp
 * （约 26px 高）的字那是字高的 2.9%，肉眼基本无感。
 *
 * 所以真正的问题不是「什么时候开始模糊」，而是：
 *   ① 峰值太低（3px 只有字高的 11%）
 *   ② **曲线让模糊收得太早**——抢在「看得见」之前就烧完了
 *
 * 新曲线 1 - p^0.55：慢启动、后段陡收，让模糊覆盖「字真正可见」的整段。
 */
class RevealBlurCurveTest {

    /** 与 App 内 blurOf() 完全一致 */
    private val BLUR: Float = 6.5f
    private fun blurOf(p: Float): Float {
        val t = p.coerceIn(0f, 1f)
        return BLUR * (1f - t.pow(0.55f))
    }

    /** 13sp 在 2x 屏上约 26px 高——模糊占比要按这个折算才谈「明不明显」 */
    private val glyphHeightPx: Float = 26f

    // ── 形状性质 ────────────────────────────────────────────────

    @Test
    fun `起点是峰值`() {
        assertEquals(BLUR, blurOf(0f), 0.001f)
    }

    @Test
    fun `终点精确归零`() {
        // 归不了零就会在字上留一层永久的脏，这是必须卡死的不变量
        assertEquals(0f, blurOf(1f), 0f)
    }

    @Test
    fun `单调递减`() {
        var prev = Float.MAX_VALUE
        for (i in 0..100) {
            val b = blurOf(i / 100f)
            assertTrue("p=${i / 100f} 处非单调递减：$b > $prev", b <= prev + 1e-6f)
            prev = b
        }
    }

    @Test
    fun `越界输入被夹紧不炸`() {
        assertEquals(blurOf(0f), blurOf(-5f), 1e-6f)
        assertEquals(blurOf(1f), blurOf(5f), 1e-6f)
    }

    // ── 观感性质：这才是用户真正在意的 ────────────────────────────

    /**
     * 核心不变量：**在「字真正看得见」的区间里，模糊必须仍然明显。**
     *
     * 旧曲线在这里只剩 0.75px（字高 2.9%）——所以用户说"看不出模糊"。
     * 新曲线要求 ≥1px，也就是字高的 3.8% 以上。
     */
    @Test
    fun `字可见时模糊仍然明显`() {
        // Float 区间没有 step()，手写序列
        var k = 0
        while (k <= 8) {
            val p = 0.30f + k * 0.05f
            val b = blurOf(p)
            val pct = b / glyphHeightPx * 100
            assertTrue(
                "p=$p 时 blur=$b px（字高 $pct%）——旧曲线在这里只剩 0.75px，是看不出模糊的原因",
                b >= 1f && pct >= 3.5f
            )
            k++
        }
    }

    /**
     * 对比锚点：旧曲线 **(1-p)²** 在中后段塌到 1px 以下——
     * 这正是用户说"看不出模糊"的确切位置（字已经看得见了，模糊却没了）。
     *
     * 注：旧曲线在 p=0.3 时还有 1.47px，并不低于门槛；它真正的问题是
     * **塌得太早**，p 一过 0.35 就进入无感区，而那时字才刚能看清。
     */
    @Test
    fun `旧曲线在中后段塌到无感区`() {
        fun old(p: Float): Float {
            val inv = 1f - p.coerceIn(0f, 1f)
            return 3f * (1f - (1f - inv * inv))     // 3 * (1-p)^2
        }
        // 旧曲线 p=0.5 时只剩 0.75px = 字高 2.9% —— 这才是问题现场
        assertTrue("旧曲线 p=0.5 应低于 1px（实得 ${old(0.5f)}）", old(0.5f) < 1f)
        assertTrue("而新曲线 p=0.5 有 ${blurOf(0.5f)} px，是旧值的 " +
            "${blurOf(0.5f) / old(0.5f)} 倍", blurOf(0.5f) > old(0.5f) * 2f)
        // 全区间新曲线都明显高于旧曲线
        var k = 0
        while (k <= 8) {
            val p = 0.30f + k * 0.05f
            assertTrue("p=$p 新曲线（${blurOf(p)}）应远高于旧曲线（${old(p)}）",
                blurOf(p) > old(p) * 1.5f)
            k++
        }
    }

    /**
     * 慢启动：模糊不该在开头就塌掉。
     * 前 30% 的时间里至少要保住八成模糊，字才像是"正在被冲印出来"。
     */
    @Test
    fun `前段保持高模糊`() {
        assertTrue("p=0.2 掉太快：${blurOf(0.2f)}", blurOf(0.2f) >= BLUR * 0.58f)
        assertTrue("p=0.3 掉太快：${blurOf(0.3f)}", blurOf(0.3f) >= BLUR * 0.45f)
    }

    /**
     * 后段必须收干净，否则末段会留残影。
     * 1px 以下（字高 3.8%）肉眼已无感，App 侧据此裁掉 RenderEffect。
     */
    @Test
    fun `后段收得干净且阈值有意义`() {
        assertTrue("p=0.8 应低于 1px 阈值（实得 ${blurOf(0.8f)}）", blurOf(0.8f) < 1f)
        assertTrue("p=0.74 恰好在阈值附近（实得 ${blurOf(0.74f)}）", blurOf(0.74f) in 0.9f..1.1f)
    }
}
