package com.linroid.ketch.app.desktop

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Opens Ketch when the user logs in: a launch agent on macOS
 * (`~/Library/LaunchAgents/com.linroid.ketch.plist`), a `Run` registry value on Windows and an
 * autostart entry on Linux (`~/.config/autostart/ketch.desktop`). A hidden login item starts the
 * app with [BACKGROUND_FLAG].
 *
 * Writing it again on each launch keeps it pointing at the app after an update or a move.
 *
 * @param runCommand runs a command and returns its exit code.
 */
internal class LoginItem(
  private val os: DesktopOs = DesktopOs.current,
  private val home: File = File(System.getProperty("user.home")),
  private val appCommand: AppCommand = AppCommand.current(),
  private val linuxConfigHome: File = System.getenv("XDG_CONFIG_HOME")?.let(::File)
    ?: File(home, ".config"),
  private val runCommand: (List<String>) -> Int = ::runProcess,
) {
  private val launchAgent: File get() = File(home, "Library/LaunchAgents/$LABEL.plist")
  private val autostartEntry: File get() = File(linuxConfigHome, "autostart/ketch.desktop")

  /** Whether Ketch opens at login. */
  fun isEnabled(): Boolean = when (os) {
    DesktopOs.MAC -> launchAgent.isFile
    DesktopOs.LINUX -> autostartEntry.isFile
    DesktopOs.WINDOWS -> runCommand(listOf("reg", "query", RUN_KEY, "/v", RUN_VALUE)) == 0
  }

  /**
   * Adds the login item, starting the app [hidden] or not, or removes it when not [enabled].
   *
   * @throws IOException when the file or the registry value cannot be written.
   */
  fun set(enabled: Boolean, hidden: Boolean) {
    when (os) {
      DesktopOs.MAC -> writeOrDelete(launchAgent, launchAgentPlist(hidden).takeIf { enabled })
      DesktopOs.LINUX ->
        writeOrDelete(autostartEntry, autostartDesktopEntry(hidden).takeIf { enabled })
      DesktopOs.WINDOWS -> {
        val value = runValue(hidden).takeIf { enabled }
        importRegFile(regFile(mapOf(RUN_KEY to mapOf(RUN_VALUE to value))), runCommand)
      }
    }
  }

  /** The launch agent that starts the app through Launch Services when the user logs in. */
  internal fun launchAgentPlist(hidden: Boolean): String {
    val arguments = appCommand.startCommand(launchArguments(hidden))
      .joinToString("\n") { "    <string>${xmlEscape(it)}</string>" }
    return PLIST_HEADER + """
      |<plist version="1.0">
      |<dict>
      |  <key>Label</key>
      |  <string>$LABEL</string>
      |  <key>ProgramArguments</key>
      |  <array>
      |$arguments
      |  </array>
      |  <key>RunAtLoad</key>
      |  <true/>
      |  <key>LimitLoadToSessionType</key>
      |  <string>Aqua</string>
      |</dict>
      |</plist>
      |""".trimMargin()
  }

  /** The XDG autostart entry that starts the app when the user logs in. */
  internal fun autostartDesktopEntry(hidden: Boolean): String {
    val exec = desktopEntryExec(appCommand.startCommand(launchArguments(hidden)))
    return """
      |[Desktop Entry]
      |Type=Application
      |Name=Ketch
      |Comment=Opens Ketch when you log in
      |Exec=$exec
      |Terminal=false
      |X-GNOME-Autostart-enabled=true
      |""".trimMargin()
  }

  /** The command line of the `Run` registry value, each part quoted for Windows. */
  internal fun runValue(hidden: Boolean): String =
    windowsCommandLine(appCommand.startCommand(launchArguments(hidden)))

  private fun launchArguments(hidden: Boolean): List<String> =
    if (hidden) listOf(BACKGROUND_FLAG) else emptyList()

  companion object {
    /** Label of the macOS launch agent. */
    const val LABEL = "com.linroid.ketch"

    private const val PLIST_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
      "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
      "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "Ketch"
  }
}

private fun writeOrDelete(file: File, content: String?) {
  if (content == null) {
    if (file.exists() && !file.delete()) throw IOException("Couldn't delete ${file.path}")
    return
  }
  file.parentFile?.mkdirs()
  file.writeText(content)
}

private fun xmlEscape(text: String): String = text
  .replace("&", "&amp;")
  .replace("<", "&lt;")
  .replace(">", "&gt;")
  .replace("\"", "&quot;")

/**
 * The `Exec` value of a desktop entry for [command]. Arguments with spaces or reserved characters
 * are quoted, with `"`, `` ` ``, `$` and `\` escaped inside the quotes, and `%` doubled so it is
 * not read as a field code. Backslashes are doubled once more, as entry values unescape them
 * before the command line is split.
 */
internal fun desktopEntryExec(command: List<String>): String = command.joinToString(" ") { arg ->
  val escaped = arg.replace("%", "%%")
  if (escaped.isNotEmpty() && escaped.none { it in DESKTOP_ENTRY_RESERVED }) {
    escaped
  } else {
    val quoted = escaped.replace(Regex("""(["`$\\])"""), """\\$1""")
    "\"" + quoted.replace("\\", "\\\\") + "\""
  }
}

private const val DESKTOP_ENTRY_RESERVED = " \t\n\"'\\><~|&;$*?#()`"

/** [command] as one Windows command line, each part in double quotes. */
internal fun windowsCommandLine(command: List<String>): String =
  command.joinToString(" ") { "\"${it.replace("\"", "\\\"")}\"" }

/**
 * A `.reg` file that sets values under `HKEY_CURRENT_USER`. [keys] maps each key, written with
 * `HKCU`, to its values by name, `@` for the default value; a `null` value deletes it.
 */
internal fun regFile(keys: Map<String, Map<String, String?>>): String = buildString {
  append("Windows Registry Editor Version 5.00\r\n")
  for ((key, values) in keys) {
    append("\r\n[").append(key.replaceFirst("HKCU\\", "HKEY_CURRENT_USER\\")).append("]\r\n")
    for ((name, value) in values) {
      append(if (name == "@") "@" else "\"${regEscape(name)}\"")
      append("=")
      append(if (value == null) "-" else "\"${regEscape(value)}\"")
      append("\r\n")
    }
  }
}

private fun regEscape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Imports [content], a [regFile], with `reg import`, which takes `.reg` files as UTF-16 with a
 * byte order mark.
 *
 * @throws IOException when the import fails.
 */
internal fun importRegFile(content: String, runCommand: (List<String>) -> Int) {
  val file = File.createTempFile("ketch-", ".reg")
  try {
    val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    file.writeBytes(bom + content.toByteArray(StandardCharsets.UTF_16LE))
    val exit = runCommand(listOf("reg", "import", file.path))
    if (exit != 0) throw IOException("reg import exited with $exit")
  } finally {
    file.delete()
  }
}

/** Runs [command] with its output discarded and returns its exit code. */
internal fun runProcess(command: List<String>): Int =
  ProcessBuilder(command)
    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
    .waitFor()
