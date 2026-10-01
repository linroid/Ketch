package com.linroid.ketch.app.components

import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class KetchToastTest {

  @Test
  fun toastDuration_infoOrSuccess_lastsFourSecondsOrEightWithActions() {
    assertEquals(4.seconds, toastDuration(MessageLevel.Info, ToastMode.Auto, hasActions = false))
    assertEquals(8.seconds, toastDuration(MessageLevel.Success, ToastMode.Auto, hasActions = true))
  }

  @Test
  fun toastDuration_errorOrSticky_staysUntilDismissed() {
    assertNull(toastDuration(MessageLevel.Error, ToastMode.Auto, hasActions = true))
    assertNull(toastDuration(MessageLevel.Info, ToastMode.Sticky, hasActions = false))
  }
}
