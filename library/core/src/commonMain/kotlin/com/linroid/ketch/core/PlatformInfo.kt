package com.linroid.ketch.core

import com.linroid.ketch.api.SystemInfo

/**
 * Returns current system and storage information for the download [directory], or for
 * [defaultDownloadDirectory] when it is `null`.
 */
internal expect fun currentSystemInfo(directory: String?): SystemInfo

/** Folder used when [com.linroid.ketch.api.DownloadConfig.defaultDirectory] is `null`. */
internal expect fun defaultDownloadDirectory(): String
