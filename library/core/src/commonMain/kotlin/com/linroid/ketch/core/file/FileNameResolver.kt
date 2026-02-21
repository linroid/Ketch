package com.linroid.ketch.core.file

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.ServerInfo

/**
 * Resolves a file name for a download from server metadata.
 *
 * Explicit names set via [DownloadRequest.destination] are handled by
 * the coordinator before this resolver is called. Implementations only
 * need to derive a name from the server response.
 */
fun interface FileNameResolver {
  /**
   * Resolves a file name from the given [request] and [serverInfo].
   *
   * @param request the download request
   * @param serverInfo server metadata from the HEAD response
   * @return a non-blank file name to save the download as
   */
  fun resolve(request: DownloadRequest, serverInfo: ServerInfo): String
}
