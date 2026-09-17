package com.drone.quiz.data.repo

import kotlinx.serialization.Serializable

/** 题型常量与展示名（v2.8.0 新题型：multi/blank/short） */
object QuestionTypes {
    const val SINGLE = "single"
    const val MULTI = "multi"
    const val BLANK = "blank"
    const val JUDGE = "judge"
    const val SHORT = "short"

    /** 组卷默认顺序：单选 → 多选 → 填空 → 判断 → 简答 */
    val canonicalOrder = listOf(SINGLE, MULTI, BLANK, JUDGE, SHORT)

    fun label(t: String): String = when (t) {
        SINGLE -> "单选"; MULTI -> "多选"; BLANK -> "填空"; JUDGE -> "判断"; SHORT -> "简答"; else -> t
    }

    fun isKnown(t: String) = t in canonicalOrder
}

/**
 * 统一作答状态（刷题 / 模考共用）。
 * picked 语义按题型：single/judge = 选项下标；multi = 已选位掩码（bit i = 选项 i）；
 * blank/short = 1 表示已提交。short 自评结果 = picked(1 对 / 0 错) + graded。
 */
@Serializable
data class UserAnswer(
    val picked: Int? = null,
    val texts: List<String> = emptyList(),  // blank 各空内容
    val text: String = "",                  // short 作答内容
    val graded: Boolean = false             // short 是否已自评
)

// ==================== 判分与展示 ====================

/** 判定一道题的作答对错；null = 尚未完成判定（如简答未自评）。 */
fun judgeAnswer(q: Question, ua: UserAnswer?): Boolean? {
    if (ua == null) return null
    return when (q.type) {
        QuestionTypes.SINGLE, QuestionTypes.JUDGE -> ua.picked?.let { it == q.answer }
        QuestionTypes.MULTI -> ua.picked?.let { it == q.answer }   // 位掩码全等 = 全对才对
        QuestionTypes.BLANK -> {
            // 用户选定口径：精确匹配（一字不差）；同空多个可接受变体任一命中即可
            if (ua.picked == null) null else {
                val ans = q.blankAnswers
                ua.texts.size == ans.size && ans.indices.all { i -> ua.texts[i] in ans[i] }
            }
        }
        QuestionTypes.SHORT -> if (ua.graded) ua.picked == 1 else null  // 自评判分
        else -> null
    }
}

/** 答题卡「已答」口径：blank/short 提交文本即算已答（简答自评前也占位）。 */
fun isAnswered(q: Question, ua: UserAnswer?): Boolean = when (q.type) {
    QuestionTypes.SHORT -> ua != null && ua.text.isNotBlank()
    else -> ua?.picked != null
}

/** 用户答案的可读文本（成绩页"你的答案"） */
fun displayUserAnswer(q: Question, ua: UserAnswer?): String {
    if (ua == null) return "未作答"
    return when (q.type) {
        QuestionTypes.SINGLE, QuestionTypes.JUDGE ->
            ua.picked?.let { optionLabel(it, q.isJudge) + "·" + q.options.getOrNull(it).orEmpty() } ?: "未作答"
        QuestionTypes.MULTI -> {
            val set = ua.picked?.let { p -> (0..31).filter { p and (1 shl it) != 0 } } ?: emptyList()
            if (set.isEmpty()) "未作答" else set.joinToString("、") { optionLabel(it, false) }
        }
        QuestionTypes.BLANK -> if (ua.texts.isEmpty()) "未作答"
        else ua.texts.mapIndexed { i, t -> "第${i + 1}空：${t.ifBlank { "（空）" }}" }.joinToString("；")
        QuestionTypes.SHORT -> ua.text.ifBlank { "未作答" }
        else -> "未作答"
    }
}

internal fun optionLabel(i: Int, isJudge: Boolean): String =
    if (isJudge) listOf("√", "×")[i.coerceIn(0, 1)] else ('A' + i).toString()

// ==================== 导入（CSV / ZIP） ====================

