package com.linroid.ketch.core.file

import okio.FileSystem
import okio.NodeJsFileSystem

internal actual val platformFileSystem: FileSystem = NodeJsFileSystem
