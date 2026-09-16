package com.drone.quiz.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 描边风格（箭头/细线类图标保留）。 */
private fun icon(
    name: String,
    size: Dp = 24.dp,
    viewport: Float = 24f,
    block: PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size,
    defaultHeight = size,
    viewportWidth = viewport,
    viewportHeight = viewport
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color(0xFF000000)),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) { block() }
}.build()

/** 填充风格（v2.6.0：Tab 栏与常用图标改为填充式，穿透感更强）。 */
private fun filledIcon(
    name: String,
    size: Dp = 24.dp,
    viewport: Float = 24f,
    evenOdd: Boolean = false,
    block: PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size,
    defaultHeight = size,
    viewportWidth = viewport,
    viewportHeight = viewport
).apply {
    path(
        fill = SolidColor(Color(0xFF000000)),
        fillAlpha = 1f,
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        stroke = null
    ) { block() }
}.build()

// ---------- 底栏图标（填充风格） ----------

object AppIcons {

    /** 首页：填充小屋 + 门洞（EvenOdd 挖空）。 */
    val Home: ImageVector by lazy {
        filledIcon("AppHome", evenOdd = true) {
            // 屋身一体轮廓
            moveTo(12f, 2.8f)
            lineTo(21f, 10.2f)
            lineTo(21f, 19.2f)
            curveTo(21f, 20.2f, 20.2f, 21f, 19.2f, 21f)
            lineTo(4.8f, 21f)
            curveTo(3.8f, 21f, 3f, 20.2f, 3f, 19.2f)
            lineTo(3f, 10.2f)
            close()
            // 门洞
            moveTo(10.1f, 21f)
            lineTo(10.1f, 14.4f)
            curveTo(10.1f, 13.7f, 10.6f, 13.2f, 11.3f, 13.2f)
            lineTo(12.7f, 13.2f)
            curveTo(13.4f, 13.2f, 13.9f, 13.7f, 13.9f, 14.4f)
            lineTo(13.9f, 21f)
            close()
        }
    }

    /** 刷题：填充卡片 + 对勾挖空（与错题本"方块+X"成对，答对语义；去掉了旧版怪异的后卡帽）。 */
    val Cards: ImageVector by lazy {
        filledIcon("AppCards", evenOdd = true) {
            // 卡片主体（与 BookWrong 同一几何，保证 Tab 栏视觉一致）
            moveTo(5.6f, 3.4f)
            lineTo(18.4f, 3.4f)
            curveTo(19.5f, 3.4f, 20.4f, 4.3f, 20.4f, 5.4f)
            lineTo(20.4f, 19f)
            curveTo(20.4f, 20.1f, 19.5f, 21f, 18.4f, 21f)
            lineTo(5.6f, 21f)
            curveTo(4.5f, 21f, 3.6f, 20.1f, 3.6f, 19f)
            lineTo(3.6f, 5.4f)
            curveTo(3.6f, 4.3f, 4.5f, 3.4f, 5.6f, 3.4f)
            close()
            // 对勾（挖空，居中）
            moveTo(7.2f, 12.2f)
            lineTo(9.2f, 10.2f)
            lineTo(11.5f, 12.5f)
            lineTo(14.9f, 9.1f)
            lineTo(16.9f, 11.1f)
            lineTo(11.5f, 16.5f)
            close()
        }
    }

    /** 模考：填充秒表 + 镂空指针。 */
    val Timer: ImageVector by lazy {
        filledIcon("AppTimer", evenOdd = true) {
            // 顶部按钮与柄
            moveTo(9.2f, 2f)
            lineTo(14.8f, 2f)
            curveTo(15.3f, 2f, 15.7f, 2.4f, 15.7f, 2.9f)
            curveTo(15.7f, 3.4f, 15.3f, 3.8f, 14.8f, 3.8f)
            lineTo(13f, 3.8f)
            lineTo(13f, 5f)
            lineTo(11f, 5f)
            lineTo(11f, 3.8f)
            lineTo(9.2f, 3.8f)
            curveTo(8.7f, 3.8f, 8.3f, 3.4f, 8.3f, 2.9f)
            curveTo(8.3f, 2.4f, 8.7f, 2f, 9.2f, 2f)
            close()
            // 表体
            moveTo(12f, 21.8f)
            curveTo(7.2f, 21.8f, 3.3f, 17.9f, 3.3f, 13.1f)
            curveTo(3.3f, 8.3f, 7.2f, 4.4f, 12f, 4.4f)
            curveTo(16.8f, 4.4f, 20.7f, 8.3f, 20.7f, 13.1f)
            curveTo(20.7f, 17.9f, 16.8f, 21.8f, 12f, 21.8f)
            close()
            // 指针（挖空，竖针 + 斜针连成一体）
            moveTo(11.1f, 13.1f)
            lineTo(11.1f, 8.6f)
            lineTo(12.9f, 8.6f)
            lineTo(12.9f, 12.2f)
            lineTo(15.4f, 13.7f)
            lineTo(14.5f, 15.3f)
            lineTo(11.1f, 13.3f)
            close()
        }
    }

