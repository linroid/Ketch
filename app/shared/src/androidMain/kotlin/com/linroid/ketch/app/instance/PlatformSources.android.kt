package com.linroid.ketch.app.instance

import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.torrent.TorrentDownloadSource

internal actual fun platformAdditionalSources(): List<DownloadSource> =
  listOf(FtpDownloadSource(), TorrentDownloadSource())
