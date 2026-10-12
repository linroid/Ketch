package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A [DownloadSource]'s state as a task record saves it, encoded with its
 * [DownloadSource.stateSerializer]. Sources never build or read it; Ketch does, with
 * [encodeState] and [decodeState].
 *
 * @property sourceType the [DownloadSource.type] that produced this state
 * @property data the state as JSON
 */
@Serializable
data class SourceState(
  val sourceType: String,
  val data: String,
)

// Lenient, so states written by newer builds still decode; encoding stays the default Json.
private val StateJson = Json { ignoreUnknownKeys = true }

/** Encodes [state] as a task record saves it. */
fun <S : Any> DownloadSource<S>.encodeState(state: S): SourceState =
  SourceState(type, StateJson.encodeToString(stateSerializer, state))

/**
 * Decodes the state a task record [saved]. Only a state this source wrote, under its
 * [type][DownloadSource.type] or one of its [previousTypes][DownloadSource.previousTypes], is
 * read: another source's state could decode as this one's and skip its checks.
 *
 * @throws KetchError.CorruptResumeState when it is not a state of this source
 */
fun <S : Any> DownloadSource<S>.decodeState(saved: SourceState): S {
  if (saved.sourceType != type && saved.sourceType !in previousTypes) {
    throw KetchError.CorruptResumeState("Saved by source '${saved.sourceType}', not '$type'")
  }
  return try {
    StateJson.decodeFromString(stateSerializer, saved.data)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    throw KetchError.CorruptResumeState(e.message, e)
  }
}

/** [DownloadSource.download] from the state a task record [saved], if any. */
internal suspend fun <S : Any> DownloadSource<S>.downloadSaved(
  context: DownloadContext,
  saved: SourceState?,
) = download(context, saved?.let { decodeState(it) })

/** [DownloadSource.store], encoded for the task record. */
internal suspend fun <S : Any> DownloadSource<S>.stored(
  context: DownloadContext,
): SourceState? = store(context)?.let { encodeState(it) }

/**
 * [DownloadSource.cleanup] with the state a task record [saved]. One that cannot be read is
 * reported to [unreadable] and passed as `null`, so the source cleans up only what it can prove.
 */
internal suspend fun <S : Any> DownloadSource<S>.cleanupSaved(
  context: DownloadContext,
  saved: SourceState?,
  deleteFiles: Boolean,
  unreadable: (KetchError.CorruptResumeState) -> Unit,
) {
  val state = try {
    saved?.let { decodeState(it) }
  } catch (e: KetchError.CorruptResumeState) {
    unreadable(e)
    null
  }
  cleanup(context, state, deleteFiles)
}
