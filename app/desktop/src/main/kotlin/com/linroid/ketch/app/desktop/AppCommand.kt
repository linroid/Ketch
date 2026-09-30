package com.linroid.ketch.app.desktop

import java.io.File

/**
 * How another process starts this app: the native launcher of an installed app, whose path
 * jpackage publishes as `jpackage.app-path`, or, when running from Gradle or an IDE, this JVM with
 * its class path.
 *
 * @property command the executable and its arguments
 * @property macBundle the `.app` bundle on macOS, started through Launch Services so it runs as
 *   a normal app. Started directly, macOS counts the browser as responsible for it and
 *   quarantines the native libraries it extracts, which then hang on loading.
 */
internal data class AppCommand(
  val command: List<String>,
  val macBundle: File? = null,
) {

  /** Starts the app so it outlives the process that started it, with no console attached. */
  fun launchDetached() {
    val launch = when {
      macBundle != null -> listOf("open", macBundle.path)
      DesktopOs.current == DesktopOs.WINDOWS -> listOf("cmd", "/c", "start", "\"\"") + command
      // A new session, so closing the browser's native messaging host doesn't take it along.
      File("/usr/bin/setsid").exists() -> listOf("/usr/bin/setsid") + command
      else -> command
    }
    val nullDevice = File(if (DesktopOs.current == DesktopOs.WINDOWS) "NUL" else "/dev/null")
    ProcessBuilder(launch)
      .redirectInput(ProcessBuilder.Redirect.from(nullDevice))
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
  }

  companion object {
    private const val MAIN_CLASS = "com.linroid.ketch.app.desktop.MainKt"

    /** The command that starts this app, as it was started. */
    fun current(): AppCommand {
      System.getProperty("jpackage.app-path")?.let { path ->
        // macOS launchers live in Ketch.app/Contents/MacOS.
        val bundle = File(path).parentFile?.parentFile?.parentFile
        return AppCommand(listOf(path), bundle?.takeIf { it.name.endsWith(".app") })
      }
      val java = ProcessHandle.current().info().command().orElse(null)
        ?: File(System.getProperty("java.home"), "bin/java").path
      return AppCommand(listOf(java, "-cp", System.getProperty("java.class.path"), MAIN_CLASS))
    }
  }
}

/** The desktop platforms, which differ in where browsers look for native messaging hosts. */
internal enum class DesktopOs {
  MAC,
  WINDOWS,
  LINUX,
  ;

  companion object {
    val current: DesktopOs = System.getProperty("os.name", "").lowercase().let {
      when {
        it.startsWith("mac") -> MAC
        it.startsWith("windows") -> WINDOWS
        else -> LINUX
      }
    }
  }
}
