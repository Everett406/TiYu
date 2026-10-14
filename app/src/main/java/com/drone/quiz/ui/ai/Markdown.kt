package com.drone.quiz.ui.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 极简 Markdown → AnnotatedString 渲染。
 *
 * ## 为什么要自己写
 * 沙箱里 Gradle 的 Java 证书链与出网代理对不上，坐标依赖拉不下来；而 Markdown 渲染
 * 只需要纯文本样式，Compose 的 `buildAnnotatedString` 本身就够，不必引库。
 *
 * ## 支持范围（刻意克制）
 * 标题 `#`~`######`、有序/无序列表、粗体 `**` / 斜体 `*` / 删除线 `~~`、
 * 行内代码 `` ` ``、围栏代码块 ```、引用 `>`、水平线 `---`、链接 `[t](u)`。
 * **不支持表格和图片**（聊天场景极少用到，强行半支持反而更难看）。
 *
 * ## LaTeX 降级（用户明确：公式别学 servllama 那套，它是坏的）
 * 模型很爱写 `\frac{1}{2}`、`\rho`、`\cdot`。这里不排版，只做**字符级降级**：
 * 常见命令换成 Unicode 符号（`\rho`→ρ、`\cdot`→·、`\times`→×），
 * `\frac{a}{b}` 降级成 `(a)/(b)`，其余反斜杠命令剥掉反斜杠保留字母。
 * 至少读得懂，不会像截图里那样满屏 `\(` `\)` `\frac12`。
 */
object Md {

    /** 解析出的行类型。 */
    sealed interface Line {
        data class Text(val spans: List<Span>) : Line
        data class Heading(val level: Int, val spans: List<Span>) : Line
        data class Bullet(val indent: Int, val spans: List<Span>) : Line
        data class Ordered(val indent: Int, val marker: String, val spans: List<Span>) : Line
        data class Quote(val spans: List<Span>) : Line
        data class Code(val text: String) : Line
        data object Rule : Line
        data object Blank : Line
    }

    /** 行内片段。 */
    data class Span(
        val text: String = "",
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null
    )

    /** 一行里"不含标记"的可见字符数——吐字动画按这个计数。 */
    val Line.visibleLen: Int get() = when (this) {
        is Line.Text -> spans.sumOf { it.text.length }
        is Line.Heading -> spans.sumOf { it.text.length }
        is Line.Bullet -> spans.sumOf { it.text.length }
        is Line.Ordered -> spans.sumOf { it.text.length }
        is Line.Quote -> spans.sumOf { it.text.length }
        is Line.Code -> text.length
        else -> 0
    }

