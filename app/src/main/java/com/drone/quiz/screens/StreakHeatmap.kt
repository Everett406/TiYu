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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
 * ## 详情浮窗：**真·浮层**（v2.19.15）
 *
 * 用户原话："让这个小浮窗完完全全浮在上面，不要占用其他的空间，就是刚好浮在
 * 这个位置，定位到这个位置。"
 *
 * 此前四版都是把浮窗当作网格 Box 的子节点、用 `offset` 摆位置——于是陷入两难：
 * 窗比格子大（窗高约 50dp，格子只有 10dp），夹在网格内就必然盖住旁边几列；
 * 想不盖就得允许越界，一越界就压住脚注、图例，甚至冲出卡片被圆角裁掉
 * （v2.19.14 的截图）。**这是架构选错了，不是参数没调好。**
 *
 * 现改为 **`androidx.compose.ui.window.Popup`**：独立窗口绘制在整棵视图树之上，
 * **不占布局、不被父级裁剪、可自由定位到屏幕任意位置**——"完完全全浮在上面"。
 * 附带解决两件旧账：
 * · `PopupPositionProvider.calculatePosition` 拿得到**已量好的 popup 尺寸**，
 *   v2.19.11 那处"先量尺寸才渲染、渲染了才量得到"的鸡生蛋死锁彻底消失，
 *   不再需要 `tipW/tipH` 两个状态；
 * · `dismissOnClickOutside` 白送"点别处收起"，此前要靠事件分发自己实现，
 *   且容易和列表滚动打架。
 *
 * 网格区不再需要 `clipToBounds()`——浮窗已不在图里。
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
 * ## v2.19.14 按真机截图修的四处（浮窗冲出卡片 / 卡片高度会跳）
 *
 * ① **浮窗翻到下方后直接冲出卡片。** v2.19.13 的定位是"上方放得下就上方，
 *    否则翻到下方"，可"下方"没有任何下界——最下面几行的格子一翻，窗就压到脚注
 *    文字和图例上，甚至被卡片圆角裁掉半截（用户截图里"正确率 77%"被切了一半）。
 *    现改成**三态**：上方放得下就上方；上方放不下且下方还塞得下，才翻下方；
 *    **两边都塞不下就贴住网格底部**，任何情况下窗的上下边都不越出网格区域。
 * ② **网格区加 `clipToBounds()` 兜底。** 即使上面某处算错，窗也画不出去。
 * ③ **卡片高度会随文字长短跳。** 顶行注解（"再练 4 天达成 7 天" / "手感正热，
 *    保持节奏"）与脚注（"今日答对 28 · 答错 12" / "今天还没开始，来几题热热手"）
 *    在大字号或小屏下会换行，换行就撑高整张卡。现全部加 `maxLines = 1`，
 *    高度锁定不随文案变化——这正是用户说的"卡片高度没有锁定"。
 * ④ 选中描边再淡一档（0.55 → 0.45）。
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

/**
 * 浮窗定位：**上方放得下就上方，否则下方**，左右与上下都夹在窗口内不出界。
 * `popupContentSize` 是**已量好的**浮窗尺寸——这正是 Popup 相较"offset + 自测量"的关键优势，
 * 不再需要"先量尺寸才渲染、渲染了才量得到"的自我循环。
 */
private class TooltipPositionProvider(
    private val cellCenterX: () -> Float,
    private val cellTop: () -> Float,
    private val cellBottom: () -> Float
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val maxX = (windowSize.width - w).coerceAtLeast(0).toFloat()
        val maxY = (windowSize.height - h).coerceAtLeast(0).toFloat()
        val x = (cellCenterX() - w / 2f).coerceIn(0f, maxX)
        val above = cellTop() - h - 10f
        val below = cellBottom() + 10f
        val y = if (above >= 0f) above else below
        return IntOffset(x.toInt(), y.coerceIn(0f, maxY).toInt())
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
    // 热力图在**窗口**中的位置，供浮窗（Popup）定位
    var gridPosInWindow by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(tipIndex) {
        if (tipIndex >= 0) { kotlinx.coroutines.delay(TIP_LINGER_MS); tipIndex = -1 }
    }

    BoxWithConstraints(modifier) {
        // 列宽取整后由每一列自己声明：既保证 22 列严丝合缝铺满可用宽，
        // 又不会出现最后一列被约束压扁（v2.19.10 的"狭长挤压"就是丢了这层保证）
        val pitch: Dp = Dp(kotlin.math.floor(maxWidth.value / HEATMAP_WEEKS))
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
            // ---- 格子矩阵 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { gridPosInWindow = it.positionInWindow() }
                    .pointerInput(grid.cells, pitch) {
                        // PointerInputScope 本身即 Density，就地换算 px
                        val pitchPx = pitch.toPx()
                        fun cellAt(pos: Offset): Int? {
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
                                                    1.dp, ui.text.copy(alpha = 0.45f),
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
            }
        }

        // ---- 详情浮窗：独立窗口，完完全全浮在整棵视图树之上 ----
        // 声明在 BoxWithConstraints 作用域内只是为了取到 pitch/maxWidth；它渲染到
        // 独立窗口，声明位置不影响绘制。Popup 不占布局、不被父级裁剪，
        // calculatePosition 拿到的还是**已量好**的尺寸——所以不再需要自测量。
        if (tipIndex >= 0) {
            val c = grid.cells[tipIndex]
            val col = tipIndex / 7
            val row = tipIndex % 7
            val pitchPx = pitch.value * density
            val gapPx = HeatGap.value * density
            Popup(
                popupPositionProvider = TooltipPositionProvider(
                    cellCenterX = {
                        gridPosInWindow.x + pitchPx * col + (pitchPx - gapPx) / 2f
                    },
                    cellTop = { gridPosInWindow.y + pitchPx * row },
                    cellBottom = { gridPosInWindow.y + pitchPx * row + (pitchPx - gapPx) }
                ),
                onDismissRequest = { tipIndex = -1 },
                properties = PopupProperties(
                    focusable = true,          // 让 dismissOnClickOutside 生效（点别处收起）
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                DayTip(date = c.date, answered = c.answered, correct = c.correct)
            }
        }
    }
}

/** 深色小窗 + 指向格子的三角；位置由 TooltipPositionProvider 决定，箭头恒朝下（浮在格子上方） */
@Composable
private fun DayTip(
    date: String,
    answered: Int,
    correct: Int,
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

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            // 三行分排而非拼成一行——拼一行时窗宽到能盖住五六列
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
        // 指向格子的三角：旋转 45° 的小方块，一半压在窗体上、一半露在外面
        Box(
            Modifier
                .offset(y = (-4).dp)
                .size(8.dp)
                .rotate(45f)
                .background(bg)
        )
    }
}
