package com.linroid.kdown.cli

import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.core.DownloadConfig
import com.linroid.kdown.core.KDown
import com.linroid.kdown.core.QueueConfig
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

  if (args[0] == "--queue-demo") {
    runQueueDemo(args.drop(1))
    return
  }

  var url: String? = null
  var dest: String? = null
  var speedLimit = SpeedLimit.Unlimited
  var priority = DownloadPriority.NORMAL
  var maxConcurrent = 3

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
      "--priority" -> {
        if (i + 1 >= args.size) {
          println("Error: --priority requires a value")
          println()
          printUsage()
          return
        }
        priority = parsePriority(args[i + 1]) ?: run {
          println("Error: invalid priority '${args[i + 1]}'")
          println("  Valid values: low, normal, high, urgent")
          println()
          printUsage()
          return
        }
        i += 2
      }
      "--max-concurrent" -> {
        if (i + 1 >= args.size) {
          println("Error: --max-concurrent requires a value")
          println()
          printUsage()
          return
        }
        maxConcurrent = args[i + 1].toIntOrNull() ?: run {
          println("Error: invalid number '${args[i + 1]}'")
          println()
          printUsage()
          return
        }
        if (maxConcurrent <= 0) {
          println("Error: --max-concurrent must be > 0")
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

  val directory: String
  val fileName: String?
  if (dest != null) {
    val destPath = Path(dest)
    directory = (destPath.parent ?: Path(".")).toString()
    fileName = destPath.name
  } else {
    directory = "."
    fileName = null
  }

  println("Downloading: $url")
  println("Directory: $directory")
  if (fileName != null) println("File name: $fileName")
  if (!speedLimit.isUnlimited) {
    println("Speed limit: ${formatBytes(speedLimit.bytesPerSecond)}/s")
  }
  if (priority != DownloadPriority.NORMAL) {
    println("Priority: $priority")
  }
  println("Max concurrent: $maxConcurrent")
  println()

  val config = DownloadConfig(
    maxConnections = 4,
    retryCount = 3,
    retryDelayMs = 1000,
    progressUpdateIntervalMs = 200,
    queueConfig = QueueConfig(
      maxConcurrentDownloads = maxConcurrent,
    )
  )

  val kdown = KDown(
    httpEngine = KtorHttpEngine(),
    config = config,
  )

  runBlocking {
    val request = DownloadRequest(
      url = url,
      directory = directory,
      fileName = fileName,
      connections = config.maxConnections,
      speedLimit = speedLimit,
      priority = priority,
    )

    val task = kdown.download(request)

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

    // Demonstrate pause/resume
    val pauseDemo = launch {
      delay(2000)
      println("\n\n--- Demonstrating pause ---")
      task.pause()
      delay(1000)
      println("--- Resuming download ---\n")
      task.resume()
    }

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

/**
 * Demonstrates queue management with multiple downloads, priorities,
 * and concurrency limits. Enqueues several downloads that exceed
 * the max concurrent limit so some are queued automatically.
 */
private fun runQueueDemo(urls: List<String>) {
  val demoUrls = urls.ifEmpty {
    println("Usage: kdown-cli --queue-demo <url1> <url2> ...")
    println()
    println(
      "Provide 4+ URLs to see queue behavior. " +
        "Only 2 run at a time;"
    )
    println("the rest are queued by priority.")
    return
  }

  println("=== Queue Management Demo ===")
  println("Max concurrent downloads: 2")
  println("Downloads: ${demoUrls.size}")
  println()

  val config = DownloadConfig(
    maxConnections = 4,
    retryCount = 3,
    retryDelayMs = 1000,
    progressUpdateIntervalMs = 500,
    queueConfig = QueueConfig(
      maxConcurrentDownloads = 2,
      maxConnectionsPerHost = 4,
      autoStart = true,
    )
  )

  val kdown = KDown(
    httpEngine = KtorHttpEngine(),
    config = config,
  )

  // Assign ascending priorities to demonstrate ordering
  val priorities = listOf(
    DownloadPriority.LOW,
    DownloadPriority.NORMAL,
    DownloadPriority.HIGH,
    DownloadPriority.URGENT
  )

  runBlocking {
    val tasks = mutableListOf<DownloadTask>()

    demoUrls.forEachIndexed { index, url ->
      val priority = priorities[index % priorities.size]
      val request = DownloadRequest(
        url = url,
        directory = "downloads",
        connections = 2,
        priority = priority,
      )
      val task = kdown.download(request)
      tasks.add(task)
      val name = extractFilename(url).ifBlank { "download-$index" }
      println("  [$priority] $name -> ${task.state.value}")
    }

    println()
    println("--- Monitoring downloads ---")
    println()

    // Monitor all tasks
    val monitors = tasks.mapIndexed { index, task ->
      val name = extractFilename(task.request.url)
        .ifBlank { "download-$index" }
      launch {
        task.state.collect { state ->
          val label = formatTaskState(state)
          println("  [$name] $label")
        }
      }
    }

    // Demonstrate dynamic priority change after 3 seconds
    if (tasks.size >= 3) {
      launch {
        delay(3000)
        val target = tasks[0]
        val name = extractFilename(target.request.url)
          .ifBlank { "download-0" }
        println()
        println(
          "--- Boosting priority of '$name' to URGENT ---"
        )
        target.setPriority(DownloadPriority.URGENT)
      }
    }

    // Wait for all tasks to complete
    for (task in tasks) {
      task.await()
    }
    monitors.forEach { it.cancel() }

    println()
    println("=== All downloads finished ===")
    kdown.close()
  }
}

private fun formatTaskState(state: DownloadState): String {
  return when (state) {
    is DownloadState.Queued -> "Queued"
    is DownloadState.Pending -> "Starting..."
    is DownloadState.Scheduled -> "Scheduled"
    is DownloadState.Downloading -> {
      val p = state.progress
      val pct = (p.percent * 100).toInt()
      "$pct% (${formatBytes(p.downloadedBytes)}" +
        " / ${formatBytes(p.totalBytes)})"
    }
    is DownloadState.Paused -> "Paused"
    is DownloadState.Completed -> "Completed -> ${state.filePath}"
    is DownloadState.Failed -> "Failed: ${state.error.message}"
    is DownloadState.Canceled -> "Canceled"
    is DownloadState.Idle -> "Idle"
  }
}

private fun extractFilename(url: String): String {
  return url.substringBefore("?")
    .substringBefore("#")
    .trimEnd('/')
    .substringAfterLast("/")
}

private fun printUsage() {
  println("Usage: kdown-cli [options] <url> [destination]")
  println("       kdown-cli --queue-demo <url1> <url2> ...")
  println()
  println("Options:")
  println("  --speed-limit <value>    Limit download speed")
  println("                           Examples: 500k, 1m, 10m")
  println("                           Suffixes: k = KB/s, m = MB/s")
  println("  --priority <level>       Set download priority")
  println("                           Values: low, normal, high, urgent")
  println("  --max-concurrent <n>     Max simultaneous downloads")
  println("                           Default: 3")
  println()
  println("Queue Demo:")
  println("  --queue-demo <urls...>   Run queue demo with multiple URLs")
  println("                           Uses max 2 concurrent downloads")
  println("                           to show queuing and priority")
  println()
  println("Examples:")
  println("  kdown-cli https://example.com/file.zip")
  println("  kdown-cli --priority high https://example.com/file.zip")
  println("  kdown-cli --max-concurrent 2 https://example.com/file.zip")
  println("  kdown-cli --queue-demo url1 url2 url3 url4")
}

private fun parsePriority(value: String): DownloadPriority? {
  return when (value.trim().lowercase()) {
    "low" -> DownloadPriority.LOW
    "normal" -> DownloadPriority.NORMAL
    "high" -> DownloadPriority.HIGH
    "urgent" -> DownloadPriority.URGENT
    else -> null
  }
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
