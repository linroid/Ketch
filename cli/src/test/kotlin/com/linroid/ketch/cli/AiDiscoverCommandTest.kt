package com.linroid.ketch.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AiDiscoverCommandTest {

  @Test
  fun runAiDiscover_invalidArguments_exitWithUsageStatus() {
    assertEquals(AiDiscoverExit.USAGE, runAiDiscover(listOf("--max-results", "0", "ubuntu")))
    assertEquals(AiDiscoverExit.USAGE, runAiDiscover(listOf("--unknown", "ubuntu")))
  }

  @Test
  fun runAiDiscover_help_exitsWithSuccess() {
    assertEquals(AiDiscoverExit.OK, runAiDiscover(listOf("--help")))
  }

  @Test
  fun runAiDiscover_help_printsOnlyTheUsageToStdout() {
    val (status, stdout) = capturingStdout { runAiDiscover(listOf("--help")) }

    assertEquals(AiDiscoverExit.OK, status)
    // A script that reads stdout gets no banner ahead of what it asked for.
    assertTrue(stdout.startsWith("Usage: ketch ai-discover"), stdout)
  }

  @Test
  fun runAiDiscover_invalidArguments_printNothingToStdout() {
    val (status, stdout) = capturingStdout { runAiDiscover(listOf("--unknown", "ubuntu")) }

    assertEquals(AiDiscoverExit.USAGE, status)
    assertEquals("", stdout)
  }

  /** Runs [block] with stdout captured, and puts the real one back after it. */
  private fun <T> capturingStdout(block: () -> T): Pair<T, String> {
    val original = System.out
    val captured = ByteArrayOutputStream()
    val stream = PrintStream(captured, true, Charsets.UTF_8)
    System.setOut(stream)
    val result = try {
      block().also { assertSame(stream, System.out, "The command puts stdout back") }
    } finally {
      System.setOut(original)
    }
    return result to captured.toString(Charsets.UTF_8)
  }
}
