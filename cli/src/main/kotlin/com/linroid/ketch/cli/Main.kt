package com.linroid.ketch.cli

import ch.qos.logback.classic.Level
import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.ai.resolveAiSettingsFromEnv
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.DiscoveryException
import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.config.defaultConfigPath
import com.linroid.ketch.config.defaultDbPath
import com.linroid.ketch.config.generateConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.mcp.KetchMcpServer
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.SqliteTaskStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

/** Ketch library log level, derived from CLI flags. */
private var ketchLogLevel = LogLevel.INFO

fun main(args: Array<String>) {
  // Windows cannot replace a running program, so `ketch update` renamed the old binary aside.
  if (File.separatorChar == '\\') CliInstallation.current()?.removeReplaced()

  // Parse global flags before subcommand dispatch
  val remaining = applyGlobalFlags(args.toMutableList())

  // The MCP server owns stdout, and ai-discover keeps it for its results, so each prints the
  // banner to stderr itself
  when (remaining.firstOrNull()) {
    "mcp" -> {
      runMcp(remaining.drop(1))
      return
    }
    "ai-discover" -> {
      val status = runAiDiscover(remaining.drop(1))
      if (status != AiDiscoverExit.OK) exitProcess(status)
      return
    }
  }

  printBanner()

  when (remaining.firstOrNull()) {
    null -> printUsage()
    "server" -> runServer(remaining.drop(1).toTypedArray())
    "update" -> {
      // The engine that downloads the release only logs warnings unless -v or --debug asks for
      // more, so the progress line stays readable.
      val level = if (ketchLogLevel < LogLevel.INFO) ketchLogLevel else LogLevel.WARN
      exitProcess(runUpdate(remaining.drop(1), Logger.console(level)))
    }
    else -> when (val parsed = parseDownloadArgs(remaining)) {
      DownloadArgs.Help -> printUsage()
      is DownloadArgs.Invalid -> {
        System.err.println("Error: ${parsed.message}")
        println()
        printUsage()
      }
      is DownloadArgs.Download -> runDownload(parsed)
    }
  }
}

private fun printBanner() {
  println("Ketch CLI - Version ${KetchApi.VERSION} (${KetchApi.REVISION})")
  println()
}

