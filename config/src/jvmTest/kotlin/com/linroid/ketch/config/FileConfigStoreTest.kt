package com.linroid.ketch.config

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
  fun load_unreadableFile_movesItAsideAndReturnsDefaults() {
    // A TOML syntax error, and a port ServerConfig refuses.
    for (content in listOf("[server\nport = 8642\n", "[server]\nport = 70000\n")) {
      file.writeText(content)
      val reported = mutableListOf<UnreadableConfig>()
      val store = FileConfigStore(file.path) { reported += it }

      assertEquals(KetchConfig(), store.load())

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
  fun load_unreadableFileWithoutCallback_throwsAndKeepsIt() {
    file.writeText("[server\n")

    assertFails { FileConfigStore(file.path).load() }

    assertEquals(listOf("config.toml"), dir.list()?.toList())
  }
}
