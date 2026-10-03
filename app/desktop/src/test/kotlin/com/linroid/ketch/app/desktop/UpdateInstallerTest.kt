package com.linroid.ketch.app.desktop

import com.linroid.ketch.updater.ReleaseProduct
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateInstallerTest {
  private val dir = Files.createTempDirectory("ketch-installer").toFile()
  private val bin = File(dir, "bin").apply { mkdirs() }
  private val calls = File(dir, "calls.txt")

  @AfterTest
  fun cleanUp() {
    dir.walk().forEach { it.setWritable(true) }
    dir.deleteRecursively()
  }

  @Test
  fun forThisApp_runFromSource_isNull() {
    assertNull(UpdateInstaller.forThisApp(dir, File(dir, "log"), appPath = null))
  }

  @Test
  fun forThisApp_macBundleInAWritableFolder_swapsTheBundle() {
    val launcher = File(dir, "Applications/Ketch.app/Contents/MacOS/Ketch")
    launcher.parentFile.mkdirs()
    val installer = UpdateInstaller.forThisApp(dir, File(dir, "log"), launcher.path, DesktopOs.MAC)

    assertIs<UpdateInstaller.MacBundle>(installer)
    assertTrue(installer.restarts)
  }

  @Test
  fun forThisApp_windows_runsThePackage() {
    val launcher = File(dir, "Ketch/Ketch.exe")
    val installer =
      UpdateInstaller.forThisApp(dir, File(dir, "log"), launcher.path, DesktopOs.WINDOWS)

    assertIs<UpdateInstaller.WindowsMsi>(installer)
  }

  @Test
  fun forThisApp_portableCopy_replacesItsFiles() {
    val launcher = File(dir, "Ketch/Ketch.exe")
    File(launcher.parentFile, PortableApp.DATA_DIR).mkdirs()
    val installer =
      UpdateInstaller.forThisApp(dir, File(dir, "log"), launcher.path, DesktopOs.WINDOWS)

    assertIs<UpdateInstaller.WindowsPortable>(installer)
    assertEquals(ReleaseProduct.PortableDesktop, installer.product)
    assertTrue(installer.restarts)
  }

  @Test
  fun forThisApp_portableCopyInAReadOnlyFolder_opensTheArchive() {
    val launcher = File(dir, "Ketch/Ketch.exe")
    File(launcher.parentFile, PortableApp.DATA_DIR).mkdirs()
    // Windows ignores the flag on folders, and root writes anyway.
    val folder = launcher.parentFile
    if (!folder.setWritable(false) || Files.isWritable(folder.toPath())) return
    val installer =
      UpdateInstaller.forThisApp(dir, File(dir, "log"), launcher.path, DesktopOs.WINDOWS)

    assertIs<UpdateInstaller.OpenInstaller>(installer)
    assertEquals(ReleaseProduct.PortableDesktop, installer.product)
    assertFalse(installer.restarts)
  }

  @Test
  fun forThisApp_portableCopyWithAReadOnlyDataFolder_opensTheArchive() {
    val launcher = File(dir, "Ketch/Ketch.exe")
    val data = File(launcher.parentFile, PortableApp.DATA_DIR).apply { mkdirs() }
    // The data then stays in the user's profile, often on another drive, where the update would
    // be unpacked and could not be renamed into the app's folder.
    if (!data.setWritable(false) || Files.isWritable(data.toPath())) return
    val installer =
      UpdateInstaller.forThisApp(dir, File(dir, "log"), launcher.path, DesktopOs.WINDOWS)

    assertIs<UpdateInstaller.OpenInstaller>(installer)
    assertEquals(ReleaseProduct.PortableDesktop, installer.product)
  }

  @Test
  fun windowsPortablePrepare_releaseArchive_unpacksTheAppFolder() {
    val download = zip(
      "ketch-desktop-0.0.2-windows-x64-portable.zip",
      "Ketch/Ketch.exe", "Ketch/app/Ketch.cfg", "Ketch/runtime/release", "Ketch/data/",
    )

    val prepared = portableInstaller().prepare(download)

    assertEquals(File(dir, "work/unpacked/Ketch"), prepared)
    assertTrue(File(prepared, "app/Ketch.cfg").isFile)
    assertTrue(File(prepared, "runtime/release").isFile)
    assertFalse(download.exists())
  }

  @Test
  fun windowsPortablePrepare_archiveWithoutTheLauncher_fails() {
    val download = zip(
      "ketch-desktop-0.0.2-windows-x64-portable.zip",
      "Ketch/app/Ketch.cfg", "Ketch/runtime/release",
    )

    assertFailsWith<IOException> { portableInstaller().prepare(download) }
  }

  @Test
  fun canReplace_diskImageOrTranslocatedApp_isFalse() {
    assertFalse(UpdateInstaller.canReplace(File("/Volumes/Ketch/Ketch.app")))
    val translocated = File(dir, "AppTranslocation/1234/d/Ketch.app").apply { mkdirs() }
    assertFalse(UpdateInstaller.canReplace(translocated))
    assertTrue(UpdateInstaller.canReplace(File(dir, "Ketch.app").apply { mkdirs() }))
  }

  @Test
  fun macScript_swapsTheBundleAndOpensIt() {
    val app = bundle("Applications/Ketch.app", "old")
    val staged = bundle("updates/Ketch.app", "new")
    fake("open", """echo "open ${'$'}1" >> "${calls.path}"""")
    if (!onPath("ditto")) fake("ditto", """cp -R "${'$'}1" "${'$'}2"""")

    runScript("install-update-mac.sh", staged.path, app.path)

    assertEquals("new", File(app, "Contents/version").readText())
    assertEquals(listOf("Ketch.app"), app.parentFile.list()?.toList())
    assertFalse(staged.exists())
    assertEquals(listOf("open ${app.path}"), calls.readLines())
  }

  @Test
  fun macScript_failedCopy_keepsAndOpensTheOldBundle() {
    val app = bundle("Applications/Ketch.app", "old")
    val staged = bundle("updates/Ketch.app", "new")
    fake("open", """echo "open ${'$'}1" >> "${calls.path}"""")
    fake("ditto", "exit 1")

    runScript("install-update-mac.sh", staged.path, app.path)

    assertEquals("old", File(app, "Contents/version").readText())
    assertEquals(listOf("Ketch.app"), app.parentFile.list()?.toList())
    assertEquals(listOf("open ${app.path}"), calls.readLines())
  }

  @Test
  fun linuxScript_installsThePackageAndOpensTheApp() {
    val pkg = File(dir, "ketch.deb").apply { writeText("deb") }
    val app = File(dir, "Ketch").apply {
      writeText("#!/bin/sh\necho launched >> \"${calls.path}\"\n")
      setExecutable(true)
    }
    fake("pkexec", """echo "pkexec ${'$'}*" >> "${calls.path}"""")

    runScript("install-update-linux.sh", pkg.path, app.path)
    // The app starts in the background.
    repeat(50) { if (calls.readLines().size < 2) Thread.sleep(100) }

    assertEquals(listOf("pkexec dpkg -i ${pkg.path}", "launched"), calls.readLines())
    assertFalse(pkg.exists())
  }

  /** Runs the script [name] after a process that is still running, which it waits for. */
  private fun runScript(name: String, vararg args: String) {
    val running = ProcessBuilder("sleep", "0.3").start()
    val script = writeScript(File(dir, "work"), name)
    val process = ProcessBuilder(listOf("/bin/sh", script.path, running.pid().toString()) + args)
      .redirectErrorStream(true)
      .redirectOutput(File(dir, "script.log"))
      .apply { environment()["PATH"] = bin.path + File.pathSeparator + System.getenv("PATH") }
      .start()
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$name did not finish")
    assertFalse(running.isAlive, "$name did not wait for the app")
  }

  private fun portableInstaller(): UpdateInstaller.WindowsPortable {
    val folder = File(dir, "Portable/Ketch")
    val app = PortableApp(folder, File(folder, "Ketch.exe"))
    return UpdateInstaller.WindowsPortable(app, File(dir, "work"), File(dir, "log"))
  }

  /** Writes the archive [name] holding [entries]; a name ending in `/` is a folder. */
  private fun zip(name: String, vararg entries: String): File = File(dir, name).also { file ->
    ZipOutputStream(file.outputStream()).use { zip ->
      entries.forEach { entry ->
        zip.putNextEntry(ZipEntry(entry))
        if (!entry.endsWith("/")) zip.write(entry.toByteArray())
        zip.closeEntry()
      }
    }
  }

  private fun bundle(path: String, version: String): File = File(dir, path).apply {
    File(this, "Contents/MacOS").mkdirs()
    File(this, "Contents/version").writeText(version)
  }

  private fun fake(command: String, body: String) {
    File(bin, command).apply {
      writeText("#!/bin/sh\n$body\n")
      setExecutable(true)
    }
  }

  private fun onPath(command: String): Boolean = System.getenv("PATH").orEmpty()
    .split(File.pathSeparator)
    .any { File(it, command).canExecute() }
}
