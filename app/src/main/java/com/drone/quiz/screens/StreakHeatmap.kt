package com.drone.quiz.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ui.theme.LocalUi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 打卡热力图：GitHub 贡献图那种形状 —— **列 = 周，行 = 周一…周日**。
 *
 * 数据来自 `streak_log` 表（全局，不按题库隔离，与连击一致），仓库层已给出
 * 「日期 → 当日答题数」。当天没练的日子仓库不返回，这里补 0。
 *
 * 分档只看**当天做了多少题**，不看正确率：低正确率的日子若染成暗红，
 * 看着像欠账，但那些天可能刚做完一套难题。答得多本身不该被惩罚。
 *
 * 左侧第一列是「本周尚未到来的日子」，**留空不画**——画一个浅灰格虽然更像
 * GitHub，但会让整张图看着左边缺一块。
 */
private const val HEATMAP_WEEKS = 13
private val HEATMAP_CELL = 13.dp
private val HEATMAP_GAP = 3.dp
private val HEATMAP_GUTTER = 15.dp

/** 分档：0 / 1–9 / 10–29 / 30–59 / 60+ */
private fun levelOf(answered: Int): Int = when {
    answered <= 0 -> 0
    answered < 10 -> 1
    answered < 30 -> 2
    answered < 60 -> 3
    else -> 4
}

private data class HeatCell(
    val answered: Int,
    val future: Boolean
)

private data class HeatGrid(
    val cells: List<HeatCell>,
    /** 每一列顶部要显示的月份（null = 该列不标月） */
    val monthLabels: List<Int?>,
    /** 今天的下标（-1 = 图内没有今天），该格加描边 */
    val todayIndex: Int
)

@Composable
private fun rememberHeatGrid(days: List<Pair<String, Int>>): HeatGrid = remember(days) {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // 本周一往前推 12 周 = 图的左上角
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        // DAY_OF_WEEK：周日=1 … 周六=7，换算成「距周一几天」
        add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        add(Calendar.DAY_OF_YEAR, -7 * (HEATMAP_WEEKS - 1))
    }

    val map = HashMap<String, Int>(days.size)
    for ((d, n) in days) map[d] = n

    val cells = ArrayList<HeatCell>(HEATMAP_WEEKS * 7)
    val monthLabels = arrayOfNulls<Int>(HEATMAP_WEEKS)
    var lastMonth = -1
    for (w in 0 until HEATMAP_WEEKS) {
        for (d in 0 until 7) {
            val c = start.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, w * 7 + d)
            val m = c.get(Calendar.MONTH) + 1
            if (d == 0 && m != lastMonth) {
                monthLabels[w] = m
                lastMonth = m
            }
            val future = c.timeInMillis > todayStart
            cells += HeatCell(
                answered = if (future) 0 else (map[fmt.format(c.time)] ?: 0),
                future = future
            )
        }
    }
    val todayIndex = cells.indexOfLast { !it.future }
    HeatGrid(cells, monthLabels.toList(), todayIndex)
}

@Composable
internal fun StreakHeatmap(
    days: List<Pair<String, Int>>,
    modifier: Modifier = Modifier
) {
    val ui = LocalUi.current
    val grid = rememberHeatGrid(days)
    // 单色阶：由淡到浓，跟随主题强调色；0 档压到近乎底色
    val scale = listOf(
        ui.ink.copy(alpha = 0.06f),
        ui.accent.copy(alpha = 0.22f),
        ui.accent.copy(alpha = 0.45f),
        ui.accent.copy(alpha = 0.72f),
        ui.accent
    )

    Column(modifier) {
        // ---- 月份标签（与周列一一对齐） ----
        Row(Modifier.padding(start = HEATMAP_GUTTER, bottom = 3.dp)) {
            for (w in 0 until HEATMAP_WEEKS) {
                Box(
                    Modifier
                        .width(HEATMAP_CELL + HEATMAP_GAP)
                        .height(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    grid.monthLabels[w]?.let {
                        Text(
                            "${it}月",
                            color = ui.textSub, fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        // ---- 格子矩阵 ----
        Row(Modifier.fillMaxWidth()) {
            // 左侧周内标签：只标一三五日，避免七行全标太挤
            Column(Modifier.width(HEATMAP_GUTTER)) {
                listOf("一", "三", "五", "日").forEach { s ->
                    Box(
                        Modifier
                            .height(HEATMAP_CELL + HEATMAP_GAP)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (s == "日") Text(s, color = ui.textSub, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
            for (w in 0 until HEATMAP_WEEKS) {
                Column {
                    for (d in 0 until 7) {
                        val idx = w * 7 + d
                        val cell = grid.cells[idx]
                        if (cell.future) {
                            // 未来日：留空
                            Spacer(
                                Modifier
                                    .size(HEATMAP_CELL)
                                    .padding(bottom = HEATMAP_GAP)
                            )
                        } else {
                            val isToday = idx == grid.todayIndex
                            Box(
                                Modifier
                                    .size(HEATMAP_CELL)
                                    .padding(bottom = HEATMAP_GAP)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(scale[levelOf(cell.answered)])
                                    .then(
                                        if (isToday)
                                            Modifier.border(
                                                1.dp, ui.accent.copy(alpha = 0.9f),
                                                RoundedCornerShape(3.dp)
                                            )
                                        else Modifier
                                    )
                            )
                        }
                    }
                }
            }
        }
        // ---- 图例 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("少", color = ui.textSub, fontSize = 9.sp, fontWeight = FontWeight.Normal)
            Spacer(Modifier.width(4.dp))
            for (c in scale) {
                Box(
                    Modifier
                        .size(9.dp)
                        .padding(end = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c)
                )
            }
            Spacer(Modifier.width(1.dp))
            Text("多", color = ui.textSub, fontSize = 9.sp, fontWeight = FontWeight.Normal)
        }
    }
}
