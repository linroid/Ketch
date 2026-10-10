package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.PauseReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

/** How many characters of a task ID the table shows; any unique prefix names a task. */
internal const val SHORT_ID_LENGTH = 8

/**
 * [task] as the JSON object `list --json`, `add --json` and `watch` print, with the field names
 * `ketch mcp`'s tools use. Request headers are left out, as they can hold credentials.
 */
internal fun taskJson(task: DownloadTask): JsonObject = buildJsonObject { putTask(task) }

/** Adds [task]'s fields, as [taskJson] has them, to this object. */
internal fun JsonObjectBuilder.putTask(task: DownloadTask) {
  val state = task.state.value
  val request = task.request
  put("taskId", task.taskId)
  put("name", displayName(task))
  put("url", request.url)
  put("destination", request.destination?.value)
  put("state", stateName(state))
  put("createdAt", task.createdAt.toString())
  when (state) {
    is DownloadState.Downloading -> {
      putProgress(state.progress)
      put("bytesPerSecond", state.progress.bytesPerSecond)
    }
    is DownloadState.Paused -> {
      putProgress(state.progress)
      put("pauseReason", pauseReasonName(state.reason))
      (state.reason as? PauseReason.Preempted)?.let { put("preemptedBy", it.byTaskId) }
    }
    is DownloadState.Completed -> {
      put("outputPath", state.outputPath)
      state.totalBytes?.let { put("totalBytes", it) }
      state.downloadTime?.let { put("downloadTimeMs", it.inWholeMilliseconds) }
      state.completedAt?.let { put("completedAt", it.toString()) }
    }
    is DownloadState.Failed -> put("error", state.error.message ?: "Unknown error")
    else -> Unit
  }
  task.queuePosition.value?.let { put("queuePosition", it) }
  if (request.priority != DownloadPriority.NORMAL) put("priority", request.priority.name)
  if (!request.speedLimit.isUnlimited) put("speedLimit", request.speedLimit.bytesPerSecond)
  request.requestId?.let { put("requestId", it) }
}

private fun JsonObjectBuilder.putProgress(progress: DownloadProgress) {
  put("downloadedBytes", progress.downloadedBytes)
  put("totalBytes", progress.totalBytes)
  put("percent", progress.percent.toDouble())
}

/** The name of [state] in JSON output, as `ketch mcp` names it. */
internal fun stateName(state: DownloadState): String = when (state) {
  is DownloadState.Scheduled -> "scheduled"
  is DownloadState.Queued -> "queued"
  is DownloadState.Downloading -> "downloading"
  is DownloadState.Paused -> "paused"
  is DownloadState.Completed -> "completed"
  is DownloadState.Failed -> "failed"
  is DownloadState.Canceled -> "canceled"
}

private fun pauseReasonName(reason: PauseReason): String = when (reason) {
  PauseReason.User -> "user"
  is PauseReason.Preempted -> "preempted"
  PauseReason.WaitingForCondition -> "waiting_for_condition"
  PauseReason.Shutdown -> "shutdown"
  PauseReason.AwaitingFileSelection -> "awaiting_file_selection"
}

/**
 * The tasks as a table for people, oldest first: a short ID, the state, how much is done, the
 * size, the speed and the name. Prints "No downloads." when there are none.
 */
internal fun formatTaskTable(tasks: List<DownloadTask>): String {
  if (tasks.isEmpty()) return "No downloads."
  val header = listOf("ID", "STATE", "DONE", "SIZE", "SPEED", "NAME")
  val rows = tasks.sortedBy { it.createdAt }.map { task ->
    val state = task.state.value
    val progress = (state as? DownloadState.Downloading)?.progress
      ?: (state as? DownloadState.Paused)?.progress
    val total = (state as? DownloadState.Completed)?.totalBytes
      ?: progress?.totalBytes?.takeIf { it > 0 }
    listOf(
      task.taskId.take(SHORT_ID_LENGTH),
      stateLabel(task),
      when {
        state is DownloadState.Completed -> "100%"
        progress != null && progress.totalBytes > 0 -> "${(progress.percent * 100).toInt()}%"
        progress != null && progress.downloadedBytes > 0 -> formatBytes(progress.downloadedBytes)
        else -> "-"
      },
      total?.let(::formatBytes) ?: "-",
      (state as? DownloadState.Downloading)?.progress?.bytesPerSecond?.takeIf { it > 0 }
        ?.let { "${formatBytes(it)}/s" } ?: "-",
      displayName(task).printable(),
    )
  }
  val widths = header.indices.map { column ->
    (rows.map { it[column] } + header[column]).maxOf { it.length }
  }
  return (listOf(header) + rows).joinToString("\n") { row ->
    row.mapIndexed { column, cell ->
      if (column == row.lastIndex) cell else cell.padEnd(widths[column])
    }.joinToString("  ")
  }
}

/** [task]'s state for people, saying why a paused task waits and where a queued one is. */
internal fun stateLabel(task: DownloadTask): String = when (val state = task.state.value) {
  is DownloadState.Queued -> task.queuePosition.value?.let { "queued #$it" } ?: "queued"
  is DownloadState.Paused -> when (state.reason) {
    is PauseReason.Preempted, PauseReason.WaitingForCondition -> "waiting"
    PauseReason.User, PauseReason.Shutdown -> "paused"
    PauseReason.AwaitingFileSelection -> "choose files"
  }
  else -> stateName(state)
}

/**
 * The name of [task]'s file: the file it saved, the file its destination names, the name the
 * source suggested, or the last part of its URL (a magnet link's display name).
 */
internal fun displayName(task: DownloadTask): String {
  (task.state.value as? DownloadState.Completed)?.let { return fileName(it.outputPath) }
  task.outputPath?.let { return fileName(it) }
  task.request.destination?.value
    ?.takeUnless { it.endsWith('/') || it.endsWith('\\') }
    ?.let { return fileName(it) }
  task.request.resolvedSource?.suggestedFileName?.takeIf { it.isNotBlank() }?.let { return it }
  return urlName(task.request.url)
}

private fun fileName(path: String): String =
  path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

private fun urlName(url: String): String {
  val uri = runCatching { URI(url) }.getOrNull() ?: return url
  if (uri.scheme.equals("magnet", ignoreCase = true)) {
    val name = uri.rawSchemeSpecificPart.substringAfter('?').split('&')
      .firstOrNull { it.startsWith("dn=") }?.removePrefix("dn=")
    // Form encoded, so a + is a space.
    return name?.let(::decode) ?: url
  }
  val segment = uri.rawPath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
  // In a path a + is itself.
  return segment?.let { decode(it.replace("+", "%2B")) } ?: uri.host ?: url
}

private fun decode(text: String): String =
  runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)
