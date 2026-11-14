package com.linroid.ketch.app.state

import com.linroid.ketch.core.file.renamedIfExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun renamedIfFileExists(folder: String, name: String): String? =
  withContext(Dispatchers.Default) { renamedIfExists(folder, name) }
