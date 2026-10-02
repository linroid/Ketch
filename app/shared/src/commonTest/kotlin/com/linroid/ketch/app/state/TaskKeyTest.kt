package com.linroid.ketch.app.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskKeyTest {

  @Test
  fun encode_localTask_usesPayloadScheme() {
    assertEquals("ketch-task://local/3f2a-91c0", TaskKey(LOCAL_DEVICE_ID, "3f2a-91c0").encode())
  }

  @Test
  fun decode_encodedKeyWithReservedCharacters_roundTrips() {
    val keys = listOf(
      TaskKey("192.168.1.20:8642", "a/b"),
      TaskKey("[fd00::20]:8642", "100% done?"),
      TaskKey("nas", "ünïcode name"),
    )
    keys.forEach { key ->
      assertEquals(key, TaskKey.decode(key.encode()), key.encode())
    }
  }

  @Test
  fun decode_malformedPayload_returnsNull() {
    val payloads = listOf(
      "",
      "https://example.com/file.iso",
      "ketch-task://local",
      "ketch-task://local/",
      "ketch-task:///task",
      "ketch-task://local/a/b",
      "ketch-task://local/a%2",
      "ketch-task://local/a%zz",
      "ketch-task://local/a b",
    )
    payloads.forEach { payload ->
      assertNull(TaskKey.decode(payload), payload)
    }
  }
}
