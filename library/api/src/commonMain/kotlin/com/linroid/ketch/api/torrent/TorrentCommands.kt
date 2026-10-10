package com.linroid.ketch.api.torrent

import kotlinx.serialization.Serializable

/**
 * What a [TorrentController] command left the task at.
 *
 * @property revision the revision the outcome was published at; an exact retry returns the same
 * @property selectionGeneration the task's selection generation after the command
 * @property seeding whether the task's seeding intent is set, for seeding commands; `null`
 *   otherwise
 */
@Serializable
data class TorrentCommandResult(
  val taskId: String,
  val revision: TorrentRevision,
  val selectionGeneration: Long,
  val seeding: Boolean? = null,
)

/**
 * Why a [TorrentController] command failed.
 *
 * @property wireName the error code REST responses carry
 */
enum class TorrentCommandError(val wireName: String) {
  /** The backend cannot run this command. */
  UNSUPPORTED("unsupported"),

  /** An argument is malformed or out of range, such as an unknown file ID or a bad cursor. */
  INVALID_INPUT("invalid_input"),

  /** The task's state does not allow the command, such as seeding a task that is not done. */
  INVALID_STATE("invalid_state"),

  /** The torrent's file list is not known yet. */
  METADATA_UNAVAILABLE("metadata_unavailable"),

  /**
   * [TorrentCommandContext.expectedRevision] is stale; [TorrentCommandException.currentRevision]
   * holds the current one.
   */
  CONFLICT("revision_conflict"),

  /** [TorrentCommandContext.idempotencyKey] was used for a different command. */
  KEY_REUSED("idempotency_key_reused"),

  /** A page cursor belongs to an older selection or another order. */
  STALE_CURSOR("stale_cursor"),

  /** A policy forbids the command, such as seeding while uploads are off. */
  POLICY_DENIED("policy_denied"),

  /** A bound was reached: no free slot, a full retry ledger or too many subscriptions. */
  RESOURCE_EXHAUSTED("resource_exhausted"),

  /** Saving the change or touching the task's files failed. */
  STORAGE_FAILURE("storage_failure"),

  /** The task does not exist or is not a torrent. */
  NOT_FOUND("not_found"),

  /** The connection to a remote backend changed while the command ran; client side only. */
  CONNECTION_CHANGED("connection_changed");

  companion object {
    /** The error with [name] as its [wireName], or `null` for a code this version does not know. */
    fun fromWire(name: String?): TorrentCommandError? = entries.firstOrNull { it.wireName == name }
  }
}

/**
 * A [TorrentController] command failed with [error].
 *
 * @property currentRevision the task's current revision, for [TorrentCommandError.CONFLICT]
 */
class TorrentCommandException(
  val error: TorrentCommandError,
  message: String,
  val currentRevision: TorrentRevision? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
