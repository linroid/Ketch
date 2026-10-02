package com.linroid.ketch.app.ui.intake

import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.IntakeSeed
import kotlin.test.Test
import kotlin.test.assertEquals

class IntakeHostTest {

  @Test
  fun initialUrl_textWithSeveralLinks_isTheFirstLink() {
    val request = IntakeRequest(text = "see https://a.example/one.iso\nhttps://b.example/two.iso")

    assertEquals("https://a.example/one.iso", initialUrl(request))
  }

  @Test
  fun initialUrl_seed_winsOverTheText() {
    val request = IntakeRequest(
      text = "https://a.example/one.iso",
      seeds = listOf(IntakeSeed(url = "https://b.example/two.iso")),
    )

    assertEquals("https://b.example/two.iso", initialUrl(request))
  }

  @Test
  fun initialUrl_noLink_isEmpty() {
    assertEquals("", initialUrl(IntakeRequest(text = "ubuntu server iso")))
  }
}
