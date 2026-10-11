package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchStats
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamSubscriptionTest {
  private val json = Json {
    ignoreUnknownKeys = true
    serializersModule = StreamSerializers
  }

  @Test
  fun interests_carryTheirTypeNamesAndIds() {
    val subscription = StreamSubscription(
      revision = 3,
      progressIntervalMs = 1_000,
      segments = listOf(StreamSegments(setOf("a"), 500)),
      interests = listOf(
        StreamInterest(1, Interest.Segments(setOf("a")), 500),
        StreamInterest(2, Interest.Stats, 1_000),
        StreamInterest(3, Interest.Connections(36), 1_000),
        StreamInterest(4, TorrentInterest("t"), 2_000),
      ),
    )

    val wire = json.encodeToString(subscription)

    for (type in listOf("segments", "stats", "connections", "torrent")) {
      assertTrue("\"type\":\"$type\"" in wire, wire)
    }
    assertFalse("valueSerializer" in wire, wire)
    assertEquals(subscription, json.decodeFromString<StreamSubscription>(wire))
  }

  @Test
  fun interestOfAnUnknownType_decodesAsUnknown() {
    val wire = """
      {"revision":1,"interests":[
        {"id":1,"interest":{"type":"later","since":3},"intervalMs":500},
        {"id":2,"interest":{"type":"stats"},"intervalMs":1000}
      ]}
    """.trimIndent()

    val subscription = json.decodeFromString<StreamSubscription>(wire)

    assertEquals(
      listOf(UnknownInterest, Interest.Stats),
      subscription.interests.map { it.interest },
    )
  }

  @Test
  fun interestUpdate_carriesValuesTheInterestDecodes() {
    val stats = KetchStats(downloadBps = 10, downloadedBytes = 5, totalBytes = 20)
    val update = InterestUpdate(2, json.encodeToJsonElement(Interest.Stats.valueSerializer, stats))
    val wire = json.encodeToString(update)

    val decoded = json.decodeFromString<InterestUpdate>(wire)

    assertEquals(stats, json.decodeFromJsonElement(Interest.Stats.valueSerializer, decoded.value))
    // A torrent's tombstone is a JSON null.
    val gone = json.decodeFromString<InterestUpdate>("""{"id":4,"value":null}""")
    assertEquals(JsonNull, gone.value)
    assertNull(json.decodeFromJsonElement(TorrentInterest("t").valueSerializer, gone.value))
  }
}
