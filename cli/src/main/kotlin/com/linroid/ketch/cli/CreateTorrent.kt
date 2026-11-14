package com.linroid.ketch.cli

import com.linroid.ketch.torrent.CreatedTorrent
import com.linroid.ketch.torrent.TorrentCreateOptions
import com.linroid.ketch.torrent.TorrentCreator
import com.linroid.ketch.torrent.TorrentFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.PrintStream

/** Exit statuses of `ketch create`. */
internal object CreateExit {
  const val OK = 0

  /** The content could not be read or made into a torrent, or the file could not be written. */
  const val FAILED = 1

  /** The arguments cannot be used. */
  const val USAGE = 2
}

/** The arguments of `ketch create`. */
internal sealed interface CreateArgs {
  /** `--help` or `-h` was given. */
  data object Help : CreateArgs

  /** The arguments cannot be used; [message] says why. */
  data class Invalid(val message: String) : CreateArgs

  /**
   * @property path the file or folder to share
   * @property output where to save the `.torrent` file; `<name>.torrent` here when `null`
   * @property overwrite replace an existing file at the output
   * @property json print the torrent's details as JSON instead
   */
  data class Run(
    val path: String,
    val options: TorrentCreateOptions,
    val output: String? = null,
    val overwrite: Boolean = false,
    val json: Boolean = false,
  ) : CreateArgs
}

private val createValueOptions = setOf(
  "--output", "-o", "--format", "--tracker", "-t", "--web-seed", "--piece-length", "--name",
  "--comment", "--source",
)

/** Parses the arguments given after `ketch create`. */
internal fun parseCreateArgs(args: List<String>): CreateArgs {
  var options = TorrentCreateOptions()
  var output: String? = null
  var overwrite = false
  var json = false
  // Each --tracker is a tier of its own, tried in the order given.
  val trackers = mutableListOf<List<String>>()
  val webSeeds = mutableListOf<String>()
  val positional = mutableListOf<String>()
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--help" || arg == "-h" -> return CreateArgs.Help
      arg == "--" -> {
        positional += args.drop(i + 1)
        break
      }
      arg in createValueOptions -> {
        val value = args.getOrNull(++i) ?: return CreateArgs.Invalid("$arg requires a value")
        when (arg) {
          "--output", "-o" -> output = value
          "--format" -> options = options.copy(
            format = when (value.lowercase()) {
              "v1" -> TorrentFormat.V1
              "v2" -> TorrentFormat.V2
              "hybrid" -> TorrentFormat.HYBRID
              else -> return CreateArgs.Invalid(
                "invalid format '$value' (valid values: v1, v2, hybrid)"
              )
            }
          )
          "--tracker", "-t" -> trackers += listOf(value)
          "--web-seed" -> webSeeds += value
          "--piece-length" -> options = options.copy(
            pieceLength = parsePieceLength(value) ?: return CreateArgs.Invalid(
              "invalid piece length '$value' (a power of two from 16k to 16m, such as 256k)"
            )
          )
          "--name" -> options = options.copy(name = value)
          "--comment" -> options = options.copy(comment = value)
          "--source" -> options = options.copy(source = value)
        }
      }
      arg == "--private" -> options = options.copy(privateTorrent = true)
      arg == OVERWRITE_OPTION -> overwrite = true
      arg == "--json" -> json = true
      arg.length > 1 && arg.startsWith("-") -> return CreateArgs.Invalid("unknown option '$arg'")
      else -> positional += arg
    }
    i++
  }
  val path = positional.getOrNull(0) ?: return CreateArgs.Invalid("missing <path>")
  positional.getOrNull(1)?.let { return CreateArgs.Invalid("unexpected argument '$it'") }
  return CreateArgs.Run(
    path = path,
    options = options.copy(trackers = trackers, webSeeds = webSeeds),
    output = output,
    overwrite = overwrite,
    json = json,
  )
}

/** A piece length such as `16384`, `256k` or `4m` (binary units); `null` when it is none. */
internal fun parsePieceLength(value: String): Int? {
  val text = value.trim().lowercase()
  val (digits, unit) = when {
    text.endsWith("k") -> text.dropLast(1) to 1024L
    text.endsWith("m") -> text.dropLast(1) to 1024L * 1024
    else -> text to 1L
  }
  val bytes = (digits.toLongOrNull() ?: return null) * unit
  return bytes.takeIf { it in 16 * 1024..16 * 1024 * 1024 && it.countOneBits() == 1 }?.toInt()
}

