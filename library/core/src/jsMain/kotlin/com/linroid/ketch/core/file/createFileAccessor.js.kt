package com.linroid.ketch.core.file

import kotlinx.coroutines.CoroutineDispatcher

actual fun createFileAccessor(
  path: String,
  ioDispatcher: CoroutineDispatcher,
): FileAccessor = PathFileAccessor(path, ioDispatcher)
