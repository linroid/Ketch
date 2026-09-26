package com.linroid.ketch.app

import androidx.compose.runtime.saveable.SaverScope
import com.linroid.ketch.app.state.SettingsEdits
import com.linroid.ketch.app.state.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsEditsTest {

  @Test
  fun requestClose_nothingUnsaved_closesRightAway() {
    val edits = SettingsEdits()

    assertTrue(edits.requestClose())
    assertFalse(edits.confirmingDiscard)
  }

  @Test
  fun requestClose_unsavedEdits_asksBeforeClosing() {
    val edits = SettingsEdits()
    edits.report(SettingsSection.Server, true)

    assertFalse(edits.requestClose())
    assertTrue(edits.confirmingDiscard)
  }

  @Test
  fun report_savedAfterEditing_allowsClosing() {
    val edits = SettingsEdits()
    edits.report(SettingsSection.Downloads, true)
    edits.report(SettingsSection.Downloads, false)

    assertTrue(edits.requestClose())
  }

  @Test
  fun keepEditing_keepsEditsForTheNextClose() {
    val edits = SettingsEdits()
    edits.report(SettingsSection.General, true)
    edits.requestClose()

    edits.keepEditing()

    assertFalse(edits.confirmingDiscard)
    assertFalse(edits.requestClose())
  }

  @Test
  fun discard_forgetsEditsAndConfirmation() {
    val edits = SettingsEdits()
    edits.report(SettingsSection.Ai, true)
    edits.requestClose()

    edits.discard()

    assertFalse(edits.confirmingDiscard)
    assertTrue(edits.unsavedSections.isEmpty())
    assertTrue(edits.requestClose())
  }

  @Test
  fun unsavedSections_followDisplayOrderNotReportOrder() {
    val edits = SettingsEdits()
    edits.report(SettingsSection.Ai, true)
    edits.report(SettingsSection.General, true)
    edits.report(SettingsSection.Ai, true)

    assertEquals(
      listOf(SettingsSection.General, SettingsSection.Ai),
      edits.unsavedSections,
    )
  }

  @Test
  fun saver_restoredState_stillGuardsEveryUnsavedSection() {
    val edits = SettingsEdits()
    edits.report(SettingsSection.Downloads, true)
    edits.report(SettingsSection.Ai, true)

    val saved = with(SettingsEdits.Saver) {
      SaverScope { true }.save(edits)
    }
    val restored = SettingsEdits.Saver.restore(assertNotNull(saved))

    assertEquals(edits.unsavedSections, assertNotNull(restored).unsavedSections)
    assertFalse(restored.requestClose())
  }

  @Test
  fun visible_serverUnsupported_hidesServerSection() {
    assertFalse(SettingsSection.Server in SettingsSection.visible(false))
    assertTrue(SettingsSection.Server in SettingsSection.visible(true))
  }
}
