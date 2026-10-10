package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.sortedByFileOrder
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
    /** `--proxy` or `--no-proxy`, in place of the configured proxy; `null` for that one. */
    val proxy: ProxyConfig? = null,
    /** `--files`: the IDs of the files of a torrent to download; empty for every file. */
    val fileIds: Set<String> = emptySet(),
    /** `--list-files`: list the files of the URL instead of downloading it. */
    val listFiles: Boolean = false,
    /** `--sort` of `--list-files`. */
    val fileOrder: TorrentFileOrder = TorrentFileOrder.TORRENT,
    /** `--reverse` of `--list-files`. */
    val reverse: Boolean = false,
  ) : DownloadArgs
}

/**
 * [DownloadRequest.properties] key naming the client a task was added from. Ketch never reads
 * it; apps group and filter downloads by it.
 */
internal const val ORIGIN_PROPERTY = "ketch.origin"

/** Value of [ORIGIN_PROPERTY] on downloads started from the command line. */
internal const val CLI_ORIGIN = "cli"

/** Options that set a request header: `-H 'Name: value'`, `--user-agent` and `--referer`. */
internal val HEADER_OPTIONS = setOf("-H", "--header", "--user-agent", "--referer")

/** The request headers [HEADER_OPTIONS] give, in the order given. */
internal class HeaderOptions {
  private val headers = LinkedHashMap<String, String>()

  /**
   * Applies [option], one of [HEADER_OPTIONS], with its [value], returning why it cannot be used
   * or `null`. Header names ignore case, so a later option replaces an earlier one however it is
   * spelled.
   */
  fun apply(option: String, value: String): String? {
    when (option) {
      "-H", "--header" -> {
        val separator = value.indexOf(':')
        if (separator <= 0) return "$option expects 'Name: value'"
        set(value.substring(0, separator).trim(), value.substring(separator + 1).trim())
      }
      "--user-agent" -> set("User-Agent", value)
      "--referer" -> set("Referer", value)
      else -> return "unknown option '$option'"
    }
    return null
  }

  /** The headers, or why one of them cannot be sent. */
  fun build(): Result<Map<String, String>> = try {
    RequestHeaders.requireValid(headers)
    Result.success(headers.toMap())
  } catch (e: IllegalArgumentException) {
    Result.failure(e)
  }

  private fun set(name: String, value: String) {
    headers.keys.removeAll { it.equals(name, ignoreCase = true) }
    headers[name] = value
  }
}

/** Options that choose a download's proxy with a value: `--proxy` and `--proxy-bypass`. */
internal val PROXY_OPTIONS = setOf("--proxy", "--proxy-bypass")

/** The flag that downloads without a proxy. */
internal const val NO_PROXY_OPTION = "--no-proxy"

/**
 * The proxy [PROXY_OPTIONS] and [NO_PROXY_OPTION] give a download, in place of the configured
 * one.
 */
internal class ProxyOptions {
  private var url: String? = null
  private var bypass: List<String>? = null
  private var direct = false

  /** Notes [NO_PROXY_OPTION]. */
  fun noProxy() {
    direct = true
  }

  /** Applies [option], one of [PROXY_OPTIONS], with its [value], returning why it cannot be used. */
  fun apply(option: String, value: String): String? {
    when (option) {
      "--proxy" -> url = value
      "--proxy-bypass" -> {
        val entries = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        entries.firstOrNull { !ProxyConfig.isValidBypass(it) }?.let {
          return "invalid host '$it' in --proxy-bypass"
        }
        bypass = entries
      }
      else -> return "unknown option '$option'"
    }
    return null
  }

  /** The proxy, `null` to keep the configured one, or why the options cannot be used together. */
  fun build(): Result<ProxyConfig?> {
    val proxyUrl = url
    val problem = when {
      direct && proxyUrl != null -> "--proxy and --no-proxy cannot be used together"
      bypass != null && proxyUrl == null -> "--proxy-bypass requires --proxy"
      else -> null
    }
    if (problem != null) return Result.failure(IllegalArgumentException(problem))
    if (direct) return Result.success(ProxyConfig.Direct)
    if (proxyUrl == null) return Result.success(null)
    return try {
      Result.success(ProxyConfig.manual(proxyUrl, bypass.orEmpty()))
    } catch (_: IllegalArgumentException) {
      val message = "invalid proxy '$proxyUrl' (expected http://host:port or socks5://host:port)"
      Result.failure(IllegalArgumentException(message))
    }
  }
}

