package com.drone.quiz.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.drone.quiz.R
import kotlin.math.abs

/**
 * 成绩分享卡渲染器（v2.10.0）：1080×1440 固定画布，纯 Canvas 绘制，零第三方依赖。
 * 预览直接展示渲染出的位图 = 最终分享产物（所见即所得，双实现零漂移）。
 *
 * 7 套主题预设：日落/极光/薄荷三组渐变 + 液态玻璃 + 纸感白 + 墨黑 + 深色金辉。
 * 字体全部用应用内置字体（Noto Sans SC / Noto Serif SC / 霞鹜文楷），无系统字体回退差异。
 * 强调色可微调（accentHex），作用于成绩下划线条、品牌圆点等点缀元素。
 */
object ShareCardRenderer {

    const val CARD_W = 1080
    const val CARD_H = 1440

    /** 成绩卡数据（全部为展示态字符串/整数，由调用方组装） */
    data class CardData(
        val dateText: String,
        val scoreText: String,
        val passed: Boolean,
        val passLine: Int,
        val accuracyPct: Int,
        val durationText: String,
        val correctText: String,
        val delta: Int?,          // 与上次同库模考分差（null = 首考/无对比）
        val attemptText: String?, // 如「本库第 3 次模考」（null 不显示）
        val streakDays: Int,
        val totalAnswered: Int,
        val bankName: String,
        val name: String,         // 署名（空 = 不显示）
        val slogan: String,       // 自定义标语（空 = 不显示）
        val themeId: String,
        val accentHex: String     // 空 = 主题默认强调色
    )

    /** 主题预设（label 供选择器展示） */
    class Theme(
        val id: String,
        val label: String,
        val ink: Int,
        val sub: Int,
        val hair: Int,
        val accent: Int,
        val serifNumber: Boolean,
        val kaiBody: Boolean,
        val paint: (Canvas) -> Unit
    )

    fun themes(): List<Pair<String, String>> = listOf(
        "sunset" to "日落", "aurora" to "极光", "mint" to "薄荷",
        "glass" to "液态玻璃", "paper" to "纸感白", "ink" to "墨黑", "gold" to "深色金辉"
    )

