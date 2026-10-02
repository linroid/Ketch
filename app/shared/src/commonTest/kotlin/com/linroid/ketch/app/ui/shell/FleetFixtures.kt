package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.backgroundChild
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Presences of the devices the fleet tests show. */
internal object FleetFixtures {
  const val NAS_ID = "nas.local:8642"

  /** 1.8 TB free of 3.6 TB, in the binary units the app shows. */
  val NasDisk = DiskSpace(1_979_120_929_996, 4_000_787_030_016, "/volume1/downloads")

  /** This device, "This Mac" on "MacBook Pro". */
  fun mac(
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
    failures: Int = 0,
    speedMode: SpeedMode = SpeedMode.Full,
  ): DevicePresence = presence(
    entry = EmbeddedInstance(FakeKetchApi(), "MacBook Pro"),
    name = "This Mac",
    detail = "MacBook Pro",
    health = DeviceHealth.Local(),
    counts = counts,
    speed = speed,
    failures = failures,
    speedMode = speedMode,
  )

  /** NAS-Basement at nas.local:8642. */
  fun nas(
    health: DeviceHealth = DeviceHealth.Live,
    connected: Boolean = true,
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
    failures: Int = 0,
    disk: DiskSpace? = NasDisk,
    lastSeen: Instant? = null,
  ): DevicePresence = presence(
    entry = RemoteInstance(
      instance = FakeKetchApi(),
      remoteConfig = RemoteConfig(host = "nas.local", name = "NAS-Basement"),
      connectionState = MutableStateFlow(ConnectionState.Connected),
    ),
    name = "NAS-Basement",
    detail = NAS_ID,
    health = health,
    connected = connected,
    counts = counts,
    speed = speed,
    failures = failures,
    disk = disk,
    lastSeen = lastSeen,
  )

  /** The presence of a watched device with no status, cap or history. */
  fun presence(
    entry: InstanceEntry,
    name: String,
    detail: String = name,
    health: DeviceHealth = DeviceHealth.Live,
    connected: Boolean = true,
    status: KetchStatus? = null,
    statusAt: Instant? = null,
    lastSeen: Instant? = null,
    speed: Long = 0,
    counts: PulseCounts = PulseCounts(),
    failures: Int = 0,
    disk: DiskSpace? = null,
    speedMode: SpeedMode = SpeedMode.Full,
  ) = DevicePresence(
    entry = entry,
    name = name,
    detail = detail,
    health = health,
    connected = connected,
    watched = true,
    status = status,
    statusAt = statusAt,
    lastSeen = lastSeen,
    speed = speed,
    counts = counts,
    failures = failures,
    unseenFailures = 0,
    cap = SpeedLimit.Unlimited,
    disk = disk,
    speedMode = speedMode,
    history = emptyList(),
  )
}

/** This Mac and a connected NAS, whose engines are [mac] and [nas], with the app over them. */
internal class Fleet<A : KetchApi>(scope: TestScope, val mac: A, val nas: A, store: ConfigStore?) {
  val manager = InstanceManager(
    factory = InstanceFactory(
      deviceName = "This Mac",
      embeddedFactory = { mac },
      remoteFactory = { config ->
        RemoteInstance(nas, config, MutableStateFlow(ConnectionState.Connected))
      },
    ),
    initialRemotes = listOf(RemoteConfig(host = "nas.local", name = "NAS")),
    configStore = store,
    context = scope.backgroundScope.coroutineContext,
  )
  val controller = AppController(
    instanceManager = manager,
    context = scope.backgroundChild(),
    clock = ListFixtures.clock(scope),
  )
  val state: AppState get() = controller.state
  val local: InstanceEntry get() = manager.instances.value.first()
  val remote: InstanceEntry get() = manager.instances.value.last()
}

/** A [Fleet] over [mac] and [nas], once the NAS has connected. */
internal fun <A : KetchApi> TestScope.fleet(mac: A, nas: A, store: ConfigStore? = null): Fleet<A> =
  Fleet(this, mac, nas, store).also {
    runCurrent()
    advanceTimeBy(1.seconds)
  }
