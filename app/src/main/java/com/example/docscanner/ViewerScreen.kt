@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.docscanner

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed class PageInfo {
    object Loading : PageInfo()
    object Locked : PageInfo()
    class Ok(val count: Int) : PageInfo()
    class Error(val message: String) : PageInfo()
}

@Composable
fun ViewerScreen(
    sv: Services,
    rev: Int,
    file: File,
    onBack: () -> Unit,
    onRelChange: (String) -> Unit,
    onAnnotate: (Int) -> Unit,
    onText: () -> Unit
) {
    val repo = sv.repo
    var reload by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            sv.run("Saving copy") {
                withContext(Dispatchers.IO) {
                    val out = sv.context.contentResolver.openOutputStream(uri) ?: error("Cannot write there")
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                }
                sv.toast("Saved")
            }
        }
    }

    val doc = remember(rev, reload, file.path) { repo.find(repo.relOf(file)) }
    val name = file.nameWithoutExtension

    val info by produceState<PageInfo>(PageInfo.Loading, file.path, file.lastModified(), reload) {
        value = withContext(Dispatchers.IO) {
            try {
                PageInfo.Ok(PdfTools.pageCount(file))
            } catch (e: SecurityException) {
                PageInfo.Locked
            } catch (e: Throwable) {
                PageInfo.Error(e.message ?: "Cannot open this PDF")
            }
        }
    }
    val count = (info as? PageInfo.Ok)?.count ?: 0

    fun pageOp(order: List<Int>, rot: Map<Int, Int> = emptyMap()) {
        sv.run("Updating pages") {
            withContext(Dispatchers.IO) {
                val tmp = sv.tmpPdf()
                PdfTools.editPages(file, tmp, order, rot)
                repo.replaceContent(file, tmp)
            }
            sv.reindex(file)
            reload++
        }
    }

    fun exportText(kind: String) {
        sv.run("Preparing export") {
            val text = ensureOcr(repo, file)
            if (text.isBlank()) {
                sv.toast("No text found in this document")
            } else {
                val out = withContext(Dispatchers.IO) {
                    val dir = sv.exportDir()
                    when (kind) {
                        "docx" -> File(dir, "$name.docx").also { Exporter.writeDocx(text, it) }
                        "xlsx" -> File(dir, "$name.xlsx").also { Exporter.writeXlsx(text, it) }
                        else -> File(dir, "$name.txt").also { Exporter.writeTxt(text, it) }
                    }
                }
                val mime = when (kind) {
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "text/plain"
                }
                sv.share(listOf(out), mime)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        repo.setFavorite(file, !(doc?.favorite ?: false))
                        reload++
                    }) {
                        Icon(
                            if (doc?.favorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite"
                        )
                    }
                    IconButton(onClick = onText) {
                        Icon(Icons.Default.TextFields, contentDescription = "Text (OCR)")
                    }
                    IconButton(onClick = { sv.share(listOf(file), "application/pdf") }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val items = listOf(
                            "rename" to "Rename",
                            "tags" to "Tags",
                            "move" to "Move to folder",
                            "savecopy" to "Save a copy to phone / Drive…",
                            "addpages" to "Add pages (scan)",
                            "merge" to "Merge with other scans",
                            "split" to "Extract / split pages",
                            "compress" to "Compress",
                            "searchable" to "Make searchable PDF (copy)",
                            "protect" to "Password protect (copy)",
                            "unlock" to "Remove password (copy)",
                            "jpg" to "Export as JPG images",
                            "docx" to "Export as Word (.docx)",
                            "xlsx" to "Export as Excel (.xlsx)",
                            "txt" to "Export as text (.txt)",
                            "delete" to "Delete"
                        )
                        items.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menu = false
                                    when (key) {
                                        "addpages" -> sv.scan(ScanMode.DOCUMENT) { out ->
                                            sv.run("Adding pages") {
                                                withContext(Dispatchers.IO) {
                                                    val uri = out.pdfUri ?: error("No PDF returned")
                                                    val add = sv.tmpPdf()
                                                    val input = sv.context.contentResolver.openInputStream(uri)
                                                        ?: error("Cannot read scan")
                                                    input.use { i -> add.outputStream().use { o -> i.copyTo(o) } }
                                                    val merged = sv.tmpPdf()
                                                    PdfTools.merge(listOf(file, add), merged)
                                                    repo.replaceContent(file, merged)
                                                    add.delete()
                                                }
                                                sv.reindex(file)
                                                reload++
                                            }
                                        }
                                        "savecopy" -> saveLauncher.launch("$name.pdf")
                                        "searchable" -> sv.run("Adding text layer") {
                                            val saved = withContext(Dispatchers.IO) {
                                                val tmp = sv.tmpPdf()
                                                PdfTools.makeSearchable(file, tmp)
                                                repo.saveFile(tmp, "$name (searchable)", doc?.folder ?: "")
                                            }
                                            sv.reindex(saved)
                                            sv.toast("Searchable copy saved")
                                        }
                                        "jpg" -> sv.run("Exporting images") {
                                            val files = withContext(Dispatchers.IO) {
                                                PdfTools.exportJpgs(file, sv.exportDir(), name)
                                            }
                                            sv.share(files, "image/jpeg")
                                        }
                                        "docx", "xlsx", "txt" -> exportText(key)
                                        else -> {
                                            dialog = key
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val i = info) {
                is PageInfo.Loading -> Text("Loading…", modifier = Modifier.padding(16.dp))
                is PageInfo.Locked -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("This PDF is password protected", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { dialog = "unlock" }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Unlock (saves an unprotected copy)")
                    }
                }
                is PageInfo.Error -> Text("Cannot show this PDF: ${i.message}", modifier = Modifier.padding(16.dp))
                is PageInfo.Ok -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items((0 until i.count).toList(), key = { it }) { p ->
                        PageCard(
                            file = file,
                            index = p,
                            total = i.count,
                            onLeft = {
                                val o = (0 until i.count).toMutableList()
                                o[p] = p - 1
                                o[p - 1] = p
                                pageOp(o)
                            },
                            onRight = {
                                val o = (0 until i.count).toMutableList()
                                o[p] = p + 1
                                o[p + 1] = p
                                pageOp(o)
                            },
                            onRotate = { pageOp((0 until i.count).toList(), mapOf(p to 90)) },
                            onDelete = {
                                if (i.count <= 1) dialog = "delete"
                                else pageOp((0 until i.count).filter { it != p })
                            },
                            onEdit = { onAnnotate(p) }
                        )
                    }
                }
            }
        }
    }

    // ---------------- Dialogs ----------------

    when (dialog) {
        "rename" -> {
            var text by remember { mutableStateOf(name) }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Rename") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        val renamed = repo.rename(file, text)
                        dialog = ""
                        if (renamed == null) sv.toast("Invalid or duplicate name") else onRelChange(repo.relOf(renamed))
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "tags" -> {
            var text by remember { mutableStateOf((doc?.tags ?: emptyList()).joinToString(", ")) }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Tags") },
                text = {
                    OutlinedTextField(
                        text, { text = it }, singleLine = true,
                        label = { Text("Comma separated, e.g. tax, 2026") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        repo.setTags(file, text.split(","))
                        dialog = ""
                        reload++
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "move" -> {
            var newFolder by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Move to") },
                text = {
                    Column {
                        TextButton(onClick = {
                            dialog = ""
                            repo.move(file, "")?.let { onRelChange(repo.relOf(it)) }
                        }) { Text("All scans (no folder)") }
                        repo.folders().forEach { f ->
                            TextButton(onClick = {
                                dialog = ""
                                repo.move(file, f)?.let { onRelChange(repo.relOf(it)) }
                            }) { Text(f) }
                        }
                        OutlinedTextField(
                            newFolder, { newFolder = it }, singleLine = true,
                            label = { Text("New folder") }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (repo.createFolder(newFolder)) {
                            val f = newFolder.trim()
                            dialog = ""
                            repo.move(file, f)?.let { onRelChange(repo.relOf(it)) }
                        } else {
                            dialog = ""
                        }
                    }) { Text("Create & move") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "merge" -> {
            val others = remember { repo.list().filter { it.file != file } }
            val picked = remember { mutableStateListOf<String>() }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Merge with…") },
                text = {
                    if (others.isEmpty()) {
                        Text("No other scans to merge.")
                    } else {
                        Column(modifier = Modifier.height(320.dp)) {
                            androidx.compose.foundation.lazy.LazyColumn {
                                items(others.size) { idx ->
                                    val d = others[idx]
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = d.rel in picked,
                                            onCheckedChange = { c -> if (c) picked.add(d.rel) else picked.remove(d.rel) }
                                        )
                                        Text(d.name, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = picked.isNotEmpty(), onClick = {
                        dialog = ""
                        val chosen = picked.toList()
                        sv.run("Merging") {
                            withContext(Dispatchers.IO) {
                                val files = listOf(file) + chosen.map { File(repo.root, it) }
                                val tmp = sv.tmpPdf()
                                PdfTools.merge(files, tmp)
                                val saved = repo.saveFile(tmp, "$name (merged)", doc?.folder ?: "")
                                sv.reindex(saved)
                            }
                            sv.toast("Merged copy saved")
                        }
                    }) { Text("Merge") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "split" -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Extract pages") },
                text = {
                    Column {
                        Text("This document has $count pages. Pages go to a new scan; the original stays.")
                        OutlinedTextField(
                            text, { text = it }, singleLine = true,
                            label = { Text("Pages, e.g. 1-3, 5") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val order = PdfTools.parsePageRanges(text, count)
                        if (order == null) {
                            sv.toast("Enter valid pages between 1 and $count")
                        } else {
                            dialog = ""
                            sv.run("Extracting") {
                                withContext(Dispatchers.IO) {
                                    val tmp = sv.tmpPdf()
                                    PdfTools.editPages(file, tmp, order)
                                    val saved = repo.saveFile(tmp, "$name (pages ${text.trim()})", doc?.folder ?: "")
                                    sv.reindex(saved)
                                }
                                sv.toast("New scan saved")
                            }
                        }
                    }) { Text("Extract") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "compress" -> {
            var level by remember { mutableIntStateOf(1) }
            val labels = listOf("Light (best quality)", "Medium (recommended)", "Strong (smallest file)")
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Compress") },
                text = {
                    Column {
                        Text("Current size: ${formatSize(file.length())}. A smaller copy is saved; the original stays.")
                        labels.forEachIndexed { idx, l ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = level == idx, onClick = { level = idx })
                                Text(l)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = ""
                        val width = intArrayOf(1600, 1240, 900)[level]
                        val q = floatArrayOf(0.85f, 0.7f, 0.5f)[level]
                        sv.run("Compressing") {
                            val saved = withContext(Dispatchers.IO) {
                                val tmp = sv.tmpPdf()
                                PdfTools.compress(file, tmp, width, q)
                                repo.saveFile(tmp, "$name (compressed)", doc?.folder ?: "")
                            }
                            sv.reindex(saved)
                            sv.toast("${formatSize(file.length())} → ${formatSize(saved.length())}")
                        }
                    }) { Text("Compress") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "protect", "unlock" -> {
            val locking = dialog == "protect"
            var pw by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text(if (locking) "Password protect" else "Remove password") },
                text = {
                    Column {
                        Text(
                            if (locking) "A protected copy is saved. Remember the password: it cannot be recovered."
                            else "Enter the current password. An unprotected copy is saved."
                        )
                        OutlinedTextField(
                            pw, { pw = it }, singleLine = true,
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = pw.isNotEmpty(), onClick = {
                        dialog = ""
                        val password = pw
                        sv.run(if (locking) "Protecting" else "Unlocking") {
                            val saved = withContext(Dispatchers.IO) {
                                val tmp = sv.tmpPdf()
                                if (locking) PdfTools.protect(file, tmp, password) else PdfTools.unprotect(file, tmp, password)
                                repo.saveFile(tmp, "$name (${if (locking) "protected" else "unlocked"})", doc?.folder ?: "")
                            }
                            if (!locking) sv.reindex(saved)
                            sv.toast("Saved as ${saved.nameWithoutExtension}")
                            if (!locking) onRelChange(repo.relOf(saved))
                        }
                    }) { Text(if (locking) "Protect" else "Unlock") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Delete whole scan?") },
            text = { Text("\"$name\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = ""
                    repo.delete(file)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PageCard(
    file: File,
    index: Int,
    total: Int,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val bmp by produceState<Bitmap?>(null, file.path, file.lastModified(), index) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfTools.renderPage(file, index, 420)
            } catch (e: Throwable) {
                null
            }
        }
    }
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(180.dp))
            }
            Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium)
            Row {
                IconButton(onClick = onLeft, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Move earlier")
                }
                IconButton(onClick = onRight, enabled = index < total - 1, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Move later")
                }
                IconButton(onClick = onRotate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate")
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Create, contentDescription = "Annotate or sign")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete page")
                }
            }
        }
    }
}
