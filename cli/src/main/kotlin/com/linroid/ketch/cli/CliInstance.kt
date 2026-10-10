package com.linroid.ketch.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Marks the `ketch` process that runs the downloads of a config directory, `ketch server` or
 * `ketch mcp --standalone`. It holds [LOCK_FILE] while its engine has the task database open, so
 * no other `ketch` process opens it too, and describes itself in [INFO_FILE], readable by its
 * owner only as it holds the access token, so other `ketch` commands can attach to it.
 *
 * The desktop app is found through its own endpoint instead (see [DesktopApp]).
 */
internal class CliInstance private constructor(
  private val infoFile: File,
  private val channel: FileChannel,
) : AutoCloseable {

  /** Describes this process in the info file, replacing what it said before. */
  fun publish(info: Info) {
    writeOwnerOnly(infoFile, info.toJson().toString())
  }

  /** Removes the info file and lets another process run the downloads. */
  override fun close() {
    infoFile.delete()
    // Closing the channel releases the lock.
    channel.close()
  }

  /**
   * What the process running the downloads says about itself.
   *
   * @property command the command it runs, such as `ketch server`
   * @property pid its process ID
   * @property url where its API listens on this machine, once it does
   * @property token the access token its API requires, if any
   */
  data class Info(
    val command: String,
    val pid: Long,
    val url: String? = null,
    val token: String? = null,
  ) {
    internal fun toJson(): JsonObject = buildJsonObject {
      put("command", command)
      put("pid", pid)
      url?.let { put("url", it) }
      token?.let { put("token", it) }
    }
  }

  companion object {
    const val LOCK_FILE = "instance.lock"
    const val INFO_FILE = "instance.json"

    /**
     * Makes this process the one that runs the downloads of the config directory [dir], or
     * returns `null` when another one already does; [read] says which.
     *
     * @throws IOException if the lock file cannot be opened
     */
    fun acquire(dir: File): CliInstance? {
      dir.mkdirs()
      val channel = FileChannel.open(
        File(dir, LOCK_FILE).toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
      )
      val lock = try {
        channel.tryLock()
      } catch (_: OverlappingFileLockException) {
        // Held by this JVM already, which only happens in tests.
        null
      } catch (e: IOException) {
        channel.close()
        throw e
      }
      if (lock == null) {
        channel.close()
        return null
      }
      return CliInstance(File(dir, INFO_FILE), channel)
    }

    /**
     * What the process running the downloads of [dir] says about itself, or `null` when the
     * info file is missing or cannot be read. A process that stopped without removing it can
     * leave one behind.
     */
    fun read(dir: File): Info? {
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
      return Info(
        command = json["command"]?.jsonPrimitive?.contentOrNull ?: return null,
        pid = json["pid"]?.jsonPrimitive?.longOrNull ?: return null,
        url = json["url"]?.jsonPrimitive?.contentOrNull,
        token = json["token"]?.jsonPrimitive?.contentOrNull,
      )
    }
  }
}

/**
 * Replaces [file] with [content], readable and writable by its owner only: the content goes to a
 * file created with those permissions, which then takes [file]'s place.
 */
private fun writeOwnerOnly(file: File, content: String) {
  val temp = File(file.parentFile, "${file.name}.tmp").toPath()
  Files.deleteIfExists(temp)
  if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
    Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
  } else {
    // On Windows the profile folder holding the config directory is already private.
    Files.createFile(temp)
  }
  Files.write(temp, content.toByteArray())
  Files.move(
    temp, file.toPath(),
    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
  )
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
