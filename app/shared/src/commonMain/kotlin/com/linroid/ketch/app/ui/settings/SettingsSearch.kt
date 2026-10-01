package com.linroid.ketch.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.linroid.ketch.app.state.SettingsCategory
import kotlinx.coroutines.delay

/** What this app offers beyond the rows every app has, which decides some search entries. */
internal enum class SettingsFeature {
  /** The desktop app: startup, window, browser and default-app rows. */
  Desktop,

  /** A phone or tablet app, which has the welcome screens. */
  Mobile,

  /** An app whose empty Downloads page shows the setup checklist: desktop and the web. */
  SetupChecklist,

  /** The browser asks before the web app may notify. */
  BrowserNotifications,

  /** The app keeps log files to open or share. */
  Logs,
}

/**
 * One setting that search finds.
 *
 * @property category page the setting is on.
 * @property title what the setting is called, as results show it.
 * @property description what it does; searched, and shown under the title.
 * @property keywords other words people look for it by; searched, never shown.
 * @property anchors titles of the rows or groups to scroll to, the first one found winning;
 *   empty opens the page at the top.
 * @property needs what the app must offer for the setting to show; `null` when every app has it.
 */
@Immutable
internal data class SettingsEntry(
  val category: SettingsCategory,
  val title: String,
  val description: String = "",
  val keywords: List<String> = emptyList(),
  val anchors: List<String> = listOf(title),
  val needs: SettingsFeature? = null,
)

/**
 * A setting search found, with the parts of its [title][SettingsEntry.title] and
 * [description][SettingsEntry.description] that match, to highlight.
 */
@Immutable
internal data class SettingsHit(
  val entry: SettingsEntry,
  val titleMatches: List<IntRange>,
  val descriptionMatches: List<IntRange>,
)

/**
 * The settings in [categories] that match [query], best first: every word of the query must
 * appear in the title, the description, the keywords or the page's name. A title that starts
 * with the query ranks first, then one that holds it, then one that holds all of its words, then
 * the rest, each in page order. Each page also answers by its own name and description.
 *
 * @param features what this app offers; entries that need anything else are left out.
 */
internal fun searchSettings(
  query: String,
  categories: List<SettingsCategory>,
  features: Set<SettingsFeature>,
  entries: List<SettingsEntry> = SettingsIndex,
): List<SettingsHit> {
  val phrase = query.trim().lowercase()
  val words = phrase.split(WHITESPACE).filter { it.isNotEmpty() }
  if (words.isEmpty()) return emptyList()
  val pages = categories.map { SettingsEntry(it, it.title, it.description, anchors = emptyList()) }
  val candidates = pages + entries.filter { entry ->
    entry.category in categories && (entry.needs == null || entry.needs in features)
  }
  return candidates.mapNotNull { entry ->
    val title = entry.title.lowercase()
    val haystack = listOf(title, entry.description.lowercase(), entry.category.title.lowercase()) +
      entry.keywords.map { it.lowercase() }
    if (!words.all { word -> haystack.any { it.contains(word) } }) return@mapNotNull null
    val rank = when {
      title.startsWith(phrase) -> 0
      title.contains(phrase) -> 1
      words.all { title.contains(it) } -> 2
      else -> 3
    }
    val hit = SettingsHit(
      entry = entry,
      titleMatches = matchRanges(entry.title, words),
      descriptionMatches = matchRanges(entry.description, words),
    )
    Triple(rank, categories.indexOf(entry.category), hit)
  }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}

/** Where [words] occur in [text], ignoring case, merged where they touch or overlap. */
internal fun matchRanges(text: String, words: List<String>): List<IntRange> {
  val lower = text.lowercase()
  val found = words.filter { it.isNotEmpty() }.flatMap { word ->
    generateSequence(lower.indexOf(word).takeIf { it >= 0 }) { from ->
      lower.indexOf(word, from + 1).takeIf { it >= 0 }
    }.map { it until it + word.length }.toList()
  }.sortedBy { it.first }
  return found.fold(mutableListOf()) { merged, range ->
    val last = merged.lastOrNull()
    if (last != null && range.first <= last.last + 1) {
      merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
    } else {
      merged += range
    }
    merged
  }
}

/** [text] with [matches] drawn in [style]. */
internal fun highlighted(text: String, matches: List<IntRange>, style: SpanStyle): AnnotatedString =
  buildAnnotatedString {
    append(text)
    for (range in matches) {
      if (range.first >= 0 && range.last < text.length) {
        addStyle(style, range.first, range.last + 1)
      }
    }
  }

/** Asks a page to scroll to the first of [anchors] it shows and flash it. */
@Immutable
internal data class SettingsJumpRequest(val anchors: List<String>, val serial: Int)

