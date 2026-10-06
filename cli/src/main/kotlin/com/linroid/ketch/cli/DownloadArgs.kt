package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isName
import com.linroid.ketch.core.engine.RequestHeaders
import java.io.File

/** Arguments of the single-file download command, `ketch [options] <url> [destination]`. */
internal sealed interface DownloadArgs {
  /** `--help` or `-h` was given. */
  data object Help : DownloadArgs

  /** The arguments cannot be used; [message] says why. */
  data class Invalid(val message: String) : DownloadArgs

  data class Download(
    val url: String,
    val destination: String?,
    val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val maxConcurrent: Int = DownloadConfig.Default.maxConcurrentDownloads,
    val headers: Map<String, String> = emptyMap(),
  ) : DownloadArgs
}

private val valueOptions = setOf(
  "--speed-limit", "--priority", "--max-concurrent", "-H", "--header", "--user-agent", "--referer",
)

/**
 * [DownloadRequest.properties] key naming the client a task was added from. Ketch never reads
 * it; apps group and filter downloads by it.
 */
private const val ORIGIN_PROPERTY = "ketch.origin"

/** Value of [ORIGIN_PROPERTY] on downloads started from the command line. */
private const val CLI_ORIGIN = "cli"

/** Parses [args], which no longer contain the global flags. */
internal fun parseDownloadArgs(args: List<String>): DownloadArgs {
  var url: String? = null
  var destination: String? = null
  var speedLimit = SpeedLimit.Unlimited
  var priority = DownloadPriority.NORMAL
  var maxConcurrent = DownloadConfig.Default.maxConcurrentDownloads
  val headers = LinkedHashMap<String, String>()
  // Header names ignore case, so a later option replaces an earlier one however it is spelled.
  fun setHeader(name: String, value: String) {
    headers.keys.removeAll { it.equals(name, ignoreCase = true) }
    headers[name] = value
  }

  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--help" || arg == "-h" -> return DownloadArgs.Help
      arg in valueOptions -> {
        val value = args.getOrNull(i + 1)
          ?: return DownloadArgs.Invalid("$arg requires a value")
        when (arg) {
          "--speed-limit" -> speedLimit = SpeedLimit.parse(value)
            ?: return DownloadArgs.Invalid("invalid speed limit '$value'")
          "--priority" -> priority = parsePriority(value)
            ?: return DownloadArgs.Invalid(
              "invalid priority '$value' (valid values: low, normal, high, urgent)"
            )
          "--max-concurrent" -> {
            maxConcurrent = value.toIntOrNull()
              ?: return DownloadArgs.Invalid("invalid number '$value'")
            if (maxConcurrent <= 0) {
              return DownloadArgs.Invalid("--max-concurrent must be > 0")
            }
          }
          "-H", "--header" -> {
            val separator = value.indexOf(':')
            if (separator <= 0) return DownloadArgs.Invalid("$arg expects 'Name: value'")
            setHeader(value.substring(0, separator).trim(), value.substring(separator + 1).trim())
          }
          "--user-agent" -> setHeader("User-Agent", value)
          "--referer" -> setHeader("Referer", value)
        }
        i++
      }
      arg.length > 1 && arg.startsWith("-") -> return DownloadArgs.Invalid("unknown option '$arg'")
      url == null -> url = arg
      destination == null -> destination = arg
      else -> return DownloadArgs.Invalid("unexpected argument '$arg'")
    }
    i++
  }

  if (url == null) return DownloadArgs.Invalid("missing <url>")
  try {
    RequestHeaders.requireValid(headers)
  } catch (e: IllegalArgumentException) {
    return DownloadArgs.Invalid(e.message ?: "invalid header")
  }
  return DownloadArgs.Download(
    url = url,
    destination = destination,
    speedLimit = speedLimit,
    priority = priority,
    maxConcurrent = maxConcurrent,
    headers = headers,
  )
}

/** The request that downloads these arguments' URL to [destination], tagged as a CLI download. */
internal fun DownloadArgs.Download.toRequest(destination: Destination): DownloadRequest =
  DownloadRequest(
    url = url,
    destination = destination,
    speedLimit = speedLimit,
    priority = priority,
    headers = headers,
    properties = mapOf(ORIGIN_PROPERTY to CLI_ORIGIN),
  )

/**
 * Resolves the destination argument against the current directory, like other command-line
 * tools do. The engine resolves a bare file name such as `file.zip` against the default
 * download directory instead, so it gets a `./` prefix. No destination saves the server's
 * file name in the current directory.
 */
internal fun resolveDestination(
  destination: String?,
  isDirectory: (String) -> Boolean = { File(it).isDirectory },
): Destination {
  if (destination == null) return Destination("./")
  val hasTrailingSeparator = destination.endsWith('/') || destination.endsWith('\\')
  return when {
    // A trailing separator marks a Destination as a directory
    !hasTrailingSeparator && isDirectory(destination) ->
      Destination(destination + File.separatorChar)
    Destination(destination).isName() -> Destination("./$destination")
    else -> Destination(destination)
  }
}

internal fun parsePriority(value: String): DownloadPriority? {
  return when (value.trim().lowercase()) {
    "low" -> DownloadPriority.LOW
    "normal" -> DownloadPriority.NORMAL
    "high" -> DownloadPriority.HIGH
    "urgent" -> DownloadPriority.URGENT
    else -> null
  }
}
