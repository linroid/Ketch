package com.linroid.ketch.app.feedback

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageCenterTest {

  @Test
  fun post_overTheLimit_keepsTheNewestFirst() {
    val center = MessageCenter(historyLimit = 2)

    listOf("first", "second", "third").forEach { center.post(MessageLevel.Info, it) }

    assertEquals(listOf("third", "second"), center.history.value.map { it.title })
    assertEquals(listOf("second", "third"), center.active.value.map { it.title })
    assertEquals(2, center.unreadCount.value)
  }

  @Test
  fun post_silent_onlyRecordsIt() {
    val center = MessageCenter()

    center.post(MessageLevel.Info, "Added ubuntu.iso", toast = ToastMode.Silent)

    assertEquals(1, center.history.value.size)
    assertEquals(emptyList(), center.active.value)
  }

  @Test
  fun post_threeActions_keepsTwo() {
    val center = MessageCenter()

    val message = center.post(
      level = MessageLevel.Error,
      title = "Couldn't pause",
      actions = listOf("Retry", "Copy", "Open logs").map { MessageAction(it) {} },
    )

    assertEquals(listOf("Retry", "Copy"), message.actions.map { it.label })
  }

  @Test
  fun dismiss_message_keepsItInTheHistory() {
    val center = MessageCenter()
    val message = center.post(MessageLevel.Error, "Couldn't pause")

    center.dismiss(message.id)
    center.markAllRead()

    assertEquals(emptyList(), center.active.value)
    assertEquals(listOf(message), center.history.value)
    assertEquals(0, center.unreadCount.value)
  }

  @Test
  fun withdraw_unreadMessage_leavesTheHistoryAndTheUnreadCount() {
    val center = MessageCenter()
    val read = center.post(MessageLevel.Info, "Slow lane on")
    center.markAllRead()
    val withdrawn = center.post(MessageLevel.Info, "Added ubuntu.iso")
    val kept = center.post(MessageLevel.Success, "Added ubuntu.iso → This Mac")

    center.withdraw(withdrawn.id)

    assertEquals(listOf(kept, read), center.history.value)
    assertEquals(listOf(read, kept), center.active.value)
    assertEquals(1, center.unreadCount.value)
  }

  @Test
  fun withdraw_readMessage_keepsTheUnreadCount() {
    val center = MessageCenter()
    val withdrawn = center.post(MessageLevel.Info, "Added ubuntu.iso")
    center.markAllRead()
    center.post(MessageLevel.Info, "Slow lane on")

    center.withdraw(withdrawn.id)

    assertEquals(listOf("Slow lane on"), center.history.value.map { it.title })
    assertEquals(1, center.unreadCount.value)
  }

  @Test
  fun post_banner_showsWithoutEnteringTheHistory() {
    val center = MessageCenter()

    val banner = center.post(
      level = MessageLevel.Info,
      title = "Downloads pause when Ketch is in the background",
      toast = ToastMode.Sticky,
      placement = MessagePlacement.Banner,
    )

    assertEquals(listOf(banner), center.active.value)
    assertEquals(emptyList(), center.history.value)
    assertEquals(0, center.unreadCount.value)
  }
}
