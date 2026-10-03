package com.linroid.ketch.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {
  @Test
  fun parse_tagWithBuildMetadata_keepsVersionOnly() {
    assertEquals(ReleaseVersion(0, 0, 1, "rc15"), ReleaseVersion.parse("v0.0.1-rc15+abc123"))
    assertEquals("1.2.3", ReleaseVersion.parse(" 1.2.3 ").toString())
  }

  @Test
  fun parse_notAVersion_returnsNull() {
    assertNull(ReleaseVersion.parse("latest"))
    assertNull(ReleaseVersion.parse("1.2"))
    assertNull(ReleaseVersion.parse("1.2.3-"))
    assertNull(ReleaseVersion.parse("1.2.3-rc 1"))
  }

  @Test
  fun compareTo_preReleaseNumbers_compareByValue() {
    assertOrdered("0.0.1-rc9", "0.0.1-rc15", "0.0.1-rc100")
  }

  @Test
  fun compareTo_release_comesAfterItsPreReleases() {
    assertOrdered("0.0.1-dev", "0.0.1-rc1", "0.0.1", "0.0.2-rc1", "0.0.2")
  }

  @Test
  fun compareTo_coreNumbers_compareByValue() {
    assertOrdered("0.9.9", "0.10.0", "1.0.0", "1.0.10", "2.0.0")
  }

  @Test
  fun compareTo_morePreReleaseParts_comeLater() {
    assertOrdered("1.0.0-rc", "1.0.0-rc.1", "1.0.0-rc.1.1", "1.0.0-rc1")
  }

  @Test
  fun compareTo_leadingZeros_compareByValue() {
    val padded = assertNotNullVersion("1.0.0-rc015")
    assertEquals(0, padded.compareTo(assertNotNullVersion("1.0.0-rc15")))
  }

  private fun assertOrdered(vararg versions: String) {
    val parsed = versions.map { assertNotNullVersion(it) }
    for ((earlier, later) in parsed.zipWithNext()) {
      assertTrue(earlier < later, "$earlier should come before $later")
    }
  }

  private fun assertNotNullVersion(text: String): ReleaseVersion =
    ReleaseVersion.parse(text) ?: error("$text did not parse")
}
