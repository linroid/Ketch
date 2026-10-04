package com.linroid.ketch.ai.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTextTest {

  @Test
  fun sanitizeAgentText_whitespaceRuns_becomeOneSpace() {
    assertEquals("Read the notes", sanitizeAgentText("  Read\n\tthe\r\n  notes \n", 160))
  }

  @Test
  fun sanitizeAgentText_hiddenCharacters_areRemoved() {
    // Bidi overrides, isolates and marks, zero-width characters, the BOM and C0/C1 controls.
    val text = "Always\u202E allow\u2066 \u200Bthis\u200F\u061C\u2060\u001B[0m site\u0085\uFEFF"

    assertEquals("Always allow this[0m site", sanitizeAgentText(text, 160))
  }

  @Test
  fun sanitizeAgentText_overLimit_isCutWithEllipsis() {
    assertEquals("one two…", sanitizeAgentText("one two three", 9))
  }

  @Test
  fun sanitizeAgentText_cutInsideSurrogatePair_dropsTheHalf() {
    // The emoji takes two chars; the cut would otherwise keep its high surrogate.
    assertEquals("ab…", sanitizeAgentText("ab\uD83D\uDE00cd", 4))
  }

  @Test
  fun sanitizeAgentText_keepLineBreaks_keepsEveryKindOfLineBreak() {
    val text = "1. Search\n2. Fetch\r\n3. Check\r4. Filter\u20285. Rank\u20296. Answer"

    assertEquals(
      "1. Search\n2. Fetch\n3. Check\n4. Filter\n5. Rank\n6. Answer",
      sanitizeAgentText(text, 160, keepLineBreaks = true),
    )
  }

  @Test
  fun sanitizeAgentText_keepLineBreaks_dropsBlankLines() {
    val text = "\n  Plan:\n\n \t \n\u200B\u202E\n  1. Search  \n\n"

    assertEquals("Plan:\n1. Search", sanitizeAgentText(text, 160, keepLineBreaks = true))
  }

  @Test
  fun sanitizeAgentText_keepLineBreaks_sanitizesEachLine() {
    // A bidi override must not reach the next line, nor a tab or a control keep its spacing.
    val text = "Read\t\tthe \u202Erelease\u200B notes\nThen\u001B[2J  check \u2066it\u0085"

    assertEquals(
      "Read the release notes\nThen[2J check it",
      sanitizeAgentText(text, 160, keepLineBreaks = true),
    )
  }

  @Test
  fun sanitizeAgentText_keepLineBreaks_keepsTwelveLinesMarkingTheCut() {
    val text = (1..15).joinToString("\n") { "Step $it" }

    val lines = sanitizeAgentText(text, 600, keepLineBreaks = true).lines()

    assertEquals(12, lines.size)
    assertEquals("Step 11", lines[10])
    assertEquals("Step 12…", lines[11])
  }

  @Test
  fun sanitizeAgentText_keepLineBreaks_twelveLines_areNotMarked() {
    val text = (1..12).joinToString("\n") { "Step $it" }

    assertEquals(text, sanitizeAgentText(text, 600, keepLineBreaks = true))
  }

  @Test
  fun sanitizeAgentText_keepLineBreaks_overLimit_isCutWithEllipsis() {
    val text = "one two\nthree four"

    // The line break counts toward the limit, and a cut right after it drops it.
    assertEquals("one two\nthree…", sanitizeAgentText(text, 14, keepLineBreaks = true))
    assertEquals("one two…", sanitizeAgentText(text, 9, keepLineBreaks = true))
  }

  @Test
  fun sanitizeAgentText_withoutKeepLineBreaks_joinsLines() {
    val text = "Plan:\n1. Search\u20282. Fetch"

    assertEquals("Plan: 1. Search 2. Fetch", sanitizeAgentText(text, 160))
  }
}
