package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.DetectedBrowser
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The [IntegrationStatus] the desktop app provides through [LocalIntegrationStatus]: the
 * browsers installed for this user, which of them the Ketch extension has connected from, and
 * whether Ketch opens `magnet:` links and `.torrent` files.
 *
 * The browsers the extension connected from are kept in [file], since the extension only
 * connects when it needs the app. Safe to call from any thread.
 *
 * @param browsers names of the browsers installed for this user, as
 *   [NativeHostRegistration.browsers] finds them; blocks.
 * @param handlers asks the system which apps open links and files.
 */
@Stable
internal class DesktopIntegrationStatus(
  private val file: File,
  private val browsers: () -> List<String>,
  private val handlers: DefaultHandlers = DefaultHandlers(),
) {
  private val log = KetchLogger("IntegrationStatus")
  private val connected = load()
  private var detected = emptyList<String>()
  private var magnetHandler = false
  private var torrentFileHandler = false

  /** The current status. */
  var status: IntegrationStatus by mutableStateOf(build())
    private set

  /** Looks for browsers and asks the system for the default apps again. Blocks. */
  fun refresh() {
    val browsers = browsers()
    val magnet = handlers.opensMagnetLinks()
    val torrent = handlers.opensTorrentFiles()
    synchronized(this) {
      detected = browsers
      magnetHandler = magnet
      torrentFileHandler = torrent
      status = build()
    }
  }

  /**
   * Records that the extension connected from [browser], or from a browser that could not be
   * told when it is `null`.
   */
  @Synchronized
  fun extensionConnected(browser: String?) {
    val key = browser ?: UNKNOWN_BROWSER
    val first = connected.put(key, System.currentTimeMillis()) == null
    if (first) log.i { "The browser extension connected from ${browser ?: "a browser"}" }
    save()
    status = build()
  }

  private fun build(): IntegrationStatus {
    val names = (detected + connected.keys.filter { it != UNKNOWN_BROWSER }.sorted()).distinct()
    return IntegrationStatus(
      browsers = names.map { DetectedBrowser(it, extensionConnected = it in connected) },
      extensionConnected = connected.isNotEmpty(),
      magnetHandler = magnetHandler,
      torrentFileHandler = torrentFileHandler,
    )
  }

  private fun load(): MutableMap<String, Long> {
    val connected = LinkedHashMap<String, Long>()
    if (!file.isFile) return connected
    val props = Properties()
    try {
      file.inputStream().use { props.load(it) }
    } catch (e: IOException) {
      log.w { "Couldn't read ${file.path}: ${e.describeCauses()}" }
    } catch (e: IllegalArgumentException) {
      log.w { "Malformed ${file.path}: ${e.describeCauses()}" }
    }
    for (name in props.stringPropertyNames()) {
      props.getProperty(name).toLongOrNull()?.let { connected[name] = it }
    }
    return connected
  }

  private fun save() {
    val props = Properties()
    for ((name, at) in connected) props.setProperty(name, at.toString())
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.path + ".tmp")
      tmp.outputStream().use { props.store(it, "Browsers the Ketch extension connected from") }
      Files.move(
        tmp.toPath(), file.toPath(),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: IOException) {
      log.w { "Couldn't save ${file.path}: ${e.describeCauses()}" }
    }
  }

  private companion object {
    // Key of connections whose browser couldn't be told.
    const val UNKNOWN_BROWSER = "*"
  }
}

/**
 * [DesktopHooks] that ask [integration] for the default apps again once Ketch registered for
 * links or files, so the Integration page shows the change.
 */
internal fun DesktopHooks.refreshingDefaults(integration: DesktopIntegrationStatus): DesktopHooks =
  RefreshingHooks(this, integration)

private class RefreshingHooks(
  private val hooks: DesktopHooks,
  private val integration: DesktopIntegrationStatus,
) : DesktopHooks by hooks {
  override suspend fun registerMagnetHandler(): Boolean =
    hooks.registerMagnetHandler().also { refresh() }

  override suspend fun registerTorrentFileHandler(): Boolean =
    hooks.registerTorrentFileHandler().also { refresh() }

  private suspend fun refresh() {
    withContext(Dispatchers.IO) { integration.refresh() }
  }
}

/**
 * Asks the system which apps open `magnet:` links and `.torrent` files, to tell whether Ketch
 * does; see [MagnetHandler] for how Ketch registers.
 *
 * - macOS: Launch Services, through `osascript`, for the bundle [MagnetHandler.MAC_BUNDLE_ID].
 * - Windows: the `HKCU\Software\Classes` entries Ketch writes.
 * - Linux: `xdg-mime`'s default for the type.
 *
 * Each check blocks, and answers `false` when the system can't be asked.
 *
 * @param run runs a command and returns what it printed, or `null` when it failed.
 */
