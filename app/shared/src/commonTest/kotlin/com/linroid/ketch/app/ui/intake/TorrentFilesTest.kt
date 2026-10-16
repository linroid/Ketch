package com.linroid.ketch.app.ui.intake

import androidx.compose.ui.state.ToggleableState
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.state.FileOrder
import com.linroid.ketch.app.state.FileSort
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentFilesTest {

  private val files = listOf(
    SourceFile("0", "Season 1/S01E02.mkv", 2_000),
    SourceFile("1", "Season 1/S01E01.mkv", 1_000),
    SourceFile("2", "Season 1/S01E01.en.srt", 10),
    SourceFile("3", "Season 1/Extras/sample.mkv", 100),
    SourceFile("4", "cover.jpg", 50),
  )
  private val tree = TorrentTree(files)

  @Test
  fun visible_nothingCollapsed_listsFoldersFirstThenFilesByName() {
    assertEquals(
      listOf(
        "Season 1",
        "Season 1/Extras",
        "Season 1/Extras/sample.mkv",
        "Season 1/S01E01.en.srt",
        "Season 1/S01E01.mkv",
        "Season 1/S01E02.mkv",
        "cover.jpg",
      ),
      tree.visible(collapsed = emptySet()).map { it.path },
    )
  }

  @Test
  fun visible_collapsedFolder_hidesItsRows() {
    assertEquals(
      listOf("Season 1", "cover.jpg"),
      tree.visible(collapsed = setOf("Season 1")).map { it.path },
    )
  }

  @Test
  fun visible_filter_keepsMatchesInsideTheirOpenFolders() {
    assertEquals(
      listOf("Season 1", "Season 1/S01E01.en.srt", "Season 1/S01E01.mkv"),
      tree.visible(collapsed = setOf("Season 1"), filter = "e01").map { it.path },
    )
  }

  @Test
  fun toggleState_folderWithSomeFiles_isIndeterminate() {
    val season = tree.roots.first()

    assertEquals(ToggleableState.Indeterminate, toggleState(season, setOf("0")))
    assertEquals(ToggleableState.On, toggleState(season, setOf("0", "1", "2", "3")))
    assertEquals(ToggleableState.Off, toggleState(season, setOf("4")))
  }

  @Test
  fun toggle_partlySelectedFolder_selectsAllOfIt() {
    val season = tree.roots.first()

    val selected = toggle(season, setOf("0"))

    assertEquals(setOf("0", "1", "2", "3"), selected)
    assertEquals(emptySet(), toggle(season, selected))
  }

  @Test
  fun toggleRange_shiftClick_givesEveryRowBetweenTheClickedState() {
    val rows = tree.visible(collapsed = setOf("Season 1/Extras"))
    val last = rows.first { it.path == "Season 1/S01E02.mkv" }

    val selected = toggleRange(rows, from = "Season 1/S01E01.en.srt", to = last, emptySet())

    assertEquals(setOf("2", "1", "0"), selected)
  }

  @Test
  fun kindCounts_files_countEachKind() {
    assertEquals(
      mapOf(
        TorrentFileKind.Video to 3,
        TorrentFileKind.Subtitles to 1,
        TorrentFileKind.Images to 1,
      ),
      tree.kindCounts,
    )
  }

  @Test
  fun sortBySize_files_putTheLargestFirst() {
    val sorted = TorrentTree(files, FileSort(FileOrder.Size))

    assertEquals(
      listOf("Season 1", "cover.jpg"),
      sorted.roots.map { it.path },
    )
    val season = sorted.roots.first() as TorrentNode.Folder
    assertEquals(
      listOf("Season 1/Extras", "Season 1/S01E02.mkv", "Season 1/S01E01.mkv"),
      season.children.take(3).map { it.path },
    )
  }

  @Test
  fun sortByKind_foldersFirstByNameThenFilesGroupedByKind() {
    val tree = TorrentTree(
      listOf(
        SourceFile("0", "b-folder/x.mkv", 10),
        SourceFile("1", "a-folder/y.mkv", 10),
        SourceFile("2", "notes.txt", 10),
        SourceFile("3", "song.flac", 10),
        SourceFile("4", "movie.mkv", 10),
        SourceFile("5", "movie.srt", 10),
      ),
      FileSort(FileOrder.Kind),
    )

    assertEquals(
      listOf("a-folder", "b-folder", "movie.mkv", "song.flac", "movie.srt", "notes.txt"),
      tree.roots.map { it.path }
    )
  }

  @Test
  fun sortByTorrentOrder_foldersByTheirFirstFile() {
    val sorted = TorrentTree(files, FileSort(FileOrder.Torrent))
    val season = sorted.roots.first() as TorrentNode.Folder

    assertEquals(listOf("Season 1", "cover.jpg"), sorted.roots.map { it.path })
    assertEquals(
      listOf(
        "Season 1/Extras",
        "Season 1/S01E02.mkv",
        "Season 1/S01E01.mkv",
        "Season 1/S01E01.en.srt",
      ),
      season.children.map { it.path }
    )
  }

  @Test
  fun sortBySelected_chosenFirstWithFoldersStillFirst() {
    val sorted = TorrentTree(files, FileSort(FileOrder.Selected), selection = setOf("2", "4"))
    val season = sorted.roots.first() as TorrentNode.Folder

    assertEquals(listOf("Season 1", "cover.jpg"), sorted.roots.map { it.path })
    assertEquals(
      listOf(
        "Season 1/Extras",
        "Season 1/S01E01.en.srt",
        "Season 1/S01E01.mkv",
        "Season 1/S01E02.mkv",
      ),
      season.children.map { it.path }
    )
  }

  @Test
  fun sortByName_reversed_turnsFoldersAndFilesAround() {
    val sorted = TorrentTree(files, FileSort(FileOrder.Name).reverse())
    val season = sorted.roots.first() as TorrentNode.Folder

    assertEquals(
      listOf(
        "Season 1/Extras",
        "Season 1/S01E02.mkv",
        "Season 1/S01E01.mkv",
        "Season 1/S01E01.en.srt",
      ),
      season.children.map { it.path }
    )
  }
}
