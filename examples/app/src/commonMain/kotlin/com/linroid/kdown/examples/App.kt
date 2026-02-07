package com.linroid.kdown.examples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadTask
import com.linroid.kdown.KDown
import com.linroid.kdown.KtorHttpEngine
import com.linroid.kdown.model.DownloadState
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

@Composable
fun App() {
  val config = remember {
    DownloadConfig(
      maxConnections = 4,
      retryCount = 3,
      retryDelayMs = 1000,
      progressUpdateIntervalMs = 200
    )
  }
  val kdown = remember { KDown(httpEngine = KtorHttpEngine(), config = config) }
  val scope = rememberCoroutineScope()
  val tasks = remember { mutableStateMapOf<String, DownloadTask>() }
  val taskErrors = remember { mutableStateMapOf<String, String>() }
  var customUrl by remember { mutableStateOf("") }
  var customFileName by remember { mutableStateOf("kdown-custom.bin") }

  DisposableEffect(Unit) {
    onDispose {
      kdown.close()
    }
  }

  val samples = remember {
    listOf(
      SampleFile(
        id = "w3c-pdf",
        title = "W3C Sample PDF",
        description = "Small PDF hosted by W3C for testing downloads.",
        url = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
        sizeHint = "13 KB"
      ),
      SampleFile(
        id = "rfc-text",
        title = "RFC 9110 (HTTP Semantics)",
        description = "Plain-text RFC from the official RFC Editor.",
        url = "https://www.rfc-editor.org/rfc/rfc9110.txt",
        sizeHint = "1.6 MB"
      ),
      SampleFile(
        id = "wikimedia-jpg",
        title = "Wikimedia Example Image",
        description = "Public domain example image from Wikimedia Commons.",
        url = "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg",
        sizeHint = "9 KB"
      )
    )
  }
  val publicExamples = remember {
    listOf(
      PublicExample(
        title = "File Examples - Sample PDF (150 KB)",
        url = "https://file-examples.com/storage/fe44f4f319f75cf86f0e8f6/2017/10/file-example_PDF_1MB.pdf",
        suggestedName = "file-examples-1mb.pdf"
      ),
      PublicExample(
        title = "File Examples - Sample JPG (100 KB)",
        url = "https://file-examples.com/storage/fe44f4f319f75cf86f0e8f6/2017/10/file_example_JPG_100kB.jpg",
        suggestedName = "file-examples-100kb.jpg"
      ),
      PublicExample(
        title = "File Examples - Sample MP3 (1 MB)",
        url = "https://file-examples.com/storage/fe44f4f319f75cf86f0e8f6/2017/11/file_example_MP3_1MG.mp3",
        suggestedName = "file-examples-1mb.mp3"
      )
    )
  }

  MaterialTheme {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "KDown Example Downloader",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = "Version ${KDown.VERSION} • Beautiful, minimal UI for every platform.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "Quick start",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = "Pick a curated test file below (all hosted on public legal sites) " +
                "or paste your own URL to validate pause/resume, retries, and progress.",
              style = MaterialTheme.typography.bodyMedium
            )
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                modifier = Modifier.weight(1f),
                label = { Text("Custom URL") },
                singleLine = true,
                placeholder = { Text("https://example.com/file.zip") }
              )
              OutlinedTextField(
                value = customFileName,
                onValueChange = { customFileName = it },
                modifier = Modifier.width(180.dp),
                label = { Text("Save as") },
                singleLine = true
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Button(
                onClick = {
                  val trimmed = customUrl.trim()
                  if (trimmed.isNotEmpty()) {
                    startDownload(
                      scope = scope,
                      kdown = kdown,
                      tasks = tasks,
                      taskErrors = taskErrors,
                      entryId = "custom",
                      url = trimmed,
                      destPath = buildDestPath(customFileName.ifBlank { "kdown-custom.bin" })
                    )
                  }
                }
              ) {
                Text("Download custom")
              }
              if (taskErrors["custom"] != null) {
                Text(
                  text = taskErrors["custom"] ?: "",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "Public file examples",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = "Select a public test file (e.g. File Examples) to prefill the custom downloader.",
              style = MaterialTheme.typography.bodyMedium
            )
            publicExamples.forEach { example ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = example.title,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.weight(1f),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(12.dp))
                FilledTonalButton(
                  onClick = {
                    customUrl = example.url
                    customFileName = example.suggestedName
                  }
                ) {
                  Text("Use")
                }
              }
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            text = "Test files",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
          )
          samples.forEach { sample ->
            val task = tasks[sample.id]
            val state = task?.state?.collectAsState(DownloadState.Idle)?.value ?: DownloadState.Idle
            SampleCard(
              sample = sample,
              state = state,
              errorMessage = taskErrors[sample.id],
              onDownload = {
                startDownload(
                  scope = scope,
                  kdown = kdown,
                  tasks = tasks,
                  taskErrors = taskErrors,
                  entryId = sample.id,
                  url = sample.url,
                  destPath = buildDestPath(sample.fileName)
                )
              },
              onPause = {
                scope.launch {
                  task?.pause()
                }
              },
              onResume = {
                scope.launch {
                  val resumed = task?.resume()
                  if (resumed != null) {
                    tasks[sample.id] = resumed
                  }
                }
              },
              onCancel = {
                scope.launch {
                  task?.cancel()
                }
              }
            )
          }
        }

        Divider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Tips",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = "Pause an active download to confirm range requests. " +
              "If the server does not support ranged downloads, KDown will fall back automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "On web/Wasm builds, file system access is limited, so downloads may show as unsupported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

