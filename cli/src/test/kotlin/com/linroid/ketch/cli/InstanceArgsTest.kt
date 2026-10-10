package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class InstanceArgsTest {

  @Test
  fun `add takes the download options, the target and the idempotency key`() {
    val command = run(
      "add", "--server", "nas:8642", "--token", "secret", "--priority", "high",
      "--speed-limit", "1m", "--connections", "2", "-H", "Cookie: sid=1",
      "--referer", "https://example.com/", "--idempotency-key", "job-42", "--json",
      "https://example.com/a.iso", "a.iso",
    )

    assertIs<InstanceCommand.Add>(command)
    assertEquals(Target("nas:8642", "secret"), command.target)
    assertEquals("https://example.com/a.iso", command.url)
    assertEquals("a.iso", command.destination)
    assertEquals(DownloadPriority.HIGH, command.priority)
    assertEquals(SpeedLimit.parse("1m"), command.speedLimit)
    assertEquals(2, command.connections)
    assertEquals(
      mapOf("Cookie" to "sid=1", "Referer" to "https://example.com/"),
      command.headers,
    )
    assertEquals(requestIdFor("job-42"), command.requestId)
    assertEquals(true, command.json)
  }

  @Test
  fun `add without a key leaves the request ID to the command`() {
    val command = assertIs<InstanceCommand.Add>(run("add", "https://example.com/a.iso"))

    assertEquals(null, command.requestId)
    assertEquals(null, command.destination)
  }

  @Test
  fun `add takes a proxy for the download`() {
    val command = run(
      "add", "--proxy", "socks5://me:secret@127.0.0.1:1080", "--proxy-bypass", "*.lan",
      "https://example.com/a.iso",
    )
    assertEquals(
      ProxyConfig.manual("socks5://me:secret@127.0.0.1:1080", bypass = listOf("*.lan")),
      assertIs<InstanceCommand.Add>(command).proxy,
    )
    val direct = run("add", "--no-proxy", "https://example.com/a.iso")
    assertEquals(ProxyConfig.Direct, assertIs<InstanceCommand.Add>(direct).proxy)
    assertIs<InstanceArgs.Invalid>(
      parseInstanceArgs("add", listOf("--proxy", "http://p:8080", "--no-proxy", "https://x.test")),
    )
  }

  @Test
  fun `add needs a URL and takes at most a destination after it`() {
    assertEquals(InstanceArgs.Invalid("missing <url>"), parseInstanceArgs("add", emptyList()))
    assertEquals(
      InstanceArgs.Invalid("unexpected argument 'c'"),
      parseInstanceArgs("add", listOf("https://example.com/a", "b", "c")),
    )
  }

  @Test
  fun `add refuses a header it cannot send without quoting its value`() {
    val parsed = parseInstanceArgs("add", listOf("-H", "Cookie: a\u0007b", "https://x.test/a"))

    val invalid = assertIs<InstanceArgs.Invalid>(parsed)
    assertEquals(false, "a\u0007b" in invalid.message)
  }

  @Test
  fun `options of other commands are unknown`() {
    assertEquals(
      InstanceArgs.Invalid("unknown option '--json'"),
      parseInstanceArgs("pause", listOf("--json", "abc")),
    )
    assertEquals(
      InstanceArgs.Invalid("unknown option '--all'"),
      parseInstanceArgs("list", listOf("--all")),
    )
    assertEquals(
      InstanceArgs.Invalid("unknown option '--priority'"),
      parseInstanceArgs("watch", listOf("--priority", "high")),
    )
  }

  @Test
  fun `pause and resume take task IDs or --all, not both`() {
    assertEquals(
      InstanceCommand.Pause(Target(), listOf("3f2a", "9c1e")),
      run("pause", "3f2a", "9c1e"),
    )
    assertEquals(InstanceCommand.Resume(Target(), emptyList(), all = true), run("resume", "--all"))
    assertEquals(InstanceArgs.Invalid("missing <task-id>"), parseInstanceArgs("pause", emptyList()))
    assertEquals(
      InstanceArgs.Invalid("give task IDs or --all, not both"),
      parseInstanceArgs("resume", listOf("--all", "3f2a")),
    )
  }

  @Test
  fun `watch follows every task without IDs`() {
    assertEquals(InstanceCommand.Watch(Target(), emptyList()), run("watch"))
    assertEquals(InstanceCommand.Watch(Target(), listOf("3f2a")), run("watch", "3f2a"))
  }

  @Test
  fun `a double dash ends the options`() {
    val command = assertIs<InstanceCommand.Add>(run("add", "--", "-file.iso"))

    assertEquals("-file.iso", command.url)
  }

  @Test
  fun `a value option at the end asks for its value`() {
    assertEquals(
      InstanceArgs.Invalid("--server requires a value"),
      parseInstanceArgs("list", listOf("--server")),
    )
  }

  @Test
  fun `help is shown without checking the arguments after it`() {
    assertEquals(InstanceArgs.Help, parseInstanceArgs("pause", listOf("-h", "--bogus")))
  }

  @Test
  fun `requestIdFor keeps a UUID and derives one from any other key`() {
    assertEquals(
      "6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a",
      requestIdFor(" 6F1C2B0E-8A1D-4C5E-9B7A-3D2F1E0C9B8A "),
    )
    val derived = requestIdFor("job-42")
    assertEquals(derived, requestIdFor("job-42"))
    assertNotEquals(derived, requestIdFor("job-43"))
    assertEquals(36, derived.length)
  }

  @Test
  fun `an empty idempotency key is refused`() {
    assertEquals(
      InstanceArgs.Invalid("--idempotency-key must not be empty"),
      parseInstanceArgs("add", listOf("--idempotency-key", " ", "https://x.test/a")),
    )
  }

  private fun run(command: String, vararg args: String): InstanceCommand =
    assertIs<InstanceArgs.Run>(parseInstanceArgs(command, args.toList())).command
}
