package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.CandidateAddResult
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.deviceId
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_review
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.action_undo
import ketch.app.shared.generated.resources.count_downloads
import ketch.app.shared.generated.resources.feedback_undo_add
import ketch.app.shared.generated.resources.intake_add_failed
import ketch.app.shared.generated.resources.intake_added_failed
import ketch.app.shared.generated.resources.intake_added_here
import ketch.app.shared.generated.resources.intake_added_to
import kotlinx.coroutines.Job
import kotlin.time.Duration.Companion.seconds

private val log = KetchLogger("DiscoverScreen")

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
    MessageAction(Res.string.action_review.text()) {
      openIntake(aiDiscover.reviewRequest(failed, target.deviceId, query))
    }
  }
  val tasks = result.added
  if (tasks.isEmpty()) {
    val what = failed.singleOrNull()?.let { verbatim(candidateName(it)) }
      ?: Res.plurals.count_downloads.text(failed.size)
    messages.post(
      level = MessageLevel.Error,
      title = Res.string.intake_add_failed.text(what),
      detail = firstError?.message?.let(::verbatim),
      deviceId = target.deviceId,
      actions = listOfNotNull(review),
      cause = firstError,
    )
    return
  }
  val undoTitle = Res.string.feedback_undo_add.text()
  val op = pendingOps.register(undoTitle, timeout = ADD_UNDO_WINDOW, undo = {
    tasks.forEach { task ->
      catchingUnlessCancelled { task.remove(deleteFiles = true) }.onFailure { e ->
        log.w { "Couldn't undo the add of taskId=${task.taskId}: ${e.describeCauses()}" }
      }
    }
  })
  // Under All devices the target may show already; switching to it would hide the others.
  val shown = target in shownInstances.value
  val single = tasks.singleOrNull()?.takeIf { failed.isEmpty() }
  val key = single?.let { TaskKey(target.deviceId, it.taskId) }
  val show = MessageAction(Res.string.action_show.text()) {
    if (!shown) switchInstance(target)
    showDownloads(StatusFilter.All)
    key?.let(::inspect)
  }
  val what = added.singleOrNull()?.takeIf { single != null }?.let { verbatim(candidateName(it)) }
    ?: Res.plurals.count_downloads.text(tasks.size)
  val device = target.displayName
  val title = listOfNotNull(
    if (shown) {
      Res.string.intake_added_here.text(what, device)
    } else {
      Res.string.intake_added_to.text(what, device)
    },
    Res.plurals.intake_added_failed.text(failed.size).takeIf { failed.isNotEmpty() },
  ).joinText()
  messages.post(
    level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
    title = title,
    detail = firstError?.message?.let(::verbatim),
    taskKey = key,
    deviceId = target.deviceId,
    actions = listOf(
      review ?: show,
      MessageAction(Res.string.action_undo.text()) { pendingOps.undo(op.id) }
    ),
    cause = firstError,
  )
}

/** The name a result is saved under, as messages and rows show it. */
internal fun candidateName(candidate: AiCandidate): String =
  candidate.fileName?.takeIf { it.isNotBlank() } ?: candidate.title.ifBlank { candidate.url }

private val ADD_UNDO_WINDOW = 8.seconds
