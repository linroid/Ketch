package com.linroid.ketch.core.task

/**
 * A command of a typed controller ([com.linroid.ketch.core.engine.SourceExtension]), checked
 * against the task's retry ledger ([TaskControl.commands]) before it changes anything.
 *
 * @property key the caller's idempotency key, 1 to 128 characters; never logged
 * @property digest the SHA-256 of the canonical command, as 64 lowercase hex digits; an exact
 *   retry has the same [key] and digest
 * @property precondition throws when the command must not run, such as for a stale revision; it
 *   runs after an exact retry was answered from the ledger, under the task's control lock
 * @property reserve reserves the revision the outcome is published at, given the selection
 *   generation and the sharing intent the command leaves the task at
 */
class ControlCommand(
  val key: String,
  val digest: String,
  val precondition: suspend () -> Unit,
  val reserve: suspend (generation: Long, sharing: Boolean) -> ControlRevision,
)

/**
 * A revision a controller published a control change at, as the retry ledger keeps it.
 *
 * @property epoch the controller's revision epoch
 * @property sequence the revision within the epoch
 */
data class ControlRevision(val epoch: String, val sequence: Long)

/**
 * What a control change left the task at.
 *
 * @property generation the task's selection generation
 * @property sharing the task's sharing intent
 * @property revision the controller revision the change was published at; `null` for changes
 *   made without a [ControlCommand]
 * @property replayed whether an exact retry returned an earlier outcome
 */
class ControlOutcome(
  val generation: Long,
  val sharing: Boolean,
  val revision: ControlRevision?,
  val replayed: Boolean,
)

/**
 * Ketch refused a [ControlCommand] because of the task's retry ledger.
 *
 * @property reason why
 */
class ControlLedgerException(
  val reason: Reason,
  message: String,
) : RuntimeException(message) {
  /** Why the ledger refused a command. */
  enum class Reason {
    /** The command's key was used for another command within the retry window. */
    KEY_REUSED,

    /** The ledger holds [TaskControl.MAX_COMMANDS] commands of the retry window. */
    FULL,
  }
}
