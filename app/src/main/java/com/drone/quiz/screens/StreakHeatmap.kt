package com.drone.quiz.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.data.repo.Repo
import com.drone.quiz.ui.theme.LocalUi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor

/**
 * 打卡热力图：GitHub 贡献图那种形状 —— **列 = 周，行 = 周一…周日**。
 *
 * 数据来自 `streak_log` 表（全局，不按题库隔离，与连击一致），仓库层已给出
 * `HeatDay(date, answered, correct)`。当天没练的日子仓库不返回，这里补 0。
 *
 * 分档只看**当天做了多少题**，不看正确率：低正确率的日子若染成暗红，
 * 看着像欠账，但那些天可能刚做完一套难题。答得多本身不该被惩罚。
 * 正确率只出现在长按详情浮窗里。
 *
 * ## 尺寸怎么定（v2.19.11 起，别改回去）
 *
 * 周数 **22**（约 5 个月）、每列固定 `pitch = floor(可用宽 / 22)`、格子 `pitch - 间距`。
 *
 * · **格子尺寸按可用宽度算，勿写死常量。** 写死会在宽屏右侧空一大块。
 * · **列宽取整（floor）再 `width(pitch)` 定死。** v2.19.10 用
 *   `Row(spacedBy)` 让子项自适应，结果 18 列总宽略超可用宽，最后一列被约束压扁，
 *   出现"右侧一列狭长如被挤压"。列宽一旦由每列自己声明，就不存在谁被挤的问题。
 * · **周数 22 而非 18/26。** 用户反馈卡片"略显过盛"；26 周格子太小（8dp），
 *   18 周则高 113dp。22 周格子约 10dp、网格高约 92dp，比 18 周矮约 21dp，
 *   格子仍够看清深浅。
 *
 * ## 详情浮窗（v2.19.12 重做）
 *
 * **交互按图表的惯例来：轻点格子即出窗**，不是长按。触屏没有 hover，
 * 轻点是最贴近"鼠标移上去"的动作；长按反而不合直觉，还会和列表的
 * 长按滚动打架。再点同一格收起，4 秒后自动消失。
 *
 * v2.19.11 那一版**只有框、没有窗**，是条件写死了：
 * `if (tipIndex >= 0 && tipW > 0 && gridW > 0)` 才渲染浮窗，而 `tipW`
 * 只有浮窗被渲染后 `onSizeChanged` 才会写入——浮窗不出，尺寸永不更新，
 * 尺寸不更新浮窗更不出。鸡生蛋死锁，故整版永远只有格子上的描边。
 * 现改为：**先无条件渲染，再拿尺寸定位**（首帧位置略有偏差，下一帧即修正）。
 *
 * 外观按用户给的参考图：深色圆角小窗 + 指向格子的三角，默认浮在格子上方，
 * 顶上卡片边缘则改浮下方；左右始终夹在图内不出界。
 *
 * ## v2.19.13 按真机截图修的三处
 *
 * ① **窗太宽、盖住半个图。** v2.19.12 把日期和题量拼在一行
 *    （`9月5日 · 答了 206 题`），十几个字排成一行，窗宽到能盖住五六列、
 *    还压到下面的图例和上面的月份标签。现拆成三行（日期 / 题量 / 正确率），
 *    字号各降一档，窗宽压到约 5 列、窗高约 50dp。**没练的日子只出两行。**
 * ② **箭头方向不跟着位置走。** 「放上方还是下方」在 offset 里算了一遍给坐标，
 *    在 `DayTip` 里却写死 `below = false` 给箭头——于是窗被翻到下方、
 *    箭头还朝下。现把判断**抽成一个 `showBelow` 状态**，坐标与箭头共用同一份，
 *    不可能再不一致。
 * ③ **选中描边太重。** 1.5dp 的 `ui.text` 黑圈在小方格上像套了个相框，
 *    降到 1dp、透明度 55%。
 */
private const val HEATMAP_WEEKS = 22
private val HeatGap = 3.dp
private val CellMin = 9.dp
private val CellMax = 20.dp
private const val TIP_LINGER_MS = 4000L

