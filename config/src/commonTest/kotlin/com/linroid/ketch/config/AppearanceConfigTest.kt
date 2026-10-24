package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppearanceConfigTest {

  @Test
  fun `accent round trips through toml under its own section`() {
    val config = KetchConfig(
      appearance = AppearanceConfig(accent = AccentColor.Green),
    )
    val encoded = ConfigStore.toml
      .encodeToString(KetchConfig.serializer(), config)
    assertTrue(
      encoded.contains("[appearance]") &&
        encoded.contains("accent = \"green\""),
      "expected a hand-editable appearance section, got:\n$encoded",
    )
    val decoded = ConfigStore.toml
      .decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(AccentColor.Green, decoded.appearance.accent)
  }

  @Test
  fun `every accent round trips under its color name`() {
    for (accent in AccentColor.entries) {
      val encoded = ConfigStore.toml.encodeToString(
        KetchConfig.serializer(),
        KetchConfig(appearance = AppearanceConfig(accent = accent)),
      )
      assertTrue(encoded.contains("accent = \"${accent.name.lowercase()}\""), encoded)
      val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
      assertEquals(accent, decoded.appearance.accent)
    }
  }

  @Test
  fun `accents saved under their former names load as their colors`() {
    val former = mapOf(
      "signal" to AccentColor.Indigo,
      "harbor" to AccentColor.Teal,
      "fathom" to AccentColor.Green,
      "beacon" to AccentColor.Orange,
    )
    for ((name, accent) in former) {
      val decoded = ConfigStore.toml.decodeFromString(
        KetchConfig.serializer(),
        """
        |[appearance]
        |accent = "$name"
        """.trimMargin(),
      )
      assertEquals(accent, decoded.appearance.accent, name)
    }
  }

  @Test
  fun `an accent this version does not know loads as the default`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[appearance]
      |accent = "chartreuse"
      |theme = "dark"
      """.trimMargin(),
    )
    assertEquals(AccentColor.Indigo, decoded.appearance.accent)
    assertEquals(ThemeMode.Dark, decoded.appearance.theme)
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
      |accent = "teal"
      """.trimMargin(),
    )
    assertEquals(ThemeMode.System, legacy.appearance.theme)
    assertEquals(AccentColor.Teal, legacy.appearance.accent)
  }

  @Test
  fun `language round trips and is left out while it follows the system`() {
    val encoded = ConfigStore.toml.encodeToString(
      KetchConfig.serializer(),
      KetchConfig(appearance = AppearanceConfig(language = "zh-Hant")),
    )
    assertTrue(encoded.contains("language = \"zh-Hant\""), encoded)
    val decoded = ConfigStore.toml
      .decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals("zh-Hant", decoded.appearance.language)

    val system = ConfigStore.toml.encodeToString(KetchConfig.serializer(), KetchConfig())
    assertTrue("language" !in system, system)
    val legacy = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[appearance]
      |theme = "dark"
      """.trimMargin(),
    )
    assertEquals(null, legacy.appearance.language)
  }

  @Test
  fun `zoom round trips as a whole number of percent`() {
    val encoded = ConfigStore.encode(KetchConfig(appearance = AppearanceConfig(zoom = 150)))
    assertTrue(encoded.contains("zoom = 150"), encoded)
    assertEquals(150, ConfigStore.decode(encoded).appearance.zoom)
  }

  @Test
  fun `zoom outside its range loads as the nearest one in it`() {
    assertEquals(AppearanceConfig.MAX_ZOOM, decodeZoom("1000"))
    assertEquals(AppearanceConfig.MIN_ZOOM, decodeZoom("10"))
    assertEquals(AppearanceConfig.MIN_ZOOM, decodeZoom("-150"))
  }

  @Test
  fun `zoom that is not a whole number loads as the default`() {
    assertEquals(AppearanceConfig.DEFAULT_ZOOM, decodeZoom("\"large\""))
    assertEquals(AppearanceConfig.DEFAULT_ZOOM, decodeZoom("1.5"))
    assertEquals(AppearanceConfig.DEFAULT_ZOOM, decodeZoom("true"))
    assertEquals(125, decodeZoom("\"125%\""))
  }

  @Test
  fun `zoom left out of the file is the default`() {
    assertEquals(AppearanceConfig.DEFAULT_ZOOM, ConfigStore.decode("[appearance]").appearance.zoom)
  }

  private fun decodeZoom(value: String): Int = ConfigStore.decode(
    """
    |[appearance]
    |zoom = $value
    |theme = "dark"
    """.trimMargin(),
  ).also { assertEquals(ThemeMode.Dark, it.appearance.theme) }.appearance.zoom
}
