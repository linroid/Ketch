package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.config.AccentColor
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.CloseAction
import com.linroid.ketch.config.DesktopSettings
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.ThemeMode
import com.linroid.ketch.config.UiPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
  fun saveUi_change_keepsTheOtherPreferences() {
    val store = RecordingConfigStore(
      KetchConfig(ui = UiPreferences(lastDeviceId = "nas.local:8642", inspectorWidth = 400)),
    )
    val controller = AppSettingsController(store)
    assertEquals("nas.local:8642", controller.ui.lastDeviceId)

    controller.saveUi { it.copy(sidebarCollapsed = true) }

    val saved = store.load().ui
    assertEquals(true, saved.sidebarCollapsed)
    assertEquals("nas.local:8642", saved.lastDeviceId)
    assertEquals(400, saved.inspectorWidth)
    assertEquals(saved, controller.ui)
  }

  @Test
  fun saveNotifications_change_keepsTheOtherSections() {
    val store = RecordingConfigStore(KetchConfig(desktop = DesktopSettings(openAtLogin = true)))
    val controller = AppSettingsController(store)

    controller.saveNotifications { it.copy(failed = NotificationMode.InApp) }
    controller.saveIntegration { it.copy(magnetHandler = true) }

    val saved = store.load()
    assertEquals(NotificationMode.InApp, saved.notifications.failed)
    assertTrue(saved.integration.magnetHandler)
    assertTrue(saved.desktop.openAtLogin)
    assertEquals(saved, controller.config)
  }

  @Test
  fun reload_sectionSavedElsewhere_showsIt() {
    val store = RecordingConfigStore()
    val controller = AppSettingsController(store)
    // The desktop app saves the close action straight to the file.
    store.save(store.load().copy(desktop = DesktopSettings(closeAction = CloseAction.Quit)))
    assertEquals(CloseAction.Ask, controller.config.desktop.closeAction)

    controller.reload()

    assertEquals(CloseAction.Quit, controller.config.desktop.closeAction)
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
  fun `discover is hidden while discovery is not offered`() {
    assertEquals(
      listOf(AppDestination.Downloads, AppDestination.Devices),
      AppDestination.visible(discoverOffered = false),
    )
  }

  @Test
  fun `discover shows while discovery is offered`() {
    assertEquals(
      AppDestination.entries.toList(),
      AppDestination.visible(discoverOffered = true),
    )
  }
}
