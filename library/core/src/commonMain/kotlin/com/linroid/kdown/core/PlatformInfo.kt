package com.linroid.kdown.core

import com.linroid.kdown.api.StorageInfo
import com.linroid.kdown.api.SystemInfo

/** Returns current system information (OS, arch, memory, etc.). */
internal expect fun currentSystemInfo(): SystemInfo

/** Returns storage information for the given [directory]. */
internal expect fun currentStorageInfo(directory: String): StorageInfo
