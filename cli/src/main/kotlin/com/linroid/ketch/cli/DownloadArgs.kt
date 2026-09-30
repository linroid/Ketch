package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isName
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
    val maxConcurrent: Int = 3,
  ) : DownloadArgs
}

private val valueOptions = setOf("--speed-limit", "--priority", "--max-concurrent")

/** Parses [args], which no longer contain the global flags. */
internal fun parseDownloadArgs(args: List<String>): DownloadArgs {
  var url: String? = null
  var destination: String? = null
  var speedLimit = SpeedLimit.Unlimited
  var priority = DownloadPriority.NORMAL
  var maxConcurrent = 3

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
  return DownloadArgs.Download(
    url = url,
    destination = destination,
    speedLimit = speedLimit,
    priority = priority,
    maxConcurrent = maxConcurrent,
  )
}

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
