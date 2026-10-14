package com.linroid.ketch.server

import java.io.File

/**
 * Folders of this system where a new file can make it run code or change how it behaves, which
 * a [KetchServer] with an access token keeps callers out of when they may otherwise save
 * anywhere: the system's own folders, and the user's application data (`~/Library` on macOS,
 * `AppData` on Windows; hidden entries such as `~/.ssh` and `~/.config` are refused anyway).
 *
 * Apps add their own data and configuration folders, which this cannot know.
 */
fun systemProtectedDirectories(): List<String> = buildList {
  val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
  if (home != null) {
    add(File(home, "Library").path)
    add(File(home, "AppData").path)
  }
  if (File.separatorChar == '\\') {
    listOf("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData")
      .mapNotNullTo(this) { System.getenv(it)?.takeIf(String::isNotBlank) }
  } else {
    addAll(UNIX_SYSTEM_DIRECTORIES)
  }
}.distinct()

private val UNIX_SYSTEM_DIRECTORIES = listOf(
  "/Applications",
  "/Library",
  "/System",
  "/bin",
  "/boot",
  "/etc",
  "/lib",
  "/lib64",
  "/opt",
  "/sbin",
  "/usr",
)
