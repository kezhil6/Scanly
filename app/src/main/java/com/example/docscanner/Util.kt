package com.example.docscanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ScanMode(val pageLimit: Int) {
    DOCUMENT(50),
    SINGLE(1)
}

class ScanOutput(val pdfUri: Uri?)

/** Everything the screens need from the activity. */
class Services(
    val context: Context,
    val repo: ScanRepository,
    val run: (String, suspend () -> Unit) -> Unit,
    val scan: (ScanMode, (ScanOutput) -> Unit) -> Unit,
    val reindex: (File) -> Unit
) {
    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    /** Copies the PDF returned by the scanner into a temp file (call off the main thread). */
    fun copyScan(out: ScanOutput): File {
        val uri = out.pdfUri ?: error("No PDF returned")
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read scan")
        val tmp = tmpPdf()
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        return tmp
    }

    fun tmpPdf(): File = File(context.cacheDir, "tmp_${System.nanoTime()}.pdf")

    fun exportDir(): File {
        val d = File(context.cacheDir, "export")
        d.mkdirs()
        d.listFiles()?.forEach { it.delete() }
        return d
    }

    fun share(files: List<File>, mime: String) {
        val uris = ArrayList<Uri>(files.map { repo.uriFor(it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun displayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Returns stored OCR text, running recognition first if there is none. */
suspend fun ensureOcr(repo: ScanRepository, file: File): String = withContext(Dispatchers.IO) {
    val stored = repo.ocrText(file)
    if (stored.isNotEmpty()) {
        stored
    } else {
        val t = PdfTools.recognizePdf(file)
        repo.setOcrText(file, t)
        t
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format("%d KB", bytes / 1024)
    else -> "$bytes B"
}
