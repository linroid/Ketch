package com.linroid.ketch.cli

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** Environment variable that holds the access token of `ketch server`. */
internal const val TOKEN_ENV = "KETCH_API_TOKEN"

/** Name of the file in the config directory that keeps the token `ketch server` created. */
internal const val TOKEN_FILE_NAME = "api-token"

/** The access token `ketch server` requires, and where it came from. */
internal sealed interface ServerToken {
  val value: String?

  /** Given with `--token`, [TOKEN_ENV] or `apiToken`, named by [source]. */
  data class Given(override val value: String, val source: String) : ServerToken

  /** Created by an earlier run and read from [file]. */
  data class Saved(override val value: String, val file: File) : ServerToken

  /** Created by this run and saved to [file]. */
  data class Created(override val value: String, val file: File) : ServerToken

  /** No token: the server only listens on loopback, or `--no-token` asked for none. */
  data object None : ServerToken {
    override val value: String? = null
  }
}

/**
 * Picks the access token of `ketch server`: [cliToken] (`--token`), then [environmentToken]
 * ([TOKEN_ENV]), then [configToken] (`apiToken`). Without any, a server that listens beyond
 * this machine ([loopbackOnly] is `false`) uses the token kept in [tokenFile], creating it
 * readable by its owner only when it is missing; a loopback server takes none. [noToken]
 * (`--no-token`) turns all of this off. Blank values count as missing.
 *
 * @throws IOException if [tokenFile] cannot be read or created
 */
internal fun resolveServerToken(
  cliToken: String?,
  noToken: Boolean,
  environmentToken: String?,
  configToken: String?,
  loopbackOnly: Boolean,
  tokenFile: File,
): ServerToken {
  if (noToken) return ServerToken.None
  cliToken?.takeIf { it.isNotBlank() }?.let { return ServerToken.Given(it, "--token") }
  environmentToken?.takeIf { it.isNotBlank() }?.let { return ServerToken.Given(it, TOKEN_ENV) }
  configToken?.takeIf { it.isNotBlank() }?.let { return ServerToken.Given(it, "apiToken") }
  if (loopbackOnly) return ServerToken.None
  readTokenFile(tokenFile)?.let { return ServerToken.Saved(it, tokenFile) }
  val token = newToken()
  return try {
    createTokenFile(tokenFile, token)
    ServerToken.Created(token, tokenFile)
  } catch (_: FileAlreadyExistsException) {
    // Another server created it in the meantime.
    ServerToken.Saved(checkNotNull(readTokenFile(tokenFile)) { "$tokenFile is empty" }, tokenFile)
  }
}

private fun readTokenFile(file: File): String? {
  if (!file.exists()) return null
  try {
    restrictToOwner(file)
  } catch (e: IOException) {
    System.err.println("Warning: could not make $file private to its owner: ${e.message}")
  }
  return file.readText().trim().ifEmpty { null }
}

private fun createTokenFile(file: File, token: String) {
  file.parentFile?.mkdirs()
  val path = file.toPath()
  if (isPosix()) {
    // Created with its permissions, so no one else can open it before they are set.
    Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
  } else {
    Files.createFile(path)
    restrictToOwner(file)
  }
  file.writeText(token + "\n")
}

/** Makes [file] readable and writable by its owner only, as a token file must be. */
private fun restrictToOwner(file: File) {
  if (isPosix()) {
    val path = file.toPath()
    if (Files.getPosixFilePermissions(path) != OWNER_ONLY) {
      Files.setPosixFilePermissions(path, OWNER_ONLY)
    }
  } else {
    // On Windows the profile folder holding the config directory is already private.
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
  }
}

private fun isPosix(): Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

internal fun newToken(): String =
  ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
