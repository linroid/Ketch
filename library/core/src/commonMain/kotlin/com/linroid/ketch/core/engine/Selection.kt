package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment

/**
 * The task's file selection as a running download sees it through
 * [DownloadContext.selection].
 *
 * @property fileIds the chosen file IDs; empty for a task that downloads every file
 * @property revision counts the selections delivered to one execution; it starts at 0 and only
 *   grows
 */
class SelectionUpdate(
  val fileIds: Set<String>,
  val revision: Int,
)

/**
 * What [DownloadSource.planSelection] validates and sizes.
 *
 * @property fileIds files to choose, never empty; `null` chooses every file
 * @property current the saved selection; empty for tasks that download every file
 * @property resumeState the task's saved [SourceResumeState], if any
 * @property resolved the task's [com.linroid.ketch.api.DownloadRequest.resolvedSource]: supplied
 *   by the client and possibly without its bulky metadata, so never trusted for sizes
 * @property segments the task's saved segments, if any
 * @property outputPath where the task saves, once it started
 * @property completed whether the task has completed
 */
class SelectionRequest(
  val taskId: String,
  val fileIds: Set<String>?,
  val current: Set<String>,
  val resumeState: SourceResumeState?,
  val resolved: ResolvedSource?,
  val segments: List<Segment>?,
  val outputPath: String?,
  val completed: Boolean,
)

/**
 * A validated selection, sized from metadata the source holds.
 *
 * @property fileIds the chosen files, explicit and never empty
 * @property totalBytes their size, from the source's own metadata, never client sizes
 * @property changed whether it differs from the selection the task runs with now
 * @property expands whether it chooses a file that selection lacked
 * @property segments the new segment layout with the progress carried over, or `null` to keep
 *   the task's segments
 */
class SelectionPlan(
  val fileIds: Set<String>,
  val totalBytes: Long,
  val changed: Boolean,
  val expands: Boolean,
  val segments: List<Segment>?,
)
