package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KetchStatusTest {

  @Test
  fun systemInfo_withoutDefaultDownloadDirectory_decodesAsUnknown() {
    // An older server reports only the folder in use.
    val json = """
      {
        "os": "Linux",
        "arch": "amd64",
        "separator": "/",
        "javaVersion": "21",
        "availableProcessors": 4,
        "maxMemory": 0,
        "totalMemory": 0,
        "freeMemory": 0,
        "downloadDirectory": "/volume1/downloads",
        "totalSpace": 0,
        "freeSpace": 0,
        "usableSpace": 0
      }
    """.trimIndent()

    val system = Json.decodeFromString(SystemInfo.serializer(), json)

    assertEquals("/volume1/downloads", system.downloadDirectory)
    assertNull(system.defaultDownloadDirectory)
  }

  @Test
  fun status_jsonWithoutFeatures_decodesEmptySet() {
    // An older server lists no optional features.
    val json = """
      {
        "name": "nas",
        "version": "1.0.0",
        "revision": "abc1234",
        "uptime": 60,
        "config": {},
        "system": {
          "os": "Linux",
          "arch": "amd64",
          "separator": "/",
          "javaVersion": "21",
          "availableProcessors": 4,
          "maxMemory": 0,
          "totalMemory": 0,
          "freeMemory": 0,
          "downloadDirectory": "/volume1/downloads",
          "totalSpace": 0,
          "freeSpace": 0,
          "usableSpace": 0
        }
      }
    """.trimIndent()

    val status = Json.decodeFromString(KetchStatus.serializer(), json)

    assertTrue(status.features.isEmpty())
  }
}
