package com.linroid.ketch.app.snapshot

import com.linroid.ketch.config.RemoteConfig
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Device names as the device switcher shows them; see [SnapshotHarness] for how to run it. */
class DevicesStateSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun devices_namedAndUnnamedRemotes_showNameOverAddress() {
    val remotes = listOf(
      SampleData.NAS.copy(name = "NAS-Basement"),
      RemoteConfig(host = "192.168.1.42", port = 9000, watch = false),
    )
    appSnapshots(
      name = "devices-named",
      sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Phone),
      data = { SampleData(tasks = SampleData.downloads().tasks, remotes = remotes) },
    ) {
      openDevices()
    }
  }
}
