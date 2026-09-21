package com.linroid.ketch.remote

import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RemoteNetworkInterfacesTest {
  @Test
  fun discoveryAndUpdate_useRemoteEndpointAndForwardSelectionAndToken() = runTest {
    val available = listOf(NetworkInterfaceInfo("remote-wifi", "Wi-Fi", listOf("192.0.2.1")))
    var selection = NetworkInterfaceConfig()
    val methods = mutableListOf<HttpMethod>()
    val transport = MockEngine { request ->
      assertEquals("remote.example", request.url.host)
      assertEquals("/api/network-interfaces", request.url.encodedPath)
      assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
      methods.add(request.method)
      if (request.method == HttpMethod.Put) {
        selection = Json.decodeFromString(request.body.toByteArray().decodeToString())
      }
      respond(
        Json.encodeToString(NetworkInterfaces(true, available, selection)),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val remote = RemoteKetch("remote.example", 8642, "secret", false, transport)
    try {
      assertEquals(available, remote.networkInterfaces().available)
      val result = remote.updateNetworkInterfaces(NetworkInterfaceConfig(listOf("remote-wifi")))
      assertEquals(listOf("remote-wifi"), result.config.interfaceIds)
      remote.updateNetworkInterfaces(NetworkInterfaceConfig())
      assertEquals(emptyList(), selection.interfaceIds)
      assertEquals(listOf(HttpMethod.Get, HttpMethod.Put, HttpMethod.Put), methods)
    } finally {
      remote.close()
    }
  }

  @Test
  fun oldOrUnsupportedServer_reportsUnavailableAndRejectsUpdates() = runTest {
    for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented)) {
      val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
        respond("", status)
      })
      try {
        assertFalse(remote.networkInterfaces().supported)
        assertFailsWith<UnsupportedOperationException> {
          remote.updateNetworkInterfaces(NetworkInterfaceConfig())
        }
      } finally {
        remote.close()
      }
    }
  }

  @Test
  fun rejectedSelection_isNotReportedAsSuccess() = runTest {
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
      respond("", HttpStatusCode.BadRequest)
    })
    try {
      assertFailsWith<IllegalArgumentException> {
        remote.updateNetworkInterfaces(NetworkInterfaceConfig(listOf("missing")))
      }
    } finally {
      remote.close()
    }
  }
}
