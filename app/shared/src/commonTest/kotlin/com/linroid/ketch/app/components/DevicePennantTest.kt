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
  fun monogram_address_standsForTheHostOrTheLastPart() {
    assertEquals("NA", monogram("nas.local:8642"))
    assertEquals("SE", monogram("seedbox.example.com"))
    assertEquals("42", monogram("192.168.1.42:9000"))
    assertEquals("42", monogram("10.0.0.142"))
    assertEquals("NA", monogram("nas:8642"))
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
