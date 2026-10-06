package com.linroid.ketch.app.nativeimage

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.linroid.ketch.app.App
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.dash.DashDownloadSource
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.hls.HlsDownloadSource
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication

/** Runs the shared UI and HTTP engine in an isolated, in-memory Native Image experiment. */
fun main(args: Array<String>): Unit = nucleusApplication(args = args, backend = NucleusBackend.Tao) {
  val manager = remember {
    InstanceManager(
      factory = InstanceFactory(
        deviceName = "Native Image prototype",
        embeddedFactory = {
          val httpEngine = KtorHttpEngine()
          Ketch(
            httpEngine = httpEngine,
            additionalSources = listOf(HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)),
          )
        },
      ),
    )
  }
  DisposableEffect(manager) {
    onDispose { manager.close() }
  }
  DecoratedWindow(
    onCloseRequest = ::exitApplication,
    title = "Ketch Native Image prototype",
    state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
  ) {
    App(instanceManager = manager)
  }
}
