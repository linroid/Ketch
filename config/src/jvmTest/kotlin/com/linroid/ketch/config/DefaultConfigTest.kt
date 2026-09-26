package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultConfigTest {

  @Test
  fun `default template decodes with every commented option at its default`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      DEFAULT_CONFIG_CONTENT,
    )
    assertEquals(TorrentSettings(), decoded.torrent)
    assertEquals(8642, decoded.server.port)
  }
}
