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

  @Test
  fun `theme mode round trips and defaults to following the system`() {
    val encoded = ConfigStore.toml.encodeToString(
      KetchConfig.serializer(),
      KetchConfig(appearance = AppearanceConfig(theme = ThemeMode.Dark)),
    )
    assertTrue(encoded.contains("theme = \"dark\""), encoded)
    val decoded = ConfigStore.toml
      .decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(ThemeMode.Dark, decoded.appearance.theme)

    // Configs written before the theme existed keep following the system.
    val legacy = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[appearance]
      |accent = "harbor"
      """.trimMargin(),
    )
    assertEquals(ThemeMode.System, legacy.appearance.theme)
    assertEquals(AccentColor.Harbor, legacy.appearance.accent)
  }
}