private fun runDownload(args: DownloadArgs.Download) {
  val speedLimit = args.speedLimit
  val destination = resolveDestination(args.destination)

  println("Downloading: ${args.url}")
  println("Destination: ${destination.value}")
  if (!speedLimit.isUnlimited) {
    println("Speed limit: ${formatBytes(speedLimit.bytesPerSecond)}/s")
  }
  if (args.priority != DownloadPriority.NORMAL) {
    println("Priority: ${args.priority}")
  }
  println("Max concurrent: ${args.maxConcurrent}")
  // Names only: values such as cookies are credentials.
  if (args.headers.isNotEmpty()) println("Headers: ${args.headers.keys.joinToString()}")
  println()

  val config = DownloadConfig(
    maxConnectionsPerDownload = 4,
    retryCount = 3,
    retryDelayMs = 1000,
    progressIntervalMs = 200,
    maxConcurrentDownloads = args.maxConcurrent,
  )

  val ketch = Ketch(
    httpEngine = KtorHttpEngine.withNetworkInterfaces(),
    config = config,
    logger = Logger.console(ketchLogLevel),
    additionalSources = listOf(FtpDownloadSource(), torrentSource(readDefaultConfig().torrent)),
  )

  runBlocking {
    val task = ketch.download(args.toRequest(destination))

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
            val downloaded = formatBytes(progress.downloadedBytes)
            val amount = if (progress.totalBytes > 0) {
              val pct = (progress.percent * 100).toInt()
              "$pct%  $downloaded / ${formatBytes(progress.totalBytes)}"
            } else {
              downloaded
            }
            val speed = if (progress.bytesPerSecond > 0) {
              "  ${formatBytes(progress.bytesPerSecond)}/s"
            } else ""
            print("\r[Downloading] $amount$speed$limitLabel    ")
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

    val result = task.await()
    monitor.cancel()

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
  var noToken = false
  var cliCorsOrigins: List<String>? = null
  var cliAllowedHosts: List<String>? = null
  var cliAllowedDirectories: List<String>? = null
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
      "--no-token" -> noToken = true
      "--cors" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --cors requires a value")
          printServerUsage()
          return
        }
        cliCorsOrigins = args[++i].split(",").map { it.trim() }
      }
      "--allowed-hosts" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --allowed-hosts requires a value")
          printServerUsage()
          return
        }
        cliAllowedHosts = args[++i].split(",").map { it.trim() }
      }
      "--allowed-dirs" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --allowed-dirs requires a value")
          printServerUsage()
          return
        }
        cliAllowedDirectories = args[++i].split(",").map { it.trim() }.filter { it.isNotEmpty() }
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
  if (noToken && cliToken != null) {
    System.err.println("Error: --token and --no-token cannot be used together")
    return
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
      corsAllowedHosts = cliCorsOrigins
        ?: fileConfig.server.corsAllowedHosts,
      allowedHosts = cliAllowedHosts
        ?: fileConfig.server.allowedHosts,
      allowedDirectories = cliAllowedDirectories
        ?: fileConfig.server.allowedDirectories,
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
  val instanceName = mergedConfig.name ?: "Ketch"
  val token = try {
    resolveServerToken(
      cliToken = cliToken,
      noToken = noToken,
      environmentToken = System.getenv(TOKEN_ENV),
      configToken = fileConfig.server.apiToken,
      loopbackOnly = mergedConfig.server.isLoopbackOnly,
      tokenFile = File(defaultConfigDir(), TOKEN_FILE_NAME),
    )
  } catch (e: IOException) {
    System.err.println("Error: could not keep the access token: ${e.message}")
    return
  }
  val serverConfig = mergedConfig.server.copy(
    apiToken = token.value,
    // With a token, pages on any site, such as the web app, may call the API, as they still
    // need the token; the apps do the same. Without one, KetchServer refuses them.
    corsAllowedHosts = mergedConfig.server.corsAllowedHosts.ifEmpty {
      if (token.value == null) emptyList() else listOf("*")
    },
  )

  File(downloadConfig.defaultDirectory).mkdirs()

  val dbPath = defaultDbPath()
  val taskStore = openTaskStore(dbPath)

  val ketch = Ketch(
    httpEngine = KtorHttpEngine.withNetworkInterfaces(),
    taskStore = taskStore,
    config = downloadConfig,
    name = instanceName,
    logger = Logger.console(ketchLogLevel),
    additionalSources = listOf(FtpDownloadSource(), torrentSource(fileConfig.torrent)),
  )
  val server = KetchServer(
    ketch,
    host = serverConfig.host,
    port = serverConfig.port,
    apiToken = serverConfig.apiToken,
    name = instanceName,
    corsAllowedHosts = serverConfig.corsAllowedHosts,
    allowedHosts = serverConfig.allowedHosts,
    allowedDirectories = serverConfig.allowedDirectories,
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
  when (token) {
    is ServerToken.Given -> println("  Auth:          token from ${token.source}")
    is ServerToken.Saved -> println("  Auth:          token from ${token.file}")
    is ServerToken.Created -> {
      println("  Auth:          new token, saved to ${token.file}")
      println("  Token:         ${token.value}")
    }
    ServerToken.None -> println(
      "  Auth:          none" + if (serverConfig.isLoopbackOnly) " (this machine only)" else ""
    )
  }
  if (token.value == null && serverConfig.allowedHosts.isNotEmpty()) {
    println("  Allowed hosts: " + serverConfig.allowedHosts.joinToString(", "))
  }
  if (token.value == null || serverConfig.allowedDirectories.isNotEmpty()) {
    val folders = listOf(downloadConfig.defaultDirectory) + serverConfig.allowedDirectories
    println("  Save folders:  " + folders.joinToString(", "))
  }
  if (serverConfig.corsAllowedHosts.isNotEmpty()) {
    println(
      "  CORS origins:  " +
        serverConfig.corsAllowedHosts.joinToString(", ") +
        if (serverConfig.apiToken == null) " (ignored: needs a token)" else ""
    )
  }
  if (!downloadConfig.speedLimit.isUnlimited) {
    println(
      "  Speed limit:   " +
        "${downloadConfig.speedLimit.bytesPerSecond / 1024} KB/s"
    )
  }
  println()
  if (token.value == null && !serverConfig.isLoopbackOnly) {
    System.err.println(
      """
      |WARNING: --no-token: anyone who can reach port ${serverConfig.port} of this machine can use
      |this server: add downloads to the save folders above, list, pause and remove tasks, and
      |delete their files. Leave out --no-token to require a token.
      |""".trimMargin()
    )
  }

  serveDaemon(server, ketch)
}

/**
 * Serves [ketch] through [server] until the server stops. It listens before restoring the
 * tasks saved by earlier runs, so a daemon that cannot start, e.g. because another one uses
 * the port, never resumes downloads into the same files.
 */
internal fun serveDaemon(server: KetchServer, ketch: KetchApi) {
  server.start(wait = false)
  runBlocking { ketch.start() }
  server.awaitStop()
}

/** Exit statuses of `ketch ai-discover`, for scripts that run it. */
internal object AiDiscoverExit {
  /** The search ran, whether or not it found anything. */
  const val OK = 0

  /** The search could not run or failed: not configured, no terminal to ask on, an error. */
  const val FAILED = 1

  /** The arguments cannot be used. */
  const val USAGE = 2
}

/**
 * Runs `ketch ai-discover` with [args], the arguments after `ai-discover`, and returns its exit
 * status, one of [AiDiscoverExit]. Only the results, or the usage asked for, go to [results];
 * everything else goes to stderr: the banner, what it searches for, the steps, the questions
 * and the errors.
 *
 * @param results where the results go; stdout by default.
 */
internal fun runAiDiscover(args: List<String>, results: PrintStream = System.out): Int {
  // Logback's console appender, the agent's libraries and any println go to stdout, so it
  // points at stderr while the command runs; see runMcp.
  val stdout = System.out
  System.setOut(System.err)
  try {
    printBanner()
    return discover(args, results)
  } finally {
    System.setOut(stdout)
  }
}

private fun discover(args: List<String>, results: PrintStream): Int {
  val options = when (val parsed = parseAiDiscoverArgs(args)) {
    AiDiscoverArgs.Help -> {
      printAiDiscoverUsage(results)
      return AiDiscoverExit.OK
    }
    is AiDiscoverArgs.Invalid -> {
      System.err.println("Error: ${parsed.message}")
      System.err.println()
      printAiDiscoverUsage(System.err)
      return AiDiscoverExit.USAGE
    }
    is AiDiscoverArgs.Discover -> parsed
  }

  val settings = resolveAiSettingsFromEnv(readDefaultConfig().ai)
  if (!settings.isUsable) {
    System.err.println("AI discovery is not configured.")
    System.err.println("Set a provider and API token on the app's Settings page,")
    System.err.println("or export a provider API key for this shell.")
    return AiDiscoverExit.FAILED
  }

  val terminal = if (options.mayAsk(settings.access)) {
    openTerminal() ?: run {
      System.err.println(noTerminalMessage(settings.access.mode))
      return AiDiscoverExit.FAILED
    }
  } else {
    null
  }
  val outputLock = Any()
  // Nothing to ask: --yes or the allow mode lets every request through.
  val approver = if (options.allowAll || settings.access.mode == PageAccessMode.Allow) {
    PageAccessApprover.AllowAll
  } else {
    CliPageAccess(
      settings = settings.access,
      allowAll = false,
      terminal = terminal,
      outputLock = outputLock,
    )
  }
  val aiModule = AiModule.create(AiConfig(settings = settings))

  System.err.println(
    "Using ${settings.llm.provider.label}" +
      " · ${settings.llm.effectiveModel}"
  )
  System.err.println("Discovering resources for: \"${options.query}\"")
  if (options.sites.isNotEmpty()) {
    System.err.println("Limited to: ${options.sites.joinToString(", ")}")
  }
  System.err.println()

  return runBlocking {
    val discoverQuery = DiscoverQuery(
      query = options.query,
      sites = options.sites,
      maxResults = options.maxResults,
      contentFilter = settings.contentFilter && !options.noFilter,
    )
    val response = try {
      aiModule.discoveryService.discover(
        query = discoverQuery,
        stepListener = StderrStepListener(outputLock),
        approver = approver,
      )
    } catch (e: IllegalArgumentException) {
      System.err.println("Error: ${e.message}")
      return@runBlocking AiDiscoverExit.FAILED
    } catch (e: DiscoveryException) {
      System.err.println("Error: ${e.message}")
      return@runBlocking AiDiscoverExit.FAILED
    } finally {
      // The fetcher's OkHttp engine keeps an idle non-daemon thread for a minute after its
      // last request, which would hold the process open that long
      aiModule.close()
      terminal?.close()
    }

    if (response.summary.isNotEmpty()) {
      results.println(response.summary.printable())
      results.println()
    }
    if (response.filtered > 0) {
      // On stderr, like the other notes, so stdout keeps only what was found.
      System.err.println(
        "The content filter hid ${response.filtered} candidate(s); pass --no-filter," +
          " or set contentFilter = false under [ai] in config.toml, to show them.",
      )
      System.err.println()
    }
    if (response.candidates.isEmpty()) {
      results.println("No candidates found.")
      if (settings.search.provider == SearchProvider.None) {
        results.println("(Configure a web search provider for broader results)")
      }
      return@runBlocking AiDiscoverExit.OK
    }

    results.println("Found ${response.candidates.size} candidate(s):")
    results.println()
    response.candidates.forEachIndexed { idx, candidate ->
      results.println("  ${idx + 1}. ${candidate.title.printable()}")
      results.println("     URL: ${printableUrl(candidate.url)}")
      candidate.fileName?.let { results.println("     File: ${it.printable()}") }
      val fileSize = candidate.fileSize
      if (fileSize != null) {
        results.println("     Size: ${formatBytes(fileSize)}")
      }
      results.println("     Confidence: ${"%.0f".format(candidate.confidence * 100)}%")
      if (candidate.description.isNotEmpty()) {
        results.println("     ${candidate.description.printable()}")
      }
      results.println()
    }

    if (response.sources.isNotEmpty()) {
      results.println("Sources analyzed:")
      response.sources.forEach { src ->
        results.println("  - ${src.title.printable()} (${printableUrl(src.url)})")
      }
    }
    AiDiscoverExit.OK
  }
}

/**
 * Prints the agent's steps to stderr as they arrive, keeping stdout for the results. A step
 * waits for [lock] while a page access question is open; Koog runs one tool at a time, so the
 * two rarely meet.
 */
private class StderrStepListener(private val lock: Any) : DiscoveryStepListener {
  override fun onStep(title: String, details: String) {
    synchronized(lock) { System.err.println(printableStep(title, details)) }
  }
}

private fun printAiDiscoverUsage(out: PrintStream) {
  out.println("Usage: ketch ai-discover <query> [options]")
  out.println()
  out.println("  --sites <domains>    Only search, read and return links from")
  out.println("                       these sites and their subdomains;")
  out.println("                       redirects to download hosts are followed")
  out.println("  --max-results <n>    Max results (default: 5)")
  out.println("  -y, --yes            Open websites without asking")
  out.println("  --no-filter          Show results the content filter hides:")
  out.println("                       shortened links, download aggregators,")
  out.println("                       look-alike sites, installers over HTTP")
  out.println("  -h, --help           Show this help message")
  out.println()
  out.println("Discover asks before it opens a website unless [ai.access] in")
  out.println("config.toml says mode = \"allow\". It asks on the terminal, and")
  out.println("answers last for this run. Without a terminal, pass --yes or")
  out.println("--sites. On Windows it asks only while its input and output")
  out.println("are both the console: pass --yes or --sites to redirect them.")
  out.println()
  out.println("Exit status: 0 when the search ran, even if it found nothing;")
  out.println("1 when it could not run or failed; 2 for invalid arguments.")
  out.println()
  out.println("Examples:")
  out.println("  ketch ai-discover \"latest Ubuntu 24.04 ISO\"")
  out.println("  ketch ai-discover \"ffmpeg release\" --sites ffmpeg.org")
  out.println("  ketch ai-discover --yes \"blender 4.2 macOS\" > results.txt")
  out.println()
  out.println("Configure a provider and token on the app's Settings")
  out.println("page, or export OPENAI_API_KEY / ANTHROPIC_API_KEY /")
  out.println("GEMINI_API_KEY for the same effect.")
}

private fun runMcp(args: List<String>) {
  var configPath: String? = null
  var cliDownloadDir: String? = null

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--help", "-h" -> {
        printMcpUsage()
        return
      }
      "--config" -> {
        if (i + 1 >= args.size) {
          printMcpError("--config requires a value")
          return
        }
        configPath = args[++i]
      }
      "--dir" -> {
        if (i + 1 >= args.size) {
          printMcpError("--dir requires a value")
          return
        }
        cliDownloadDir = args[++i]
      }
      else -> {
        printMcpError("unknown option '${args[i]}'")
        return
      }
    }
    i++
  }

  // stdout carries the JSON-RPC stream, so keep it for the transport and send everything
  // else to stderr: the banner, the console logger, Logback and any library output.
  // Logback's console appender looks up System.out on every write, so this also covers the
  // Logback that -v or --debug initialized before this point.
  val protocolOut = System.out
  System.setOut(System.err)
  printBanner()

  val fileConfig = if (configPath != null) {
    try {
      FileConfigStore(configPath).load()
    } catch (e: Exception) {
      // A failing status, so the MCP client reports the server as failed rather than closed
      System.err.println("Error loading config from $configPath: ${e.message}")
      exitProcess(1)
    }
  } else {
    readDefaultConfig()
  }

  val defaultDownloadDir = System.getProperty("user.home") +
    File.separator + "Downloads"
  val downloadConfig = fileConfig.download.copy(
    defaultDirectory = cliDownloadDir
      ?: fileConfig.download.defaultDirectory
      ?: defaultDownloadDir,
  )

  File(downloadConfig.defaultDirectory!!).mkdirs()

  val taskStore = openTaskStore(defaultDbPath())

  val ketch = Ketch(
    httpEngine = KtorHttpEngine.withNetworkInterfaces(),
    taskStore = taskStore,
    config = downloadConfig,
    logger = Logger.console(ketchLogLevel),
    additionalSources = listOf(FtpDownloadSource(), torrentSource(fileConfig.torrent)),
  )

  Runtime.getRuntime().addShutdownHook(Thread {
    ketch.close()
  })

  val mcpServer = KetchMcpServer(ketch)

  runBlocking {
    ketch.start()
    mcpServer.startStdio(output = protocolOut)
  }
  // The client closed stdin, which ends the MCP session. Exit rather than rely on every
  // library thread being a daemon; the shutdown hook above closes Ketch.
  exitProcess(0)
}

