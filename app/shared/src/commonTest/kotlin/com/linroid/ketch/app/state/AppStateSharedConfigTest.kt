package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerContext
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.ServerConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppStateSharedConfigTest {
  private val store = RecordingConfigStore(KetchConfig(server = ServerConfig(autoStart = true)))
  private var server: LocalServerContext? = null
  private val manager = InstanceManager(
    factory = InstanceFactory(
      embeddedFactory = { FakeKetchApi() },
      localServerFactory = { context ->
        server = context
        object : LocalServerHandle {
          override fun stop() {}
        }
      },
    ),
    configStore = store,
  )

  @Test
  fun clientChange_isSavedAndShownOnTheDevicePage() = runTest {
    val state = AppState(manager, backgroundScope, appSettings = AppSettingsController(store))
    val shared = DownloadConfig(maxConcurrentDownloads = 2)

    server!!.onConfigChanged(shared)

    assertEquals(shared, store.load().download)
    assertEquals(shared, state.appSettings.config.download)
    val embedded = manager.instances.value.single { it is EmbeddedInstance }
    assertEquals(shared, state.settingsFor(embedded).download)
    manager.close()
  }

  @Test
  fun clientChange_withoutTheApp_isSavedAsItIs() = runTest {
    val state = AppState(manager, backgroundScope, appSettings = AppSettingsController(store))
    state.close()
    val shared = DownloadConfig(maxConcurrentDownloads = 2)

    server!!.onConfigChanged(shared)

    assertEquals(shared, store.load().download)
    manager.close()
  }
}