@Composable
private fun SampleCard(
  sample: SampleFile,
  state: DownloadState,
  errorMessage: String?,
  onDownload: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit
) {
  val isDownloading = state is DownloadState.Downloading || state is DownloadState.Pending
  val isPaused = state is DownloadState.Paused
  val canDownload = state is DownloadState.Idle || state.isTerminal

  Card(
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(44.dp)
            .padding(8.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = sample.title.firstOrNull()?.uppercase() ?: "D",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = sample.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = "${sample.description} • ${sample.sizeHint}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Text(
        text = sample.url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      when (state) {
        is DownloadState.Downloading -> {
          val progress = state.progress
          val percent = (progress.percent * 100).coerceIn(0f, 100f)
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { progress.percent })
            Text(
              text = "${percent.toInt()}% • " +
                "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}" +
                progress.bytesPerSecond.takeIf { it > 0 }?.let { " • ${formatBytes(it)}/s" }.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
        is DownloadState.Pending -> {
          LinearProgressIndicator()
          Text(
            text = "Preparing download…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        is DownloadState.Paused -> {
          Text(
            text = "Paused. Ready to resume.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        is DownloadState.Completed -> {
          Text(
            text = "Completed • Saved to ${state.filePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        is DownloadState.Failed -> {
          Text(
            text = "Failed • ${state.error.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
          )
        }
        is DownloadState.Canceled -> {
          Text(
            text = "Canceled by user.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        is DownloadState.Idle -> {
          Text(
            text = "Ready to download.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (!errorMessage.isNullOrBlank()) {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        FilledTonalButton(
          onClick = onDownload,
          enabled = canDownload
        ) {
          Text(if (state is DownloadState.Completed) "Download again" else "Download")
        }
        OutlinedButton(
          onClick = if (isPaused) onResume else onPause,
          enabled = isPaused || isDownloading
        ) {
          Text(if (isPaused) "Resume" else "Pause")
        }
        TextButton(
          onClick = onCancel,
          enabled = isDownloading || isPaused
        ) {
          Text("Cancel")
        }
      }
    }
  }
}

private data class SampleFile(
  val id: String,
  val title: String,
  val description: String,
  val url: String,
  val sizeHint: String
) {
  val fileName: String
    get() = url.substringAfterLast("/").ifBlank { "kdown-sample.bin" }
}

private data class PublicExample(
  val title: String,
  val url: String,
  val suggestedName: String
)

private fun buildDestPath(fileName: String): Path {
  val safeName = fileName.ifBlank { "kdown-download.bin" }
  return Path("downloads/$safeName")
}

private fun startDownload(
  scope: kotlinx.coroutines.CoroutineScope,
  kdown: KDown,
  tasks: MutableMap<String, DownloadTask>,
  taskErrors: MutableMap<String, String>,
  entryId: String,
  url: String,
  destPath: Path
) {
  taskErrors.remove(entryId)
  scope.launch {
    runCatching {
      val request = DownloadRequest(
        url = url,
        destPath = destPath,
        connections = 4
      )
      kdown.download(request)
    }.onSuccess { task ->
      tasks[entryId] = task
    }.onFailure { error ->
      taskErrors[entryId] = error.message ?: "Unable to start download."
    }
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "--"
  val kb = 1024L
  val mb = kb * 1024
  val gb = mb * 1024
  return when {
    bytes < kb -> "$bytes B"
    bytes < mb -> {
      val tenths = (bytes * 10 + kb / 2) / kb
      "${tenths / 10}.${tenths % 10} KB"
    }
    bytes < gb -> {
      val tenths = (bytes * 10 + mb / 2) / mb
      "${tenths / 10}.${tenths % 10} MB"
    }
    else -> {
      val hundredths = (bytes * 100 + gb / 2) / gb
      "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
    }
  }
}
