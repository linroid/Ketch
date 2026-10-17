package com.linroid.ketch.updater

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads release files with a Ketch engine of its own, which checks each against the SHA-256
 * digest GitHub published for it ([DownloadRequest.checksum]).
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
      ?.let { Checksum.parseOrNull(ChecksumAlgorithm.SHA_256.id + ":" + it) }
      ?: throw UpdateException("GitHub published no checksum for ${asset.name}")
    withContext(Dispatchers.IO) {
      file.delete()
      file.parentFile?.mkdirs()
    }
    try {
      fetch(asset, file, expected, onProgress)
    } catch (e: Throwable) {
      withContext(Dispatchers.IO) { file.delete() }
      throw e
    }
  }

  private suspend fun fetch(
    asset: ReleaseAsset,
    file: File,
    checksum: Checksum,
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
        DownloadRequest(
          url = asset.url,
          destination = Destination(file.path),
          checksum = checksum,
        ),
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
        if (error is KetchError.ChecksumMismatch) {
          throw UpdateException("${asset.name} doesn't match the checksum GitHub published", error)
        }
        throw UpdateException("Couldn't download ${asset.name}: ${error.describeCauses()}", error)
      }
    } finally {
      // Returns once the engine and its HTTP engine are released, also when cancelled.
      withContext(NonCancellable) { ketch.shutdown() }
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
