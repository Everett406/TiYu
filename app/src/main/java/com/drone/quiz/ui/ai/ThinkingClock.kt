package com.drone.quiz.ui.ai

import android.os.SystemClock

/**
 * 思考耗时与思考正文的**静态表**。
 *
 * ## 为什么不能放 widget State
 * `ThinkingPanel` 在 `LazyColumn` 里。行滚出视口会被销毁、滚回来重建 —— State 里的
 * 计时器就归零了。servllama 在这上面栽过（显示「已思考 0 秒」），根因就是
 * `State { var elapsed = 0L }` 每次重建都从 0 开始。
 *
 * ## 槽位
 * 槽号 = assistant 消息在会话列表里的下标，由调用方直接给定，不自动分配——
 * 自动分配会和 `messages` 下标脱节，思考区就挂不到对应的气泡上了。
 */
object ThinkingClock {

    class Entry(
        val startedAt: Long,
        /** 正文开始输出的时刻；null = 还在思考 */
        @Volatile var frozenAt: Long? = null,
        /** 思考正文增量追加，读侧可能与写侧并发，故不加锁只保证引用可见 */
        @Volatile var reasoning: StringBuilder = StringBuilder()
    ) {
        val closed: Boolean get() = frozenAt != null
        fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
            (frozenAt ?: now) - startedAt
    }

    private val table = LinkedHashMap<Int, Entry>()

    /** 该槽开始思考。 */
    fun start(slot: Int) = synchronized(table) {
        table[slot] = Entry(SystemClock.elapsedRealtime())
    }

    /** 取该槽的思考正文并追加增量。 */
    fun appendReasoning(slot: Int, delta: String) = synchronized(table) {
        table[slot]?.reasoning?.append(delta)
    }

    /** 正文开始 → 冻结耗时。重复调用只冻第一次（onDelta 每次 delta 都会调）。 */
    fun close(slot: Int) = synchronized(table) {
        table[slot]?.let { if (it.frozenAt == null) it.frozenAt = SystemClock.elapsedRealtime() }
    }

    fun peek(slot: Int): Entry? = synchronized(table) { table[slot] }

    fun clearAll() = synchronized(table) { table.clear() }
}