internal class DefaultHandlers(
  private val os: DesktopOs = DesktopOs.current,
  private val appCommand: AppCommand = AppCommand.current(),
  private val run: (List<String>) -> String? = ::runForOutput,
) {
  /** Whether Ketch opens `magnet:` links. */
  fun opensMagnetLinks(): Boolean = when (os) {
    DesktopOs.MAC -> isKetchBundle(
      "ObjC.import('CoreServices');\n" +
        "var app = \$.LSCopyDefaultHandlerForURLScheme(\$('magnet'));\n" +
        "app ? ObjC.unwrap(ObjC.castRefToObject(app)) : ''",
    )
    DesktopOs.WINDOWS -> {
      val command = query("HKCU\\Software\\Classes\\magnet\\shell\\open\\command")
      command != null && command.contains(appCommand.command.first(), ignoreCase = true)
    }
    DesktopOs.LINUX -> isKetchEntry("x-scheme-handler/magnet")
  }

  /** Whether Ketch opens `.torrent` files. */
  fun opensTorrentFiles(): Boolean = when (os) {
    DesktopOs.MAC -> isKetchBundle(
      "ObjC.import('CoreServices');\n" +
        "var type = \$.UTTypeCreatePreferredIdentifierForTag(" +
        "\$.kUTTagClassFilenameExtension, \$('torrent'), null);\n" +
        "var app = \$.LSCopyDefaultRoleHandlerForContentType(type, \$.kLSRolesAll);\n" +
        "app ? ObjC.unwrap(ObjC.castRefToObject(app)) : ''",
    )
    DesktopOs.WINDOWS -> {
      query("HKCU\\Software\\Classes\\.torrent")?.contains("Ketch", ignoreCase = true) == true
    }
    DesktopOs.LINUX -> isKetchEntry("application/x-bittorrent")
  }

  private fun isKetchBundle(script: String): Boolean {
    val bundle = run(listOf("osascript", "-l", "JavaScript", "-e", script))?.trim()
    return bundle.equals(MagnetHandler.MAC_BUNDLE_ID, ignoreCase = true)
  }

  // The default value of a registry key, in `reg query` output.
  private fun query(key: String): String? = run(listOf("reg", "query", key, "/ve"))

  // Ketch's own entry, or the one its package installs.
  private fun isKetchEntry(mimeType: String): Boolean =
    run(listOf("xdg-mime", "query", "default", mimeType))?.contains("ketch", ignoreCase = true)
      ?: false
}

/**
 * A running process, as far as [connectingBrowser] needs to know it.
 *
 * @property parent id of the process that started it, or `null`.
 * @property command its executable, or `null` when the system doesn't say.
 * @property arguments its arguments, empty where the system doesn't say, such as on Windows.
 */
internal data class ProcessEntry(
  val pid: Long,
  val parent: Long?,
  val command: String?,
  val arguments: List<String>,
)

/**
 * The browser whose Ketch extension is asking for the app right now: the one that started a
 * native messaging host ([NativeMessagingHost.FLAG]) that is still running, waiting for the
 * reply. The host is found by its arguments, or, where the system hides them, as another process
 * of the app's own executable. Browsers start hosts directly, or through a shell on Windows.
 *
 * @param processes the running processes, [runningProcesses] by default.
 * @param self this app's process.
 * @return the browser's name, as [browserNamed] gives it, or `null` when it can't be told.
 */
internal fun connectingBrowser(processes: List<ProcessEntry>, self: ProcessEntry): String? {
  val byPid = processes.associateBy { it.pid }
  val hosts = processes.filter { process ->
    val sameApp = process.arguments.isEmpty() && process.command?.equals(self.command) == true
    process.pid != self.pid && (NativeMessagingHost.FLAG in process.arguments || sameApp)
  }
  for (host in hosts) {
    var ancestor = host.parent?.let(byPid::get)
    repeat(MAX_HOST_ANCESTORS) {
      val process = ancestor ?: return@repeat
      browserNamed(process.command)?.let { return it }
      ancestor = process.parent?.let(byPid::get)
    }
  }
  return null
}

/** [connectingBrowser] among the processes running now; `null` when they can't be listed. */
internal fun connectingBrowser(): String? = try {
  connectingBrowser(runningProcesses(), ProcessHandle.current().toEntry())
} catch (e: RuntimeException) {
  KetchLogger("IntegrationStatus").d { "Couldn't list processes: ${e.describeCauses()}" }
  null
}

/** The processes running now, as far as the system shows them. */
internal fun runningProcesses(): List<ProcessEntry> =
  ProcessHandle.allProcesses().iterator().asSequence().map { it.toEntry() }.toList()

private fun ProcessHandle.toEntry(): ProcessEntry {
  val info = info()
  return ProcessEntry(
    pid = pid(),
    parent = parent().map { it.pid() }.orElse(null),
    command = info.command().orElse(null),
    arguments = info.arguments().map { it.toList() }.orElse(emptyList()),
  )
}

/**
 * The name of the browser whose executable is [command], such as "Chrome" for
 * `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` or `chrome.exe`; `null` for
 * anything else. Names match [NativeHostRegistration.browsers].
 */
internal fun browserNamed(command: String?): String? {
  val name = command?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase()
    ?.removeSuffix(".exe") ?: return null
  return when {
    name.startsWith("google chrome") || name == "chrome" -> "Chrome"
    name.startsWith("microsoft edge") || name == "msedge" -> "Edge"
    name.startsWith("brave") -> "Brave"
    name.startsWith("firefox") -> "Firefox"
    name == "arc" -> "Arc"
    name.startsWith("vivaldi") -> "Vivaldi"
    name.startsWith("chromium") -> "Chromium"
    else -> null
  }
}

/**
 * Runs [command] and returns what it printed, or `null` when it could not start, failed or took
 * longer than a few seconds. Blocks.
 */
internal fun runForOutput(command: List<String>): String? {
  val process = try {
    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  } catch (_: IOException) {
    return null
  }
  // The commands run here print a line or two, which the pipe holds until they are read.
  if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    return null
  }
  if (process.exitValue() != 0) return null
  return process.inputStream.bufferedReader().use { it.readText() }
}

// The host, the shell a browser may start it through, and the browser.
private const val MAX_HOST_ANCESTORS = 3
private const val COMMAND_TIMEOUT_SECONDS = 5L
