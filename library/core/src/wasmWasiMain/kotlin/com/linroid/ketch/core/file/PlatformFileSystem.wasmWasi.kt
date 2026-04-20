package com.linroid.ketch.core.file

import okio.FileSystem
import okio.WasiFileSystem

internal actual val platformFileSystem: FileSystem = WasiFileSystem
