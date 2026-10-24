package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteConfigTest {

  @Test
  fun decode_remoteSavedBeforeNamesWatchingSystemsAndIds_isUnnamedWatchedAndUnknown() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[[remotes]]
      |host = "192.168.1.100"
      |port = 8642
      |apiToken = "token"
      |secure = false
      """.trimMargin(),
    )

    val remote = decoded.remotes.single()
    assertNull(remote.name)
    assertTrue(remote.watch)
    assertNull(remote.os)
    assertNull(remote.instanceId)
    assertEquals("token", remote.apiToken)
  }

  @Test
  fun encode_remotesWithNamesSystemsAndIds_readBackTheSame() {
    val config = KetchConfig(
      remotes = listOf(
        RemoteConfig(host = "nas.local", name = "NAS \"Basement\"", watch = false),
        RemoteConfig(
          host = "den-pc",
          port = 9000,
          os = "Windows 11",
          instanceId = "0b9f3a52-6c1e-4d3e-9a51-2f4c7d8e9b10",
        ),
      ),
    )

    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)

    assertEquals(config.remotes, decoded.remotes)
    assertFalse(decoded.remotes.first().watch)
  }
}
