package com.linroid.ketch.config

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileNotFoundException
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Name of the file that keeps a device's identity, beside its task database. */
const val DEVICE_FILE_NAME = "device.json"

/**
 * This device's identity, kept in `device.json` (readable and writable by its owner only) beside
 * the task database: the config directory on the desktop and the CLI, which the desktop app,
 * `ketch server` and `ketch mcp --standalone` share, and the app's data directory on phones,
 * which leave it out of backups and transfers to a new device, so a restored copy gets an
 * identity of its own. It never goes in `config.toml`, which users may keep read-only.
 *
 * Create it where the task database is opened, after taking the lock that keeps one process at a
 * time on that database where there is one, so two processes never create it at once.
 *
 * @param path where `device.json` is
 */
class DeviceStore(private val path: String) {
  private val log = KetchLogger("DeviceStore")

  /**
   * Returns this device's instance id, a random UUID, which `KetchStatus.instanceId` reports.
   * Generates one, and saves it, when the file is missing or holds no valid id. An id that
   * cannot be saved is still returned, for this process alone; the next start generates another.
   */
  @OptIn(ExperimentalUuidApi::class)
  fun instanceId(): String {
    val saved = read()
    if (saved != null) return saved
    val id = Uuid.random().toString()
    try {
      path.toPath().parent?.let { platformFileSystem.createDirectories(it) }
      writeOwnerOnly(path, json.encodeToString(DeviceFile.serializer(), DeviceFile(id)))
      log.i { "Created a new device identity in $path" }
    } catch (e: IOException) {
      log.w { "Couldn't save the device identity to $path: ${e.describeCauses()}" }
    }
    return id
  }

  @OptIn(ExperimentalUuidApi::class)
  private fun read(): String? {
    val content = try {
      platformFileSystem.read(path.toPath()) { readUtf8() }
    } catch (_: FileNotFoundException) {
      return null
    } catch (e: IOException) {
      log.w { "Couldn't read $path, creating a new device identity: ${e.describeCauses()}" }
      return null
    }
    val id = try {
      json.decodeFromString(DeviceFile.serializer(), content).instanceId
    } catch (e: IllegalArgumentException) {
      log.w { "$path is unreadable, creating a new device identity: ${e.describeCauses()}" }
      return null
    }
    if (Uuid.parseOrNull(id) == null) {
      log.w { "$path holds no valid instance id, creating a new device identity" }
      return null
    }
    return id
  }

  private companion object {
    // Later versions add fields, such as helper pairings, that this one keeps by not rewriting.
    val json = Json { ignoreUnknownKeys = true }
  }
}

/** What `device.json` holds. */
@Serializable
private class DeviceFile(val instanceId: String)

/**
 * Replaces the file at [path] with [content], readable and writable by its owner only: the
 * content goes to a file created with those permissions, which then takes its place. On iOS the
 * file is also left out of backups.
 *
 * @throws IOException if it cannot be written
 */
internal expect fun writeOwnerOnly(path: String, content: String)