    fun parse(src: String): List<Line> {
        val out = mutableListOf<Line>()
        val raw = src.split("\n")
        var i = 0
        var orderedN = 0
        while (i < raw.size) {
            val s = raw[i]
            val t = s.trim()
            when {
                t.isEmpty() -> { out += Line.Blank; orderedN = 0 }

                // 围栏代码块：整块吃掉，内部不做任何行内解析
                t.startsWith("```") -> {
                    val buf = StringBuilder()
                    i++
                    while (i < raw.size && !raw[i].trim().startsWith("```")) {
                        buf.append(raw[i]).append('\n')
                        i++
                    }
                    out += Line.Code(buf.toString().trimEnd('\n'))
                }

                // 水平线
                t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' } ||
                        t.all { it == '_' }) -> {
                    out += Line.Rule
                    orderedN = 0
                }

                else -> {
                    // 标题
                    val h = t.takeWhile { it == '#' }.length
                    if (h in 1..6 && t.length > h && t[h] == ' ') {
                        out += Line.Heading(h, inline(t.substring(h + 1)))
                        orderedN = 0
                    } else if (t.startsWith("> ")) {
                        out += Line.Quote(inline(t.substring(2)))
                        orderedN = 0
                    } else if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                        val indent = s.takeWhile { it == ' ' }.length / 2
                        out += Line.Bullet(indent, inline(t.drop(2)))
                    } else {
                        val om = ORDERED.matchEntire(t)
                        if (om != null) {
                            orderedN++
                            val indent = s.takeWhile { it == ' ' }.length / 2
                            out += Line.Ordered(indent, "${om.groupValues[1]}.", inline(t.substringAfter(". ")))
                        } else {
                            // 续行：上一行是列表项且本行缩进 → 当作同一项的续行（简化：不特殊处理）
                            out += Line.Text(inline(t))
                        }
                    }
                }
            }
            i++
        }
        return out
    }

    /** 整篇的可见字符总数（吐字动画的目标长度）。 */
    fun visibleLen(lines: List<Line>): Int = lines.sumOf { it.visibleLen }

    /**
     * 把解析结果渲染成 AnnotatedString，只显示前 [showChars] 个可见字符。
     *
     * `showChars = null` 表示全量显示（非流式、或已吐完）。
     * 这是"逐字吐"与"Markdown 正确渲染"能共存的关键：
     * **先解析 Markdown，再按可见字符数截断**——而不是把 Markdown 源码本身截断
     * （那会让屏幕上半屏裸的 `**` 和没闭合的 `|`，servllama 踩过这个坑）。
     */
    fun build(
        lines: List<Line>,
        showChars: Int?,
        base: TextStyle,
        color: Color,
        accent: Color,
        codeBg: Color
    ): AnnotatedString {
        val sb = AnnotatedString.Builder()
        // 剩余可见字符预算：逐个 span 扣，扣到 0 就停。
        // 标记本身（**、``、#）不计入预算——它们不是"人能看到的字"。
        var budget = showChars ?: Int.MAX_VALUE

        fun emit(spans: List<Span>, container: Span, st: TextStyle) {
            for (s in spans) {
                if (budget <= 0) return
                val merged = Span(
                    text = s.text,
                    bold = s.bold || container.bold,
                    italic = s.italic || container.italic,
                    strike = s.strike || container.strike,
                    code = s.code || container.code,
                    link = s.link ?: container.link
                )
                val n = minOf(budget, merged.text.length)
                if (n <= 0) return
                val body = merged.text.take(n)
                budget -= n
                sb.pushStyle(
                    styleOf(merged, st, color, accent, codeBg)
                )
                sb.append(body)
                sb.pop()
            }
        }

        for ((li, line) in lines.withIndex()) {
            if (budget <= 0) break
            when (line) {
                is Line.Blank -> { if (li > 0 && sb.length > 0) sb.append('\n') }

                is Line.Rule -> if (budget > 0) sb.append("──────────\n")

                // 代码块整体出现：代码不是给人读内容的，逐字吐没意义
                is Line.Code -> {
                    val n = minOf(budget, line.text.length)
                    if (n > 0) {
                        budget -= n
                        sb.pushStyle(styleOf(Span(code = true), base, color, accent, codeBg))
                        sb.append(line.text.take(n))
                        sb.pop()
                        sb.append('\n')
                    }
                }

                is Line.Heading -> {
                    emit(line.spans, Span(bold = true), headingStyle(line.level, base))
                    sb.append('\n')
                }
                is Line.Quote -> {
                    emit(line.spans, Span(italic = true),
                        base.copy(fontStyle = FontStyle.Italic, color = color.copy(alpha = 0.72f)))
                    sb.append('\n')
                }
                is Line.Bullet -> {
                    sb.append("  ".repeat(line.indent)); sb.append("· ")
                    emit(line.spans, Span(), base)
                    sb.append('\n')
                }
                is Line.Ordered -> {
                    sb.append("  ".repeat(line.indent)); sb.append("${line.marker} ")
                    emit(line.spans, Span(), base)
                    sb.append('\n')
                }
                is Line.Text -> {
                    emit(line.spans, Span(), base)
                    sb.append('\n')
                }
            }
        }
        return sb.toAnnotatedString()
    }

    private fun styleOf(span: Span, base: TextStyle, color: Color, accent: Color, codeBg: Color) =
        SpanStyle(
            color = if (span.code) codeBg else color,
            fontWeight = if (span.bold) FontWeight.Bold else base.fontWeight,
            fontStyle = if (span.italic) FontStyle.Italic else base.fontStyle,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            fontFamily = if (span.code) FontFamily.Monospace else base.fontFamily
        )

    private fun headingStyle(level: Int, base: TextStyle) = when (level) {
        1 -> base.copy(fontSize = base.fontSize * 1.32f, fontWeight = FontWeight.Bold)
        2 -> base.copy(fontSize = base.fontSize * 1.20f, fontWeight = FontWeight.Bold)
        3 -> base.copy(fontSize = base.fontSize * 1.10f, fontWeight = FontWeight.Bold)
        else -> base.copy(fontWeight = FontWeight.Bold)
    }

    // ---- 行内解析 ----

    private fun inline(src: String): List<Span> {
        // 先脱 LaTeX：否则 `\(` `\)` 会被当成普通反斜杠文本，链接/强调规则会乱切
        val s = dematex(src)
        val out = mutableListOf<Span>()
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) { out += Span(buf.toString()); buf.clear() }
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '*' && i + 1 < s.length && s[i + 1] == '*' -> {
                    flush()
                    val end = s.indexOf("**", i + 2)
                    if (end < 0) { buf.append(c); i++ } else {
                        out += Span(s.substring(i + 2, end), bold = true); i = end + 2
                    }
                }
                c == '~' && i + 1 < s.length && s[i + 1] == '~' -> {
                    flush()
                    val end = s.indexOf("~~", i + 2)
                    if (end < 0) { buf.append(c); i++ } else {
                        out += Span(s.substring(i + 2, end), strike = true); i = end + 2
                    }
                }
                c == '*' -> {
                    flush()
                    val end = s.indexOf('*', i + 1)
                    if (end <= i + 1) { buf.append(c); i++ } else {
                        out += Span(s.substring(i + 1, end), italic = true); i = end + 1
                    }
                }
                c == '`' -> {
                    flush()
                    val end = s.indexOf('`', i + 1)
                    if (end <= i + 1) { buf.append(c); i++ } else {
                        out += Span(s.substring(i + 1, end), code = true); i = end + 1
                    }
                }
                c == '[' -> {
                    val close = s.indexOf(']', i)
                    val open = if (close >= 0) s.indexOf('(', close) else -1
                    val end = if (open >= 0) s.indexOf(')', open) else -1
                    if (close > i && open > close && end > open) {
                        flush()
                        out += Span(
                            s.substring(i + 1, close),
                            link = s.substring(open + 1, end),
                            italic = false
                        )
                        i = end + 1
                    } else { buf.append(c); i++ }
                }
                c == '\\' -> {          // 残留的孤立反斜杠，直接吞掉
                    i++
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return out.ifEmpty { listOf(Span("")) }
    }

    /**
     * LaTeX → 可读文本的**降级**（不是排版）。
     * 目标只有一个：别让用户看到满屏 `\frac12\rho^2`。
     */
    private fun dematex(s: String): String {
        var t = s
        // 去掉成对包裹：\( ... \) 和 \[ ... \]
        t = t.replace(Regex("\\\\\\((.*?)\\\\\\)", RegexOption.DOT_MATCHES_ALL), "$1")
        t = t.replace(Regex("\\\\\\[(.*?)\\\\\\]", RegexOption.DOT_MATCHES_ALL), "$1")
        t = t.replace("$", "").replace("$$", "")
        // \frac{a}{b} → (a)/(b)
        t = t.replace(Regex("\\\\d?frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "($1)/($2)")
        // \sqrt{a} → √(a)
        t = t.replace(Regex("\\\\sqrt\\s*\\{([^{}]*)\\}"), "√($1)")
        // 上下标：x^{2} → x²
        t = t.replace(Regex("\\^\\{?(-?[0-9a-zA-Z]+)\\}?")) { m ->
            SUPERSCRIPT[m.groupValues[1]] ?: ("^" + m.groupValues[1])
        }
        t = t.replace(Regex("\\_\\{?([0-9a-zA-Z]+)\\}?")) { m ->
            SUBSCRIPT[m.groupValues[1]] ?: ("_" + m.groupValues[1])
        }
        // 常见命令 → 符号
        val SYM = mapOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "epsilon" to "ε", "theta" to "θ", "lambda" to "λ", "mu" to "μ",
            "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "omega" to "ω",
            "Delta" to "Δ", "Omega" to "Ω", "Phi" to "Φ", "Sigma" to "Σ",
            "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±",
            "approx" to "≈", "neq" to "≠", "leq" to "≤", "geq" to "≥",
            "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
            "infty" to "∞", "degree" to "°", "circ" to "°", "percent" to "%",
            "left" to "", "right" to "", "mathrm" to "", "text" to "", "quad" to " "
        )
        SYM.forEach { (k, v) -> t = t.replace(Regex("\\\\$k\\b"), Regex.escapeReplacement(v)) }
        // 残留的 \command → command
        t = t.replace(Regex("\\\\([a-zA-Z]+)"), "$1")
        // 残留的孤立花括号/下划线：保留内容即可
        return t
    }

    private val SUPERSCRIPT = mapOf(
        "0" to "⁰", "1" to "¹", "2" to "²", "3" to "³", "4" to "⁴",
        "5" to "⁵", "6" to "⁶", "7" to "⁷", "8" to "⁸", "9" to "⁹",
        "+" to "⁺", "-" to "⁻", "n" to "ⁿ", "i" to "ⁱ"
    )
    private val SUBSCRIPT = mapOf(
        "0" to "₀", "1" to "₁", "2" to "₂", "3" to "₃", "4" to "₄",
        "5" to "₅", "6" to "₆", "7" to "₇", "8" to "₈", "9" to "₉",
        "n" to "ₙ", "i" to "ᵢ", "j" to "ⱼ"
    )

    private val ORDERED = Regex("^(\\d{1,2})[.)]\\s+(.*)$")
}