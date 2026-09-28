package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.config.AccentColor
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RecordingConfigStore(
  private var config: KetchConfig = KetchConfig(),
) : ConfigStore {
  override fun load(): KetchConfig = config

  override fun save(config: KetchConfig) {
    this.config = config
  }
}

class AppSettingsControllerTest {

  @Test
  fun `settings load from the store`() {
    val store = RecordingConfigStore(
      KetchConfig(name = "laptop", server = ServerConfig(port = 9000)),
    )
    val controller = AppSettingsController(store)
    assertEquals("laptop", controller.config.name)
    assertEquals(9000, controller.config.server.port)
  }

  @Test
  fun `a blank name clears back to the platform default`() {
    val store = RecordingConfigStore(KetchConfig(name = "laptop"))
    val controller = AppSettingsController(store)
    controller.saveName("   ")
    assertNull(controller.config.name)
    assertNull(store.load().name)
  }

  @Test
  fun `saving one section keeps the others`() {
    val store = RecordingConfigStore(
      KetchConfig(
        name = "laptop",
        ai = AiSettings(
          enabled = true,
          llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "sk"),
        ),
      ),
    )
    val controller = AppSettingsController(store)
    controller.saveDownload(
      DownloadConfig(maxConcurrentDownloads = 5),
    )
    controller.saveServer(ServerConfig(port = 9100))
    controller.saveAccent(KetchAccent.Beacon)

    val saved = store.load()
    assertEquals("laptop", saved.name)
    assertEquals("sk", saved.ai.llm.apiKey)
    assertEquals(5, saved.download.maxConcurrentDownloads)
    assertEquals(9100, saved.server.port)
    assertEquals(AccentColor.Beacon, saved.appearance.accent)
    assertEquals(KetchAccent.Beacon, controller.accent)
  }

  @Test
  fun `a section written behind our back is not clobbered`() {
    val store = RecordingConfigStore()
    val controller = AppSettingsController(store)
    // Another controller (AI settings) persists its own section.
    store.save(store.load().copy(ai = AiSettings(enabled = true)))
    controller.saveName("desktop")
    assertTrue(store.load().ai.enabled)
    assertEquals("desktop", store.load().name)
  }

  @Test
  fun `without a store settings stay in memory`() {
    val controller = AppSettingsController()
    controller.saveName("ephemeral")
    assertEquals("ephemeral", controller.config.name)
  }

  @Test
  fun `accent and theme mode are saved without overwriting each other`() {
    val store = RecordingConfigStore()
    val controller = AppSettingsController(store)
    controller.saveThemeMode(ThemeMode.Dark)
    controller.saveAccent(KetchAccent.Harbor)
    assertEquals(ThemeMode.Dark, store.load().appearance.theme)
    assertEquals(AccentColor.Harbor, store.load().appearance.accent)

    controller.saveThemeMode(ThemeMode.Light)
    assertEquals(ThemeMode.Light, controller.themeMode)
    assertEquals(KetchAccent.Harbor, controller.accent)
  }

  @Test
  fun `accents survive a round trip through the persisted form`() {
    KetchAccent.entries.forEach { accent ->
      val controller = AppSettingsController(RecordingConfigStore())
      controller.saveAccent(accent)
      assertEquals(accent, controller.accent)
    }
  }
}

class AppDestinationTest {

  @Test
  fun `discover is hidden until discovery works`() {
    assertEquals(
      listOf(AppDestination.Downloads, AppDestination.Settings),
      AppDestination.visible(aiAvailable = false),
    )
  }

  @Test
  fun `discover appears once discovery works`() {
    assertEquals(
      AppDestination.entries.toList(),
      AppDestination.visible(aiAvailable = true),
    )
  }
}

class SettingsCategoryTest {

  @Test
  fun `remote access is hidden where no local server can run`() {
    val categories = SettingsCategory.visible(serverSupported = false)
    assertTrue(SettingsCategory.RemoteAccess !in categories)
    assertEquals(SettingsCategory.entries.size - 1, categories.size)
  }

  @Test
  fun `every category is offered where the server can run`() {
    assertEquals(
      SettingsCategory.entries.toList(),
      SettingsCategory.visible(serverSupported = true),
    )
  }
}
