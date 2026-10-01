package com.linroid.ketch.app.icons

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

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
  ChevronUp(IconData.strokes("M5 12l5-5 5 5")),
  ChevronDown(IconData.strokes("M5 8l5 5 5-5")),
  Search(IconData.paths(
    stroke = listOf("M9 3a6 6 0 1 0 0 12A6 6 0 0 0 9 3z", "M13.5 13.5l3 3"),
  )),
  Filter(IconData.strokes("M3 5h14", "M6 10h8", "M9 15h2")),
  Link(IconData.strokes(
    "M8 12l4-4", "M7 13l-2-2a3 3 0 0 1 4-4l1 1", "M13 7l2 2a3 3 0 0 1 -4 4l-1-1",
  )),
  Folder(IconData.strokes(FOLDER)),
  Settings(IconData.paths(
    stroke = listOf(
      "M10 7.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
      "M10 2v2M10 16v2M2 10h2M16 10h2",
      "M4.2 4.2l1.4 1.4M14.4 14.4l1.4 1.4M4.2 15.8l1.4-1.4M14.4 5.6l1.4-1.4",
    ),
  )),
  Appearance(IconData.paths(
    stroke = listOf(CIRCLE),
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
  Undo(IconData.strokes("M7 3.5L3.5 7 7 10.5", "M3.5 7h8.5a4.5 4.5 0 0 1 0 9H9")),

  // File actions
  Open(IconData.strokes("M5.5 14.5l9-9", "M8 5.5h6.5V12")),
  Reveal(IconData.strokes(FOLDER, "M7 11.5h6", "M10.5 9l2.5 2.5-2.5 2.5")),
  Copy(IconData.strokes(
    "M4.5 7h7A1.5 1.5 0 0 1 13 8.5v7a1.5 1.5 0 0 1 -1.5 1.5h-7A1.5 1.5 0 0 1 3 15.5v-7" +
      "A1.5 1.5 0 0 1 4.5 7z",
    "M7 7V4.5A1.5 1.5 0 0 1 8.5 3h7A1.5 1.5 0 0 1 17 4.5v7a1.5 1.5 0 0 1 -1.5 1.5H13",
  )),
  Drop(IconData.strokes(
    "M3 6.5V4.5A1.5 1.5 0 0 1 4.5 3h2", "M13.5 3h2A1.5 1.5 0 0 1 17 4.5v2",
    "M17 13.5v2a1.5 1.5 0 0 1 -1.5 1.5h-2", "M6.5 17h-2A1.5 1.5 0 0 1 3 15.5v-2",
    "M9 3h2", "M9 17h2", "M3 9v2", "M17 9v2",
    "M10 6.5V13", "M7.5 10.5L10 13l2.5-2.5",
  )),

  // Sidebar nav
  All(IconData.strokes("M4 6h12", "M4 10h12", "M4 14h8")),
  Active(IconData.strokes(
    "M10 2.5v10", "M6.5 9L10 12.5L13.5 9",
    "M3.5 13.5v2.5a1.5 1.5 0 0 0 1.5 1.5h10a1.5 1.5 0 0 0 1.5-1.5v-2.5",
  )),
  Queued(IconData.paths(
    stroke = listOf(CIRCLE, "M10 6v4l3 2"),
  )),
  Scheduled(IconData.paths(
    stroke = listOf(
      "M3.5 5h13a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1 -1.5 1.5h-13A1.5 1.5 0 0 1 2 15.5v-9" +
        "A1.5 1.5 0 0 1 3.5 5z",
      "M2 8.5h16M7 3v3M13 3v3",
    ),
  )),
  Done(IconData.paths(
    stroke = listOf(CIRCLE, "M7 10l2 2 4-4"),
  )),
  Failed(IconData.paths(
    stroke = listOf(CIRCLE, "M7.5 7.5l5 5", "M12.5 7.5l-5 5"),
  )),

  // Navigation
  Discover(IconData.paths(
    stroke = listOf("M8.5 2.5a6 6 0 1 0 0 12 6 6 0 0 0 0-12z", "M12.9 12.9l4.1 4.1"),
    fill = listOf(
      "M8.5 5.5C8.9 7.6 9.4 8.1 11.5 8.5C9.4 8.9 8.9 9.4 8.5 11.5" +
        "C8.1 9.4 7.6 8.9 5.5 8.5C7.6 8.1 8.1 7.6 8.5 5.5Z",
    ),
  )),
  Devices(IconData.strokes(
    "M15 5.5V5A1.5 1.5 0 0 0 13.5 3.5h-10A1.5 1.5 0 0 0 2 5v6.5A1.5 1.5 0 0 0 3.5 13H10",
    "M7 13v3.5", "M5 16.5h4",
    "M13.7 8h3.1A1.2 1.2 0 0 1 18 9.2v7.1a1.2 1.2 0 0 1 -1.2 1.2h-3.1a1.2 1.2 0 0 1 -1.2-1.2" +
      "V9.2A1.2 1.2 0 0 1 13.7 8z",
  )),
  Bell(IconData.strokes("M5 12.5V8a5 5 0 0 1 10 0v4.5l1.5 2h-13z", "M8 16.5a2 2 0 0 0 4 0")),
  Command(IconData.strokes(
    "M12.5 5v10a2.5 2.5 0 1 0 2.5-2.5H5a2.5 2.5 0 1 0 2.5 2.5V5a2.5 2.5 0 1 0 -2.5 2.5" +
      "h10a2.5 2.5 0 1 0 -2.5-2.5z",
  )),
  Columns(IconData.strokes(FRAME, "M7.5 3.5v13", "M12.5 3.5v13")),
  Inspector(IconData.strokes(FRAME, "M12.5 3.5v13")),
  Sidebar(IconData.strokes(FRAME, "M7.5 3.5v13")),
  Pennant(IconData.strokes("M5.5 2.5v15", "M5.5 3.5L16 8 5.5 12.5")),
  QrCode(IconData.paths(
    stroke = listOf(
      "M4 3h3.5a1 1 0 0 1 1 1v3.5a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1-1V4a1 1 0 0 1 1-1z",
      "M12.5 3H16a1 1 0 0 1 1 1v3.5a1 1 0 0 1 -1 1h-3.5a1 1 0 0 1 -1-1V4a1 1 0 0 1 1-1z",
      "M4 11.5h3.5a1 1 0 0 1 1 1V16a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1-1v-3.5a1 1 0 0 1 1-1z",
    ),
    fill = listOf(
      "M5 5h1.5v1.5H5z", "M13.5 5H15v1.5h-1.5z", "M5 13.5h1.5V15H5z",
      "M11.5 11.5h2.2v2.2h-2.2z", "M14.8 14.8h2.2v2.2h-2.2z", "M11.5 14.8h2.2v2.2h-2.2z",
    ),
  )),

  // Status
  CheckCircle(IconData.paths(
    stroke = listOf(CIRCLE, "M7 10l2 2 4-4"),
  )),
  Warning(IconData.paths(
    stroke = listOf(
      "M8.7 3.8a1.5 1.5 0 0 1 2.6 0l6.3 11a1.5 1.5 0 0 1 -1.3 2.2H3.7a1.5 1.5 0 0 1 -1.3-2.2z",
      "M10 7.8v3.7",
    ),
    fill = listOf("M10 15a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"),
  )),

  // Brand / speed. Sail is the art/icon.svg mark fitted to the grid, mizzen as the soft fill.
  Sail(IconData.paths(
    fill = listOf(
      "M4.82 2.49Q5.41 3.18 5.95 3.9L4.63 3.9L4.63 2.56Q4.63 2.25 4.82 2.49Z" +
        "M4.63 4.34L6.29 4.34Q6.89 5.15 7.45 5.98L4.63 5.98Z" +
        "M4.63 6.42L7.74 6.42Q8.27 7.23 8.76 8.07L4.63 8.07Z" +
        "M4.63 8.51L9.02 8.51Q9.48 9.32 9.91 10.15L4.63 10.15Z" +
        "M4.63 10.59L10.13 10.59Q10.53 11.4 10.9 12.23L4.63 12.23Z" +
        "M4.63 12.67L11.09 12.67Q11.44 13.49 11.76 14.32L4.63 14.32Z",
      "M2.52 15.03L17.57 15.03Q18.28 15.03 17.81 15.56C16.26 17.06 13.76 17.64 10.46 17.64" +
        "C7.15 17.64 4.51 17.24 2.29 15.59Q1.61 15.03 2.52 15.03Z",
    ),
    softFill = listOf(
      "M12.66 6.65Q13.25 7.34 13.79 8.07L12.47 8.07L12.47 6.72Q12.47 6.42 12.66 6.65Z" +
        "M12.47 8.51L14.11 8.51Q14.67 9.31 15.18 10.15L12.47 10.15Z" +
        "M12.47 10.59L15.43 10.59Q15.89 11.4 16.29 12.23L12.47 12.23Z" +
        "M12.47 12.67L16.5 12.67Q16.87 13.48 17.19 14.32L12.47 14.32Z",
    ),
  )),
  SlowLane(IconData.strokes("M3 6h14", "M3 14h7", "M7.5 11.5L10 14l-2.5 2.5")),
  Auto(IconData.strokes(
    "M10 2.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11z", "M10 5.3V8l1.9 1.3", "M3.5 17h13",
  )),

  // Engine
  Lanes(IconData.paths(
    stroke = listOf("M3 4.5h8", "M3 10h11.5", "M3 15.5h4.5"),
    fill = listOf(
      "M12.6 6.1a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z",
      "M16.1 11.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z",
      "M9.1 17.1a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z",
    ),
  )),
  Bolt(IconData.strokes("M11 2L4.5 11h5l-1 7 6.5-9h-5z")),

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
    "M3.5 4h13a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-13A1.5 1.5 0 0 1 2 12.5v-7" +
      "A1.5 1.5 0 0 1 3.5 4z",
    "M7 17h6", "M8 14v3", "M12 14v3",
  )),
  Remote(IconData.strokes(
    CIRCLE,
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
    stroke = listOf(CIRCLE, "M10 9.5v4.5"),
    fill = listOf("M10 7.6a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"),
  )),

  // Devices
  Laptop(IconData.strokes(
    "M5.5 4h9A1.5 1.5 0 0 1 16 5.5V13H4V5.5A1.5 1.5 0 0 1 5.5 4z", "M2 16h16",
  )),
  Desktop(IconData.strokes(
    "M3.5 3.5h13A1.5 1.5 0 0 1 18 5v7.5a1.5 1.5 0 0 1 -1.5 1.5h-13A1.5 1.5 0 0 1 2 12.5V5" +
      "A1.5 1.5 0 0 1 3.5 3.5z",
    "M10 14v3", "M6.5 17h7",
  )),
  Phone(IconData.strokes(
    "M7 2h6a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 13 18H7a1.5 1.5 0 0 1 -1.5-1.5v-13" +
      "A1.5 1.5 0 0 1 7 2z",
    "M9 15.5h2",
  )),
  Tablet(IconData.strokes(
    "M4.5 2.5h11A1.5 1.5 0 0 1 17 4v12a1.5 1.5 0 0 1 -1.5 1.5h-11A1.5 1.5 0 0 1 3 16V4" +
      "a1.5 1.5 0 0 1 1.5-1.5z",
    "M8.5 15h3",
  )),
  Browser(IconData.paths(
    stroke = listOf(FRAME, "M2.5 7h15"),
    fill = listOf(
      "M5 6a0.7 0.7 0 1 0 0-1.4 0.7 0.7 0 0 0 0 1.4z",
      "M7.4 6a0.7 0.7 0 1 0 0-1.4 0.7 0.7 0 0 0 0 1.4z",
    ),
  )),
  Fleet(IconData.paths(
    stroke = listOf("M10 5L4.5 15h11z"),
    fill = listOf(
      "M10 7.3a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6z",
      "M4.5 17.3a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6z",
      "M15.5 17.3a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6z",
    ),
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
    CIRCLE,
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
  FileGeneric(IconData.strokes(PAGE, PAGE_FOLD));

  /**
   * The icon as a 20×20 [ImageVector], built on first use and cached so drawing never
   * parses path data.
   */
  internal val imageVector: ImageVector by lazy { data.toImageVector(name) }
}

/** Seven-unit circle centered in the viewport, shared by status and globe icons. */
private const val CIRCLE = "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14z"

/** Folder with a tab on the top left, shared by [KetchIcon.Folder] and [KetchIcon.Reveal]. */
private const val FOLDER =
  "M3 7a2 2 0 0 1 2-2h3l2 2h5a2 2 0 0 1 2 2v5a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2-2V7z"

/** Page outline with a dog-eared top-right corner, shared by document-like file icons. */
private const val PAGE =
  "M11.5 2.5H6A1.5 1.5 0 0 0 4.5 4v12A1.5 1.5 0 0 0 6 17.5h8a1.5 1.5 0 0 0 1.5-1.5V6.5z"
private const val PAGE_FOLD = "M11.5 2.5v4h4"

/** Landscape rounded frame shared by media, table and window icons. */
private const val FRAME =
  "M4 3.5h12A1.5 1.5 0 0 1 17.5 5v10a1.5 1.5 0 0 1 -1.5 1.5H4A1.5 1.5 0 0 1 2.5 15V5" +
    "A1.5 1.5 0 0 1 4 3.5z"

internal const val ICON_VIEWPORT = 20f
internal const val ICON_STROKE_WIDTH = 1.7f

/** Alpha of [IconData.softFills], matching the mizzen sail of the brand mark. */
internal const val ICON_SOFT_FILL_ALPHA = 0.65f

/** Raw path data for an icon. */
@Immutable
internal data class IconData(
  /** Paths rendered with a stroke (outlined). */
  val strokes: List<String> = emptyList(),
  /** Paths rendered with a fill. */
  val fills: List<String> = emptyList(),
  /** Paths filled at [ICON_SOFT_FILL_ALPHA] of the tint. */
  val softFills: List<String> = emptyList(),
) {
  companion object {
    fun strokes(vararg d: String) = IconData(strokes = d.toList())
    fun fills(vararg d: String) = IconData(fills = d.toList())
    fun paths(
      stroke: List<String> = emptyList(),
      fill: List<String> = emptyList(),
      softFill: List<String> = emptyList(),
    ) = IconData(strokes = stroke, fills = fill, softFills = softFill)
  }
}

/**
 * Builds a black [ImageVector] from this data; [KetchIconImage] tints it when drawing.
 * Strokes are added before fills, so fills paint over them.
 */
internal fun IconData.toImageVector(name: String): ImageVector {
  val brush = SolidColor(Color.Black)
  val builder = ImageVector.Builder(
    name = name,
    defaultWidth = ICON_VIEWPORT.dp,
    defaultHeight = ICON_VIEWPORT.dp,
    viewportWidth = ICON_VIEWPORT,
    viewportHeight = ICON_VIEWPORT,
  )
  strokes.forEach { d ->
    builder.addPath(
      pathData = PathParser().parsePathString(d).toNodes(),
      stroke = brush,
      strokeLineWidth = ICON_STROKE_WIDTH,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
  }
  fills.forEach { d ->
    builder.addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = brush)
  }
  softFills.forEach { d ->
    builder.addPath(
      pathData = PathParser().parsePathString(d).toNodes(),
      fill = brush,
      fillAlpha = ICON_SOFT_FILL_ALPHA,
    )
  }
  return builder.build()
}
