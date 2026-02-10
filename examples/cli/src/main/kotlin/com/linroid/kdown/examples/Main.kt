package com.linroid.kdown.examples

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadState
import com.linroid.kdown.KDown
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.engine.KtorHttpEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

fun main(args: Array<String>) {
  println("KDown CLI Example - Version ${KDown.VERSION}")
  println()

  if (args.isEmpty()) {
    printUsage()
    return
  }

  var url: String? = null
  var dest: String? = null
  var speedLimit = SpeedLimit.Unlimited

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--speed-limit" -> {
        if (i + 1 >= args.size) {
          println("Error: --speed-limit requires a value")
          println()
          printUsage()
          return
        }
        speedLimit = parseSpeedLimit(args[i + 1]) ?: run {
          println("Error: invalid speed limit '${args[i + 1]}'")
          println()
          printUsage()
          return
        }
        i += 2
      }
      else -> {
        if (url == null) url = args[i]
        else if (dest == null) dest = args[i]
        i++
      }
    }
  }

  if (url == null) {
    printUsage()
    return
  }

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
  if (!speedLimit.isUnlimited) {
    println("Speed limit: ${formatBytes(speedLimit.bytesPerSecond)}/s")
  }
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
      connections = config.maxConnections,
      speedLimit = speedLimit
    )

    val task = kdown.download(request)

    // Monitor state changes in a separate coroutine
    val limitLabel = if (speedLimit.isUnlimited) ""
      else " [limit: ${formatBytes(speedLimit.bytesPerSecond)}/s]"
    val monitor = launch {
      task.state.collect { state ->
        when (state) {
          is DownloadState.Scheduled ->
            println("[Scheduled] Waiting for scheduled time...")
          is DownloadState.Queued ->
            println("[Queued] Waiting for download slot...")
          is DownloadState.Pending ->
            println("[Pending] Preparing download...")
          is DownloadState.Downloading -> {
            val progress = state.progress
            val pct = (progress.percent * 100).toInt()
            val downloaded = formatBytes(progress.downloadedBytes)
            val total = formatBytes(progress.totalBytes)
            val speed = if (progress.bytesPerSecond > 0) {
              "  ${formatBytes(progress.bytesPerSecond)}/s"
            } else ""
            print(
              "\r[Downloading] $pct%  $downloaded" +
                " / $total$speed$limitLabel    "
            )
          }
          is DownloadState.Paused ->
            println("\n[Paused] Download paused.")
          is DownloadState.Completed ->
            println("\n[Completed] Saved to ${state.filePath}")
          is DownloadState.Failed ->
            println("\n[Failed] ${state.error.message}")
          is DownloadState.Canceled ->
            println("\n[Canceled] Download canceled.")
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
      onFailure = { error ->
        println("\nDownload failed: ${error.message}")
      }
    )

    kdown.close()
  }
}

private fun printUsage() {
  println("Usage: kdown-cli [options] <url> [destination]")
  println()
  println("Options:")
  println("  --speed-limit <value>  Limit download speed")
  println("                         Examples: 500k, 1m, 10m, 1024000")
  println("                         Suffixes: k = KB/s, m = MB/s")
  println("                         No suffix = bytes/s")
  println()
  println("Examples:")
  println("  kdown-cli https://example.com/file.zip")
  println("  kdown-cli --speed-limit 5m https://example.com/file.zip")
  println("  kdown-cli --speed-limit 500k https://example.com/file.zip ./downloads/file.zip")
  println()
  println("Controls:")
  println("  The download will automatically pause after 2 seconds,")
  println("  then resume after 1 second to demonstrate pause/resume.")
}

private fun parseSpeedLimit(value: String): SpeedLimit? {
  val trimmed = value.trim().lowercase()
  return when {
    trimmed.endsWith("m") -> {
      val num = trimmed.dropLast(1).toLongOrNull() ?: return null
      if (num <= 0) return null
      SpeedLimit.mbps(num)
    }
    trimmed.endsWith("k") -> {
      val num = trimmed.dropLast(1).toLongOrNull() ?: return null
      if (num <= 0) return null
      SpeedLimit.kbps(num)
    }
    else -> {
      val num = trimmed.toLongOrNull() ?: return null
      if (num <= 0) return null
      SpeedLimit.of(num)
    }
  }
}

private fun formatBytes(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 ->
      String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 ->
      String.format("%.1f MB", bytes / (1024.0 * 1024))
    else ->
      String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
  }
}
