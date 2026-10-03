package com.linroid.ketch.cli

import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.PageAccessRequest
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import com.linroid.ketch.config.SiteNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter

/**
 * Answers Discover's page access requests for one `ketch ai-discover` run, following
 * `[ai.access]` and asking on [terminal] when it says to.
 *
 * What the user allows or denies lasts for the run; the CLI never writes `config.toml`. A
 * blank, unknown or missing answer denies: once input ends (Ctrl+D), every later request is
 * denied without asking.
 *
 * @param settings the `[ai.access]` settings
 * @param allowAll `--yes`: allow every request
 * @param terminal where to ask; without one, requests the settings do not allow are denied
 * @param outputLock held from a question until its answer, so other output that takes it, such
 *   as the agent's steps, never lands inside a question
 */
internal class CliPageAccess(
  private val settings: PageAccessSettings,
  allowAll: Boolean,
  private val terminal: PromptTerminal?,
  private val outputLock: Any = Any(),
) : PageAccessApprover {

  /** Keeps one question on the terminal at a time. */
  private val mutex = Mutex()
  private var allowEverything = allowAll
  private val allowedSites = mutableSetOf<String>()
  private val deniedSites = mutableSetOf<String>()
  private var inputEnded = false

  override suspend fun approve(request: PageAccessRequest): Boolean = mutex.withLock {
    val host = request.host
    when {
      allowEverything || settings.allowsWithoutAsking(host, allowedSites) -> true
      deniedSites.any { SiteNames.covers(it, host) } -> false
      terminal == null || inputEnded -> false
      else -> ask(terminal, request)
    }
  }

  private suspend fun ask(terminal: PromptTerminal, request: PageAccessRequest): Boolean {
    val site = SiteNames.normalize(request.host)
    val answer = runInterruptible(Dispatchers.IO) {
      synchronized(outputLock) {
        terminal.print(question(request, site))
        terminal.readLine()
      }
    }
    if (answer == null) {
      inputEnded = true
      terminal.print("\n")
    }
    return when (answer?.trim()?.lowercase()) {
      "y", "yes" -> {
        // Asking for each new site, an allowed site stays allowed.
        if (settings.mode == PageAccessMode.AskPerSite) allowedSites += site
        true
      }
      "s" -> {
        allowedSites += site
        true
      }
      "a" -> {
        allowEverything = true
        true
      }
      else -> {
        deniedSites += site
        false
      }
    }
  }

  /** The question for [request]; nothing a page shaped can add a line or hide what it shows. */
  private fun question(request: PageAccessRequest, site: String): String = buildString {
    appendLine("Allow Discover to open ${request.host}? ${printableUrl(redactUrl(request.url))}")
    if (request.redirectFrom.isNotEmpty()) {
      appendLine("  Redirected from ${request.redirectFrom.printable()}")
    }
    if (request.reason.isNotEmpty()) appendLine("  Discover says: ${request.reason.printable()}")
    append("[y] allow  [s] allow $site for this run  [a] allow all  [n] deny: ")
  }
}

/**
 * Whether a run with these arguments may have to ask before opening a website: `[ai.access]`
 * asks, and neither `--yes` nor `--sites` is given. A run limited to websites never asks.
 */
internal fun AiDiscoverArgs.Discover.mayAsk(access: PageAccessSettings): Boolean =
  access.mode != PageAccessMode.Allow && !allowAll && sites.isEmpty()

/**
 * Why a run that [mayAsk] cannot start without a terminal to ask on. On Windows that is also a
 * run in a console whose input or output is redirected; see [openTerminal].
 */
internal fun noTerminalMessage(mode: PageAccessMode): String =
  "Discover asks before opening websites ([ai.access] mode = \"${mode.id}\"), " +
    "but has no terminal to ask on" +
    (if (isWindows()) " (on Windows, its input and output must both be the console)" else "") +
    ". Run it in a terminal to answer, pass --yes or --sites, or set mode = \"allow\"."

/** A terminal `ketch ai-discover` asks its questions on. */
internal interface PromptTerminal : Closeable {
  /** Shows [text] at once, without adding a line break. */
  fun print(text: String)

  /** Blocks until the user enters a line and returns it, or `null` once input has ended. */
  fun readLine(): String?
}

/**
 * Opens the terminal to ask on: the controlling terminal, `/dev/tty`, so questions reach the
 * user even when the standard streams are redirected.
 *
 * On Windows it is the console, and only while the standard input and output are both the
 * console ([System.console]): a run whose streams are redirected has none, and needs `--yes`
 * or `--sites`. The console itself is not opened (`CONIN$`) in that case: a process started
 * by the Task Scheduler or a CI runner may have a hidden one, where a question would wait
 * forever instead of failing at once.
 *
 * @return the terminal, or `null` when the process has none, such as under cron or in CI
 */
internal fun openTerminal(): PromptTerminal? {
  if (isWindows()) {
    val console = System.console() ?: return null
    // The console stays open for the process; closing its streams would close stdin.
    return ReaderTerminal(BufferedReader(console.reader()), console.writer()) {}
  }
  val input = try {
    FileInputStream(TTY)
  } catch (_: IOException) {
    return null
  }
  val output = try {
    FileOutputStream(TTY)
  } catch (_: IOException) {
    input.close()
    return null
  }
  return ReaderTerminal(input.bufferedReader(), PrintWriter(output.writer())) {
    input.close()
    output.close()
  }
}

private const val TTY = "/dev/tty"

private fun isWindows(): Boolean =
  System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private class ReaderTerminal(
  private val input: BufferedReader,
  private val output: PrintWriter,
  private val onClose: () -> Unit,
) : PromptTerminal {
  override fun print(text: String) {
    output.print(text)
    output.flush()
  }

  override fun readLine(): String? = input.readLine()

  override fun close() = onClose()
}
