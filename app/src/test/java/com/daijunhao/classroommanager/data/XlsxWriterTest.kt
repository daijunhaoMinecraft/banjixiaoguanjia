package com.daijunhao.classroommanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class XlsxWriterTest {
    @Test
    fun `creates a valid xlsx package with worksheet data`() {
        val bytes = XlsxWriter.build(
            sheetName = "第一次成绩",
            headers = listOf("姓名", "语文", "备注"),
            rows = listOf(
                listOf("戴俊豪", "118", "正常"),
                listOf("刘鑫", "105.5", "正常")
            )
        )
        val entries = unzip(bytes)

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("戴俊豪"))
        assertTrue(sheet.contains("<v>118</v>"))
        assertTrue(sheet.contains("<v>105.5</v>"))
        assertTrue(sheet.contains("autoFilter"))
    }

    @Test
    fun `escapes xml and sanitizes sheet name`() {
        val bytes = XlsxWriter.build(
            sheetName = "成绩/第一学期?",
            headers = listOf("姓名", "说明"),
            rows = listOf(listOf("A&B", "<通过>"))
        )
        val entries = unzip(bytes)

        assertTrue(
            entries.getValue("xl/workbook.xml")
                .contains("""name="成绩 第一学期"""")
        )
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("A&amp;B"))
        assertTrue(sheet.contains("&lt;通过&gt;"))
    }

    @Test
    fun `writes inspectable workbook to build output`() {
        val output = File("build/test-output/score-export.xlsx")
        output.parentFile?.mkdirs()
        output.writeBytes(
            XlsxWriter.build(
                sheetName = "全班成绩",
                headers = listOf("姓名", "语文", "数学", "总分折后", "班排"),
                rows = listOf(
                    listOf("戴俊豪", "118", "117", "535.4", "9"),
                    listOf("刘鑫", "10", "8", "93.3", "51")
                )
            )
        )
        assertTrue(output.isFile && output.length() > 0)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals(true, entries.isNotEmpty())
        return entries
    }
}
