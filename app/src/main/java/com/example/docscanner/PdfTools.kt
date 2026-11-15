package com.example.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** All PDF / image processing. Call from a background thread. */
object PdfTools {

    fun init(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    // ---------- Rendering ----------

    fun pageCount(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            return r.pageCount
        } finally {
            r.close()
            pfd.close()
        }
    }

    fun renderPage(file: File, index: Int, targetWidth: Int): Bitmap {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            val p = r.openPage(index)
            try {
                val w = targetWidth
                val h = (p.height.toFloat() / p.width * w).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            } finally {
                p.close()
            }
        } finally {
            r.close()
            pfd.close()
        }
    }

    // ---------- Building PDFs from images ----------

    fun bitmapsToPdf(bitmaps: List<Bitmap>, out: File) {
        val doc = PdfDocument()
        try {
            bitmaps.forEachIndexed { i, b ->
                val info = PdfDocument.PageInfo.Builder(b.width, b.height, i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(b, 0f, 0f, null)
                doc.finishPage(page)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    fun decodeScaled(context: Context, uri: Uri, maxDim: Int = 2200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val s = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    }

    /** Places one or two ID-card sides on a single A4 page. */
    fun composeIdCardPage(cards: List<Bitmap>): Bitmap {
        val w = 1240
        val h = 1754
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 160f
        for (card in cards.take(2)) {
            var cw = 1000f
            var ch = card.height.toFloat() / card.width * cw
            val maxH = 720f
            if (ch > maxH) {
                cw = cw * maxH / ch
                ch = maxH
            }
            val left = (w - cw) / 2f
            c.drawBitmap(card, null, RectF(left, y, left + cw, y + ch), paint)
            y += ch + 120f
        }
        return page
    }

    /** Boosts contrast / brightness so whiteboards and faded pages read clearly. */
    fun enhanceWhiteboard(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val contrast = 1.7f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 25f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // ---------- Page operations (lossless, via PDFBox) ----------

    /**
     * Builds [dst] from the pages of [src] in the given [order] (0-based indices).
     * Used for delete, reorder, split/extract and rotate.
     * [extraRotation] maps source page index -> degrees to add (90, 180, 270).
     */
    fun editPages(src: File, dst: File, order: List<Int>, extraRotation: Map<Int, Int> = emptyMap()) {
        val source = PDDocument.load(src)
        val out = PDDocument()
        try {
            for (idx in order) {
                val imported = out.importPage(source.getPage(idx))
                val add = extraRotation[idx] ?: 0
                if (add != 0) {
                    imported.setRotation((imported.getRotation() + add) % 360)
                }
            }
            out.save(dst)
        } finally {
            out.close()
            source.close()
        }
    }

    fun merge(files: List<File>, dst: File) {
        val out = PDDocument()
        val sources = ArrayList<PDDocument>()
        try {
            for (f in files) {
                val d = PDDocument.load(f)
                sources.add(d)
                for (i in 0 until d.getNumberOfPages()) out.importPage(d.getPage(i))
            }
            out.save(dst)
        } finally {
            out.close()
            sources.forEach { it.close() }
        }
    }

    /** Re-renders every page as JPEG to shrink big scans. */
    fun compress(src: File, dst: File, widthPx: Int, quality: Float) {
        val n = pageCount(src)
        val doc = PDDocument()
        try {
            for (i in 0 until n) {
                val bmp = renderPage(src, i, widthPx)
                val pw = 595f
                val ph = pw * bmp.height / bmp.width
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                val img = JPEGFactory.createFromImage(doc, bmp, quality)
                val cs = PDPageContentStream(doc, page)
                try {
                    cs.drawImage(img, 0f, 0f, pw, ph)
                } finally {
                    cs.close()
                }
                bmp.recycle()
            }
            doc.save(dst)
        } finally {
            doc.close()
        }
    }

    fun protect(src: File, dst: File, password: String) {
        val doc = PDDocument.load(src)
        try {
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.setEncryptionKeyLength(128)
            doc.protect(policy)
            doc.save(dst)
        } finally {
            doc.close()
        }
    }

    /** Throws if the password is wrong. */
    fun unprotect(src: File, dst: File, password: String) {
        val doc = PDDocument.load(src, password)
        try {
            doc.setAllSecurityToBeRemoved(true)
            doc.save(dst)
        } finally {
            doc.close()
        }
    }

    /** Draws a transparent [overlay] (annotations, signatures) on top of one page. */
    fun applyOverlay(src: File, dst: File, pageIndex: Int, overlay: Bitmap) {
        val doc = PDDocument.load(src)
        try {
            val page = doc.getPage(pageIndex)
            val box = page.getMediaBox()
            val img = LosslessFactory.createFromImage(doc, overlay)
            val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
            try {
                cs.drawImage(img, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight())
            } finally {
                cs.close()
            }
            doc.save(dst)
        } finally {
            doc.close()
        }
    }

    fun pageRotation(src: File, pageIndex: Int): Int {
        val doc = PDDocument.load(src)
        try {
            return doc.getPage(pageIndex).getRotation()
        } finally {
            doc.close()
        }
    }

    // ---------- Export ----------

    fun exportJpgs(src: File, outDir: File, baseName: String): List<File> {
        outDir.mkdirs()
        val n = pageCount(src)
        val files = ArrayList<File>()
        for (i in 0 until n) {
            val bmp = renderPage(src, i, 1654)
            val f = File(outDir, "${baseName}_${i + 1}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            files.add(f)
        }
        return files
    }

    // ---------- OCR ----------

    fun recognizeText(bitmap: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(rec.process(InputImage.fromBitmap(bitmap, 0)))
            return result.text
        } finally {
            rec.close()
        }
    }

    fun recognizePdf(file: File): String {
        val n = pageCount(file)
        val sb = StringBuilder()
        for (i in 0 until n) {
            val bmp = renderPage(file, i, 1600)
            try {
                val t = recognizeText(bmp).trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t)
                }
            } finally {
                bmp.recycle()
            }
        }
        return sb.toString()
    }

    // ---------- Misc ----------

    /** "1-3, 5, 7-8" -> [0,1,2,4,6,7]; null if invalid. */
    fun parsePageRanges(text: String, pageCount: Int): List<Int>? {
        val result = ArrayList<Int>()
        for (part in text.split(",")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val bits = p.split("-").map { it.trim() }
            if (bits.size == 1) {
                val a = bits[0].toIntOrNull() ?: return null
                if (a < 1 || a > pageCount) return null
                result.add(a - 1)
            } else if (bits.size == 2) {
                val a = bits[0].toIntOrNull() ?: return null
                val b = bits[1].toIntOrNull() ?: return null
                if (a < 1 || b > pageCount || a > b) return null
                for (x in a..b) result.add(x - 1)
            } else {
                return null
            }
        }
        return if (result.isEmpty()) null else result
    }

    fun bitmapToPngBytes(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, o)
        return o.toByteArray()
    }
}
