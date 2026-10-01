package com.linroid.ketch.app.ui.feedback

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
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
}
