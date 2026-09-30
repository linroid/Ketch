package com.linroid.ketch.config

import okio.FileSystem

internal actual val platformFileSystem: FileSystem = FileSystem.SYSTEM

internal actual val userHomeDirectory: String?
  get() = System.getProperty("user.home")?.ifEmpty { null }
