package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Saves the download settings clients of `ketch server` change through `PUT /api/config` to the
 * `[download]` table of its config file at [path], so they outlast a restart.
 *
 * Only the fields a change sets to a new value are written, over what the file holds when it is
 * saved: values the server took from flags, `KETCH_*` variables or its defaults stay out of the
 * file, and edits made to it meanwhile are kept. The other tables keep their values too, though
 * the file is written anew, without its comments, as the apps write theirs. A field a flag or
 * variable sets is saved as well, but that flag or variable replaces it again at the next start,
 * which is logged.
 *
 * @param path the config file, created when missing, as in the Docker image's `/config`.
 * @param started the download settings the server started with.
 * @param pinned what sets each field at every start, such as `KETCH_SPEED_LIMIT` for
 *   `speedLimit`, by field name ([pinnedDownloadFields]).
 */
internal class ServerConfigFile(
  private val path: String,
  started: DownloadConfig,
  private val pinned: Map<String, String> = emptyMap(),
) {
  private val log = KetchLogger("ServerConfig")
  private val lock = Mutex()

  // What the file holds of the settings in effect: a change is what differs from these. Moved
  // on only once saved, so a save that failed is tried again with the next change.
  private var saved = started

  /**
   * Saves the fields of [config], the download settings now in effect, that differ from those
   * saved last.
   *
   * @throws Exception when the file cannot be read, such as one edited into invalid TOML since,
   *   or written; the file is left as it was.
   */
  suspend fun save(config: DownloadConfig) {
    lock.withLock {
      val changed = changedFields(saved, config)
      if (changed.isNotEmpty()) {
        withContext(Dispatchers.IO) {
          val store = FileConfigStore(path)
          val file = store.load()
          store.save(file.copy(download = file.download.with(changed)))
        }
        log.i { "Saved ${changed.keys.joinToString()} to $path" }
        for (field in changed.keys) {
          val source = pinned[field] ?: continue
          log.w { "$source sets $field when the server starts, replacing the value saved" }
        }
      }
      saved = config
    }
  }

  /**
   * Applies [transform] to the file as it is now and saves the result, under the lock [save]
   * takes, so neither drops what the other wrote, such as the speed modes' `[speed]`.
   *
   * @throws Exception when the file cannot be read or written; the file is left as it was.
   */
  suspend fun update(transform: (KetchConfig) -> KetchConfig) {
    lock.withLock {
      withContext(Dispatchers.IO) {
        val store = FileConfigStore(path)
        store.save(transform(store.load()))
      }
    }
  }

  /** What sets [field] of `[download]` at every start, replacing what is saved; `null` for none. */
  fun pinnedBy(field: String): String? = pinned[field]
}

/**
 * The [DownloadConfig] fields `ketch server` takes from its flags or the [ServerEnv] variables set
 * in [environment], by field name, with what sets each: at every start they replace what the
 * config file holds.
 *
 * @param flags the fields flags set, with the flag, such as `--dir` for `defaultDirectory`;
 *   they win over the variables.
 */
internal fun pinnedDownloadFields(
  environment: Map<String, String>,
  flags: Map<String, String> = emptyMap(),
): Map<String, String> {
  val variables = mapOf(
    "defaultDirectory" to ServerEnv.DOWNLOAD_DIR,
    "speedLimit" to ServerEnv.SPEED_LIMIT,
    "maxConcurrentDownloads" to ServerEnv.MAX_CONCURRENT_DOWNLOADS,
    "maxConnectionsPerDownload" to ServerEnv.MAX_CONNECTIONS_PER_DOWNLOAD,
    "maxConnectionsPerHost" to ServerEnv.MAX_CONNECTIONS_PER_HOST,
  )
  return variables.filterValues { !environment[it].isNullOrBlank() } + flags
}

private val json = Json { encodeDefaults = true }

/** The fields of [after], by name, whose values differ from [before]'s. */
private fun changedFields(before: DownloadConfig, after: DownloadConfig): Map<String, JsonElement> {
  val old = json.encodeToJsonElement(DownloadConfig.serializer(), before).jsonObject
  val new = json.encodeToJsonElement(DownloadConfig.serializer(), after).jsonObject
  return new.filter { (name, value) -> old[name] != value }
}

/** This configuration with [fields], by name, set to their values. */
private fun DownloadConfig.with(fields: Map<String, JsonElement>): DownloadConfig {
  val own = json.encodeToJsonElement(DownloadConfig.serializer(), this).jsonObject
  return json.decodeFromJsonElement(DownloadConfig.serializer(), JsonObject(own + fields))
}
