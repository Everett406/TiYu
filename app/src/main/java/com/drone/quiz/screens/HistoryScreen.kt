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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
    // v2.19.37 修「时间轴断成一段段」。
    //
    // 病灶：v2.19.36 把竖线从 9999.dp 改成了固定 24.dp（当时为了不让末条拖长尾），
    // 结果**线长与卡片高度彻底脱钩**。真机截图实测：每条线只有 24~32px，
    // 而条目间距 361~363px——线只占 8%，剩下 92% 全是空白。于是整条轴
    // 看着是一串棒棒糖，而不是一根时间轴。
    //
    // 修法：外层 Row 用 IntrinsicSize.Min 让左右两列取齐高（左列内容少、
    // 右列卡片高），左列 fillMaxHeight，竖线 weight(1f) 吃掉剩余空间——
    // 线长自动等于「本条卡片高度 − 节点偏移」，与标题几行无关。
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // ---- 时间轴轴线与节点 ----
        Column(
            Modifier
                .width(22.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .padding(top = 22.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (e.reverted) ui.wrong else ui.textSub.copy(alpha = 0.55f)
                    )
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.5.dp)
                        .weight(1f)
                        .background(ui.textSub.copy(alpha = 0.20f))
                )
                // 线要略微探出卡片底部（v2.19.37）：Row 高度只到卡片底，
                // 若线正好齐平卡片底，视觉上像"被卡片截断"；探出去 10dp
                // 才读得出"这条线还要连到下一个节点"。
                Spacer(Modifier.height(10.dp))
            } else {
                // 末条不画线：空占位保底，保证节点仍与上方节点同一水平线
                Spacer(Modifier.weight(1f))
            }
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
