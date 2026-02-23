package com.linroid.ketch.app.instance

import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.ftp.FtpDownloadSource

internal actual fun platformAdditionalSources(): List<DownloadSource> =
  listOf(FtpDownloadSource())
