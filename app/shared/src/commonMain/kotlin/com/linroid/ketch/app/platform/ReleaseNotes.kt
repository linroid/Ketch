package com.linroid.ketch.app.platform

import kotlin.time.Instant

/** What a change in a release does for the people who use Ketch. */
enum class ReleaseChangeKind {
  /** Something new: a `feat` pull request. */
  Feature,

  /** Something that works again: a `fix` pull request. */
  Fix,

  /** Something that works better, such as a `perf` pull request or one without a type. */
  Improvement,
}

/**
 * One change a release lists.
 *
 * @property summary what it does, from the title of its pull request without the type, with a
 *   capital first letter: "Pause and resume downloads with a double-click".
 * @property area the part of Ketch it touches, the scope of its title, such as `android`;
 *   `null` when the title names none.
 * @property link its pull request, which tells the same change listed by two releases apart;
 *   `null` when the line names none.
 */
data class ReleaseChange(
  val kind: ReleaseChangeKind,
  val summary: String,
  val area: String? = null,
  val link: String? = null,
)

/**
 * What changed in one release.
 *
 * @property version its version, such as `0.3.1`.
 * @property pageUrl its web page, with the full notes and the downloads.
 * @property publishedAt when it was published; `null` when unknown.
 * @property changes what it brings, in the order its notes list them, without the changes that
 *   only concern Ketch's developers, such as tests, builds and dependency updates.
 */
data class ReleaseNotes(
  val version: String,
  val pageUrl: String,
  val publishedAt: Instant? = null,
  val changes: List<ReleaseChange> = emptyList(),
) {
  companion object {
    /** The notes of a release from its notes in Markdown, as GitHub publishes them. */
    fun parse(version: String, pageUrl: String, publishedAt: Instant?, markdown: String) =
      ReleaseNotes(version, pageUrl, publishedAt, releaseChanges(markdown))
  }
}

/** Release notes the user asked to read: those after [since] up to [version]. */
data class ReleaseNotesRequest(
  val version: String,
  val since: String? = null,
)

/**
 * The changes [markdown] lists the way GitHub generates release notes: a line per pull request,
 * `* <title> by @<author> in <link>`. Other lines, such as the download list above them and the
 * new contributors below, are skipped, as are titles of types and scopes that only concern
 * Ketch's developers.
 */
fun releaseChanges(markdown: String): List<ReleaseChange> = markdown.lineSequence()
  .mapNotNull { line -> PULL_REQUEST_LINE.matchEntire(line.trim()) }
  .mapNotNull { match -> changeOf(match.groupValues[1], match.groupValues[2]) }
  .toList()

/**
 * [notes], newest first, without the changes a newer release already lists, as a release's
 * notes repeat those of its release candidates when GitHub compares it with the release before
 * them. A release left with nothing new is dropped; one that listed nothing to begin with stays.
 */
fun List<ReleaseNotes>.withoutRepeats(): List<ReleaseNotes> {
  val seen = mutableSetOf<String>()
  return mapNotNull { release ->
    val changes = release.changes.filter { change -> change.link == null || seen.add(change.link) }
    release.takeIf { changes.isNotEmpty() || release.changes.isEmpty() }?.copy(changes = changes)
  }
}

/** The change a pull request titled [title] makes, or `null` when it only concerns developers. */
private fun changeOf(title: String, link: String): ReleaseChange? {
  val conventional = CONVENTIONAL_TITLE.matchEntire(title)
  if (conventional == null) {
    val kind = when (title.substringBefore(' ').lowercase()) {
      in FIX_VERBS -> ReleaseChangeKind.Fix
      in FEATURE_VERBS -> ReleaseChangeKind.Feature
      else -> ReleaseChangeKind.Improvement
    }
    return ReleaseChange(kind, title.capitalized(), link = link)
  }
  val (type, scope, summary) = conventional.destructured
  val area = scope.trim().lowercase().ifEmpty { null }
  if (type.lowercase() in DEVELOPER_TYPES || area in DEVELOPER_AREAS) return null
  val kind = when (type.lowercase()) {
    "feat" -> ReleaseChangeKind.Feature
    "fix" -> ReleaseChangeKind.Fix
    else -> ReleaseChangeKind.Improvement
  }
  return ReleaseChange(kind, summary.trim().capitalized(), area, link)
}

private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

/** `* <title> by @<author> in <link>`, as GitHub writes each pull request. */
private val PULL_REQUEST_LINE = Regex("""^[*-]\s+(.+?)\s+by\s+@\S+\s+in\s+(https?://\S+)$""")

/** `<type>(<scope>)!: <summary>`, a Conventional Commits title. */
private val CONVENTIONAL_TITLE = Regex("""^([A-Za-z]+)(?:\(([^)]*)\))?!?:\s*(.+)$""")

/** Types of changes the people who use Ketch never notice. */
private val DEVELOPER_TYPES = setOf("build", "chore", "ci", "docs", "refactor", "style", "test")

/** Scopes of changes only Ketch's developers notice: dependency updates, whatever their type. */
private val DEVELOPER_AREAS = setOf("deps", "deps-dev")

/** First words of titles without a type that fix something. */
private val FIX_VERBS = setOf("fix", "fixes", "fixed")

/** First words of titles without a type that add something. */
private val FEATURE_VERBS = setOf("add", "adds", "support", "supports", "introduce")
