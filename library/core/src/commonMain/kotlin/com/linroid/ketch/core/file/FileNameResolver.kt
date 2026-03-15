package com.linroid.ketch.core.file

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource

/**
 * Resolves a file name for a download from source metadata.
 *
 * Explicit names set via [DownloadRequest.destination] are handled by
 * the coordinator before this resolver is called. Implementations only
 * need to derive a name from the source response.
 */
fun interface FileNameResolver {
  /**
   * Resolves a file name from the given [request] and [resolved] metadata.
   *
   * @param request the download request
   * @param resolved source metadata from the resolve/probe step
   * @return a non-blank file name to save the download as
   */
  fun resolve(request: DownloadRequest, resolved: ResolvedSource): String
}
