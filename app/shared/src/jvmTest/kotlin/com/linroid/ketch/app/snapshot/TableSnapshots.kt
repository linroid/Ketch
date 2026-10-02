package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchMenuPanel
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.DetectedBrowser
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.state.GroupBy
import com.linroid.ketch.app.state.ListArrangement
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.SortKey
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.downloads.ClipboardChipRow
import com.linroid.ketch.app.ui.downloads.EmptyMessage
import com.linroid.ketch.app.ui.downloads.Launchpad
import com.linroid.ketch.app.ui.downloads.RowDensity
import com.linroid.ketch.app.ui.downloads.SkeletonRows
import com.linroid.ketch.app.ui.downloads.TableColumn
import com.linroid.ketch.app.ui.downloads.TableLayout
import com.linroid.ketch.app.ui.downloads.columnChooser
import com.linroid.ketch.app.ui.downloads.emptyCopy
import com.linroid.ketch.app.ui.downloads.offlineCopy
import com.linroid.ketch.app.ui.downloads.remoteEmptyCopy
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.DownloadsLayout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * The Downloads page (W3-TABLE): the table with its groups, columns and docked inspector, the
 * list rows, the phone page, the tabs' own actions, search with facets, the empty states and the
 * launchpad; see [SnapshotHarness] for how to run it.
 */
class TableSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  private val wide = listOf(SnapshotSize.Desktop, SnapshotSize.SmallDesktop)

  @Test
  fun table_inspectorClosed_showsEveryColumn() {
    appSnapshots("table", wide) { state.updateInspectorOpen(false) }
  }

  @Test
  fun table_rowInspected_docksTheInspector() {
    appSnapshots("table-inspected", wide) { inspect(UBUNTU) }
  }

  @Test
  fun table_nothingInspected_showsTheOverview() {
    appSnapshots("table-overview", listOf(SnapshotSize.Desktop))
  }

  @Test
  fun list_mediumAndPhone_showTwoLineRows() {
    appSnapshots("list", listOf(SnapshotSize.Medium, SnapshotSize.Phone))
    appSnapshots("list-inspected", listOf(SnapshotSize.Medium, SnapshotSize.Phone)) {
      inspect(UBUNTU)
    }
    appSnapshots("list-hover", listOf(SnapshotSize.Medium)) { scene.hover(420.dp, 145.dp) }
    appSnapshots("list-picked", listOf(SnapshotSize.Desktop)) {
      state.updateInspectorOpen(false)
      state.appSettings.saveUi { it.copy(layout = DownloadsLayout.List) }
    }
    appSnapshots("list-narrow", listOf(SnapshotSize(600.dp, 700.dp, KetchDensity.Compact)))
    // A narrow browser window: a pointer on the phone layout shows a clicked row in the sheet.
    appSnapshots("list-pointer", listOf(SnapshotSize(390.dp, 844.dp, KetchDensity.Compact))) {
      scene.click(200.dp, 300.dp)
    }
  }

  @Test
  fun tabs_doneAndFailed_offerTheirActions() {
    appSnapshots("tab-done", listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      state.updateInspectorOpen(false)
      showTab(StatusFilter.Done)
    }
    appSnapshots("tab-failed", listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      state.updateInspectorOpen(false)
      showTab(StatusFilter.Failed)
    }
    appSnapshots("tab-waiting", listOf(SnapshotSize.Desktop)) {
      state.updateInspectorOpen(false)
      showTab(StatusFilter.Waiting)
    }
  }

  @Test
  fun search_tokensAndText_showFacetsAndMatches() {
    appSnapshots("search-tokens", listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      state.updateInspectorOpen(false)
      search("is:downloading type:archive")
    }
    appSnapshots("search-none", listOf(SnapshotSize.Desktop, SnapshotSize.Medium)) {
      search("blender 5")
    }
    appSnapshots("search-link", listOf(SnapshotSize.Desktop)) {
      search("https://example.com/files/report-2027.pdf")
    }
  }

  @Test
  fun table_sortedAndGrouped_showsHeaderState() {
    appSnapshots("table-sorted", listOf(SnapshotSize.Desktop)) {
      state.updateInspectorOpen(false)
      state.listArrangement = ListArrangement(SortKey.Speed, descending = true, GroupBy.Status)
    }
    appSnapshots("table-compact-rows", listOf(SnapshotSize.Desktop)) {
      state.updateInspectorOpen(false)
      state.appSettings.saveUi {
        it.copy(table = it.table + (RowDensity.ROWS_KEY to RowDensity.Compact.id))
      }
    }
  }

  @Test
  fun table_selectionAndHover_showTheBarAndActions() {
    appSnapshots("table-selection", listOf(SnapshotSize.Desktop)) {
      select(UBUNTU, IMAGENET, REPORT)
    }
    appSnapshots("table-hover", listOf(SnapshotSize.Desktop)) {
      state.updateInspectorOpen(false)
      scene.hover(700.dp, 200.dp)
    }
    appSnapshots("table-keyboard", listOf(SnapshotSize.Desktop)) {
      state.updateInspectorOpen(false)
      scene.click(700.dp, 176.dp)
      scene.pressKey(Key.DirectionDown)
      scene.pressKey(Key.DirectionDown)
    }
  }

  @Test
  fun selection_phone_showsTheBottomBar() {
    appSnapshots("list-selection", listOf(SnapshotSize.Phone)) { select(UBUNTU, IMAGENET) }
  }

  @Test
  fun columnChooser_menu_listsTheColumnsThatCanHide() {
    val size = SnapshotSize(240.dp, 400.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      snapshot("table-columns", size, theme) {
        KetchMenuPanel(Modifier.padding(KetchTheme.spacing.s2)) {
          columnChooser(TableLayout().withVisible(TableColumn.Source, true)) {}
        }
      }
    }
  }

  @Test
  fun clipboardChip_phone_offersTheCopiedLink() {
    val size = SnapshotSize(390.dp, 120.dp, KetchDensity.Comfortable)
    for (theme in SnapshotTheme.entries) {
      snapshot("clipboard-chip", size, theme) {
        Column(Modifier.background(KetchTheme.colors.surface)) {
          ClipboardChipRow(
            label = "ubuntu-24.04-desktop-amd64.iso · releases.ubuntu.com",
            onClick = {},
            onDismiss = {},
          )
          ClipboardChipRow(label = null, onClick = {}, onDismiss = {})
        }
      }
    }
  }

  @Test
  fun emptyStates_page_sayWhyAndOfferAWayOut() {
    val size = SnapshotSize(760.dp, 360.dp, KetchDensity.Compact)
    val states: Map<String, @Composable () -> Unit> = mapOf(
      "empty-waiting" to {
        EmptyMessage(emptyCopy(StatusFilter.Waiting, "", "This Mac", slots = 3), onAction = {})
      },
      "empty-links" to {
        val links = "https://example.com/a.iso https://example.com/b.iso"
        EmptyMessage(emptyCopy(StatusFilter.All, links, "This Mac", slots = 3), onAction = {})
      },
      "empty-offline" to {
        EmptyMessage(offlineCopy("nas.local:8642", unauthorized = false), onAction = {})
      },
      "empty-remote" to { EmptyMessage(remoteEmptyCopy("nas.local:8642"), onAction = {}) },
      "loading-rows" to {
        SkeletonRows(
          rowHeight = KetchTheme.density.tableRow,
          chip = KetchFileTypeChipDefaults.TableSize,
        )
      },
    )
    for ((name, content) in states) {
      for (theme in SnapshotTheme.entries) {
        snapshot(name, size, theme) {
          Box(Modifier.fillMaxSize().background(KetchTheme.colors.surface)) { content() }
        }
      }
    }
  }

  @Test
  fun empty_everySize_showsTheLaunchpad() {
    // Discovery is supported but not set up, as on a fresh desktop or Android install.
    appSnapshots("launchpad", data = SampleData::empty, aiProviderFactory = { null })
  }

  @Test
  fun launchpad_desktop_showsTheBrowserTileAndChecklist() {
    for (theme in SnapshotTheme.entries) {
      val environment = runBlocking(SnapshotHarness.ui) {
        // Discovery is supported but not set up, as on a fresh desktop install.
        SampleEnvironment(
          data = SampleData.empty(),
          theme = theme,
          density = DensityMode.Compact,
          aiProviderFactory = { null },
        )
      }
      try {
        runBlocking(SnapshotHarness.ui) { environment.start() }
        snapshot("launchpad-desktop", SnapshotSize.SmallDesktop, theme) {
          CompositionLocalProvider(
            LocalDesktopHooks provides DesktopSupport,
            LocalIntegrationStatus provides IntegrationStatus(
              browsers = listOf(DetectedBrowser("Chrome", extensionConnected = true)) +
                DetectedBrowser("Firefox"),
            )
          ) {
            Box(
              Modifier
                .fillMaxSize()
                .padding(KetchTheme.spacing.cardInset)
                .ketchSurface(
                  level = KetchElevationLevel.E1,
                  shape = KetchTheme.shapes.card,
                  fill = KetchTheme.colors.surface,
                  border = KetchTheme.colors.hairline,
                )
            ) {
              Launchpad(environment.controller.state, phone = false)
            }
          }
        }
      } finally {
        runBlocking(SnapshotHarness.ui) { environment.close() }
      }
    }
  }

  @Test
  fun many_thousandTasks_rendersTheFirstPage() {
    appSnapshots("table-many", listOf(SnapshotSize.Desktop), data = { manyDownloads(1000) }) {
      state.updateInspectorOpen(false)
    }
  }
}

/** The sample downloads plus [count] finished ones added over the last months. */
private fun manyDownloads(count: Int): SampleData {
  val sample = SampleData.downloads()
  val extra = (1..count).map { index ->
    val name = "archive-${index.toString().padStart(4, '0')}.zip"
    ListTestTask(
      taskId = "many-$index",
      state = DownloadState.Completed(
        outputPath = "${SampleData.DOWNLOAD_DIR}/$name",
        totalBytes = index * 1_048_576L,
        downloadTime = (index % 90 + 1).seconds,
      ),
      request = DownloadRequest(url = "https://mirror.example.org/files/$name"),
      createdAt = SampleData.NOW - (index * 3).hours,
    )
  }
  return SampleData(tasks = sample.tasks + extra)
}

/** Desktop hooks that only report being there, so the launchpad shows its desktop parts. */
private object DesktopSupport : DesktopHooks {
  override val isSupported: Boolean = true
}

private const val UBUNTU = "ubuntu-24.04-desktop-amd64.iso"
private const val IMAGENET = "imagenet-part03.tar"
private const val REPORT = "q3-report.pdf"
