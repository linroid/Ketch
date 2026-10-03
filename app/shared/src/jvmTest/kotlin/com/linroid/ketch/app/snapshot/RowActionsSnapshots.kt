package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.PriorityGlyph
import com.linroid.ketch.app.components.StatusDot
import com.linroid.ketch.app.components.StatusDotDefaults
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.downloads.actions.HoverActions
import com.linroid.ketch.app.ui.downloads.actions.ListActions
import com.linroid.ketch.app.ui.downloads.actions.RowActionDialogs
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.SelectionBar
import com.linroid.ketch.app.ui.downloads.actions.SelectionCheckbox
import com.linroid.ketch.app.ui.downloads.actions.TaskRowFrame
import com.linroid.ketch.app.ui.downloads.actions.dragCount
import com.linroid.ketch.app.ui.downloads.actions.drawDragPreview
import com.linroid.ketch.app.ui.downloads.actions.listKeyboard
import com.linroid.ketch.app.ui.downloads.actions.pageSizeOf
import com.linroid.ketch.app.ui.downloads.actions.rememberListActions
import com.linroid.ketch.app.ui.downloads.actions.rubberBand
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The selection, row menus, hover actions, list keys, Remove dialog, drag preview and rubber
 * band of the Downloads list (W3-ROW-ACTIONS), over [SampleData] in a small stand-in for the
 * table that W3-TABLE builds on them. See [SnapshotHarness] for how to run it.
 */
class RowActionsSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  private val wide = listOf(SnapshotSize.Desktop, SnapshotSize.Medium)

  @Test
  fun selectionBar_wideAndMedium_showsVerbsWithCounts() {
    for (size in wide) {
      for (theme in SnapshotTheme.entries) {
        actionsSnapshot("row-actions-selection", size, theme, prepare = {
          select(UBUNTU, IMAGENET, REPORT, WEIGHTS, ANDROID)
        }) { hoverRow(LINUX) }
      }
    }
  }

  @Test
  fun hoverActions_runningAndFinishedRows_fadeInOverTheRow() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-hover-done", SnapshotSize.Desktop, theme) { hoverRow(LINUX) }
      actionsSnapshot("row-actions-hover-running", SnapshotSize.Desktop, theme) {
        hoverRow(UBUNTU)
      }
    }
  }

  @Test
  fun hoverActions_moreMenu_staysWhileThePointerIsInIt() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-hover-menu", SnapshotSize.Desktop, theme) {
        hoverRow(UBUNTU, x = 1200.dp)
        clickRow(UBUNTU, x = 1200.dp)
        scene.hover(1150.dp, 300.dp)
      }
    }
  }

  @Test
  fun rowMenu_downloadingRow_opensAtThePointerWithSubmenus() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-menu", SnapshotSize.Desktop, theme, prepare = {
        openMenu(UBUNTU, x = 300.dp)
      }) {
        pressKey(Key.DirectionDown)
        pressKey(Key.DirectionDown)
        pressKey(Key.DirectionRight)
      }
    }
  }

  @Test
  fun rowMenu_finishedRowAndSelection_listTheirActions() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-menu-done", SnapshotSize.Desktop, theme, prepare = {
        openMenu(REPORT, x = 420.dp)
      })
      actionsSnapshot("row-actions-menu-selection", SnapshotSize.Desktop, theme, prepare = {
        select(UBUNTU, IMAGENET, BLENDER, REPORT)
        openMenu(IMAGENET, x = 360.dp)
      })
    }
  }

  @Test
  fun rowMenu_phone_opensAsASheet() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-menu-phone", SnapshotSize.Phone, theme, prepare = {
        actions.showMenu(row(UBUNTU))
      })
    }
  }

  @Test
  fun selectionBar_phone_replacesTheAddButton() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-selection-phone", SnapshotSize.Phone, theme, prepare = {
        select(UBUNTU, IMAGENET, ANDROID)
      })
    }
  }

  @Test
  fun removeDialog_everyForm_namesWhatGoes() {
    val sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Phone)
    for (size in sizes) {
      for (theme in SnapshotTheme.entries) {
        actionsSnapshot("row-actions-remove-trash", size, theme, prepare = {
          remove(listOf(PHOTOS), withFiles = true)
        })
      }
    }
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-remove-several", SnapshotSize.Desktop, theme, prepare = {
        remove(listOf(LINUX, PODCAST, ANDROID), withFiles = false)
      })
      actionsSnapshot("row-actions-remove-partial", SnapshotSize.Desktop, theme, prepare = {
        remove(listOf(ANDROID), withFiles = true)
      })
      actionsSnapshot("row-actions-remove-mixed", SnapshotSize.Desktop, theme, prepare = {
        remove(listOf(LINUX, PODCAST, ANDROID), withFiles = true)
      })
      actionsSnapshot(
        "row-actions-remove-delete",
        SnapshotSize.Desktop,
        theme,
        files = SnapshotFiles(canTrash = false),
        prepare = { remove(listOf(LINUX), withFiles = true) },
      )
    }
  }

  @Test
  fun dialogs_discardAndCustomSpeed_askFirst() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-discard", SnapshotSize.Desktop, theme, prepare = {
        run(RowAction.StopAndDiscard, UBUNTU)
      })
      actionsSnapshot("row-actions-custom-speed", SnapshotSize.Desktop, theme, prepare = {
        actions.runner.requestCustomSpeed(listOf(row(UBUNTU), row(IMAGENET)))
      })
    }
  }

  @Test
  fun keyboard_arrowKeys_moveTheFocusedRow() {
    for (theme in SnapshotTheme.entries) {
      actionsSnapshot("row-actions-keyboard", SnapshotSize.Desktop, theme, prepare = {
        actions.keyboard.focus()
      }) {
        repeat(3) { pressKey(Key.DirectionDown) }
        pressKey(Key.DirectionDown, shift = true)
      }
    }
  }

  @Test
  fun dragPreview_oneAndSeveralRows_showNameAndCount() {
    val rows = previewRows(SampleData.downloads())
    for (theme in SnapshotTheme.entries) {
      val size = SnapshotSize(360.dp, 120.dp, KetchDensity.Compact)
      snapshot("row-actions-drag-preview", size, theme) {
        val measurer = rememberTextMeasurer()
        val colors = KetchTheme.colors
        val spacing = KetchTheme.spacing
        val type = KetchTheme.typography
        val count = dragCount(rows)
        Column(
          Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Canvas(Modifier.width(320.dp).height(36.dp)) {
            drawDragPreview(rows.take(1), measurer, colors, spacing, type, count = null)
          }
          Canvas(Modifier.width(320.dp).height(36.dp)) {
            drawDragPreview(rows, measurer, colors, spacing, type, count)
          }
        }
      }
    }
  }

  @Test
  fun rubberBand_dragFromBelowTheRows_selectsTheRowsItCrosses() {
    for (theme in SnapshotTheme.entries) {
      bandSnapshot("row-actions-rubber-band", SnapshotSize.Desktop, theme)
    }
  }
}

private const val UBUNTU = "ubuntu-24.04-desktop-amd64.iso"
private const val IMAGENET = "imagenet-part03.tar"
private const val BLENDER = "blender-4.2-macos-arm64.dmg"
private const val REPORT = "q3-report.pdf"
private const val LINUX = "linux-6.11.tar.xz"
private const val PHOTOS = "vacation-photos-2026.zip"
private const val PODCAST = "podcast-episode-142.mp3"
private const val ANDROID = "android-studio-2024.2.1.12-mac_arm.dmg"
private const val WEIGHTS = "model-weights.safetensors"

/** Rows of the stand-in table, from its top, in dp. */
private val CARD_INSET = 8.dp
private val HEADER = 52.dp
private val TABS = 40.dp
private val COLUMN_HEADER = 28.dp
private val ROW = 36.dp
private val PHONE_ROW = 72.dp
private val START_TIMEOUT = 5.seconds

