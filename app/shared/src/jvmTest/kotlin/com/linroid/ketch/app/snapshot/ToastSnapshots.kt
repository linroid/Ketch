package com.linroid.ketch.app.snapshot

import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.verbatim
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Where the app stacks its toasts: in the corner of the content card on wide windows, with and
 * without the inspector, and centered above the add button on phones; see [SnapshotHarness] for
 * how to run it.
 */
class ToastSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun app_toasts_stackAtTheBottom() {
    appSnapshots("toasts", themes = listOf(SnapshotTheme.Light)) { postToasts() }
  }

  @Test
  fun app_toastsWithInspector_stackBesideIt() {
    appSnapshots(
      "toasts-inspector",
      sizes = listOf(SnapshotSize.Desktop, SnapshotSize.SmallDesktop),
      themes = listOf(SnapshotTheme.Dark),
    ) {
      inspect("model-weights.safetensors")
      postToasts()
    }
  }

  private fun AppScenario.postToasts() {
    state.messages.post(
      MessageLevel.Success,
      verbatim("Removed 3 downloads"),
      actions = listOf(MessageAction(verbatim("Undo")) {}),
      toast = ToastMode.Sticky,
    )
    state.messages.post(
      MessageLevel.Error,
      verbatim("Couldn't pause imagenet-part03.tar on MacBook Pro"),
      actions = listOf(MessageAction(verbatim("Try again")) {}),
      cause = IllegalStateException("Connection lost"),
    )
  }
}
