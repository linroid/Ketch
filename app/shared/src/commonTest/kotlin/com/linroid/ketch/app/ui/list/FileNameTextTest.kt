package com.linroid.ketch.app.ui.list

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNameTextTest {
  @Test
  fun middleEllipsis_longName_keepsTheStartAndTheExtension() {
    val cut = middleEllipsis("android-studio-2024.2.1.12-mac_arm.dmg", 20)

    assertEquals("android-stud…arm.dmg", cut)
  }

  @Test
  fun middleEllipsis_enoughRoom_keepsTheName() {
    assertEquals("q3-report.pdf", middleEllipsis("q3-report.pdf", 13))
  }

  @Test
  fun middleEllipsis_cutAfterASeparator_dropsItBeforeTheEllipsis() {
    val cut = middleEllipsis("archlinux-2026.10.01-x86_64.iso", 26)

    assertEquals("archlinux-2026.10…6_64.iso", cut)
  }

  @Test
  fun middleEllipsis_tailStartingWithASeparator_dropsItAfterTheEllipsis() {
    assertEquals("aaaaaaaa…bbb", middleEllipsis("aaaaaaaaaaaa_bbb", 12))
  }

  @Test
  fun middleEllipsis_nameWithoutExtension_keepsAThirdFromTheEnd() {
    val cut = middleEllipsis("abcdefghijklmnopqrstuvwxyz", 9)

    assertEquals("abcde…wxyz", cut)
  }
}
