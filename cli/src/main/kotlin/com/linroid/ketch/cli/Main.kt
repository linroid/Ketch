package com.linroid.ketch.cli

import ch.qos.logback.classic.Level
import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.ai.resolveAiSettingsFromEnv
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.ProxyMode
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.ai.DiscoverDevice
import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.DiscoveryException
import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.config.CONFIG_DIR_ENV
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
import com.linroid.ketch.dash.DashDownloadSource
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.hls.HlsDownloadSource
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
  // banner to stderr itself; health and the commands that work on a running instance print none
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
    in INSTANCE_COMMANDS -> exitProcess(runInstance(remaining.first(), remaining.drop(1)))
    // Health checks run it every few seconds: one line, no banner.
    "health" -> exitProcess(runHealth(remaining.drop(1)))
  }

  printBanner()

  when (remaining.firstOrNull()) {
    null -> printUsage()
    "server" -> {
      val status = runServer(remaining.drop(1).toTypedArray())
      if (status != 0) exitProcess(status)
    }
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
  val fileConfig = readDefaultConfig()
  val proxy = args.proxy ?: fileConfig.download.proxy
  when (proxy.mode) {
    ProxyMode.SYSTEM -> Unit
    ProxyMode.DIRECT -> println("Proxy: none")
    // The URL holds no credentials; they are kept apart.
    ProxyMode.MANUAL -> println("Proxy: ${proxy.url}")
  }
  println()

  val config = DownloadConfig(
    maxConcurrentDownloads = args.maxConcurrent,
    proxy = fileConfig.download.proxy,
  )

  val httpEngine = KtorHttpEngine.withNetworkInterfaces()
  val ketch = Ketch(
    httpEngine = httpEngine,
    config = config,
    logger = Logger.console(ketchLogLevel),
    additionalSources = listOf(
      FtpDownloadSource(), torrentSource(fileConfig.torrent),
      HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)
    ),
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

/**
 * Runs `ketch server` with [args], the arguments after `server`, until the server stops, and
 * returns its exit status: 0, 1 when it cannot start, or 2 for unusable arguments or
 * environment variables.
 */
private fun runServer(args: Array<String>): Int {
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
  var cliTorrentPort: Int? = null
  var configPath: String? = null

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--help", "-h" -> {
        printServerUsage()
        return 0
      }
      "--generate-config" -> {
        val path = defaultConfigPath()
        if (File(path).exists()) {
          System.err.println("Config file already exists: $path")
          System.err.println(
            "Delete it first if you want to regenerate."
          )
          return 1
        }
        generateConfig(path)
        println("Generated default config at: $path")
        return 0
      }
      "--config" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --config requires a value")
          printServerUsage()
          return 2
        }
        configPath = args[++i]
      }
      "--host" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --host requires a value")
          printServerUsage()
          return 2
        }
        cliHost = args[++i]
      }
      "--port" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --port requires a value")
          printServerUsage()
          return 2
        }
        val value = args[++i].toIntOrNull()
        if (value == null || value !in 1..65535) {
          System.err.println("Error: invalid port '${args[i]}'")
          return 2
        }
        cliPort = value
      }
      "--token" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --token requires a value")
          printServerUsage()
          return 2
        }
        cliToken = args[++i]
      }
      "--no-token" -> noToken = true
      "--cors" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --cors requires a value")
          printServerUsage()
          return 2
        }
        cliCorsOrigins = args[++i].split(",").map { it.trim() }
      }
      "--allowed-hosts" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --allowed-hosts requires a value")
          printServerUsage()
          return 2
        }
        cliAllowedHosts = args[++i].split(",").map { it.trim() }
      }
      "--allowed-dirs" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --allowed-dirs requires a value")
          printServerUsage()
          return 2
        }
        cliAllowedDirectories = args[++i].split(",").map { it.trim() }.filter { it.isNotEmpty() }
      }
      "--dir" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --dir requires a value")
          printServerUsage()
          return 2
        }
        cliDownloadDir = args[++i]
      }
      "--speed-limit" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --speed-limit requires a value")
          printServerUsage()
          return 2
        }
        cliSpeedLimit = SpeedLimit.parse(args[++i]) ?: run {
          System.err.println("Error: invalid speed limit '${args[i]}'")
          printServerUsage()
          return 2
        }
      }
      "--torrent-port" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --torrent-port requires a value")
          printServerUsage()
          return 2
        }
        val value = args[++i].toIntOrNull()
        if (value == null || value !in 0..65535) {
          System.err.println("Error: invalid torrent port '${args[i]}'")
          return 2
        }
        cliTorrentPort = value
      }
      else -> {
        System.err.println("Error: unknown option '${args[i]}'")
        printServerUsage()
        return 2
      }
    }
    i++
  }
  if (noToken && cliToken != null) {
    System.err.println("Error: --token and --no-token cannot be used together")
    return 2
  }

  // Load config: explicit --config path, or default path if exists,
  // or empty defaults
  val fileConfig = if (configPath != null) {
    try {
      FileConfigStore(configPath).load()
    } catch (e: Exception) {
      System.err.println("Error loading config: ${e.message}")
      return 1
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
        return 1
      }
    } else {
      KetchConfig()
    }
  }

  // Environment variables override config file values, and CLI flags both
  val environment = System.getenv()
  val envConfig = try {
    applyServerEnvironment(fileConfig, environment)
  } catch (e: IllegalArgumentException) {
    System.err.println("Error: ${e.message}")
    return 2
  }
  val defaultDownloadDir = System.getProperty("user.home") +
    File.separator + "Downloads"
  val mergedConfig = envConfig.copy(
    server = envConfig.server.copy(
      host = cliHost ?: envConfig.server.host,
      port = cliPort ?: envConfig.server.port,
      corsAllowedHosts = cliCorsOrigins
        ?: envConfig.server.corsAllowedHosts,
      allowedHosts = cliAllowedHosts
        ?: envConfig.server.allowedHosts,
      allowedDirectories = cliAllowedDirectories
        ?: envConfig.server.allowedDirectories,
    ),
    download = envConfig.download.copy(
      defaultDirectory = cliDownloadDir
        ?: envConfig.download.defaultDirectory
        ?: defaultDownloadDir,
      speedLimit = cliSpeedLimit
        ?: envConfig.download.speedLimit,
    ),
    torrent = envConfig.torrent.copy(
      listenPort = cliTorrentPort ?: envConfig.torrent.listenPort,
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
    return 1
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
  val instance = claimDownloads(SERVER_COMMAND)
  val taskStore = openTaskStore(dbPath)

  val httpEngine = KtorHttpEngine.withNetworkInterfaces()
  val ketch = Ketch(
    httpEngine = httpEngine,
    taskStore = taskStore,
    config = downloadConfig,
    name = instanceName,
    logger = Logger.console(ketchLogLevel),
    additionalSources = listOf(
      FtpDownloadSource(),
      torrentSource(mergedConfig.torrent, listenPort = mergedConfig.torrent.listenPort),
      HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)
    ),
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
    // Health checks wait for the saved tasks, which serveDaemon restores once it listens.
    ready = false,
  )

  Runtime.getRuntime().addShutdownHook(Thread {
    println("Shutting down Ketch server...")
    server.stop()
    ketch.close()
    instance.close()
  })

  println("Ketch Server v${KetchApi.VERSION}")
  println("  Host:          ${serverConfig.host}")
  println("  Port:          ${serverConfig.port}")
  println("  Download dir:  ${downloadConfig.defaultDirectory}")
  println("  Database:      $dbPath")
  if (configPath != null) {
    println("  Config:        $configPath")
  }
  serverEnvironmentNames(environment).takeIf { it.isNotEmpty() }?.let { names ->
    println("  Environment:   " + names.joinToString(", "))
  }
  val torrentPort = mergedConfig.torrent.listenPort
  println("  Torrent port:  " + if (torrentPort == 0) "any free one" else "$torrentPort (TCP, UDP)")
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

  serveDaemon(server, ketch) {
    // Other `ketch` commands on this machine attach to it through this.
    val port = runBlocking { server.port() }
    instance.publish(
      CliInstance.Info(
        command = SERVER_COMMAND,
        pid = ProcessHandle.current().pid(),
        url = loopbackUrl(serverConfig.host, port),
        token = serverConfig.apiToken,
      )
    )
  }
  return 0
}

