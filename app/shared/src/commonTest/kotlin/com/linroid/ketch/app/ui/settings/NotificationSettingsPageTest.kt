package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationSettingsPageTest {
  private val embedded = EmbeddedInstance(FakeKetchApi(), "MacBook Pro")

  private fun remote(watch: Boolean) = RemoteInstance(
    instance = FakeKetchApi(),
    remoteConfig = RemoteConfig(host = "nas.local", watch = watch),
    connectionState = MutableStateFlow(ConnectionState.Connected),
  )

  @Test
  fun notifiesAbout_thisDevice_followsItsMute() {
    assertTrue(notifiesAbout(embedded, muted = emptyList()))
    assertFalse(notifiesAbout(embedded, muted = listOf(embedded.deviceId)))
  }

  @Test
  fun notifiesAbout_watchedRemote_followsItsMute() {
    val nas = remote(watch = true)

    assertTrue(notifiesAbout(nas, muted = emptyList()))
    assertFalse(notifiesAbout(nas, muted = listOf(nas.deviceId)))
  }

  @Test
  fun notifiesAbout_remoteNotWatched_isOffBecauseItsEventsDoNotArrive() {
    assertFalse(notifiesAbout(remote(watch = false), muted = emptyList()))
  }
}
