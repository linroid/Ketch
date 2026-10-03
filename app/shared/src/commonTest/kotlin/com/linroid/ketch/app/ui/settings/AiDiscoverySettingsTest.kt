package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class AiDiscoverySettingsTest {
  @Test
  fun connectedCopy_timedTest_namesModelAndTenthsOfASecond() = runTest {
    assertEquals(
      "Connected · claude-sonnet-5 responded in 1.2 s",
      connectedCopy("claude-sonnet-5", 1_249.milliseconds).load(),
    )
    assertEquals(
      "Connected · gpt-6 responded in 0.0 s",
      connectedCopy("gpt-6", 40.milliseconds).load(),
    )
  }

  @Test
  fun connectedCopy_untimedOrUnnamed_leavesThoseOut() = runTest {
    assertEquals("Connected · qwen3 responded", connectedCopy("qwen3", null).load())
    assertEquals("Connected · the model responded", connectedCopy("", null).load())
  }

  @Test
  fun typedSites_urlsAndWildcards_normalizeToEachSiteOnce() {
    val typed = typedSites("https://www.Ubuntu.com/download, *.ubuntu.com\nblender.org:443")

    assertEquals(TypedSites(listOf("ubuntu.com", "blender.org"), emptyList()), typed)
  }

  @Test
  fun typedSites_wordsWithoutADot_areRejectedAsTyped() {
    val typed = typedSites("nas ubuntu.com https:// Localhost")

    assertEquals(TypedSites(listOf("ubuntu.com"), listOf("nas", "https://", "Localhost")), typed)
  }

  @Test
  fun typedSites_numbersAndStrayHyphens_areRejected() {
    val typed = typedSites("1.5 203.0.113 -.- ubuntu-.com -ubuntu.com [1.2] xn--p1ai.xn--p1ai")

    assertEquals(
      TypedSites(
        sites = listOf("xn--p1ai.xn--p1ai"),
        rejected = listOf("1.5", "203.0.113", "-.-", "ubuntu-.com", "-ubuntu.com", "[1.2]"),
      ),
      typed
    )
  }

  @Test
  fun typedSites_ipAddresses_areSites() {
    val typed = typedSites("203.0.113.7 [2001:DB8::1]:8443")

    assertEquals(TypedSites(listOf("203.0.113.7", "[2001:db8::1]"), emptyList()), typed)
  }

  @Test
  fun typedSites_blankText_hasNothing() {
    assertEquals(TypedSites(emptyList(), emptyList()), typedSites(" , \n"))
  }
}
