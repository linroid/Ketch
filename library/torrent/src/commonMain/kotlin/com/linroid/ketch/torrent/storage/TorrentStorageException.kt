package com.linroid.ketch.torrent.storage

import okio.IOException

internal class TorrentStorageException(cause: IOException) :
  Exception("Torrent storage failed", cause)
