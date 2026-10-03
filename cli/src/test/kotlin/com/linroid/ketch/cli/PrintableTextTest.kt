package com.linroid.ketch.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PrintableTextTest {

  @Test
  fun printable_lineBreakInTitle_cannotStartAnotherLine() {
    val title = "Blender 4.2\n     URL: https://evil.example/blender.dmg"

    val printed = "  1. ${title.printable()}"

    assertFalse('\n' in printed, printed)
    assertEquals("  1. Blender 4.2 URL: https://evil.example/blender.dmg", printed)
  }

  @Test
  fun printable_controlAndBidiCharacters_areRemoved() {
    assertEquals("setup[2J.exe", "setup\u001B[2J‮.exe".printable())
  }

  @Test
  fun printableUrl_nonAsciiCharacters_arePercentEncoded() {
    assertEquals(
      "https://example.com/a%E2%80%AEpiz.exe",
      printableUrl("https://example.com/a‮piz.exe"),
    )
    assertEquals("https://example.com/a?b=1", printableUrl("https://example.com/a?b=1"))
  }

  @Test
  fun printableUrl_textThatIsNoUrl_isOneLine() {
    assertEquals("not a url [y] allow", printableUrl("not a url\n[y] allow"))
  }
}