/**
 * The rows and groups of one Settings page that a [SettingsJumpRequest] can reach. Rows and
 * groups report where they are through [settingsAnchor]; the page scrolls to the one asked for,
 * and the anchor draws a fading highlight over itself.
 *
 * @param highlight color of the highlight.
 * @param shape outline of the highlight, matching the groups' cards.
 */
@Stable
internal class SettingsJump(val highlight: Color, val shape: Shape) {
  private val anchors = HashMap<String, LayoutCoordinates>()
  private val flash = Animatable(0f)

  /** Name of the anchor being highlighted, or `null`. */
  var highlighted: String? by mutableStateOf(null)
    private set

  /** Anchors the latest jump asked for, so a collapsed section can open for one of its rows. */
  var requested: List<String> by mutableStateOf(emptyList())
    private set

  /** How strongly [name] is highlighted now, from 0 to 1; read while drawing. */
  fun highlightOf(name: String): Float = if (name == highlighted) flash.value else 0f

  /** Records where the anchor called [name] is. */
  fun place(name: String, coordinates: LayoutCoordinates) {
    if (name.isNotEmpty()) anchors[name] = coordinates
  }

  /**
   * Waits a few frames for one of [names] to be laid out, scrolls [scroll] so it sits near the
   * top of the page, and flashes it. A collapsed section that holds one of them opens through
   * [requested]; otherwise the jump gives up quietly when none shows.
   *
   * @param content coordinates of the scrolled content, which anchors are measured against.
   * @param margin room left above the anchor.
   */
  suspend fun jump(
    names: List<String>,
    scroll: ScrollState,
    content: () -> LayoutCoordinates?,
    margin: Float,
    animate: Boolean,
  ) {
    if (names.isEmpty()) return
    requested = names
    var target: Pair<String, LayoutCoordinates>? = null
    var frames = 0
    while (target == null && frames < MAX_WAIT_FRAMES) {
      withFrameNanos {}
      target = names.firstNotNullOfOrNull { name ->
        anchors[name]?.takeIf { it.isAttached }?.let { name to it }
      }
      frames++
    }
    val (name, coordinates) = target ?: return
    val within = content()?.takeIf { it.isAttached }
    if (within != null) {
      val y = (within.localPositionOf(coordinates, Offset.Zero).y - margin).toInt().coerceAtLeast(0)
      if (animate) scroll.animateScrollTo(y) else scroll.scrollTo(y)
    }
    highlighted = name
    // Cleared even when cancelled, such as by opening the page again, so no tint stays behind.
    try {
      if (animate) {
        flash.snapTo(0f)
        flash.animateTo(1f, tween(FLASH_IN_MS))
        delay(FLASH_HOLD_MS)
        flash.animateTo(0f, tween(FLASH_OUT_MS))
      } else {
        flash.snapTo(1f)
        delay(FLASH_IN_MS + FLASH_HOLD_MS + FLASH_OUT_MS)
        flash.snapTo(0f)
      }
    } finally {
      highlighted = null
    }
  }
}

/** The page's [SettingsJump], or `null` outside a Settings page. */
internal val LocalSettingsJump = staticCompositionLocalOf<SettingsJump?> { null }

/**
 * Makes this row or group a place search can jump to, under [name], its title. Outside a
 * Settings page it does nothing.
 */
internal fun Modifier.settingsAnchor(name: String): Modifier = this then SettingsAnchorElement(name)

private data class SettingsAnchorElement(val name: String) :
  ModifierNodeElement<SettingsAnchorNode>() {
  override fun create(): SettingsAnchorNode = SettingsAnchorNode(name)

  override fun update(node: SettingsAnchorNode) {
    node.name = name
    node.invalidateDraw()
  }
}

private class SettingsAnchorNode(var name: String) :
  Modifier.Node(),
  CompositionLocalConsumerModifierNode,
  GlobalPositionAwareModifierNode,
  DrawModifierNode {
  override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
    currentValueOf(LocalSettingsJump)?.place(name, coordinates)
  }

  override fun ContentDrawScope.draw() {
    drawContent()
    val jump = currentValueOf(LocalSettingsJump) ?: return
    val strength = jump.highlightOf(name)
    if (strength > 0f) {
      drawOutline(
        outline = jump.shape.createOutline(size, layoutDirection, this),
        color = jump.highlight.copy(alpha = jump.highlight.alpha * strength),
      )
    }
  }
}

