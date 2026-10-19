package com.drone.quiz

import com.drone.quiz.data.repo.BankImport
import com.drone.quiz.screens.CSV_TEMPLATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 模板自身的合法性（v2.19.52）。
 *
 * 模板是用户拿到 App 外去填的**唯一说明书**——照抄错位就是导入报错。
 * 之前加「解析图片」列时，模板有 3 行多了一列、1 行整体错位一格
 * （答案栏写着「见下图」，解析栏写着图片名），用户照抄必然踩坑。
 *
 * 所以这里不只查列数，还要**真的拿模板去导入一遍**：
 * 能被解析器吃下去，才算模板是对的。
 */
class CsvTemplateTest {

    /** 极简 RFC4180 行切分（够用于模板这种不含换行字段的场景） */
    private fun splitRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuote && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun rows(): List<List<String>> =
        CSV_TEMPLATE.trim().lines().filter { it.isNotBlank() }.map { splitRow(it) }

    @Test
    fun `模板每一行的列数都必须与表头一致`() {
        val r = rows()
        val header = r.first()
        assertTrue("表头不应为空", header.isNotEmpty())

        r.drop(1).forEachIndexed { i, row ->
            assertEquals(
                "模板第 ${i + 2} 行（题号 ${row.firstOrNull()}）列数与表头不一致：" +
                    "表头 ${header.size} 列，实际 ${row.size} 列",
                header.size, row.size
            )
        }
    }

    @Test
    fun `模板表头必须同时含图片与解析图片两列`() {
        val header = rows().first()
        assertTrue("表头须有「图片」列", header.contains("图片"))
        assertTrue("表头须有「解析图片」列", header.contains("解析图片"))
        // 两列必须是分开的，不能合成一列——合并会让答案图在作答前就露出来
        assertEquals(1, header.count { it == "解析图片" })
    }

    /**
     * 模板必须**真的能被 App 导入**。
     * 这是最硬的一条：模板自己都导不进去，用户照抄的必然也导不进去。
     */
    @Test
    fun `模板本身必须能被导入器吃下去`() {
        val r = BankImport.parse(CSV_TEMPLATE.toByteArray())
        assertTrue(
            "模板应当能被正常导入，实际报错：${r.errors}",
            r.errors.isEmpty()
        )
        assertTrue("模板应至少解析出几道题", r.ok.size >= 5)
    }

    /**
     * 模板本身**不能**带真实图片文件名。
     *
     * 模板是用户拿出去填的东西，填完得能直接当纯 CSV 导入。
     * 如果模板示例行里真写了「ans1.png」，纯 CSV 导入时解析器会报
     * 「填了解析图片…需打包 ZIP 导入」——用户拿官方模板都导不进去。
     * 所以模板只留**规则说明**（在备注里），真正的填法由提示词讲。
     */
    @Test
    fun `模板不含真实图片引用以保证能当纯CSV导入`() {
        val r = BankImport.parse(CSV_TEMPLATE.toByteArray())
        assertTrue(
            "模板须能当纯 CSV 导入（图片列有内容就会报「需打包 ZIP」）：${r.errors}",
            r.errors.isEmpty()
        )
        r.ok.forEach {
            assertTrue("模板不应带题目图引用", it.images.isEmpty())
            assertTrue("模板不应带解析图引用", it.explanationImages.isEmpty())
        }
    }

    /** 模板里那道作图示例题，备注必须讲清「答案图放解析图片列」 */
    @Test
    fun `模板的作图示例行在备注里讲清了解析图片的填法`() {
        val rows = rows()
        val header = rows.first()
        val noteIdx = header.indexOf("备注")
        val drawing = rows.drop(1).firstOrNull {
            it.getOrNull(header.indexOf("题干")).orEmpty().contains("作图题")
        }
        assertTrue("模板应含作图示例行", drawing != null)
        val note = drawing!![noteIdx]
        assertTrue("备注须写明答案图放「解析图片」列：$note", note.contains("解析图片"))
        assertTrue("备注须警告别放「图片」列：$note", note.contains("图片」列"))
    }
}
