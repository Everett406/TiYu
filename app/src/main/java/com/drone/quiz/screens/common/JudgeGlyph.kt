package com.drone.quiz.screens.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 判断题圆标：**自绘**的对勾 / 叉号（v2.19.16 提出，v2.19.36 提到此处共用）。
 *
 * 为什么不用字符 `√` / `×`：前者是根号而非对勾、后者字重随系统字体变，
 * 两个符号的视觉重量对不上。
 *
 * **为什么是填充多边形而不是描边折线**：v2.19.16 那一版用 `drawLine`/`drawPath`
 * 手搓折线，坐标凭感觉拍的，真机上是「极丑无比，而且还不居中」——
 * 折线的**几何包围盒居中 ≠ 视觉重心居中**：勾形的长臂墨量远大于短臂，
 * 整块墨的重心被拽到左下，看着就是歪的。描边还额外受 `stroke/2` 圆头外扩影响。
 *
 * 现按 24 单位视口的固定比例作图再等比缩放：勾的墨迹在 x∈[3.4,21]、y∈[5.6,19]，
 * 叉在 x,y∈[5,19]，两者都以视口中心对称，缩放后自然落在圆心，且笔画粗实、
 * 不受系统字重影响。
 *
 * 从 QuestionBody 提上来是因为搜索页需要同一套字形——此前它是各写各的，
 * 搜索页那份只补了字母的行盒居中，判断题仍走系统字符。
 */
@Composable
fun JudgeBadgeGlyph(
    isCheck: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.size(13.dp)) {
        val k = size.minDimension / 24f
        val path = Path()
        if (isCheck) {
            path.moveTo(9f * k, 16.2f * k)
            path.lineTo(4.8f * k, 12f * k)
            path.lineTo(3.4f * k, 13.4f * k)
            path.lineTo(9f * k, 19f * k)
            path.lineTo(21f * k, 7f * k)
            path.lineTo(19.6f * k, 5.6f * k)
            path.close()
        } else {
            path.moveTo(19f * k, 6.4f * k)
            path.lineTo(17.6f * k, 5f * k)
            path.lineTo(12f * k, 10.6f * k)
            path.lineTo(6.4f * k, 5f * k)
            path.lineTo(5f * k, 6.4f * k)
            path.lineTo(10.6f * k, 12f * k)
            path.lineTo(5f * k, 17.6f * k)
            path.lineTo(6.4f * k, 19f * k)
            path.lineTo(12f * k, 13.4f * k)
            path.lineTo(17.6f * k, 19f * k)
            path.lineTo(19f * k, 17.6f * k)
            path.lineTo(13.4f * k, 12f * k)
            path.close()
        }
        drawPath(path, color)
    }
}
