package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookConfigTest {

  @Test
  fun decode_handWrittenHooks_readsCommandsAndWebhooks() {
    val decoded = ConfigStore.decode(
      """
      |[download]
      |maxConcurrentDownloads = 3
      |
      |[[hooks]]
      |events = ["completed", "failed"]
      |command = ["/usr/local/bin/notify", "--title", "Ketch"]
      |workingDirectory = "/tmp"
      |timeoutSeconds = 10
      |origins = ["browser"]
      |
      |[[hooks]]
      |name = "Home Assistant"
      |events = ["added"]
      |webhook = "https://ha.local/api/webhook/ketch"
      |secret = "s3cret"
      |hosts = ["example.com"]
      |categories = ["Videos"]
      |headers = { Authorization = "Bearer abc" }
      """.trimMargin(),
    )

    val (command, webhook) = decoded.hooks
    assertEquals(listOf("/usr/local/bin/notify", "--title", "Ketch"), command.command)
    assertEquals(setOf(HookEvent.Completed, HookEvent.Failed), command.eventSet)
    assertEquals("/tmp", command.workingDirectory)
    assertEquals(10, command.timeout)
    assertEquals(listOf("browser"), command.origins)
    assertEquals("https://ha.local/api/webhook/ketch", webhook.webhook)
    assertEquals(mapOf("Authorization" to "Bearer abc"), webhook.headers)
    assertEquals("s3cret", webhook.secret)
    assertEquals(HookConfig.DEFAULT_TIMEOUT_SECONDS, webhook.timeout)
    assertEquals(3, decoded.download.maxConcurrentDownloads)
  }

  @Test
  fun decode_unknownEventsAndUnreadableTimeout_areIgnored() {
    val decoded = ConfigStore.decode(
      """
      |[[hooks]]
      |events = ["completed", "exploded", " FAILED "]
      |command = ["true"]
      |timeoutSeconds = "soon"
      """.trimMargin(),
    )

    val hook = decoded.hooks.single()
    assertEquals(setOf(HookEvent.Completed, HookEvent.Failed), hook.eventSet)
    assertEquals(HookConfig.DEFAULT_TIMEOUT_SECONDS, hook.timeout)
    assertTrue(hook.enabled)
  }

  @Test
  fun decode_configWithoutHooks_hasNone() {
    assertEquals(emptyList(), ConfigStore.decode("name = \"laptop\"").hooks)
  }

  @Test
  fun encode_hooks_readBackTheSame() {
    val config = KetchConfig(
      name = "nas",
      hooks = listOf(
        HookConfig(events = listOf("completed"), command = listOf("sh", "-c", "echo \"done\"")),
        HookConfig(
          name = "chat",
          events = listOf("failed"),
          webhook = "https://example.com/hook",
          headers = mapOf("Authorization" to "Bearer abc", "X-Team" to "ops"),
          secret = "s3cret",
          timeoutSeconds = 5,
          enabled = false,
        ),
      ),
      remotes = listOf(RemoteConfig(host = "den-pc")),
    )

    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
    val decoded = ConfigStore.decode(encoded)

    assertEquals(config, decoded, encoded)
  }

  @Test
  fun toString_leavesOutSecretsAndHeaders() {
    val text = HookConfig(
      webhook = "https://example.com/hook?token=abc",
      headers = mapOf("Authorization" to "Bearer abc"),
      secret = "s3cret",
    ).toString()
    assertFalse("abc" in text, text)
    assertFalse("s3cret" in text, text)
  }
}
