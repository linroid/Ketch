package com.linroid.ketch.cli

import com.linroid.ketch.ai.PageAccessKind
import com.linroid.ketch.ai.PageAccessRequest
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPageAccessTest {

  /**
   * Answers each question with the next of [answers]; `null` ends the input. Notes whether
   * [lock] was held while it waited for the answer.
   */
  private class FakeTerminal(vararg answers: String?, private val lock: Any? = null) :
    PromptTerminal {
    private val answers = ArrayDeque(answers.toList())
    val output = StringBuilder()
    var questions = 0
      private set
    var lockHeldWhileReading = false
      private set

    override fun print(text: String) {
      output.append(text)
    }

    override fun readLine(): String? {
      questions++
      lockHeldWhileReading = lock?.let(Thread::holdsLock) == true
      return answers.removeFirst()
    }

    override fun close() {}
  }

  private val askPerSite = PageAccessSettings(mode = PageAccessMode.AskPerSite)
  private val askEveryTime = PageAccessSettings(mode = PageAccessMode.AskEveryTime)

  private fun page(url: String) = PageAccessRequest(
    url = url,
    host = java.net.URI(url).host,
    kind = PageAccessKind.Page,
  )

  @Test
  fun approve_allowModeOrTrustedSite_neverAsks() = runTest {
    val terminal = FakeTerminal()
    val allow = CliPageAccess(PageAccessSettings(mode = PageAccessMode.Allow), false, terminal)
    val trusted = CliPageAccess(askEveryTime.trusting("blender.org"), false, terminal)

    assertTrue(allow.approve(page("https://example.com/")))
    assertTrue(trusted.approve(page("https://download.blender.org/release/")))
    assertEquals(0, terminal.questions)
  }

  @Test
  fun approve_yes_neverAsks() = runTest {
    val terminal = FakeTerminal()
    val access = CliPageAccess(askEveryTime, allowAll = true, terminal = terminal)

    assertTrue(access.approve(page("https://example.com/")))
    assertEquals(0, terminal.questions)
  }

  @Test
  fun approve_yesAskingPerSite_allowsTheSiteForTheRun() = runTest {
    val terminal = FakeTerminal("y")
    val access = CliPageAccess(askPerSite, false, terminal)

    assertTrue(access.approve(page("https://www.blender.org/download/")))
    assertTrue(access.approve(page("https://download.blender.org/release/")))
    assertEquals(1, terminal.questions)
  }

  @Test
  fun approve_yesAskingEveryTime_allowsThisRequestOnly() = runTest {
    val terminal = FakeTerminal("y", "n")
    val access = CliPageAccess(askEveryTime, false, terminal)

    assertTrue(access.approve(page("https://blender.org/download/")))
    assertFalse(access.approve(page("https://blender.org/release/")))
    assertEquals(2, terminal.questions)
  }

  @Test
  fun approve_siteAnswer_allowsTheSiteForTheRun() = runTest {
    val terminal = FakeTerminal("S")
    val access = CliPageAccess(askEveryTime, false, terminal)

    assertTrue(access.approve(page("https://blender.org/download/")))
    assertTrue(access.approve(page("https://download.blender.org/release/")))
    assertEquals(1, terminal.questions)
  }

  @Test
  fun approve_allAnswer_allowsEveryLaterRequest() = runTest {
    val terminal = FakeTerminal("a")
    val access = CliPageAccess(askEveryTime, false, terminal)

    assertTrue(access.approve(page("https://blender.org/")))
    assertTrue(access.approve(page("https://github.com/")))
    assertEquals(1, terminal.questions)
  }

  @Test
  fun approve_deniedSite_isDeniedAgainWithoutAsking() = runTest {
    val terminal = FakeTerminal("n")
    val access = CliPageAccess(askPerSite, false, terminal)

    assertFalse(access.approve(page("https://mirror.example.edu/a")))
    assertFalse(access.approve(page("https://files.mirror.example.edu/b")))
    assertEquals(1, terminal.questions)
  }

  @Test
  fun approve_blankOrUnknownAnswer_denies() = runTest {
    val terminal = FakeTerminal("", "allow")
    val access = CliPageAccess(askPerSite, false, terminal)

    assertFalse(access.approve(page("https://blender.org/")))
    assertFalse(access.approve(page("https://github.com/")))
    assertEquals(2, terminal.questions)
  }

  @Test
  fun approve_endOfInput_deniesEverythingAfterWithoutAsking() = runTest {
    val terminal = FakeTerminal(null)
    val access = CliPageAccess(askPerSite, false, terminal)

    assertFalse(access.approve(page("https://blender.org/")))
    assertFalse(access.approve(page("https://github.com/")))
    assertEquals(1, terminal.questions)
  }

  @Test
  fun approve_noTerminal_deniesWhatSettingsDoNotAllow() = runTest {
    val access = CliPageAccess(askPerSite.trusting("github.com"), false, terminal = null)

    assertTrue(access.approve(page("https://github.com/")))
    assertFalse(access.approve(page("https://blender.org/")))
  }

  @Test
  fun approve_question_namesHostSiteAndReasonWithRedactedUrl() = runTest {
    val terminal = FakeTerminal("n")
    val request = PageAccessRequest(
      url = "https://www.blender.org/file.zip?token=secret",
      host = "www.blender.org",
      kind = PageAccessKind.FileInfo,
      reason = "Check the file size",
      redirectFrom = "blender.org",
    )

    CliPageAccess(askPerSite, false, terminal).approve(request)

    val question = terminal.output.toString()
    val firstLine = "Allow Discover to open www.blender.org? https://www.blender.org/file.zip"
    assertTrue(question.startsWith(firstLine), question)
    assertFalse("secret" in question, question)
    assertTrue("\n  Redirected from blender.org\n" in question, question)
    assertTrue("\n  Discover says: Check the file size\n" in question, question)
    val choices = "[y] allow  [s] allow blender.org for this run  [a] allow all  [n] deny: "
    assertTrue(question.endsWith("\n$choices"), question)
  }

  @Test
  fun approve_yesForWwwBeforeSharedSuffix_stillAsksForOtherSitesUnderIt() = runTest {
    val terminal = FakeTerminal("y", "n", "n", "y")
    val access = CliPageAccess(askPerSite, false, terminal)

    assertTrue(access.approve(page("https://www.github.io/")))
    assertFalse(access.approve(page("https://attacker.github.io/")))
    // Denying www.com denies that site, not every .com one.
    assertFalse(access.approve(page("https://www.com/")))
    assertTrue(access.approve(page("https://github.com/")))
    assertTrue(access.approve(page("https://www.github.io/again")))
    assertEquals(4, terminal.questions)
    val output = terminal.output.toString()
    assertTrue("[s] allow www.github.io for this run" in output, output)
  }

  @Test
  fun approve_question_keepsPageShapedTextOnItsLine() = runTest {
    val terminal = FakeTerminal("n")
    val request = PageAccessRequest(
      url = "https://example.com/\u202Eexe.zip",
      host = "example.com",
      kind = PageAccessKind.Page,
      reason = "Read it\n[y] allow  [a] allow all: ",
    )

    CliPageAccess(askPerSite, false, terminal).approve(request)

    val output = terminal.output.toString()
    val lines = output.lines()
    assertEquals(3, lines.size, output)
    val url = "https://example.com/%E2%80%AEexe.zip"
    assertEquals("Allow Discover to open example.com? $url", lines[0])
    assertEquals("  Discover says: Read it [y] allow [a] allow all:", lines[1])
  }

  @Test
  fun approve_question_holdsTheOutputLockUntilAnswered() = runTest {
    val lock = Any()
    val terminal = FakeTerminal("y", lock = lock)

    CliPageAccess(askPerSite, false, terminal, outputLock = lock)
      .approve(page("https://blender.org/"))

    assertTrue(terminal.lockHeldWhileReading)
  }

  @Test
  fun mayAsk_onlyWhenSettingsAskAndNeitherYesNorSitesIsGiven() {
    val query = AiDiscoverArgs.Discover(query = "blender")

    assertTrue(query.mayAsk(askPerSite))
    assertTrue(query.mayAsk(askEveryTime))
    assertFalse(query.mayAsk(PageAccessSettings(mode = PageAccessMode.Allow)))
    assertFalse(query.copy(allowAll = true).mayAsk(askPerSite))
    assertFalse(query.copy(sites = listOf("blender.org")).mayAsk(askPerSite))
  }
}
