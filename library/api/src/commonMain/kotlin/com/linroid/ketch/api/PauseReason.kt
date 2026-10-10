package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a task is [DownloadState.Paused].
 *
 * Serialized as an object with a `type`: `{"type":"user"}`,
 * `{"type":"preempted","byTaskId":"..."}`, `{"type":"waiting_for_condition"}` or
 * `{"type":"shutdown"}`. A `type` this version does not know, such as one a newer server
 * sends, decodes as [User], so clients never fail on a reason added later.
 */
@Serializable(with = PauseReasonSerializer::class)
sealed class PauseReason {
  /**
   * Paused through [DownloadTask.pause], or restored paused after a restart. Stays paused
   * until [DownloadTask.resume] is called.
   */
  data object User : PauseReason()

  /**
   * Paused to give its download slot to the [DownloadPriority.URGENT] task [byTaskId]. The task
   * still waits in the download queue, reports a [DownloadTask.queuePosition] and resumes on
   * its own when a slot frees. [DownloadTask.pause] turns it into a [User] pause;
   * [DownloadTask.resume] leaves it waiting.
   */
  data class Preempted(val byTaskId: String) : PauseReason()

  /** Waiting for its [DownloadCondition]s to be met again; resumes on its own. */
  data object WaitingForCondition : PauseReason()

  /**
   * Stopped because its Ketch instance closed. Its progress is kept and it resumes when the
   * instance starts again.
   */
  data object Shutdown : PauseReason()

  /**
   * Waiting for [DownloadTask.selectFiles], as [DownloadRequest.awaitFileSelection] asked once
   * the file list was known. Holds no download slot and has no queue position;
   * [DownloadTask.resume] downloads every file. Older clients decode it as [User].
   */
  data object AwaitingFileSelection : PauseReason()
}

// The wire shape of every reason. Not polymorphic on purpose: a sealed polymorphic hierarchy
// throws on a subtype it does not know, which would break older clients of newer servers.
@Serializable
@SerialName("com.linroid.ketch.api.PauseReason.Wire")
private class PauseReasonWire(val type: String, val byTaskId: String? = null)

internal object PauseReasonSerializer : KSerializer<PauseReason> {
  override val descriptor: SerialDescriptor =
    SerialDescriptor("com.linroid.ketch.api.PauseReason", PauseReasonWire.serializer().descriptor)

  override fun serialize(encoder: Encoder, value: PauseReason) {
    val wire = when (value) {
      PauseReason.User -> PauseReasonWire(USER)
      is PauseReason.Preempted -> PauseReasonWire(PREEMPTED, value.byTaskId)
      PauseReason.WaitingForCondition -> PauseReasonWire(WAITING_FOR_CONDITION)
      PauseReason.Shutdown -> PauseReasonWire(SHUTDOWN)
      PauseReason.AwaitingFileSelection -> PauseReasonWire(AWAITING_FILE_SELECTION)
    }
    encoder.encodeSerializableValue(PauseReasonWire.serializer(), wire)
  }

  override fun deserialize(decoder: Decoder): PauseReason {
    // From JSON the object is read as it is, so a newer reason with fields of its own decodes
    // as User even where unknown keys are rejected, such as with Json.Default.
    val (type, byTaskId) = if (decoder is JsonDecoder) {
      val fields = decoder.decodeJsonElement() as? JsonObject ?: return PauseReason.User
      fields.string("type") to fields.string("byTaskId")
    } else {
      val wire = decoder.decodeSerializableValue(PauseReasonWire.serializer())
      wire.type to wire.byTaskId
    }
    return when (type) {
      PREEMPTED -> byTaskId?.let(PauseReason::Preempted) ?: PauseReason.User
      WAITING_FOR_CONDITION -> PauseReason.WaitingForCondition
      SHUTDOWN -> PauseReason.Shutdown
      AWAITING_FILE_SELECTION -> PauseReason.AwaitingFileSelection
      else -> PauseReason.User
    }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private const val USER = "user"
  private const val PREEMPTED = "preempted"
  private const val WAITING_FOR_CONDITION = "waiting_for_condition"
  private const val SHUTDOWN = "shutdown"
  private const val AWAITING_FILE_SELECTION = "awaiting_file_selection"
}
