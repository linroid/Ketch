package com.linroid.ketch.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PortableAppTest {
  private val dir = Files.createTempDirectory("ketch-portable").toFile()
  private val folder = File(dir, "Ketch")
  private val launcher = File(folder, "Ketch.exe")

  @AfterTest
  fun cleanUp() {
    dir.walk().forEach { it.setWritable(true) }
    dir.deleteRecursively()
  }

  @Test
  fun detect_dataFolderBesideTheLauncher_isPortable() {
    val data = File(folder, "data").apply { mkdirs() }

    val app = assertNotNull(PortableApp.detect(launcher.path, DesktopOs.WINDOWS))

    assertEquals(folder, app.dir)
    assertEquals(launcher, app.launcher)
    assertEquals(data, app.writableDataDir())
  }

  @Test
  fun detect_withoutADataFolder_isNull() {
    folder.mkdirs()

    assertNull(PortableApp.detect(launcher.path, DesktopOs.WINDOWS))
  }

  @Test
  fun detect_notOnWindows_isNull() {
    File(folder, "data").mkdirs()

    assertNull(PortableApp.detect(launcher.path, DesktopOs.MAC))
    assertNull(PortableApp.detect(launcher.path, DesktopOs.LINUX))
  }

  @Test
  fun detect_runFromSource_isNull() {
    assertNull(PortableApp.detect(appPath = null, os = DesktopOs.WINDOWS))
  }

  @Test
  fun writableDataDir_readOnlyFolder_isNull() {
    val data = File(folder, "data").apply { mkdirs() }
    // Windows ignores the flag on folders, and root writes anyway.
    if (!data.setWritable(false) || Files.isWritable(data.toPath())) return

    assertNull(PortableApp(folder, launcher).writableDataDir())
  }
}