/**
 * Runs `ketch create` with [args]: hashes the content, saves the `.torrent` file and prints its
 * path and magnet link to [out]. Everything else, progress on a terminal included, goes to [err].
 *
 * @param create builds the torrent; replaced in tests
 */
internal fun runCreate(
  args: List<String>,
  out: PrintStream = System.out,
  err: PrintStream = System.err,
  progress: Boolean = System.console() != null,
  create: suspend (
    path: String,
    options: TorrentCreateOptions,
    onProgress: (Long, Long) -> Unit,
  ) -> CreatedTorrent = TorrentCreator::create,
): Int {
  val run = when (val parsed = parseCreateArgs(args)) {
    CreateArgs.Help -> {
      printCreateUsage(out)
      return CreateExit.OK
    }
    is CreateArgs.Invalid -> {
      err.println("Error: ${parsed.message}")
      err.println()
      printCreateUsage(err)
      return CreateExit.USAGE
    }
    is CreateArgs.Run -> parsed
  }
  val source = File(run.path).absoluteFile
  if (!source.exists()) {
    err.println("Error: ${run.path} does not exist")
    return CreateExit.FAILED
  }
  var shown = -1
  val created = try {
    runBlocking {
      create(source.path, run.options) { done, total ->
        val percent = if (total > 0) (done * 100 / total).toInt() else 100
        if (progress && percent != shown) {
          shown = percent
          err.print("\rHashing $percent%")
          err.flush()
        }
      }
    }
  } catch (e: IllegalArgumentException) {
    if (shown >= 0) err.println()
    err.println("Error: ${e.message}")
    return CreateExit.FAILED
  } catch (e: IOException) {
    if (shown >= 0) err.println()
    err.println("Error: couldn't read ${run.path}: ${e.message}")
    return CreateExit.FAILED
  }
  if (shown >= 0) err.println()
  val target = File(run.output ?: "${created.name}.torrent").absoluteFile.let {
    if (it.isDirectory) File(it, "${created.name}.torrent") else it
  }
  if (target.exists() && !run.overwrite) {
    err.println("Error: ${target.path} already exists; pass $OVERWRITE_OPTION to replace it")
    return CreateExit.FAILED
  }
  try {
    target.parentFile?.mkdirs()
    target.writeBytes(created.metainfo)
  } catch (e: IOException) {
    err.println("Error: couldn't write ${target.path}: ${e.message}")
    return CreateExit.FAILED
  }
  if (run.json) {
    out.println(
      buildJsonObject {
        put("file", target.path)
        put("name", created.name)
        put("infoHash", created.infoHash)
        put("infoHashV2", created.infoHashV2)
        put("magnet", created.magnet)
        put("size", created.totalBytes)
        put("pieceLength", created.pieceLength)
        put("files", created.fileCount)
      }
    )
  } else {
    out.println(target.path)
    created.magnet?.let(out::println)
  }
  return CreateExit.OK
}

/** Prints the usage of `ketch create` to [out]. */
internal fun printCreateUsage(out: PrintStream) {
  out.println("Usage: ketch create [options] <path>")
  out.println()
  out.println("Make a .torrent file of a file or a folder and print where it")
  out.println("was saved, then its magnet link.")
  out.println()
  out.println("Options:")
  out.println("  -o, --output <file>      Where to save it (default: <name>.torrent")
  out.println("                           in the current directory)")
  out.println("  --format <format>        v1 (default; every client opens it),")
  out.println("                           v2 or hybrid")
  out.println("  -t, --tracker <url>      A tracker to announce to; repeatable,")
  out.println("                           tried in the order given")
  out.println("  --web-seed <url>         An http(s) address serving the content;")
  out.println("                           repeatable")
  out.println("  --private                Find peers through the trackers only")
  out.println("  --piece-length <size>    A power of two from 16k to 16m, such as")
  out.println("                           256k (default: about 1000 pieces)")
  out.println("  --name <name>            The torrent's name (default: the file")
  out.println("                           or folder's)")
  out.println("  --comment <text>         A comment to store in the file")
  out.println("  --source <tag>           A private tracker's source tag")
  out.println("  $OVERWRITE_OPTION              Replace an existing .torrent file")
  out.println("  --json                   Print the details as JSON instead")
  out.println("  -h, --help               Show this help message")
  out.println()
  out.println("Exit status: 0 on success, 1 on failure, 2 for invalid arguments.")
}
