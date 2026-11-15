@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.docscanner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shows the text recognised in a scan (OCR). Edit, copy, share or export it. */
@Composable
fun TextScreen(
    sv: Services,
    file: File,
    onBack: () -> Unit,
    onContact: (Exporter.Contact) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val name = file.nameWithoutExtension
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(file.path, attempt) {
        text = null
        error = null
        try {
            if (attempt > 0) sv.repo.setOcrText(file, "")
            text = ensureOcr(sv.repo, file)
        } catch (e: Throwable) {
            error = e.message ?: "Text recognition failed"
        }
    }

    fun exportAs(ext: String, mime: String) {
        val t = text ?: return
        sv.run("Preparing export") {
            val out = withContext(Dispatchers.IO) {
                val f = File(sv.exportDir(), "$name.$ext")
                when (ext) {
                    "docx" -> Exporter.writeDocx(t, f)
                    "xlsx" -> Exporter.writeXlsx(t, f)
                    else -> Exporter.writeTxt(t, f)
                }
                f
            }
            sv.share(listOf(out), mime)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text · $name", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            val t = text
            when {
                error != null -> Text("Could not read text: $error")
                t == null -> Row {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("Reading text from your scan…")
                }
                else -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(onClick = { clipboard.setText(AnnotatedString(t)); sv.toast("Copied") }, label = { Text("Copy") })
                        AssistChip(onClick = {
                            val f = File(sv.exportDir(), "$name.txt")
                            Exporter.writeTxt(t, f)
                            sv.share(listOf(f), "text/plain")
                        }, label = { Text("Share text") })
                        AssistChip(
                            onClick = { exportAs("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document") },
                            label = { Text("Word") }
                        )
                        AssistChip(
                            onClick = { exportAs("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") },
                            label = { Text("Excel") }
                        )
                        AssistChip(onClick = { onContact(Exporter.parseBusinessCard(t)) }, label = { Text("Save as contact") })
                        AssistChip(onClick = { attempt++ }, label = { Text("Re-scan text") })
                    }
                    if (t.isBlank()) {
                        Text("No text found in this document.", modifier = Modifier.padding(top = 12.dp))
                    }
                    OutlinedTextField(
                        value = t,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