/** What a scenario does once the rows are on screen. */
private class DemoScope(val actions: ListActions, val state: AppState) {
  fun row(name: String): TaskRow = actions.rows.first { it.name == name }

  fun select(vararg names: String) {
    state.selectedKeys = names.mapTo(LinkedHashSet()) { row(it).key }
  }

  fun openMenu(name: String, x: Dp) {
    actions.contextClick(row(name), Offset(x.value * SnapshotHarness.SCALE, ROW_MIDDLE))
  }

  fun remove(names: List<String>, withFiles: Boolean) {
    actions.runner.requestRemove(names.map(::row), withFiles)
  }

  fun run(action: RowAction, name: String) {
    actions.runner.run(action, listOf(row(name)))
  }

  private companion object {
    val ROW_MIDDLE = ROW.value / 2 * SnapshotHarness.SCALE
  }
}

/** The scene of an [actionsSnapshot] with the rows' positions, to hover them. */
private class ActionsScene(val scene: SnapshotScene, private val controller: AppController) {
  suspend fun hoverRow(name: String, x: Dp = 600.dp) {
    scene.hover(x, middleOf(name))
  }

  suspend fun clickRow(name: String, x: Dp) {
    scene.click(x, middleOf(name))
  }

  private fun middleOf(name: String): Dp {
    val index = controller.state.taskList.view.value.rows.indexOfFirst { it.name == name }
    val top = CARD_INSET + HEADER + TABS + COLUMN_HEADER
    return top + ROW * index + ROW / 2
  }

  suspend fun pressKey(key: Key, shift: Boolean = false) = scene.pressKey(key, shift)
}

/**
 * Renders the stand-in Downloads list over [SampleData] at [size] in [theme], runs [prepare]
 * once the rows are listed and [interact] once they settled, and writes the PNG.
 */
private fun actionsSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  files: FileActions = SnapshotFiles(),
  prepare: DemoScope.() -> Unit = {},
  interact: suspend ActionsScene.() -> Unit = {},
): File {
  val phone = size.density != KetchDensity.Compact
  val mode = size.density.toMode()
  return withSample(theme, mode) { env ->
    val controller = env.controller
    SnapshotHarness.capture(
      name = "$name-${theme.id}-${size.id}",
      size = size,
      interact = { ActionsScene(this, controller).interact() },
    ) {
      KetchTheme(darkTheme = theme == SnapshotTheme.Dark, density = mode, reduceMotion = true) {
        Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) {
          StandInDownloads(controller, files, prepare, phone)
        }
      }
    }
  }
}

/** The Downloads card as W3-TABLE will plug the row actions into it, much simplified. */
@Composable
private fun StandInDownloads(
  controller: AppController,
  files: FileActions,
  prepare: DemoScope.() -> Unit,
  phone: Boolean,
) {
  val state = controller.state
  CompositionLocalProvider(LocalAppState provides state) {
    val colors = KetchTheme.colors
    val spacing = KetchTheme.spacing
    val view by state.taskList.view.collectAsState()
    val rows = view.rows
    val scope = rememberCoroutineScope()
    val runner = remember(state, files) {
      val commands = RowCommands(state, files, SnapshotClipboard(), scope) {}
      RowActionRunner(commands)
    }
    val actions = rememberListActions(rows, state, runner)
    val listState = rememberLazyListState()
    LaunchedEffect(rows.isNotEmpty()) {
      if (rows.isNotEmpty()) DemoScope(actions, state).prepare()
    }
    val selected = actions.selectedRows
    Column(
      Modifier
        .fillMaxSize()
        .padding(CARD_INSET)
        .ketchSurface(
          KetchElevationLevel.E1,
          KetchTheme.shapes.card,
          colors.surface,
          colors.hairline,
        ),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(HEADER).padding(horizontal = spacing.s4),
      ) {
        Text("Downloads", style = KetchTheme.typography.pageTitle, color = colors.textPrimary)
        Spacer(Modifier.width(spacing.s2))
        Text("${rows.size}", style = KetchTheme.typography.numeralS, color = colors.textTertiary)
      }
      if (!phone) {
        if (selected.size >= 2) {
          SelectionBar(
            rows = selected,
            runner = runner,
            onClear = actions.selection::clear,
            onSelectAll = actions::selectAll,
          )
        } else {
          Tabs(state)
        }
        ColumnHeader()
      }
      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .listKeyboard(actions.keyboard, { actions.rows }, { pageSizeOf(listState) })
          .rubberBand(listState, actions.selection, { rows.getOrNull(it)?.key }),
      ) {
        items(rows, key = { it.key.encode() }) { row ->
          if (phone) PhoneRow(row, actions) else TableRow(row, actions)
        }
      }
      if (phone && selected.isNotEmpty()) {
        SelectionBar(
          rows = selected,
          runner = runner,
          onClear = actions.selection::clear,
          onSelectAll = actions::selectAll,
          compact = true,
        )
      }
    }
    RowActionDialogs(runner)
  }
}

