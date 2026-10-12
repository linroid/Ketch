package com.linroid.ketch.cli

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.SpeedLimit
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The commands that work on a running instance rather than an engine of their own. */
internal val INSTANCE_COMMANDS = setOf("add", "list", "pause", "resume", "watch")

/**
 * Where an attaching command finds its instance: `--server` and `--token`, or what
 * [InstanceLocator] finds when they are `null`.
 */
internal data class Target(val server: String? = null, val token: String? = null)

/** A command that works on a running instance. */
internal sealed interface InstanceCommand {
  val target: Target

  /** `ketch add`: adds a download and prints its task ID. */
  data class Add(
    override val target: Target,
    val url: String,
    val destination: String?,
    val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val connections: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val requestId: String? = null,
    val json: Boolean = false,
    /** `--proxy` or `--no-proxy`, in place of the instance's proxy; `null` for that one. */
    val proxy: Proxy? = null,
    /** `--checksum`: the digest the file must have; `null` checks only a published one. */
    val checksum: Checksum? = null,
  ) : InstanceCommand

  /** `ketch list`: prints the tasks. */
  data class ListTasks(override val target: Target, val json: Boolean = false) : InstanceCommand

  /** `ketch pause`: pauses the tasks [ids] name, or every waiting and running one. */
  data class Pause(
    override val target: Target,
    val ids: List<String>,
    val all: Boolean = false,
  ) : InstanceCommand

  /** `ketch resume`: resumes the tasks [ids] name, or every one paused until resumed. */
  data class Resume(
    override val target: Target,
    val ids: List<String>,
    val all: Boolean = false,
  ) : InstanceCommand

  /** `ketch watch`: prints task changes as JSON lines, of [ids] only when given. */
  /**
   * @property interval how often the progress of downloading tasks updates, or `null` for the
   *   client's default
   */
  data class Watch(
    override val target: Target,
    val ids: List<String>,
    val interval: Duration? = null,
  ) : InstanceCommand
}

/** The arguments of an attaching command. */
internal sealed interface InstanceArgs {
  /** `--help` or `-h` was given. */
  data object Help : InstanceArgs

  /** The arguments cannot be used; [message] says why. */
  data class Invalid(val message: String) : InstanceArgs

  data class Run(val command: InstanceCommand) : InstanceArgs
}

private val targetOptions = setOf("--server", "--token")

private val addValueOptions =
  targetOptions + HEADER_OPTIONS + PROXY_OPTIONS +
    setOf("--speed-limit", "--priority", "--connections", "--idempotency-key", CHECKSUM_OPTION)

/**
 * Parses the arguments of [command], one of [INSTANCE_COMMANDS], given after its name and
 * without the global flags.
 */
