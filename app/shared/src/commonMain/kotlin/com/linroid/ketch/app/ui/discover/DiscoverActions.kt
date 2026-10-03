package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.CandidateAddResult
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.util.downloads
import kotlinx.coroutines.Job

/** The device Discover adds to: the one picked with the On: chip, else the active one. */
internal fun AppState.discoverTarget(): InstanceEntry? {
  val picked = aiDiscover.draft.target
  return instances.value.firstOrNull { it.deviceId == picked } ?: activeInstance.value
}

/**
 * Adds [candidates] to the [discoverTarget] now, each on its own so one that fails never stops
 * the others, and reports the outcome in one message: "Added 3 downloads → This Mac · 1 failed",
 * with Undo, and Review for the ones that failed. Added results leave the selection.
 *
 * @return the running add, or `null` when there is no device to add to.
 */
internal fun AppState.addDiscovered(candidates: List<AiCandidate>): Job? {
  val target = discoverTarget() ?: return null
  val query = aiDiscover.draft.submittedQuery
  return launchCommand {
    val result = aiDiscover.add(target.instance, candidates, query)
    announceAdded(result.added.map { TaskKey(target.deviceId, it.taskId) })
    val added = candidates - result.failed.map { it.first }.toSet()
    val draft = aiDiscover.draft
    draft.selected = draft.selected - added.map { it.url }.toSet()
    reportDiscovered(target, added, result, query)
  }
}

/** Opens the add sheet with [candidates] as its rows, aimed at the [discoverTarget]. */
internal fun AppState.reviewDiscovered(candidates: List<AiCandidate>) {
  openIntake(aiDiscover.reviewRequest(candidates, discoverTarget()?.deviceId))
}

/** Posts what adding to [target] did; [added] are the candidates that were added. */
private fun AppState.reportDiscovered(
  target: InstanceEntry,
  added: List<AiCandidate>,
  result: CandidateAddResult,
  query: String,
) {
  val failed = result.failed.map { it.first }
  val firstError = result.failed.firstOrNull()?.second
  val review = if (failed.isEmpty()) {
    null
  } else {
    MessageAction("Review") {
      openIntake(aiDiscover.reviewRequest(failed, target.deviceId, query))
    }
  }
  val tasks = result.added
  if (tasks.isEmpty()) {
    val what = failed.singleOrNull()?.let(::candidateName) ?: downloads(failed.size)
    messages.post(
      level = MessageLevel.Error,
      title = "Couldn't add $what",
      detail = firstError?.message,
      deviceId = target.deviceId,
      actions = listOfNotNull(review),
      cause = firstError,
    )
    return
  }
  val undo = undoAddAction(tasks)
  val single = tasks.singleOrNull()?.takeIf { failed.isEmpty() }
  val key = single?.let { TaskKey(target.deviceId, it.taskId) }
  val show = MessageAction("Show") { showOn(target, single) }
  val what = added.singleOrNull()?.takeIf { single != null }?.let(::candidateName)
    ?: downloads(tasks.size)
  messages.post(
    level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
    title = addedTitle(what, target, failed.size),
    detail = firstError?.message,
    taskKey = key,
    deviceId = target.deviceId,
    actions = listOf(review ?: show, undo),
    cause = firstError,
  )
}

/** The name a result is saved under, as messages and rows show it. */
internal fun candidateName(candidate: AiCandidate): String =
  candidate.fileName?.takeIf { it.isNotBlank() } ?: candidate.title.ifBlank { candidate.url }
