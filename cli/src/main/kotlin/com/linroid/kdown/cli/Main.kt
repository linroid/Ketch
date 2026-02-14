package com.linroid.kdown.cli

import ch.qos.logback.classic.Level
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.core.DownloadConfig
import com.linroid.kdown.core.KDown
import com.linroid.kdown.core.QueueConfig
import com.linroid.kdown.core.log.LogLevel
import com.linroid.kdown.core.log.Logger
import com.linroid.kdown.engine.KtorHttpEngine
import com.linroid.kdown.server.KDownServer
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.SqliteTaskStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.slf4j.LoggerFactory
import java.io.File

/** KDown library log level, derived from CLI flags. */
private var kdownLogLevel = LogLevel.INFO

fun main(args: Array<String>) {
  // Parse global flags before subcommand dispatch
  val remaining = applyGlobalFlags(args.toMutableList())

  println("KDown CLI - Version ${KDownVersion.DEFAULT} (${KDownVersion.REVISION})")
  println()

  if (remaining.isEmpty()) {
    printUsage()
    return
  }

  when (remaining[0]) {
    "server" -> {
      runServer(remaining.drop(1).toTypedArray())
      return
    }
  }

  var url: String? = null
  var dest: String? = null
  var speedLimit = SpeedLimit.Unlimited
  var priority = DownloadPriority.NORMAL
  var maxConcurrent = 3

  var i = 0
  while (i < remaining.size) {
    when (remaining[i]) {
      "--speed-limit" -> {
        if (i + 1 >= remaining.size) {
          println("Error: --speed-limit requires a value")
          println()
          printUsage()
          return
        }
        speedLimit = parseSpeedLimit(remaining[i + 1]) ?: run {
          println("Error: invalid speed limit '${remaining[i + 1]}'")
          println()
          printUsage()
          return
        }
        i += 2
      }
      "--priority" -> {
        if (i + 1 >= remaining.size) {
          println("Error: --priority requires a value")
          println()
          printUsage()
          return
        }
        priority = parsePriority(remaining[i + 1]) ?: run {
          println("Error: invalid priority '${remaining[i + 1]}'")
          println("  Valid values: low, normal, high, urgent")
          println()
          printUsage()
          return
        }
        i += 2
      }
      "--max-concurrent" -> {
        if (i + 1 >= remaining.size) {
          println("Error: --max-concurrent requires a value")
          println()
          printUsage()
          return
        }
        maxConcurrent = remaining[i + 1].toIntOrNull() ?: run {
          println("Error: invalid number '${remaining[i + 1]}'")
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
        if (url == null) url = remaining[i]
        else if (dest == null) dest = remaining[i]
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
 * Extracts global flags (`-v`/`--verbose`, `--debug`) from [args],
 * applies them (sets logback root level and KDown log level),
 * and returns the remaining arguments.
 */
private fun applyGlobalFlags(args: MutableList<String>): List<String> {
  var logbackLevel: Level? = null
  val remaining = mutableListOf<String>()
  for (arg in args) {
    when (arg) {
      "-v", "--verbose" -> {
        logbackLevel = Level.DEBUG
        kdownLogLevel = LogLevel.DEBUG
      }
      "--debug" -> {
        logbackLevel = Level.TRACE
        kdownLogLevel = LogLevel.VERBOSE
      }
      else -> remaining.add(arg)
    }
  }
  if (logbackLevel != null) {
    val root = LoggerFactory.getLogger(
      org.slf4j.Logger.ROOT_LOGGER_NAME
    ) as ch.qos.logback.classic.Logger
    root.level = logbackLevel
  }
  return remaining
}

private fun runServer(args: Array<String>) {
  // Track which CLI flags are explicitly set
  var cliHost: String? = null
  var cliPort: Int? = null
  var cliToken: String? = null
  var cliCorsOrigins: List<String>? = null
  var cliDownloadDir: String? = null
  var cliSpeedLimit: SpeedLimit? = null
  var configPath: String? = null

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--help", "-h" -> {
        printServerUsage()
        return
      }
      "--generate-config" -> {
        val path = defaultConfigPath()
        if (File(path).exists()) {
          System.err.println("Config file already exists: $path")
          System.err.println(
            "Delete it first if you want to regenerate."
          )
          return
        }
        generateConfig(path)
        println("Generated default config at: $path")
        return
      }
      "--config" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --config requires a value")
          printServerUsage()
          return
        }
        configPath = args[++i]
      }
      "--host" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --host requires a value")
          printServerUsage()
          return
        }
        cliHost = args[++i]
      }
      "--port" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --port requires a value")
          printServerUsage()
          return
        }
        val value = args[++i].toIntOrNull()
        if (value == null || value !in 1..65535) {
          System.err.println("Error: invalid port '${args[i]}'")
          return
        }
        cliPort = value
      }
      "--token" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --token requires a value")
          printServerUsage()
          return
        }
        cliToken = args[++i]
      }
      "--cors" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --cors requires a value")
          printServerUsage()
          return
        }
        cliCorsOrigins = args[++i].split(",").map { it.trim() }
      }
      "--dir" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --dir requires a value")
          printServerUsage()
          return
        }
        cliDownloadDir = args[++i]
      }
      "--speed-limit" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --speed-limit requires a value")
          printServerUsage()
          return
        }
        cliSpeedLimit = parseSpeedLimit(args[++i]) ?: run {
          System.err.println("Error: invalid speed limit '${args[i]}'")
          printServerUsage()
          return
        }
      }
      else -> {
        System.err.println("Error: unknown option '${args[i]}'")
        printServerUsage()
        return
      }
    }
    i++
  }

  // Load config: explicit --config path, or default path if exists,
  // or empty defaults
  val fileConfig = if (configPath != null) {
    try {
      loadConfig(configPath)
    } catch (e: Exception) {
      System.err.println("Error loading config: ${e.message}")
      return
    }
  } else {
    val defaultPath = defaultConfigPath()
    if (File(defaultPath).exists()) {
      try {
        println("Loading config from $defaultPath")
        loadConfig(defaultPath)
      } catch (e: Exception) {
        System.err.println(
          "Error loading config from $defaultPath: ${e.message}"
        )
        return
      }
    } else {
      CliConfig()
    }
  }

  // CLI flags override config file values
  val mergedConfig = fileConfig.copy(
    server = fileConfig.server.copy(
      host = cliHost ?: fileConfig.server.host,
      port = cliPort ?: fileConfig.server.port,
      apiToken = cliToken ?: fileConfig.server.apiToken,
      corsAllowedHosts = cliCorsOrigins
        ?: fileConfig.server.corsAllowedHosts,
    ),
    download = fileConfig.download.copy(
      directory = cliDownloadDir ?: fileConfig.download.directory,
      speedLimit = cliSpeedLimit?.let { limit ->
        if (limit.isUnlimited) null
        else "${limit.bytesPerSecond}"
      } ?: fileConfig.download.speedLimit,
    ),
  )

  val defaultDownloadDir = System.getProperty("user.home") +
    File.separator + "Downloads"
  val downloadConfig = mergedConfig.toDownloadConfig(defaultDownloadDir)
  val serverConfig = mergedConfig.toServerConfig()

  File(downloadConfig.defaultDirectory).mkdirs()

  val dbPath = defaultDbPath()
  val driver = DriverFactory(dbPath).createDriver()
  val taskStore = SqliteTaskStore(driver)

  val kdown = KDown(
    httpEngine = KtorHttpEngine(),
    taskStore = taskStore,
    config = downloadConfig,
    logger = Logger.console(kdownLogLevel),
  )
  val server = KDownServer(kdown, serverConfig)

  Runtime.getRuntime().addShutdownHook(Thread {
    println("Shutting down KDown server...")
    server.stop()
    kdown.close()
  })

  println("KDown Server v${KDownVersion.DEFAULT}")
  println("  Host:          ${serverConfig.host}")
  println("  Port:          ${serverConfig.port}")
  println("  Download dir:  ${downloadConfig.defaultDirectory}")
  println("  Database:      $dbPath")
  if (configPath != null) {
    println("  Config:        $configPath")
  }
  if (serverConfig.apiToken != null) {
    println("  Auth:          enabled")
  }
  if (serverConfig.corsAllowedHosts.isNotEmpty()) {
    println(
      "  CORS origins:  " +
        serverConfig.corsAllowedHosts.joinToString(", ")
    )
  }
  if (!downloadConfig.speedLimit.isUnlimited) {
    println(
      "  Speed limit:   " +
        "${downloadConfig.speedLimit.bytesPerSecond / 1024} KB/s"
    )
  }
  println()

  server.start(wait = true)
}

