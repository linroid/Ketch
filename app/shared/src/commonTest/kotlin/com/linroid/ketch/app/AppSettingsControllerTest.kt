package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.DownloadSettingsInput
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.config.AccentColor
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingConfigStore(
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
  fun `accents survive a round trip through the persisted form`() {
    KetchAccent.entries.forEach { accent ->
      val controller = AppSettingsController(RecordingConfigStore())
      controller.saveAccent(accent)
      assertEquals(accent, controller.accent)
    }
  }
}

class DownloadSettingsInputTest {

  private val base = DownloadConfig()

  private fun input(
    concurrent: String = "2",
    perDownload: String = "4",
    perHost: String = "8",
    directory: String = "",
    speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  ) = DownloadSettingsInput(
    directory = directory,
    maxConcurrentDownloads = concurrent,
    maxConnectionsPerDownload = perDownload,
    maxConnectionsPerHost = perHost,
    speedLimit = speedLimit,
  )

  @Test
  fun `valid values build a config`() {
    val config = input(
      concurrent = "3",
      perDownload = "6",
      perHost = "0",
      directory = " /tmp/dl ",
      speedLimit = SpeedLimit.mbps(2),
    ).toConfig(base)
    assertNotNull(config)
    assertEquals(3, config.maxConcurrentDownloads)
    assertEquals(6, config.maxConnectionsPerDownload)
    assertEquals(0, config.maxConnectionsPerHost)
    assertEquals("/tmp/dl", config.defaultDirectory)
    assertEquals(SpeedLimit.mbps(2), config.speedLimit)
  }

  @Test
  fun `a blank directory falls back to the platform default`() {
    val config = input(directory = "   ").toConfig(base)
    assertNotNull(config)
    assertNull(config.defaultDirectory)
  }

  @Test
  fun `zero connections per download is rejected`() {
    // DownloadConfig would throw on this, so the form must catch it.
    val form = input(perDownload = "0")
    assertNotNull(form.validate())
    assertNull(form.toConfig(base))
  }

  @Test
  fun `non-numeric and negative values are rejected`() {
    assertNotNull(input(concurrent = "many").validate())
    assertNotNull(input(concurrent = "-1").validate())
    assertNotNull(input(perHost = "-2").validate())
    assertNull(input(perDownload = "").toConfig(base))
  }

  @Test
  fun `zero means unlimited for queue limits`() {
    val config = input(concurrent = "0", perHost = "0").toConfig(base)
    assertNotNull(config)
    assertEquals(0, config.maxConcurrentDownloads)
  }

  @Test
  fun `a saved config round trips through the form`() {
    val saved = base.copy(
      defaultDirectory = "/downloads",
      maxConcurrentDownloads = 7,
      maxConnectionsPerDownload = 3,
      maxConnectionsPerHost = 5,
      speedLimit = SpeedLimit.kbps(512),
    )
    assertEquals(saved, DownloadSettingsInput.from(saved).toConfig(saved))
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
