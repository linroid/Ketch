package com.linroid.ketch.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

class DevicePennantTest {
  @Test
  fun monogram_severalWords_takesTheFirstTwoInitials() {
    assertEquals("NB", monogram("NAS-Basement"))
    assertEquals("LM", monogram("Lins-MacBook-Pro"))
    assertEquals("TM", monogram("this mac"))
  }

  @Test
  fun monogram_oneWord_takesItsNextCapitalOrSecondLetter() {
    assertEquals("MB", monogram("MacBook"))
    assertEquals("LO", monogram("localhost"))
    assertEquals("X", monogram("x"))
  }

  @Test
  fun monogram_noLettersOrDigits_isAQuestionMark() {
    assertEquals("?", monogram(" - "))
  }

  @Test
  fun failureBadge_counts_capAtNine() {
    assertEquals("3", failureBadge(3))
    assertEquals("9+", failureBadge(12))
  }
}