private fun printMcpError(message: String) {
  System.err.println("Error: $message")
  System.err.println("Run `ketch mcp --help` for usage.")
}

private fun printMcpUsage() {
  println("Usage: ketch mcp [options]")
  println()
  println("Start Ketch as an MCP (Model Context Protocol) server")
  println("using stdio transport. AI agents like Claude Desktop")
  println("can manage downloads through MCP tools.")
  println()
  println("Options:")
  println("  --config <path>   Path to TOML config file")
  println("  --dir <path>      Download directory")
  println("                    (default: ~/Downloads)")
  println("  --help, -h        Show this help message")
  println()
  println("MCP client configuration (e.g. claude_desktop_config.json):")
  println("  {")
  println("    \"mcpServers\": {")
  println("      \"ketch\": {")
  println("        \"command\": \"ketch\",")
  println("        \"args\": [\"mcp\"]")
  println("      }")
  println("    }")
  println("  }")
}

/** Torrent source that persists DHT state and adds the configured extra trackers. */
private fun torrentSource(settings: TorrentSettings) = TorrentDownloadSource(
  TorrentConfig(
    stateDirectory = File(defaultConfigDir(), "torrent-state").path,
    additionalTrackers = settings.trackers,
  ),
)

/**
 * The default config file's settings, or the defaults when there is none or it cannot be read,
 * which stderr reports. The file stays as it is: the CLI never saves it, and the apps that share
 * it move an unreadable one aside and tell the user.
 */
