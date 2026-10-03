package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.components.DeviceType
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.LocalDeviceKind
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceTypesTest {

  private fun remote(os: String?) = RemoteInstance(
    instance = FakeKetchApi("NAS"),
    remoteConfig = RemoteConfig(host = "nas.local", os = os),
    connectionState = MutableStateFlow(ConnectionState.Disconnected("Offline")),
  )

  @Test
  fun deviceType_embeddedDevice_followsTheFormFactorTheAppRunsOn() {
    val embedded = EmbeddedInstance(FakeKetchApi(), "MacBook Pro")

    assertEquals(DeviceType.Laptop, deviceType(embedded, LocalDeviceKind.Mac))
    assertEquals(DeviceType.Desktop, deviceType(embedded, LocalDeviceKind.Computer))
    assertEquals(DeviceType.Phone, deviceType(embedded, LocalDeviceKind.Phone))
    assertEquals(DeviceType.Tablet, deviceType(embedded, LocalDeviceKind.IPad))
    assertEquals(DeviceType.Browser, deviceType(embedded, LocalDeviceKind.Browser))
  }

  @Test
  fun deviceType_remoteDevice_followsTheSystemItLastReported() {
    // The form factor of the app is not the remote's.
    assertEquals(DeviceType.Phone, deviceType(remote("Android 15"), LocalDeviceKind.Mac))
    assertNull(deviceType(remote(null), LocalDeviceKind.Mac))
  }

  @Test
  fun systemDeviceType_reportedSystems_mapToTheirKind() {
    assertEquals(DeviceType.Laptop, systemDeviceType("Mac OS X"))
    assertEquals(DeviceType.Desktop, systemDeviceType("Windows 11"))
    assertEquals(DeviceType.Phone, systemDeviceType("Android 15"))
    assertEquals(DeviceType.Phone, systemDeviceType("iOS Version 18.0 (Build 22A3354)"))
    assertEquals(DeviceType.Tablet, systemDeviceType("iPadOS 18.0"))
    assertEquals(DeviceType.Server, systemDeviceType("Linux"))
    assertEquals(DeviceType.Server, systemDeviceType("FreeBSD"))
    assertEquals(DeviceType.Server, systemDeviceType("Node.js"))
    assertNull(systemDeviceType("  "))
  }
}