@Composable
private fun Tabs(state: AppState) {
  Box(
    contentAlignment = Alignment.CenterStart,
    modifier = Modifier.fillMaxWidth().height(TABS).padding(horizontal = KetchTheme.spacing.s3),
  ) {
    KetchSegmented(
      options = StatusFilter.entries,
      selected = StatusFilter.All,
      onSelect = {},
      label = { it.label.resolve() },
    )
  }
}

@Composable
private fun ColumnHeader() {
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .height(COLUMN_HEADER)
      .background(colors.surfaceSunken)
      .padding(horizontal = KetchTheme.spacing.cellPadding),
  ) {
    HeaderCell("", Modifier.width(28.dp))
    HeaderCell("Name", Modifier.weight(1f))
    HeaderCell("Size", Modifier.width(128.dp), TextAlign.End)
    HeaderCell("Progress", Modifier.width(160.dp).padding(start = 16.dp))
    HeaderCell("Speed", Modifier.width(92.dp), TextAlign.End)
    HeaderCell("Left", Modifier.width(132.dp).padding(start = 16.dp))
  }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
  Text(
    text = text.uppercase(),
    style = KetchTheme.typography.eyebrow,
    color = KetchTheme.colors.textTertiary,
    textAlign = align,
    modifier = modifier,
  )
}

@Composable
private fun TableRow(row: TaskRow, actions: ListActions) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val content = row.content
  val running = row.state is DownloadState.Downloading || row.state is DownloadState.Paused
  TaskRowFrame(row, actions, Modifier.fillMaxWidth().height(ROW)) { frame ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxSize().padding(horizontal = KetchTheme.spacing.cellPadding),
    ) {
      Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
        if (frame.selecting || frame.hovered) {
          SelectionCheckbox(row, actions)
        } else {
          StatusDot(content.status, size = StatusDotDefaults.TableSize)
        }
      }
      Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        KetchFileTypeChip(
          fileName = row.name,
          sourceUrl = row.request.url,
          size = KetchFileTypeChipDefaults.TableSize,
        )
        Text(
          text = row.name,
          style = type.cellStrong,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
        )
        PriorityGlyph(row.request.priority, Modifier.padding(start = 4.dp))
      }
      NumeralCell(content.size.resolve(), 128.dp)
      if (running) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.width(160.dp).padding(start = 16.dp),
        ) {
          LaneStrip(
            state = row.state,
            segments = row.segments,
            modifier = Modifier.weight(1f),
            height = LaneStripDefaults.CellHeight,
          )
          Text(
            text = "${((content.progress ?: 0f) * 100).toInt()}%",
            style = type.numeralS,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
        NumeralCell(content.speed.resolve(), 92.dp)
      } else {
        Text(
          text = (content.error?.title ?: content.detail).resolve(),
          style = type.caption,
          color = if (content.error != null) colors.status.failed.color else colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.width(252.dp).padding(start = 16.dp),
        )
      }
      Box(Modifier.width(132.dp).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (!frame.hovered) {
          Text(
            text = content.time.resolve().ifEmpty { content.added.resolve() },
            style = type.numeral,
            color = colors.textTertiary,
          )
        }
        HoverActions(row, frame.hovered, actions.runner, menu = actions.menu)
      }
    }
  }
}

