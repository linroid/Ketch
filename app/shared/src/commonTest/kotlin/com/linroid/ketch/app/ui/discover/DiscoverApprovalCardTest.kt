package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.app.state.PageAccessChoice
import com.linroid.ketch.config.PageAccessMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiscoverApprovalCardTest {
  private val mirror = "https://mirror.example.edu/blender/release/blender-4.2.1-macos-arm64.dmg"

  @Test
  fun approvalChoices_askForEachNewSite_allowsTheSiteForTheChatFirst() {
    val choices = approvalChoices(PageAccessMode.AskPerSite)

    assertEquals(PageAccessChoice.AllowSite, choices.primary)
    assertEquals(
      listOf(PageAccessChoice.AlwaysAllow, PageAccessChoice.AllowAll),
      choices.quiet,
    )
  }

  @Test
  fun approvalChoices_askEveryTime_allowsOnceFirstAndOffersTheSiteForTheChat() {
    val choices = approvalChoices(PageAccessMode.AskEveryTime)

    assertEquals(PageAccessChoice.AllowOnce, choices.primary)
    assertEquals(
      listOf(PageAccessChoice.AllowSite, PageAccessChoice.AlwaysAllow, PageAccessChoice.AllowAll),
      choices.quiet,
    )
  }

  @Test
  fun approvalChoices_allowAutomatically_readsAsAskingForEachNewSite() {
    // Only a request that waited while the mode changed shows a card then.
    assertEquals(
      approvalChoices(PageAccessMode.AskPerSite),
      approvalChoices(PageAccessMode.Allow),
    )
  }

  @Test
  fun approvalChoices_everyMode_neverRepeatsDenyOrThePrimaryAmongTheQuietAnswers() {
    for (mode in PageAccessMode.entries) {
      val choices = approvalChoices(mode)

      assertEquals(choices.quiet.distinct(), choices.quiet, "$mode repeats an answer")
      assertFalse(PageAccessChoice.Deny in choices.quiet, "$mode offers Deny twice")
      assertFalse(choices.primary in choices.quiet, "$mode offers its first answer twice")
    }
  }

  @Test
  fun shortenUrl_fits_isUnchanged() {
    val url = "https://www.blender.org/download/"

    assertEquals(url, shortenUrl(url) { it.length <= 40 })
  }

  @Test
  fun shortenUrl_queryDoesNotFit_dropsTheQueryFirst() {
    val url = "$mirror?session=***&token=***"

    assertEquals("$mirror?…", shortenUrl(url) { it.length <= mirror.length + 2 })
  }

  @Test
  fun shortenUrl_pathDoesNotFit_keepsTheHostAndTheFileName() {
    val shown = shortenUrl("$mirror?session=***") { it.length <= 50 }

    assertEquals("https://mirror.example.edu/blende…acos-arm64.dmg?…", shown)
  }

  @Test
  fun shortenUrl_hostWiderThanTheLine_keepsTheHostWhole() {
    val url = "https://objects.githubusercontent-production.example-cdn.com/asset/1/file.dmg"

    val shown = shortenUrl(url) { it.length <= 30 }

    assertEquals("https://objects.githubusercontent-production.example-cdn.com…", shown)
  }

  @Test
  fun shortenUrl_noPath_isLeftForTheLineToWrap() {
    val url = "https://objects.githubusercontent-production.example-cdn.com"

    assertEquals(url, shortenUrl(url) { it.length <= 30 })
  }
}
