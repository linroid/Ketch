package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkInterfacesTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun kind_roundTripsAsLowercaseName() {
    val info = NetworkInterfaceInfo("a", "Wi-Fi", listOf("192.0.2.1"), NetworkKind.CELLULAR)
    val encoded = json.encodeToString(NetworkInterfaceInfo.serializer(), info)
    assertEquals(true, "\"kind\":\"cellular\"" in encoded, encoded)
    assertEquals(info, json.decodeFromString(NetworkInterfaceInfo.serializer(), encoded))
  }

  @Test
  fun kind_missingOrUnknown_decodesAsOther() {
    val older = """{"id":"a","name":"en0","addresses":[]}"""
    val newer = """{"id":"a","name":"en0","addresses":[],"kind":"satellite"}"""
    for (body in listOf(older, newer)) {
      assertEquals(
        NetworkKind.OTHER,
        json.decodeFromString(NetworkInterfaceInfo.serializer(), body).kind,
      )
    }
  }
}