private val valueOptions =
  setOf("--speed-limit", "--priority", "--max-concurrent", "--files", "--sort") +
    HEADER_OPTIONS + PROXY_OPTIONS

/** The orders `--sort` names: `type` sorts by file extension. */
private val FILE_ORDERS = mapOf(
  "name" to TorrentFileOrder.NAME,
  "size" to TorrentFileOrder.SIZE,
  "type" to TorrentFileOrder.EXTENSION,
)

/** The file IDs of a `--files` [value], or `null` when it is not comma-separated IDs. */
private fun parseFileIds(value: String): Set<String>? {
  val ids = value.split(',').map { it.trim() }
  if (ids.any { it.isEmpty() || it.length > DownloadTask.MAX_FILE_ID_LENGTH }) return null
  return ids.toCollection(LinkedHashSet())
}

/** Parses [args], which no longer contain the global flags. */
internal fun parseDownloadArgs(args: List<String>): DownloadArgs {
  var url: String? = null
  var destination: String? = null
  var speedLimit = SpeedLimit.Unlimited
  var priority = DownloadPriority.NORMAL
  var maxConcurrent = DownloadConfig.Default.maxConcurrentDownloads
  val headers = HeaderOptions()
  val proxy = ProxyOptions()
  var fileIds = emptySet<String>()
  var listFiles = false
  var fileOrder: TorrentFileOrder? = null
  var reverse = false

  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--help" || arg == "-h" -> return DownloadArgs.Help
      arg == NO_PROXY_OPTION -> proxy.noProxy()
      arg == "--list-files" -> listFiles = true
      arg == "--reverse" -> reverse = true
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
          "--files" -> fileIds = parseFileIds(value)
            ?: return DownloadArgs.Invalid(
              "--files expects comma-separated file IDs, such as 0,2,5"
            )
          "--sort" -> fileOrder = FILE_ORDERS[value.trim().lowercase()]
            ?: return DownloadArgs.Invalid("invalid sort '$value' (valid values: name, size, type)")
          in PROXY_OPTIONS -> proxy.apply(arg, value)?.let { return DownloadArgs.Invalid(it) }
          else -> headers.apply(arg, value)?.let { return DownloadArgs.Invalid(it) }
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
  if (listFiles && fileIds.isNotEmpty()) {
    return DownloadArgs.Invalid("--list-files and --files cannot be used together")
  }
  if (!listFiles && (fileOrder != null || reverse)) {
    return DownloadArgs.Invalid("--sort and --reverse require --list-files")
  }
  return DownloadArgs.Download(
    url = url,
    destination = destination,
    speedLimit = speedLimit,
    priority = priority,
    maxConcurrent = maxConcurrent,
    headers = headers.build().getOrElse {
      return DownloadArgs.Invalid(it.message ?: "invalid header")
    },
    proxy = proxy.build().getOrElse {
      return DownloadArgs.Invalid(it.message ?: "invalid proxy")
    },
    fileIds = fileIds,
    listFiles = listFiles,
    fileOrder = fileOrder ?: TorrentFileOrder.TORRENT,
    reverse = reverse,
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
    proxy = proxy,
    selectedFileIds = fileIds,
  )

/**
 * The rows `--list-files` prints for [source]: a header, then one `ID  SIZE  PATH` row per file,
 * sorted by [order] and reversed when [reverse]. Empty when the source has no files to choose.
 */
internal fun fileListRows(
  source: ResolvedSource,
  order: TorrentFileOrder = TorrentFileOrder.TORRENT,
  reverse: Boolean = false,
): List<String> {
  if (source.files.isEmpty()) return emptyList()
  val files = source.files.sortedByFileOrder(
    order = order,
    descending = reverse,
    path = { it.metadata["path"] ?: it.name },
    size = { it.size },
    selected = { true },
  )
  val rows = files.map { file ->
    Triple(
      file.id,
      if (file.size >= 0) formatBytes(file.size) else "-",
      // Paths come from the torrent: printed as one line of plain text.
      (file.metadata["path"] ?: file.name).printable(),
    )
  }
  val idWidth = maxOf("ID".length, rows.maxOf { it.first.length })
  val sizeWidth = maxOf("SIZE".length, rows.maxOf { it.second.length })
  return listOf(Triple("ID", "SIZE", "PATH")).plus(rows).map { (id, size, path) ->
    "${id.padEnd(idWidth)}  ${size.padStart(sizeWidth)}  $path"
  }
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
