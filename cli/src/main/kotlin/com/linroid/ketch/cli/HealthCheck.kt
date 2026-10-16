package com.linroid.ketch.cli

import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking

/** Exit statuses of `ketch health`, which container health checks read. */
internal object HealthExit {
  /** The server serves its saved tasks. */
  const val READY = 0

  /** The server still restores its saved tasks, answers otherwise, or cannot be reached. */
  const val NOT_READY = 1

  /** The arguments, or the server's environment variables, cannot be used. */
  const val USAGE = 2
}

/** Arguments of `ketch health`. */
internal sealed interface HealthArgs {
  /** `--help` or `-h` was given. */
  data object Help : HealthArgs

  /** The arguments cannot be used; [message] says why. */
  data class Invalid(val message: String) : HealthArgs

  /**
   * @property host address to ask, or `null` for the one the server listens on.
   * @property port port to ask, or `null` for the server's.
   * @property configPath config file the server reads, or `null` for the default one.
   */
  data class Check(
    val host: String? = null,
    val port: Int? = null,
    val configPath: String? = null,
  ) : HealthArgs
}

/** Parses the arguments of `ketch health`, which no longer contain the global flags. */
internal fun parseHealthArgs(args: List<String>): HealthArgs {
  var check = HealthArgs.Check()
  var i = 0
  while (i < args.size) {
    when (val arg = args[i]) {
      "--help", "-h" -> return HealthArgs.Help
      "--host" -> {
        val value = args.getOrNull(++i) ?: return HealthArgs.Invalid("--host requires a value")
        check = check.copy(host = value)
      }
      "--port" -> {
        val value = args.getOrNull(++i) ?: return HealthArgs.Invalid("--port requires a value")
        val port = value.toIntOrNull()?.takeIf { it in 1..65535 }
          ?: return HealthArgs.Invalid("invalid port '$value'")
        check = check.copy(port = port)
      }
      "--config" -> {
        val value = args.getOrNull(++i) ?: return HealthArgs.Invalid("--config requires a value")
        check = check.copy(configPath = value)
      }
      else -> return HealthArgs.Invalid("unknown option '$arg'")
    }
    i++
  }
  return check
}

/**
 * The health endpoint `ketch health` asks: at the host and port [check] names, when it names
 * either; else at the URL the running server published in `instance.json` ([running]), which
 * follows the options it was started with; else where [server] listens. A server on every
 * interface is asked on loopback, which also passes its `Host` check without a token.
 */
internal fun healthCheckUrl(
  check: HealthArgs.Check,
  server: ServerConfig,
  running: CliInstance.Info?,
): String {
  val base = when {
    check.host != null || check.port != null ->
      loopbackUrl(check.host ?: server.host, check.port ?: server.port)
    running?.command == SERVER_COMMAND && running.url != null -> running.url
    else -> loopbackUrl(server.host, server.port)
  }
  return "${base.trimEnd('/')}$HEALTH_PATH"
}

/**
 * Asks the server at [url] whether it is ready, printing its answer, and returns one of
 * [HealthExit].
 */
internal fun checkHealth(url: String): Int = runBlocking {
  HttpClient(CIO) {
    expectSuccess = false
    install(HttpTimeout) {
      connectTimeoutMillis = HEALTH_TIMEOUT_MS
      requestTimeoutMillis = HEALTH_TIMEOUT_MS
    }
  }.use { client ->
    try {
      when (val status = client.get(url).status) {
        HttpStatusCode.OK -> {
          println("ready")
          HealthExit.READY
        }
        HttpStatusCode.ServiceUnavailable -> {
          println("starting: restoring saved downloads")
          HealthExit.NOT_READY
        }
        else -> {
          println("not ready: $url answered $status")
          HealthExit.NOT_READY
        }
      }
    } catch (e: Exception) {
      println("not ready: cannot reach $url (${e.describeCauses()})")
      HealthExit.NOT_READY
    }
  }
}

private const val HEALTH_PATH = "/api/health"

private const val HEALTH_TIMEOUT_MS = 5_000L
