package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import java.awt.Desktop
import java.io.File
import java.io.IOException

/**
 * macOS sends links the app is registered to open (`magnet:` and `ketch:`, declared in the
 * bundle's `CFBundleURLTypes`) as events rather than arguments, including the one that launched
 * the app. Events that arrive before this runs are queued by the JDK.
 */
internal fun installOpenUriHandler(onUri: (String) -> Unit) {
  if (!Desktop.isDesktopSupported()) return
  val desktop = Desktop.getDesktop()
  if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
    desktop.setOpenURIHandler { event -> onUri(event.uri.toString()) }
  }
}

/**
 * Makes Ketch the app that opens `magnet:` links or `.torrent` files, when the user asks for it
 * from Settings. The packaged apps already declare both, so the system offers Ketch for them;
 * this makes Ketch the default.
 *
 * - macOS: Launch Services, through `osascript`, for the bundle [MAC_BUNDLE_ID].
 * - Windows: `HKCU\Software\Classes` entries, imported from a `.reg` file.
 * - Linux: a hidden desktop entry, `ketch-handler.desktop`, that opens the links and files,
 *   made the default with `xdg-mime`.
 *
 * Each registration throws [IOException] when the system refuses it.
 *
 * @param runCommand runs a command and returns its exit code.
 * @param runScript runs a JavaScript for Automation script and returns what it printed.
 */
internal class MagnetHandler(
  private val os: DesktopOs = DesktopOs.current,
  private val home: File = File(System.getProperty("user.home")),
  private val appCommand: AppCommand = AppCommand.current(),
  private val linuxDataHome: File = System.getenv("XDG_DATA_HOME")?.let(::File)
    ?: File(home, ".local/share"),
  private val runCommand: (List<String>) -> Int = ::runProcess,
  private val runScript: (String) -> String = ::runJavaScript,
) {
  private val log = KetchLogger("MagnetHandler")

  /** Makes Ketch open `magnet:` links. */
  fun registerMagnet() {
    when (os) {
      DesktopOs.MAC -> setMacDefault(macMagnetScript())
      DesktopOs.WINDOWS -> importRegFile(windowsMagnetRegFile(), runCommand)
      DesktopOs.LINUX -> setLinuxDefault(MAGNET_MIME_TYPE)
    }
  }

  /** Makes Ketch open `.torrent` files. */
  fun registerTorrentFiles() {
    when (os) {
      DesktopOs.MAC -> setMacDefault(macTorrentScript())
      DesktopOs.WINDOWS -> importRegFile(windowsTorrentRegFile(), runCommand)
      DesktopOs.LINUX -> setLinuxDefault(TORRENT_MIME_TYPE)
    }
  }

  /** The `.reg` file that makes Ketch open `magnet:` links, as an opt-in URL protocol. */
  internal fun windowsMagnetRegFile(): String {
    val classes = "HKCU\\Software\\Classes\\magnet"
    return regFile(
      linkedMapOf(
        classes to mapOf("@" to "URL:Magnet link", "URL Protocol" to ""),
        "$classes\\DefaultIcon" to mapOf("@" to "\"${appCommand.command.first()}\",0"),
        "$classes\\shell\\open\\command" to mapOf("@" to openCommand()),
      ),
    )
  }

  /** The `.reg` file that makes Ketch open `.torrent` files. */
  internal fun windowsTorrentRegFile(): String {
    val classes = "HKCU\\Software\\Classes"
    return regFile(
      linkedMapOf(
        "$classes\\.torrent" to mapOf("@" to TORRENT_PROG_ID),
        "$classes\\$TORRENT_PROG_ID" to mapOf("@" to "BitTorrent file"),
        "$classes\\$TORRENT_PROG_ID\\DefaultIcon" to
          mapOf("@" to "\"${appCommand.command.first()}\",0"),
        "$classes\\$TORRENT_PROG_ID\\shell\\open\\command" to mapOf("@" to openCommand()),
      ),
    )
  }

  /** The hidden desktop entry that opens links and files with Ketch. */
  internal fun linuxDesktopEntry(): String {
    val exec = desktopEntryExec(appCommand.startCommand()) + " %U"
    return """
      |[Desktop Entry]
      |Type=Application
      |Name=Ketch
      |Comment=Opens magnet links and .torrent files in Ketch
      |Exec=$exec
      |MimeType=$MAGNET_MIME_TYPE;$TORRENT_MIME_TYPE;
      |NoDisplay=true
      |Terminal=false
      |""".trimMargin()
  }

  /** The script that makes the bundle the default app for `magnet:` links. */
  internal fun macMagnetScript(): String =
    "ObjC.import('CoreServices');\n" +
      "\$.LSSetDefaultHandlerForURLScheme(\$('magnet'), \$('$MAC_BUNDLE_ID'))"

  /** The script that makes the bundle the default app for `.torrent` files. */
  internal fun macTorrentScript(): String =
    "ObjC.import('CoreServices');\n" +
      "var type = \$.UTTypeCreatePreferredIdentifierForTag(" +
      "\$.kUTTagClassFilenameExtension, \$('torrent'), null);\n" +
      "\$.LSSetDefaultRoleHandlerForContentType(type, \$.kLSRolesAll, \$('$MAC_BUNDLE_ID'))"

  // The Windows command line that opens the link or file in %1 with Ketch.
  private fun openCommand(): String = windowsCommandLine(appCommand.command) + " \"%1\""

  // Launch Services only knows the bundle, so a run from Gradle or an IDE cannot register.
  private fun setMacDefault(script: String) {
    if (appCommand.macBundle == null) throw IOException("Only the installed app can register")
    val status = runScript(script).trim()
    if (status != "0") throw IOException("Launch Services refused with status $status")
  }

  private fun setLinuxDefault(mimeType: String) {
    val applications = File(linuxDataHome, "applications").apply { mkdirs() }
    File(applications, LINUX_ENTRY).writeText(linuxDesktopEntry())
    // Refreshes the MIME cache where the tool exists; xdg-mime works without it.
    try {
      runCommand(listOf("update-desktop-database", applications.path))
    } catch (e: IOException) {
      log.d { "Couldn't run update-desktop-database: ${e.describeCauses()}" }
    }
    val exit = runCommand(listOf("xdg-mime", "default", LINUX_ENTRY, mimeType))
    if (exit != 0) throw IOException("xdg-mime exited with $exit")
  }

  internal companion object {
    /** The packaged app's bundle identifier (`bundleID` in `app/desktop/build.gradle.kts`). */
    const val MAC_BUNDLE_ID = "com.linroid.ketch.app.desktop"
    private const val MAGNET_MIME_TYPE = "x-scheme-handler/magnet"
    private const val TORRENT_MIME_TYPE = "application/x-bittorrent"
    private const val TORRENT_PROG_ID = "Ketch.torrent"
    private const val LINUX_ENTRY = "ketch-handler.desktop"
  }
}

private fun runJavaScript(script: String): String {
  val process = ProcessBuilder("osascript", "-l", "JavaScript", "-e", script)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
  val output = process.inputStream.bufferedReader().use { it.readText() }
  process.waitFor()
  return output
}
