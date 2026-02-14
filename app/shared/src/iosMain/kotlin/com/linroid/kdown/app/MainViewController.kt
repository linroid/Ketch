package com.linroid.kdown.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
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
    val taskStore = createSqliteTaskStore(DriverFactory())
    @Suppress("UNCHECKED_CAST")
    val downloadsDir = (NSSearchPathForDirectoriesInDomains(
      NSDocumentDirectory, NSUserDomainMask, true,
    ) as List<String>).first()
    InstanceManager(
      InstanceFactory(
        taskStore = taskStore,
        defaultDirectory = downloadsDir,
        deviceName = UIDevice.currentDevice.name,
      )
    )
  }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  App(instanceManager)
}