/** How [CliInstance.Info] names `ketch server`. */
internal const val SERVER_COMMAND = "ketch server"

/** How [CliInstance.Info] names `ketch mcp --standalone`. */
private const val STANDALONE_MCP_COMMAND = "ketch mcp --standalone"

/**
 * Makes this process, which runs [command], the one that runs the downloads of the config
 * directory, before it opens their database. When the desktop app or another `ketch` process
 * already runs them, it says so and exits with status 1 instead: two engines on one database
 * would resume the same downloads into the same files.
 */
private fun claimDownloads(command: String): CliInstance {
  val configDir = File(defaultConfigDir())
  if (DesktopApp.isRunning(configDir)) {
    System.err.println(
      """
      |Error: the Ketch app is running, and it runs the downloads in ${defaultDbPath()}.
      |Quit it before running `$command`. To reach the app's downloads, use `ketch add`,
      |`ketch list` and `ketch mcp`, or turn on Settings > Sharing in the app for other devices.
      """.trimMargin()
    )
    exitProcess(1)
  }
  val instance = try {
    CliInstance.acquire(configDir)
  } catch (e: IOException) {
    val lockFile = File(configDir, CliInstance.LOCK_FILE)
    System.err.println("Error: couldn't lock $lockFile: ${e.message}")
    exitProcess(1)
  }
  if (instance == null) {
    val holder = CliInstance.read(configDir)
    val who = holder?.let { "`${it.command}` (process ${it.pid})" } ?: "another `ketch` process"
    System.err.println(
      """
      |Error: $who already runs the downloads in ${defaultDbPath()}.
      |Stop it before running `$command`, or use `ketch add`, `ketch list` and `ketch mcp`,
      |which work through it.
      """.trimMargin()
    )
    exitProcess(1)
  }
  return instance
}

