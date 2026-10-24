package com.linroid.ketch.app.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.withScene
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchThemeZoomTest {
  @Test
  fun ketchTheme_zoom_drawsTheContentLargerAndKeepsItsState() {
    var zoom by mutableStateOf(1f)
    var size = IntSize.Zero
    var created = 0
    withScene(
      width = 800,
      height = 800,
      density = Density(2f),
      content = {
        KetchTheme(zoom = zoom) {
          remember { created++ }
          Box(Modifier.size(100.dp).onSizeChanged { size = it })
        }
      },
    ) {
      frames(2)
      assertEquals(IntSize(200, 200), size)

      zoom = 1.5f
      frames(2)
      assertEquals(IntSize(300, 300), size)
      assertEquals(1, created)
    }
  }
}
