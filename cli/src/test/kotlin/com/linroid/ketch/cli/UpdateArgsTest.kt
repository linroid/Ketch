package com.linroid.ketch.cli

import com.linroid.ketch.updater.ReleaseVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateArgsTest {
  @Test
  fun parseUpdateArgs_none_installsTheLatest() {
    assertEquals(UpdateArgs.Update(), parseUpdateArgs(emptyList()))
  }

  @Test
  fun parseUpdateArgs_checkAndVersion() {
    assertEquals(
      UpdateArgs.Update(checkOnly = true, version = ReleaseVersion(0, 0, 1, "rc15")),
      parseUpdateArgs(listOf("--version", "v0.0.1-rc15", "--check")),
    )
  }

  @Test
  fun parseUpdateArgs_badVersion_isInvalid() {
    assertIs<UpdateArgs.Invalid>(parseUpdateArgs(listOf("--version", "latest")))
    assertIs<UpdateArgs.Invalid>(parseUpdateArgs(listOf("--version")))
  }

  @Test
  fun parseUpdateArgs_unknownOption_isInvalid() {
    assertIs<UpdateArgs.Invalid>(parseUpdateArgs(listOf("--force")))
  }

  @Test
  fun parseUpdateArgs_help_winsOverOtherOptions() {
    assertEquals(UpdateArgs.Help, parseUpdateArgs(listOf("--check", "-h")))
  }
}
