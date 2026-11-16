@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.docscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class Screen {
    object Home : Screen()
    data class Viewer(val rel: String) : Screen()
    data class Annotate(val rel: String, val page: Int) : Screen()
    data class Text(val rel: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PdfTools.init(this)
        val prefs = getSharedPreferences("scanly", MODE_PRIVATE)
        PdfTools.ocrLang = try {
            OcrLang.valueOf(prefs.getString("ocr_lang", "LATIN") ?: "LATIN")
        } catch (e: Exception) {
            OcrLang.LATIN
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(
                colorScheme = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                    dark -> darkColorScheme()
                    else -> lightColorScheme()
                }
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScannerApp()
                }
            }
        }
    }
}

@Composable
fun ScannerApp() {
    val context = LocalContext.current
    val activity = context as Activity
    val repo = remember { ScanRepository(context) }
    val scope = rememberCoroutineScope()

    var rev by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var busy by remember { mutableStateOf<String?>(null) }
    var onScanned by remember { mutableStateOf<((ScanOutput) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            if (r != null) {
                onScanned?.invoke(ScanOutput(r.pdf?.uri))
            }
        }
    }

    val run: (String, suspend () -> Unit) -> Unit = { label, block ->
        scope.launch {
            busy = label
            try {
                block()
            } catch (e: Throwable) {
                Toast.makeText(
                    context,
                    "Failed: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                busy = null
                rev++
            }
        }
    }

    val reindex: (File) -> Unit = { f ->
        scope.launch(Dispatchers.IO) {
            try {
                repo.setOcrText(f, PdfTools.recognizePdf(f))
            } catch (_: Throwable) {
            }
            rev++
        }
    }

    val scan: (ScanMode, (ScanOutput) -> Unit) -> Unit = { mode, cb ->
        onScanned = cb
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(mode.pageLimit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Scanner unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    val sv = remember { Services(context, repo, run, scan, reindex) }

    BackHandler(enabled = screen !is Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Annotate -> Screen.Viewer(s.rel)
            is Screen.Text -> Screen.Viewer(s.rel)
            else -> Screen.Home
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = screen) {
            is Screen.Home -> HomeScreen(
                sv = sv,
                rev = rev,
                onOpen = { screen = Screen.Viewer(it) }
            )
            is Screen.Viewer -> {
                val file = File(repo.root, s.rel)
                if (!file.exists()) {
                    LaunchedEffect(s.rel) { screen = Screen.Home }
                } else {
                    ViewerScreen(
                        sv = sv,
                        rev = rev,
                        file = file,
                        onBack = { screen = Screen.Home },
                        onRelChange = { screen = Screen.Viewer(it) },
                        onAnnotate = { screen = Screen.Annotate(s.rel, it) },
                        onText = { screen = Screen.Text(s.rel) }
                    )
                }
            }
            is Screen.Annotate -> AnnotateScreen(
                sv = sv,
                file = File(repo.root, s.rel),
                pageIndex = s.page,
                onDone = { screen = Screen.Viewer(s.rel) }
            )
            is Screen.Text -> TextScreen(
                sv = sv,
                file = File(repo.root, s.rel),
                onBack = { screen = Screen.Viewer(s.rel) }
            )
        }

        if (busy != null) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(busy ?: "") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("  Please wait…")
                    }
                }
            )
        }
    }
}

@Composable
fun Thumb(file: File, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfTools.renderPage(file, 0, 240)
            } catch (e: Throwable) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
        }
    }
}

/** Saves the PDF returned by the scanner into the library. */
private suspend fun importScan(sv: Services, out: ScanOutput, folder: String): File =
    withContext(Dispatchers.IO) {
        val tmp = sv.tmpPdf()
        val uri = out.pdfUri ?: error("No PDF returned")
        val input = sv.context.contentResolver.openInputStream(uri) ?: error("Cannot read scan")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        sv.repo.saveFile(tmp, sv.repo.defaultName("Scan"), folder)
    }

