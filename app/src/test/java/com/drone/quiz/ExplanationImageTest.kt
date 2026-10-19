package com.drone.quiz

import com.drone.quiz.data.repo.BankImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 解析图片（v2.19.52）。
 *
 * 起因：作图题的答案本身就是一张图（几何作图、步骤图、标准答案图），
 * 而解析字段此前是纯文本，放不了图。用户要求支持，并把规则写进导入模板/提示词。
 *
 * 最关键的一条不变量：**答案图不能出现在作答前可见的位置**。
 * 「图片」列的图显示在题干下方，用户一眼就能看到；「解析图片」列的图藏在
 * 折叠的解析里，必须主动点「查看解析」才展开。填错列 = 直接剧透答案。
 */
class ExplanationImageTest {

    private fun csvZip(csv: String, images: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("题目.csv")); z.write(csv.toByteArray()); z.closeEntry()
            images.forEach { (n, b) ->
                z.putNextEntry(ZipEntry("images/$n")); z.write(b); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 1×1 的合法 PNG */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte()
    )

    // ── 解析：列能读到 ──────────────────────────────────────────

    @Test
    fun `解析图片列被正确解析`() {
        val csv = """
            题号,题型,题干,图片,答案,解析,解析图片
            1,简答,普通题,,普通题的参考答案,这是解析,
            2,简答,作图题,,见解析图,看下图,ans1.png
        """.trimIndent()
        val r = BankImport.parseZip(csvZip(csv, mapOf("ans1.png" to png)))
        assertTrue("应全部解析成功，实际错：${r.preview.errors}", r.preview.errors.isEmpty())

        val q1 = r.preview.ok[0]
        val q2 = r.preview.ok[1]
        assertTrue("第 1 题不应有解析图片", q1.explanationImages.isEmpty())
        assertEquals(listOf("ans1.png"), q2.explanationImages)
    }

    // ── 解析：两列必须严格分开 ──────────────────────────────────

    /**
     * 核心不变量：答案图只能落在 explanationImages，**绝不能**混进 images。
     * images 里的图显示在题干下方 = 作答前可见 = 剧透。
     */
    @Test
    fun `答案图只落在解析图列不混进题目图列`() {
        val csv = """
            题号,题型,题干,图片,答案,解析,解析图片
            1,简答,作图题,,见解析图,箭头方向即答案,ans1.png
        """.trimIndent()
        val r = BankImport.parseZip(csvZip(csv, mapOf("ans1.png" to png)))
        val q = r.preview.ok[0]
        assertEquals("答案图不得进入题干图列", emptyList<String>(), q.images)
        assertEquals("答案图应进入解析图列", listOf("ans1.png"), q.explanationImages)
    }

    @Test
    fun `题目图与解析图可以同时存在且各归各位`() {
        val csv = """
            题号,题型,题干,图片,答案,解析,解析图片
            1,简答,看图并作图,fig1.png,见解析图,红色箭头为答案,ans1.png
        """.trimIndent()
        val r = BankImport.parseZip(csvZip(csv, mapOf("fig1.png" to png, "ans1.png" to png)))
        val q = r.preview.ok[0]
        assertEquals(listOf("fig1.png"), q.images)
        assertEquals(listOf("ans1.png"), q.explanationImages)
    }

    @Test
    fun `解析图支持多张用竖线分隔`() {
        val csv = """
            题号,题型,题干,图片,答案,解析,解析图片
            1,简答,作图题,,见解析图,看下图,a.png|b.png
        """.trimIndent()
        val r = BankImport.parseZip(csvZip(csv, mapOf("a.png" to png, "b.png" to png)))
        assertEquals(listOf("a.png", "b.png"), r.preview.ok[0].explanationImages)
    }

    // ── 解析：落盘 ──────────────────────────────────────────────

    /** 只被「解析图片」列引用的图也必须随题库落盘，否则打开时是空图 */
    @Test
    fun `仅被解析引用的图片也会落盘`() {
        val csv = """
            题号,题型,题干,图片,答案,解析,解析图片
            1,简答,作图题,,见解析图,看下图,ans1.png
        """.trimIndent()
        val r = BankImport.parseZip(csvZip(csv, mapOf("ans1.png" to png, "unused.png" to png)))
        assertTrue("解析引用的图必须落盘，实际：${r.images.keys}",
            r.images.containsKey("ans1.png"))
        assertTrue("未被引用的图不应落盘", !r.images.containsKey("unused.png"))
    }

    // ── 解析：报错要能看懂 ──────────────────────────────────────

    @Test
    fun `纯CSV填了解析图片要给出明确报错`() {
        val csv = "题号,题型,题干,图片,答案,解析,解析图片\n1,简答,作图题,,见解析图,看下图,ans1.png"
        val r = BankImport.parse(csv.toByteArray())
        assertTrue("应报错", r.errors.isNotEmpty())
        assertTrue("报错应点明需 ZIP 导入：${r.errors.first()}",
            r.errors.first().contains("ZIP"))
    }

    @Test
    fun `解析图文件缺失要报出文件名`() {
        val csv = """
            题号,题型,题干,图片,答案,解析,解析图片
            1,简答,作图题,,见解析图,看下图,missing.png
        """.trimIndent()
        val r = BankImport.parseZip(csvZip(csv, mapOf("other.png" to png)))
        assertTrue("应报错", r.preview.errors.isNotEmpty())
        assertTrue("报错应含缺失的文件名：${r.preview.errors.first()}",
            r.preview.errors.first().contains("missing.png"))
    }

    @Test
    fun `没有解析图片列时不影响兼容性`() {
        // 老题库（v2.8.5 及更早）没有这一列，必须照常导入
        val csv = """
            题号,题型,题干,图片,选项A,选项B,选项C,答案,解析
            1,单选,老题库题目,,甲,乙,丙,B,解析文字
        """.trimIndent()
        val r = BankImport.parse(csv.toByteArray())
        assertTrue("旧格式须照常导入：${r.errors}", r.errors.isEmpty())
        assertTrue("旧格式解析图片应为空", r.ok[0].explanationImages.isEmpty())
    }
}
