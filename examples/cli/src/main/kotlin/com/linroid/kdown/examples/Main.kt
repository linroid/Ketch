package com.linroid.kdown.examples

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.KDown
import com.linroid.kdown.KtorHttpEngine
import com.linroid.kdown.model.DownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

fun main(args: Array<String>) {
  println("KDown CLI Example - Version ${KDown.VERSION}")
  println()

  if (args.isEmpty()) {
    println("Usage: kdown-cli <url> [destination]")
    println()
    println("Examples:")
    println("  kdown-cli https://example.com/file.zip")
    println("  kdown-cli https://example.com/file.zip ./downloads/file.zip")
    println()
    println("Controls:")
    println("  The download will automatically pause after 2 seconds,")
    println("  then resume after 1 second to demonstrate pause/resume.")
    return
  }

  val url = args[0]
  val dest = args.getOrNull(1)
  val directory: Path
  val fileName: String?
  if (dest != null) {
    val destPath = Path(dest)
    directory = destPath.parent ?: Path(".")
    fileName = destPath.name
  } else {
    directory = Path(".")
    fileName = null
  }

  println("Downloading: $url")
  println("Directory: $directory")
  if (fileName != null) println("File name: $fileName")
  println()

  val config = DownloadConfig(
    maxConnections = 4,
    retryCount = 3,
    retryDelayMs = 1000,
    progressUpdateIntervalMs = 200
  )

  val kdown = KDown(
    httpEngine = KtorHttpEngine(),
    config = config
  )

  runBlocking {
    val request = DownloadRequest(
      url = url,
      directory = directory,
      fileName = fileName,
      connections = config.maxConnections
    )

    val task = kdown.download(request)

    // Monitor state changes in a separate coroutine
    val monitor = launch {
      task.state.collect { state ->
        when (state) {
          is DownloadState.Pending -> println("[Pending] Preparing download...")
          is DownloadState.Downloading -> {
            val progress = state.progress
            val pct = (progress.percent * 100).toInt()
            val downloaded = formatBytes(progress.downloadedBytes)
            val total = formatBytes(progress.totalBytes)
            print("\r[Downloading] $pct%  $downloaded / $total    ")
          }
          is DownloadState.Paused -> println("\n[Paused] Download paused.")
          is DownloadState.Completed -> println("\n[Completed] Saved to ${state.filePath}")
          is DownloadState.Failed -> println("\n[Failed] ${state.error.message}")
          is DownloadState.Canceled -> println("\n[Canceled] Download canceled.")
          is DownloadState.Idle -> {}
        }
      }
    }

    // Demonstrate pause/resume: pause after 2 seconds, resume after 1 more second
    val pauseDemo = launch {
      delay(2000)
      println("\n\n--- Demonstrating pause ---")
      task.pause()
      delay(1000)
      println("--- Resuming download ---\n")
      task.resume()
    }

    // Wait for completion
    val result = task.await()
    monitor.cancel()
    pauseDemo.cancel()

    result.fold(
      onSuccess = { path -> println("\nDownload completed: $path") },
      onFailure = { error -> println("\nDownload failed: ${error.message}") }
    )

    kdown.close()
  }
}

private fun formatBytes(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
  }
}
