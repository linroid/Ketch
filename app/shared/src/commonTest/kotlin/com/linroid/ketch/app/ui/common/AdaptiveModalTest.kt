package com.linroid.ketch.app.ui.common

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveModalTest {

  @Test
  fun modalForm_compactWindow_isASheetOnEveryPlatform() {
    assertEquals(ModalForm.Sheet, modalForm(599.dp, touch = false))
    assertEquals(ModalForm.Sheet, modalForm(390.dp, touch = true))
  }

  @Test
  fun modalForm_widerWindow_isADialogAnchoredOnTouch() {
    assertEquals(ModalForm.AnchoredDialog, modalForm(820.dp, touch = true))
    assertEquals(ModalForm.CenteredDialog, modalForm(600.dp, touch = false))
  }
}
