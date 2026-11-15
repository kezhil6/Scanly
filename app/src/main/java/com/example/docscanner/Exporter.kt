package com.example.docscanner

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes minimal .docx / .xlsx / .txt files from OCR text (no extra libraries). */
object Exporter {

    private fun clean(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val ok = ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20
            if (ok) sb.append(ch)
        }
        return sb.toString()
    }

    private fun esc(s: String): String =
        clean(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    fun writeTxt(text: String, out: File) {
        out.writeText(clean(text))
    }

    fun writeDocx(text: String, out: File) {
        val body = StringBuilder()
        for (line in clean(text).lines()) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                .append(esc(line))
                .append("</w:t></w:r></w:p>")
        }
        ZipOutputStream(out.outputStream()).use { z ->
            z.put(
                "[Content_Types].xml",
                XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "</Types>"
            )
            z.put(
                "_rels/.rels",
                XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "</Relationships>"
            )
            z.put(
                "word/document.xml",
                XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                    body + "</w:body></w:document>"
            )
        }
    }

    fun writeXlsx(text: String, out: File) {
        val rows = StringBuilder()
        clean(text).lines().filter { it.isNotBlank() }.forEach { line ->
            rows.append("<row>")
            line.trim().split(Regex("\\t|\\s{2,}")).forEach { cell ->
                rows.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(esc(cell))
                    .append("</t></is></c>")
            }
            rows.append("</row>")
        }
        ZipOutputStream(out.outputStream()).use { z ->
            z.put(
                "[Content_Types].xml",
                XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>"
            )
            z.put(
                "_rels/.rels",
                XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>"
            )
            z.put(
                "xl/workbook.xml",
                XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Scan\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
            )
            z.put(
                "xl/_rels/workbook.xml.rels",
                XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "</Relationships>"
            )
            z.put(
                "xl/worksheets/sheet1.xml",
                XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                    rows + "</sheetData></worksheet>"
            )
        }
    }

    // ---------- Business card parsing ----------

    data class Contact(val name: String, val company: String, val phone: String, val email: String)

    fun parseBusinessCard(text: String): Contact {
        val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(text)?.value ?: ""
        val phone = Regex("(\\+?\\d[\\d\\s().-]{7,}\\d)").find(text)?.value?.trim() ?: ""
        val lines = text.lines().map { it.trim() }.filter {
            it.isNotEmpty() && !it.contains("@") && !it.any { c -> c.isDigit() } && !it.lowercase().startsWith("www")
        }
        return Contact(lines.getOrElse(0) { "" }, lines.getOrElse(1) { "" }, phone, email)
    }
}
