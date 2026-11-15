package com.example.docscanner

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Doc(
    val file: File,
    val rel: String,
    val folder: String,
    val tags: List<String>,
    val favorite: Boolean,
    val hasOcr: Boolean
) {
    val name: String get() = file.nameWithoutExtension
}

/**
 * Stores PDFs under filesDir/scans (one level of sub-folders).
 * Tags, favorites and OCR text live in filesDir/index.json keyed by relative path.
 */
class ScanRepository(private val context: Context) {

    val root: File = File(context.filesDir, "scans").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "index.json")
    private var index: JSONObject = try {
        JSONObject(indexFile.readText())
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save() {
        try {
            indexFile.writeText(index.toString())
        } catch (_: Exception) {
        }
    }

    private fun entry(rel: String): JSONObject {
        var e = index.optJSONObject(rel)
        if (e == null) {
            e = JSONObject()
            index.put(rel, e)
        }
        return e
    }

    fun relOf(f: File): String = f.relativeTo(root).path

    // ---------- Listing ----------

    fun folders(): List<String> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).map { it.name }.sorted()

    fun list(): List<Doc> =
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .map { toDoc(it) }
            .sortedByDescending { it.file.lastModified() }
            .toList()

    fun find(rel: String): Doc? {
        val f = File(root, rel)
        return if (f.exists()) toDoc(f) else null
    }

    private fun toDoc(f: File): Doc {
        val r = relOf(f)
        val e = index.optJSONObject(r)
        val tags = ArrayList<String>()
        val arr = e?.optJSONArray("tags")
        if (arr != null) {
            for (i in 0 until arr.length()) tags.add(arr.getString(i))
        }
        val parent = f.parentFile
        val folder = if (parent != null && parent != root) parent.name else ""
        val ocr = e?.optString("ocr", "") ?: ""
        return Doc(f, r, folder, tags, e?.optBoolean("fav", false) ?: false, ocr.isNotEmpty())
    }

    fun allTags(): List<String> =
        list().flatMap { it.tags }.distinct().sorted()

    // ---------- Folders ----------

    private fun cleanName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()

    fun createFolder(name: String): Boolean {
        val n = cleanName(name)
        if (n.isEmpty()) return false
        val d = File(root, n)
        return d.exists() || d.mkdirs()
    }

    /** Deletes the folder; documents inside move back to the main list. */
    fun deleteFolder(name: String) {
        val d = File(root, name)
        d.listFiles()?.forEach { f -> if (f.isFile) move(f, "") }
        d.delete()
    }

    private fun dirFor(folder: String): File =
        if (folder.isEmpty()) root else File(root, folder).apply { mkdirs() }

    // ---------- Files ----------

    fun newFile(base: String, folder: String): File {
        val dir = dirFor(folder)
        val b = cleanName(base).ifEmpty { "Scan" }
        var f = File(dir, "$b.pdf")
        var n = 1
        while (f.exists()) {
            f = File(dir, "$b ($n).pdf")
            n++
        }
        return f
    }

    fun defaultName(prefix: String = "Scan"): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault()).format(Date())
        return "$prefix $stamp"
    }

    fun saveFromUri(uri: Uri, name: String = defaultName(), folder: String = ""): File? {
        val out = newFile(name, folder)
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    /** Moves a finished temp file into the library. */
    fun saveFile(tmp: File, name: String = defaultName(), folder: String = ""): File {
        val out = newFile(name, folder)
        tmp.copyTo(out, overwrite = true)
        tmp.delete()
        return out
    }

    /** Replaces the content of an existing document (keeps tags, favorite, resets OCR). */
    fun replaceContent(file: File, tmp: File) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
        index.optJSONObject(relOf(file))?.remove("ocr")
        save()
    }

    fun rename(file: File, newName: String): File? {
        val base = cleanName(newName).removeSuffix(".pdf")
        if (base.isBlank()) return null
        val target = File(file.parentFile, "$base.pdf")
        if (target.exists() && target != file) return null
        val old = relOf(file)
        if (!file.renameTo(target)) return null
        moveEntry(old, relOf(target))
        return target
    }

    fun move(file: File, folder: String): File? {
        val dir = dirFor(folder)
        if (file.parentFile == dir) return file
        val target = newFile(file.nameWithoutExtension, folder)
        val old = relOf(file)
        if (!file.renameTo(target)) {
            // Fall back to copy+delete
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        moveEntry(old, relOf(target))
        return target
    }

    private fun moveEntry(oldRel: String, newRel: String) {
        val e = index.optJSONObject(oldRel) ?: return
        index.remove(oldRel)
        index.put(newRel, e)
        save()
    }

    fun delete(file: File): Boolean {
        index.remove(relOf(file))
        save()
        return file.delete()
    }

    // ---------- Metadata ----------

    fun setTags(file: File, tags: List<String>) {
        val arr = JSONArray()
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { arr.put(it) }
        entry(relOf(file)).put("tags", arr)
        save()
    }

    fun setFavorite(file: File, fav: Boolean) {
        entry(relOf(file)).put("fav", fav)
        save()
    }

    fun ocrText(file: File): String =
        index.optJSONObject(relOf(file))?.optString("ocr", "") ?: ""

    fun setOcrText(file: File, text: String) {
        entry(relOf(file)).put("ocr", text)
        save()
    }

    // ---------- Sharing ----------

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
