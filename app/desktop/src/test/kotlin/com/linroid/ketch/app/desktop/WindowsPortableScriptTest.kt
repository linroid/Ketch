package com.linroid.ketch.app.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs `install-update-windows-portable.ps1`, on Windows only. */
class WindowsPortableScriptTest {
  private val windows = DesktopOs.current == DesktopOs.WINDOWS

  // Long names: the temporary folder can be a short 8.3 path, as on CI.
  private val dir = Files.createTempDirectory("ketch-portable").toFile().canonicalFile
  private val target = File(dir, "Ketch")
  private val workDir = File(target, "data/updates")
  private val source = File(workDir, "unpacked/Ketch")
  private val backup = File(workDir, "previous")
  private val calls = File(dir, "calls.txt")
  private val scriptLog = File(dir, "script.log")

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun script_newVersion_replacesTheAppAndKeepsTheData() {
    if (!windows) return
    app(target, "old")
    app(source, "new")

    runScript()

    assertEquals(listOf("new"), launches(), scriptLog.readText())
    assertEquals("new", File(target, "app/version").readText())
    assertEquals("new", File(target, "runtime/release").readText())
    assertEquals("config", File(target, "data/config.toml").readText())
    assertEquals(listOf("data"), source.list()?.toList())
    assertEquals("old", File(backup, "app/version").readText())
    assertEquals("old", File(backup, "runtime/release").readText())
  }

  @Test
  fun script_fileHeldOpen_putsTheOldVersionBack() {
    if (!windows) return
    app(target, "old")
    app(source, "new")
    // Sorts after the other items, so they move before this one fails.
    File(target, "version.txt").writeText("old")
    File(source, "version.txt").writeText("new")

    // Java opens files without letting others rename them.
    RandomAccessFile(File(target, "version.txt"), "r").use { runScript() }

    assertEquals(listOf("old"), launches(), scriptLog.readText())
    for (item in listOf("app/version", "runtime/release", "version.txt")) {
      assertEquals("old", File(target, item).readText(), item)
      assertEquals("new", File(source, item).readText(), item)
    }
    assertTrue(File(target, "Ketch.cmd").readText().contains("old"))
    assertEquals("config", File(target, "data/config.toml").readText())
  }

  @Test
  fun script_programRunFromTheFolder_waitsForItToEnd() {
    if (!windows) return
    app(target, "old")
    app(source, "new")
    val ping = File(target, "PING.EXE")
    systemPing().copyTo(ping)
    // With its messages, which live in language folders beside it.
    systemPing().parentFile.listFiles()?.forEach { language ->
      val messages = File(language, "PING.EXE.mui")
      if (messages.isFile) messages.copyTo(File(target, "${language.name}/${messages.name}"))
    }
    // Outlives the app below, so the script reaches the folder check while it runs.
    val helper = ProcessBuilder(ping.path, "-n", "15", "127.0.0.1")
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    try {
      runScript()

      assertFalse(helper.isAlive, "The script did not wait for ${ping.name}")
    } finally {
      helper.destroyForcibly()
      helper.waitFor(10, TimeUnit.SECONDS)
    }
    assertEquals(listOf("new"), launches(), scriptLog.readText())
    assertEquals("new", File(target, "app/version").readText())
    assertTrue(ping.isFile)
  }

  /**
   * Lays out an app in [folder] whose files say [version], with a launcher that writes it to
   * [calls]. The installed one has data, the new one an empty data folder, as in the release.
   */
  private fun app(folder: File, version: String) {
    File(folder, "app").mkdirs()
    File(folder, "app/version").writeText(version)
    File(folder, "runtime").mkdirs()
    File(folder, "runtime/release").writeText(version)
    File(folder, "Ketch.cmd").writeText("@echo $version>>\"${calls.path}\"\r\n")
    File(folder, PortableApp.DATA_DIR).mkdirs()
    if (folder == target) File(folder, "data/config.toml").writeText("config")
  }

  /** Runs the script after a process that is still running, which it waits for. */
  private fun runScript() {
    // About 7 seconds, well past PowerShell's start, so the script has to wait for it.
    val running = ProcessBuilder(systemPing().path, "-n", "8", "127.0.0.1")
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    val script = writeScript(workDir, "install-update-windows-portable.ps1")
    val command = listOf(
      "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
      "-File", script.path, "-ProcessId", running.pid().toString(),
      "-Source", source.path, "-Target", target.path, "-Backup", backup.path,
      "-App", File(target, "Ketch.cmd").path,
    )
    val process = ProcessBuilder(command)
      .directory(target)
      .redirectErrorStream(true)
      .redirectOutput(scriptLog)
      .start()
    try {
      assertTrue(process.waitFor(150, TimeUnit.SECONDS), "The script did not finish")
      assertFalse(running.isAlive, "The script did not wait for the app")
    } finally {
      process.destroyForcibly()
      running.destroyForcibly()
    }
  }

  /** What the launchers the script opened wrote, waiting up to 10 seconds for the first. */
  private fun launches(): List<String> {
    repeat(100) { if (!calls.isFile) Thread.sleep(100) }
    return calls.takeIf { it.isFile }?.readLines()?.map { it.trim() }.orEmpty()
  }

  private fun systemPing(): File = File(System.getenv("SystemRoot"), "System32/PING.EXE")
}
