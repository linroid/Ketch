package com.linroid.ketch.app.ui.palette

import androidx.compose.runtime.Immutable

/** How well a palette row matches the typed text, best first. */
internal enum class MatchTier {
  /** Made from the typed text itself, such as a pasted link or a speed. */
  Direct,

  /** The title starts with the text. */
  ExactPrefix,

  /** Each typed word starts a word of the title, in order. */
  WordPrefix,

  /** The typed letters appear in the title in order, close together. */
  Subsequence,

  /** Offered whatever was typed, after every match. */
  Fallback,
}

/**
 * How a row matches.
 *
 * @property tier how well.
 * @property span for [MatchTier.Subsequence], how many characters the matched letters spread
 *   over; tighter matches come first.
 */
@Immutable
internal data class PaletteMatch(val tier: MatchTier, val span: Int = 0)

/** A line of the palette's list: a heading or a row. */
@Immutable
internal sealed interface PaletteEntry {
  /** A heading over the rows below it, such as "Recent". */
  data class Header(val title: String) : PaletteEntry

  /** The row [item], [index] among the rows of the list. */
  data class Row(val item: PaletteItem, val index: Int) : PaletteEntry
}

/**
 * The palette's list for what was typed.
 *
 * @property entries headings and rows in display order.
 */
@Immutable
internal data class PaletteResults(val entries: List<PaletteEntry>) {
  /** The rows alone, in display order; the highlight moves over these. */
  val items: List<PaletteItem> = entries.filterIsInstance<PaletteEntry.Row>().map { it.item }

  /** Position in [entries] of the [index]th row. */
  fun entryIndex(index: Int): Int =
    entries.indexOfFirst { it is PaletteEntry.Row && it.index == index }
}

/**
 * Ranks [items] for [query]: rows made from the text first, then titles that start with it,
 * then titles whose words start with its words, then titles holding its letters in order, then
 * the fallbacks. Among rows that match equally well, the [recent] ones come first, most recent
 * first, then the providers in [PaletteProvider] order. Rows that do not match are left out.
 *
 * A query starting with "/" only looks for Settings pages, such as "/speed". With nothing
 * typed, the list shows the recent rows under "Recent", then every row that does not wait for
 * a query under its provider's heading.
 */
internal fun paletteResults(
  query: String,
  items: List<PaletteItem>,
  recent: List<String> = emptyList(),
): PaletteResults {
  if (query.isBlank()) return browse(items, recent)
  val slash = query.trimStart().startsWith("/")
  val ranked = items.withIndex()
    .mapNotNull { (index, item) ->
      val match = if (slash) matchSlash(query, item) else matchItem(query, item)
      match?.let { Ranked(item, it, recentRank(item, recent), index) }
    }
    .sortedWith(
      compareBy<Ranked>(
        { it.match.tier },
        { it.recent },
        { it.item.provider },
        { it.match.span },
        { it.index },
      ),
    )
    .take(MAX_RESULTS)
  return PaletteResults(ranked.mapIndexed { index, it -> PaletteEntry.Row(it.item, index) })
}

/**
 * How [item] matches [query], or `null` when it does not: its own [PaletteItem.matchQuery]
 * stands in for the query when it has one, and the best of its title and keywords counts.
 */
internal fun matchItem(query: String, item: PaletteItem): PaletteMatch? {
  if (item.direct) return PaletteMatch(MatchTier.Direct)
  if (item.provider == PaletteProvider.Fallback) return PaletteMatch(MatchTier.Fallback)
  val text = item.matchQuery ?: query
  if (text.isBlank()) return PaletteMatch(MatchTier.ExactPrefix)
  return (listOf(item.title) + item.keywords)
    .mapNotNull { matchText(text, it) }
    .minWithOrNull(compareBy({ it.tier }, { it.span }))
}

