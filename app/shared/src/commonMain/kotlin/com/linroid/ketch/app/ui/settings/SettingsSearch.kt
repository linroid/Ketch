package com.linroid.ketch.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.SettingsCategory
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What this app offers beyond the rows every app has, which decides some search entries. */
internal enum class SettingsFeature {
  /** The desktop app: startup, window, browser and default-app rows. */
  Desktop,

  /** A phone or tablet app, which has the welcome screens. */
  Mobile,

  /** An app whose empty Downloads page shows the setup checklist: desktop and the web. */
  SetupChecklist,

  /** An app used with a keyboard, which lists its shortcuts: desktop and the web. */
  Keyboard,

  /** The browser asks before the web app may notify. */
  BrowserNotifications,

  /** The app keeps log files to open or share. */
  Logs,

  /** The app keeps the system awake while downloading: desktop and Android. */
  KeepAwake,
}

/**
 * One setting that search finds, as the string resources its page shows, so search reads the
 * pages' own words in their language. [SettingsIndex] lists them; [rememberSettingsSearchIndex]
 * and [loadSettingsSearchIndex] read them into [SettingsEntry].
 *
 * @property category page the setting is on.
 * @property title what the setting is called: the title of its row or group.
 * @property description what it does, when search should say more than the title.
 * @property keywords other words people look for it by, separated by commas.
 * @property anchors titles of the rows or groups to scroll to, the first one found winning.
 * @property needs what the app must offer for the setting to show; `null` when every app has it.
 */
@Immutable
internal data class SettingsIndexEntry(
  val category: SettingsCategory,
  val title: StringResource,
  val description: UiText? = null,
  val keywords: StringResource? = null,
  val anchors: List<StringResource> = listOf(title),
  val needs: SettingsFeature? = null,
)

/**
 * One setting that search finds, in the language of the pages.
 *
 * @property category page the setting is on.
 * @property title what the setting is called, as results show it.
 * @property description what it does; searched, and shown under the title.
 * @property keywords other words people look for it by; searched, never shown.
 * @property anchors titles of the rows or groups to scroll to, the first one found winning;
 *   empty opens the page at the top.
 * @property needs what the app must offer for the setting to show; `null` when every app has it.
 * @property page the name of the page the setting is on; searched, and shown under the title.
 */
@Immutable
internal data class SettingsEntry(
  val category: SettingsCategory,
  val title: String,
  val description: String = "",
  val keywords: List<String> = emptyList(),
  val anchors: List<String> = listOf(title),
  val needs: SettingsFeature? = null,
  val page: String = "",
)

/**
 * What search reads, in one language.
 *
 * @property pages each page by its own name and description, which opens it at the top.
 * @property entries the settings of [SettingsIndex].
 */
@Immutable
internal data class SettingsSearchIndex(
  val pages: Map<SettingsCategory, SettingsEntry>,
  val entries: List<SettingsEntry>,
)

/** [SettingsIndex] and the pages in the language of the composition. */
@Composable
internal fun rememberSettingsSearchIndex(): SettingsSearchIndex {
  val pages = SettingsCategory.entries.associateWith { category ->
    pageEntry(category, category.titleText.resolve(), category.descriptionText.resolve())
  }
  val entries = SettingsIndex.map { entry ->
    SettingsEntry(
      category = entry.category,
      title = stringResource(entry.title),
      description = entry.description?.resolve().orEmpty(),
      keywords = entry.keywords?.let { splitKeywords(stringResource(it)) }.orEmpty(),
      anchors = entry.anchors.map { stringResource(it) },
      needs = entry.needs,
      page = pages.getValue(entry.category).title,
    )
  }
  return remember(pages, entries) { SettingsSearchIndex(pages, entries) }
}

/** [SettingsIndex] and the pages in the language of the app's windows, as tests read them. */
internal suspend fun loadSettingsSearchIndex(): SettingsSearchIndex {
  val pages = SettingsCategory.entries.associateWith { category ->
    pageEntry(category, category.titleText.load(), category.descriptionText.load())
  }
  val entries = SettingsIndex.map { entry ->
    SettingsEntry(
      category = entry.category,
      title = entry.title.text().load(),
      description = entry.description?.load().orEmpty(),
      keywords = entry.keywords?.let { splitKeywords(it.text().load()) }.orEmpty(),
      anchors = entry.anchors.map { it.text().load() },
      needs = entry.needs,
      page = pages.getValue(entry.category).title,
    )
  }
  return SettingsSearchIndex(pages, entries)
}

/** The page [category] as search finds it by its name, opening at the top. */
private fun pageEntry(category: SettingsCategory, title: String, description: String) =
  SettingsEntry(category, title, description, anchors = emptyList(), page = title)

private fun splitKeywords(text: String): List<String> =
  text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

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
 * @param index the pages and settings in the language shown.
 */
internal fun searchSettings(
  query: String,
  categories: List<SettingsCategory>,
  features: Set<SettingsFeature>,
  index: SettingsSearchIndex,
): List<SettingsHit> {
  val phrase = query.trim().lowercase()
  val words = phrase.split(WHITESPACE).filter { it.isNotEmpty() }
  if (words.isEmpty()) return emptyList()
  val pages = categories.mapNotNull { index.pages[it] }
  val candidates = pages + index.entries.filter { entry ->
    entry.category in categories && (entry.needs == null || entry.needs in features)
  }
  return candidates.mapNotNull { entry ->
    val title = entry.title.lowercase()
    val haystack = listOf(title, entry.description.lowercase(), entry.page.lowercase()) +
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

private val WHITESPACE = Regex("\\s+")
private const val MAX_WAIT_FRAMES = 30
private const val FLASH_IN_MS = 150
private const val FLASH_HOLD_MS = 900L
private const val FLASH_OUT_MS = 600
