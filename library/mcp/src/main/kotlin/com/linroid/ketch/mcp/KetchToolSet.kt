package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Koog [ToolSet] exposing [KetchApi] download management capabilities
 * as MCP tools for AI agents.
 *
 * Each `@Tool` method wraps a [KetchApi] or `DownloadTask` operation
 * and returns a JSON-encoded string.
 */
@LLMDescription(
  "Download manager tools for starting, managing, and monitoring file downloads",
)
class KetchToolSet(
  private val ketch: KetchApi,
  private val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  },
) : ToolSet {

  @Tool
  @LLMDescription(
    "List all download tasks with their current state and progress. " +
      "Returns JSON array of task snapshots.",
  )
  fun listDownloads(): String {
    val tasks = ketch.tasks.value
    return json.encodeToString(
      buildJsonArray {
        tasks.forEach { task -> add(taskToJson(task)) }
      },
    )
  }

  @Tool
  @LLMDescription(
    "Get details of a specific download task by its ID. " +
      "Returns JSON object with task state, progress, and segments.",
  )
  fun getDownload(
    @LLMDescription("The unique task ID")
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription(
    "Start a new download from a URL. Returns the created task snapshot.",
  )
  suspend fun startDownload(
    @LLMDescription("The URL to download from")
    url: String,
    @LLMDescription(
      "Where to save the file. Can be a directory path " +
        "(ending with /), a filename, or a full file path. " +
        "Omit to use the default directory.",
    )
    destination: String = "",
    @LLMDescription(
      "Number of concurrent connections (segments). " +
        "0 uses the default from config.",
    )
    connections: Int = 0,
    @LLMDescription(
      "Download priority: LOW, NORMAL, HIGH, or URGENT",
    )
    priority: String = "NORMAL",
    @LLMDescription(
      "Speed limit, e.g. '1m' for 1 MB/s, '500k' for 500 KB/s, " +
        "or 'unlimited'",
    )
    speedLimit: String = "unlimited",
  ): String {
    val request = DownloadRequest(
      url = url,
      destination = destination.ifEmpty { null }?.let { Destination(it) },
      connections = connections,
      priority = parsePriority(priority),
      speedLimit = parseSpeedLimit(speedLimit),
    )
    val task = ketch.download(request)
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription("Pause a running download. Preserves progress for later resume.")
  suspend fun pauseDownload(
    @LLMDescription("The unique task ID to pause")
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.pause()
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription("Resume a paused or failed download from where it left off.")
  suspend fun resumeDownload(
    @LLMDescription("The unique task ID to resume")
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.resume()
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription("Cancel a download. This is a terminal action and cannot be undone.")
  suspend fun cancelDownload(
    @LLMDescription("The unique task ID to cancel")
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.cancel()
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription(
    "Remove a download task from the task list. " +
      "Cancels the download if still active.",
  )
  suspend fun removeDownload(
    @LLMDescription("The unique task ID to remove")
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.remove()
    return buildJsonObject { put("removed", taskId) }.toString()
  }

  @Tool
  @LLMDescription(
    "Resolve URL metadata without downloading. Returns file size, " +
      "resume support, suggested filename, and source type.",
  )
  suspend fun resolveUrl(
    @LLMDescription("The URL to resolve")
    url: String,
  ): String {
    val resolved = ketch.resolve(url)
    return json.encodeToString(
      json.encodeToJsonElement(resolved),
    )
  }

  @Tool
  @LLMDescription(
    "Get server status including version, uptime, configuration, " +
      "and system information.",
  )
  suspend fun getStatus(): String {
    val status = ketch.status()
    return json.encodeToString(
      json.encodeToJsonElement(status),
    )
  }

  @Tool
  @LLMDescription("Set the speed limit for a specific download task.")
  suspend fun setSpeedLimit(
    @LLMDescription("The unique task ID")
    taskId: String,
    @LLMDescription(
      "Speed limit, e.g. '1m' for 1 MB/s, '500k' for 500 KB/s, " +
        "or 'unlimited' to remove the limit",
    )
    speedLimit: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.setSpeedLimit(parseSpeedLimit(speedLimit))
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription("Set the priority of a download task in the queue.")
  suspend fun setPriority(
    @LLMDescription("The unique task ID")
    taskId: String,
    @LLMDescription("Priority level: LOW, NORMAL, HIGH, or URGENT")
    priority: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.setPriority(parsePriority(priority))
    return json.encodeToString(taskToJson(task))
  }

  @Tool
  @LLMDescription(
    "Update global download configuration such as speed limit " +
      "and concurrency settings.",
  )
  suspend fun updateConfig(
    @LLMDescription(
      "Global speed limit, e.g. '10m' for 10 MB/s, " +
        "'unlimited' to remove. Empty string to keep current.",
    )
    speedLimit: String = "",
    @LLMDescription(
      "Maximum concurrent downloads. 0 to keep current.",
    )
    maxConcurrentDownloads: Int = 0,
    @LLMDescription(
      "Maximum connections per download. 0 to keep current.",
    )
    maxConnectionsPerDownload: Int = 0,
  ): String {
    val current = ketch.status().config
    val updated = current.copy(
      speedLimit = if (speedLimit.isEmpty()) {
        current.speedLimit
      } else {
        parseSpeedLimit(speedLimit)
      },
      maxConcurrentDownloads = if (maxConcurrentDownloads > 0) {
        maxConcurrentDownloads
      } else {
        current.maxConcurrentDownloads
      },
      maxConnectionsPerDownload = if (maxConnectionsPerDownload > 0) {
        maxConnectionsPerDownload
      } else {
        current.maxConnectionsPerDownload
      },
    )
    ketch.updateConfig(updated)
    return json.encodeToString(
      json.encodeToJsonElement(updated),
    )
  }

  private fun findTask(taskId: String) =
    ketch.tasks.value.find { it.taskId == taskId }

  private fun notFound(taskId: String): String =
    buildJsonObject {
      put("error", "not_found")
      put("message", "Task not found: $taskId")
    }.toString()

  private fun taskToJson(task: com.linroid.ketch.api.DownloadTask): JsonObject {
    val state = task.state.value
    return buildJsonObject {
      put("taskId", task.taskId)
      put("url", task.request.url)
      put("destination", task.request.destination?.value)
      put("state", stateName(state))
      put("createdAt", task.createdAt.toString())
      when (state) {
        is DownloadState.Downloading -> {
          val p = state.progress
          put("downloadedBytes", p.downloadedBytes)
          put("totalBytes", p.totalBytes)
          put("bytesPerSecond", p.bytesPerSecond)
          put("percent", p.percent.toDouble())
        }
        is DownloadState.Paused -> {
          val p = state.progress
          put("downloadedBytes", p.downloadedBytes)
          put("totalBytes", p.totalBytes)
          put("percent", p.percent.toDouble())
        }
        is DownloadState.Completed -> {
          put("outputPath", state.outputPath)
        }
        is DownloadState.Failed -> {
          put("error", state.error.message ?: "Unknown error")
        }
        else -> {}
      }
      if (task.request.priority != DownloadPriority.NORMAL) {
        put("priority", task.request.priority.name)
      }
      if (!task.request.speedLimit.isUnlimited) {
        put(
          "speedLimit",
          "${task.request.speedLimit.bytesPerSecond}",
        )
      }
    }
  }

  private fun stateName(state: DownloadState): String = when (state) {
    is DownloadState.Scheduled -> "scheduled"
    is DownloadState.Queued -> "queued"
    is DownloadState.Downloading -> "downloading"
    is DownloadState.Paused -> "paused"
    is DownloadState.Completed -> "completed"
    is DownloadState.Failed -> "failed"
    is DownloadState.Canceled -> "canceled"
  }

  private fun parsePriority(value: String): DownloadPriority =
    DownloadPriority.entries.find {
      it.name.equals(value, ignoreCase = true)
    } ?: DownloadPriority.NORMAL

  private fun parseSpeedLimit(value: String): SpeedLimit =
    if (value.equals("unlimited", ignoreCase = true) || value.isEmpty()) {
      SpeedLimit.Unlimited
    } else {
      SpeedLimit.parse(value)
        ?: throw IllegalArgumentException(
          "Invalid speed limit '$value'. " +
            "Use e.g. '1m' (1 MB/s), '500k' (500 KB/s), " +
            "a raw byte count, or 'unlimited'.",
        )
    }
}
