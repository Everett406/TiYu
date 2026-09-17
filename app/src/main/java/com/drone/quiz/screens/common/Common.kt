package com.drone.quiz.screens.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.quiz.ServiceLocator
import com.drone.quiz.ui.theme.LocalUi
import com.drone.quiz.ui.theme.readableSubColor
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

/**
 * 分段选择器：槽内滑动的墨色胶囊。
 */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val ui = LocalUi.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(ui.ink.copy(alpha = if (ui.isDark) 0.22f else 0.07f))
    ) {
        val segWidth = maxWidth / options.size
        val pillX by animateDpAsState(
            targetValue = segWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 480f),
            label = "segPill"
        )
        Box(
            Modifier
                .offset(x = pillX + 3.dp, y = 3.dp)
                .size(width = segWidth - 6.dp, height = 34.dp)
                .clip(RoundedCornerShape(50))
                .background(ui.ink)
        )
        Row(Modifier.height(40.dp)) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (i == selectedIndex) ui.onInk else ui.textSub,
                        fontSize = 13.sp,
                        fontWeight = if (i == selectedIndex) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 小标签（类别/题型）。
 */
@Composable
fun TagChip(text: String, color: Color = LocalUi.current.textSub, outline: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (outline) Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
                else Modifier.background(color.copy(alpha = 0.12f))
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 分类标签文案（v2.9.2）：导入时无分类的题落库为「未分类」，展示时回退为题库名称——
 * 卡片上应显示题目来自哪个题库，而不是干巴巴的「未分类」。
 */
fun displayCategory(category: String?, bankName: String?): String {
    val c = category?.takeIf { it.isNotBlank() }
    return if (c == null || c == "未分类") {
        bankName?.takeIf { it.isNotBlank() } ?: "未分类"
    } else c
}

/**
 * 题库名称查询（remember 缓存，bankId 变化自动重查；失败返回 null 回退「未分类」）。
 * 配合 [displayCategory] 使用：TagChip(displayCategory(q.category, rememberBankName(q.bankId)))。
 */
@Composable
fun rememberBankName(bankId: String?): String? {
    var name by remember(bankId) { mutableStateOf<String?>(null) }
    LaunchedEffect(bankId) {
        if (bankId != null) {
            name = runCatching { ServiceLocator.repo.bankNameOf(bankId) }.getOrNull()
        }
    }
    return name
}

/**
 * 跨屏前台信号（轻量全局态，v2.9.2）：打赏弹窗门槛计时只统计真实刷题时长——
 * 刷题答题页（PracticeRunScreen）组合中才置 true，MainActivity 的计时器按此门控累计。
 */
object UsageSignals {
    var onPracticeScreen by mutableStateOf(false)
}

/**
 * 卡片区块标题（直接坐在壁纸上；v2.8.7 改自适应色——壁纸翻暗提亮/翻亮压深，修用户圈出的"看不清"）。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp),
        color = readableSubColor(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * 列表/网格/滚动容器"顶部已滚出的像素"（供柔化连续渐显；
 * 在 draw 阶段读取，状态变化只触发重绘，零重组、零 Modifier 重建）。
 */
fun androidx.compose.foundation.lazy.LazyListState.scrolledFromTopPx(): Float =
    if (firstVisibleItemIndex > 0) Float.MAX_VALUE * 0.5f else firstVisibleItemScrollOffset.toFloat()

fun androidx.compose.foundation.lazy.grid.LazyGridState.scrolledFromTopPx(): Float =
    if (firstVisibleItemIndex > 0) Float.MAX_VALUE * 0.5f else firstVisibleItemScrollOffset.toFloat()

fun androidx.compose.foundation.ScrollState.scrolledFromTopPx(): Float = value.toFloat()

/**
 * 网格"距底部剩余像素"近似（未显示 item 数 × 平均可视行高 + 尾部残量），
 * 用于底部柔化连续渐显。
 */
fun androidx.compose.foundation.lazy.grid.LazyGridState.remainingBottomPx(): Float {
    val info = layoutInfo
    val infos = info.visibleItemsInfo
    if (infos.isEmpty()) return 0f
    val last = infos.last()
    if (last.index >= info.totalItemsCount - 1) return 0f
    val lastBottom = last.offset.y + last.size.height
    val tail = (info.viewportEndOffset - lastBottom).coerceAtLeast(0)
    val avg = infos.map { it.size.height }.average().toFloat().coerceAtLeast(1f)
    return tail + (info.totalItemsCount - 1 - last.index) * avg
}

/** 列表"距底部剩余像素"近似（同网格版，答题卡 v2.8.3 改 LazyColumn 分段后需要）。 */
fun androidx.compose.foundation.lazy.LazyListState.remainingBottomPx(): Float {
    val info = layoutInfo
    val infos = info.visibleItemsInfo
    if (infos.isEmpty()) return 0f
    val last = infos.last()
    if (last.index >= info.totalItemsCount - 1) return 0f
    val lastBottom = last.offset + last.size
    val tail = (info.viewportEndOffset - lastBottom).coerceAtLeast(0)
    val avg = infos.map { it.size }.average().toFloat().coerceAtLeast(1f)
    return tail + (info.totalItemsCount - 1 - last.index) * avg
}

/**
 * 上下双向柔化（答题卡网格上下边缘渐隐，替代硬切行）。
 * v2.7.1 同步生长式模型：带高 = min(滚出/剩余像素, 带高上限)，
 * 内容"滚到哪里淡到哪里"，未达边缘的区域永不变淡；曲线 smoothstep。
 */
fun Modifier.softVerticalEdges(
    top: Dp = 20.dp,
    bottom: Dp = 24.dp,
    topScrolledPx: () -> Float = { Float.MAX_VALUE * 0.5f },
    bottomRemainingPx: () -> Float = { Float.MAX_VALUE * 0.5f }
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val th = top.toPx()
        val bh = bottom.toPx()
        val te = topScrolledPx().coerceAtLeast(0f).coerceAtMost(th)
        val be = bottomRemainingPx().coerceAtLeast(0f).coerceAtMost(bh)
        if (size.height > th + bh) {
            if (th > 0f && te > 0.5f) {
                drawRect(
                    brush = fadeMaskBrush(),
                    topLeft = Offset.Zero,
                    size = Size(size.width, te),
                    blendMode = BlendMode.DstIn
                )
            }
            if (bh > 0f && be > 0.5f) {
                drawRect(
                    brush = fadeMaskBrush(),
                    topLeft = Offset(0f, size.height - be),
                    size = Size(size.width, be),
                    blendMode = BlendMode.DstIn
                )
            }
        }
    }

/**
 * 边缘淡出曲线：smoothstep 五采样（两端平缓、中段顺滑）。
 * 线性渐变的"被幕布切"观感主要来自曲线两端斜率突变，smoothstep 消除之。
 */
private fun fadeMaskBrush(): Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0f),
    0.25f to Color.Black.copy(alpha = 0.16f),
    0.5f to Color.Black.copy(alpha = 0.5f),
    0.75f to Color.Black.copy(alpha = 0.84f),
    1f to Color.Black.copy(alpha = 1f)
)

/**
 * 屏幕大标题（每页最顶部）。
 */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    Column(modifier = modifier) {
        Text(
            title,
            color = ui.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp
        )
        if (subtitle != null) {
            Text(
                subtitle,
                // 页面副标题同样裸坐在壁纸上，v2.8.7 同步自适应（用户圈出"选择范围…""外观/刷题/数据"）
                color = readableSubColor(),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}


/** Hero 动画基础设施（搜索框 → 搜索页共享元素转场）。 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 搜索框共享元素（Hero）：刷题页入口与搜索页输入框同 key，
 * 导航转场时两框之间平滑飞行衔接。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.heroSearchField(): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@heroSearchField.sharedElement(
            rememberSharedContentState(key = "search-field"),
            animatedVisibilityScope = anim
        )
    }
}
