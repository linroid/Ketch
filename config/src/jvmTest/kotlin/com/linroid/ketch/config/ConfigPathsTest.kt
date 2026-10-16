package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigPathsTest {

  @Test
  fun configDir_configDirVariableSet_usedAsItIs() {
    val dir = configDir { name -> if (name == CONFIG_DIR_ENV) "/config" else null }

    assertEquals("/config", dir)
  }

  @Test
  fun configDir_configDirVariableBlank_platformDirectory() {
    val platform = configDir { null }

    assertEquals(platform, configDir { name -> if (name == CONFIG_DIR_ENV) " " else null })
  }
}
