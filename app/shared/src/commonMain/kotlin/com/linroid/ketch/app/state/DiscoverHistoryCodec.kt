package com.linroid.ketch.app.state

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrlsIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Writes the Discover history as JSON, `{"version": 1, "sessions": [...]}` with times in epoch
 * milliseconds, and reads it back.
 *
 * A turn keeps its last [MAX_STEPS] steps, with the URLs in them redacted; everything else is
 * kept as it is. Keys this version does not know are ignored, and a status it does not know
 * reads as [TurnStatus.Stopped].
 */
internal object DiscoverHistoryCodec {
  /** Version of the format this app writes. */
  const val VERSION = 1

  /** Most steps a saved turn keeps, the latest ones. */
  const val MAX_STEPS = 30

  private val log = KetchLogger("DiscoverHistory")
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  fun encode(sessions: List<DiscoverSession>): String =
    json.encodeToString(HistoryRecord.serializer(), HistoryRecord(VERSION, sessions.map(::record)))

  /**
   * The sessions in [text], newest first as saved, or `null` when it is not a history this
   * version can read: malformed, or written by a newer version of the app.
   */
  fun decode(text: String): List<DiscoverSession>? {
    val history = try {
      json.decodeFromString(HistoryRecord.serializer(), text)
    } catch (e: IllegalArgumentException) {
      log.w { "Couldn't read the Discover history: ${e.describeCauses()}" }
      return null
    }
    if (history.version > VERSION) {
      log.w { "The Discover history has version ${history.version}, newer than $VERSION" }
      return null
    }
    return history.sessions.map(::session)
  }

  private fun record(session: DiscoverSession) = SessionRecord(
    id = session.id,
    title = session.title,
    createdAt = session.createdAt.toEpochMilliseconds(),
    updatedAt = session.updatedAt.toEpochMilliseconds(),
    turns = session.turns.map(::record),
    discarded = session.discarded.toList(),
  )

  private fun record(turn: DiscoverTurn) = TurnRecord(
    id = turn.id,
    message = turn.message,
    sites = turn.sites,
    startedAt = turn.startedAt.toEpochMilliseconds(),
    status = turn.status.name.lowercase(),
    // Steps quote the pages the agent opened, whose links may carry tokens.
    steps = turn.steps.takeLast(MAX_STEPS).map {
      StepRecord(redactUrlsIn(it.title), redactUrlsIn(it.detail))
    },
    candidates = turn.candidates.map(::record),
    summary = turn.summary,
    error = turn.errorText,
    access = turn.access.map { AccessRecord(it.host, it.allowed) },
  )

  private fun record(candidate: AiCandidate) = CandidateRecord(
    url = candidate.url,
    title = candidate.title,
    fileName = candidate.fileName,
    fileSize = candidate.fileSize,
    mimeType = candidate.mimeType,
    sourceUrl = candidate.sourceUrl,
    confidence = candidate.confidence,
    description = candidate.description,
  )

  private fun session(record: SessionRecord) = DiscoverSession(
    id = record.id,
    title = record.title,
    createdAt = Instant.fromEpochMilliseconds(record.createdAt),
    updatedAt = Instant.fromEpochMilliseconds(record.updatedAt),
    turns = record.turns.map(::turn),
    discarded = record.discarded.toSet(),
  )

  private fun turn(record: TurnRecord) = DiscoverTurn(
    id = record.id,
    message = record.message,
    sites = record.sites,
    startedAt = Instant.fromEpochMilliseconds(record.startedAt),
    status = TurnStatus.entries.firstOrNull { it.name.lowercase() == record.status }
      ?: TurnStatus.Stopped,
    steps = record.steps.map { DiscoveryStep(it.title, it.detail) },
    candidates = record.candidates.map(::candidate),
    summary = record.summary,
    errorText = record.error,
    access = record.access.map { AccessNote(it.host, it.allowed) },
  )

  private fun candidate(record: CandidateRecord) = AiCandidate(
    url = record.url,
    title = record.title,
    fileName = record.fileName,
    fileSize = record.fileSize,
    mimeType = record.mimeType,
    sourceUrl = record.sourceUrl,
    confidence = record.confidence,
    description = record.description,
  )
}

@Serializable
private class HistoryRecord(
  val version: Int,
  val sessions: List<SessionRecord> = emptyList(),
)

@Serializable
private class SessionRecord(
  val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val turns: List<TurnRecord> = emptyList(),
  val discarded: List<String> = emptyList(),
)

@Serializable
private class TurnRecord(
  val id: String,
  val message: String,
  val sites: List<String> = emptyList(),
  val startedAt: Long,
  val status: String,
  val steps: List<StepRecord> = emptyList(),
  val candidates: List<CandidateRecord> = emptyList(),
  val summary: String = "",
  val error: String? = null,
  val access: List<AccessRecord> = emptyList(),
)

@Serializable
private class StepRecord(
  val title: String,
  val detail: String = "",
)

@Serializable
private class CandidateRecord(
  val url: String,
  val title: String = "",
  val fileName: String? = null,
  val fileSize: Long? = null,
  val mimeType: String? = null,
  val sourceUrl: String = "",
  val confidence: Float = 0f,
  val description: String = "",
)

@Serializable
private class AccessRecord(
  val host: String,
  val allowed: Boolean,
)
