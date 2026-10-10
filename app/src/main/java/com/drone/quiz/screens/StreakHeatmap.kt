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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
 * 分档只看**当天做了多少题**，不看正确率：低正确率的日子若染成暗红，
 * 看着像欠账，但那些天可能刚做完一套难题。答得多本身不该被惩罚。
 *
 * ## v2.19.10 修的三条（用户真机截图反馈）
 *
 * ① **末列是一根孤零零的竖条。** v2.19.9 让末列「只画到今天」，于是本周
 *    若只过了 4 天，末列就只有 4 格，跟左边整块脱节、看着像另一根柱子。
 *    现把未来的日子画成 0 档的极淡格子——**整张图永远是规整的矩形**，
 *    末列与相邻列一体，这也正是 GitHub 的做法。
 * ② **左侧星期栏砍掉。** 用户原话"似可有可无"。砍掉后格子横向多得 14dp，
 *    整图更饱满；周内顺序（周一在顶）靠左对齐自然可读，不必再标。
 * ③ **图例移出组件**，改由首页卡片放在底部脚注行右侧，与左侧文字同一基线，
 *    不再在矩阵下方单独吊着。
 *
 * ## 更早的修复（留档）
 *
 * - v2.19.9：格子横竖双向均用 `Arrangement.spacedBy`（v2.19.8 漏了行间距，
 *   格子连成实心板）；月份标签 `wrapContentWidth(unbounded = true)` 允许溢出，
 *   否则「10月」被截成「10」。
 * - v2.19.8：格子尺寸按 `BoxWithConstraints` 的可用宽度反推，勿写死常量。
 */
private const val HEATMAP_WEEKS = 18
private val HeatGap = 3.dp
private val CellMin = 11.dp
private val CellMax = 22.dp

/** 分档：0 / 1–9 / 10–29 / 30–59 / 60+ */
private fun levelOf(answered: Int): Int = when {
    answered <= 0 -> 0
    answered < 10 -> 1
    answered < 30 -> 2
    answered < 60 -> 3
    else -> 4
}

private data class HeatCell(val answered: Int)

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

    // 本周一往前推 17 周 = 图的左上角
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
    var todayIndex = -1
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
            // ① 未来的日子按 0 档处理——与「没练」同色，整图恒为规整矩形
            val future = c.timeInMillis > startOfToday
            if (!future) todayIndex = cells.size
            cells += HeatCell(answered = if (future) 0 else (map[fmt.format(c.time)] ?: 0))
        }
    }
    // 今天 = 最后一个非未来格
    HeatGrid(cells, monthLabels.toList(), todayIndex)
}

/**
 * 分档色阶：0 档（无练习 / 未来）压到近乎底色，1–4 档由淡到浓跟随主题强调色。
 * 首页的图例也用这套色阶，故抽成内部函数。
 */
@Composable
internal fun heatScale(): List<androidx.compose.ui.graphics.Color> {
    val ui = LocalUi.current
    return listOf(
        ui.ink.copy(alpha = 0.06f),
        ui.accent.copy(alpha = 0.22f),
        ui.accent.copy(alpha = 0.45f),
        ui.accent.copy(alpha = 0.72f),
        ui.accent
    )
}

/** 图例「少 ▪▪▪▪ 多」，由首页放在底部脚注行右侧（③） */
@Composable
internal fun StreakHeatmapLegend(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("少", color = ui.textSub, fontSize = 9.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.width(4.dp))
        for (c in heatScale()) {
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

@Composable
internal fun StreakHeatmap(
    days: List<Pair<String, Int>>,
    modifier: Modifier = Modifier
) {
    val ui = LocalUi.current
    val grid = rememberHeatGrid(days)
    val scale = heatScale()

    BoxWithConstraints(modifier) {
        // 18 列铺满可用宽度：列距 = (可用宽 + 间距) / 列数，格子 = 列距 - 间距
        val pitch: Dp = ((maxWidth + HeatGap) / HEATMAP_WEEKS).coerceIn(CellMin, CellMax)
        val cell = pitch - HeatGap

        Column(Modifier.fillMaxWidth()) {
            // ---- 月份标签：高度交给文字自己决定；宽度不限，允许溢出所在列 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                for (w in 0 until HEATMAP_WEEKS) {
                    Box(Modifier.width(cell)) {
                        grid.monthLabels[w]?.let {
                            Text(
                                "${it}月",
                                color = ui.textSub,
                                fontSize = 9.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .wrapContentWidth(unbounded = true)
                                    .padding(start = 1.dp)
                            )
                        }
                    }
                }
            }
            // ---- 格子矩阵：横竖双向间距统一由 spacedBy 管，尺寸不再单独算间距 ----
            Row(horizontalArrangement = Arrangement.spacedBy(HeatGap)) {
                for (w in 0 until HEATMAP_WEEKS) {
                    Column(verticalArrangement = Arrangement.spacedBy(HeatGap)) {
                        for (d in 0 until 7) {
                            val idx = w * 7 + d
                            val c = grid.cells[idx]
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
    }
}
