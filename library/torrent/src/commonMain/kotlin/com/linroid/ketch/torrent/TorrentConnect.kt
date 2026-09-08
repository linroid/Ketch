package com.linroid.ketch.torrent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException

/** Try every DNS answer, reserving time for later address families within one deadline. */
internal suspend fun <T, R : Any> connectTorrentCandidates(
  candidates: List<T>,
  connect: suspend (T) -> R,
): R = withTimeout(10_000) {
  require(candidates.isNotEmpty()) { "No torrent endpoint addresses" }
  var failure: Exception? = null
  for (candidate in candidates) {
    try {
      val result = withTimeoutOrNull(10_000L / candidates.size) { connect(candidate) }
      if (result != null) return@withTimeout result
    } catch (error: Exception) {
      currentCoroutineContext().ensureActive()
      failure = error
    }
  }
  throw IOException("Cannot connect to any torrent endpoint address", failure)
}
