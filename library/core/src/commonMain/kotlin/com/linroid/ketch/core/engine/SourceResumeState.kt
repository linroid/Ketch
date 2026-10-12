package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A [DownloadSource]'s resume state as a task record saves it: the source's own state, encoded
 * with its [DownloadSource.resumeStateSerializer]. Sources never build or read it; Ketch does,
 * with [encodeResumeState] and [decodeResumeState].
 *
 * @property sourceType the [DownloadSource.type] that produced this state
 * @property data the state as JSON
 */
@Serializable
data class SourceResumeState(
  val sourceType: String,
  val data: String,
)

// Lenient, so states written by newer builds still decode; encoding stays the default Json.
private val ResumeStateJson = Json { ignoreUnknownKeys = true }

/** Encodes [state] as a task record saves it. */
fun <S : Any> DownloadSource<S>.encodeResumeState(state: S): SourceResumeState =
  SourceResumeState(type, ResumeStateJson.encodeToString(resumeStateSerializer, state))

/**
 * Decodes the state a task record [saved]. Only a state this source wrote, under its
 * [type][DownloadSource.type] or one of its [previousTypes][DownloadSource.previousTypes], is
 * read: another source's state could decode as this one's and skip its checks.
 *
 * @throws KetchError.CorruptResumeState when it is not a state of this source
 */
fun <S : Any> DownloadSource<S>.decodeResumeState(saved: SourceResumeState): S {
  if (saved.sourceType != type && saved.sourceType !in previousTypes) {
    throw KetchError.CorruptResumeState("Saved by source '${saved.sourceType}', not '$type'")
  }
  return try {
    ResumeStateJson.decodeFromString(resumeStateSerializer, saved.data)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    throw KetchError.CorruptResumeState(e.message, e)
  }
}

/** [DownloadSource.resume] from the state a task record [saved], if any. */
internal suspend fun <S : Any> DownloadSource<S>.resumeSaved(
  context: DownloadContext,
  saved: SourceResumeState?,
) = resume(context, saved?.let { decodeResumeState(it) })

/** [DownloadSource.store], encoded for the task record. */
internal suspend fun <S : Any> DownloadSource<S>.stored(
  context: DownloadContext,
): SourceResumeState? = store(context)?.let { encodeResumeState(it) }

/**
 * [DownloadSource.cleanup] with the state a task record [saved]. One that cannot be read is
 * reported to [unreadable] and passed as `null`, so the source cleans up only what it can prove.
 */
internal suspend fun <S : Any> DownloadSource<S>.cleanupSaved(
  context: DownloadContext,
  saved: SourceResumeState?,
  deleteFiles: Boolean,
  unreadable: (KetchError.CorruptResumeState) -> Unit,
) {
  val state = try {
    saved?.let { decodeResumeState(it) }
  } catch (e: KetchError.CorruptResumeState) {
    unreadable(e)
    null
  }
  cleanup(context, state, deleteFiles)
}
