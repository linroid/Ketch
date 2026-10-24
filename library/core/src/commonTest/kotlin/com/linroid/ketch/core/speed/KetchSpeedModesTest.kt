package com.linroid.ketch.core.speed

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.engine.FakeHttpEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Ketch] runs its speed modes over its own global speed limit. */
@OptIn(ExperimentalCoroutinesApi::class)
class KetchSpeedModesTest {

  private fun TestScope.ketch(
    cap: SpeedLimit = SpeedLimit.Unlimited,
    speedModes: SpeedModeOptions? = SpeedModeOptions(),
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      // Android host tests have no application context to resolve the default folder.
      config = DownloadConfig(
        speedLimit = cap,
        defaultDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString(),
      ),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      speedModes = speedModes,
    )
  }

  @Test
  fun speedModes_given_listedAndOffered() = runTest {
    val ketch = ketch()
    try {
      assertTrue(KetchFeatures.SPEED_MODES in ketch.status().features)
      assertNotNull(ketch.controller(SpeedModes))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun speedModes_none_subscribeFails() = runTest {
    val ketch = ketch(speedModes = null)
    try {
      assertFalse(KetchFeatures.SPEED_MODES in ketch.status().features)
      assertNull(ketch.controller(SpeedModes))
      assertFailsWith<UnsupportedOperationException> {
        ketch.subscribe(Interest.SpeedMode).first()
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun init_savedSlowLane_startsAtSlowLaneLimit() = runTest {
    val settings = SpeedSettings(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(2))
    val ketch = ketch(cap = SpeedLimit.mbps(5), speedModes = SpeedModeOptions(settings))
    try {
      assertEquals(SpeedLimit.mbps(2), ketch.status().config.speedLimit)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun updateConfig_inSlowLane_otherLimitBecomesStandingCap() = runTest {
    val ketch = ketch(speedModes = SpeedModeOptions(SpeedSettings(mode = SpeedLimitMode.SlowLane)))
    val modes = ketch.controller(SpeedModes)!!
    try {
      val read = ketch.status().config
      ketch.updateConfig(read.copy(maxConnectionsPerDownload = 2))
      assertEquals(SpeedLimit.mbps(1), ketch.status().config.speedLimit)
      assertEquals(SpeedLimit.Unlimited, modes.state().settings.standard)

      ketch.updateConfig(ketch.status().config.copy(speedLimit = SpeedLimit.mbps(5)))
      assertEquals(SpeedLimit.mbps(1), ketch.status().config.speedLimit)

      modes.update(SpeedModeChange(mode = SpeedLimitMode.Full))
      assertEquals(SpeedLimit.mbps(5), ketch.status().config.speedLimit)
      assertEquals(2, ketch.status().config.maxConnectionsPerDownload)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun subscribe_speedMode_followsChanges() = runTest {
    val ketch = ketch()
    val modes = ketch.controller(SpeedModes)!!
    try {
      val states = ketch.subscribe(Interest.SpeedMode)
      assertFalse(states.first().inSlowLane)

      modes.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))
      runCurrent()

      assertTrue(states.first().inSlowLane)
    } finally {
      ketch.close()
    }
  }
}