/** Runs [request] on the page once it is laid out; see [SettingsJump.jump]. */
@Composable
internal fun SettingsJumpEffect(
  jump: SettingsJump,
  request: SettingsJumpRequest?,
  scroll: ScrollState,
  content: () -> LayoutCoordinates?,
  margin: Float,
  animate: Boolean,
) {
  LaunchedEffect(jump, request) {
    if (request != null) jump.jump(request.anchors, scroll, content, margin, animate)
  }
}

private val WHITESPACE = Regex("\\s+")
private const val MAX_WAIT_FRAMES = 30
private const val FLASH_IN_MS = 150
private const val FLASH_HOLD_MS = 900L
private const val FLASH_OUT_MS = 600

/**
 * Every setting search finds, page by page. Titles and anchors match the titles of the rows and
 * groups on the pages, so a search result can scroll to them.
 */
internal val SettingsIndex: List<SettingsEntry> = listOf(
  // General
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Device name",
    description = "The name your other devices see",
    keywords = listOf("hostname", "computer", "rename"),
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Theme",
    description = "System, light or dark",
    keywords = listOf("dark mode", "light mode", "appearance"),
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Accent color",
    description = "Signal, Harbor, Fathom or Beacon",
    keywords = listOf("colour", "appearance"),
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Density",
    description = "Auto, compact or comfortable",
    keywords = listOf("size", "spacing", "touch", "appearance"),
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Reduce motion",
    description = "Fewer animations",
    keywords = listOf("animation", "accessibility"),
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Language",
    keywords = listOf("locale", "translation"),
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "When I close the window",
    description = "Keep downloading in the background, or quit",
    keywords = listOf("quit", "menu bar", "tray", "notification area", "exit"),
    needs = SettingsFeature.Desktop,
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Open Ketch at login",
    keywords = listOf("startup", "launch", "boot", "login item"),
    needs = SettingsFeature.Desktop,
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "Start hidden at login",
    keywords = listOf("startup", "background", "menu bar", "tray"),
    needs = SettingsFeature.Desktop,
  ),
  SettingsEntry(
    category = SettingsCategory.General,
    title = "App icon badge",
    description = "What the Dock or taskbar icon shows",
    keywords = listOf("count", "dock", "taskbar"),
    needs = SettingsFeature.Desktop,
  ),
  // Notifications
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "Download finished",
    keywords = listOf("complete", "done", "alert"),
  ),
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "Download failed",
    keywords = listOf("error", "alert"),
  ),
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "All downloads finished",
    keywords = listOf("queue", "complete", "done"),
  ),
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "Devices going offline",
    keywords = listOf("disconnected", "connection lost"),
  ),
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "Only when Ketch is in the background",
    keywords = listOf("foreground", "toast"),
  ),
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "Notify me about these devices",
    keywords = listOf("mute", "remote", "nas", "keep connected"),
  ),
  SettingsEntry(
    category = SettingsCategory.Notifications,
    title = "Browser notifications",
    description = "Let this browser show Ketch's notifications",
    keywords = listOf("permission", "allow"),
    needs = SettingsFeature.BrowserNotifications,
  ),
  // Integration
  SettingsEntry(
    category = SettingsCategory.Integration,
    title = "Browser extension",
    description = "Send browser downloads to Ketch",
    keywords = listOf("chrome", "edge", "firefox", "brave", "arc", "vivaldi", "capture"),
    needs = SettingsFeature.Desktop,
  ),
  SettingsEntry(
    category = SettingsCategory.Integration,
    title = "Open magnet links with Ketch",
    keywords = listOf("default app", "handler", "protocol"),
    needs = SettingsFeature.Desktop,
  ),
  SettingsEntry(
    category = SettingsCategory.Integration,
    title = "Open .torrent files with Ketch",
    keywords = listOf("default app", "file type", "handler"),
    needs = SettingsFeature.Desktop,
  ),
  SettingsEntry(
    category = SettingsCategory.Integration,
    title = "Suggest links from the clipboard",
    keywords = listOf("paste", "copy"),
  ),
  SettingsEntry(
    category = SettingsCategory.Integration,
    title = "Add pasted links immediately",
    keywords = listOf("paste", "quick add", "clipboard"),
  ),
  // Discover
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "AI discovery",
    keywords = listOf("ai", "llm", "agent", "turn on"),
  ),
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "Provider",
    description = "OpenAI, Anthropic, Gemini, Ollama or your own",
    keywords = listOf("claude", "gpt", "google", "openrouter", "llm", "model provider"),
  ),
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "API key",
    keywords = listOf("token", "secret", "credentials"),
  ),
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "Model",
    keywords = listOf("llm"),
  ),
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "Endpoint",
    keywords = listOf("base url", "server", "url"),
    anchors = listOf("Endpoint", "Endpoint (optional)"),
  ),
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "Test connection",
    keywords = listOf("check", "verify"),
  ),
  SettingsEntry(
    category = SettingsCategory.Discover,
    title = "Web search",
    description = "Brave or Google search for the agent",
    keywords = listOf("search provider", "search api key", "engine"),
  ),
  // About
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Version",
    keywords = listOf("build", "revision", "update"),
  ),
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Source code",
    keywords = listOf("github", "project"),
  ),
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Report a problem",
    keywords = listOf("bug", "issue", "feedback", "help"),
  ),
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Open-source licenses",
    keywords = listOf("license", "notices", "fonts", "ofl"),
  ),
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Log files",
    description = "Open or share the logs for a bug report",
    keywords = listOf("logs", "debug", "troubleshooting"),
    anchors = listOf("Open log folder", "Share logs"),
    needs = SettingsFeature.Logs,
  ),
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Show setup checklist",
    keywords = listOf("getting started", "onboarding", "setup"),
    needs = SettingsFeature.SetupChecklist,
  ),
  SettingsEntry(
    category = SettingsCategory.About,
    title = "Show welcome again",
    keywords = listOf("onboarding", "first run", "intro"),
    needs = SettingsFeature.Mobile,
  ),
  // Downloads
  SettingsEntry(
    category = SettingsCategory.Downloads,
    title = "Save downloads to",
    keywords = listOf("folder", "directory", "location", "path", "destination"),
  ),
  SettingsEntry(
    category = SettingsCategory.Downloads,
    title = "Folders in the add sheet",
    description = "Pinned and recent folders",
    keywords = listOf("favorite", "pin", "recent"),
  ),
  SettingsEntry(
    category = SettingsCategory.Downloads,
    title = "Run at once",
    description = "Downloads that run together",
    keywords = listOf("concurrent", "parallel", "simultaneous", "queue", "limit"),
  ),
  SettingsEntry(
    category = SettingsCategory.Downloads,
    title = "Per server",
    description = "Downloads from one website at a time",
    keywords = listOf("host", "website", "queue", "limit"),
  ),
  SettingsEntry(
    category = SettingsCategory.Downloads,
    title = "Retries",
    keywords = listOf("retry", "attempts", "errors"),
  ),
  // Speed
  SettingsEntry(
    category = SettingsCategory.Speed,
    title = "Speed mode",
    description = "Full speed, Slow lane or Auto",
    keywords = listOf("limit", "bandwidth", "throttle"),
  ),
  SettingsEntry(
    category = SettingsCategory.Speed,
    title = "Full speed cap",
    description = "The speed limit all downloads share",
    keywords = listOf("speed limit", "bandwidth", "throttle", "maximum"),
    anchors = listOf("Full speed cap", "Speed limit"),
  ),
  SettingsEntry(
    category = SettingsCategory.Speed,
    title = "Slow lane speed",
    keywords = listOf("limit", "bandwidth", "throttle"),
  ),
  SettingsEntry(
    category = SettingsCategory.Speed,
    title = "Auto rules",
    description = "Turn the Slow lane on at set times",
    keywords = listOf("schedule", "work hours", "weekdays", "time"),
  ),
  SettingsEntry(
    category = SettingsCategory.Speed,
    title = "Connections per download",
    keywords = listOf("segments", "threads", "parallel", "split"),
  ),
  // Network
  SettingsEntry(
    category = SettingsCategory.Network,
    title = "Spread downloads across",
    description = "The networks downloads use",
    keywords = listOf("wifi", "wi-fi", "ethernet", "interface", "vpn", "multiple networks"),
    anchors = listOf("Networks", "Spread downloads across"),
  ),
  // BitTorrent
  SettingsEntry(
    category = SettingsCategory.BitTorrent,
    title = "Extra trackers",
    description = "Trackers added to public torrents",
    keywords = listOf("announce", "udp", "magnet", "add trackers"),
  ),
  // Sharing
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Pair a device",
    description = "Control this device from a phone or browser",
    keywords = listOf("qr code", "phone", "pairing", "remote", "web app", "connect"),
  ),
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Reachable from other devices",
    keywords = listOf("lan", "network", "remote access"),
  ),
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Port",
    keywords = listOf("server"),
  ),
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Access code",
    keywords = listOf("token", "password", "api token"),
  ),
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Discoverable on the local network",
    keywords = listOf("mdns", "bonjour", "find"),
  ),
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Websites allowed to connect",
    keywords = listOf("cors", "origin"),
  ),
  SettingsEntry(
    category = SettingsCategory.Sharing,
    title = "Start sharing when Ketch opens",
    keywords = listOf("server", "startup", "auto start"),
  ),
)
