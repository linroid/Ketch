package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.shortPath
import com.linroid.ketch.app.testStatus
import com.linroid.ketch.app.testSystem
import com.linroid.ketch.app.ui.shell.FleetFixtures.presence
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DeviceTextTest {

  private val now = Instant.parse("2026-10-01T14:30:00Z")
  private val nas = RemoteInstance(
    instance = FakeKetchApi("NAS"),
    remoteConfig = RemoteConfig(host = "nas.local", name = "NAS-Basement"),
    connectionState = MutableStateFlow(ConnectionState.Connected),
  )

  private fun status(os: String = "Linux", arch: String = "amd64", uptime: Long = 3600) =
    testStatus(
      name = "NAS-Basement",
      version = "0.0.1",
      uptime = uptime,
      system = testSystem(
        os = os,
        arch = arch,
        javaVersion = "21",
        availableProcessors = 4,
        downloadDirectory = "/volume1/downloads",
      ),
    )

  private fun device(
    entry: InstanceEntry = nas,
    health: DeviceHealth = DeviceHealth.Live,
    connected: Boolean = true,
    status: KetchStatus? = null,
    statusAt: Instant? = null,
    lastSeen: Instant? = null,
  ) = presence(
    entry = entry,
    name = "NAS-Basement",
    detail = "nas.local:8642",
    health = health,
    connected = connected,
    status = status,
    statusAt = statusAt,
    lastSeen = lastSeen,
  )

  @Test
  fun systemLabel_jvmNames_readAsTheSystemCallsThem() {
    assertEquals("macOS arm64", systemLabel("Mac OS X", "aarch64"))
    assertEquals("Linux x64", systemLabel("Linux", "amd64"))
    assertEquals("Windows x64", systemLabel("Windows 11", "x86_64"))
    assertEquals("FreeBSD riscv64", systemLabel("FreeBSD", "riscv64"))
  }

  @Test
  fun shortDuration_eachRange_usesItsLargestUnit() {
    assertEquals("1 min", shortDuration(20.seconds))
    assertEquals("45 min", shortDuration(45.minutes))
    assertEquals("3 h", shortDuration(3.hours + 50.minutes))
    assertEquals("12 d", shortDuration(12.days + 5.hours))
  }

  @Test
  fun deviceMeta_onlineWithStatus_namesVersionSystemAndUptime() {
    val device = device(status = status(uptime = 3.hours.inWholeSeconds), statusAt = now - 1.hours)

    assertEquals("Ketch 0.0.1 · Linux x64 · up 4 h", deviceMeta(device, now))
  }

  @Test
  fun deviceMeta_offline_leavesOutTheUptime() {
    val device = device(health = DeviceHealth.Offline(), status = status(), statusAt = now)

    assertEquals("Ketch 0.0.1 · Linux x64", deviceMeta(device, now))
  }

  @Test
  fun deviceMeta_beforeTheFirstStatus_showsTheAddress() {
    assertEquals("nas.local:8642", deviceMeta(device(), now))
  }

  @Test
  fun deviceMeta_unnamedBeforeTheFirstStatus_isEmpty() {
    val unnamed = device().copy(name = "nas.local:8642")

    assertEquals("", deviceMeta(unnamed, now))
  }

  @Test
  fun deviceProblem_online_isNull() {
    assertNull(deviceProblem(device(), now))
  }

  @Test
  fun deviceProblem_offlineAfterBeingOnline_saysForHowLong() {
    val problem = deviceProblem(
      device(health = DeviceHealth.Offline("Connection refused"), lastSeen = now - 2.hours),
      now
    )

    assertEquals(
      DeviceProblem(ProblemKind.Offline, "Offline for 2 h · retrying", "Connection refused"),
      problem
    )
  }

  @Test
  fun deviceProblem_reconnectingAfterBeingOnline_staysOffline() {
    val problem = deviceProblem(
      device(health = DeviceHealth.Connecting, lastSeen = now - 5.minutes),
      now
    )

    assertEquals(ProblemKind.Offline, problem?.kind)
    assertEquals("Offline for 5 min · retrying", problem?.title)
  }

  @Test
  fun deviceProblem_firstConnection_readsConnecting() {
    val problem = deviceProblem(device(health = DeviceHealth.Connecting), now)

    assertEquals(DeviceProblem(ProblemKind.Connecting, "Connecting…"), problem)
  }

  @Test
  fun deviceProblem_unauthorized_asksForAToken() {
    val problem = deviceProblem(device(health = DeviceHealth.Unauthorized, connected = false), now)

    assertEquals(ProblemKind.Unauthorized, problem?.kind)
    assertEquals("Needs a new access token", problem?.title)
  }

  @Test
  fun deviceProblem_notKeptConnected_isNotAFailure() {
    val device = device(health = DeviceHealth.Offline(), connected = false).copy(watched = false)

    val problem = deviceProblem(device, now)

    assertEquals(ProblemKind.NotConnected, problem?.kind)
    assertEquals("Not connected", problem?.title)
  }

  @Test
  fun deviceProblem_keptConnectedButPastTheLimit_saysWhy() {
    val device = device(health = DeviceHealth.Offline(), connected = false)

    val problem = deviceProblem(device, now)

    assertEquals(ProblemKind.NotConnected, problem?.kind)
    assertEquals(
      "Ketch keeps only a few devices connected at once. It connects to this one while it shows.",
      problem?.detail
    )
  }

  @Test
  fun problemFix_eachProblem_offersItsFix() {
    val offline = device(health = DeviceHealth.Offline(), lastSeen = now - 1.hours)
    val locked = device(health = DeviceHealth.Unauthorized, connected = false)
    val unwatched = device(health = DeviceHealth.Offline(), connected = false).copy(watched = false)
    val pastTheLimit = device(health = DeviceHealth.Offline(), connected = false)
    val connecting = device(health = DeviceHealth.Connecting)

    fun fixOf(device: DevicePresence) = problemFix(device, deviceProblem(device, now)!!)

    assertEquals(ProblemFix.RetryNow, fixOf(offline))
    assertEquals(ProblemFix.EnterToken, fixOf(locked))
    assertEquals(ProblemFix.Connect, fixOf(unwatched))
    assertEquals(ProblemFix.Show, fixOf(pastTheLimit))
    assertNull(fixOf(connecting))
  }

  @Test
  fun storageLabel_disk_namesFreeAndTotalSpace() {
    val gb = 1L shl 30
    val disk = DiskSpace(412 * gb, 926 * gb, "/Users/alex/Downloads")

    assertEquals("412 GB free of 926 GB", storageLabel(disk))
  }

  @Test
  fun networkNames_selected_listsTheSelectedOnes() {
    val networks = NetworkInterfaces(
      supported = true,
      available = listOf(
        NetworkInterfaceInfo("en0", "en0", emptyList()),
        NetworkInterfaceInfo("en7", "en7", emptyList()),
        NetworkInterfaceInfo("utun3", "utun3", emptyList())
      ),
      config = NetworkInterfaceConfig(listOf("en7", "en0")),
    )

    assertEquals(listOf("en7", "en0"), networkNames(networks))
  }

  @Test
  fun networkNames_noneSelected_listsEveryAvailableOne() {
    val networks = NetworkInterfaces(
      supported = true,
      available = listOf(NetworkInterfaceInfo("eth0", "eth0", emptyList())),
    )

    assertEquals(listOf("eth0"), networkNames(networks))
    assertEquals(emptyList(), networkNames(NetworkInterfaces()))
  }

  @Test
  fun sharingLabel_eachServerState_saysWhetherOthersReachIt() {
    val shared = ServerState.Running(ServerConfig(port = 8642, mdnsEnabled = true))
    val quiet = ServerState.Running(ServerConfig(port = 9000, mdnsEnabled = false))
    val local = ServerState.Running(ServerConfig(host = ServerConfig.LOOPBACK_HOST))

    assertEquals("Sharing on :8642 · discoverable", sharingLabel(shared))
    assertEquals("Sharing on :9000", sharingLabel(quiet))
    assertNull(sharingLabel(local))
    assertNull(sharingLabel(ServerState.Stopped))
  }

  @Test
  fun deviceIcon_eachKind_picksItsGlyph() {
    val mac = device(entry = EmbeddedInstance(FakeKetchApi(), "MacBook Pro"))

    assertEquals(KetchIcon.Laptop, deviceIcon(mac, "This Mac"))
    assertEquals(KetchIcon.Phone, deviceIcon(mac, "This phone"))
    assertEquals(KetchIcon.Tablet, deviceIcon(mac, "This iPad"))
    assertEquals(KetchIcon.Server, deviceIcon(device(status = status()), "This Mac"))
    assertEquals(KetchIcon.Desktop, deviceIcon(device(status = status("Windows 11")), "This Mac"))
  }

  @Test
  fun shortPath_homeFoldersAndDocumentTrees_shorten() {
    assertEquals("~/Downloads", shortPath("/Users/alex/Downloads"))
    assertEquals("~/dl", shortPath("/home/sam/dl"))
    assertEquals("~\\Downloads", shortPath("C:\\Users\\sam\\Downloads"))
    assertEquals("/volume1/downloads", shortPath("/volume1/downloads"))
    assertEquals("~", shortPath("/Users/alex"))
    val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FKetch"
    assertEquals("Ketch", shortPath(tree))
  }

  @Test
  fun clipName_longName_endsInAnEllipsis() {
    assertEquals("blender.dmg", clipName("blender.dmg"))
    assertEquals("blender-4.2-macos-a…", clipName("blender-4.2-macos-arm64.dmg"))
  }
}