    /** 错题本：填充书 + X 挖空。 */
    val BookWrong: ImageVector by lazy {
        filledIcon("AppBookWrong", evenOdd = true) {
            moveTo(5.6f, 3.4f)
            lineTo(18.4f, 3.4f)
            curveTo(19.5f, 3.4f, 20.4f, 4.3f, 20.4f, 5.4f)
            lineTo(20.4f, 19f)
            curveTo(20.4f, 20.1f, 19.5f, 21f, 18.4f, 21f)
            lineTo(5.6f, 21f)
            curveTo(4.5f, 21f, 3.6f, 20.1f, 3.6f, 19f)
            lineTo(3.6f, 5.4f)
            curveTo(3.6f, 4.3f, 4.5f, 3.4f, 5.6f, 3.4f)
            close()
            // X（挖空，12 点多边形）
            moveTo(8.6f, 7.2f)
            lineTo(12f, 10.6f)
            lineTo(15.4f, 7.2f)
            lineTo(16.8f, 8.6f)
            lineTo(13.4f, 12f)
            lineTo(16.8f, 15.4f)
            lineTo(15.4f, 16.8f)
            lineTo(12f, 13.4f)
            lineTo(8.6f, 16.8f)
            lineTo(7.2f, 15.4f)
            lineTo(10.6f, 12f)
            lineTo(7.2f, 8.6f)
            close()
        }
    }

    /** 设置：三条填充滑轨 + 圆形滑钮。 */
    val Tune: ImageVector by lazy {
        filledIcon("AppTune") {
            fun bar(y: Float) {
                moveTo(3.4f, y - 1.1f)
                lineTo(20.6f, y - 1.1f)
                curveTo(21.2f, y - 1.1f, 21.7f, y - 0.6f, 21.7f, y)
                curveTo(21.7f, y + 0.6f, 21.2f, y + 1.1f, 20.6f, y + 1.1f)
                lineTo(3.4f, y + 1.1f)
                curveTo(2.8f, y + 1.1f, 2.3f, y + 0.6f, 2.3f, y)
                curveTo(2.3f, y - 0.6f, 2.8f, y - 1.1f, 3.4f, y - 1.1f)
                close()
            }
            fun knob(x: Float, y: Float) {
                moveTo(x, y - 2.5f)
                curveTo(x + 1.4f, y - 2.5f, x + 2.5f, y - 1.4f, x + 2.5f, y)
                curveTo(x + 2.5f, y + 1.4f, x + 1.4f, y + 2.5f, x, y + 2.5f)
                curveTo(x - 1.4f, y + 2.5f, x - 2.5f, y + 1.4f, x - 2.5f, y)
                curveTo(x - 2.5f, y - 1.4f, x - 1.4f, y - 2.5f, x, y - 2.5f)
                close()
            }
            bar(6.5f); knob(15.2f, 6.5f)
            bar(12f); knob(8.4f, 12f)
            bar(17.5f); knob(12.6f, 17.5f)
        }
    }

    // ---------- 通用 ----------

