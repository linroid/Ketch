package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.platform.FilePicker
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.ui.pulse.toggleSlowLane
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What adding from the clipboard does with the text on it. */
internal sealed interface ClipboardAdd {
  /** Adds [urls] at once, without the add sheet. */
  data class Now(val urls: List<String>) : ClipboardAdd

  /** Opens the add sheet with [text]. */
  data class Sheet(val text: String) : ClipboardAdd

  /** The clipboard holds nothing to add. */
  data object Empty : ClipboardAdd
}

/**
 * What pasting [text] into the shell does (`⌘V`): one plain link is added at once while
 * [quickAdd] is on, and anything else opens the add sheet with the text. Several links, a
 * magnet, a `.torrent` or a cURL command always open the sheet, where they can be checked.
 */
internal fun pasteAction(text: String?, quickAdd: Boolean): ClipboardAdd {
  val trimmed = text?.trim().orEmpty()
  if (trimmed.isEmpty()) return ClipboardAdd.Empty
  val link = LinkParser.quickAddLink(trimmed)
  return if (quickAdd && link != null && link.headers.isEmpty()) {
    ClipboardAdd.Now(listOf(link.url))
  } else {
    ClipboardAdd.Sheet(trimmed)
  }
}

/**
 * What adding the links on the clipboard does (`⇧⌘V`, a long press on the Add button): every
 * link at once. Text without a link, or a cURL command whose headers only the sheet keeps, opens
 * the add sheet instead.
 */
internal fun clipboardLinksAction(text: String?): ClipboardAdd {
  val trimmed = text?.trim().orEmpty()
  if (trimmed.isEmpty()) return ClipboardAdd.Empty
  val links = LinkParser.parseIntake(trimmed).links()
  return if (links.isEmpty() || links.any { it.headers.isNotEmpty() }) {
    ClipboardAdd.Sheet(trimmed)
  } else {
    ClipboardAdd.Now(links.map { it.url }.distinct())
  }
}

/**
 * Whether one plain link pasted or dropped is added without the sheet: as the user chose, and by
 * default on desktop and web.
 */
internal val AppState.quickAddsLinks: Boolean
  get() = appSettings.ui.quickAdd ?: !isMobilePlatform

/**
 * Runs the global [KetchCommands] in the window: adding, the tabs and destinations, the devices
 * and their switcher, the speed and queue commands, the sidebar, the inspector, Activity, Undo,
 * Settings, the shortcut sheet and the command palette (`⌘K`). While Settings shows, `⌘F` is
 * left to Settings' own search.
 *
 * @param clipboard where pasted and added links come from.
 * @param files picks `.torrent` files to open.
 */
