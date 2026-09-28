package com.linroid.ketch.app.icons

import androidx.compose.runtime.Immutable

/**
 * Ketch icon set.
 *
 * Every icon is authored against a 20×20 viewport with a 1.7px outlined stroke
 * (round caps + joins). Arc flags use explicit separators because Compose
 * PathParser does not accept the compact SVG flag syntax used in the web mock.
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
  ChevronLeft(IconData.strokes("M13 5l-5 5 5 5")),
  ChevronDown(IconData.strokes("M5 8l5 5 5-5")),
  Search(IconData.paths(
    stroke = listOf("M9 3a6 6 0 1 0 0 12A6 6 0 0 0 9 3z", "M13.5 13.5l3 3"),
  )),
  Filter(IconData.strokes("M3 5h14", "M6 10h8", "M9 15h2")),
  Link(IconData.strokes(
    "M8 12l4-4", "M7 13l-2-2a3 3 0 0 1 4-4l1 1", "M13 7l2 2a3 3 0 0 1 -4 4l-1-1",
  )),
  Folder(IconData.strokes(
    "M3 7a2 2 0 0 1 2-2h3l2 2h5a2 2 0 0 1 2 2v5a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2-2V7z",
  )),
  Settings(IconData.paths(
    stroke = listOf(
      "M10 7.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
      "M10 2v2M10 16v2M2 10h2M16 10h2",
      "M4.2 4.2l1.4 1.4M14.4 14.4l1.4 1.4M4.2 15.8l1.4-1.4M14.4 5.6l1.4-1.4",
    ),
  )),
  Appearance(IconData.paths(
    stroke = listOf("M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z"),
    fill = listOf("M10 3a7 7 0 0 1 0 14z"),
  )),

  // Playback / actions
  Play(IconData.fills("M6 4l10 6-10 6V4z")),
  Pause(IconData.fills("M5 4h3v12H5z", "M12 4h3v12h-3z")),
  Stop(IconData.fills("M5 5h10v10H5z")),
  Retry(IconData.strokes(
    "M16 10a6 6 0 1 1 -6-6 6 6 0 0 1 4.5 2", "M17 3v4h-4",
  )),
  More(IconData.fills(
    "M10 5.5a1.3 1.3 0 1 0 0-2.6 1.3 1.3 0 0 0 0 2.6z",
    "M10 11.3a1.3 1.3 0 1 0 0-2.6 1.3 1.3 0 0 0 0 2.6z",
    "M10 17.1a1.3 1.3 0 1 0 0-2.6 1.3 1.3 0 0 0 0 2.6z",
  )),
  Trash(IconData.strokes("M4 6h12", "M8 6V4h4v2", "M6 6l1 10h6l1-10")),

  // Sidebar nav
  All(IconData.strokes("M4 6h12", "M4 10h12", "M4 14h8")),
  Active(IconData.strokes(
    "M10 2.5v10", "M6.5 9L10 12.5L13.5 9",
    "M3.5 13.5v2.5a1.5 1.5 0 0 0 1.5 1.5h10a1.5 1.5 0 0 0 1.5-1.5v-2.5",
  )),
  Queued(IconData.paths(
    stroke = listOf("M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M10 6v4l3 2"),
  )),
  Scheduled(IconData.paths(
    stroke = listOf(
      "M3.5 5h13a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1 -1.5 1.5h-13A1.5 1.5 0 0 1 2 15.5v-9A1.5 1.5 0 0 1 3.5 5z",
      "M2 8.5h16M7 3v3M13 3v3",
    ),
  )),
  Done(IconData.paths(
    stroke = listOf("M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M7 10l2 2 4-4"),
  )),
  Failed(IconData.paths(
    stroke = listOf("M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M7.5 7.5l5 5", "M12.5 7.5l-5 5"),
  )),

  // AI + infra
  Ai(IconData.strokes(
    "M8 4C8.8 8.3 9.7 9.2 14 10C9.7 10.8 8.8 11.7 8 16C7.2 11.7 6.3 10.8 2 10C6.3 9.2 7.2 8.3 8 4Z",
    "M15.5 2v5", "M13 4.5h5",
  )),
  Speed(IconData.strokes("M3 14a7 7 0 0 1 14 0", "M10 14l3-4")),
  Server(IconData.paths(
    stroke = listOf(
      "M3 4h14a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1H3a1 1 0 0 1 -1-1V5a1 1 0 0 1 1-1z",
      "M3 11h14a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1H3a1 1 0 0 1 -1-1v-3a1 1 0 0 1 1-1z",
    ),
    fill = listOf(
      "M6 6.5a0.7 0.7 0 1 0 0-1.4 0.7 0.7 0 0 0 0 1.4z",
      "M6 13.5a0.7 0.7 0 1 0 0-1.4 0.7 0.7 0 0 0 0 1.4z",
    ),
  )),
  Local(IconData.strokes(
    "M3.5 4h13a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-13A1.5 1.5 0 0 1 2 12.5v-7A1.5 1.5 0 0 1 3.5 4z",
    "M7 17h6", "M8 14v3", "M12 14v3",
  )),
  Remote(IconData.strokes(
    "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z",
    "M3 10h14",
    "M10 3c3 4 3 10 0 14",
    "M10 3c-3 4-3 10 0 14",
  )),
  Network(IconData.paths(
    stroke = listOf(
      "M2.5 7.5a11 11 0 0 1 15 0",
      "M5 10.5a7 7 0 0 1 10 0",
      "M7.5 13.5a3.5 3.5 0 0 1 5 0",
    ),
    fill = listOf("M10 17.2a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"),
  )),
  Info(IconData.paths(
    stroke = listOf("M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M10 9.5v4.5"),
    fill = listOf("M10 7.6a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"),
  )),

  // File types, see FileKind
  FileTorrent(IconData.paths(
    stroke = listOf("M3.5 3.5v6.5a6.5 6.5 0 0 0 13 0V3.5h-4.5v6.5a2 2 0 0 1 -4 0V3.5z"),
    fill = listOf("M3.5 3.5H8V7H3.5z", "M12 3.5h4.5V7H12z"),
  )),
  FileVideo(IconData.strokes(
    FRAME, "M6.2 3.5v13", "M13.8 3.5v13",
    "M2.5 7.7h3.7", "M2.5 12.3h3.7", "M13.8 7.7h3.7", "M13.8 12.3h3.7",
  )),
  FileAudio(IconData.paths(
    stroke = listOf("M8 15V4.8l8.5-1.8v10.5"),
    fill = listOf(
      "M6 12.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z",
      "M14.3 11.3a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z",
    ),
  )),
  FileSubtitle(IconData.strokes(
    FRAME, "M5.5 10.5h3.5", "M11.5 10.5h3", "M5.5 13.5h6", "M14 13.5h0.5",
  )),
  FileImage(IconData.paths(
    stroke = listOf(FRAME, "M2.5 14l4.2-4.2 4.3 4.3 2-2 4.5 4"),
    fill = listOf("M13 5.8a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z"),
  )),
  FileDesign(IconData.paths(
    stroke = listOf("M10 17.5L4.8 10 7.5 3h5l2.7 7z", "M10 17.5v-5.3"),
    fill = listOf("M10 9a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z"),
  )),
  FileDocument(IconData.strokes(PAGE, PAGE_FOLD, "M7.5 7h1.5", "M7.5 10h5", "M7.5 13h5")),
  FilePdf(IconData.strokes(
    PAGE, PAGE_FOLD,
    "M7 15c1.8-1.6 3.6-5.2 2.9-6.6-0.6-1.2-1.8 0.3-0.9 2 0.9 1.8 2.9 3.2 4.5 3 " +
      "1.2-0.2 0.8-1.4-0.5-1.3-2 0.1-4 1.3-6 2.9z",
  )),
  FileEbook(IconData.strokes(
    "M10 5.5C8.5 4.2 6.3 3.8 2.5 4v11.5c3.8-0.2 6 0.2 7.5 1.5 1.5-1.3 3.7-1.7 7.5-1.5V4" +
      "c-3.8-0.2-6 0.2-7.5 1.5z",
    "M10 5.5V17",
  )),
  FileSpreadsheet(IconData.strokes(FRAME, "M2.5 8h15", "M2.5 12.3h15", "M7.5 3.5v13")),
  FilePresentation(IconData.strokes(
    "M2.5 3.5h15", "M4 3.5v8a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-8",
    "M10 12.5v2.5", "M6.5 17.5l3.5-2.5 3.5 2.5",
    "M7.5 9.5v-1", "M10 9.5V6.5", "M12.5 9.5v-2",
  )),
  FileText(IconData.strokes("M3.5 4.5h13", "M3.5 8.2h13", "M3.5 11.9h13", "M3.5 15.6h8")),
  FileCode(IconData.strokes("M6.5 5.5L2.5 10l4 4.5", "M13.5 5.5l4 4.5-4 4.5", "M11.3 3.5l-2.6 13")),
  FileData(IconData.strokes(
    "M7.5 3.5c-1.7 0-2.5 0.8-2.5 2.2v2.1c0 1.3-0.7 2.2-2 2.2 1.3 0 2 0.9 2 2.2v2.1" +
      "c0 1.4 0.8 2.2 2.5 2.2",
    "M12.5 3.5c1.7 0 2.5 0.8 2.5 2.2v2.1c0 1.3 0.7 2.2 2 2.2-1.3 0-2 0.9-2 2.2v2.1" +
      "c0 1.4-0.8 2.2-2.5 2.2",
  )),
  FileDatabase(IconData.strokes(
    "M3.5 5.5a6.5 2.5 0 1 0 13 0 6.5 2.5 0 1 0 -13 0z",
    "M3.5 5.5v9a6.5 2.5 0 0 0 13 0v-9",
    "M3.5 10a6.5 2.5 0 0 0 13 0",
  )),
  FileWeb(IconData.strokes(
    "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z",
    "M3 10h14",
    "M10 3c3 4 3 10 0 14",
    "M10 3c-3 4-3 10 0 14",
  )),
  FileArchive(IconData.strokes(
    "M3.5 3.5h13a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-13a1 1 0 0 1 -1-1v-2a1 1 0 0 1 1-1z",
    "M4 7.5v8A1.5 1.5 0 0 0 5.5 17h9a1.5 1.5 0 0 0 1.5-1.5v-8",
    "M8 11h4",
  )),
  FileDiskImage(IconData.paths(
    stroke = listOf(
      "M10 2.8a7.2 7.2 0 1 0 0 14.4 7.2 7.2 0 0 0 0-14.4z",
      "M5.6 8.6a4.6 4.6 0 0 1 3-3",
    ),
    fill = listOf("M10 8.1a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 0 0 0-3.8z"),
  )),
  FileApp(IconData.strokes(
    "M10 2.5l6.5 3.6v7.8L10 17.5l-6.5-3.6V6.1z", "M3.5 6.1L10 9.7l6.5-3.6", "M10 9.7v7.8",
  )),
  FileModel3d(IconData.strokes("M10 2.5L3 14l7 3.5 7-3.5z", "M10 2.5v15")),
  FileFont(IconData.strokes(
    "M2.5 16.5L7 4h1.2l4.5 12.5", "M4.3 12h6.6",
    "M15 11a2.2 2.6 0 1 0 0 5.2 2.2 2.6 0 0 0 0-5.2z", "M17.3 10v6.5",
  )),
  FileKey(IconData.strokes(
    "M6.5 10.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z",
    "M9 11.5l7.5-7.5", "M14 6.5l2.2 2.2", "M11.8 8.7l1.7 1.7",
  )),
  FileEmail(IconData.strokes(
    "M4 4.5h12A1.5 1.5 0 0 1 17.5 6v8a1.5 1.5 0 0 1 -1.5 1.5H4A1.5 1.5 0 0 1 2.5 14V6" +
      "A1.5 1.5 0 0 1 4 4.5z",
    "M3 5.5l7 5.5 7-5.5",
  )),
  FileGeneric(IconData.strokes(PAGE, PAGE_FOLD)),
}

/** Page outline with a dog-eared top-right corner, shared by document-like file icons. */
private const val PAGE =
  "M11.5 2.5H6A1.5 1.5 0 0 0 4.5 4v12A1.5 1.5 0 0 0 6 17.5h8a1.5 1.5 0 0 0 1.5-1.5V6.5z"
private const val PAGE_FOLD = "M11.5 2.5v4h4"

/** Landscape rounded frame shared by media and table file icons. */
private const val FRAME =
  "M4 3.5h12A1.5 1.5 0 0 1 17.5 5v10a1.5 1.5 0 0 1 -1.5 1.5H4A1.5 1.5 0 0 1 2.5 15V5" +
    "A1.5 1.5 0 0 1 4 3.5z"

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
