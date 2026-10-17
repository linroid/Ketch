package com.linroid.ketch.server.mdns

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs [stdinBoundCommand] with `sleep` standing in for `dns-sd`, so it runs wherever `/bin/sh`
 * does (not on Windows, where the tests return at once).
 */
class NativeMdnsRegistrarTest {
  private val started = mutableListOf<ProcessHandle>()

  @AfterTest
  fun killLeftovers() {
    started.forEach { process ->
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
    }
  }

  @Test
  fun stdinBoundCommand_pipeHolderKilled_stopsCommand() {
    if (!hasShell()) return
    // The JVM never holds this pipe: `sleep` writes it, as the server would.
    val (holder, wrapper) = ProcessBuilder.startPipeline(
      listOf(
        ProcessBuilder("sleep", "60"),
        ProcessBuilder(stdinBoundCommand(listOf("sleep", "60")))
          .redirectOutput(ProcessBuilder.Redirect.DISCARD),
      ),
    ).onEach { started += it.toHandle() }
    val child = awaitChild(wrapper)

    holder.destroyForcibly()

    assertExits(wrapper)
    assertGone(child)
  }

  @Test
  fun stdinBoundCommand_stdinClosed_stopsCommand() {
    if (!hasShell()) return
    val wrapper = start(listOf("sleep", "60"))
    val child = awaitChild(wrapper)

    wrapper.outputStream.close()

    assertExits(wrapper)
    assertGone(child)
  }

  @Test
  fun stdinBoundCommand_shellTerminated_stopsCommand() {
    if (!hasShell()) return
    val wrapper = start(listOf("sleep", "60"))
    val child = awaitChild(wrapper)

    wrapper.destroy()

    assertExits(wrapper)
    assertGone(child)
  }

  @Test
  fun stdinBoundCommand_interrupted_runsUntilStdinCloses() {
    if (!hasShell()) return
    val wrapper = start(listOf("sleep", "60"))
    val child = awaitChild(wrapper)

    val kill = ProcessBuilder("kill", "-INT", wrapper.pid().toString(), child.pid().toString())
      .start()
    assertEquals(0, kill.waitFor())
    Thread.sleep(300)
    assertTrue(wrapper.isAlive)
    assertTrue(child.isAlive)

    wrapper.outputStream.close()

    assertExits(wrapper)
    assertGone(child)
  }

  @Test
  fun stdinBoundCommand_shellCharacters_reachCommandUnparsed() {
    if (!hasShell()) return
    val name = "Lin's \"Mac\" \$(echo x); `id` * \\n"
    val command = listOf("printf", "%s|%s\\n", name, "token=none")
    val wrapper = ProcessBuilder(stdinBoundCommand(command))
      .start()
      .also { started += it.toHandle() }

    // Read before closing stdin, which would stop printf.
    val output = wrapper.inputStream.bufferedReader().readLine()
    wrapper.outputStream.close()

    assertExits(wrapper)
    assertEquals("$name|token=none", output)
  }

  private fun hasShell(): Boolean = File("/bin/sh").canExecute()

  private fun start(command: List<String>): Process =
    ProcessBuilder(stdinBoundCommand(command))
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
      .also { started += it.toHandle() }

  private fun awaitChild(wrapper: Process): ProcessHandle {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      wrapper.children().findFirst().orElse(null)?.let { return it }
      Thread.sleep(10)
    }
    fail("The shell started no command")
  }

  private fun assertExits(process: Process) {
    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The shell is still running")
  }

  private fun assertGone(child: ProcessHandle) {
    val exited = runCatching { child.onExit().get(5, TimeUnit.SECONDS) }.isSuccess
    assertTrue(exited, "The command is still running")
    assertFalse(child.isAlive)
  }
}
