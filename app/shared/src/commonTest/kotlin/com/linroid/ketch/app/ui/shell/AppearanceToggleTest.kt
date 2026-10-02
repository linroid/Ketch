package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceToggleTest {
  @Test
  fun themeModeFor_systemShowsTheOther_savesTheChoice() {
    assertEquals(ThemeMode.Dark, themeModeFor(dark = true, systemDark = false))
    assertEquals(ThemeMode.Light, themeModeFor(dark = false, systemDark = true))
  }

  @Test
  fun themeModeFor_systemShowsTheSame_followsTheSystem() {
    assertEquals(ThemeMode.System, themeModeFor(dark = true, systemDark = true))
    assertEquals(ThemeMode.System, themeModeFor(dark = false, systemDark = false))
  }
}
