package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeExpandingConfigStoreTest {
  private val home = File("home", "me").absolutePath
  private val directory = Files.createTempDirectory("ketch-config").toFile()

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  @Test
  fun `uncommented template directory loads inside the home directory`() {
    val file = File(directory, "config.toml")
    file.writeText(
      DEFAULT_CONFIG_CONTENT.replace(
        "# defaultDirectory = \"~/Downloads\"",
        "defaultDirectory = \"~/Downloads\"",
      ),
    )

    val config = HomeExpandingConfigStore(FileConfigStore(file.path), home).load()

    assertEquals(File(home, "Downloads").path, config.download.defaultDirectory)
  }

  @Test
  fun `load expands a lone tilde to the home directory`() {
    assertEquals(home, loadDirectory("~"))
  }

  @Test
  fun `load expands a tilde slash prefix`() {
    assertEquals(home, loadDirectory("~/"))
    assertEquals(File(home, "media/films").path, loadDirectory("~/media/films"))
  }

  @Test
  fun `load keeps paths that do not start with the home directory`() {
    val paths = listOf("/srv/downloads", "downloads", "~other/Downloads", "~Downloads", "a/~/b")
    for (path in paths) {
      assertEquals(path, loadDirectory(path))
    }
  }

  @Test
  fun `load keeps an unset directory unset`() {
    assertNull(loadDirectory(null))
  }

  private fun loadDirectory(path: String?): String? {
    val stored = KetchConfig(download = DownloadConfig(defaultDirectory = path))
    return HomeExpandingConfigStore(FakeConfigStore(stored), home).load().download.defaultDirectory
  }

  private class FakeConfigStore(private val config: KetchConfig) : ConfigStore {
    override fun load(): KetchConfig = config

    override fun save(config: KetchConfig) = Unit
  }
}
