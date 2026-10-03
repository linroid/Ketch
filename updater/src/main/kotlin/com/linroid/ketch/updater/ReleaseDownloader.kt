package com.linroid.ketch.updater

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads release files with a Ketch engine of its own, then checks each against the SHA-256
 * digest GitHub published for it.
 *
 * @param httpEngine makes the HTTP engine of one download, which closes it afterwards.
 * @param logger the logger the process already uses: every Ketch engine installs its logger for
 *   the whole process.
 */
class ReleaseDownloader(
  private val httpEngine: () -> HttpEngine,
  private val logger: Logger,
) {
  /**
   * Downloads [asset] to [file], replacing what is there, and checks its digest.
   *
   * @param onProgress called as the download goes, on the engine's thread.
   * @throws UpdateException when [asset] has no digest, the download fails or the file does not
   *   match the digest. [file] is deleted whenever this throws.
   */
  suspend fun download(
    asset: ReleaseAsset,
    file: File,
    onProgress: (DownloadProgress) -> Unit = {},
  ) {
    val expected = asset.sha256
      ?: throw UpdateException("GitHub published no checksum for ${asset.name}")
    withContext(Dispatchers.IO) {
      file.delete()
      file.parentFile?.mkdirs()
    }
    try {
      fetch(asset, file, onProgress)
      val actual = withContext(Dispatchers.IO) { sha256(file) }
      if (actual != expected) {
        throw UpdateException("${asset.name} doesn't match the checksum GitHub published")
      }
    } catch (e: Throwable) {
      withContext(Dispatchers.IO) { file.delete() }
      throw e
    }
  }

  private suspend fun fetch(
    asset: ReleaseAsset,
    file: File,
    onProgress: (DownloadProgress) -> Unit,
  ) {
    val ketch = Ketch(
      httpEngine = httpEngine(),
      config = CONFIG,
      name = "Ketch updater",
      logger = logger,
    )
    try {
      val task = ketch.download(
        DownloadRequest(url = asset.url, destination = Destination(file.path)),
      )
      val result = coroutineScope {
        val progress = launch {
          task.state.collect { state ->
            if (state is DownloadState.Downloading) onProgress(state.progress)
          }
        }
        task.await().also { progress.cancel() }
      }
      result.getOrElse { error ->
        throw UpdateException("Couldn't download ${asset.name}: ${error.describeCauses()}", error)
      }
    } finally {
      ketch.close()
    }
  }

  private companion object {
    val CONFIG = DownloadConfig(
      retryCount = 3,
      maxConcurrentDownloads = 1,
      maxConnectionsPerDownload = 4,
    )
  }
}

/** SHA-256 digest of [file] in lowercase hex. */
internal fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}