private fun readDefaultConfig(): KetchConfig {
  val path = defaultConfigPath()
  if (!File(path).exists()) return KetchConfig()
  return try {
    FileConfigStore(path).load()
  } catch (e: Exception) {
    System.err.println("Ignoring $path, which can't be read: ${e.message}")
    KetchConfig()
  }
}

/**
 * Opens the task database at [dbPath], which the desktop app shares. A file SQLite cannot read
 * is moved aside, which stderr reports, and an empty database replaces it. When the database
 * cannot be opened at all, such as when it is locked, the process exits with status 1 after
 * reporting why, so a service manager or MCP client sees the failure.
 */
private fun openTaskStore(dbPath: String): SqliteTaskStore {
  val driverFactory = DriverFactory(dbPath) { unreadable ->
    System.err.println(
      "Moved $dbPath aside to ${unreadable.movedTo}, as it can't be read " +
        "(${unreadable.cause?.message}); starting with no downloads"
    )
  }
  return try {
    SqliteTaskStore(driverFactory.createDriver())
  } catch (e: Exception) {
    System.err.println("Error opening $dbPath: ${e.message}")
    exitProcess(1)
  }
}

private fun printUsage() {
  println("Usage: ketch [options] <url> [destination]")
  println("       ketch server [options]")
  println("       ketch mcp [options]")
  println("       ketch ai-discover <query> [options]")
  println("       ketch update [options]")
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
  println("  -H, --header <header>    Send a request header, e.g.")
  println("                           -H 'Cookie: sid=1'; repeatable")
  println("  --user-agent <value>     Send this User-Agent instead of")
  println("                           Ketch/<version>")
  println("  --referer <url>          Send this Referer")
  println()
  println("Server:")
  println("  server [options]         Start Ketch daemon server")
  println("                           Run `ketch server --help`")
  println("                           for server options")
  println()
  println("MCP Server:")
  println("  mcp [options]            Start MCP server (stdio)")
  println("                           Run `ketch mcp --help`")
  println("                           for MCP options")
  println()
  println("AI Discovery:")
  println("  ai-discover <query>      Discover downloadable resources")
  println("                           Configure the provider in the")
  println("                           app's Settings page, or export a")
  println("                           provider API key")
  println("                           Run `ketch ai-discover --help`")
  println("                           for all discover options")
  println("    --sites <domains>      Only use these comma-separated sites")
  println("                           (subdomains included)")
  println("    --max-results <n>      Max results (default: 5)")
  println("    -y, --yes              Open websites without asking")
  println()
  println("  Provider env vars (fill blank settings):")
  println("    OPENAI_API_KEY         OpenAI or compatible endpoints")
  println("    ANTHROPIC_API_KEY      Anthropic")
  println("    GEMINI_API_KEY         Google Gemini")
  println()
  println("  Search env vars (checked in order):")
  println("    BRAVE_SEARCH_API_KEY   Use Brave Search API")
  println("    GOOGLE_SEARCH_API_KEY  Use Google Custom Search")
  println("    GOOGLE_SEARCH_CX      Google Search Engine ID")
  println()
  println("Update:")
  println("  update [options]         Replace this binary with the latest")
  println("                           release; run `ketch update --help`")
  println("                           for options")
  println()
  println("Examples:")
  println("  ketch https://example.com/file.zip")
  println("  ketch -v https://example.com/file.zip")
  println("  ketch --priority high https://example.com/file.zip")
  println("  ketch server --port 9000 --dir /tmp/downloads")
  println("  ketch ai-discover \"latest Ubuntu ISO\"")
  println("  ketch ai-discover \"ffmpeg\" --sites ffmpeg.org")
}

