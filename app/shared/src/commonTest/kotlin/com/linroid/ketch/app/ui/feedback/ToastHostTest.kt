package com.linroid.ketch.app.ui.feedback

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import okio.IOException

class ToastHostTest {

  private fun message(
    id: Long = 1,
    detail: String? = null,
    cause: Throwable? = null,
    level: MessageLevel = MessageLevel.Error,
  ) = AppMessage(
    id = id,
    level = level,
    title = "Couldn't pause on NAS-Basement",
    detail = detail,
    at = Instant.fromEpochSeconds(0),
    cause = cause,
  )

  @Test
  fun toastDetail_noCause_isTheDetail() {
    assertEquals("6 files", toastDetail(message(detail = "6 files", level = MessageLevel.Success)))
  }

  @Test
  fun toastDetail_lostConnection_readsAsTheCatalogCopy() {
    val cause = IOException("Socket closed")

    assertEquals("Connection lost", toastDetail(message(detail = cause.message, cause = cause)))
  }

  @Test
  fun toastDetail_namedSubject_leadsTheCatalogCopy() {
    val detail = toastDetail(message(detail = "q3-report.pdf", cause = KetchError.Http(403)))

    assertEquals("q3-report.pdf: Access denied (403)", detail)
  }

  @Test
  fun toastDetail_unexplainedFailure_readsAsItsOwnMessage() {
    val cause = IllegalArgumentException("Destination must not be blank")

    assertEquals(
      "Destination must not be blank",
      toastDetail(message(detail = cause.message, cause = cause))
    )
  }

  @Test
  fun visibleToasts_moreThanThree_keepsTheNewestThreeOldestFirst() {
    val active = (1L..5L).map { message(id = it) }

    assertEquals(listOf(3L, 4L, 5L), visibleToasts(active).map { it.id })
  }

  @Test
  fun overflowingToasts_pushedOffTheStack_dismissesTimedOnesAndKeepsErrors() {
    val active = listOf(
      message(id = 1, level = MessageLevel.Success),
      message(id = 2, level = MessageLevel.Error),
      message(id = 3, level = MessageLevel.Success),
      message(id = 4, level = MessageLevel.Info),
      message(id = 5, level = MessageLevel.Success)
    )

    assertEquals(listOf(1L), overflowingToasts(active).map { it.id })
  }

  @Test
  fun overflowingToasts_threeOrFewer_isEmpty() {
    val active = (1L..3L).map { message(id = it, level = MessageLevel.Success) }

    assertEquals(emptyList(), overflowingToasts(active))
  }

  @Test
  fun visibleToasts_withBanner_leavesItToTheBannerHost() {
    val active = listOf(
      message(id = 1, level = MessageLevel.Info).copy(placement = MessagePlacement.Banner),
      message(id = 2, level = MessageLevel.Success)
    )

    assertEquals(listOf(2L), visibleToasts(active).map { it.id })
  }

  @Test
  fun overflowingToasts_bannersBeyondThree_neverDismissed() {
    val banners = (1L..4L).map {
      message(id = it, level = MessageLevel.Info).copy(placement = MessagePlacement.Banner)
    }

    assertEquals(emptyList(), overflowingToasts(banners))
  }
}
