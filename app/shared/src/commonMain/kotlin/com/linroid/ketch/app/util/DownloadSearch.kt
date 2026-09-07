package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadRequest

internal fun DownloadRequest.matchesSearch(query: String): Boolean {
  val term = query.trim()
  return term.isEmpty() || url.contains(term, ignoreCase = true) ||
    destination?.value?.contains(term, ignoreCase = true) == true
}
