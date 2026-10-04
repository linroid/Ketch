package com.linroid.ketch.app.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DiscoverHistoryCodecTest {

  private val at = Instant.fromEpochMilliseconds(1_790_000_000_123)

  private val session = DiscoverSession(
    id = "s1",
    title = "Blender for Apple silicon",
    createdAt = at,
    updatedAt = at + 2.minutes,
    turns = listOf(
      DiscoverTurn(
        id = "t1",
        message = "Blender for Apple silicon",
        sites = listOf("blender.org"),
        startedAt = at,
        status = TurnStatus.Done,
        steps = listOf(DiscoveryStep("Plan", "Search blender.org"), DiscoveryStep("Searching")),
        candidates = listOf(
          AiCandidate(
            url = "https://download.blender.org/a.dmg",
            title = "Blender 4.2",
            fileName = "a.dmg",
            fileSize = 432_013_312,
            mimeType = "application/x-apple-diskimage",
            sourceUrl = "https://www.blender.org/download/",
            confidence = 0.92f,
            description = "The installer",
          ),
          AiCandidate(
            url = "https://example.com/b",
            title = "B",
            confidence = 0.4f,
            description = "",
          ),
        ),
        summary = "The official installer.",
        access = listOf(
          AccessNote("www.blender.org", allowed = true),
          AccessNote("mirror.example", allowed = false),
        ),
      ),
      DiscoverTurn(
        id = "t2",
        message = "only arm64",
        sites = emptyList(),
        startedAt = at + 1.minutes,
        status = TurnStatus.Failed,
        errorText = "The provider rejected the key (HTTP 401)",
      ),
    ),
    discarded = setOf("https://example.com/b"),
  )

  @Test
  fun encode_thenDecode_keepsEverySession() {
    val decoded = DiscoverHistoryCodec.decode(DiscoverHistoryCodec.encode(listOf(session)))

    assertEquals(listOf(session), decoded)
  }

  @Test
  fun encode_manySteps_keepsTheLatestOnesWithTheirUrlsRedacted() {
    val steps = (1..35).map { DiscoveryStep("Step $it", "Opened https://cdn.example/f?token=s$it") }
    val turn = session.turns.first().copy(steps = steps)

    val decoded = DiscoverHistoryCodec.decode(
      DiscoverHistoryCodec.encode(listOf(session.copy(turns = listOf(turn)))),
    )

    val saved = decoded!!.single().turns.single().steps
    assertEquals(DiscoverHistoryCodec.MAX_STEPS, saved.size)
    assertEquals(DiscoveryStep("Step 6", "Opened https://cdn.example/f?token=***"), saved.first())
  }

  @Test
  fun decode_unknownKeysAndStatus_readsWhatItKnows() {
    val text = """
      {"version": 1, "sync": true, "sessions": [{
        "id": "s1", "title": "Blender", "createdAt": 1000, "updatedAt": 2000, "pinned": true,
        "turns": [{"id": "t1", "message": "Blender", "startedAt": 1000, "status": "paused"}]
      }]}
    """.trimIndent()

    val turn = DiscoverHistoryCodec.decode(text)!!.single().turns.single()

    assertEquals(TurnStatus.Stopped, turn.status)
    assertEquals(emptyList(), turn.candidates)
    assertEquals("", turn.summary)
  }

  @Test
  fun decode_newerVersion_readsNothing() {
    assertNull(DiscoverHistoryCodec.decode("""{"version": 2, "sessions": []}"""))
  }

  @Test
  fun decode_malformed_readsNothing() {
    assertNull(DiscoverHistoryCodec.decode("""{"version": 1, "sessions": [{"id": 3}]}"""))
    assertNull(DiscoverHistoryCodec.decode("not json"))
  }
}
