package com.linroid.ketch.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A task lifecycle event a hook can run on.
 *
 * @property id the name `config.toml` and the hook's payload use.
 */
enum class HookEvent(val id: String) {
  /** A download was added to the list while the engine ran (restored tasks are not added). */
  Added(id = "added"),

  /** A download started transferring: after it was added, resumed, retried or restored. */
  Started(id = "started"),

  /** A download finished, once per completion. */
  Completed(id = "completed"),

  /** A download stopped with an error. */
  Failed(id = "failed"),

  /** A download was canceled, which deletes its partial file. */
  Canceled(id = "canceled"),

  /** A download was removed from the list. */
  Removed(id = "removed"),
  ;

  companion object {
    /** The event named [id], ignoring case and surrounding blanks, or `null` for none. */
    fun of(id: String): HookEvent? = entries.firstOrNull { it.id.equals(id.trim(), true) }
  }
}

/**
 * A command or webhook the process that runs this device's downloads (`ketch server`,
 * `ketch mcp --standalone` or the desktop app) runs when a download's [events] happen, persisted
 * as a `[[hooks]]` table of `config.toml`.
 *
 * Hooks are only read from the local file, at launch: no API, `PUT /api/config` included, sets
 * them, as they run programs on this machine.
 *
 * A hook runs [command] when it names one, else posts to [webhook]; one with neither does nothing.
 * Events, origins, categories and hosts are kept as written: names this version does not know are
 * ignored rather than failing the whole file.
 *
 * @property name label for the logs; the program's or the webhook host's name when `null`.
 * @property events the [HookEvent] ids the hook runs on, such as `["completed", "failed"]`.
 * @property command program and arguments to run, never through a shell: the payload is written to
 *   its standard input as JSON and its fields are in `KETCH_*` environment variables.
 * @property workingDirectory directory [command] runs in; the process's own when `null`.
 * @property webhook `http` or `https` URL the payload is posted to as JSON.
 * @property headers extra request headers of [webhook], such as `Authorization`.
 * @property secret key of the HMAC-SHA256 of the body sent as `X-Ketch-Signature: sha256=<hex>`.
 * @property timeoutSeconds how long a command may run, or a webhook request take, before it is
 *   stopped; [DEFAULT_TIMEOUT_SECONDS] when it is not a positive number.
 * @property origins only downloads added from one of these (`browser`, `discover`, `agent`, `app`,
 *   `cli`, the `ketch.origin` request property); every download when empty.
 * @property categories only downloads in one of these category folders; every download when empty.
 * @property hosts only downloads from these hosts or their subdomains; every download when empty.
 * @property enabled whether the hook runs.
 */
@Serializable
data class HookConfig(
  val name: String? = null,
  val events: List<String> = emptyList(),
  val command: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val webhook: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val secret: String? = null,
  @Serializable(with = LenientTimeoutSerializer::class)
  val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
  val origins: List<String> = emptyList(),
  val categories: List<String> = emptyList(),
  val hosts: List<String> = emptyList(),
  val enabled: Boolean = true,
) {
  /** The [events] this version knows. */
  val eventSet: Set<HookEvent> get() = events.mapNotNullTo(LinkedHashSet()) { HookEvent.of(it) }

  /** [timeoutSeconds], or [DEFAULT_TIMEOUT_SECONDS] when it is not positive. */
  val timeout: Int get() = timeoutSeconds.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS

  // Leaves the secret and the headers, which can hold tokens, out of logs that print the config.
  override fun toString(): String =
    "HookConfig(name=$name, events=$events, command=${command.firstOrNull()}, " +
      "webhook=${webhook != null}, timeoutSeconds=$timeoutSeconds, enabled=$enabled)"

  companion object {
    /** Seconds a hook may take when [timeoutSeconds] does not say. */
    const val DEFAULT_TIMEOUT_SECONDS: Int = 30
  }
}

/** Reads `timeoutSeconds`, loading a value that is not a whole number as `0`, the default. */
internal object LenientTimeoutSerializer : KSerializer<Int> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.LenientTimeout", PrimitiveKind.LONG)

  override fun serialize(encoder: Encoder, value: Int) {
    encoder.encodeLong(value.toLong())
  }

  override fun deserialize(decoder: Decoder): Int {
    val seconds = try { decoder.decodeLong() } catch (_: SerializationException) {
      try { decoder.decodeString().trim().toLongOrNull() } catch (_: SerializationException) {
        null
      }
    } ?: return 0
    return seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
  }
}
