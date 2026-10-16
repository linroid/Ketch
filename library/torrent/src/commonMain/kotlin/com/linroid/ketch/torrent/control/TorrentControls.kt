package com.linroid.ketch.torrent.control

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.engine.SourceExtension
import com.linroid.ketch.core.engine.SourceHost
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * What a torrent source adds to the Ketch that runs it ([SourceExtension]): the
 * [TorrentController] that `ketch.controller(TorrentController)` returns, and seeding of completed
 * tasks. It shows on every completed task of the source whether it seeds
 * ([DownloadState.Completed.sharing]) and saves the intent to seed it again after a restart once
 * it does, or once it completes while only paused uploads keep it from seeding
 * ([TorrentControlSource.takeDeferredSharing]). From [start] on, it seeds again the tasks meant to
 * seed: at once, and each time sharing comes back on ([TorrentControlSource.sharingResumes]).
 *
 * @param sourceType the [com.linroid.ketch.core.engine.DownloadSource.type] of [source]
 * @param handlesUrl whether [source] downloads a URL, for tasks that have not resolved yet
 */
internal class TorrentControls(
  private val source: TorrentControlSource,
  private val sourceType: String,
  handlesUrl: (String) -> Boolean,
  private val host: SourceHost,
) : SourceExtension {
  private val log = KetchLogger("TorrentControls")

  /**
   * How many Stop commands stopped each task's seeding. One counts only once its session
   * stopped, so a watcher that reads it before [TorrentControlSource.seedingTaskIds] and still
   * finds the task seeding saw seeding that started after every stop it counted.
   */
  private val stops = MutableStateFlow<Map<String, Long>>(emptyMap())

  /** The torrent controller of this source's tasks. */
  val controller: KetchTorrentController = KetchTorrentController(
    source = source,
    sourceType = sourceType,
    host = host,
    handlesUrl = handlesUrl,
    seed = { task ->
      host.scope.launch {
        val outcome = seed(task)
        if (outcome != SeedingOutcome.SEEDING) {
          log.i { "Did not seed taskId=${task.taskId}: $outcome" }
        }
      }
    },
    seedingStopped = { taskId ->
      stops.update { it + (taskId to (it[taskId] ?: 0) + 1) }
    },
  )

  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> controller(key: ControlKey<T>): T? =
    if (key === TorrentController.Key) controller as T else null

  @Suppress("UNCHECKED_CAST")
  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T>? =
    if (interest is TorrentInterest) controller.observe(interest.taskId) as Flow<T> else null

  override suspend fun start() {
    host.scope.launch { followSeeding() }
    host.scope.launch {
      // The first value restores seeding as Ketch starts; each later one, sharing coming back on,
      // walks the tasks meant to seed again.
      source.sharingResumes.collect { restoreSeeding() }
    }
  }

  override suspend fun taskCompleted(task: DownloadTask) {
    val seen = stops.value
    syncSeeding(task, source.seedingTaskIds.value, seen)
    if (source.takeDeferredSharing(task.taskId)) deferSharing(task, seen[task.taskId])
  }

  override suspend fun selectionChanged(task: DownloadTask, fileIds: Set<String>) {
    // A seeding session keeps running on the smaller selection rather than reopening.
    source.changeSeedingSelection(task.taskId, fileIds)
  }

  /** Shows on every completed task of the source whether it seeds, as the source reports it. */
  private suspend fun followSeeding() {
    source.seedingTaskIds.collect {
      // The stops first, then the tasks seeding now: see [stops].
      val seen = stops.value
      val ids = source.seedingTaskIds.value
      for (task in host.tasks.value) {
        if (host.record(task)?.sourceType == sourceType) syncSeeding(task, ids, seen)
      }
    }
  }

  /**
   * Publishes whether a completed task seeds, and saves the intent to seed it again after a
   * restart once it does. Nothing here clears the intent: a seeder that a download stopped, or
   * that stopped with Ketch, seeds again later.
   *
   * The save runs later, and not after a Stop command that [seen], the [stops] read before
   * [ids], did not count: the user's stop wins over seeding the watcher saw before it.
   */
  private suspend fun syncSeeding(task: DownloadTask, ids: Set<String>, seen: Map<String, Long>) {
    val taskId = task.taskId
    val seeding = taskId in ids
    host.showSharing(task, seeding)
    if (seeding && host.record(task)?.control?.sharing != true) {
      val stopped = seen[taskId]
      host.scope.launch {
        host.saveSharingIntent(task, true) { stops.value[taskId] == stopped }
        controller.controlChanged()
      }
    }
  }

  /**
   * Saves the intent to seed [task], which completed meant to seed under a setting that seeds,
   * so it seeds once uploads resume even when a pause finished or stopped its session first; not
   * after a Stop command that [stopped], the count of [stops] read before the completion was
   * taken, did not count, as in [syncSeeding]. Seeds it at once when uploads resumed before the
   * intent was saved, as their walk may have passed it; a task still seeding is left as it is.
   */
  private fun deferSharing(task: DownloadTask, stopped: Long?) {
    val taskId = task.taskId
    host.scope.launch {
      host.saveSharingIntent(task, true) { stops.value[taskId] == stopped }
      controller.controlChanged()
      if (task.state.value !is DownloadState.Completed ||
        host.record(task)?.control?.sharing != true
      ) return@launch
      log.d { "Saved the intent to seed taskId=$taskId" }
      if (source.seedingAvailability(taskId) == null) {
        val outcome = seed(task)
        if (outcome != SeedingOutcome.SEEDING) log.i { "Did not seed taskId=$taskId: $outcome" }
      }
    }
  }

  /**
   * Seeds the completed tasks meant to seed, oldest completion first, when the source restores
   * seeding: as Ketch starts, the tasks that seeded when it last stopped, and again each time
   * sharing comes back on. Each checks its files first and only takes a free slot. The walk stops
   * at the first that finds no slot, seeding switched off or uploads paused, keeping the intents.
   */
  private suspend fun restoreSeeding() {
    if (!source.restoresSeeding) return
    val candidates = host.tasks.value.mapNotNull { task ->
      host.record(task)?.takeIf {
        it.state == TaskState.COMPLETED && it.control?.sharing == true &&
          it.sourceType == sourceType
      }?.let { task to it }
    }.sortedBy { (_, record) -> record.completedAt }.map { it.first }
    if (candidates.isEmpty()) return
    log.i { "Restoring seeding of ${candidates.size} task(s) from $sourceType" }
    for (task in candidates) {
      val taskId = task.taskId
      val record = host.record(task) ?: continue
      // The task may have started again, stopped seeding for good, or seeded on its own since
      // the walk began.
      if (task.state.value !is DownloadState.Completed ||
        record.state != TaskState.COMPLETED || record.control?.sharing != true ||
        taskId in source.seedingTaskIds.value
      ) continue
      when (val outcome = seed(task)) {
        SeedingOutcome.SEEDING -> log.i { "Restored seeding of taskId=$taskId" }
        SeedingOutcome.NO_SLOT, SeedingOutcome.POLICY_OFF, SeedingOutcome.UPLOADS_PAUSED -> {
          log.i { "Stopped restoring seeding at taskId=$taskId: $outcome" }
          return
        }
        SeedingOutcome.CHANGED_ON_DISK -> {}
        SeedingOutcome.ALREADY_ACTIVE, SeedingOutcome.UNSUPPORTED, SeedingOutcome.FAILED ->
          log.i { "Did not restore seeding of taskId=$taskId: $outcome" }
        null -> {}
      }
    }
  }

  /**
   * Starts seeding [task] and waits until it seeds or stops trying. A task whose files changed on
   * disk loses its intent to seed. Returns `null` when the task has nothing saved to seed from.
   */
  private suspend fun seed(task: DownloadTask): SeedingOutcome? {
    val taskId = task.taskId
    val record = host.record(task)
    val sourceState = record?.sourceState
    val outputPath = record?.outputPath
    if (sourceState == null || outputPath == null) {
      log.w { "Cannot seed taskId=$taskId: nothing saved to seed from" }
      return null
    }
    val outcome = try {
      source.startSeeding(
        SeedingTask(taskId, record.request.url, sourceState, record.resolvedSource, outputPath,
          record.request.selectedFileIds, host.connections(task))
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Seeding taskId=$taskId failed: ${e.describeCauses()}" }
      SeedingOutcome.FAILED
    }
    if (outcome == SeedingOutcome.CHANGED_ON_DISK) {
      host.saveSharingIntent(task, false)
      controller.controlChanged()
      log.w { "Files of taskId=$taskId changed on disk since it completed; not seeding it" }
    }
    return outcome
  }
}