@Composable
fun HomeScreen(
    sv: Services,
    rev: Int,
    onOpen: (String) -> Unit
) {
    val repo = sv.repo
    val docs = remember(rev) { repo.list() }
    val folders = remember(rev) { repo.folders() }
    val tags = remember(rev) { repo.allTags() }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var showNewFolder by remember { mutableStateOf(false) }
    var favTick by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    var deleteSelected by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<Doc?>(null) }
    var homeMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var renameDoc by remember { mutableStateOf<Doc?>(null) }
    var sort by remember { mutableStateOf("date") }
    var showLang by remember { mutableStateOf(false) }
    var langTick by remember { mutableIntStateOf(0) }

    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val folder = if (filter.startsWith("f:")) filter.removePrefix("f:") else ""
            sv.run("Importing") {
                val files = withContext(Dispatchers.IO) {
                    val result = ArrayList<File>()
                    val images = ArrayList<Bitmap>()
                    for (u in uris) {
                        val type = sv.context.contentResolver.getType(u) ?: ""
                        val isPdf = type == "application/pdf" ||
                            (sv.displayName(u)?.endsWith(".pdf", ignoreCase = true) == true)
                        if (isPdf) {
                            val base = sv.displayName(u)?.removeSuffix(".pdf") ?: sv.repo.defaultName("Import")
                            sv.repo.saveFromUri(u, base, folder)?.let { result.add(it) }
                        } else if (type.startsWith("image/")) {
                            PdfTools.decodeScaled(sv.context, u, 2200)?.let { images.add(it) }
                        }
                    }
                    if (images.isNotEmpty()) {
                        val tmp = sv.tmpPdf()
                        PdfTools.bitmapsToPdf(images, tmp)
                        result.add(sv.repo.saveFile(tmp, sv.repo.defaultName("Import"), folder))
                    }
                    result
                }
                files.forEach { sv.reindex(it) }
                if (files.isEmpty()) sv.toast("Nothing imported")
            }
        }
    }

    fun startScan() {
        val folder = if (filter.startsWith("f:")) filter.removePrefix("f:") else ""
        sv.scan(ScanMode.DOCUMENT) { out ->
            sv.run("Saving scan") {
                val file = importScan(sv, out, folder)
                sv.reindex(file)
            }
        }
    }

    val shown = remember(docs, filter, query, favTick, sort) {
        val q = query.trim()
        val filtered = docs.filter { d ->
            val okFilter = when {
                filter == "all" -> true
                filter == "fav" -> d.favorite
                filter.startsWith("f:") -> d.folder == filter.removePrefix("f:")
                filter.startsWith("t:") -> filter.removePrefix("t:") in d.tags
                else -> true
            }
            okFilter && (q.isEmpty() ||
                d.name.contains(q, ignoreCase = true) ||
                d.folder.contains(q, ignoreCase = true) ||
                d.tags.any { it.contains(q, ignoreCase = true) } ||
                (d.hasOcr && repo.ocrText(d.file).contains(q, ignoreCase = true)))
        }
        when (sort) {
            "name" -> filtered.sortedBy { it.name.lowercase() }
            "size" -> filtered.sortedByDescending { it.file.length() }
            else -> filtered
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selected.isNotEmpty()) {
                        Text("${selected.size} selected")
                    } else if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search name, tag or text") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Scanly")
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { deleteSelected = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                        IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    } else {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) query = ""
                        }) {
                            Icon(
                                if (searching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                        IconButton(onClick = { homeMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = homeMenu, onDismissRequest = { homeMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sort: newest first" + if (sort == "date") "  ✓" else "") },
                                onClick = { sort = "date"; homeMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: name A–Z" + if (sort == "name") "  ✓" else "") },
                                onClick = { sort = "name"; homeMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: largest first" + if (sort == "size") "  ✓" else "") },
                                onClick = { sort = "size"; homeMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Import PDF or photos…") },
                                onClick = {
                                    homeMenu = false
                                    importLauncher.launch(arrayOf("application/pdf", "image/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Text recognition language…") },
                                onClick = { homeMenu = false; showLang = true }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { startScan() },
                icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                text = { Text("Scan") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All") })
                }
                item {
                    FilterChip(selected = filter == "fav", onClick = { filter = "fav" }, label = { Text("Favorites") })
                }
                items(folders) { f ->
                    FilterChip(
                        selected = filter == "f:$f",
                        onClick = { filter = "f:$f" },
                        label = { Text(f) }
                    )
                }
                items(tags) { t ->
                    FilterChip(
                        selected = filter == "t:$t",
                        onClick = { filter = "t:$t" },
                        label = { Text("#$t") }
                    )
                }
                item {
                    AssistChip(onClick = { showNewFolder = true }, label = { Text("+ Folder") })
                }
            }

            if (filter.startsWith("f:")) {
                TextButton(
                    onClick = {
                        repo.deleteFolder(filter.removePrefix("f:"))
                        filter = "all"
                        sv.run("Updating") { }
                    },
                    modifier = Modifier.padding(start = 4.dp)
                ) { Text("Delete this folder (scans move to All)") }
            }

            if (shown.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                    Text(
                        if (docs.isEmpty()) "No scans yet" else "Nothing found",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (docs.isEmpty()) {
                        Text(
                            "Tap Scan to capture a document, or import a PDF or photos.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.rel }) { d ->
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        if (d.rel in selected) selected.remove(d.rel) else selected.add(d.rel)
                                    } else {
                                        onOpen(d.rel)
                                    }
                                },
                                onLongClick = { if (d.rel !in selected) selected.add(d.rel) }
                            ),
                            leadingContent = { Thumb(d.file, Modifier.size(48.dp, 64.dp)) },
                            headlineContent = { Text(d.name, maxLines = 1) },
                            supportingContent = {
                                val date = DateUtils.getRelativeTimeSpanString(d.file.lastModified())
                                val extra = buildString {
                                    if (d.folder.isNotEmpty()) append(" · ${d.folder}")
                                    if (d.tags.isNotEmpty()) append(" · " + d.tags.joinToString(" ") { "#$it" })
                                }
                                Text("$date · ${formatSize(d.file.length())}$extra", maxLines = 2)
                            },
                            trailingContent = {
                                if (selected.isNotEmpty()) {
                                    Checkbox(checked = d.rel in selected, onCheckedChange = null)
                                } else {
                                    Row {
                                        IconButton(onClick = {
                                            repo.setFavorite(d.file, !d.favorite)
                                            sv.run("Updating") { }
                                            favTick++
                                        }) {
                                            Icon(
                                                if (d.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                                contentDescription = "Favorite"
                                            )
                                        }
                                        IconButton(onClick = { sv.share(listOf(d.file), "application/pdf") }) {
                                            Icon(Icons.Default.Share, contentDescription = "Share")
                                        }
                                        Box {
                                            IconButton(onClick = { rowMenu = d.rel }) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                                            }
                                            DropdownMenu(
                                                expanded = rowMenu == d.rel,
                                                onDismissRequest = { rowMenu = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    onClick = { rowMenu = null; renameDoc = d }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete") },
                                                    onClick = { rowMenu = null; deleteOne = d }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    renameDoc?.let { d ->
        var text by remember(d.rel) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renameDoc = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (repo.rename(d.file, text) == null) sv.toast("Invalid or duplicate name")
                    renameDoc = null
                    sv.run("Updating") { }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameDoc = null }) { Text("Cancel") } }
        )
    }

    deleteOne?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteOne = null },
            title = { Text("Delete scan?") },
            text = { Text("\"${d.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(d.file)
                    deleteOne = null
                    selected.remove(d.rel)
                    sv.run("Deleting") { }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteOne = null }) { Text("Cancel") } }
        )
    }

    if (deleteSelected) {
        AlertDialog(
            onDismissRequest = { deleteSelected = false },
            title = { Text("Delete ${selected.size} scans?") },
            text = { Text("They will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    selected.toList().forEach { rel -> repo.delete(File(repo.root, rel)) }
                    selected.clear()
                    deleteSelected = false
                    sv.run("Deleting") { }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteSelected = false }) { Text("Cancel") } }
        )
    }

    if (showLang) {
        val ctx = sv.context
        AlertDialog(
            onDismissRequest = { showLang = false },
            title = { Text("Text recognition language") },
            text = {
                Column {
                    Text("Used for searching, copying and exporting text. Re-scan text on a scan to apply a new language.")
                    OcrLang.values().forEach { l ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                PdfTools.ocrLang = l
                                ctx.getSharedPreferences("scanly", android.content.Context.MODE_PRIVATE)
                                    .edit().putString("ocr_lang", l.name).apply()
                                langTick++
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = PdfTools.ocrLang == l && langTick >= 0, onClick = null)
                            Text("  " + l.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLang = false }) { Text("Done") } }
        )
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (repo.createFolder(name)) {
                        filter = "f:" + name.trim()
                        sv.run("Updating") { }
                    }
                    showNewFolder = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } }
        )
    }
}
