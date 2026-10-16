package com.linroid.ketch.app.desktop

import com.linroid.ketch.server.systemProtectedDirectories
import java.io.File

/**
 * The folders other devices may not save to through the shared server: the system's, the app's
 * data in [configDir] (its settings, database and token), and the app itself, where a new
 * library beside it, as in a portable copy, would be loaded the next time it starts.
 */
internal fun sharedServerProtectedDirectories(configDir: File): List<String> =
  systemProtectedDirectories() + listOfNotNull(configDir.path, appDirectory()?.path)

/**
 * The folder the packaged app runs from: its `.app` bundle on macOS, the folder holding
 * `Ketch.exe` on Windows, the one above `bin` on Linux; `null` when not run from a package.
 */
private fun appDirectory(): File? {
  val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return null
  val folder = File(launcher).absoluteFile.parentFile ?: return null
  val bundle = generateSequence(folder) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
  return bundle ?: if (folder.name == "bin") folder.parentFile else folder
}
