package com.linroid.ketch.core.file

import kotlinx.io.files.Path

internal actual fun resolveChildPath(
  directory: String,
  fileName: String,
): String = Path(directory, fileName).toString()
