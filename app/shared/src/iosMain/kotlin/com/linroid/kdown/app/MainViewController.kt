package com.linroid.kdown.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.linroid.kdown.app.backend.BackendFactory
import com.linroid.kdown.app.backend.BackendManager
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController {
  val backendManager = remember {
    val dbPath = appSupportDbPath()
    val taskStore = createSqliteTaskStore(DriverFactory(dbPath))
    @Suppress("UNCHECKED_CAST")
    val downloadsDir = (NSSearchPathForDirectoriesInDomains(
      NSDocumentDirectory, NSUserDomainMask, true,
    ) as List<String>).first()
    BackendManager(
      BackendFactory(
        taskStore = taskStore,
        defaultDirectory = downloadsDir,
      )
    )
  }
  DisposableEffect(Unit) {
    onDispose { backendManager.close() }
  }
  App(backendManager)
}

@Suppress("UNCHECKED_CAST")
private fun appSupportDbPath(): String {
  val paths = NSSearchPathForDirectoriesInDomains(
    NSApplicationSupportDirectory, NSUserDomainMask, true,
  ) as List<String>
  val dir = "${paths.first()}/kdown"
  val dirPath = Path(dir)
  if (!SystemFileSystem.exists(dirPath)) {
    SystemFileSystem.createDirectories(dirPath)
  }
  return "$dir/kdown.db"
}
