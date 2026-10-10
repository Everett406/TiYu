package com.drone.quiz.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * ## 三条排版约束（v2.19.8 修）
 *
 * ① **格子大小必须按可用宽度算，不能写死。** v2.19.7 写死 13dp，
 *    卡片在宽屏上右侧空出一大块（用户反馈"左右没拉满"）。现用 `BoxWithConstraints`
 *    取实际可用宽度反推格子尺寸，13 列恰好铺满；上下限 11–19dp，
 *    既不会挤成马赛克，也免得在大屏/大字号下撑得太高。
 * ② **周内标签必须落在 0/2/4/6 行。** v2.19.7 写成四个盒子依次堆叠、
 *    只在最后一个里写字，于是"日"跑到了第 4 行。现按行号取标签。
 * ③ **月份标签不设固定高度。** v2.19.7 给了 12.dp 的盒子，9.sp 的字被裁掉下半截。
 *    现让高度由文字自身决定。
 *
 * 分档只看**当天做了多少题**，不看正确率：低正确率的日子若染成暗红，
 * 看着像欠账，但那些天可能刚做完一套难题。答得多本身不该被惩罚。
 *
 * 左侧第一列是「本周尚未到来的日子」，**留空不画**——画一个浅灰格虽然更像
 * GitHub，但会让整张图看着左边缺一块。
 */
private const val HEATMAP_WEEKS = 13
private val HeatGap = 3.dp
private val HeatGutter = 14.dp
private val CellMin = 11.dp
private val CellMax = 19.dp

/** 周内标签只标一三五日，七行全标太挤 */
private val WeekdayLabels = listOf("一", "三", "五", "日")

/** 分档：0 / 1–9 / 10–29 / 30–59 / 60+ */
private fun levelOf(answered: Int): Int = when {
    answered <= 0 -> 0
    answered < 10 -> 1
    answered < 30 -> 2
    answered < 60 -> 3
    else -> 4
}

private data class HeatCell(val answered: Int, val future: Boolean)

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
    val startOfToday = Calendar.getInstance().apply {
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
            // 只在该月第一次出现的那一列标月
            if (d == 0 && m != lastMonth) {
                monthLabels[w] = m
                lastMonth = m
            }
            val future = c.timeInMillis > startOfToday
            cells += HeatCell(
                answered = if (future) 0 else (map[fmt.format(c.time)] ?: 0),
                future = future
            )
        }
    }
    HeatGrid(cells, monthLabels.toList(), cells.indexOfLast { !it.future })
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

    BoxWithConstraints(modifier) {
        // ① 13 列铺满可用宽度：列距 = (可用宽 - 标签槽 + 间距) / 列数，格子 = 列距 - 间距
        val pitch: Dp =
            ((maxWidth - HeatGutter + HeatGap) / HEATMAP_WEEKS).coerceIn(CellMin, CellMax)
        val cell = pitch - HeatGap
        val rowPitch = cell + HeatGap

        Column(Modifier.fillMaxWidth()) {
            // ---- 月份标签：高度交给文字自己决定，勿设固定值（②） ----
            Row(Modifier.padding(start = HeatGutter, bottom = 4.dp)) {
                for (w in 0 until HEATMAP_WEEKS) {
                    Box(Modifier.width(pitch)) {
                        grid.monthLabels[w]?.let {
                            Text(
                                "${it}月",
                                color = ui.textSub,
                                fontSize = 9.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 1.dp)
                            )
                        }
                    }
                }
            }
            // ---- 格子矩阵 ----
            Row(Modifier.fillMaxWidth()) {
                // ② 周内标签落在第 0/2/4/6 行
                Column(Modifier.width(HeatGutter)) {
                    for (d in 0 until 7) {
                        Box(
                            Modifier
                                .height(rowPitch)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (d % 2 == 0) {
                                Text(
                                    WeekdayLabels[d / 2],
                                    color = ui.textSub, fontSize = 9.sp, maxLines = 1
                                )
                            }
                        }
                    }
                }
                for (w in 0 until HEATMAP_WEEKS) {
                    Column {
                        for (d in 0 until 7) {
                            val idx = w * 7 + d
                            val c = grid.cells[idx]
                            if (c.future) {
                                // 未来日：留空不画
                                Spacer(Modifier.size(cell))
                            } else {
                                Box(
                                    Modifier
                                        .size(cell)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(scale[levelOf(c.answered)])
                                        .then(
                                            if (idx == grid.todayIndex)
                                                Modifier.border(
                                                    1.5.dp, ui.accent.copy(alpha = 0.9f),
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
            // ---- 图例：紧贴矩阵右下 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("少", color = ui.textSub, fontSize = 9.sp, fontWeight = FontWeight.Normal)
                Spacer(Modifier.width(4.dp))
                for (c in scale) {
                    Box(
                        Modifier
                            .padding(end = 3.dp)
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c)
                    )
                }
                Spacer(Modifier.width(1.dp))
                Text("多", color = ui.textSub, fontSize = 9.sp, fontWeight = FontWeight.Normal)
            }
        }
    }
}
