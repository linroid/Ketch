package com.linroid.ketch.core

import com.linroid.ketch.api.SystemInfo

/**
 * Returns current system and storage information for
 * the given download [directory].
 */
internal expect fun currentSystemInfo(directory: String): SystemInfo
