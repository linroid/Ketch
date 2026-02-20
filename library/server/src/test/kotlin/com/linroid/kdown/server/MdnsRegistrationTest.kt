package com.linroid.kdown.server

import com.linroid.kdown.api.MDNS_SERVICE_TYPE
import com.linroid.kdown.server.mdns.MdnsRegistrar
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FakeMdnsRegistrar : MdnsRegistrar {
  @Volatile var registered = false
    private set
  @Volatile var registerCallCount = 0
    private set
  @Volatile var unregisterCallCount = 0
    private set
  @Volatile var lastServiceType: String? = null
    private set
  @Volatile var lastServiceName: String? = null
    private set
  @Volatile var lastPort: Int? = null
    private set
  @Volatile var lastMetadata: Map<String, String>? = null
    private set

  var failOnRegister = false

  val registerLatch = CountDownLatch(1)

  override suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  ) {
    if (failOnRegister) {
      registerLatch.countDown()
      throw RuntimeException("Simulated registration failure")
    }
    registerCallCount++
    registered = true
    lastServiceType = serviceType
    lastServiceName = serviceName
    lastPort = port
    lastMetadata = metadata
    registerLatch.countDown()
  }

  override suspend fun unregister() {
    unregisterCallCount++
    registered = false
  }
}

/** Finds an available port for testing. */
private fun findFreePort(): Int {
  return ServerSocket(0).use { it.localPort }
}

class MdnsRegistrationTest {

  private fun createServer(
    config: KDownServerConfig,
    registrar: FakeMdnsRegistrar = FakeMdnsRegistrar(),
  ): Pair<KDownServer, FakeMdnsRegistrar> {
    val server = KDownServer(
      kdown = createTestKDown(),
      config = config,
      mdnsRegistrar = registrar,
    )
    return server to registrar
  }

  private fun FakeMdnsRegistrar.awaitRegister() {
    registerLatch.await(5, TimeUnit.SECONDS)
  }

  @Test
  fun `start registers mdns when enabled`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(
        port = port,
        mdnsEnabled = true,
      ),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertTrue(registrar.registered)
      assertEquals(1, registrar.registerCallCount)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `start skips mdns when disabled`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(
        port = port,
        mdnsEnabled = false,
      ),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      Thread.sleep(200)
      assertFalse(registrar.registered)
      assertEquals(0, registrar.registerCallCount)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `registers with correct service type and name`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(
        port = port,
        mdnsServiceName = "My Server",
        mdnsServiceType = MDNS_SERVICE_TYPE,
      ),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertEquals(MDNS_SERVICE_TYPE, registrar.lastServiceType)
      assertEquals("My Server", registrar.lastServiceName)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `registers with correct port`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(port = port),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertEquals(port, registrar.lastPort)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `token metadata is none when apiToken is null`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(port = port, apiToken = null),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertEquals(
        mapOf("token" to "none"),
        registrar.lastMetadata,
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `token metadata is none when apiToken is blank`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(port = port, apiToken = ""),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertEquals(
        mapOf("token" to "none"),
        registrar.lastMetadata,
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `token metadata is required when apiToken is set`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(
        port = port,
        apiToken = "my-secret",
      ),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertEquals(
        mapOf("token" to "required"),
        registrar.lastMetadata,
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `stop calls unregister`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(port = port),
      registrar = registrar,
    )
    server.start(wait = false)
    registrar.awaitRegister()
    server.stop()
    assertTrue(registrar.unregisterCallCount >= 1)
  }

  @Test
  fun `registration failure is caught and does not throw`() {
    val registrar = FakeMdnsRegistrar()
    registrar.failOnRegister = true
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(port = port),
      registrar = registrar,
    )
    // Should not throw
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertFalse(registrar.registered)
      assertEquals(0, registrar.registerCallCount)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `custom service type is used`() {
    val registrar = FakeMdnsRegistrar()
    val port = findFreePort()
    val (server, _) = createServer(
      config = KDownServerConfig(
        port = port,
        mdnsServiceType = "_custom._tcp",
      ),
      registrar = registrar,
    )
    server.start(wait = false)
    try {
      registrar.awaitRegister()
      assertEquals("_custom._tcp", registrar.lastServiceType)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `default config uses MDNS_SERVICE_TYPE constant`() {
    val config = KDownServerConfig.Default
    assertEquals(MDNS_SERVICE_TYPE, config.mdnsServiceType)
  }
}
