package com.linroid.kdown.app.android

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.linroid.kdown.app.App
import com.linroid.kdown.app.instance.InstanceFactory
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.app.instance.LocalServerHandle
import com.linroid.kdown.server.KDownServer
import com.linroid.kdown.server.KDownServerConfig
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val instanceManager = remember {
        val taskStore = createSqliteTaskStore(
          DriverFactory(applicationContext)
        )
        val downloadsDir = Environment
          .getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
          ).absolutePath
        InstanceManager(
          InstanceFactory(
            taskStore = taskStore,
            defaultDirectory = downloadsDir,
            deviceName = android.os.Build.MODEL,
            localServerFactory = { port, apiToken, kdownApi ->
              val server = KDownServer(
                kdownApi,
                KDownServerConfig(
                  port = port,
                  apiToken = apiToken,
                  corsAllowedHosts = listOf("*"),
                )
              )
              server.start(wait = false)
              object : LocalServerHandle {
                override fun stop() {
                  server.stop()
                }
              }
            }
          )
        )
      }
      DisposableEffect(Unit) {
        onDispose { instanceManager.close() }
      }
      App(instanceManager)
    }
  }
}
