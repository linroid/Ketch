package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileConfigStoreTest {
  private val dir = createTempDirectory("ketch-config").toFile()
  private val file = File(dir, "config.toml")

  @AfterTest
  fun deleteDir() {
    dir.deleteRecursively()
  }

  @Test
  fun load_unreadableFile_movesItAsideAndReturnsDefaultsWithoutUploads() {
    // A TOML syntax error, and a port ServerConfig refuses.
    for (content in listOf("[server\nport = 8642\n", "[server]\nport = 70000\n")) {
      file.writeText(content)
      val reported = mutableListOf<UnreadableConfig>()
      val store = FileConfigStore(file.path) { reported += it }

      val loaded = store.load()
      assertEquals(KetchConfig.Unreadable, loaded)
      // The file may have turned uploading off; nothing says it did not.
      assertEquals(TorrentUploadMode.Off, loaded.torrent.upload)
      assertEquals(KetchConfig(),
        loaded.copy(torrent = loaded.torrent.copy(upload = TorrentSettings().upload)))

      val unreadable = reported.single()
      assertEquals(file.path, unreadable.path)
      assertTrue(unreadable.movedTo.startsWith("${file.path}.broken-"), unreadable.movedTo)
      assertEquals(content, File(unreadable.movedTo).readText())
      assertFalse(file.exists())
      // Settings saved afterwards start a new file.
      store.save(KetchConfig(name = "NAS"))
      assertEquals("NAS", store.load().name)
      assertEquals(1, reported.size)
      file.delete()
    }
  }

  @Test
  fun save_windowsDownloadFolder_loadsUnchanged() {
    val config = KetchConfig(download = DownloadConfig(defaultDirectory = "F:\\Downloads\\backup"))
    val store = FileConfigStore(file.path)

    store.save(config)

    assertEquals(config, store.load())
  }

  @Test
  fun load_unreadableFileWithoutCallback_throwsAndKeepsIt() {
    file.writeText("[server\n")

    assertFails { FileConfigStore(file.path).load() }

    assertEquals(listOf("config.toml"), dir.list()?.toList())
  }
}
