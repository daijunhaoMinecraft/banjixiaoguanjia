package com.daijunhao.classroommanager.data

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxWriter {
    fun build(
        sheetName: String,
        headers: List<String>,
        rows: List<List<String>>
    ): ByteArray {
        require(headers.isNotEmpty()) { "XLSX 至少需要一列表头" }
        val safeSheetName = sanitizeSheetName(sheetName)
        val normalizedRows = rows.map { row ->
            List(headers.size) { index -> row.getOrElse(index) { "" } }
        }
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.writeEntry(
                    "[Content_Types].xml",
                    contentTypesXml()
                )
                zip.writeEntry("_rels/.rels", rootRelationshipsXml())
                zip.writeEntry(
                    "docProps/app.xml",
                    appPropertiesXml(safeSheetName)
                )
                zip.writeEntry("docProps/core.xml", corePropertiesXml())
                zip.writeEntry("xl/workbook.xml", workbookXml(safeSheetName))
                zip.writeEntry(
                    "xl/_rels/workbook.xml.rels",
                    workbookRelationshipsXml()
                )
                zip.writeEntry("xl/styles.xml", stylesXml())
                zip.writeEntry(
                    "xl/worksheets/sheet1.xml",
                    sheetXml(headers, normalizedRows)
                )
            }
            output.toByteArray()
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(
        headers: List<String>,
        rows: List<List<String>>
    ): String {
        val lastColumn = columnName(headers.lastIndex)
        val lastRow = rows.size + 1
        val widths = headers.indices.map { column ->
            val values = sequence {
                yield(headers[column])
                rows.forEach { yield(it[column]) }
            }
            values.maxOf { displayWidth(it) }.coerceIn(8, 40)
        }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("""<dimension ref="A1:$lastColumn$lastRow"/>""")
            append("""<sheetViews><sheetView workbookViewId="0">""")
            append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
            append("""</sheetView></sheetViews>""")
            append("""<sheetFormatPr defaultRowHeight="15"/>""")
            append("<cols>")
            widths.forEachIndexed { index, width ->
                append(
                    """<col min="${index + 1}" max="${index + 1}" """ +
                        """width="$width" customWidth="1"/>"""
                )
            }
            append("</cols><sheetData>")
            append("<row r=\"1\">")
            headers.forEachIndexed { index, value ->
                append(
                    cellXml(
                        reference = "${columnName(index)}1",
                        value = value,
                        styleIndex = 1
                    )
                )
            }
            append("</row>")
            rows.forEachIndexed { rowIndex, row ->
                val excelRow = rowIndex + 2
                append("<row r=\"$excelRow\">")
                row.forEachIndexed { columnIndex, value ->
                    append(
                        cellXml(
                            reference = "${columnName(columnIndex)}$excelRow",
                            value = value,
                            styleIndex = 0
                        )
                    )
                }
                append("</row>")
            }
            append("</sheetData>")
            append("""<autoFilter ref="A1:$lastColumn$lastRow"/>""")
            append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
            append("</worksheet>")
        }
    }

    private fun cellXml(
        reference: String,
        value: String,
        styleIndex: Int
    ): String {
        val clean = sanitizeXml(value)
        if (clean.isBlank()) {
            return """<c r="$reference" s="$styleIndex"/>"""
        }
        if (NUMBER_REGEX.matches(clean)) {
            return """<c r="$reference" s="$styleIndex"><v>$clean</v></c>"""
        }
        return """<c r="$reference" s="$styleIndex" t="inlineStr"><is>""" +
            """<t xml:space="preserve">${escapeXml(clean)}</t></is></c>"""
    }

    private fun columnName(index: Int): String {
        var current = index
        val result = StringBuilder()
        while (true) {
            result.append(('A'.code + current % 26).toChar())
            current = current / 26 - 1
            if (current < 0) break
        }
        return result.reverse().toString()
    }

    private fun displayWidth(value: String): Int {
        return value.sumOf { character ->
            if (character.code > 0x7F) 2 else 1
        }
    }

    private fun sanitizeSheetName(name: String): String {
        val clean = name.replace(Regex("[\\\\/*?:\\[\\]]"), " ")
            .trim()
            .ifBlank { "成绩" }
        return clean.take(31)
    }

    private fun sanitizeXml(value: String): String {
        return value.filter { character ->
            character == '\t' ||
                character == '\n' ||
                character == '\r' ||
                character.code >= 0x20
        }
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun contentTypesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
            """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
            """</Types>"""

    private fun rootRelationshipsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
            """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
            """</Relationships>"""

    private fun workbookXml(sheetName: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/></sheets>""" +
            """<calcPr calcId="191029"/></workbook>"""

    private fun workbookRelationshipsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    private fun stylesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="Arial"/></font>""" +
            """<font><b/><sz val="11"/><name val="Arial"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""

    private fun corePropertiesXml(): String {
        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestamp = formatter.format(Date())
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:dcterms="http://purl.org/dc/terms/" """ +
            """xmlns:dcmitype="http://purl.org/dc/dcmitype/" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:creator>ClassroomManager</dc:creator>""" +
            """<cp:lastModifiedBy>ClassroomManager</cp:lastModifiedBy>""" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$timestamp</dcterms:created>""" +
            """<dcterms:modified xsi:type="dcterms:W3CDTF">$timestamp</dcterms:modified>""" +
            """</cp:coreProperties>"""
    }

    private fun appPropertiesXml(sheetName: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" """ +
            """xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">""" +
            """<Application>ClassroomManager</Application>""" +
            """<TitlesOfParts><vt:vector size="1" baseType="lpstr">""" +
            """<vt:lpstr>${escapeXml(sheetName)}</vt:lpstr>""" +
            """</vt:vector></TitlesOfParts></Properties>"""

    private val NUMBER_REGEX = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?")
}