@Serializable
data class ImportQuestion(
    val id: Long? = null,
    val category: String = "未分类",
    val type: String = "single",
    val question: String,
    val options: List<String> = emptyList(),
    val answer: Int? = null,           // single/judge：选项下标
    val answers: List<Int>? = null,    // multi：正确项下标列表
    val answerText: String? = null,    // blank：各空答案（|| 分隔空，| 分隔变体）；short：参考答案
    val explanation: String = ""
)

@Serializable
data class ImportBank(
    val version: Int = 1,
    val questions: List<ImportQuestion>
)

/** 导入预览：逐行校验结果（失败行带原因，UI 展示导入报告） */
data class ImportPreview(
    val ok: List<ParsedQuestion>,
    val errors: List<String>,
    val categories: List<String>
) {
    val totalRows: Int get() = ok.size + errors.size
}

data class ParsedQuestion(
    val category: String,
    val type: String,
    val question: String,
    val options: List<String>,
    val answer: Int,          // single/judge 下标；multi 位掩码
    val answerText: String,   // blank / short
    val explanation: String,
    val id: Long? = null,     // JSON 内置题库可指定稳定 id
    val images: List<String> = emptyList() // v2.8.5：题目图片（ZIP 内的规范文件名，按引用顺序）
)

/** ZIP 导入结果：题目预览 + 图片字节表（key = 文件名小写）+ 唯一 CSV 名。 */
data class ZipImportResult(
    val preview: ImportPreview,
    val csvName: String,
    val images: Map<String, ByteArray>
)

object BankImport {