/**
 * How [text] matches [query], ignoring case and runs of whitespace: as a prefix, as word
 * prefixes in order, or as letters in order spread over at most three times as many
 * characters; `null` when it does not.
 */
internal fun matchText(query: String, text: String): PaletteMatch? {
  val wanted = normalize(query)
  if (wanted.isEmpty()) return null
  val candidate = normalize(text)
  if (candidate.startsWith(wanted)) return PaletteMatch(MatchTier.ExactPrefix)
  if (wordPrefixes(words(wanted), words(candidate))) return PaletteMatch(MatchTier.WordPrefix)
  val letters = wanted.filterNot { it.isWhitespace() }
  val span = subsequenceSpan(letters, candidate) ?: return null
  return if (span <= letters.length * SPREAD) PaletteMatch(MatchTier.Subsequence, span) else null
}

// "/speed" matches only the slash keywords of Settings pages.
private fun matchSlash(query: String, item: PaletteItem): PaletteMatch? =
  item.keywords.filter { it.startsWith("/") }
    .mapNotNull { matchText(query, it) }
    .minWithOrNull(compareBy({ it.tier }, { it.span }))

private fun browse(items: List<PaletteItem>, recent: List<String>): PaletteResults {
  val byId = items.filter { it.id.isNotEmpty() }.associateBy { it.id }
  val recentItems = recent.mapNotNull { byId[it] }
  val shown = recentItems.mapTo(HashSet()) { it.id }
  val entries = mutableListOf<PaletteEntry>()
  var index = 0
  fun section(title: String, rows: List<PaletteItem>) {
    if (rows.isEmpty()) return
    entries += PaletteEntry.Header(title)
    for (item in rows) entries += PaletteEntry.Row(item, index++)
  }
  section(RECENT, recentItems)
  val rest = items.filter { !it.searchOnly && !it.direct && it.id !in shown }
    .groupBy { it.provider }
  for (provider in PaletteProvider.entries) section(provider.title, rest[provider].orEmpty())
  return PaletteResults(entries)
}

private class Ranked(
  val item: PaletteItem,
  val match: PaletteMatch,
  val recent: Int,
  val index: Int,
)

private fun recentRank(item: PaletteItem, recent: List<String>): Int {
  if (item.id.isEmpty()) return Int.MAX_VALUE
  val position = recent.indexOf(item.id)
  return if (position < 0) Int.MAX_VALUE else position
}

private fun normalize(value: String): String =
  value.trim().lowercase().replace(WHITESPACE, " ")

private fun words(value: String): List<String> {
  val words = mutableListOf<String>()
  var start = -1
  for (index in 0..value.length) {
    val inWord = index < value.length && value[index].isLetterOrDigit()
    if (inWord && start < 0) start = index
    if (!inWord && start >= 0) {
      words += value.substring(start, index)
      start = -1
    }
  }
  return words
}

// Each wanted word must start a word of the candidate after the one the previous word started.
private fun wordPrefixes(wanted: List<String>, candidate: List<String>): Boolean {
  if (wanted.isEmpty()) return false
  var next = 0
  for (word in wanted) {
    val found = (next until candidate.size).firstOrNull { candidate[it].startsWith(word) }
      ?: return false
    next = found + 1
  }
  return true
}

// The fewest characters of [candidate] that hold [letters] in order, from any start.
private fun subsequenceSpan(letters: String, candidate: String): Int? {
  if (letters.isEmpty()) return null
  var best: Int? = null
  for (start in candidate.indices) {
    if (candidate[start] != letters[0]) continue
    var position = start
    var matched = 1
    while (matched < letters.length) {
      position = candidate.indexOf(letters[matched], position + 1)
      if (position < 0) break
      matched++
    }
    if (matched < letters.length) break
    val span = position - start + 1
    if (best == null || span < best) best = span
  }
  return best
}

private const val RECENT = "Recent"
private const val MAX_RESULTS = 60
private const val SPREAD = 3
private val WHITESPACE = Regex("""\s+""")