private fun printUsage() {
  println("Usage: kdown [options] <url> [destination]")
  println("       kdown server [options]")
  println()
  println("Global Options:")
  println("  -v, --verbose            Enable verbose logging (DEBUG)")
  println("  --debug                  Enable debug logging (TRACE)")
  println()
  println("Download Options:")
  println("  --speed-limit <value>    Limit download speed")
  println("                           Examples: 500k, 1m, 10m")
  println("                           Suffixes: k = KB/s, m = MB/s")
  println("  --priority <level>       Set download priority")
  println("                           Values: low, normal, high, urgent")
  println("  --max-concurrent <n>     Max simultaneous downloads")
  println("                           Default: 3")
  println()
  println("Server:")
  println("  server [options]         Start KDown daemon server")
  println("                           Run `kdown server --help`")
  println("                           for server options")
  println()
  println("Examples:")
  println("  kdown https://example.com/file.zip")
  println("  kdown -v https://example.com/file.zip")
  println("  kdown --priority high https://example.com/file.zip")
  println("  kdown server --port 9000 --dir /tmp/downloads")
}

private fun printServerUsage() {
  println("Usage: kdown server [options]")
  println()
  println("Options:")
  println("  --config <path>        Path to TOML config file")
  println("  --generate-config      Generate default config and exit")
  println("  --host <address>       Bind address (default: 0.0.0.0)")
  println("  --port <number>        Port number (default: 8642)")
  println("  --token <string>       API bearer token (optional)")
  println("  --cors <origins>       CORS allowed origins,")
  println("                         comma-separated (optional)")
  println("  --dir <path>           Download directory")
  println("                         (default: ~/Downloads)")
  println("  --speed-limit <value>  Global speed limit")
  println("                         (e.g., 10m, 500k)")
  println("  --help, -h             Show this help message")
  println()
  println("Config file:")
  println("  Default location: ${defaultConfigPath()}")
  println("  CLI flags override config file values.")
  println("  Use --generate-config to create a default file.")
  println()
  println("Examples:")
  println("  kdown server")
  println("  kdown server --port 9000 --dir /tmp/downloads")
  println("  kdown server --token my-secret --cors '*'")
  println("  kdown server --speed-limit 10m")
  println("  kdown server --config /path/to/config.toml")
  println("  kdown server --generate-config")
}

private fun defaultDbPath(): String {
  val os = System.getProperty("os.name", "").lowercase()
  val home = System.getProperty("user.home")
  val configDir = when {
    os.contains("mac") ->
      "$home${File.separator}Library${File.separator}" +
        "Application Support${File.separator}kdown"
    os.contains("win") -> {
      val appData = System.getenv("APPDATA")
        ?: "$home${File.separator}AppData${File.separator}Roaming"
      "$appData${File.separator}kdown"
    }
    else -> {
      val xdg = System.getenv("XDG_CONFIG_HOME")
        ?: "$home${File.separator}.config"
      "$xdg${File.separator}kdown"
    }
  }
  val dir = File(configDir)
  dir.mkdirs()
  return File(dir, "kdown.db").absolutePath
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

internal fun parseSpeedLimit(value: String): SpeedLimit? {
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