    /**
     * 统一入口（v2.8.5 起 UI 只保留 CSV / ZIP，JSON 不再作为导入格式）。
     * 兼容性提示：ZIP 请走 [parseZip]；BOM 容忍。
     */
    fun parse(bytes: ByteArray): ImportPreview {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            error("这是 ZIP 压缩包，请用「ZIP 压缩包」入口导入")
        }
        var text = bytes.decodeToString()
        if (text.startsWith("\uFEFF")) text = text.removePrefix("\uFEFF")
        val head = text.trimStart().firstOrNull() ?: error("文件为空")
        if (head == '{' || head == '[') {
            error("JSON 导入已下线：可让 Agent 把旧 JSON 转成 CSV 后再导入")
        }
        val preview = parseCsvText(text)
        require(preview.ok.isNotEmpty() || preview.errors.isNotEmpty()) { "没有解析到任何题目" }
        return preview
    }

    // ---------- ZIP（CSV + 图片文件夹） ----------

    /**
     * v2.8.5：ZIP 导入。约定结构（兼容单 CSV 打包）：
     * ```
     * 题库.zip
     * ├─ 题目.csv          ← 有且仅有一个 CSV（任意层级）
     * └─ images/           ← 图片文件夹可选；图片也允许在任意层级
     *     └─ 1.png ...
     * ```
     * CSV 新增「图片」列填文件名（忽略大小写匹配），多图用 | 分隔。
     * 仅被题目引用到的图片会随题库落盘；其余文件（说明等）忽略。
     */
    fun parseZip(zipBytes: ByteArray): ZipImportResult {
        val csvEntries = mutableListOf<Pair<String, ByteArray>>()
        val imageBytes = LinkedHashMap<String, ByteArray>()   // key = 文件名小写
        val imageIndex = LinkedHashMap<String, String>()      // key = 文件名小写 → ZIP 内规范文件名
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = QuestionImages.sanitize(entry.name)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        ext == "csv" -> {
                            val bytes = zis.readBytes()
                            if (bytes.isNotEmpty()) csvEntries.add(name to bytes)
                        }
                        ext in QuestionImages.ALLOWED_EXTS -> {
                            val bytes = zis.readBytes()
                            val key = name.lowercase()
                            if (bytes.isNotEmpty() && !imageBytes.containsKey(key)) {
                                imageBytes[key] = bytes
                                imageIndex[key] = name
                            }
                        }
                        // 其他文件（说明.md / .DS_Store / __MACOSX 等）忽略
                    }
                }
                entry = zis.nextEntry
            }
        }
        require(csvEntries.isNotEmpty()) {
            "ZIP 中没有找到 CSV 文件（需包含一个题目 CSV；带图片时把 CSV 和图片文件夹一起压缩）"
        }
        require(csvEntries.size == 1) {
            "ZIP 里有 ${csvEntries.size} 个 CSV（${csvEntries.take(3).joinToString("、") { it.first }}），请只保留一个题目 CSV"
        }
        val (csvName, csvBytes) = csvEntries.first()
        var text = csvBytes.decodeToString()
        if (text.startsWith("\uFEFF")) text = text.removePrefix("\uFEFF")
        val preview = parseCsvText(text, imageIndex)
        require(preview.ok.isNotEmpty() || preview.errors.isNotEmpty()) { "没有解析到任何题目" }
        val used = preview.ok.flatMap { it.images }.map { it.lowercase() }.toSet()
        return ZipImportResult(preview, csvName, imageBytes.filterKeys { it in used })
    }

    // ---------- JSON（仅内置题库播种用；导入通道已下线，见 parse()） ----------

    // ---------- CSV ----------

    /** 极简 RFC4180 解析：支持引号包裹、双引号转义、字段内逗号/换行、\r\n。 */
    fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> { sb.append('"'); i += 2 }
                    c == '"' -> { inQuotes = false; i++ }
                    else -> { sb.append(c); i++ }
                }
            } else when (c) {
                '"' -> { inQuotes = true; i++ }
                ',' -> { row.add(sb.toString()); sb.clear(); i++ }
                '\r' -> { i++; if (i < n && text[i] == '\n') i++; row.add(sb.toString()); sb.clear(); rows.add(row.toList()); row.clear() }
                '\n' -> { i++; row.add(sb.toString()); sb.clear(); rows.add(row.toList()); row.clear() }
                else -> { sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row.toList()) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun normHeader(s: String) = s.trim().replace(" ", "").replace("　", "")

    /**
     * CSV 解析。v2.8.5 新增「图片」列：
     * - [imageIndex] == null：纯 CSV 导入，「图片」列填了值会报错（带图请打包 ZIP）；
     * - [imageIndex] != null：ZIP 导入，key = 文件名小写 → value = ZIP 内规范文件名；
     *   引用不存在的文件名会逐行报错。多图用 | 分隔（也容忍 ；/;）。
     */
    fun parseCsvText(text: String, imageIndex: Map<String, String>? = null): ImportPreview {
        val rows = parseCsvRows(text)
        require(rows.isNotEmpty()) { "CSV 为空" }
        val header = rows.first().map(::normHeader)

        fun colOf(names: List<String>): Int = header.indexOfFirst { h -> names.any { h.equals(it, true) } }
        val iNo = colOf(listOf("题号", "编号", "序号", "No", "ID"))
        val iType = colOf(listOf("题型", "类型", "Type"))
        val iStem = colOf(listOf("题干", "题目", "问题", "Question"))
        require(iStem >= 0) { "CSV 缺少「题干」列（列头需含：题号/题型/题干/选项A…/答案/解析）" }
        val iAns = colOf(listOf("答案", "正确答案", "Answer"))
        require(iAns >= 0) { "CSV 缺少「答案」列" }
        val iExpl = colOf(listOf("解析", "答案解析", "Explanation"))
        val iNote = colOf(listOf("备注", "Note"))
        val iImg = colOf(listOf("图片", "配图", "插图", "image", "images", "picture"))
        // 选项列：选项A…选项H（兼容 A/optionA 写法）
        val optionCols = IntArray(8) { -1 }
        header.forEachIndexed { idx, h ->
            val m = Regex("^(?:选项|option)?([A-Ha-h])$").find(h)
            if (m != null) optionCols[m.groupValues[1].uppercase().first() - 'A'] = idx
        }

        val errors = mutableListOf<String>()
        val ok = mutableListOf<ParsedQuestion>()
        rows.drop(1).forEachIndexed { rowIdx, cells ->
            fun cell(i: Int) = if (i >= 0 && i < cells.size) cells[i].trim() else ""
            val lineNo = rowIdx + 2 // 含表头，人类可读行号
            val stem = cell(iStem)
            if (stem.isBlank()) { errors.add("第 $lineNo 行：题干为空"); return@forEachIndexed }
            val opts = optionCols.map { cell(it) }.filter { it.isNotEmpty() }
            val rawType = if (iType >= 0) cell(iType) else "单选"
            val ansRaw = cell(iAns)
            var expl = cell(iExpl)
            val note = cell(iNote)
            if (note.isNotBlank()) expl = (if (expl.isBlank()) "" else "$expl\n") + "备注：$note"

            val no = if (iNo >= 0) cell(iNo) else "${rowIdx + 1}"
            // v2.8.5：图片列解析（大小写不敏感匹配 ZIP 内文件；多图用 | 分隔）
            val imageRefs = if (iImg >= 0) {
                cell(iImg).split('|', '；', ';').map { it.trim() }.filter { it.isNotEmpty() }
            } else emptyList()
            val resolvedImages = if (imageRefs.isEmpty()) {
                emptyList()
            } else if (imageIndex == null) {
                errors.add(
                    "第 $lineNo 行：填了图片「${imageRefs.first()}」——带图片的题库需打包 ZIP（题目 CSV + 图片）导入；纯 CSV 请把「图片」列留空"
                )
                return@forEachIndexed
            } else {
                val missing = imageRefs.filter { it.lowercase() !in imageIndex }
                if (missing.isNotEmpty()) {
                    errors.add(
                        "第 $lineNo 行：图片「${missing.first()}」在压缩包中未找到（文件名需与 ZIP 内图片一致，支持 jpg/png/webp/gif/bmp）"
                    )
                    return@forEachIndexed
                }
                imageRefs.map { imageIndex[it.lowercase()]!! }.distinct()
            }
            handleParsedRaw(lineNo, no, rawType, stem, opts, ansRaw, expl, resolvedImages, ok, errors)
        }
        return ImportPreview(ok, errors, ok.map { it.category }.distinct().sorted())
    }

    /** CSV 通道的类型归一与校验 */
    private fun handleParsedRaw(
        lineNo: Int,
        no: String,
        rawType: String,
        stem: String,
        opts: List<String>,
        ansRaw: String,
        explanation: String,
        images: List<String> = emptyList(),
        ok: MutableList<ParsedQuestion>,
        errors: MutableList<String>
    ) {
        val type = normalizeType(rawType)
            ?: run { errors.add("第 $lineNo 行（题号 $no）：无法识别题型「$rawType」"); return }
        val ansSupplier: () -> Int? = {
            parseAnswerIndex(type, ansRaw, opts.size, no, lineNo, errors)
        }
        val answersSupplier: () -> List<Int>? = {
            parseMultiIndices(ansRaw)?.also {
                if (it.size < 2) errors.add("第 $lineNo 行（题号 $no）：多选题答案需至少两个选项（如 ABD）")
            }
        }
        buildParsed(lineNo, type, "未分类", stem, opts, ansSupplier, answersSupplier, { ansRaw }, explanation, images, ok, errors)
    }

    private fun buildParsed(
        lineNo: Int,
        type: String,
        category: String,
        stem: String,
        opts: List<String>,
        answer: () -> Int?,
        answers: () -> List<Int>?,
        answerText: () -> String,
        explanation: String,
        images: List<String> = emptyList(),
        ok: MutableList<ParsedQuestion>,
        errors: MutableList<String>
    ) {
        when (type) {
            QuestionTypes.SINGLE, QuestionTypes.JUDGE -> {
                if (opts.size < 2) { errors.add("第 $lineNo 处：${QuestionTypes.label(type)}题至少需要 2 个选项"); return }
                val a = answer() ?: return // 错误已在 parseAnswerIndex 内记录
                ok.add(ParsedQuestion(category, type, stem, opts, a, "", explanation, images = images))
            }
            QuestionTypes.MULTI -> {
                if (opts.size < 3) { errors.add("第 $lineNo 处：多选题至少需要 3 个选项"); return }
                val list = answers() ?: return
                if (list.size < 2) return // 错误已记录
                val mask = list.fold(0) { acc, i -> acc or (1 shl i) }
                ok.add(ParsedQuestion(category, type, stem, opts, mask, "", explanation, images = images))
            }
            QuestionTypes.BLANK -> {
                val ansText = answerText()
                val blanksInStem = Regex("_{3,}").findAll(stem).count()
                if (blanksInStem == 0) { errors.add("第 $lineNo 处：填空题题干需用下划线 ____ 标出空位"); return }
                val blanksInAns = ansText.split("||").size
                if (ansText.isBlank()) { errors.add("第 $lineNo 处：填空题答案不能为空"); return }
                if (blanksInAns != blanksInStem) {
                    errors.add("第 $lineNo 处：填空题空位数（$blanksInStem）与答案空数（$blanksInAns）不一致（多空用 || 分隔）")
                    return
                }
                ok.add(ParsedQuestion(category, type, stem, emptyList(), 0, ansText, explanation, images = images))
            }
            QuestionTypes.SHORT -> {
                val ansText = answerText()
                if (ansText.isBlank()) { errors.add("第 $lineNo 处：简答题需要参考答案（自评判分的对照）"); return }
                ok.add(ParsedQuestion(category, type, stem, emptyList(), 0, ansText, explanation, images = images))
            }
        }
    }

    /** 题型归一：单选/多选/判断/填空/简答（含常见变体） */
    fun normalizeType(raw: String): String? {
        val t = raw.trim().lowercase().removeSuffix("题")
        return when {
            t.isEmpty() -> null
            "multi" in t || "多选" in t || "多选题" in raw -> QuestionTypes.MULTI
            "blank" in t || "fill" in t || "填空" in raw -> QuestionTypes.BLANK
            "short" in t || "简答" in raw || "问答" in raw || "主观" in raw -> QuestionTypes.SHORT
            "judge" in t || "判断" in raw || "对错" in raw -> QuestionTypes.JUDGE
            "single" in t || "单选" in raw || "选择" in raw || "choice" in t -> QuestionTypes.SINGLE
            else -> null
        }
    }

    /** single/judge 的答案解析（字母 A–H / 数字序号 1 起 / 判断的 对错正误） */
    private fun parseAnswerIndex(type: String, raw: String, optCount: Int, no: String, lineNo: Int, errors: MutableList<String>): Int? {
        val s = raw.trim()
        fun fail(reason: String): Int? { errors.add("第 $lineNo 行（题号 $no）：$reason"); return null }
        if (type == QuestionTypes.JUDGE) {
            return when {
                s in listOf("对", "正确", "√", "✓", "是", "T", "true", "True", "TRUE", "A", "a", "0") -> 0
                s in listOf("错", "错误", "×", "X", "x", "否", "F", "false", "False", "FALSE", "B", "b", "1") -> 1
                else -> fail("判断题答案无法识别「$s」（应为 对/错/正确/错误）")
            }
        }
        val up = s.uppercase()
        if (up.length == 1 && up[0] in 'A'..'H') {
            val idx = up[0] - 'A'
            if (idx >= optCount) return fail("答案 $s 超出选项数量（$optCount）")
            return idx
        }
        s.toIntOrNull()?.let { n ->
            val idx = n - 1
            if (idx in 0 until optCount) return idx
            return fail("答案序号 $n 超出选项数量（$optCount）")
        }
        return fail("单选题答案无法识别「$s」（应为字母 A–${('A' + optCount - 1)} 或序号）")
    }

    /** 多选答案：从字符串提取全部 A–H（容忍 ABD / A、B、D / A,B,D 写法） */
    private fun parseMultiIndices(raw: String): List<Int>? {
        val chars = raw.uppercase().filter { it in 'A'..'H' }.toCharArray().distinct()
        if (chars.isEmpty()) return null
        return chars.map { it - 'A' }
    }
}
