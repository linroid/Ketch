package com.linroid.ketch.core.task

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Instant

/**
 * Internal bundle of mutable task state used by the engine layer.
 *
 * Implemented by [RealDownloadTask] so that scheduler, queue,
 * coordinator, and execution classes can accept a single object
 * instead of threading five separate parameters.
 */
internal interface TaskHandle {
  val taskId: String
  val request: DownloadRequest
  val createdAt: Instant
  val mutableState: MutableStateFlow<DownloadState>
  val mutableSegments: MutableStateFlow<List<Segment>>
  val record: AtomicSaver<TaskRecord>

  /** Written by [com.linroid.ketch.core.engine.DownloadQueue] only. */
  val mutableQueuePosition: MutableStateFlow<Int?>

  /**
   * Serializes the task's control changes: file selections, seeding and the per-task settings
   * (speed limit, priority, connections), and the execution's sections that read them: the
   * section after a fresh start resolved its source, the context build of a resume and the
   * completion check.
   *
   * Lock order: this lock, then the queue's and the coordinator's mutexes, then the record's
   * [AtomicSaver]. Nothing may take it in an execution's `finally`, its periodic save or its
   * final save: [DownloadTask.setPriority][com.linroid.ketch.api.DownloadTask.setPriority]
   * holds it while the queue can join a stopping execution of this task, which would never
   * finish. The execution's own waits for it are cancellable, and a stopping execution is
   * always cancelled. Nothing joins this task's execution while holding it.
   */
  val controlLock: Mutex
}
