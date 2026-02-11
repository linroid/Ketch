package com.linroid.kdown.core.file

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.core.engine.ServerInfo

/**
 * Resolves a file name for a download.
 *
 * Implement this interface to customize how file names are determined from
 * the download request and server response headers. Implementations should
 * check [DownloadRequest.fileName] first and honour it when set.
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
