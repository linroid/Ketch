package com.linroid.ketch.torrent

import okio.Buffer
import okio.ByteString.Companion.toByteString

/** Shared exchange allowance for bounded scrape bodies, parse nodes, URL copies, and UDP scratch. */
internal const val TRACKER_SCRAPE_WORKSPACE_BYTES: Int = 64 * 1024 * 17

/** Tracker-reported swarm counts; these are not authenticated content or local progress. */
internal data class TrackerScrape(val complete: Long, val downloaded: Long, val incomplete: Long) {
  init { require(complete >= 0 && downloaded >= 0 && incomplete >= 0) }

  companion object {
    /** Keep UDP requests below the IPv6 minimum MTU and reject ambiguous wire identities. */
    fun validate(topics: List<TrackerTopic>) {
      require(topics.size in 1..50) { "Scrape requires 1 to 50 topics" }
      require(topics.map { it.wireBytes().toByteString() }.distinct().size == topics.size) {
        "Scrape topics have duplicate wire identities"
      }
    }

    fun parseHttp(bytes: ByteArray, topics: List<TrackerTopic>): Map<TrackerTopic, TrackerScrape> {
      validate(topics)
      val root = Bencode.parse(bytes, 64 * 1024, 4096)
      require(root["failure reason"] == null && root["failure_reason"] == null) {
        "Tracker rejected scrape"
      }
      val files = requireNotNull(root["files"]?.dictionary) { "Invalid scrape response" }
      val result = mutableMapOf<TrackerTopic, TrackerScrape>()
      for (topic in topics) {
        // Trackers can omit unknown torrents; absence is not a zero-count result.
        val entry = files[topic.wireBytes().toByteString()] ?: continue
        result[topic] = TrackerScrape(requireNotNull(entry["complete"]?.integer),
          requireNotNull(entry["downloaded"]?.integer),
          requireNotNull(entry["incomplete"]?.integer))
      }
      return result
    }

    /** The caller validates the UDP action, transaction, and source before parsing the counts. */
    fun parseUdp(bytes: ByteArray, topics: List<TrackerTopic>): Map<TrackerTopic, TrackerScrape> {
      validate(topics)
      require(bytes.size >= 8 + 12 * topics.size) { "Truncated scrape response" }
      val input = Buffer().write(bytes)
      input.skip(8)
      return topics.associateWith {
        TrackerScrape(input.readInt().toLong() and 0xffffffffL,
          input.readInt().toLong() and 0xffffffffL, input.readInt().toLong() and 0xffffffffL)
      }
    }
  }
}
