@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.docscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Something drawn on the page. Coordinates are fractions (0..1) of the page size. */
sealed class Mark {
    class Stroke(val pts: MutableList<Offset>, val color: Int, val width: Float, val alpha: Int) : Mark()
    class TextM(val pos: Offset, val text: String, val color: Int, val size: Float) : Mark()
    class Sig(val center: Offset, val width: Float, val bmp: Bitmap) : Mark()
}

/** Draws marks onto any android Canvas (used for both the preview and the saved overlay). */
fun renderMarks(canvas: android.graphics.Canvas, w: Float, h: Float, marks: List<Mark>) {
    val paint = Paint()
    for (m in marks) {
        when (m) {
            is Mark.Stroke -> {
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = m.color
                paint.alpha = m.alpha
                paint.strokeWidth = m.width * w
                val path = Path()
                m.pts.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h)
                }
                if (m.pts.size == 1) path.lineTo(m.pts[0].x * w + 0.1f, m.pts[0].y * h)
                canvas.drawPath(path, paint)
            }
            is Mark.TextM -> {
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = m.color
                paint.textSize = m.size * w
                canvas.drawText(m.text, m.pos.x * w, m.pos.y * h, paint)
            }
            is Mark.Sig -> {
                val sw = m.width * w
                val sh = sw * m.bmp.height / m.bmp.width
                val cx = m.center.x * w
                val cy = m.center.y * h
                canvas.drawBitmap(m.bmp, null, RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2), null)
            }
        }
    }
}

private enum class Tool { PEN, HIGHLIGHT, TEXT, SIGN }

private val palette = listOf(Color.Black, Color(0xFF1565C0), Color(0xFFC62828), Color(0xFF2E7D32))

