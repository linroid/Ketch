package com.linroid.ketch.app.state

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An operation that can still be undone.
 *
 * @property id unique id within this run of the app.
 * @property label what undoing it reverts, for "Undo {label}", such as "Clear Finished".
 * @property hides tasks the list hides until the operation is undone or committed.
 */
data class PendingOp(
  val id: Long,
  val label: String,
  val hides: Set<TaskKey>,
)

/**
 * Deferred commits behind the app's Undo.
 *
 * Destructive operations (remove, clear finished, discard progress, move to another device) hide
 * their rows at once and commit when the undo window ends. Operations applied at once, such as
 * Pause all, register their inverse instead and commit nothing. [flush] commits everything still
 * pending, for when the app quits.
 *
 * @param scope runs the timers, commits and inverses.
 * @param window how long an operation can be undone.
 */
class PendingOps(
  private val scope: CoroutineScope,
  private val window: Duration = DEFAULT_WINDOW,
) {
  private class Entry(
    val op: PendingOp,
    val commit: suspend () -> Unit,
    val undo: suspend () -> Unit,
  ) {
    var timer: Job? = null
    var committing = false
  }

  private val log = KetchLogger("PendingOps")
  private var nextId = 1L
  private val entries = mutableListOf<Entry>()
  private val opsState = MutableStateFlow<List<PendingOp>>(emptyList())
  private val hiddenState = MutableStateFlow<Set<TaskKey>>(emptySet())

  /** Operations that can still be undone, oldest first. */
  val ops: StateFlow<List<PendingOp>> = opsState.asStateFlow()

  /** Tasks hidden by operations that are pending or committing. */
  val hidden: StateFlow<Set<TaskKey>> = hiddenState.asStateFlow()

  /**
   * Registers an operation that commits after [timeout] unless undone first.
   *
   * @param label what undoing it reverts.
   * @param hides tasks to hide until it is undone or committed.
   * @param timeout how long it can be undone.
   * @param commit runs when the window ends or on [flush]; failures are logged, so report them
   *   from inside the block.
   * @param undo runs when the operation is undone; the hidden tasks show again first.
   */
  fun register(
    label: String,
    hides: Set<TaskKey> = emptySet(),
    timeout: Duration = window,
    commit: suspend () -> Unit = {},
    undo: suspend () -> Unit = {},
  ): PendingOp {
    val entry = Entry(PendingOp(nextId++, label, hides), commit, undo)
    entries += entry
    publish()
    entry.timer = scope.launch {
      delay(timeout)
      entry.timer = null
      commit(entry, CoroutineStart.DEFAULT)
    }
    return entry.op
  }

  /** Undoes the operation with [id]; returns `false` when it already committed. */
  fun undo(id: Long): Boolean {
    val entry = entries.firstOrNull { it.op.id == id && !it.committing } ?: return false
    entry.timer?.cancel()
    entries -= entry
    publish()
    scope.launch {
      try {
        entry.undo()
      } catch (e: Exception) {
        log.w { "Undo of ${entry.op.label} failed: ${e.describeCauses()}" }
      }
    }
    return true
  }

  /** Undoes the most recent operation that can still be undone. */
  fun undoLast(): Boolean {
    val last = entries.lastOrNull { !it.committing } ?: return false
    return undo(last.op.id)
  }

  /** Commits the operation with [id] now, for example when its toast is closed. */
  fun commitNow(id: Long) {
    entries.firstOrNull { it.op.id == id && !it.committing }
      ?.let { commit(it, CoroutineStart.DEFAULT) }
  }

  /**
   * Commits every pending operation now. The commits start before this returns and finish even
   * when [scope] is cancelled right after, as it is when the app closes.
   */
  fun flush() {
    entries.filter { !it.committing }.forEach { commit(it, CoroutineStart.UNDISPATCHED) }
  }

  private fun commit(entry: Entry, start: CoroutineStart) {
    if (entry.committing) return
    entry.committing = true
    entry.timer?.cancel()
    publish()
    scope.launch(start = start) {
      withContext(NonCancellable) {
        try {
          entry.commit()
        } catch (e: Exception) {
          log.w { "Commit of ${entry.op.label} failed: ${e.describeCauses()}" }
        } finally {
          entries -= entry
          publish()
        }
      }
    }
  }

  private fun publish() {
    opsState.value = entries.filter { !it.committing }.map { it.op }
    hiddenState.value = entries.flatMapTo(mutableSetOf()) { it.op.hides }
  }

  private companion object {
    val DEFAULT_WINDOW = 6.seconds
  }
}