/**
 * The address this machine reaches a server listening on [host] and [port] at: the loopback
 * address when it listens on every interface.
 */
internal fun loopbackUrl(host: String, port: Int): String {
  val name = host.trim().removePrefix("[").removeSuffix("]")
  val address = when {
    name.isEmpty() || name == "0.0.0.0" || name == "::" -> "127.0.0.1"
    ':' in name -> "[$name]"
    else -> name
  }
  return "http://$address:$port"
}

/**
 * Runs the attaching command [name], one of [INSTANCE_COMMANDS], with [args], the arguments
 * after its name, and returns its exit status, one of [InstanceExit]. Only the results, or the
 * usage asked for, go to [results]; errors and logs go to stderr.
 */
internal fun runInstance(name: String, args: List<String>, results: PrintStream = System.out): Int {
  // Logback's console appender and the console logger write to System.out; see runMcp.
  val stdout = System.out
  System.setOut(System.err)
  try {
    val command = when (val parsed = parseInstanceArgs(name, args)) {
      InstanceArgs.Help -> {
        printInstanceUsage(name, results)
        return InstanceExit.OK
      }
      is InstanceArgs.Invalid -> {
        System.err.println("Error: ${parsed.message}")
        System.err.println("Run `ketch $name --help` for usage.")
        return InstanceExit.USAGE
      }
      is InstanceArgs.Run -> parsed.command
    }
    // Only warnings unless -v or --debug asks for more, so the results stay readable.
    val level = if (ketchLogLevel < LogLevel.INFO) ketchLogLevel else LogLevel.WARN
    KetchLogger.setLogger(Logger.console(level))
    val locator = instanceLocator(command.target)
    return runBlocking { runInstanceCommand(command, locator::locate, results, System.err) }
  } finally {
    System.setOut(stdout)
  }
}

/** Finds the instance [target] names, or the one running on this machine. */
private fun instanceLocator(target: Target) = InstanceLocator(
  configDir = File(defaultConfigDir()),
  server = target.server ?: System.getenv(SERVER_ENV),
  token = target.token ?: System.getenv(TOKEN_ENV),
)

/**
 * Serves [ketch] through [server] until the server stops. It listens before restoring the
 * tasks saved by earlier runs, so a daemon that cannot start, e.g. because another one uses
 * the port, never resumes downloads into the same files; health checks report it ready once
 * they are restored. A failure to restore them stops the server.
 *
 * @param onReady called once the server listens and the tasks are restored, so a client it lets
 *   find the server sees every task
 */