@Composable
private fun NumeralCell(text: String, width: Dp) {
  Text(
    text = text,
    style = KetchTheme.typography.numeral,
    color = KetchTheme.colors.textSecondary,
    textAlign = TextAlign.End,
    maxLines = 1,
    modifier = Modifier.width(width),
  )
}

@Composable
private fun PhoneRow(row: TaskRow, actions: ListActions) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val content = row.content
  TaskRowFrame(
    row,
    actions,
    Modifier.fillMaxWidth().heightIn(min = PHONE_ROW).padding(horizontal = spacing.s1),
    shape = KetchTheme.shapes.md,
  ) { frame ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(spacing.s3),
    ) {
      Box(Modifier.size(KetchFileTypeChipDefaults.TouchSize), contentAlignment = Alignment.Center) {
        if (frame.selecting) {
          SelectionCheckbox(row, actions)
        } else {
          KetchFileTypeChip(row.name, sourceUrl = row.request.url)
        }
      }
      Column(Modifier.weight(1f).padding(start = spacing.s3)) {
        Text(
          text = row.name,
          style = type.bodyStrong,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = (content.error?.title ?: content.detail).resolve(),
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private fun previewRows(data: SampleData): List<TaskRow> =
  withSample(SnapshotTheme.Light, data = data) { env ->
    runBlocking(SnapshotHarness.ui) {
      // The view follows the rows that start() waits for a moment later.
      val view = env.controller.state.taskList.view.first { it.rows.size == data.tasks.size }
      listOf(PHOTOS, LINUX, PODCAST).map { name -> view.rows.first { it.name == name } }
    }
  }

/**
 * Renders the stand-in list at [size] with a rubber band dragged from below the last row up
 * over four rows, the pointer still held, and writes the PNG.
 */
private fun bandSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
): File = withSample(theme) { environment ->
  runBlocking(SnapshotHarness.ui) {
    val scale = SnapshotHarness.SCALE
    val scene = ImageComposeScene(
      width = (size.width.value * scale).toInt(),
      height = (size.height.value * scale).toInt(),
      density = Density(scale),
      coroutineContext = SnapshotHarness.ui,
    ) {
      KetchTheme(
        darkTheme = theme == SnapshotTheme.Dark,
        density = DensityMode.Compact,
        reduceMotion = true,
      ) {
        Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) {
          StandInDownloads(environment.controller, SnapshotFiles(), {}, phone = false)
        }
      }
    }
    val start = TimeSource.Monotonic.markNow()
    suspend fun frames(count: Int) = repeat(count) {
      scene.render(start.elapsedNow().inWholeNanoseconds)
      delay(16.milliseconds)
      Snapshot.sendApplyNotifications()
    }
    try {
      frames(40)
      val top = CARD_INSET + HEADER + TABS + COLUMN_HEADER
      val from = Offset(900f * scale, (size.height.value - 40f) * scale)
      val to = Offset(380f * scale, (top + ROW * 9 + ROW / 2).value * scale)
      scene.sendPointerEvent(PointerEventType.Move, from)
      scene.sendPointerEvent(
        PointerEventType.Press,
        from,
        buttons = PointerButtons(isPrimaryPressed = true),
        button = PointerButton.Primary,
      )
      for (step in 1..8) {
        val point = from + (to - from) * (step / 8f)
        scene.sendPointerEvent(
          PointerEventType.Move,
          point,
          buttons = PointerButtons(isPrimaryPressed = true),
        )
        frames(2)
      }
      frames(20)
      val image = scene.render(start.elapsedNow().inWholeNanoseconds)
      val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
      SnapshotHarness.outputDir.mkdirs()
      File(SnapshotHarness.outputDir, "$name-${theme.id}-${size.id}.png")
        .apply { writeBytes(bytes) }
    } finally {
      scene.close()
    }
  }
}
