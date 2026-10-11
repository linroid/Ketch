package com.linroid.ketch.api

import kotlin.time.Duration

/**
 * Tasks a caller shows, and how fresh it needs their progress and segments: what
 * [KetchApi.watch] asks of an instance. An in-process instance updates every task as it
 * changes; a remote one sends only what some caller asks for, the shortest interval winning.
 *
 * @property taskIds the tasks, or `null` for every task
 * @property progressInterval how often a downloading task's progress should update, or `null`
 *   for no more than other callers ask
 * @property segmentsInterval how often the tasks' segments should update, or `null` for no more
 *   than other callers ask
 */
data class TaskInterest(
  val taskIds: Set<String>? = null,
  val progressInterval: Duration? = null,
  val segmentsInterval: Duration? = null,
) {
  init {
    require(progressInterval == null || progressInterval.isPositive()) {
      "progressInterval must be positive"
    }
    require(segmentsInterval == null || segmentsInterval.isPositive()) {
      "segmentsInterval must be positive"
    }
  }
}
