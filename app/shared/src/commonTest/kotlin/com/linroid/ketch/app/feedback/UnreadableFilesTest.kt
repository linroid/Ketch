package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnreadableFilesTest {

  @Test
  fun reported_beforeTheAppStarts_postsAStickyWarning() = runTest {
    val files = UnreadableFiles()
    files.report(UnreadableFile(UnreadableFile.Kind.Settings, "/ketch/config.toml.broken-1"))
    val manager = InstanceManager(InstanceFactory(embeddedFactory = { FakeKetchApi("Core") }))
    try {
      val state = AppState(manager, backgroundScope, unreadableFiles = files)
      runCurrent()

      val warning = state.messages.active.value.single()
      assertEquals(MessageLevel.Warning, warning.level)
      assertEquals(ToastMode.Sticky, warning.toast)
      assertEquals(
        "Couldn't read your settings, so Ketch started with the defaults" to
          "The old file was kept as config.toml.broken-1",
        warning.title.load() to warning.detail.load(),
      )
    } finally {
      manager.close()
    }
  }

  @Test
  fun postUnreadable_deletedDatabase_namesNoFile() = runTest {
    val messages = MessageCenter()

    val warning = messages.postUnreadable(UnreadableFile(UnreadableFile.Kind.Downloads, null))

    assertEquals(
      "Couldn't read the list of downloads, so Ketch started with an empty one",
      warning.title.load(),
    )
    assertNull(warning.detail)
    assertEquals(LOCAL_DEVICE_ID, warning.deviceId)
  }
}
