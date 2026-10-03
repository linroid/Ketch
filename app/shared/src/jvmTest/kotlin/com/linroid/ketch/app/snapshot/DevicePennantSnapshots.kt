package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.DeviceType
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Device pennants with the glyph of each [DeviceType] at every size, a row per type after the
 * glyph drawn large, and the monogram of a device of unknown type; see [SnapshotHarness] for how
 * to run it.
 */
class DevicePennantSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun devicePennant_types_showTheirGlyphAtEverySize() {
    for (theme in SnapshotTheme.entries) {
      snapshot("device-pennants", Gallery, theme) {
        Column(
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          DeviceType.entries.forEachIndexed { row, type ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
            ) {
              KetchIconImage(type.glyph, size = 40.dp)
              Sizes.forEach { size ->
                DevicePennant(
                  deviceId = "device-$row",
                  name = type.name,
                  size = size,
                  fallbackType = type,
                )
              }
              DevicePennant(
                deviceId = "device-$row",
                name = type.name,
                health = DeviceHealth.Live,
                failures = row % 2 * 3,
                fallbackType = type,
              )
            }
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
          ) {
            // A device of unknown type shows its monogram.
            Sizes.forEach { DevicePennant(deviceId = "nas", name = "NAS-Basement", size = it) }
          }
        }
      }
    }
  }
}

private val Sizes = listOf(
  DevicePennantDefaults.XSmall,
  DevicePennantDefaults.Small,
  DevicePennantDefaults.Medium,
  DevicePennantDefaults.Large,
  DevicePennantDefaults.XLarge,
)

private val Gallery = SnapshotSize(420.dp, 460.dp, KetchDensity.Compact)
