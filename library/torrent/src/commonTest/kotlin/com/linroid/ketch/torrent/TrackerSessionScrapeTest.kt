package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerSessionScrapeTest {
  private val topic = TrackerTopic.V1(InfoHash.fromBytes(ByteArray(20) { 1 }))
  private val request = TrackerAnnounce(topic, ByteArray(20), 6881, 0, 1)

  @Test
  fun scrapesOnlyCurrentTrackerAndKeepsEstimatesSeparateFromAnnounceState() = runTest {
    val tiers = TrackerTiers(listOf(listOf("https://first/announce"),
      listOf("https://second/announce")), { _, _, _ -> TrackerResponse(emptyList(), 60) })
    assertFalse(tiers.scrapeCurrent(0) { _, _ -> error("No announce yet") })
    tiers.announce(request)
    val counts = TrackerScrape(12, 34, 56)
    assertTrue(tiers.scrapeCurrent(0) { url, topics ->
      assertEquals("https://first/announce", url)
      assertEquals(listOf(topic), topics)
      mapOf(topic to counts)
    })
    val saved = tiers.status()
    assertEquals(counts, saved.first().scrape)
    assertEquals(0, saved.first().lastPeerCount)
    assertEquals(1L, saved.first().attempts)
    assertEquals(TrackerStatus.ScrapeOutcome.NOT_REQUESTED, saved.last().scrapeOutcome)
    assertFalse(tiers.scrapeCurrent(59_999) { _, _ -> error("Too early") })
    assertTrue(tiers.scrapeCurrent(60_000) { _, _ -> emptyMap() })
    assertNull(tiers.status().first().scrape)
    assertEquals(TrackerStatus.ScrapeOutcome.MISSING, tiers.status().first().scrapeOutcome)
    assertEquals(counts, saved.first().scrape)
  }

  @Test
  fun failuresAndCancellationAreRateLimitedAndNeverTriggerFallback() = runTest {
    val tiers = TrackerTiers(listOf(listOf("https://first/announce"),
      listOf("https://second/announce")), { _, _, _ -> TrackerResponse(emptyList(), 60) })
    tiers.preferCurrentTracker { error("Scrape cannot switch private trackers") }
    tiers.announce(request)
    assertFailsWith<IOException> {
      tiers.scrapeCurrent(0) { _, _ -> throw IOException("Unavailable") }
    }
    assertEquals(TrackerStatus.ScrapeOutcome.FAILED, tiers.status().first().scrapeOutcome)
    assertFalse(tiers.scrapeCurrent(1) { _, _ -> error("Too early") })
    assertFailsWith<CancellationException> {
      tiers.scrapeCurrent(60_000) { _, _ -> throw CancellationException("Canceled") }
    }
    assertEquals(TrackerStatus.ScrapeOutcome.CANCELED, tiers.status().first().scrapeOutcome)
    assertFalse(tiers.scrapeCurrent(60_001) { _, _ -> error("Too early") })
    assertEquals(TrackerStatus.Outcome.SUCCEEDED, tiers.status().first().outcome)
    assertEquals(TrackerStatus.Outcome.NOT_CONTACTED, tiers.status().last().outcome)
  }

  @Test
  fun replacingConfigurationDropsOldEstimatesAndRequiresNewAnnounce() = runTest {
    val tiers = TrackerTiers(listOf(listOf("https://old/announce")),
      { _, _, _ -> TrackerResponse(emptyList(), 60) })
    tiers.announce(request)
    tiers.scrapeCurrent(0) { _, _ -> mapOf(topic to TrackerScrape(1, 2, 3)) }
    tiers.replace(TrackerConfiguration.prepare(listOf(listOf("https://new/announce"))))
    assertFalse(tiers.scrapeCurrent(1) { _, _ -> error("No new announce") })
    assertNull(tiers.status().single().scrape)
    tiers.announce(request)
    assertTrue(tiers.scrapeCurrent(1) { url, _ ->
      assertEquals("https://new/announce", url)
      emptyMap()
    })
    assertEquals(1L, tiers.status().single().configurationRevision)
  }
}
