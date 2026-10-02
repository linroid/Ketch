package com.linroid.ketch.config

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSectionsFileConfigStoreTest {

  @Test
  fun save_appSections_loadsBackUnchanged() {
    val dir = createTempDirectory("ketch-config").toFile()
    try {
      val path = File(dir, "config.toml").path
      FileConfigStore(path).save(populatedAppSections)
      assertEquals(populatedAppSections, FileConfigStore(path).load())
    } finally {
      dir.deleteRecursively()
    }
  }
}
