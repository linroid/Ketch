package com.linroid.ketch.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Registers this app as the native messaging host [NativeMessagingHost.NAME] with the browsers
 * installed for this user, so the Ketch extension can reach it. Runs on every launch, since
 * updates and moves change where the app lives.
 *
 * Writes a launcher script into `<configDir>/native-messaging` that starts the app with
 * [NativeMessagingHost.FLAG], and a host manifest naming the script and the extensions allowed
 * to use it. macOS and Linux browsers read manifests from their own directories, where one is
 * copied when the browser's data directory exists; on Windows, registry values point to them.
 *
 * @param runCommand runs `reg.exe` on Windows
 * @param windowsLocalAppData `%LOCALAPPDATA%`, where Chromium browsers keep their data on Windows
 * @param windowsAppData `%APPDATA%`, where Firefox keeps its data on Windows
 */
internal class NativeHostRegistration(
  private val configDir: File,
  private val home: File,
  private val os: DesktopOs = DesktopOs.current,
  private val appCommand: AppCommand = AppCommand.current(),
  private val linuxConfigHome: File = System.getenv("XDG_CONFIG_HOME")?.let(::File)
    ?: File(home, ".config"),
  private val runCommand: (List<String>) -> Unit = ::runQuietly,
  private val windowsLocalAppData: File = System.getenv("LOCALAPPDATA")?.let(::File)
    ?: File(home, "AppData/Local"),
  private val windowsAppData: File = System.getenv("APPDATA")?.let(::File)
    ?: File(home, "AppData/Roaming"),
) {

  /** Registers the host and returns where manifests were written or registry values set. */
  fun register(): List<String> {
    val dir = File(configDir, "native-messaging").apply { mkdirs() }
    val scriptName = if (os == DesktopOs.WINDOWS) "ketch-native-host.bat" else "ketch-native-host"
    val script = File(dir, scriptName)
    script.writeText(hostScript())
    if (os != DesktopOs.WINDOWS) script.setExecutable(true, true)

    val chromium = File(dir, "chromium.json").apply { writeText(chromiumManifest(script)) }
    val firefox = File(dir, "firefox.json").apply { writeText(firefoxManifest(script)) }
    if (os == DesktopOs.WINDOWS) {
      val keys = WINDOWS_CHROMIUM_KEYS.map { it to chromium } + (WINDOWS_FIREFOX_KEY to firefox)
      return keys.map { (key, manifest) ->
        val path = "$key\\${NativeMessagingHost.NAME}"
        runCommand(listOf("reg", "add", path, "/ve", "/t", "REG_SZ", "/d", manifest.path, "/f"))
        path
      }
    }
    return manifestLocations().filter { it.browserDir.isDirectory }.map { location ->
      location.manifestDir.mkdirs()
      File(location.manifestDir, "${NativeMessagingHost.NAME}.json").writeText(
        if (location.firefox) firefoxManifest(script) else chromiumManifest(script),
      )
      location.manifestDir.path
    }
  }

  /**
   * Names of the browsers installed for this user that the host is registered with, such as
   * "Chrome" and "Firefox", once each and most common first; a browser counts once its data
   * directory exists. Names match [browserNamed].
   */
  fun browsers(): List<String> {
    val installed = if (os == DesktopOs.WINDOWS) {
      windowsBrowserDirs().filter { (_, dir) -> dir.isDirectory }.map { (name, _) -> name }
    } else {
      manifestLocations().filter { it.browserDir.isDirectory }.map { it.browser }
    }
    return installed.distinct().sortedBy { name ->
      BROWSER_ORDER.indexOf(name).takeIf { it >= 0 } ?: BROWSER_ORDER.size
    }
  }

  // Data directories of the browsers WINDOWS_CHROMIUM_KEYS and WINDOWS_FIREFOX_KEY cover.
  private fun windowsBrowserDirs(): List<Pair<String, File>> = listOf(
    "Chrome" to File(windowsLocalAppData, "Google/Chrome/User Data"),
    "Chromium" to File(windowsLocalAppData, "Chromium/User Data"),
    "Edge" to File(windowsLocalAppData, "Microsoft/Edge/User Data"),
    "Brave" to File(windowsLocalAppData, "BraveSoftware/Brave-Browser/User Data"),
    "Firefox" to File(windowsAppData, "Mozilla/Firefox"),
  )

  /** A script that starts this app as the host, passing on the browser's arguments. */
  internal fun hostScript(): String {
    val command = appCommand.command + NativeMessagingHost.FLAG
    return if (os == DesktopOs.WINDOWS) {
      val line = command.joinToString(" ") { "\"${it.replace("%", "%%")}\"" }
      "@echo off\r\n$line %*\r\n"
    } else {
      val line = command.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
      "#!/bin/sh\n# Written by Ketch on launch; browsers run it for the Ketch extension.\n" +
        "exec $line \"\$@\"\n"
    }
  }

  internal fun chromiumManifest(script: File): String = manifest(script) {
    val origins = CHROMIUM_EXTENSION_IDS.map { JsonPrimitive("chrome-extension://$it/") }
    put("allowed_origins", JsonArray(origins))
  }

  internal fun firefoxManifest(script: File): String = manifest(script) {
    put("allowed_extensions", JsonArray(listOf(JsonPrimitive(FIREFOX_EXTENSION_ID))))
  }

  private fun manifest(script: File, allowed: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
      put("name", NativeMessagingHost.NAME)
      put("description", "Ketch download manager")
      put("path", script.absolutePath)
      put("type", "stdio")
      allowed()
    }.toString()

  /** Where each browser installed for this user reads host manifests from. */
  internal fun manifestLocations(): List<ManifestLocation> = when (os) {
    DesktopOs.MAC -> {
      val support = File(home, "Library/Application Support")
      MAC_CHROMIUM_DIRS.map { (dir, browser) -> ManifestLocation(browser, File(support, dir)) } +
        ManifestLocation("Firefox", File(support, "Mozilla"), firefox = true)
    }
    DesktopOs.LINUX ->
      LINUX_CHROMIUM_DIRS.map { (dir, browser) ->
        ManifestLocation(browser, File(linuxConfigHome, dir))
      } + ManifestLocation(
        browser = "Firefox",
        browserDir = File(home, ".mozilla"),
        hostsDir = "native-messaging-hosts",
        firefox = true,
      )
    DesktopOs.WINDOWS -> emptyList()
  }

  /**
   * @property browser name of the browser, as [browsers] lists it
   * @property browserDir the browser's data directory, which exists once it has been used
   * @property firefox whether the browser takes Firefox's manifest format
   */
  internal data class ManifestLocation(
    val browser: String,
    val browserDir: File,
    private val hostsDir: String = "NativeMessagingHosts",
    val firefox: Boolean = false,
  ) {
    val manifestDir: File get() = File(browserDir, hostsDir)
  }

  companion object {
    /**
     * Chromium ids of the extension allowed to use the host: the one pinned by the `key` in
     * the extension's manifest. Add the Chrome Web Store and Edge Add-ons ids once published.
     */
    val CHROMIUM_EXTENSION_IDS = listOf("kddcjkhnjcjhekohejnehplbnjclbdbl")

    /** The add-on id in the Firefox build of the extension. */
    const val FIREFOX_EXTENSION_ID = "ketch@linroid.github.io"

    // Browsers in the order Settings lists them; the others follow.
    private val BROWSER_ORDER = listOf("Chrome", "Edge", "Brave", "Firefox")

    private val MAC_CHROMIUM_DIRS = listOf(
      "Google/Chrome" to "Chrome",
      "Google/Chrome Beta" to "Chrome",
      "Google/Chrome Dev" to "Chrome",
      "Google/Chrome Canary" to "Chrome",
      "Chromium" to "Chromium",
      "Microsoft Edge" to "Edge",
      "Microsoft Edge Beta" to "Edge",
      "Microsoft Edge Dev" to "Edge",
      "Microsoft Edge Canary" to "Edge",
      "BraveSoftware/Brave-Browser" to "Brave",
      "Vivaldi" to "Vivaldi",
      "Arc/User Data" to "Arc",
    )

    private val LINUX_CHROMIUM_DIRS = listOf(
      "google-chrome" to "Chrome",
      "google-chrome-beta" to "Chrome",
      "google-chrome-unstable" to "Chrome",
      "chromium" to "Chromium",
      "microsoft-edge" to "Edge",
      "microsoft-edge-beta" to "Edge",
      "microsoft-edge-dev" to "Edge",
      "BraveSoftware/Brave-Browser" to "Brave",
      "vivaldi" to "Vivaldi",
    )

    private val WINDOWS_CHROMIUM_KEYS = listOf(
      "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts",
      "HKCU\\Software\\Chromium\\NativeMessagingHosts",
      "HKCU\\Software\\Microsoft\\Edge\\NativeMessagingHosts",
      "HKCU\\Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts",
    )

    private const val WINDOWS_FIREFOX_KEY = "HKCU\\Software\\Mozilla\\NativeMessagingHosts"
  }
}

private fun runQuietly(command: List<String>) {
  val process = ProcessBuilder(command)
    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
  val exit = process.waitFor()
  check(exit == 0) { "${command.first()} exited with $exit" }
}
