@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.docscanner

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shown right after scanning, before anything is saved: retake or delete any page,
 * add more pages, name the file, then save.
 */
@Composable
fun ReviewScreen(
    sv: Services,
    draft: File,
    folder: String,
    onSaved: () -> Unit,
    onDiscard: () -> Unit
) {
    var reload by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(sv.repo.defaultName("Scan")) }
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler { confirmDiscard = true }

    val count by produceState(0, draft.path, draft.lastModified(), reload) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfTools.pageCount(draft)
            } catch (e: Throwable) {
                0
            }
        }
    }

    /** Replaces the draft file with [updated] (a finished temp PDF). */
    fun commit(updated: File) {
        updated.copyTo(draft, overwrite = true)
        updated.delete()
    }

    fun retake(index: Int) {
        sv.scan(ScanMode.SINGLE) { out ->
            sv.run("Replacing page") {
                withContext(Dispatchers.IO) {
                    val fresh = sv.copyScan(out)
                    val merged = sv.tmpPdf()
                    PdfTools.replacePage(draft, index, fresh, merged)
                    fresh.delete()
                    commit(merged)
                }
                reload++
            }
        }
    }

    fun addPages() {
        sv.scan(ScanMode.DOCUMENT) { out ->
            sv.run("Adding pages") {
                withContext(Dispatchers.IO) {
                    val extra = sv.copyScan(out)
                    val merged = sv.tmpPdf()
                    PdfTools.merge(listOf(draft, extra), merged)
                    extra.delete()
                    commit(merged)
                }
                reload++
            }
        }
    }

    fun deletePage(index: Int) {
        sv.run("Removing page") {
            withContext(Dispatchers.IO) {
                val tmp = sv.tmpPdf()
                PdfTools.editPages(draft, tmp, (0 until count).filter { it != index })
                commit(tmp)
            }
            reload++
        }
    }

    fun save() {
        sv.run("Saving") {
            val saved = withContext(Dispatchers.IO) {
                val tmp = sv.tmpPdf()
                draft.copyTo(tmp, overwrite = true)
                sv.repo.saveFile(tmp, name, folder)
            }
            draft.delete()
            sv.reindex(saved)
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review scan") },
                navigationIcon = {
                    IconButton(onClick = { confirmDiscard = true }) {
                        Icon(Icons.Default.Close, contentDescription = "Discard")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }, enabled = count > 0) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("File name") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            OutlinedButton(
                onClick = { addPages() },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  Add page")
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items((0 until count).toList(), key = { it }) { p ->
                    ReviewPage(
                        file = draft,
                        index = p,
                        canDelete = count > 1,
                        version = reload,
                        onRetake = { retake(p) },
                        onDelete = { deletePage(p) }
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("The pages you just scanned will not be saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun ReviewPage(
    file: File,
    index: Int,
    canDelete: Boolean,
    version: Int,
    onRetake: () -> Unit,
    onDelete: () -> Unit
) {
    val bmp by produceState<Bitmap?>(null, file.path, file.lastModified(), index, version) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfTools.renderPage(file, index, 480)
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
                Box(modifier = Modifier.fillMaxWidth().height(200.dp))
            }
            Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetake) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" Retake")
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete page")
                    }
                }
            }
        }
    }
}
