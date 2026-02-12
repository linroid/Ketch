package com.linroid.kdown.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.linroid.kdown.app.App
import com.linroid.kdown.app.backend.BackendFactory
import com.linroid.kdown.app.backend.BackendManager
import com.linroid.kdown.app.backend.LocalServerHandle
import com.linroid.kdown.server.KDownServer
import com.linroid.kdown.server.KDownServerConfig
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val backendManager = remember {
        val taskStore = createSqliteTaskStore(
          DriverFactory(applicationContext)
        )
        BackendManager(
          BackendFactory(
            taskStore = taskStore,
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
        onDispose { backendManager.close() }
      }
      App(backendManager)
    }
  }
}
