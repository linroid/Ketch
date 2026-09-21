package com.linroid.ketch.server

import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.ConfigurableNetworkHttpEngine
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.NetworkInterfaceProvider
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterfaceRoutesTest {
  @Test
  fun routes_discoverSelectRejectInvalidAndReset() = testApplication {
    val provider = object : NetworkInterfaceProvider {
      override suspend fun availableInterfaces(): List<NetworkInterfaceInfo> =
        listOf(NetworkInterfaceInfo("server-wifi", "Server Wi-Fi", listOf("192.0.2.1")))
      override fun createDefaultEngine(): HttpEngine = NoOpHttpEngine()
      override fun createEngine(networkInterface: NetworkInterfaceInfo): HttpEngine =
        NoOpHttpEngine()
    }
    val ketch = Ketch(ConfigurableNetworkHttpEngine(provider))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    val client = createClient { install(ContentNegotiation) { json() } }
    try {
      val discovered = client.get("/api/network-interfaces").body<NetworkInterfaces>()
      assertTrue(discovered.supported)
      assertEquals("server-wifi", discovered.available.single().id)
      val response = client.put("/api/network-interfaces") {
        contentType(ContentType.Application.Json)
        setBody(NetworkInterfaceConfig(listOf("server-wifi")))
      }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(listOf("server-wifi"), response.body<NetworkInterfaces>().config.interfaceIds)
      assertEquals(listOf("server-wifi"), ketch.networkInterfaces().config.interfaceIds)

      for (body in listOf(
        """{"interfaceIds":["client-only"]}""",
        """{"interfaceIds":["server-wifi","server-wifi"]}""",
        """{"interfaceIds":[""]}"""
      )) {
        val invalid = client.put("/api/network-interfaces") {
          contentType(ContentType.Application.Json)
          setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(listOf("server-wifi"), ketch.networkInterfaces().config.interfaceIds)
      }

      val reset = client.put("/api/network-interfaces") {
        contentType(ContentType.Application.Json)
        setBody(NetworkInterfaceConfig())
      }
      assertTrue(reset.body<NetworkInterfaces>().config.interfaceIds.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun unsupportedBackend_reportsCapabilityAndRejectsUpdate() = testApplication {
    val ketch = createTestKetch()
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    val client = createClient { install(ContentNegotiation) { json() } }
    try {
      assertFalse(client.get("/api/network-interfaces").body<NetworkInterfaces>().supported)
      val response = client.put("/api/network-interfaces") {
        contentType(ContentType.Application.Json)
        setBody(NetworkInterfaceConfig())
      }
      assertEquals(HttpStatusCode.NotImplemented, response.status)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun discoveryAndUpdates_requireConfiguredToken() = testApplication {
    val ketch = createTestKetch()
    application {
      with(KetchServer(ketch, apiToken = "secret")) { configureServer() }
    }
    try {
      assertEquals(HttpStatusCode.Unauthorized, client.get("/api/network-interfaces").status)
      assertEquals(HttpStatusCode.Unauthorized, client.put("/api/network-interfaces") {
        contentType(ContentType.Application.Json)
        setBody("""{"interfaceIds":[]}""")
      }.status)
      assertEquals(HttpStatusCode.OK, client.get("/api/network-interfaces") {
        header(HttpHeaders.Authorization, "Bearer secret")
      }.status)
    } finally {
      ketch.close()
    }
  }
}
