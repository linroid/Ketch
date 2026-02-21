package com.linroid.kdown.core

import com.linroid.kdown.api.SystemInfo

/**
 * Returns current system and storage information for
 * the given download [directory].
 */
internal expect fun currentSystemInfo(directory: String): SystemInfo
