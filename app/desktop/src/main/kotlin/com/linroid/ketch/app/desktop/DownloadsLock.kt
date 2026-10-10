package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.config.RemoteConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Keeps one engine on the downloads of a config directory: the process running them holds its
 * [LOCK_FILE] while its engine has `ketch.db` open, so no second engine resumes the same
 * downloads into the same files. This app holds it from before it opens the database until it
 * quits. `ketch server` and `ketch mcp --standalone` hold it too, and describe themselves in the
 * owner-only [INFO_FILE], which tells this app how to reach their engine instead. The command
 * line keeps both files (`CliInstance` in `cli`), so keep the two in step.
 */
internal object DownloadsLock {
  const val LOCK_FILE = "instance.lock"
  const val INFO_FILE = "instance.json"

  private val log = KetchLogger("DownloadsLock")

  /**
   * Makes this app the process that runs the downloads of the config directory [dir], unless
   * another one does.
   *
   * A command that has just taken the lock describes itself once its API listens, so while the
   * holder has not said where, this waits up to [describeTimeout] for it.
   *
   * @param isAlive whether a process with this ID runs; an info file left by one that stopped
   *   describes no one.
   */
  fun claim(
    dir: File,
    isAlive: (Long) -> Boolean = ::isProcessAlive,
    describeTimeout: Duration = 3.seconds,
    retryInterval: Duration = 200.milliseconds,
  ): DownloadsClaim {
    val channel = try {
      dir.mkdirs()
      FileChannel.open(
        File(dir, LOCK_FILE).toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
      )
    } catch (e: IOException) {
      return unlocked(e)
    }
    val lock = try {
      channel.tryLock()
    } catch (_: OverlappingFileLockException) {
      // Held by this JVM already, which only happens in tests.
      null
    } catch (e: IOException) {
      channel.close()
      return unlocked(e)
    }
    if (lock != null) {
      // Only the holder writes the info file, so one found now was left by a process that
      // stopped, and would send `ketch` commands to it.
      File(dir, INFO_FILE).delete()
      return DownloadsClaim.Held(channel)
    }
    channel.close()
    val start = TimeSource.Monotonic.markNow()
    while (true) {
      val engine = read(dir)?.takeIf { isAlive(it.pid) }
      if (engine?.url != null || start.elapsedNow() >= describeTimeout) {
        log.i { "${engine?.describe() ?: "Another process"} runs the downloads in $dir" }
        return DownloadsClaim.Taken(engine)
      }
      Thread.sleep(retryInterval.inWholeMilliseconds)
    }
  }

  /**
   * What the `ketch` command running the downloads of [dir] says about itself, or `null` when
   * the info file is missing or cannot be read.
   */
  fun read(dir: File): CliEngine? {
    val text = try {
      File(dir, INFO_FILE).readText()
    } catch (_: IOException) {
      return null
    }
    val json = try {
      Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
      null
    } ?: return null
    return CliEngine(
      command = json["command"]?.jsonPrimitive?.contentOrNull ?: return null,
      pid = json["pid"]?.jsonPrimitive?.longOrNull ?: return null,
      url = json["url"]?.jsonPrimitive?.contentOrNull,
      token = json["token"]?.jsonPrimitive?.contentOrNull,
    )
  }

  // Storage the app cannot lock never stops it from starting; it runs the downloads as before.
  private fun unlocked(e: IOException): DownloadsClaim {
    log.w {
      "Couldn't lock $LOCK_FILE, so a `ketch server` could run the downloads too: " +
        e.describeCauses()
    }
    return DownloadsClaim.Held(null)
  }
}

/** Who runs the downloads of the app's config directory, as [DownloadsLock.claim] found. */
internal sealed interface DownloadsClaim {
  /** This app runs them, holding the lock until it closes this. */
  class Held(private val channel: FileChannel?) : DownloadsClaim, AutoCloseable {
    override fun close() {
      // Closing the channel releases the lock.
      channel?.close()
    }
  }

  /**
   * Another process runs them: [engine] when it is a `ketch` command, else `null`, such as for
   * a command that has not described itself or a second copy of this app.
   */
  data class Taken(val engine: CliEngine?) : DownloadsClaim
}

/**
 * A `ketch server` or `ketch mcp --standalone` running the downloads of the app's config
 * directory, as its [DownloadsLock.INFO_FILE] describes it.
 *
 * @property command the command, such as `ketch server`
 * @property pid its process ID
 * @property url where its API listens on this machine, once it does
 * @property token the access token its API requires, if any
 */
internal data class CliEngine(
  val command: String,
  val pid: Long,
  val url: String? = null,
  val token: String? = null,
) {
  /**
   * The device the app shows the downloads through: the command's API, named after [command].
   * `null` unless [url] is an `http` address of this machine, the only place the app sends
   * [token]: a loopback address, or an address of one of its network interfaces for a server
   * listening on one alone.
   */
  fun device(): RemoteConfig? {
    val uri = try {
      URI(url ?: return null)
    } catch (_: URISyntaxException) {
      return null
    }
    val host = uri.host ?: return null
    if (uri.scheme?.lowercase() != "http" || uri.port !in 1..65535 || !isThisMachine(host)) {
      return null
    }
    return RemoteConfig(host = host, port = uri.port, apiToken = token, name = command)
  }

  /** How logs name it, such as "`ketch server` (process 4242)". */
  fun describe(): String = "`$command` (process $pid)"
}

/** Whether [host] is an address of this machine, without looking a name up. */
private fun isThisMachine(host: String): Boolean {
  val name = host.removePrefix("[").removeSuffix("]").lowercase()
  if (name == "localhost") return true
  // Only IPv4 and IPv6 literals, which InetAddress parses without a DNS lookup.
  val literal = ':' in name || name.isNotEmpty() && name.all { it.isDigit() || it == '.' }
  if (!literal) return false
  return try {
    val address = InetAddress.getByName(name)
    address.isLoopbackAddress || NetworkInterface.getByInetAddress(address) != null
  } catch (_: UnknownHostException) {
    false
  } catch (_: SocketException) {
    false
  }
}

private fun isProcessAlive(pid: Long): Boolean =
  ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