    /** 答题卡入口：四格填充。 */
    val Grid: ImageVector by lazy {
        filledIcon("AppGrid") {
            fun cell(x: Float, y: Float) {
                moveTo(x + 1.3f, y)
                lineTo(x + 4.7f, y)
                curveTo(x + 5.5f, y, x + 6f, y + 0.5f, x + 6f, y + 1.3f)
                lineTo(x + 6f, y + 4.7f)
                curveTo(x + 6f, y + 5.5f, x + 5.5f, y + 6f, x + 4.7f, y + 6f)
                lineTo(x + 1.3f, y + 6f)
                curveTo(x + 0.5f, y + 6f, x, y + 5.5f, x, y + 4.7f)
                lineTo(x, y + 1.3f)
                curveTo(x, y + 0.5f, x + 0.5f, y, x + 1.3f, y)
                close()
            }
            cell(3.2f, 3.2f); cell(14.8f, 3.2f)
            cell(3.2f, 14.8f); cell(14.8f, 14.8f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("AppChevronRight") {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        icon("AppChevronLeft") {
            moveTo(15f, 5f)
            lineTo(8f, 12f)
            lineTo(15f, 19f)
        }
    }

    /** 对勾（填充粗笔）。 */
    val Check: ImageVector by lazy {
        filledIcon("AppCheck") {
            moveTo(9.9f, 18.1f)
            lineTo(4.5f, 12.7f)
            lineTo(6f, 11.2f)
            lineTo(9.9f, 15.1f)
            lineTo(18f, 6.2f)
            lineTo(19.5f, 7.6f)
            close()
        }
    }

    /** 关闭（填充粗叉）。 */
    val Close: ImageVector by lazy {
        filledIcon("AppClose") {
            moveTo(6.4f, 4.6f)
            lineTo(12f, 10.2f)
            lineTo(17.6f, 4.6f)
            lineTo(19.4f, 6.4f)
            lineTo(13.8f, 12f)
            lineTo(19.4f, 17.6f)
            lineTo(17.6f, 19.4f)
            lineTo(12f, 13.8f)
            lineTo(6.4f, 19.4f)
            lineTo(4.6f, 17.6f)
            lineTo(10.2f, 12f)
            lineTo(4.6f, 6.4f)
            close()
        }
    }

    /** 火焰（外焰填充 + 内焰挖空）。 */
    val Flame: ImageVector by lazy {
        filledIcon("AppFlame", evenOdd = true) {
            moveTo(12f, 2.4f)
            curveTo(12f, 2.4f, 18.6f, 9.6f, 18.6f, 14.8f)
            curveTo(18.6f, 18.6f, 15.7f, 21.5f, 12f, 21.5f)
            curveTo(8.3f, 21.5f, 5.4f, 18.6f, 5.4f, 14.8f)
            curveTo(5.4f, 9.6f, 12f, 2.4f, 12f, 2.4f)
            close()
            moveTo(12f, 19.9f)
            curveTo(10.1f, 19.9f, 8.7f, 18.5f, 8.7f, 16.6f)
            curveTo(8.7f, 14.2f, 12f, 11.2f, 12f, 11.2f)
            curveTo(12f, 11.2f, 15.3f, 14.2f, 15.3f, 16.6f)
            curveTo(15.3f, 18.5f, 13.9f, 19.9f, 12f, 19.9f)
            close()
        }
    }

    /** 播放（填充三角）。 */
    val Play: ImageVector by lazy {
        filledIcon("AppPlay") {
            moveTo(8.2f, 5.4f)
            lineTo(18.4f, 12f)
            lineTo(8.2f, 18.6f)
            close()
        }
    }

    /** 通知铃（填充铃身 + 铃锤）。 */
    val Bell: ImageVector by lazy {
        filledIcon("AppBell") {
            moveTo(12f, 2.9f)
            curveTo(9.2f, 2.9f, 7f, 5.3f, 7f, 8.3f)
            lineTo(7f, 12.8f)
            lineTo(5.6f, 15.6f)
            curveTo(5.3f, 16.1f, 5.7f, 16.7f, 6.3f, 16.7f)
            lineTo(17.7f, 16.7f)
            curveTo(18.3f, 16.7f, 18.7f, 16.1f, 18.4f, 15.6f)
            lineTo(17f, 12.8f)
            lineTo(17f, 8.3f)
            curveTo(17f, 5.3f, 14.8f, 2.9f, 12f, 2.9f)
            close()
            moveTo(9.9f, 18.9f)
            lineTo(14.1f, 18.9f)
            curveTo(13.8f, 20.1f, 13f, 21.1f, 12f, 21.1f)
            curveTo(11f, 21.1f, 10.2f, 20.1f, 9.9f, 18.9f)
            close()
        }
    }

    /** 导入题库（填充下载箭头 + 托盘）。 */
    val Import: ImageVector by lazy {
        filledIcon("AppImport") {
            moveTo(11f, 3.2f)
            lineTo(13f, 3.2f)
            lineTo(13f, 10.6f)
            lineTo(16f, 7.6f)
            lineTo(17.4f, 9f)
            lineTo(12f, 14.4f)
            lineTo(6.6f, 9f)
            lineTo(8f, 7.6f)
            lineTo(11f, 10.6f)
            close()
            moveTo(6f, 15.8f)
            lineTo(8f, 15.8f)
            lineTo(8f, 18.2f)
            lineTo(16f, 18.2f)
            lineTo(16f, 15.8f)
            lineTo(18f, 15.8f)
            curveTo(18.8f, 15.8f, 19.5f, 16.5f, 19.5f, 17.3f)
            lineTo(19.5f, 19f)
            curveTo(19.5f, 19.8f, 18.8f, 20.5f, 18f, 20.5f)
            lineTo(6f, 20.5f)
            curveTo(5.2f, 20.5f, 4.5f, 19.8f, 4.5f, 19f)
            lineTo(4.5f, 17.3f)
            curveTo(4.5f, 16.5f, 5.2f, 15.8f, 6f, 15.8f)
            close()
        }
    }

    /** 重命名（填充铅笔，v2.8.7 题库重命名入口）。 */
    val Edit: ImageVector by lazy {
        filledIcon("AppEdit") {
            // 笔杆（斜置圆角矩形，笔尖朝左下）
            moveTo(14.1f, 4.4f)
            lineTo(19.6f, 9.9f)
            lineTo(9.3f, 20.2f)
            lineTo(4.4f, 20.6f)
            curveTo(3.8f, 20.65f, 3.35f, 20.2f, 3.4f, 19.6f)
            lineTo(3.8f, 14.7f)
            close()
            // 笔尾橡皮头（独立小块，与笔杆留缝）
            moveTo(15.6f, 2.9f)
            lineTo(17.1f, 1.4f)
            curveTo(17.5f, 1f, 18.1f, 1f, 18.5f, 1.4f)
            lineTo(22.6f, 5.5f)
            curveTo(23f, 5.9f, 23f, 6.5f, 22.6f, 6.9f)
            lineTo(21.1f, 8.4f)
            close()
        }
    }

    /** 删除（填充垃圾桶 + 镂空条纹）。 */
    val Trash: ImageVector by lazy {
        filledIcon("AppTrash", evenOdd = true) {
            // 盖 + 提手
            moveTo(9f, 3.4f)
            lineTo(15f, 3.4f)
            curveTo(15.6f, 3.4f, 16f, 3.8f, 16f, 4.4f)
            lineTo(16f, 5.2f)
            lineTo(19.4f, 5.2f)
            curveTo(20f, 5.2f, 20.4f, 5.6f, 20.4f, 6.2f)
            curveTo(20.4f, 6.8f, 20f, 7.2f, 19.4f, 7.2f)
            lineTo(4.6f, 7.2f)
            curveTo(4f, 7.2f, 3.6f, 6.8f, 3.6f, 6.2f)
            curveTo(3.6f, 5.6f, 4f, 5.2f, 4.6f, 5.2f)
            lineTo(8f, 5.2f)
            lineTo(8f, 4.4f)
            curveTo(8f, 3.8f, 8.4f, 3.4f, 9f, 3.4f)
            close()
            // 桶身
            moveTo(6.6f, 8.6f)
            lineTo(17.4f, 8.6f)
            curveTo(18f, 8.6f, 18.4f, 9.1f, 18.4f, 9.7f)
            lineTo(17.7f, 19.2f)
            curveTo(17.6f, 20.2f, 16.8f, 21f, 15.8f, 21f)
            lineTo(8.2f, 21f)
            curveTo(7.2f, 21f, 6.4f, 20.2f, 6.3f, 19.2f)
            lineTo(5.6f, 9.7f)
            curveTo(5.6f, 9.1f, 6f, 8.6f, 6.6f, 8.6f)
            close()
            // 两条镂空条纹
            moveTo(10.2f, 10.6f)
            lineTo(11.2f, 10.6f)
            lineTo(11.2f, 18f)
            lineTo(10.2f, 18f)
            close()
            moveTo(12.8f, 10.6f)
            lineTo(13.8f, 10.6f)
            lineTo(13.8f, 18f)
            lineTo(12.8f, 18f)
            close()
        }
    }

    val Refresh: ImageVector by lazy {
        icon("AppRefresh") {
            moveTo(20f, 12f)
            curveTo(20f, 7.6f, 16.4f, 4f, 12f, 4f)
            curveTo(8.9f, 4f, 6.2f, 5.8f, 4.9f, 8.4f)
            moveTo(4f, 12f)
            curveTo(4f, 16.4f, 7.6f, 20f, 12f, 20f)
            curveTo(15.1f, 20f, 17.8f, 18.2f, 19.1f, 15.6f)
            moveTo(4.9f, 4.5f)
            lineTo(4.9f, 8.9f)
            lineTo(9.3f, 8.9f)
            moveTo(19.1f, 19.5f)
            lineTo(19.1f, 15.1f)
            lineTo(14.7f, 15.1f)
        }
    }

    /** 更多（填充圆点）。 */
    val Dots: ImageVector by lazy {
        filledIcon("AppDots") {
            fun dot(x: Float) {
                moveTo(x, 9.8f)
                curveTo(x + 1.2f, 9.8f, x + 2.2f, 10.8f, x + 2.2f, 12f)
                curveTo(x + 2.2f, 13.2f, x + 1.2f, 14.2f, x, 14.2f)
                curveTo(x - 1.2f, 14.2f, x - 2.2f, 13.2f, x - 2.2f, 12f)
                curveTo(x - 2.2f, 10.8f, x - 1.2f, 9.8f, x, 9.8f)
                close()
            }
            dot(5.5f); dot(12f); dot(18.5f)
        }
    }

    /** 筛选（填充漏斗）。 */
    val Filter: ImageVector by lazy {
        filledIcon("AppFilter") {
            moveTo(4.4f, 4.6f)
            lineTo(19.6f, 4.6f)
            curveTo(20.4f, 4.6f, 20.8f, 5.5f, 20.3f, 6.1f)
            lineTo(14.6f, 12.6f)
            lineTo(14.6f, 17.8f)
            curveTo(14.6f, 18.3f, 14.3f, 18.7f, 13.9f, 18.9f)
            lineTo(10.9f, 20.3f)
            curveTo(10.3f, 20.6f, 9.6f, 20.2f, 9.6f, 19.5f)
            lineTo(9.6f, 12.6f)
            lineTo(3.7f, 6.1f)
            curveTo(3.2f, 5.5f, 3.6f, 4.6f, 4.4f, 4.6f)
            close()
        }
    }

    /** 搜索（填充放大镜：圆环 + 柄）。 */
    val Search: ImageVector by lazy {
        filledIcon("AppSearch", evenOdd = true) {
            // 圆环（外圆 + 内圆挖空）
            moveTo(10.5f, 3.4f)
            curveTo(6.6f, 3.4f, 3.4f, 6.6f, 3.4f, 10.5f)
            curveTo(3.4f, 14.4f, 6.6f, 17.6f, 10.5f, 17.6f)
            curveTo(14.4f, 17.6f, 17.6f, 14.4f, 17.6f, 10.5f)
            curveTo(17.6f, 6.6f, 14.4f, 3.4f, 10.5f, 3.4f)
            close()
            moveTo(10.5f, 5.6f)
            curveTo(13.2f, 5.6f, 15.4f, 7.8f, 15.4f, 10.5f)
            curveTo(15.4f, 13.2f, 13.2f, 15.4f, 10.5f, 15.4f)
            curveTo(7.8f, 15.4f, 5.6f, 13.2f, 5.6f, 10.5f)
            curveTo(5.6f, 7.8f, 7.8f, 5.6f, 10.5f, 5.6f)
            close()
            // 柄
            moveTo(15.1f, 13.7f)
            lineTo(20.1f, 18.7f)
            curveTo(20.6f, 19.2f, 20.6f, 19.9f, 20.1f, 20.3f)
            curveTo(19.7f, 20.8f, 18.9f, 20.8f, 18.5f, 20.3f)
            lineTo(13.5f, 15.3f)
            close()
        }
    }

    /** 相机（v2.12.0 拍照搜题）：机身 + 镜头圆环（EvenOdd 挖空），Material camera_alt 口径。 */
    val Camera: ImageVector by lazy {
        filledIcon("AppCamera", evenOdd = true) {
            // 机身（含顶部凸起的快门舱）
            moveTo(9f, 2f)
            lineTo(7.17f, 4f)
            lineTo(4f, 4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            lineTo(20f, 20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            lineTo(22f, 6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            lineTo(16.83f, 4f)
            lineTo(15f, 2f)
            close()
            // 镜头外圈
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
            // 镜头内圈（挖空）
            moveTo(12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            close()
        }
    }
}