private fun printServerUsage() {
  println("Usage: ketch server [options]")
  println()
  println("Options:")
  println("  --config <path>        Path to TOML config file")
  println("  --generate-config      Generate default config and exit")
  println("  --host <address>       Bind address (default: 0.0.0.0)")
  println("  --port <number>        Port number (default: 8642)")
  println("  --token <string>       API bearer token; also read from")
  println("                         $TOKEN_ENV. Without one, a server")
  println("                         listening beyond loopback creates")
  println("                         one and saves it to")
  println("                         ${File(defaultConfigDir(), TOKEN_FILE_NAME)}")
  println("  --no-token             Require no token, even beyond")
  println("                         loopback (anyone on the network")
  println("                         can use the server)")
  println("  --cors <origins>       Origins whose web pages may call")
  println("                         the API, comma-separated or '*'")
  println("                         (default with a token: '*')")
  println("  --allowed-hosts <names>")
  println("                         Extra Host names accepted without")
  println("                         a token, comma-separated (optional)")
  println("  --allowed-dirs <paths> Folders besides --dir that clients")
  println("                         may save to and delete from,")
  println("                         comma-separated; with a token,")
  println("                         setting it also keeps clients to them")
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
  println("  ketch server --token my-secret --cors 'localhost:3000'")
  println("  ketch server --host 127.0.0.1 --no-token")
  println("  ketch server --allowed-dirs /srv/media,/srv/iso")
  println("  ketch server --speed-limit 10m")
  println("  ketch server --config /path/to/config.toml")
  println("  ketch server --generate-config")
}

internal fun formatBytes(bytes: Long): String {
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
