package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.Tool
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.SpeedLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * [DownloadRequest.properties] key naming the client a task was added from. Ketch never reads
 * it; apps group and filter downloads by it.
 */
private const val ORIGIN_PROPERTY = "ketch.origin"

/** Value of [ORIGIN_PROPERTY] on every download an agent starts through [KetchToolSet]. */
private const val AGENT_ORIGIN = "agent"

/**
 * Download management tools for AI agents, served as MCP tools by [KetchMcpServer].
 *
 * Each method wraps a [KetchApi] or `DownloadTask` operation and returns a JSON-encoded
 * string; [tools] describes them to the agent.
 *
 * @param connect returns the instance each call works on. It is asked on every call, so it may
 *   connect when first needed, or again after losing the instance; what it throws fails the call
 *   with its message.
 */
class KetchToolSet(
  private val connect: suspend () -> KetchApi,
  private val json: Json = defaultJson,
) {
  /** Tools that always work on [ketch]. */
  constructor(ketch: KetchApi, json: Json = defaultJson) : this({ ketch }, json)

  /** The methods as tools, with the names, descriptions and parameters agents see. */
  internal fun tools(): List<Tool<*, *>> = listOf(
    TextTool(
      name = "listDownloads",
      description = "List all download tasks with their current state and progress. " +
        "Returns JSON array of task snapshots.",
      parameters = emptyList(),
    ) { listDownloads() },
    TextTool(
      name = "getDownload",
      description = "Get details of a specific download task by its ID. " +
        "Returns JSON object with task state, progress, and segments.",
      parameters = listOf(stringParameter("taskId", "The unique task ID")),
    ) { getDownload(string("taskId")) },
    TextTool(
      name = "startDownload",
      description = "Start a new download from a URL. Returns the created task snapshot.",
      parameters = listOf(
        stringParameter("url", "The URL to download from"),
        stringParameter(
          "destination",
          "Where to save the file. Can be a directory path (ending with /), a filename, " +
            "or a full file path. Omit to use the default directory.",
          required = false,
        ),
        integerParameter(
          "connections",
          "Number of concurrent connections (segments). 0 uses the default from config.",
          required = false,
        ),
        stringParameter(
          "priority",
          "Download priority: LOW, NORMAL, HIGH, or URGENT",
          required = false,
        ),
        stringParameter(
          "speedLimit",
          "Speed limit, e.g. '1m' for 1 MB/s, '500k' for 500 KB/s, or 'unlimited'",
          required = false,
        ),
        stringParameter(
          "headers",
          "HTTP request headers the site needs, such as Cookie or Referer, one " +
            "'Name: value' per line. Omit for none. Ketch sends its own User-Agent unless " +
            "one is given.",
          required = false,
        ),
        stringParameter(
          "requestId",
          "A UUID you make up for this download. Calling again with the same requestId and " +
            "arguments, e.g. after a timeout, returns the download it started instead of " +
            "adding another. Omit to add a download every time.",
          required = false,
        ),
      ),
    ) {
      startDownload(
        url = string("url"),
        destination = string("destination", ""),
        connections = int("connections", 0),
        priority = string("priority", "NORMAL"),
        speedLimit = string("speedLimit", "unlimited"),
        headers = string("headers", ""),
        requestId = string("requestId", ""),
      )
    },
    TextTool(
      name = "pauseDownload",
      description = "Pause a running download. Preserves progress for later resume.",
      parameters = listOf(stringParameter("taskId", "The unique task ID to pause")),
    ) { pauseDownload(string("taskId")) },
    TextTool(
      name = "resumeDownload",
      description = "Resume a paused or failed download from where it left off.",
      parameters = listOf(stringParameter("taskId", "The unique task ID to resume")),
    ) { resumeDownload(string("taskId")) },
    TextTool(
      name = "cancelDownload",
      description = "Cancel a download. This is a terminal action and cannot be undone.",
      parameters = listOf(stringParameter("taskId", "The unique task ID to cancel")),
    ) { cancelDownload(string("taskId")) },
    TextTool(
      name = "removeDownload",
      description = "Remove a download task from the task list. " +
        "Cancels the download if still active.",
      parameters = listOf(stringParameter("taskId", "The unique task ID to remove")),
    ) { removeDownload(string("taskId")) },
    TextTool(
      name = "resolveUrl",
      description = "Resolve URL metadata without downloading. Returns file size, " +
        "resume support, suggested filename, and source type.",
      parameters = listOf(stringParameter("url", "The URL to resolve")),
    ) { resolveUrl(string("url")) },
    TextTool(
      name = "getStatus",
      description = "Get server status including version, uptime, configuration, " +
        "and system information.",
      parameters = emptyList(),
    ) { getStatus() },
    TextTool(
      name = "setSpeedLimit",
      description = "Set the speed limit for a specific download task.",
      parameters = listOf(
        stringParameter("taskId", "The unique task ID"),
        stringParameter(
          "speedLimit",
          "Speed limit, e.g. '1m' for 1 MB/s, '500k' for 500 KB/s, or 'unlimited' to " +
            "remove the limit",
        ),
      ),
    ) { setSpeedLimit(string("taskId"), string("speedLimit")) },
    TextTool(
      name = "setPriority",
      description = "Set the priority of a download task in the queue.",
      parameters = listOf(
        stringParameter("taskId", "The unique task ID"),
        stringParameter("priority", "Priority level: LOW, NORMAL, HIGH, or URGENT"),
      ),
    ) { setPriority(string("taskId"), string("priority")) },
    TextTool(
      name = "updateConfig",
      description = "Update global download configuration such as speed limit " +
        "and concurrency settings.",
      parameters = listOf(
        stringParameter(
          "speedLimit",
          "Global speed limit, e.g. '10m' for 10 MB/s, 'unlimited' to remove. " +
            "Empty string to keep current.",
          required = false,
        ),
        integerParameter(
          "maxConcurrentDownloads",
          "Maximum concurrent downloads. 0 to keep current.",
          required = false,
        ),
        integerParameter(
          "maxConnectionsPerDownload",
          "Maximum connections per download. 0 to keep current.",
          required = false,
        ),
      ),
    ) {
      updateConfig(
        speedLimit = string("speedLimit", ""),
        maxConcurrentDownloads = int("maxConcurrentDownloads", 0),
        maxConnectionsPerDownload = int("maxConnectionsPerDownload", 0),
      )
    },
  )

  suspend fun listDownloads(): String {
    val tasks = connect().tasks.value
    return json.encodeToString(
      buildJsonArray {
        tasks.forEach { task -> add(taskToJson(task)) }
      },
    )
  }

  suspend fun getDownload(
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    return json.encodeToString(taskToJson(task))
  }

  suspend fun startDownload(
    url: String,
    destination: String = "",
    connections: Int = 0,
    priority: String = "NORMAL",
    speedLimit: String = "unlimited",
    headers: String = "",
    requestId: String = "",
  ): String {
    val request = DownloadRequest(
      url = url,
      destination = destination.ifEmpty { null }?.let { Destination(it) },
      connections = connections,
      priority = parsePriority(priority),
      speedLimit = parseSpeedLimit(speedLimit),
      headers = parseHeaders(headers),
      properties = mapOf(ORIGIN_PROPERTY to AGENT_ORIGIN),
      requestId = requestId.ifEmpty { null },
    )
    val task = connect().download(request)
    return json.encodeToString(taskToJson(task))
  }

  suspend fun pauseDownload(
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.pause()
    return json.encodeToString(taskToJson(task))
  }

  suspend fun resumeDownload(
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.resume()
    return json.encodeToString(taskToJson(task))
  }

  suspend fun cancelDownload(
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.cancel()
    return json.encodeToString(taskToJson(task))
  }

  suspend fun removeDownload(
    taskId: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.remove()
    return buildJsonObject { put("removed", taskId) }.toString()
  }

  suspend fun resolveUrl(
    url: String,
  ): String {
    val resolved = connect().resolve(url)
    return json.encodeToString(
      json.encodeToJsonElement(resolved),
    )
  }

  suspend fun getStatus(): String {
    val status = connect().status()
    return json.encodeToString(
      json.encodeToJsonElement(status),
    )
  }

  suspend fun setSpeedLimit(
    taskId: String,
    speedLimit: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.setSpeedLimit(parseSpeedLimit(speedLimit))
    return json.encodeToString(taskToJson(task))
  }

  suspend fun setPriority(
    taskId: String,
    priority: String,
  ): String {
    val task = findTask(taskId) ?: return notFound(taskId)
    task.setPriority(parsePriority(priority))
    return json.encodeToString(taskToJson(task))
  }

  suspend fun updateConfig(
    speedLimit: String = "",
    maxConcurrentDownloads: Int = 0,
    maxConnectionsPerDownload: Int = 0,
  ): String {
    val ketch = connect()
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

  private suspend fun findTask(taskId: String) =
    connect().tasks.value.find { it.taskId == taskId }

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
          put("pauseReason", pauseReasonName(state.reason))
          (state.reason as? PauseReason.Preempted)?.let { put("preemptedBy", it.byTaskId) }
        }
        is DownloadState.Completed -> {
          put("outputPath", state.outputPath)
          state.totalBytes?.let { put("totalBytes", it) }
          state.downloadTime?.let { put("downloadTimeMs", it.inWholeMilliseconds) }
          state.completedAt?.let { put("completedAt", it.toString()) }
        }
        is DownloadState.Failed -> {
          put("error", state.error.message ?: "Unknown error")
        }
        else -> {}
      }
      task.queuePosition.value?.let { put("queuePosition", it) }
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

  private fun pauseReasonName(reason: PauseReason): String = when (reason) {
    PauseReason.User -> "user"
    is PauseReason.Preempted -> "preempted"
    PauseReason.WaitingForCondition -> "waiting_for_condition"
    PauseReason.Shutdown -> "shutdown"
  }

  private fun parsePriority(value: String): DownloadPriority =
    DownloadPriority.entries.find {
      it.name.equals(value, ignoreCase = true)
    } ?: DownloadPriority.NORMAL

  /** Header lines as `Name: value`, one per line; blank lines are skipped. */
  private fun parseHeaders(value: String): Map<String, String> = buildMap {
    for (line in value.lines()) {
      if (line.isBlank()) continue
      val separator = line.indexOf(':')
      // The line is not quoted back: it may hold a credential.
      require(separator > 0) { "Invalid header line. Use 'Name: value', one per line." }
      put(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
    }
  }

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

private val defaultJson = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}
