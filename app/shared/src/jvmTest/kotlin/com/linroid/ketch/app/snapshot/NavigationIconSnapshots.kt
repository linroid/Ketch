package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.delay
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Still galleries and one loop of animation frames for reviewing the navigation glyphs. */
class NavigationIconSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun navigationIcons_lightAndDark_atCommonSizes() {
    for (theme in SnapshotTheme.entries) {
      snapshot("navigation-icons", GallerySize, theme) { NavigationIconGallery() }
    }
  }

  @Test
  fun navigationIcons_animatedLoop() {
    withScene(540, 390, density = Density(1.5f), content = {
      KetchTheme(darkTheme = true, reduceMotion = false) { NavigationIconGallery() }
    }) {
      for (frame in 0..70) {
        val image = render(frame * 100_000_000L)
        try {
          if (frame > 0) {
            val index = (frame - 1).toString().padStart(3, '0')
            SnapshotHarness.write("navigation-icons-frame-$index", image)
          }
        } finally {
          image.close()
        }
        delay(2)
      }
    }
  }
}

@Composable
private fun NavigationIconGallery() {
  Column(
    modifier = Modifier.fillMaxSize().background(KetchTheme.colors.canvas).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    for (size in listOf(64, 32, 20, 16)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        for (icon in GalleryIcons) KetchIconImage(icon, size = size.dp)
      }
    }
  }
}

private val GallerySize = SnapshotSize(360.dp, 260.dp, KetchDensity.Compact)
private val GalleryIcons = listOf(
  KetchIcon.Active, KetchIcon.Discover, KetchIcon.Devices, KetchIcon.Search
)
