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
}
