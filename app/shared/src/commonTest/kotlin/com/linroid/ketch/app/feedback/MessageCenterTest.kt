package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageCenterTest {

  @Test
  fun post_overTheLimit_keepsTheNewestFirst() = runTest {
    val center = MessageCenter(historyLimit = 2)

    listOf("first", "second", "third").forEach { center.post(MessageLevel.Info, verbatim(it)) }

    assertEquals(listOf("third", "second"), center.history.value.map { it.title }.load())
    assertEquals(listOf("second", "third"), center.active.value.map { it.title }.load())
    assertEquals(2, center.unreadCount.value)
  }

  @Test
  fun post_silent_onlyRecordsIt() {
    val center = MessageCenter()

    center.post(MessageLevel.Info, verbatim("Added ubuntu.iso"), toast = ToastMode.Silent)

    assertEquals(1, center.history.value.size)
    assertEquals(emptyList(), center.active.value)
  }

  @Test
  fun postFeedback_success_keepsUndoWithoutCreatingANotification() {
    val center = MessageCenter()
    var undone = false
    val message = center.postFeedback(
      level = MessageLevel.Success,
      title = verbatim("Removed download"),
      actions = listOf(MessageAction(verbatim("Undo")) { undone = true }),
    )

    assertEquals(listOf(message), center.active.value)
    assertEquals(emptyList(), center.history.value)
    assertEquals(0, center.unreadCount.value)
    center.active.value.single().actions.single().onClick()
    assertEquals(true, undone)
    center.dismiss(message.id)
    assertEquals(emptyList(), center.active.value)
  }

  @Test
  fun postFeedback_info_doesNotAddToExistingUnreadActivity() {
    val center = MessageCenter()
    val completed = center.post(MessageLevel.Success, verbatim("Download complete"))

    center.postFeedback(MessageLevel.Info, verbatim("Nothing to pause"))

    assertEquals(listOf(completed), center.history.value)
    assertEquals(1, center.unreadCount.value)
  }

  @Test
  fun postFeedback_failures_remainInHistory() {
    val center = MessageCenter()
    val partial = center.postFeedback(MessageLevel.Warning, verbatim("Paused 2 · 1 failed"))
    val failed = center.postFeedback(MessageLevel.Error, verbatim("Couldn't pause"))

    assertEquals(listOf(partial, failed), center.active.value)
    assertEquals(listOf(failed, partial), center.history.value)
    assertEquals(2, center.unreadCount.value)
  }

  @Test
  fun post_threeActions_keepsTwo() = runTest {
    val center = MessageCenter()

    val message = center.post(
      level = MessageLevel.Error,
      title = verbatim("Couldn't pause"),
      actions = listOf("Retry", "Copy", "Open logs").map { MessageAction(verbatim(it)) {} },
    )

    assertEquals(listOf("Retry", "Copy"), message.actions.map { it.label }.load())
  }

  @Test
  fun dismiss_message_keepsItInTheHistory() {
    val center = MessageCenter()
    val message = center.post(MessageLevel.Error, verbatim("Couldn't pause"))

    center.dismiss(message.id)
    center.markAllRead()

    assertEquals(emptyList(), center.active.value)
    assertEquals(listOf(message), center.history.value)
    assertEquals(0, center.unreadCount.value)
  }

  @Test
  fun withdraw_unreadMessage_leavesTheHistoryAndTheUnreadCount() {
    val center = MessageCenter()
    val read = center.post(MessageLevel.Info, verbatim("Slow lane on"))
    center.markAllRead()
    val withdrawn = center.post(MessageLevel.Info, verbatim("Added ubuntu.iso"))
    val kept = center.post(MessageLevel.Success, verbatim("Added ubuntu.iso → This Mac"))

    center.withdraw(withdrawn.id)

    assertEquals(listOf(kept, read), center.history.value)
    assertEquals(listOf(read, kept), center.active.value)
    assertEquals(1, center.unreadCount.value)
  }

  @Test
  fun withdraw_readMessage_keepsTheUnreadCount() = runTest {
    val center = MessageCenter()
    val withdrawn = center.post(MessageLevel.Info, verbatim("Added ubuntu.iso"))
    center.markAllRead()
    center.post(MessageLevel.Info, verbatim("Slow lane on"))

    center.withdraw(withdrawn.id)

    assertEquals(listOf("Slow lane on"), center.history.value.map { it.title }.load())
    assertEquals(1, center.unreadCount.value)
  }

  @Test
  fun post_banner_showsWithoutEnteringTheHistory() {
    val center = MessageCenter()

    val banner = center.post(
      level = MessageLevel.Info,
      title = verbatim("Downloads pause when Ketch is in the background"),
      toast = ToastMode.Sticky,
      placement = MessagePlacement.Banner,
    )

    assertEquals(listOf(banner), center.active.value)
    assertEquals(emptyList(), center.history.value)
    assertEquals(0, center.unreadCount.value)
  }
}
