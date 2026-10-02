package com.linroid.ketch.app.theme

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchTypographyTest {
  @Test fun ketchTypography_numeralStyles_useTabularFigures() {
    val type = ketchTypography()
    for (style in listOf(type.numeralXL, type.numeralL, type.numeral, type.numeralS)) {
      assertEquals("tnum", style.fontFeatureSettings)
    }
  }

  @Test fun ketchTypography_comfortable_raisesRowNamesAndCaptions() {
    val compact = ketchTypography(density = KetchDensity.Compact)
    val comfortable = ketchTypography(density = KetchDensity.Comfortable)
    assertEquals(14.sp, compact.bodyStrong.fontSize)
    assertEquals(15.sp, comfortable.bodyStrong.fontSize)
    assertEquals(12.sp, compact.caption.fontSize)
    assertEquals(13.sp, comfortable.caption.fontSize)
    assertEquals(compact.body, comfortable.body)
  }
}
