package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteConfigTest {

  @Test
  fun decode_remoteSavedBeforeNamesWatchingAndSystems_isUnnamedWatchedAndUnknown() {
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
    assertEquals("token", remote.apiToken)
  }

  @Test
  fun encode_namedUnwatchedRemote_readsBackTheSame() {
    val config = KetchConfig(
      remotes = listOf(
        RemoteConfig(host = "nas.local", name = "NAS \"Basement\"", watch = false),
        RemoteConfig(host = "den-pc", port = 9000),
      ),
    )

    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)

    assertEquals(config.remotes, decoded.remotes)
    assertFalse(decoded.remotes.first().watch)
  }
}
