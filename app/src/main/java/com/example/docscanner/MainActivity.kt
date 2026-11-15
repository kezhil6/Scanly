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
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    var contactDraft by remember { mutableStateOf<Exporter.Contact?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            if (r != null) {
                val jpegs = r.pages?.map { it.imageUri } ?: emptyList()
                onScanned?.invoke(ScanOutput(r.pdf?.uri, jpegs))
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
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
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
                onOpen = { screen = Screen.Viewer(it) },
                onContact = { contactDraft = it }
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
                onBack = { screen = Screen.Viewer(s.rel) },
                onContact = { contactDraft = it }
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

        contactDraft?.let { c ->
            ContactDialog(c, onDismiss = { contactDraft = null })
        }
    }
}

@Composable
fun ContactDialog(draft: Exporter.Contact, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(draft.name) }
    var company by remember { mutableStateOf(draft.company) }
    var phone by remember { mutableStateOf(draft.phone) }
    var email by remember { mutableStateOf(draft.email) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(company, { company = it }, label = { Text("Company") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val i = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, name)
                    putExtra(ContactsContract.Intents.Insert.COMPANY, company)
                    putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                    putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                }
                try {
                    context.startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) { Text("Open in Contacts") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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

/** Turns a scanner result into a PDF in the library, depending on the scan mode. */
private suspend fun importScan(sv: Services, mode: ScanMode, out: ScanOutput, folder: String): File =
    withContext(Dispatchers.IO) {
        val tmp = sv.tmpPdf()
        when (mode) {
            ScanMode.ID_CARD -> {
                val bmps = out.jpegUris.mapNotNull { PdfTools.decodeScaled(sv.context, it, 2000) }
                if (bmps.isEmpty()) error("No image returned")
                PdfTools.bitmapsToPdf(listOf(PdfTools.composeIdCardPage(bmps)), tmp)
            }
            ScanMode.WHITEBOARD -> {
                val bmps = out.jpegUris.mapNotNull {
                    PdfTools.decodeScaled(sv.context, it, 1600)?.let { b -> PdfTools.enhanceWhiteboard(b) }
                }
                if (bmps.isEmpty()) error("No image returned")
                PdfTools.bitmapsToPdf(bmps, tmp)
            }
            else -> {
                val uri = out.pdfUri ?: error("No PDF returned")
                val input = sv.context.contentResolver.openInputStream(uri) ?: error("Cannot read scan")
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        sv.repo.saveFile(tmp, sv.repo.defaultName(mode.label), folder)
    }

@Composable
fun HomeScreen(
    sv: Services,
    rev: Int,
    onOpen: (String) -> Unit,
    onContact: (Exporter.Contact) -> Unit
) {
    val repo = sv.repo
    val docs = remember(rev) { repo.list() }
    val folders = remember(rev) { repo.folders() }
    val tags = remember(rev) { repo.allTags() }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var showModes by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var favTick by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    var deleteSelected by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<Doc?>(null) }

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

    fun startScan(mode: ScanMode) {
        val folder = if (filter.startsWith("f:")) filter.removePrefix("f:") else ""
        sv.scan(mode) { out ->
            sv.run("Saving scan") {
                val file = importScan(sv, mode, out, folder)
                if (mode == ScanMode.BUSINESS_CARD) {
                    val text = withContext(Dispatchers.IO) {
                        val first = out.jpegUris.firstOrNull()?.let { PdfTools.decodeScaled(sv.context, it, 2000) }
                        if (first != null) PdfTools.recognizeText(first) else ""
                    }
                    if (text.isNotBlank()) onContact(Exporter.parseBusinessCard(text))
                }
                sv.reindex(file)
            }
        }
    }

    val shown = remember(docs, filter, query, favTick) {
        val q = query.trim()
        docs.filter { d ->
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
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showModes = true },
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
                                        IconButton(onClick = { deleteOne = d }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete")
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

    if (showModes) {
        AlertDialog(
            onDismissRequest = { showModes = false },
            title = { Text("New scan") },
            text = {
                Column {
                    ScanMode.values().forEach { m ->
                        ListItem(
                            modifier = Modifier.clickable {
                                showModes = false
                                startScan(m)
                            },
                            headlineContent = { Text(m.label) },
                            supportingContent = { Text(m.hint) }
                        )
                    }
                    ListItem(
                        modifier = Modifier.clickable {
                            showModes = false
                            importLauncher.launch(arrayOf("application/pdf", "image/*"))
                        },
                        headlineContent = { Text("Import PDF or photos") },
                        supportingContent = { Text("From your phone storage") }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showModes = false }) { Text("Cancel") } }
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
