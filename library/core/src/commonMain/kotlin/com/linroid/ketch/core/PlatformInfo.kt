package com.linroid.ketch.core

import com.linroid.ketch.api.SystemInfo

/**
 * Returns current system and storage information for
 * the given download [directory].
 */
internal expect fun currentSystemInfo(directory: String): SystemInfo

/** Folder used when [com.linroid.ketch.api.DownloadConfig.defaultDirectory] is `null`. */
internal expect fun defaultDownloadDirectory(): String