    fun themeOf(id: String): Theme = when (id) {
        "aurora" -> Theme(
            "aurora", "极光",
            ink = Color.WHITE, sub = 0xE6FFFFFF.toInt(), hair = 0x59FFFFFF,
            accent = 0xFFFFE28A.toInt(), serifNumber = false, kaiBody = false,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, CARD_W * 0.4f, CARD_H.toFloat(),
                        intArrayOf(0xFF4C6FE8.toInt(), 0xFF8A5BE8.toInt(), 0xFFB15CE0.toInt()),
                        null, Shader.TileMode.CLAMP
                    )
                })
            }
        )
        "mint" -> Theme(
            "mint", "薄荷",
            ink = 0xFF0B3B2E.toInt(), sub = 0xB30B3B2E.toInt(), hair = 0x400B3B2E.toInt(),
            accent = 0xFF0B6B4F.toInt(), serifNumber = false, kaiBody = false,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, CARD_H.toFloat(),
                        intArrayOf(0xFF3ECF8E.toInt(), 0xFF52E0C4.toInt(), 0xFF83EBD3.toInt()),
                        null, Shader.TileMode.CLAMP
                    )
                })
            }
        )
        "glass" -> Theme(
            "glass", "液态玻璃",
            ink = 0xFF23292F.toInt(), sub = 0xFF6B7480.toInt(), hair = 0xFFD7DDE4.toInt(),
            accent = 0xFF3D7BF5.toInt(), serifNumber = false, kaiBody = true,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply { color = 0xFFEFF3F7.toInt() })
                blob(c, 140f, 200f, 430f, 0x997FB2F0.toInt())
                blob(c, CARD_W - 60f, 430f, 390f, 0x8CF5A68C.toInt())
                blob(c, CARD_W * 0.22f, CARD_H - 220f, 470f, 0x739B7BF0.toInt())
                blob(c, CARD_W - 180f, CARD_H - 340f, 360f, 0x66F2D98C.toInt())
                c.drawRoundRect(
                    RectF(20f, 20f, CARD_W - 20f, CARD_H - 20f), 52f, 52f,
                    Paint().apply {
                        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xC8FFFFFF.toInt()
                    }
                )
            }
        )
        "paper" -> Theme(
            "paper", "纸感白",
            ink = 0xFF17171B.toInt(), sub = 0xFF7A756A.toInt(), hair = 0xFFDCD6C8.toInt(),
            accent = 0xFFC0392B.toInt(), serifNumber = true, kaiBody = false,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply { color = 0xFFFAF7F0.toInt() })
            }
        )
        "ink" -> Theme(
            "ink", "墨黑",
            ink = 0xFFF2EFE6.toInt(), sub = 0xFF8F8C82.toInt(), hair = 0xFF2E2E2E.toInt(),
            accent = 0xFFF2EFE6.toInt(), serifNumber = true, kaiBody = false,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply { color = 0xFF131313.toInt() })
            }
        )
        "gold" -> Theme(
            "gold", "深色金辉",
            ink = 0xFFF5EFD9.toInt(), sub = 0xFF9AA7BD.toInt(), hair = 0xFF24354F.toInt(),
            accent = 0xFFD9B24C.toInt(), serifNumber = false, kaiBody = false,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply { color = 0xFF0D1A2E.toInt() })
                c.drawRoundRect(
                    RectF(26f, 26f, CARD_W - 26f, CARD_H - 26f), 48f, 48f,
                    Paint().apply {
                        style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xE6D9B24C.toInt()
                    }
                )
                c.drawRoundRect(
                    RectF(42f, 42f, CARD_W - 42f, CARD_H - 42f), 40f, 40f,
                    Paint().apply {
                        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x78D9B24C
                    }
                )
            }
        )
        // 默认：日落
        else -> Theme(
            "sunset", "日落",
            ink = Color.WHITE, sub = 0xE6FFFFFF.toInt(), hair = 0x59FFFFFF,
            accent = 0xFFFFFFFF.toInt(), serifNumber = false, kaiBody = false,
            paint = { c ->
                c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, CARD_H.toFloat(),
                        intArrayOf(0xFFFF7E6B.toInt(), 0xFFFF9E6E.toInt(), 0xFFFFC98A.toInt()),
                        null, Shader.TileMode.CLAMP
                    )
                })
            }
        )
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, colorArgb: Int) {
        c.drawCircle(cx, cy, r, Paint().apply {
            shader = RadialGradient(cx, cy, r, colorArgb, 0x00000000, Shader.TileMode.CLAMP)
        })
    }

    private class Faces(
        val sans: Typeface,
        val sansBold: Typeface,
        val serif: Typeface,
        val kai: Typeface
    )

    private fun faces(context: Context): Faces = Faces(
        sans = ResourcesCompat.getFont(context, R.font.notosans_sc_regular) ?: Typeface.SANS_SERIF,
        sansBold = ResourcesCompat.getFont(context, R.font.notosans_sc_bold) ?: Typeface.DEFAULT_BOLD,
        serif = ResourcesCompat.getFont(context, R.font.notoserif_sc_bold) ?: Typeface.SERIF,
        kai = ResourcesCompat.getFont(context, R.font.lxgwwenkai_medium) ?: Typeface.SANS_SERIF
    )

    private fun tp(f: Typeface, sizePx: Float, color: Int, letterSpacing: Float = 0f): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = f
            textSize = sizePx
            this.color = color
            this.letterSpacing = letterSpacing
        }

    private fun parseHex(hex: String): Int? =
        if (hex.isBlank()) null else runCatching { Color.parseColor(hex.trim()) }.getOrNull()

    /** 秒 → 「12分30秒 / 1小时05分」 */
    fun formatDuration(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return when {
            h > 0 -> if (m > 0) "${h}小时${m}分" else "${h}小时"
            m > 0 -> "${m}分${ss}秒"
            else -> "${ss}秒"
        }
    }

    /** 渲染成绩卡（IO 线程调用）；位图即最终分享产物 */
    fun render(context: Context, d: CardData): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val theme = themeOf(d.themeId)
        theme.paint(c)
        val f = faces(context)
        val accent = parseHex(d.accentHex) ?: theme.accent
        val body = if (theme.kaiBody) f.kai else f.sans
        val bodyBold = if (theme.kaiBody) f.kai else f.sansBold
        val numberFace = when {
            theme.kaiBody -> f.kai
            theme.serifNumber -> f.serif
            else -> f.sansBold
        }
        val pad = 88f
        val green = 0xFF2E9E5B.toInt()
        val red = 0xFFE0483E.toInt()

        // ---- 顶部：栏目小字 + 日期 ----
        c.drawText(
            "TIYU · 模考成绩单", pad, 140f,
            tp(body, 30f, theme.sub, letterSpacing = 0.18f)
        )
        c.drawText(
            d.dateText, CARD_W - pad, 140f,
            tp(body, 30f, theme.sub, letterSpacing = 0.06f).apply { textAlign = Paint.Align.RIGHT }
        )
        c.drawRect(pad, 172f, CARD_W - pad, 174f, Paint().apply { color = theme.hair })

        // ---- 署名 + 标语（可选，固定留白区） ----
        var blockTop = 214f
        if (d.name.isNotBlank()) {
            c.drawText(d.name, pad, blockTop + 48f, tp(bodyBold, 46f, theme.ink))
            blockTop += 72f
        }
        if (d.slogan.isNotBlank()) {
            val sl = StaticLayout.Builder
                .obtain(d.slogan, 0, d.slogan.length, tp(body, 34f, theme.sub), (CARD_W - 2 * pad).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(10f, 1f)
                .setMaxLines(2)
                .build()
            c.save()
            c.translate(pad, blockTop + 10f)
            sl.draw(c)
            c.restore()
        }

        // ---- 大分数 + 单位 + 合格胶囊 ----
        val numPaint = tp(numberFace, 300f, theme.ink)
        c.drawText(d.scoreText, pad, 660f, numPaint)
        val numW = numPaint.measureText(d.scoreText)
        c.drawText("分", pad + numW + 20f, 660f, tp(body, 60f, theme.sub))
        // 成绩下划线条（强调色点缀）
        val bar = Paint().apply { color = accent }
        c.drawRoundRect(RectF(pad, 700f, pad + 132f, 712f), 6f, 6f, bar)

        val pill = RectF(CARD_W - pad - 262f, 556f, CARD_W - pad, 648f)
        val pillPaint = Paint().apply {
            color = if (d.passed) green else red
            isAntiAlias = true
        }
        c.drawRoundRect(pill, 46f, 46f, pillPaint)
        val pillText = if (d.passed) "合格通过" else "未通过"
        val pillTp = tp(bodyBold, 38f, Color.WHITE)
        val pillW = pillTp.measureText(pillText)
        c.drawText(pillText, pill.centerX() - pillW / 2f, 616f, pillTp)

        // ---- 数据网格 2×2 ----
        val cellL = CARD_W * 0.29f
        val cellR = CARD_W * 0.71f
        c.drawRect(pad, 780f, CARD_W - pad, 782f, Paint().apply { color = theme.hair })
        c.drawRect(CARD_W / 2f, 826f, CARD_W / 2f + 1f, 1140f, Paint().apply { color = theme.hair })

        fun cell(cx: Float, numBaseline: Float, text: String, size: Float, label: String) {
            c.drawText(text, cx, numBaseline, tp(numberFace, size, theme.ink).apply {
                textAlign = Paint.Align.CENTER
            })
            c.drawText(label, cx, numBaseline + 44f, tp(body, 30f, theme.sub).apply {
                textAlign = Paint.Align.CENTER
            })
        }
        cell(cellL, 892f, "${d.accuracyPct}%", 96f, "正确率")
        cell(cellR, 892f, d.durationText, 64f, "用时")
        c.drawRect(pad, 972f, CARD_W - pad, 974f, Paint().apply { color = theme.hair })
        cell(cellL, 1080f, d.correctText, 72f, "答对 / 总题数")
        cell(cellR, 1080f, "${d.streakDays} 天", 76f, "连续打卡")

        // ---- 进步对比 / 尝试次数 ----
        if (d.delta != null) {
            val up = d.delta >= 0
            val sign = if (up) "↑" else "↓"
            c.drawText(
                "较上次 $sign ${abs(d.delta)} 分", pad, 1204f,
                tp(bodyBold, 44f, if (up) green else red)
            )
        }
        if (d.attemptText != null) {
            c.drawText(
                d.attemptText, CARD_W - pad, 1204f,
                tp(body, 28f, theme.sub).apply { textAlign = Paint.Align.RIGHT }
            )
        }

        // ---- 底部：累计 + 品牌 ----
        c.drawRect(pad, 1252f, CARD_W - pad, 1254f, Paint().apply { color = theme.hair })
        c.drawText(
            "累计刷题 ${d.totalAnswered} 题 · ${d.bankName}", pad, 1320f,
            tp(body, 32f, theme.sub)
        )
        c.drawCircle(pad + 10f, 1378f, 10f, Paint().apply { color = accent })
        c.drawText("题屿 TiYu", pad + 38f, 1392f, tp(bodyBold, 36f, theme.ink))
        c.drawText(
            "把每一次练习都留在纸上", CARD_W - pad, 1386f,
            tp(body, 28f, theme.sub).apply { textAlign = Paint.Align.RIGHT }
        )
        return bmp
    }
}
