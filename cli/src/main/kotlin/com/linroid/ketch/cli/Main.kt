package com.linroid.ketch.cli

import ch.qos.logback.classic.Level
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.defaultConfigPath
import com.linroid.ketch.config.defaultDbPath
import com.linroid.ketch.config.generateConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.SqliteTaskStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

/** Ketch library log level, derived from CLI flags. */
private var ketchLogLevel = LogLevel.INFO

fun main(args: Array<String>) {
  // Parse global flags before subcommand dispatch
  val remaining = applyGlobalFlags(args.toMutableList())

  println("Ketch CLI - Version ${KetchApi.VERSION} (${KetchApi.REVISION})")
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
        speedLimit = SpeedLimit.parse(remaining[i + 1]) ?: run {
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

  val destination = if (dest != null) {
    Destination(dest)
  } else {
    Destination(".")
  }

  println("Downloading: $url")
  println("Destination: ${destination.value}")
  if (!speedLimit.isUnlimited) {
    println("Speed limit: ${formatBytes(speedLimit.bytesPerSecond)}/s")
  }
  if (priority != DownloadPriority.NORMAL) {
    println("Priority: $priority")
  }
  println("Max concurrent: $maxConcurrent")
  println()

  val config = DownloadConfig(
    maxConnectionsPerDownload = 4,
    retryCount = 3,
    retryDelayMs = 1000,
    progressIntervalMs = 200,
    maxConcurrentDownloads = maxConcurrent,
  )

  val ketch = Ketch(
    httpEngine = KtorHttpEngine(),
    config = config,
  )

  runBlocking {
    val request = DownloadRequest(
      url = url,
      destination = destination,
      speedLimit = speedLimit,
      priority = priority,
    )

    val task = ketch.download(request)

    val limitLabel = if (speedLimit.isUnlimited) ""
      else " [limit: ${formatBytes(speedLimit.bytesPerSecond)}/s]"
    val monitor = launch {
      task.state.collect { state ->
        when (state) {
          is DownloadState.Scheduled ->
            println("[Scheduled] Waiting for scheduled time...")
          is DownloadState.Queued ->
            println("[Queued] Waiting for download slot...")
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
            println("\n[Completed] Saved to ${state.outputPath}")
          is DownloadState.Failed ->
            println("\n[Failed] ${state.error.message}")
          is DownloadState.Canceled ->
            println("\n[Canceled] Download canceled.")
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

    ketch.close()
  }
}

/**
 * Extracts global flags (`-v`/`--verbose`, `--debug`) from [args],
 * applies them (sets logback root level and Ketch log level),
 * and returns the remaining arguments.
 */
private fun applyGlobalFlags(args: MutableList<String>): List<String> {
  var logbackLevel: Level? = null
  val remaining = mutableListOf<String>()
  for (arg in args) {
    when (arg) {
      "-v", "--verbose" -> {
        logbackLevel = Level.DEBUG
        ketchLogLevel = LogLevel.DEBUG
      }
      "--debug" -> {
        logbackLevel = Level.TRACE
        ketchLogLevel = LogLevel.VERBOSE
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
        cliSpeedLimit = SpeedLimit.parse(args[++i]) ?: run {
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
      FileConfigStore(configPath).load()
    } catch (e: Exception) {
      System.err.println("Error loading config: ${e.message}")
      return
    }
  } else {
    val defaultPath = defaultConfigPath()
    if (File(defaultPath).exists()) {
      try {
        println("Loading config from $defaultPath")
        FileConfigStore(defaultPath).load()
      } catch (e: Exception) {
        System.err.println(
          "Error loading config from $defaultPath: ${e.message}"
        )
        return
      }
    } else {
      KetchConfig()
    }
  }

  // CLI flags override config file values
  val defaultDownloadDir = System.getProperty("user.home") +
    File.separator + "Downloads"
  val mergedConfig = fileConfig.copy(
    server = fileConfig.server.copy(
      host = cliHost ?: fileConfig.server.host,
      port = cliPort ?: fileConfig.server.port,
      apiToken = cliToken ?: fileConfig.server.apiToken,
      corsAllowedHosts = cliCorsOrigins
        ?: fileConfig.server.corsAllowedHosts,
    ),
    download = fileConfig.download.copy(
      defaultDirectory = cliDownloadDir
        ?: fileConfig.download.defaultDirectory
        ?: defaultDownloadDir,
      speedLimit = cliSpeedLimit
        ?: fileConfig.download.speedLimit,
    ),
  )

  val downloadConfig = mergedConfig.download
  val serverConfig = mergedConfig.server
  val instanceName = mergedConfig.name ?: "Ketch"

  File(downloadConfig.defaultDirectory).mkdirs()

  val dbPath = defaultDbPath()
  val driver = DriverFactory(dbPath).createDriver()
  val taskStore = SqliteTaskStore(driver)

  val ketch = Ketch(
    httpEngine = KtorHttpEngine(),
    taskStore = taskStore,
    config = downloadConfig,
    name = instanceName,
    logger = Logger.console(ketchLogLevel),
  )
  val server = KetchServer(
    ketch,
    host = serverConfig.host,
    port = serverConfig.port,
    apiToken = serverConfig.apiToken,
    name = instanceName,
    corsAllowedHosts = serverConfig.corsAllowedHosts,
    mdnsEnabled = serverConfig.mdnsEnabled,
  )

  Runtime.getRuntime().addShutdownHook(Thread {
    println("Shutting down Ketch server...")
    server.stop()
    ketch.close()
  })

  println("Ketch Server v${KetchApi.VERSION}")
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
  println("Usage: ketch [options] <url> [destination]")
  println("       ketch server [options]")
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
  println("  server [options]         Start Ketch daemon server")
  println("                           Run `ketch server --help`")
  println("                           for server options")
  println()
  println("Examples:")
  println("  ketch https://example.com/file.zip")
  println("  ketch -v https://example.com/file.zip")
  println("  ketch --priority high https://example.com/file.zip")
  println("  ketch server --port 9000 --dir /tmp/downloads")
}

private fun printServerUsage() {
  println("Usage: ketch server [options]")
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
  println("  ketch server")
  println("  ketch server --port 9000 --dir /tmp/downloads")
  println("  ketch server --token my-secret --cors '*'")
  println("  ketch server --speed-limit 10m")
  println("  ketch server --config /path/to/config.toml")
  println("  ketch server --generate-config")
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

private fun formatBytes(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 ->
      String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 ->
      String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else ->
      String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
  }
}
