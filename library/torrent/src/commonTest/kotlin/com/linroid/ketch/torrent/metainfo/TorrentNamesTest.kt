package com.linroid.ketch.torrent.metainfo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentNamesTest {
  @Test
  fun diskComponent_namesSafeEverywhere_keptAsTheyAre() {
    // Tasks saved before names were cleaned find their files under these names.
    for (name in listOf("readme.txt", "100% Pure.mp3", "~draft", " leading", "COM0", "音楽.flac",
      "..data", ".hidden", "界".repeat(85))) {
      assertEquals(name, diskComponent(name))
    }
  }

  @Test
  fun diskComponent_unsafeNames_cleaned() {
    assertEquals("Who Are You_.flac", diskComponent("Who Are You?.flac"))
    assertEquals("Mission_ Impossible", diskComponent("Mission: Impossible"))
    assertEquals("Greatest Hits Vol", diskComponent("Greatest Hits Vol."))
    assertEquals("R.E.M", diskComponent("R.E.M."))
    assertEquals("trailing", diskComponent("trailing "))
    assertEquals("_aux.c", diskComponent("aux.c"))
    assertEquals("a_b_c_d_e_f_g", diskComponent("a<b>c\"d|e*f?g"))
    // Nothing is left of a name of dots.
    assertEquals("_", diskComponent("..."))
  }

  @Test
  fun diskComponent_nameOver255Bytes_cutKeepingItsExtension() {
    val name = diskComponent("界".repeat(100) + ".flac")

    assertEquals("界".repeat(83) + ".flac", name)
    assertTrue(isDiskComponent(name))
  }

  @Test
  fun validateTorrentComponent_traversal_rejected() {
    for (name in listOf("", ".", "..", "a/b", "a\\b", "nul\u0000byte", "tab\there")) {
      assertFailsWith<IllegalArgumentException>(name) { validateTorrentComponent(name) }
    }
  }

  @Test
  fun validateTorrentComponent_namesOnlyUnsafeOnSomePlatforms_accepted() {
    for (name in listOf("Who?.flac", "a:b", "trailing.", "trailing ", "aux.c", "x".repeat(300))) {
      validateTorrentComponent(name)
    }
  }
}
