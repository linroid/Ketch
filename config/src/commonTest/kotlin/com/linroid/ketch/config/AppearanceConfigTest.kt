package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppearanceConfigTest {

  @Test
  fun `accent round trips through toml under its own section`() {
    val config = KetchConfig(
      appearance = AppearanceConfig(accent = AccentColor.Fathom),
    )
    val encoded = ConfigStore.toml
      .encodeToString(KetchConfig.serializer(), config)
    assertTrue(
      encoded.contains("[appearance]") &&
        encoded.contains("accent = \"fathom\""),
      "expected a hand-editable appearance section, got:\n$encoded",
    )
    val decoded = ConfigStore.toml
      .decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(AccentColor.Fathom, decoded.appearance.accent)
  }

  @Test
  fun `config without an appearance section decodes to the default accent`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |name = "laptop"
      """.trimMargin(),
    )
    assertEquals(AppearanceConfig(), decoded.appearance)
  }
}
