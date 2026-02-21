package com.linroid.kdown.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.linroid.kdown.app.config.FileConfigStore
import com.linroid.kdown.app.instance.InstanceFactory
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController {
  val instanceManager = remember {
    @Suppress("UNCHECKED_CAST")
    val docsDir = (NSSearchPathForDirectoriesInDomains(
      NSDocumentDirectory, NSUserDomainMask, true,
    ) as List<String>).first()
    val configStore = FileConfigStore("$docsDir/config.toml")
    val config = configStore.load()
    val taskStore = createSqliteTaskStore(DriverFactory())
    val downloadsDir = docsDir
    val downloadConfig = config.download.copy(
      defaultDirectory = config.download.defaultDirectory
        .takeIf { it != "downloads" }
        ?: downloadsDir,
    )
    val instanceName = config.name
      ?: UIDevice.currentDevice.name
    InstanceManager(
      factory = InstanceFactory(
        taskStore = taskStore,
        downloadConfig = downloadConfig,
        deviceName = instanceName,
      ),
      initialRemotes = config.remote,
      configStore = configStore,
    )
  }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  App(instanceManager)
}
