package com.drone.quiz.screens

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
import androidx.compose.foundation.lazy.items
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
 * 时间轴的竖线 + 节点用 ui.accent 描边，与题卡里的"正确答案"绿同源，
 * 但这里只描边不填充——它是个导航结构，不是答案语义。
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
                items(AppHistory.ALL, key = { it.version }) { e ->
                    HistoryRow(
                        backdrop = backdrop,
                        e = e,
                        expanded = expanded == e.version,
                        onToggle = { expanded = if (expanded == e.version) null else e.version }
                    )
                }
            }
        }
    }
}

/** 单个版本条目：左侧竖线 + 节点，右侧玻璃卡 */
@Composable
private fun HistoryRow(
    backdrop: Backdrop,
    e: AppHistory.Entry,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth()) {
        // ---- 时间轴轴线与节点 ----
        Column(
            Modifier.width(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .padding(top = 22.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (e.reverted) ui.wrong else ui.accent
                    )
            )
            // 竖线只在条目下方延伸一小段：靠卡片间距自然连成轴线，
            // 不用 9999.dp 撑满（那会让最后一个条目下方拖一条长尾）
            Box(
                Modifier
                    .width(1.5.dp)
                    .height(24.dp)
                    .background(ui.textSub.copy(alpha = 0.22f))
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
                if (expanded && e.points.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    e.points.forEach { p ->
                        Row(Modifier.padding(top = 4.dp)) {
                            Text(
                                "·",
                                color = ui.accent, fontSize = 12.sp,
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
