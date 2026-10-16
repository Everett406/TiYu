package com.drone.quiz.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.data.AppHistory
import com.drone.quiz.ui.glass.AppIcons
import com.drone.quiz.ui.glass.GlassCard
import com.drone.quiz.ui.glass.GlassIconButton
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.R
import com.kyant.backdrop.Backdrop
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
/**
 * 发展历程（v2.19.34）。
 *
 * 数据全部来自 [AppHistory]，那张表由 `scripts/gen_history.py` 从 git log 生成，
 * 所以这里不写任何"文案"——看到的就是真做过的。
 *
 * 视觉沿用现有的：玻璃卡 + 楷体（与关于页同源）+ 虚线分隔（参考用户旧刷题本排版）。
 * 时间轴的竖线 + 节点用 textSub 低透明度（v2.19.36 用户反馈：橙色节点太跳，
 * 整屏点状物压过了内容本身）。它是个导航结构，不该抢标题的视觉重量。
 * 回滚版本仍标红——那是有语义的信息，不是装饰。
 */
/** 文楷：与关于页同源，历程页的标题也走文档风 */
private val HistoryKai = FontFamily(
    Font(R.font.lxgwwenkai_regular, FontWeight.Normal),
    Font(R.font.lxgwwenkai_medium, FontWeight.Bold)
)

@Composable
fun HistoryScreen(backdrop: Backdrop, onBack: () -> Unit) {
    val ui = LocalUi.current
    val listState = rememberLazyListState()
    // 只展开当前选中项，滚动时自动收起——75 个版本全展开会把手机撑爆
    var expanded by remember { mutableStateOf<String?>(AppHistory.ALL.first().version) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to ui.bg.copy(alpha = 0.55f),
                    0.35f to ui.bg.copy(alpha = 0.85f),
                    1f to ui.bg
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(
                    onClick = onBack,
                    backdrop = backdrop,
                    icon = AppIcons.ChevronLeft,
                    sizeDp = 40.dp
                )
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(
                        "发展历程",
                        color = ui.text, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, fontFamily = HistoryKai
                    )
                    Text(
                        "${AppHistory.VERSION_COUNT} 个版本 · ${AppHistory.COMMIT_COUNT} 次提交 · " +
                            "${AppHistory.FIRST_DATE} 起 ${AppHistory.LAST_DATE} 止",
                        color = ui.textSub, fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp)
            ) {
                itemsIndexed(AppHistory.ALL, key = { _, it -> it.version }) { idx, e ->
                    HistoryRow(
                        backdrop = backdrop,
                        e = e,
                        expanded = expanded == e.version,
                        isLast = idx == AppHistory.ALL.lastIndex,
                        onToggle = { expanded = if (expanded == e.version) null else e.version }
                    )
                }
            }
        }
    }
}

/**
 * 时间轴几何（drawBehind 画线用，不参与测量）。
 *
 * 坐标都相对条目左上角：左列宽 [GUTTER]，节点在其中水平居中，
 * 节点顶距条目顶 [NODE_TOP]，条目间距 [ROW_GAP]（竖线要跨过它接上下一节点）。
 */
private val GUTTER = 22.dp
private val NODE_TOP = 22.dp
private val NODE_SIZE = 9.dp
/** 条目间距：卡片 padding(bottom)，竖线必须跨过它才能接到下一个节点 */
private val ROW_GAP = 10.dp

/** 单个版本条目：左侧竖线 + 节点，右侧玻璃卡 */
@Composable
private fun HistoryRow(
    backdrop: Backdrop,
    e: AppHistory.Entry,
    expanded: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit
) {
    val ui = LocalUi.current
    // v2.19.38 重写时间轴的画法。**前两版都是错的**，而且第二版还引入了新 bug：
    //
    // · v2.19.36 固定 24.dp → 线长与卡片高度脱钩，断成一段段；
    // · v2.19.37 改 IntrinsicSize.Min + weight → **与 AnimatedVisibility 冲突**：
    //   内在测量（IntrinsicSize）不跟随尺寸动画，所以收起展开的详情时，
    //   条目留出一大块空白、内容"啪"地跳回去，收起动画等于没有
    //   （用户反馈 v2.19.37 引入）。根子在于：用**测量**去驱动一条线。
    //
    // 现改为在 Row 的 drawBehind 里画线：Row 高度由卡片自然决定，
    // drawBehind 拿到的 size 就是真实布局高度；更关键的是它**不参与测量**，
    // AnimatedVisibility 的收放动画恢复正常。
    //
    // v2.19.46 修「线接不上」：v2.19.38 把线画到 `size.height + LINE_OVER`，
    // 而下一条目的线是从**它自己**的 `NODE_TOP + NODE_SIZE` 起算的。
    // 两者之间隔着整整一个 ROW_GAP(10dp) + NODE_TOP+NODE_SIZE(31dp)，
    // 于是每两个节点之间都断开约 21dp——截图上一段一段的就是这么来的。
    //
    // 现在线画「节点中心 → 下一个节点中心」，跨过间距，天然连续。
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (isLast) return@drawBehind
                val x = (GUTTER / 2).toPx()
                val nodeCY = (NODE_TOP + NODE_SIZE / 2).toPx()
                drawLine(
                    color = ui.textSub.copy(alpha = 0.24f),
                    // 从本节点圆心起，画到**下一个**条目的节点圆心：
                    // 终点 = 本条目高 + 条目间距 + 下一节点的圆心偏移
                    start = Offset(x, nodeCY),
                    end = Offset(x, size.height + ROW_GAP.toPx() + nodeCY),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
    ) {
        // ---- 只有节点，不再有竖线（竖线在 drawBehind 里） ----
        Column(
            Modifier.width(GUTTER),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(NODE_TOP))
            Box(
                Modifier
                    .size(NODE_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (e.reverted) ui.wrong else ui.textSub.copy(alpha = 0.55f)
                    )
            )
        }

        GlassCard(
            backdrop = backdrop,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 10.dp)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onToggle
                ),
            cornerRadius = 20.dp
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${e.version}",
                        color = ui.text, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (e.commits > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${e.commits} 次提交",
                            color = ui.textSub, fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (e.date == e.endDate) e.date else "${e.date} → ${e.endDate}",
                        color = ui.textSub, fontSize = 10.sp
                    )
                }
                Text(
                    e.title,
                    color = ui.text, fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (e.reverted) {
                    Text(
                        "含回滚",
                        color = ui.wrong, fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                // v2.19.36：展开加动画。此前是硬 if(expanded)，内容瞬间出现/消失，
                // 与错题本（WrongBookScreen 同款 expandVertically + fadeIn）手感不一致，
                // 同一屏里两套展开动效很扎眼。
                AnimatedVisibility(
                    visible = expanded && e.points.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        e.points.forEach { p ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text(
                                    "·",
                                    color = ui.textSub, fontSize = 12.sp,
                                    modifier = Modifier.width(12.dp)
                                )
                                Text(
                                    p,
                                    color = ui.textSub, fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
