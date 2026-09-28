package com.linroid.ketch.app.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop

@Composable
internal actual fun rememberLogFilesAction(logger: FileLogger): LogFilesAction? =
  remember(logger) {
    LogFilesAction(title = "Open log folder", description = logger.directory.toString()) {
      logger.flush()
      withContext(Dispatchers.IO) {
        if (!Desktop.isDesktopSupported() ||
          !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
          throw UnsupportedOperationException("This system cannot open folders")
        }
        // Nothing may have been logged yet at a strict KETCH_LOG_LEVEL.
        val directory = logger.directory.toFile().apply { mkdirs() }
        Desktop.getDesktop().open(directory)
      }
    }
  }
