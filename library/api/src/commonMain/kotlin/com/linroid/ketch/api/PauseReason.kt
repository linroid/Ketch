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
 * Why a task is [DownloadState.Paused]. Serialized with a `type`, such as
 * `{"type":"preempted","byTaskId":"..."}`; unknown types decode as [User].
 */
@Serializable(with = PauseReasonSerializer::class)
sealed class PauseReason {
  /** Paused by [DownloadTask.pause], or restored paused; waits for [DownloadTask.resume]. */
  data object User : PauseReason()

  /**
   * Gave its slot to the [DownloadPriority.URGENT] task [byTaskId]; still queued and resumes on
   * its own. [DownloadTask.pause] makes it a [User] pause.
   */
  data class Preempted(val byTaskId: String) : PauseReason()

  /** Waiting for its [DownloadCondition]s; resumes on its own. */
  data object WaitingForCondition : PauseReason()

  /** Stopped by the instance closing; resumes when it starts again. */
  data object Shutdown : PauseReason()

  /**
   * Waiting for [DownloadTask.selectFiles] ([DownloadRequest.awaitFileSelection]), holding no
   * slot; [DownloadTask.resume] downloads the [default][ResolvedSource.defaultSelection] files.
   */
  data object AwaitingFileSelection : PauseReason()
}

// Not polymorphic on purpose: that throws on unknown subtypes, breaking older clients.
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
    // Read as a JSON object, so newer reasons with extra fields decode even in strict Json.
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
