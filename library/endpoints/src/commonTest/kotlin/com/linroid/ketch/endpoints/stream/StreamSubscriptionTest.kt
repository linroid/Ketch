package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.Interest
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamSubscriptionTest {
  private val json = Json {
    ignoreUnknownKeys = true
    serializersModule = StreamSerializers
  }

  @Test
  fun interests_carryTheirTypeNames() {
    val subscription = StreamSubscription(
      revision = 3,
      progressIntervalMs = 1_000,
      interests = listOf(
        StreamInterest(Interest.Segments(setOf("a")), 500),
        StreamInterest(Interest.Stats, 1_000),
        StreamInterest(Interest.Connections(36), 1_000),
        StreamInterest(TorrentInterest("t"), 2_000),
      ),
    )

    val wire = json.encodeToString(subscription)

    for (type in listOf("segments", "stats", "connections", "torrent")) {
      assertTrue("\"type\":\"$type\"" in wire, wire)
    }
    assertEquals(subscription, json.decodeFromString<StreamSubscription>(wire))
  }

  @Test
  fun interestOfAnUnknownType_decodesAsUnknown() {
    val wire = """
      {"revision":1,"interests":[
        {"interest":{"type":"later","since":3},"intervalMs":500},
        {"interest":{"type":"stats"},"intervalMs":1000}
      ]}
    """.trimIndent()

    val subscription = json.decodeFromString<StreamSubscription>(wire)

    assertEquals(
      listOf(UnknownInterest, Interest.Stats),
      subscription.interests.map { it.interest },
    )
  }
}
