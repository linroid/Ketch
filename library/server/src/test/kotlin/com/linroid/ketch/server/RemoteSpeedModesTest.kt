package com.linroid.ketch.server

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedRule
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.speed.SpeedModeOptions
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteKetch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A client switches a server's speed modes, which the server then runs on its own. */
class RemoteSpeedModesTest {

  @Test
  fun remote_switchesTheServerToSlowLane_whichTheServerAppliesAndSaves() = runTest {
    withServer { ketch, client, saved ->
      assertTrue(KetchFeatures.SPEED_MODES in client.status().features)
      val modes = assertNotNull(client.controller(SpeedModes))

      val state = modes.update(
        SpeedModeChange(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(2)),
      )

      assertTrue(state.inSlowLane)
      assertEquals(SpeedLimit.mbps(2), ketch.status().config.speedLimit)
      assertEquals(SpeedLimit.mbps(2), client.status().config.speedLimit)
      assertEquals(SpeedLimitMode.SlowLane, saved.last().mode)
      assertEquals(SpeedLimit.mbps(5), saved.last().standard)
    }
  }

  @Test
  fun remote_autoRuleCoveringEveryDay_serverHoldsTheSlowLane() = runTest {
    withServer { ketch, client, _ ->
      val modes = assertNotNull(client.controller(SpeedModes))
      val wholeDay = SpeedRule(start = "00:00", end = "00:00")

      modes.update(SpeedModeChange(mode = SpeedLimitMode.Auto, rules = listOf(wholeDay)))
      client.close()

      val state = assertNotNull(ketch.controller(SpeedModes)).state()
      assertTrue(state.inSlowLane)
      assertEquals(SpeedLimit.mbps(1), ketch.status().config.speedLimit)
    }
  }

  @Test
  fun remote_followsTheServersSpeedMode() = runTest {
    withServer { ketch, client, _ ->
      client.start()
      client.connectionState.first { it == ConnectionState.Connected }
      val states = client.subscribe(Interest.SpeedMode)
      assertEquals(SpeedLimitMode.Full, states.first().settings.mode)

      assertNotNull(ketch.controller(SpeedModes))
        .update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))

      assertTrue(states.first { it.inSlowLane }.inSlowLane)
    }
  }

  @Test
  fun remote_withoutSpeedModes_failsClosed() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val ketch = Ketch(httpEngine = KtorHttpEngine())
        val server = KetchServer(ketch, port = 0, mdnsEnabled = false)
        var remote: RemoteKetch? = null
        val http = HttpClient()
        try {
          ketch.start()
          server.start(wait = false)
          val client = RemoteKetch("127.0.0.1", server.port()).also { remote = it }
          val modes = assertNotNull(client.controller(SpeedModes))

          assertFailsWith<UnsupportedOperationException> { modes.state() }
          val response = http.get("http://127.0.0.1:${server.port()}/api/speed")
          assertEquals(HttpStatusCode.NotFound, response.status)
        } finally {
          http.close()
          remote?.close()
          server.stop()
          ketch.close()
        }
      }
    }
  }

  // A server over an engine with speed modes and a cap of 5 MB/s, the settings it saved, and a
  // client of it.
  private suspend fun withServer(
    block: suspend (Ketch, RemoteKetch, List<SpeedSettings>) -> Unit,
  ) {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val saved = CopyOnWriteArrayList<SpeedSettings>()
        val ketch = Ketch(
          httpEngine = KtorHttpEngine(),
          config = DownloadConfig(speedLimit = SpeedLimit.mbps(5)),
          speedModes = SpeedModeOptions(saveSettings = { saved += it }),
        )
        val server = KetchServer(ketch, port = 0, mdnsEnabled = false)
        var remote: RemoteKetch? = null
        try {
          ketch.start()
          server.start(wait = false)
          val client = RemoteKetch("127.0.0.1", server.port()).also { remote = it }
          block(ketch, client, saved)
        } finally {
          remote?.close()
          server.stop()
          ketch.close()
        }
      }
    }
  }
}
