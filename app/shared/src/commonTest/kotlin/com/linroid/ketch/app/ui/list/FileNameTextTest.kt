package com.linroid.ketch.app.ui.list

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNameTextTest {
  @Test
  fun middleEllipsis_longName_keepsTheStartAndTheExtension() {
    val cut = middleEllipsis("android-studio-2024.2.1.12-mac_arm.dmg", 20)

    assertEquals("android-stud…_arm.dmg", cut)
  }

  @Test
  fun middleEllipsis_enoughRoom_keepsTheName() {
    assertEquals("q3-report.pdf", middleEllipsis("q3-report.pdf", 13))
  }

  @Test
  fun middleEllipsis_nameWithoutExtension_keepsAThirdFromTheEnd() {
    val cut = middleEllipsis("abcdefghijklmnopqrstuvwxyz", 9)

    assertEquals("abcde…wxyz", cut)
  }
}
