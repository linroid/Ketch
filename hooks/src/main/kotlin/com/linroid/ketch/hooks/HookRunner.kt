package com.linroid.ketch.hooks

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.config.HookConfig
import com.linroid.ketch.config.HookEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Runs the [hooks] of `config.toml` on the download events of the engine it is [attach]ed to.
 *
 * Only the process that runs the downloads of a config directory, the one holding its
 * `instance.lock`, should run one, so that an app attached to `ketch server` never runs a hook
 * twice. Events run in the background, at most [MAX_CONCURRENT] at once and [QUEUE_CAPACITY]
 * waiting, so a slow hook never holds back a download; events beyond that are dropped with a
 * warning. Failures are logged at warn, without the webhook URL, headers, secret or arguments.
 *
 * Each event fires once for each change it names: `completed` once per completion, `started`
 * each time a download starts transferring. Tasks the engine restores at launch are not `added`.
 */
class HookRunner internal constructor(
  hooks: List<HookConfig>,
  private val commands: CommandRunner,
  private val webhooks: WebhookSender,
  private val clock: Clock,
  dispatcher: CoroutineDispatcher,
) : AutoCloseable {
  /**
   * @param hooks the hooks to run; disabled ones and ones that can never run are left out.
   * @param launcher starts command hooks' processes.
   */
  constructor(
    hooks: List<HookConfig>,
    launcher: ProcessLauncher = ProcessLauncher.System,
  ) : this(hooks, CommandRunner(launcher), WebhookSender(), Clock.System, Dispatchers.IO)

  private val hooks: List<Hook> = hooks.mapIndexedNotNull { index, config ->
    Hook.of(index + 1, config)
  }

  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val deliveries = Channel<Delivery>(QUEUE_CAPACITY)

  /** Whether any hook can run. */
  val isEmpty: Boolean get() = hooks.isEmpty()

  /** How many hooks can run. */
  val size: Int get() = hooks.size

  init {
    repeat(MAX_CONCURRENT) {
      scope.launch {
        for (delivery in deliveries) run(delivery)
      }
    }
  }

  /**
   * Starts running the hooks on [ketch]'s events. Tasks it lists that were created before now are
   * taken as they are: only their later changes are events.
   */
  fun attach(ketch: KetchApi) {
    if (hooks.isEmpty()) return
    val since = clock.now()
    val config: suspend () -> DownloadConfig? = {
      try {
        ketch.status().config
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d { "Hooks: no download settings for the category (${e::class.simpleName})" }
        null
      }
    }
    scope.launch {
      val watched = HashMap<String, Pair<DownloadTask, Job>>()
      ketch.tasks.collect { tasks ->
        val listed = HashSet<String>(tasks.size)
        for (task in tasks) {
          listed += task.taskId
          if (task.taskId in watched) continue
          val added = task.createdAt >= since
          if (added) emit(HookEvent.Added, task, task.state.value, config)
          // Undispatched, so its first state is read before the next list, keeping the order.
          val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            watch(task, added, config)
          }
          watched[task.taskId] = task to job
        }
        val removed = watched.keys.filter { it !in listed }
        for (taskId in removed) {
          val (task, job) = watched.remove(taskId) ?: continue
          job.cancel()
          emit(HookEvent.Removed, task, task.state.value, config)
        }
      }
    }
  }

  private suspend fun watch(
    task: DownloadTask,
    added: Boolean,
    config: suspend () -> DownloadConfig?,
  ) {
    var first = true
    var previous: DownloadState? = null
    task.state.collect { state ->
      // A task that was there before is taken as it is; an added one starts from nothing.
      val event = if (first && !added) null else transition(previous, state)
      first = false
      previous = state
      if (event != null) emit(event, task, state, config)
    }
  }

  private fun emit(
    event: HookEvent,
    task: DownloadTask,
    state: DownloadState,
    config: suspend () -> DownloadConfig?,
  ) {
    val targets = hooks.filter { event in it.events }
    if (targets.isEmpty()) return
    val delivery = Delivery(
      event = event,
      at = clock.now(),
      taskId = task.taskId,
      request = task.requestState.value,
      state = state,
      outputPath = task.outputPath,
      createdAt = task.createdAt,
      config = config,
      hooks = targets,
    )
    if (deliveries.trySend(delivery).isFailure) {
      log.w { "Hooks: too many events waiting, dropped ${event.id} for taskId=${task.taskId}" }
    }
  }

  private suspend fun run(delivery: Delivery) {
    val config = delivery.config()
    for (hook in delivery.hooks) {
      val payload = HookPayload.of(
        event = delivery.event,
        at = delivery.at,
        deliveryId = Uuid.random().toString(),
        taskId = delivery.taskId,
        request = delivery.request,
        state = delivery.state,
        outputPath = delivery.outputPath,
        createdAt = delivery.createdAt,
        config = config,
      )
      if (!hook.accepts(payload)) continue
      val timeout = hook.config.timeout.seconds
      val outcome = try {
        when (val action = hook.action) {
          is Action.Command -> commands.run(
            action.argv, hook.config.workingDirectory, timeout, payload,
          )
          is Action.Webhook -> webhooks.send(
            url = action.url,
            headers = hook.config.headers,
            secret = hook.config.secret,
            event = payload.event,
            deliveryId = payload.deliveryId,
            body = payload.toJson(),
            timeout = timeout,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        HookOutcome.Failed("failed (${e::class.simpleName})")
      }
      when (outcome) {
        HookOutcome.Done -> log.d {
          "Hooks: ${hook.label} ran on ${payload.event} for taskId=${payload.taskId}"
        }
        is HookOutcome.Failed -> log.w {
          "Hooks: ${hook.label} on ${payload.event} for taskId=${payload.taskId}: ${outcome.reason}"
        }
      }
    }
  }

  /** Stops running hooks; commands already started keep running. */
  override fun close() {
    deliveries.close()
    scope.cancel()
    webhooks.close()
  }

  private class Delivery(
    val event: HookEvent,
    val at: Instant,
    val taskId: String,
    val request: DownloadRequest,
    val state: DownloadState,
    val outputPath: String?,
    val createdAt: Instant,
    val config: suspend () -> DownloadConfig?,
    val hooks: List<Hook>,
  )

  companion object {
    /** Hooks that run at once. */
    const val MAX_CONCURRENT: Int = 4

    /** Events that wait for a free hook before new ones are dropped. */
    const val QUEUE_CAPACITY: Int = 256

    private val log = KetchLogger("Hooks")

    /**
     * Runs [hooks] on [ketch]'s events, or returns `null` when none can run. Logs what it runs,
     * and why it leaves out the hooks that can never run.
     */
    fun start(ketch: KetchApi, hooks: List<HookConfig>): HookRunner? {
      if (hooks.isEmpty()) return null
      val runner = HookRunner(hooks)
      if (runner.isEmpty) {
        runner.close()
        return null
      }
      log.i { "Hooks: running ${runner.hooks.joinToString { it.label }}" }
      runner.attach(ketch)
      return runner
    }
  }
}

/**
 * The event a download's state changing from [previous] (`null` before it had one) to [current]
 * is, or `null` when it is none.
 */
internal fun transition(previous: DownloadState?, current: DownloadState): HookEvent? =
  when (current) {
    is DownloadState.Downloading ->
      HookEvent.Started.takeIf { previous !is DownloadState.Downloading }
    is DownloadState.Completed -> HookEvent.Completed.takeIf {
      // A completed task that gains files completes again, maybe before it was seen running.
      previous !is DownloadState.Completed ||
        current.completedAt != null && current.completedAt != previous.completedAt
    }
    is DownloadState.Failed -> HookEvent.Failed.takeIf { previous !is DownloadState.Failed }
    DownloadState.Canceled -> HookEvent.Canceled.takeIf { previous != DownloadState.Canceled }
    else -> null
  }

internal sealed interface Action {
  data class Command(val argv: List<String>) : Action
  data class Webhook(val url: String) : Action
}

/** A hook that can run: [config] with its [events] and [action] checked. */
internal class Hook(
  val config: HookConfig,
  val events: Set<HookEvent>,
  val action: Action,
  val label: String,
) {
  /** Whether [payload]'s download passes the hook's filters. */
  fun accepts(payload: HookPayload): Boolean {
    if (config.origins.isNotEmpty() &&
      config.origins.none { it.trim().equals(payload.origin, ignoreCase = true) }
    ) return false
    if (config.categories.isNotEmpty() &&
      config.categories.none { it.trim().equals(payload.category, ignoreCase = true) }
    ) return false
    if (config.hosts.isNotEmpty()) {
      val host = payload.host ?: return false
      val rules = config.hosts.map { it.trim().lowercase().removePrefix("*.").trimStart('.') }
      if (rules.none { it.isNotEmpty() && (host == it || host.endsWith(".$it")) }) return false
    }
    return true
  }

  companion object {
    private val log = KetchLogger("Hooks")

    /** The [number]th hook, or `null` (with a warning) when it can never run. */
    fun of(number: Int, config: HookConfig): Hook? {
      if (!config.enabled) return null
      val events = config.eventSet
      // Arguments stay as written, empty ones too; only the program must be named.
      val command = config.command.takeIf { it.firstOrNull()?.isNotBlank() == true }.orEmpty()
      val webhook = config.webhook?.trim()?.ifEmpty { null }
      if (command.isNotEmpty() && webhook != null) {
        log.w { "Hooks: hook $number has a command and a webhook, it runs the command only" }
      }
      val action = when {
        command.isNotEmpty() -> Action.Command(command)
        webhook != null -> {
          val scheme = webhook.substringBefore("://", "").lowercase()
          if (scheme != "http" && scheme != "https" || hostOf(webhook) == null) {
            log.w { "Hooks: hook $number left out, its webhook is not an http(s) URL" }
            return null
          }
          Action.Webhook(webhook)
        }
        else -> {
          log.w { "Hooks: hook $number left out, it has neither a command nor a webhook" }
          return null
        }
      }
      if (events.isEmpty()) {
        log.w { "Hooks: hook $number left out, it names no known event" }
        return null
      }
      val label = config.name?.trim()?.ifEmpty { null }?.let { "\"$it\"" } ?: when (action) {
        is Action.Command -> {
          val program = action.argv.first().substringAfterLast('/').substringAfterLast('\\')
          "hook $number ($program)"
        }
        is Action.Webhook -> "hook $number (webhook to ${hostOf(action.url)})"
      }
      return Hook(config, events, action, label)
    }
  }
}