internal class ShellCommands(
  private val shell: ShellState,
  private val scope: CoroutineScope,
  private val clipboard: SystemClipboard,
  private val files: FilePicker,
  private val platform: KeyboardPlatform = KeyboardPlatform.current,
) {
  private val log = KetchLogger("ShellCommands")
  private val state: AppState get() = shell.app

  private val quickAdd: Boolean get() = state.quickAddsLinks

  /** Whether [run] does anything for [command]. */
  fun binds(command: KetchCommand): Boolean =
    command in bound || tabOf(command) != null || deviceOf(command) != null

  /**
   * Runs [command]; returns whether it did, so a key it leaves alone reaches the rest of the
   * window. On the web the paste key is left to the browser, whose paste event brings the text.
   */
  fun run(command: KetchCommand): Boolean {
    tabOf(command)?.let { filter ->
      shell.show(AppDestination.Downloads)
      state.showDownloads(filter)
      return true
    }
    deviceOf(command)?.let { number ->
      val device = state.instances.value.getOrNull(number - 1) ?: return false
      state.switchInstance(device)
      return true
    }
    when (command) {
      KetchCommands.Add -> state.openIntake()
      KetchCommands.PasteLinks -> {
        if (platform.isWeb) return false
        addFromClipboard(warnEmpty = false) { pasteAction(it, quickAdd) }
      }
      KetchCommands.AddClipboardLink -> addFromClipboard(warnEmpty = true, ::clipboardLinksAction)
      KetchCommands.OpenTorrent -> openTorrentFiles()
      KetchCommands.Palette -> shell.paletteOpen = !shell.paletteOpen
      KetchCommands.Search -> {
        // Settings in the shell searches its own pages.
        if (shell.settingsOpen) return false
        // The shell shows Downloads for it, and the page's field takes the focus.
        state.requestSearchFocus()
      }
      KetchCommands.Discover -> return shell.show(AppDestination.Discover)
      KetchCommands.Devices -> return shell.show(AppDestination.Devices)
      KetchCommands.AllDevices -> return state.showAllDevices()
      KetchCommands.SwitchDevice -> state.showInstanceSelector = !state.showInstanceSelector
      KetchCommands.PauseAll -> state.pauseAll()
      KetchCommands.ResumeAll -> state.resumeAll()
      KetchCommands.RetryFailed -> state.retryFailed()
      KetchCommands.SlowLane -> return state.toggleSlowLane() != null
      KetchCommands.ShowDetails -> return state.showDetails()
      KetchCommands.ToggleSidebar -> return shell.toggleSidebar()
      KetchCommands.Activity -> shell.pulseBar.toggleActivity()
      KetchCommands.Undo -> return state.pendingOps.undoLast()
      KetchCommands.Settings -> state.openSettings()
      KetchCommands.Shortcuts -> shell.shortcutsOpen = !shell.shortcutsOpen
      else -> return false
    }
    return true
  }

  /** Adds what the browser's paste event brought, as [KetchCommands.PasteLinks] does. */
  fun paste(text: String) {
    // A paste outside the add sheet's fields while it is open adds nothing behind it.
    if (state.showAddDialog) return
    apply(pasteAction(text, quickAdd), warnEmpty = false)
  }

  private fun addFromClipboard(warnEmpty: Boolean, action: (String?) -> ClipboardAdd) {
    scope.launch {
      val text = try {
        clipboard.readText()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't read the clipboard: ${e.describeCauses()}" }
        null
      }
      apply(action(text), warnEmpty)
    }
  }

  // An empty clipboard says so for an explicit "add the link" request; a paste stays quiet.
  private fun apply(add: ClipboardAdd, warnEmpty: Boolean) {
    when (add) {
      is ClipboardAdd.Now -> {
        shell.show(AppDestination.Downloads)
        state.quickAdd(add.urls)
      }
      is ClipboardAdd.Sheet -> state.openIntake(IntakeRequest(text = add.text))
      ClipboardAdd.Empty -> if (warnEmpty) {
        state.messages.post(MessageLevel.Warning, "The clipboard holds no link")
      }
    }
  }

  private fun openTorrentFiles() {
    scope.launch {
      val picked = try {
        files.pickTorrentFiles()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't pick torrent files: ${e.describeCauses()}" }
        state.messages.post(MessageLevel.Error, "Couldn't open the file picker", e.message)
        emptyList()
      }
      if (picked.isNotEmpty()) {
        shell.show(AppDestination.Downloads)
        state.addDroppedFiles(picked)
      }
    }
  }

  private fun tabOf(command: KetchCommand): StatusFilter? =
    StatusFilter.entries.firstOrNull { KetchCommands.tab(it) == command }

  private fun deviceOf(command: KetchCommand): Int? =
    (1..MAX_DEVICE_SHORTCUTS).firstOrNull { KetchCommands.device(it) == command }

  private companion object {
    const val MAX_DEVICE_SHORTCUTS = 9

    val bound: Set<KetchCommand> = setOf(
      KetchCommands.Add,
      KetchCommands.PasteLinks,
      KetchCommands.AddClipboardLink,
      KetchCommands.OpenTorrent,
      KetchCommands.Palette,
      KetchCommands.Search,
      KetchCommands.Discover,
      KetchCommands.Devices,
      KetchCommands.AllDevices,
      KetchCommands.SwitchDevice,
      KetchCommands.PauseAll,
      KetchCommands.ResumeAll,
      KetchCommands.RetryFailed,
      KetchCommands.SlowLane,
      KetchCommands.ShowDetails,
      KetchCommands.ToggleSidebar,
      KetchCommands.Activity,
      KetchCommands.Undo,
      KetchCommands.Settings,
      KetchCommands.Shortcuts,
    )
  }
}
