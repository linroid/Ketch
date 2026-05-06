package com.linroid.ketch.app.icons

import androidx.compose.runtime.Immutable

/**
 * Ketch icon set.
 *
 * Every icon is authored against a 20×20 viewport with a 1.7px outlined stroke
 * (round caps + joins). The same path strings used by the JS mocks are kept
 * verbatim so there is no pixel drift between the web design and Compose.
 *
 * Render with [KetchIconImage].
 */
@Immutable
enum class KetchIcon(internal val data: IconData) {
  // Generic
  Plus(IconData.strokes("M10 4v12", "M4 10h12")),
  Close(IconData.strokes("M5 5l10 10", "M15 5L5 15")),
  Check(IconData.strokes("M4 10l4 4 8-8")),
  Chevron(IconData.strokes("M7 5l5 5-5 5")),
  ChevronDown(IconData.strokes("M5 8l5 5 5-5")),
  Search(IconData.paths(
    stroke = listOf("M9 3a6 6 0 100 12A6 6 0 009 3z", "M13.5 13.5l3 3"),
  )),
  Filter(IconData.strokes("M3 5h14", "M6 10h8", "M9 15h2")),
  Link(IconData.strokes(
    "M8 12l4-4", "M7 13l-2-2a3 3 0 014-4l1 1", "M13 7l2 2a3 3 0 01-4 4l-1-1",
  )),
  Folder(IconData.strokes(
    "M3 7a2 2 0 012-2h3l2 2h5a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2V7z",
  )),
  Settings(IconData.paths(
    stroke = listOf(
      "M10 7.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z",
      "M10 2v2M10 16v2M2 10h2M16 10h2",
      "M4.2 4.2l1.4 1.4M14.4 14.4l1.4 1.4M4.2 15.8l1.4-1.4M14.4 5.6l1.4-1.4",
    ),
  )),

  // Playback / actions
  Play(IconData.fills("M6 4l10 6-10 6V4z")),
  Pause(IconData.fills("M5 4h3v12H5z", "M12 4h3v12h-3z")),
  Stop(IconData.fills("M5 5h10v10H5z")),
  Retry(IconData.strokes(
    "M16 10a6 6 0 11-6-6 6 6 0 014.5 2", "M17 3v4h-4",
  )),
  More(IconData.fills(
    "M10 5.5a1.3 1.3 0 100-2.6 1.3 1.3 0 000 2.6z",
    "M10 11.3a1.3 1.3 0 100-2.6 1.3 1.3 0 000 2.6z",
    "M10 17.1a1.3 1.3 0 100-2.6 1.3 1.3 0 000 2.6z",
  )),
  Trash(IconData.strokes("M4 6h12", "M8 6V4h4v2", "M6 6l1 10h6l1-10")),

  // Sidebar nav
  All(IconData.strokes("M4 6h12", "M4 10h12", "M4 14h8")),
  Active(IconData.paths(
    stroke = listOf(
      "M10 7a3 3 0 100 6 3 3 0 000-6z",
      "M10 3v2M10 15v2M3 10h2M15 10h2M5 5l1.4 1.4M13.6 13.6L15 15M5 15l1.4-1.4M13.6 6.4L15 5",
    ),
  )),
  Queued(IconData.paths(
    stroke = listOf("M10 3a7 7 0 100 14 7 7 0 000-14z", "M10 6v4l3 2"),
  )),
  Scheduled(IconData.paths(
    stroke = listOf(
      "M3.5 5h13a1.5 1.5 0 011.5 1.5v9a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 012 15.5v-9A1.5 1.5 0 013.5 5z",
      "M2 8.5h16M7 3v3M13 3v3",
    ),
  )),
  Done(IconData.paths(
    stroke = listOf("M10 3a7 7 0 100 14 7 7 0 000-14z", "M7 10l2 2 4-4"),
  )),
  Failed(IconData.paths(
    stroke = listOf("M10 3a7 7 0 100 14 7 7 0 000-14z", "M7.5 7.5l5 5", "M12.5 7.5l-5 5"),
  )),

  // AI + infra
  Ai(IconData.strokes("M10 3l1.8 4.5L16 9l-4.2 1.5L10 15l-1.8-4.5L4 9l4.2-1.5L10 3z")),
  Speed(IconData.strokes("M3 14a7 7 0 0114 0", "M10 14l3-4")),
  Server(IconData.paths(
    stroke = listOf(
      "M3 4h14a1 1 0 011 1v3a1 1 0 01-1 1H3a1 1 0 01-1-1V5a1 1 0 011-1z",
      "M3 11h14a1 1 0 011 1v3a1 1 0 01-1 1H3a1 1 0 01-1-1v-3a1 1 0 011-1z",
    ),
    fill = listOf(
      "M6 6.5a0.7 0.7 0 100-1.4 0.7 0.7 0 000 1.4z",
      "M6 13.5a0.7 0.7 0 100-1.4 0.7 0.7 0 000 1.4z",
    ),
  )),
  Local(IconData.strokes(
    "M3.5 4h13a1.5 1.5 0 011.5 1.5v7a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 012 12.5v-7A1.5 1.5 0 013.5 4z",
    "M7 17h6", "M8 14v3", "M12 14v3",
  )),
  Remote(IconData.strokes(
    "M10 3a7 7 0 100 14 7 7 0 000-14z",
    "M3 10h14",
    "M10 3c3 4 3 10 0 14",
    "M10 3c-3 4-3 10 0 14",
  )),
}

/** Raw path data for an icon. */
@Immutable
internal data class IconData(
  /** Paths rendered with a stroke (outlined). */
  val strokes: List<String> = emptyList(),
  /** Paths rendered with a fill. */
  val fills: List<String> = emptyList(),
) {
  companion object {
    fun strokes(vararg d: String) = IconData(strokes = d.toList())
    fun fills(vararg d: String) = IconData(fills = d.toList())
    fun paths(stroke: List<String> = emptyList(), fill: List<String> = emptyList()) =
      IconData(strokes = stroke, fills = fill)
  }
}