internal fun parseInstanceArgs(command: String, args: List<String>): InstanceArgs {
  var server: String? = null
  var token: String? = null
  var json = false
  var all = false
  var speedLimit = SpeedLimit.Unlimited
  var priority = DownloadPriority.NORMAL
  var connections = 0
  var requestId: String? = null
  var checksum: Checksum? = null
  val headers = HeaderOptions()
  val proxy = ProxyOptions()
  val positional = mutableListOf<String>()

  var interval: Duration? = null
  val valueOptions = when (command) {
    "add" -> addValueOptions
    "watch" -> targetOptions + "--interval"
    else -> targetOptions
  }
  val flags = when (command) {
    "add" -> setOf("--json", NO_PROXY_OPTION)
    "list" -> setOf("--json")
    "pause", "resume" -> setOf("--all")
    else -> emptySet()
  }

  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--help" || arg == "-h" -> return InstanceArgs.Help
      arg == "--" -> {
        positional += args.drop(i + 1)
        break
      }
      arg in valueOptions -> {
        val value = args.getOrNull(++i) ?: return InstanceArgs.Invalid("$arg requires a value")
        when (arg) {
          "--server" -> server = value
          "--token" -> token = value
          "--speed-limit" -> speedLimit = SpeedLimit.parse(value)
            ?: return InstanceArgs.Invalid("invalid speed limit '$value'")
          "--priority" -> priority = parsePriority(value)
            ?: return InstanceArgs.Invalid(
              "invalid priority '$value' (valid values: low, normal, high, urgent)"
            )
          "--connections" -> connections = value.toIntOrNull()?.takeIf { it >= 0 }
            ?: return InstanceArgs.Invalid("--connections must be 0 (automatic) or more")
          "--idempotency-key" -> requestId = value.takeIf { it.isNotBlank() }?.let(::requestIdFor)
            ?: return InstanceArgs.Invalid("--idempotency-key must not be empty")
          CHECKSUM_OPTION -> checksum = parseChecksumOption(value).getOrElse {
            return InstanceArgs.Invalid(it.message ?: "invalid checksum")
          }
          "--interval" -> interval = parseInterval(value)
            ?: return InstanceArgs.Invalid("invalid interval '$value' (e.g. 500ms, 2s)")
          in PROXY_OPTIONS -> proxy.apply(arg, value)?.let { return InstanceArgs.Invalid(it) }
          else -> headers.apply(arg, value)?.let { return InstanceArgs.Invalid(it) }
        }
      }
      arg in flags -> when (arg) {
        "--json" -> json = true
        "--all" -> all = true
        NO_PROXY_OPTION -> proxy.noProxy()
      }
      arg.length > 1 && arg.startsWith("-") -> return InstanceArgs.Invalid("unknown option '$arg'")
      else -> positional += arg
    }
    i++
  }

  val target = Target(server, token)
  val parsed = when (command) {
    "add" -> {
      val url = positional.getOrNull(0) ?: return InstanceArgs.Invalid("missing <url>")
      if (positional.size > 2) {
        return InstanceArgs.Invalid("unexpected argument '${positional[2]}'")
      }
      InstanceCommand.Add(
        target = target,
        url = url,
        destination = positional.getOrNull(1),
        speedLimit = speedLimit,
        priority = priority,
        connections = connections,
        headers = headers.build().getOrElse {
          return InstanceArgs.Invalid(it.message ?: "invalid header")
        },
        requestId = requestId,
        json = json,
        proxy = proxy.build().getOrElse {
          return InstanceArgs.Invalid(it.message ?: "invalid proxy")
        },
        checksum = checksum,
      )
    }
    "list" -> {
      positional.firstOrNull()?.let { return InstanceArgs.Invalid("unexpected argument '$it'") }
      InstanceCommand.ListTasks(target, json)
    }
    "pause", "resume" -> {
      if (all && positional.isNotEmpty()) {
        return InstanceArgs.Invalid("give task IDs or --all, not both")
      }
      if (!all && positional.isEmpty()) return InstanceArgs.Invalid("missing <task-id>")
      if (command == "pause") {
        InstanceCommand.Pause(target, positional, all)
      } else {
        InstanceCommand.Resume(target, positional, all)
      }
    }
    "watch" -> InstanceCommand.Watch(target, positional, interval)
    else -> throw IllegalArgumentException("Not an instance command: $command")
  }
  return InstanceArgs.Run(parsed)
}

/**
 * The [com.linroid.ketch.api.DownloadRequest.requestId] for the idempotency [key]: a UUID as it
 * is, in lower case, and any other text the name-based UUID made from it, so running a command
 * again with the same key submits the same request ID.
 */
internal fun requestIdFor(key: String): String {
  val trimmed = key.trim()
  if (UUID_PATTERN.matches(trimmed)) return trimmed.lowercase()
  return UUID.nameUUIDFromBytes("ketch-cli:$trimmed".encodeToByteArray()).toString()
}

private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

/**
 * A positive [Duration] such as `500ms`, `2s` or `1m 30s`, or a plain number of seconds; `null`
 * for anything else.
 */
internal fun parseInterval(value: String): Duration? {
  val interval = Duration.parseOrNull(value) ?: value.toDoubleOrNull()?.seconds
  return interval?.takeIf { it.isPositive() && it.isFinite() }
}