/** 分档：0 / 1–9 / 10–29 / 30–59 / 60+ */
private fun levelOf(answered: Int): Int = when {
    answered <= 0 -> 0
    answered < 10 -> 1
    answered < 30 -> 2
    answered < 60 -> 3
    else -> 4
}

private data class HeatCell(
    val date: String,
    val answered: Int,
    val correct: Int,
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
private fun rememberHeatGrid(days: List<Repo.HeatDay>): HeatGrid = remember(days) {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    val cal = Calendar.getInstance()
    val startOfToday = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // 本周一往前推 21 周 = 图的左上角
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_YEAR, -((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7))
    cal.add(Calendar.DAY_OF_YEAR, -7 * (HEATMAP_WEEKS - 1))
    val startMs = cal.timeInMillis   // 取毫秒：Date + Int 是合法运算，会把下面的表达式解析歪

    val map = HashMap<String, Repo.HeatDay>(days.size)
    for (d in days) map[d.date] = d

    val cells = ArrayList<HeatCell>(HEATMAP_WEEKS * 7)
    val monthLabels = arrayOfNulls<Int>(HEATMAP_WEEKS)
    var lastMonth = -1
    var todayIndex = -1
    for (w in 0 until HEATMAP_WEEKS) {
        for (d in 0 until 7) {
            val t = startMs + (w * 7 + d) * 86_400_000L
            val c = Calendar.getInstance().apply { setTimeInMillis(t) }
            val m = c.get(Calendar.MONTH) + 1
            // 只在该月第一次出现的那一列标月
            if (d == 0 && m != lastMonth) {
                monthLabels[w] = m
                lastMonth = m
            }
            val future = c.timeInMillis > startOfToday
            if (!future) todayIndex = cells.size
            val key = fmt.format(c.time)
            val rec = if (future) null else map[key]
            cells += HeatCell(
                date = key,
                answered = rec?.answered ?: 0,
                correct = rec?.correct ?: 0,
                future = future
            )
        }
    }
    HeatGrid(cells, monthLabels.toList(), todayIndex)
}

/** 分档色阶：0 档（无练习 / 未来）压到近乎底色，1–4 档由淡到浓跟随主题强调色。 */
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

/** 图例「少 ▪▪▪▪ 多」，由首页放在底部脚注行右侧 */
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
    days: List<Repo.HeatDay>,
    modifier: Modifier = Modifier
) {
    val ui = LocalUi.current
    val grid = rememberHeatGrid(days)
    val scale = heatScale()
    val density = LocalDensity.current.density

    var tipIndex by remember(days) { mutableIntStateOf(-1) }
    var tipW by remember { mutableIntStateOf(0) }
    var tipH by remember { mutableIntStateOf(0) }
    var gridW by remember { mutableIntStateOf(0) }
    var gridH by remember { mutableIntStateOf(0) }
    // 自动消失。点同一格再次可立即收起（见 onTap 的 toggle 逻辑）
    LaunchedEffect(tipIndex) {
        if (tipIndex >= 0) { kotlinx.coroutines.delay(TIP_LINGER_MS); tipIndex = -1 }
    }

    BoxWithConstraints(modifier) {
        // 列宽**取整后由每一列自己声明**：既保证 22 列严丝合缝铺满可用宽，
        // 又不会出现最后一列被约束压扁的情况（v2.19.10 的"狭长挤压"就是丢了这层保证）
        val rawPitch = (maxWidth.value / HEATMAP_WEEKS)
        val pitch: Dp = Dp(floor(rawPitch))
            .coerceIn(CellMin + HeatGap, CellMax)
        val cell = pitch - HeatGap

        Column(Modifier.fillMaxWidth()) {
            // ---- 月份标签：列宽与格子列一致，故必然对齐 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                for (w in 0 until HEATMAP_WEEKS) {
                    Box(Modifier.width(pitch)) {
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
            // ---- 格子矩阵 + 长按浮窗 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { gridW = it.width; gridH = it.height }
                    .pointerInput(grid.cells, pitch) {
                        // PointerInputScope 本身即 Density，就地换算 px
                        val pitchPx = pitch.toPx()
                        fun cellAt(pos: androidx.compose.ui.geometry.Offset): Int? {
                            val w = (pos.x / pitchPx).toInt()
                            val d = (pos.y / pitchPx).toInt()
                            return if (w in 0 until HEATMAP_WEEKS && d in 0 until 7) w * 7 + d else null
                        }
                        detectTapGestures(
                            onTap = { pos ->
                                // 图表惯例：轻点出窗；再点同一格收起，点别的格则移过去
                                tipIndex = cellAt(pos)?.let { if (it == tipIndex) -1 else it } ?: -1
                            }
                        )
                    }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    for (w in 0 until HEATMAP_WEEKS) {
                        Column(
                            Modifier.width(pitch),
                            verticalArrangement = Arrangement.spacedBy(HeatGap)
                        ) {
                            for (d in 0 until 7) {
                                val idx = w * 7 + d
                                val c = grid.cells[idx]
                                Box(
                                    Modifier
                                        .size(cell)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(scale[levelOf(c.answered)])
                                        .then(
                                            when {
                                                idx == tipIndex -> Modifier.border(
                                                    1.dp, ui.text.copy(alpha = 0.55f),
                                                    RoundedCornerShape(3.dp)
                                                )
                                                idx == grid.todayIndex -> Modifier.border(
                                                    1.5.dp, ui.accent.copy(alpha = 0.9f),
                                                    RoundedCornerShape(3.dp)
                                                )
                                                else -> Modifier
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
                // ---- 详情浮窗：坐标与箭头方向共用同一个 showBelow，杜绝二者打架 ----
                if (tipIndex >= 0) {
                    val c = grid.cells[tipIndex]
                    val col = tipIndex / 7
                    val row = tipIndex % 7
                    // 放得下就放上方；放不下翻到下方。首帧 tipH 未知，先假定上方，
                    // 量到尺寸后下面的 showBelow 会自动纠正。
                    val showBelow = tipH > 0 && gridH > 0 &&
                            pitch.value * density * row - tipH - 10f < 0f
                    DayTip(
                        date = c.date,
                        answered = c.answered,
                        correct = c.correct,
                        below = showBelow,
                        modifier = Modifier
                            .onSizeChanged { tipW = it.width; tipH = it.height }
                            .offset {
                                // DensityScope 亦提供 toPx()
                                val pitchPx = pitch.toPx()
                                val cx = pitchPx * col + (pitchPx - HeatGap.toPx()) / 2f
                                val tipWf = tipW.toFloat()
                                val gridWf = gridW.toFloat()
                                val x = (cx - tipWf / 2f)
                                    .coerceIn(0f, (gridWf - tipWf).coerceAtLeast(0f))
                                    .toInt()
                                val y = if (showBelow) {
                                    pitchPx * (row + 1) + 8f
                                } else {
                                    pitchPx * row - tipH - 10f
                                }
                                IntOffset(x, y.toInt())
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTip(
    date: String,
    answered: Int,
    correct: Int,
    below: Boolean,
    modifier: Modifier = Modifier
) {
    val cal = remember(date) {
        Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date) ?: Date()
        }
    }
    val md = remember(date) { "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日" }
    val rate = if (answered > 0) (correct * 100) / answered else 0
    val bg = Color(0xF2222733)      // 深色小窗，与参考图一致
    val fg = Color(0xFFF2F4F8)
    val fgSub = Color(0xFFA8B0C0)

    // 箭头：旋转 45° 的小方块，一半压在窗体上、一半露在外面；
    // 浮在格子上方时朝下（在下边），翻到下方时朝上（在上边）。
    val arrow: @Composable () -> Unit = {
        Box(
            Modifier
                .offset(y = if (below) 4.dp else (-4).dp)
                .size(8.dp)
                .rotate(if (below) -45f else 45f)
                .background(bg)
        )
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (below) arrow()
        Column(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            // 三行分排而非拼成一行——拼一行时窗宽到能盖住五六列，压住图例与月份标签
            Text(md, color = fgSub, fontSize = 9.sp, maxLines = 1)
            Text(
                if (answered > 0) "答了 $answered 题" else "未练习",
                color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.padding(top = 1.dp)
            )
            if (answered > 0) {
                Text(
                    "正确率 $rate%",
                    color = fgSub, fontSize = 9.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        if (!below) arrow()
    }
}