internal fun serveDaemon(server: KetchServer, ketch: KetchApi, onReady: () -> Unit = {}) {
  server.start(wait = false)
  try {
    runBlocking { ketch.start() }
  } catch (e: Throwable) {
    server.stop()
    throw e
  }
  server.markReady()
  onReady()
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

  val base = when (val choice = chooseLlm(readDefaultConfig().ai, options.provider)) {
    is CliLlmChoice.Chosen -> choice.settings
    is CliLlmChoice.Unknown -> {
      System.err.println("Error: ${choice.message}")
      return AiDiscoverExit.USAGE
    }
  }
  val resolved = resolveAiSettingsFromEnv(base)
  // After the environment, which may add the provider the model is for.
  val settings = options.model?.let { resolved.withActive(resolved.llm.id, it) } ?: resolved
  if (!settings.isUsable) {
    val llm = settings.llm
    val envKey = llm.provider.envKeys.firstOrNull()
    if (options.provider != null && !llm.isComplete && envKey != null) {
      System.err.println("${llm.displayName} has no API key.")
      System.err.println("Set it on the app's Settings page, or export $envKey for this shell.")
    } else {
      System.err.println("AI discovery is not configured.")
      System.err.println("Set a provider and API token on the app's Settings page,")
      System.err.println("or export a provider API key for this shell.")
    }
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
    "Using ${settings.llm.displayName}" +
      " · ${settings.llm.effectiveModel}"
  )
  System.err.println("Discovering resources for: \"${options.query}\"")
  if (options.sites.isNotEmpty()) {
    System.err.println("Limited to: ${options.sites.joinToString(", ")}")
  }
  System.err.println()

  return runBlocking {
    // This machine searches, and downloads what `ketch <url>` is given.
    val machine = DiscoverDevice(
      os = System.getProperty("os.name").orEmpty(),
      arch = System.getProperty("os.arch").orEmpty(),
    )
    val discoverQuery = DiscoverQuery(
      query = options.query,
      sites = options.sites,
      maxResults = options.maxResults,
      contentFilter = settings.contentFilter && !options.noFilter,
      userDevice = machine,
      downloadDevice = machine,
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
  out.println("  --provider <name>    Search with this saved provider, by its id")
  out.println("                       or name, or with a provider such as")
  out.println("                       deepseek whose API key is in the")
  out.println("                       environment")
  out.println("  --model <id>         Call this model")
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
  out.println("  ketch ai-discover --provider deepseek \"krita 5 windows\"")
  out.println()
  out.println("Configure providers and keys on the app's Settings page,")
  out.println("or export a provider's API key, such as OPENAI_API_KEY,")
  out.println("ANTHROPIC_API_KEY, GEMINI_API_KEY or DEEPSEEK_API_KEY.")
}

private fun runMcp(args: List<String>) {
  var configPath: String? = null
  var cliDownloadDir: String? = null
  var server: String? = null
  var token: String? = null
  var standalone = false

  var i = 0
  while (i < args.size) {
    when (val arg = args[i]) {
      "--help", "-h" -> {
        printMcpUsage()
        return
      }
      "--standalone" -> standalone = true
      "--config", "--dir", "--server", "--token" -> {
        if (i + 1 >= args.size) {
          printMcpError("$arg requires a value")
          exitProcess(InstanceExit.USAGE)
        }
        val value = args[++i]
        when (arg) {
          "--config" -> configPath = value
          "--dir" -> cliDownloadDir = value
          "--server" -> server = value
          "--token" -> token = value
        }
      }
      else -> {
        printMcpError("unknown option '$arg'")
        exitProcess(InstanceExit.USAGE)
      }
    }
    i++
  }
  if (standalone && (server != null || token != null)) {
    printMcpError("--server and --token attach to an instance, which --standalone does not")
    exitProcess(InstanceExit.USAGE)
  }
  if (!standalone && (configPath != null || cliDownloadDir != null)) {
    printMcpError("--config and --dir set up the engine of --standalone")
    exitProcess(InstanceExit.USAGE)
  }

  // stdout carries the JSON-RPC stream, so keep it for the transport and send everything
  // else to stderr: the banner, the console logger, Logback and any library output.
  // Logback's console appender looks up System.out on every write, so this also covers the
  // Logback that -v or --debug initialized before this point.
  val protocolOut = System.out
  System.setOut(System.err)
  printBanner()

  if (standalone) {
    runStandaloneMcp(configPath, cliDownloadDir, protocolOut)
  } else {
    runAttachedMcp(Target(server, token), protocolOut)
  }
  // The client closed stdin, which ends the MCP session. Exit rather than rely on every
  // library thread being a daemon; the shutdown hooks close what is still open.
  exitProcess(0)
}

/**
 * Serves MCP on [protocolOut] for the instance the attaching commands find: the desktop app, a
 * server, or the `ketch` process running this machine's downloads. It looks for one when a tool
 * first needs it and again once it is lost, so the client may start before the app does.
 */
private fun runAttachedMcp(target: Target, protocolOut: PrintStream) {
  KetchLogger.setLogger(Logger.console(ketchLogLevel))
  val locator = instanceLocator(target)
  val connection = InstanceConnection.remote(locator::locate) { endpoint ->
    System.err.println("Working on the downloads of ${endpoint.label}")
  }
  Runtime.getRuntime().addShutdownHook(Thread { connection.close() })
  runBlocking { KetchMcpServer(connection::get).startStdio(output = protocolOut) }
}

/**
 * Serves MCP on [protocolOut] for an engine of its own on this machine's task database, as long
 * as nothing else runs those downloads. Its API listens on loopback, with a token made for this
 * run, so the attaching commands work through it meanwhile.
 */
private fun runStandaloneMcp(
  configPath: String?,
  cliDownloadDir: String?,
  protocolOut: PrintStream,
) {
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

  val instance = claimDownloads(STANDALONE_MCP_COMMAND)
  val taskStore = openTaskStore(defaultDbPath())

  val httpEngine = KtorHttpEngine.withNetworkInterfaces()
  val ketch = Ketch(
    httpEngine = httpEngine,
    taskStore = taskStore,
    config = downloadConfig,
    logger = Logger.console(ketchLogLevel),
    additionalSources = listOf(
      FtpDownloadSource(), torrentSource(fileConfig.torrent),
      HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)
    ),
  )
  val apiToken = newToken()
  val apiServer = KetchServer(
    ketch,
    host = "127.0.0.1",
    port = 0,
    apiToken = apiToken,
    mdnsEnabled = false,
  )

  Runtime.getRuntime().addShutdownHook(Thread {
    apiServer.stop()
    ketch.close()
    instance.close()
  })

  apiServer.start(wait = false)
  runBlocking {
    ketch.start()
    // Once the tasks are restored, so the attaching commands see every one.
    instance.publish(
      CliInstance.Info(
        command = STANDALONE_MCP_COMMAND,
        pid = ProcessHandle.current().pid(),
        url = loopbackUrl("127.0.0.1", apiServer.port()),
        token = apiToken,
      )
    )
    KetchMcpServer(ketch).startStdio(output = protocolOut)
  }
}

/**
 * Runs `ketch health` with [args], the arguments after `health`: asks the `ketch server` this
 * machine runs, found through its `instance.json` once it is ready and through the same config
 * file and environment variables until then, whether it is ready, and returns one of
 * [HealthExit].
 */
private fun runHealth(args: List<String>): Int {
  val check = when (val parsed = parseHealthArgs(args)) {
    HealthArgs.Help -> {
      printHealthUsage()
      return HealthExit.READY
    }
    is HealthArgs.Invalid -> {
      System.err.println("Error: ${parsed.message}")
      System.err.println("Run `ketch health --help` for usage.")
      return HealthExit.USAGE
    }
    is HealthArgs.Check -> parsed
  }
  val fileConfig = check.configPath?.let { path ->
    try {
      FileConfigStore(path).load()
    } catch (e: Exception) {
      System.err.println("Ignoring $path, which can't be read: ${e.message}")
      KetchConfig()
    }
  } ?: readDefaultConfig()
  val server = try {
    applyServerEnvironment(fileConfig, System.getenv()).server
  } catch (e: IllegalArgumentException) {
    System.err.println("Error: ${e.message}")
    return HealthExit.USAGE
  }
  val running = CliInstance.read(File(defaultConfigDir()))
  return checkHealth(healthCheckUrl(check, server, running))
}

private fun printHealthUsage() {
  println("Usage: ketch health [options]")
  println()
  println("Check whether the `ketch server` on this machine is ready, as")
  println("container health checks do. It asks where the running server")
  println("says it listens, else where its config file and KETCH_*")
  println("environment variables say.")
  println()
  println("Options:")
  println("  --host <address>  Address to ask (default: the server's;")
  println("                    127.0.0.1 for one on every interface)")
  println("  --port <number>   Port to ask (default: the server's)")
  println("  --config <path>   Config file the server was started with")
  println("  --help, -h        Show this help message")
  println()
  println("Exit status: 0 once the server serves its saved downloads; 1")
  println("while it restores them or when it cannot be reached; 2 for")
  println("invalid arguments or environment variables.")
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
  println("It works on the downloads of the Ketch app, or of the")
  println("`ketch server` running on this machine, found when a tool")
  println("first needs them, so start either before or after it.")
  println()
  println("Options:")
  println("  --server <url>    Use the Ketch server at this address;")
  println("                    also read from $SERVER_ENV")
  println("  --token <token>   Its access token; also read from")
  println("                    $TOKEN_ENV")
  println("  --standalone      Run downloads itself, as nothing else")
  println("                    on this machine does")
  println("  --config <path>   Path to TOML config file (--standalone)")
  println("  --dir <path>      Download directory (--standalone;")
  println("                    default: ~/Downloads)")
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

/**
 * Torrent source that persists DHT state and adds the configured extra trackers and tracker list.
 * Only `ketch server` passes a [listenPort], [TorrentSettings.listenPort]: the other commands
 * may run beside it, so they let the system pick a free port.
 */
private fun torrentSource(settings: TorrentSettings, listenPort: Int = 0) = TorrentDownloadSource(
  TorrentConfig(
    listenPort = listenPort,
    stateDirectory = File(defaultConfigDir(), "torrent-state").path,
    additionalTrackers = settings.trackers,
    trackerListUrls = settings.subscribedTrackerLists,
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
  println("       ketch add [options] <url> [destination]")
  println("       ketch list | pause | resume | watch [options]")
  println("       ketch server [options]")
  println("       ketch mcp [options]")
  println("       ketch ai-discover <query> [options]")
  println("       ketch update [options]")
  println("       ketch health [options]")
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
  println("  --proxy <url>            Download HTTP(S) through this proxy:")
  println("                           http://[user:pass@]host:port or")
  println("                           socks5://[user:pass@]host:port")
  println("  --proxy-bypass <hosts>   Hosts to reach directly with --proxy,")
  println("                           comma-separated, e.g. '*.lan,10.0.0.0/8'")
  println("  --no-proxy               Connect directly, without a proxy")
  println("                           Default: [download.proxy] in the config")
  println("                           file, else https_proxy, http_proxy,")
  println("                           all_proxy and no_proxy")
  println()
  println("Running Ketch (the app, or `ketch server`):")
  println("  add <url> [destination]  Add a download; prints its task ID")
  println("  list                     List the downloads")
  println("  pause <id>... | --all    Pause downloads")
  println("  resume <id>... | --all   Resume downloads")
  println("  watch [<id>...]          Print changes as JSON lines")
  println("                           Run `ketch <command> --help` for")
  println("                           their options")
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
  println("Health:")
  println("  health [options]         Exit 0 once the local server is ready;")
  println("                           run `ketch health --help` for options")
  println()
  println("Examples:")
  println("  ketch https://example.com/file.zip")
  println("  ketch -v https://example.com/file.zip")
  println("  ketch add https://example.com/file.zip ~/Downloads/")
  println("  ketch watch $(ketch add https://example.com/file.zip)")
  println("  ketch --priority high https://example.com/file.zip")
  println("  ketch server --port 9000 --dir /tmp/downloads")
  println("  ketch ai-discover \"latest Ubuntu ISO\"")
  println("  ketch ai-discover \"ffmpeg\" --sites ffmpeg.org")
}

/** Prints the usage of the attaching command [name] to [out]. */
private fun printInstanceUsage(name: String, out: PrintStream) {
  when (name) {
    "add" -> {
      out.println("Usage: ketch add [options] <url> [destination]")
      out.println()
      out.println("Add a download to the running Ketch and print its task ID.")
      out.println("Without a destination it goes to Ketch's download folder; a")
      out.println("relative path is taken from the current directory.")
    }
    "list" -> {
      out.println("Usage: ketch list [options]")
      out.println()
      out.println("List the downloads of the running Ketch.")
    }
    "pause", "resume" -> {
      out.println("Usage: ketch $name [options] <task-id>...")
      out.println("       ketch $name [options] --all")
      out.println()
      if (name == "pause") {
        out.println("Pause downloads of the running Ketch: those named, or every")
        out.println("waiting and running one. Any unique start of an ID will do.")
      } else {
        out.println("Resume downloads of the running Ketch: those named, or every")
        out.println("paused one. Any unique start of an ID will do.")
      }
    }
    "watch" -> {
      out.println("Usage: ketch watch [options] [<task-id>...]")
      out.println()
      out.println("Print the downloads of the running Ketch as JSON lines while")
      out.println("they change. Each line has an \"event\": snapshot (each task")
      out.println("when watch starts), added, state, progress or removed.")
      out.println("With task IDs it follows those only and exits once each has")
      out.println("finished: 0 when all completed, 1 otherwise.")
    }
  }
  out.println()
  out.println("It works on the Ketch app, or the `ketch server` running on")
  out.println("this machine, unless --server names another device.")
  out.println()
  out.println("Options:")
  if (name == "add") {
    out.println("  --speed-limit <value>    Limit its speed (e.g. 500k, 10m)")
    out.println("  --priority <level>       low, normal, high or urgent")
    out.println("  --connections <n>        Connections to use; 0 (default)")
    out.println("                           uses Ketch's setting")
    out.println("  -H, --header <header>    Send a request header, e.g.")
    out.println("                           -H 'Cookie: sid=1'; repeatable")
    out.println("  --user-agent <value>     Send this User-Agent")
    out.println("  --referer <url>          Send this Referer")
    out.println("  --proxy <url>            Download through this HTTP or SOCKS5")
    out.println("                           proxy instead of Ketch's setting")
    out.println("  --proxy-bypass <hosts>   Hosts to reach directly with --proxy")
    out.println("  --no-proxy               Connect directly, without a proxy")
    out.println("  --idempotency-key <key>  Running the command again with the")
    out.println("                           same key adds the download once")
  }
  if (name == "add" || name == "list") {
    out.println("  --json                   Print JSON instead")
  }
  if (name == "pause" || name == "resume") {
    out.println("  --all                    Every download it applies to")
  }
  out.println("  --server <url>           Use the Ketch server at this address;")
  out.println("                           also read from $SERVER_ENV")
  out.println("  --token <token>          Its access token; also read from")
  out.println("                           $TOKEN_ENV")
  out.println("  -h, --help               Show this help message")
  out.println()
  out.println("Exit status: 0 on success, 1 on failure, 2 for invalid arguments.")
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
  println("  --torrent-port <number>")
  println("                         Port for BitTorrent peers (TCP) and")
  println("                         DHT (UDP), to forward on a router;")
  println("                         0 picks a free one (default)")
  println("  --help, -h             Show this help message")
  println()
  println("Config file:")
  println("  Default location: ${defaultConfigPath()}")
  println("  Environment variables override config file values, and")
  println("  CLI flags override both.")
  println("  Use --generate-config to create a default file.")
  println()
  println("Environment variables:")
  println("  $CONFIG_DIR_ENV         Folder for config.toml, the")
  println("                           database and the token")
  println("  $TOKEN_ENV          --token")
  println("  ${ServerEnv.HOST}, ${ServerEnv.PORT}   --host, --port")
  println("  ${ServerEnv.DOWNLOAD_DIR}       --dir")
  println("  ${ServerEnv.ALLOWED_DIRS}       --allowed-dirs")
  println("  ${ServerEnv.ALLOWED_HOSTS}      --allowed-hosts")
  println("  ${ServerEnv.CORS}               --cors")
  println("  ${ServerEnv.SPEED_LIMIT}        --speed-limit")
  println("  ${ServerEnv.TORRENT_PORT}       --torrent-port")
  println("  ${ServerEnv.NAME}               name")
  println("  ${ServerEnv.MDNS}               mdnsEnabled (true or false)")
  println("  ${ServerEnv.MAX_CONCURRENT_DOWNLOADS}")
  println("                           maxConcurrentDownloads")
  println("  ${ServerEnv.MAX_CONNECTIONS_PER_DOWNLOAD}")
  println("                           maxConnectionsPerDownload")
  println("  ${ServerEnv.MAX_CONNECTIONS_PER_HOST}")
  println("                           maxConnectionsPerHost")
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