@Composable
fun AnnotateScreen(
    sv: Services,
    file: File,
    pageIndex: Int,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val sigFile = remember { File(context.filesDir, "signature.png") }

    val pageBmp by produceState<Bitmap?>(null, file.path, pageIndex) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfTools.renderPage(file, pageIndex, 1080)
            } catch (e: Throwable) {
                null
            }
        }
    }

    val marks = remember { mutableStateListOf<Mark>() }
    var tick by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(Color.Black) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var signature by remember {
        mutableStateOf<Bitmap?>(if (sigFile.exists()) BitmapFactory.decodeFile(sigFile.path) else null)
    }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var signAt by remember { mutableStateOf<Offset?>(null) }
    var showSigPad by remember { mutableStateOf(false) }

    fun norm(o: Offset) = Offset(
        (o.x / size.width).coerceIn(0f, 1f),
        (o.y / size.height).coerceIn(0f, 1f)
    )

    fun placeSignature(at: Offset) {
        val s = signature
        if (s != null) {
            marks.add(Mark.Sig(at, 0.3f, s))
            tick++
        }
    }

    fun save() {
        val bmp = pageBmp ?: return
        sv.run("Saving") {
            withContext(Dispatchers.IO) {
                if (PdfTools.pageRotation(file, pageIndex) != 0) {
                    error("This page is rotated. Rotate it back to its original position before annotating.")
                }
                val ow = 1600
                val oh = (ow.toFloat() / bmp.width * bmp.height).toInt()
                val overlay = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                renderMarks(android.graphics.Canvas(overlay), ow.toFloat(), oh.toFloat(), marks.toList())
                val tmp = sv.tmpPdf()
                PdfTools.applyOverlay(file, tmp, pageIndex, overlay)
                sv.repo.replaceContent(file, tmp)
            }
            sv.reindex(file)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page ${pageIndex + 1}") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (marks.isNotEmpty()) {
                            marks.removeAt(marks.size - 1)
                            tick++
                        }
                    }) { Icon(Icons.Default.Undo, contentDescription = "Undo") }
                    IconButton(onClick = { save() }, enabled = marks.isNotEmpty()) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = tool == Tool.PEN, onClick = { tool = Tool.PEN },
                    label = { Text("Pen") },
                    leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = tool == Tool.HIGHLIGHT, onClick = { tool = Tool.HIGHLIGHT },
                    label = { Text("Highlight") },
                    leadingIcon = { Icon(Icons.Default.Highlight, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = tool == Tool.TEXT, onClick = { tool = Tool.TEXT },
                    label = { Text("Text") },
                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = tool == Tool.SIGN, onClick = { tool = Tool.SIGN },
                    label = { Text("Sign") },
                    leadingIcon = { Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                palette.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(c, CircleShape)
                            .border(
                                if (color == c) 3.dp else 1.dp,
                                if (color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                CircleShape
                            )
                            .clickable { color = c }
                    )
                }
                if (tool == Tool.SIGN) {
                    TextButton(onClick = { showSigPad = true }) { Text("New signature") }
                }
            }
            Text(
                when (tool) {
                    Tool.PEN -> "Draw with your finger."
                    Tool.HIGHLIGHT -> "Drag over text to highlight."
                    Tool.TEXT -> "Tap where the text should go."
                    Tool.SIGN -> if (signature == null) "Tap the page to draw your signature." else "Tap where your signature should go."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                val b = pageBmp
                if (b == null) {
                    Text("Loading page…")
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(b.width.toFloat() / b.height)
                            .onSizeChanged { size = it }
                    ) {
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                        val gestures = when (tool) {
                            Tool.PEN, Tool.HIGHLIGHT -> Modifier.pointerInput(tool, color) {
                                var current: Mark.Stroke? = null
                                detectDragGestures(
                                    onDragStart = { o ->
                                        val highlight = tool == Tool.HIGHLIGHT
                                        val c = if (highlight && color == Color.Black) Color(0xFFFFEB3B) else color
                                        val s = Mark.Stroke(
                                            mutableListOf(norm(o)),
                                            c.toArgb(),
                                            if (highlight) 0.03f else 0.006f,
                                            if (highlight) 110 else 255
                                        )
                                        current = s
                                        marks.add(s)
                                        tick++
                                    },
                                    onDrag = { change, _ ->
                                        current?.pts?.add(norm(change.position))
                                        tick++
                                    },
                                    onDragEnd = { current = null },
                                    onDragCancel = { current = null }
                                )
                            }
                            Tool.TEXT, Tool.SIGN -> Modifier.pointerInput(tool, signature) {
                                detectTapGestures { o ->
                                    val p = norm(o)
                                    if (tool == Tool.TEXT) {
                                        textAt = p
                                    } else if (signature == null) {
                                        signAt = p
                                        showSigPad = true
                                    } else {
                                        placeSignature(p)
                                    }
                                }
                            }
                        }
                        Canvas(modifier = Modifier.fillMaxSize().then(gestures)) {
                            val t = tick // redraw when marks change
                            if (t >= 0) {
                                drawIntoCanvas { c ->
                                    renderMarks(c.nativeCanvas, this.size.width, this.size.height, marks)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    textAt?.let { pos ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text("Add text") },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    marks.add(Mark.TextM(pos, text, color.toArgb(), 0.04f))
                    tick++
                    textAt = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text("Cancel") } }
        )
    }

    if (showSigPad) {
        SignaturePad(
            onDismiss = { showSigPad = false; signAt = null },
            onDone = { bmp ->
                try {
                    sigFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Exception) {
                }
                signature = bmp
                showSigPad = false
                signAt?.let { at ->
                    marks.add(Mark.Sig(at, 0.3f, bmp))
                    tick++
                }
                signAt = null
            }
        )
    }
}

@Composable
private fun SignaturePad(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var tick by remember { mutableIntStateOf(0) }
    var padSize by remember { mutableStateOf(IntSize(1, 1)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw your signature") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .border(1.dp, Color.Gray)
                    .onSizeChanged { padSize = it }
                    .pointerInput(Unit) {
                        var current: MutableList<Offset>? = null
                        detectDragGestures(
                            onDragStart = { o ->
                                val s = mutableListOf(o)
                                current = s
                                strokes.add(s)
                                tick++
                            },
                            onDrag = { change, _ ->
                                current?.add(change.position)
                                tick++
                            },
                            onDragEnd = { current = null },
                            onDragCancel = { current = null }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val t = tick
                    if (t >= 0) {
                        drawIntoCanvas { c ->
                            val p = Paint().apply {
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = 6f
                                color = android.graphics.Color.BLACK
                            }
                            strokes.forEach { s ->
                                val path = Path()
                                s.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                                c.nativeCanvas.drawPath(path, p)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val w = 600
                val h = (w.toFloat() * padSize.height / padSize.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                val scale = w.toFloat() / padSize.width
                val p = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = 6f * scale
                    color = android.graphics.Color.BLACK
                }
                strokes.forEach { s ->
                    val path = Path()
                    s.forEachIndexed { i, o ->
                        if (i == 0) path.moveTo(o.x * scale, o.y * scale) else path.lineTo(o.x * scale, o.y * scale)
                    }
                    canvas.drawPath(path, p)
                }
                onDone(bmp)
            }) { Text("Use") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear(); tick++ }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
