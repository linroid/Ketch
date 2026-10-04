package com.linroid.ketch.app.ui.discover

import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.app.state.DiscoverDraft
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiscoverFocusTest {
  private val draft = DiscoverDraft()

  @Test
  fun typingIn_emptyMessageFocused_isNotTyping() {
    val focus = DiscoverFocus().apply { messageFocused = true }

    assertFalse(focus.typingIn(draft))
  }

  @Test
  fun typingIn_messageWithTextFocused_isTyping() {
    val focus = DiscoverFocus().apply { messageFocused = true }
    draft.text = TextFieldValue("only the LTS")

    assertTrue(focus.typingIn(draft))
  }

  @Test
  fun typingIn_websiteFieldFocused_isTypingEvenWhenEmpty() {
    val focus = DiscoverFocus().apply { sitesFocused = true }

    assertTrue(focus.typingIn(draft))
  }

  @Test
  fun typingIn_draftWithTextButFocusElsewhere_isNotTyping() {
    val focus = DiscoverFocus().apply { within = true }
    draft.text = TextFieldValue("only the LTS")

    assertFalse(focus.typingIn(draft))
  }

  @Test
  fun approval_sameId_isTheSameRequester() {
    val focus = DiscoverFocus()

    assertSame(focus.approval("a"), focus.approval("a"))
  }
}
