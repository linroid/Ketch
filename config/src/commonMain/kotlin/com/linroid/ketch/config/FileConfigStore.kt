package com.linroid.ketch.config

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * File-based [ConfigStore] that persists config as TOML.
 *
 * Uses a write-to-tmp-then-rename strategy for crash safety:
 * new content is written to `<path>.tmp`, then atomically
 * moved to [path]. On load, a leftover `.tmp` file from an
 * interrupted save is recovered or cleaned up.
 *
 * A leading `~` in the download directory expands to the user's
 * home directory on the JVM, where the file is edited by hand.
 *
 * A file that is not valid TOML, or holds a value no setting takes, makes [load] throw unless
 * [onUnreadable] is set. Then [load] moves it aside to `<path>.broken-<time>`, keeping it for
 * the user, reports it and returns the defaults, which the next [save] writes in its place.
 * [load] still throws when the file cannot be read or moved.
 *
 * @param onUnreadable hears of a file [load] moved aside; `null` to have [load] throw instead,
 *   for callers that never save and would rather stop than run on default settings.
 */
class FileConfigStore(
  private val path: String,
  private val onUnreadable: ((UnreadableConfig) -> Unit)? = null,
) : ConfigStore {
  private val log = KetchLogger("ConfigStore")
  private val tmpPath = "$path.tmp"

  override fun load(): KetchConfig {
    val file = path.toPath()
    val tmp = tmpPath.toPath()
    if (!platformFileSystem.exists(file) &&
      platformFileSystem.exists(tmp)
    ) {
      // Previous save wrote .tmp but didn't rename — recover
      platformFileSystem.atomicMove(tmp, file)
    } else if (platformFileSystem.exists(tmp)) {
      // Main file exists, leftover .tmp is stale — remove
      platformFileSystem.delete(tmp)
    }
    if (!platformFileSystem.exists(file)) return KetchConfig()
    val source = platformFileSystem.source(file).buffer()
    val content = try {
      source.readUtf8()
    } finally {
      source.close()
    }
    return try {
      ConfigStore.toml.decodeFromString(
        KetchConfig.serializer(), content,
      ).expandHome(userHomeDirectory)
    } catch (e: Exception) {
      val report = onUnreadable ?: throw e
      val movedTo = moveAside(file, e)
      log.w { "Moved unreadable $path aside to $movedTo, using defaults: ${e.describeCauses()}" }
      report(UnreadableConfig(path, movedTo, e))
      KetchConfig()
    }
  }

  override fun save(config: KetchConfig) {
    val file = path.toPath()
    val tmp = tmpPath.toPath()
    file.parent?.let { platformFileSystem.createDirectories(it) }
    val sink = platformFileSystem.sink(tmp).buffer()
    try {
      val encoded = ConfigStore.toml
        .encodeToString(KetchConfig.serializer(), config)
      sink.writeUtf8(encoded)
    } finally {
      sink.close()
    }
    platformFileSystem.atomicMove(tmp, file)
  }

  /**
   * Moves [file] to `<path>.broken-<UTC time>`, such as `config.toml.broken-20261003T142530Z`,
   * and returns where it went. When it cannot be moved, [cause] is thrown and the file stays, so
   * a save never replaces a file nobody could read.
   */
  private fun moveAside(file: Path, cause: Exception): String {
    val now = Clock.System.now()
    // ISO 8601 basic format, without the ':' that Windows refuses in file names.
    val stamp = Instant.fromEpochSeconds(now.epochSeconds).toString()
      .filter { it != '-' && it != ':' }
    val base = "$path.broken-$stamp"
    val target = generateSequence(1) { it + 1 }
      .map { if (it == 1) base else "$base-$it" }
      .first { !platformFileSystem.exists(it.toPath()) }
    try {
      platformFileSystem.atomicMove(file, target.toPath())
    } catch (e: IOException) {
      cause.addSuppressed(e)
      throw cause
    }
    return target
  }
}

/**
 * A config file [FileConfigStore] could not read, which it moved aside, keeping it, to start
 * with the default settings.
 *
 * @property path where the file was.
 * @property movedTo where the file is now.
 * @property cause why it could not be read, such as a TOML syntax error.
 */
class UnreadableConfig(
  val path: String,
  val movedTo: String,
  val cause: Exception,
)
