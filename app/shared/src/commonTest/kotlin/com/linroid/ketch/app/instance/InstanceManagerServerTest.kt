package com.linroid.ketch.app.instance

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InstanceManagerServerTest {

  private class Store(var config: KetchConfig) : ConfigStore {
    override fun load(): KetchConfig = config
    override fun save(config: KetchConfig) {
      this.config = config
    }
  }

  private fun manager(
    server: ServerConfig,
    startServer: () -> Unit = {},
  ): InstanceManager = InstanceManager(
    factory = InstanceFactory(
      embeddedFactory = { FakeKetchApi() },
      localServerFactory = {
        startServer()
        object : LocalServerHandle {
          override fun stop() {}
        }
      },
    ),
    configStore = Store(KetchConfig(server = server)),
  )

  @Test
  fun `the server reports the saved settings it started with`() {
    val saved = ServerConfig(port = 9000, apiToken = "secret")
    val manager = manager(saved)
    manager.startServer()
    assertEquals(ServerState.Running(saved), manager.serverState.value)
    assertEquals(9000, (manager.serverState.value as ServerState.Running).port)
    manager.close()
  }

  @Test
  fun `a failed start is reported instead of thrown`() {
    val manager = manager(ServerConfig()) { throw IllegalStateException("Port in use") }
    manager.startServer()
    val state = assertIs<ServerState.Failed>(manager.serverState.value)
    assertEquals("Port in use", state.message)
    manager.close()
  }

  @Test
  fun `auto start brings the server up with the manager`() {
    val manager = manager(ServerConfig(autoStart = true))
    assertIs<ServerState.Running>(manager.serverState.value)
    manager.close()
  }

  @Test
  fun `without auto start the server waits to be started`() {
    val manager = manager(ServerConfig())
    assertEquals(ServerState.Stopped, manager.serverState.value)
    manager.close()
  }
}
