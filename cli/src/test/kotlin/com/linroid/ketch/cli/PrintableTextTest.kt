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
  fun printableStep_multiLineDetails_printAsOneLine() {
    // A step's details keep their lines; a page must not get one to look like another step.
    val details = "1. Search blender.org\n2. Fetch its download page\r\n\u2028[Done] 3. Forged"

    val printed = printableStep("Plan", details)

    assertEquals(
      "[Plan] 1. Search blender.org 2. Fetch its download page [Done] 3. Forged",
      printed,
    )
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
