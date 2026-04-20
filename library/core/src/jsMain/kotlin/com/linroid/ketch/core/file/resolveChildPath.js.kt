package com.linroid.ketch.core.file

import okio.Path.Companion.toPath

internal actual fun resolveChildPath(
  directory: String,
  fileName: String,
): String = (directory.toPath() / fileName).toString()
