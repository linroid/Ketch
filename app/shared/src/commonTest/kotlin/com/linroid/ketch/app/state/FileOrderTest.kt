package com.linroid.ketch.app.state

import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.sortedByFileOrder
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileOrderTest {
  private val files = listOf(
    key("Show/Episode 10.mkv", size = 300, index = 0),
    key("Show/episode 2.mkv", size = 100, index = 1, selected = false),
    key("Show/Episode 1.srt", size = 1, index = 2),
    key("Show/cover.JPG", size = 50, index = 3),
    key("Show/readme.txt", size = 1, index = 4),
    key("Show/Episode 3.mp3", size = 100, index = 5, progress = 0.5f),
  )

  @Test
  fun name_naturalAndCaseInsensitive_putsEpisode2BeforeEpisode10() {
    assertEquals(
      listOf(
        "Show/cover.JPG",
        "Show/Episode 1.srt",
        "Show/episode 2.mkv",
        "Show/Episode 3.mp3",
        "Show/Episode 10.mkv",
        "Show/readme.txt",
      ),
      sorted(FileSort(FileOrder.Name)),
    )
  }

  @Test
  fun name_sameOrderAsTheServer() {
    val ours = sorted(FileSort(FileOrder.Name))
    val server = files.sortedByFileOrder(
      order = TorrentFileOrder.NAME,
      path = { it.path.orEmpty() },
      size = { it.size },
      selected = { it.selected },
    ).map { it.path }

    assertEquals(server, ours)
  }

  @Test
  fun size_largestFirstWithTiesInTorrentOrder() {
    assertEquals(
      listOf(0, 1, 5, 3, 2, 4),
      sortedIndexes(FileSort(FileOrder.Size)),
    )
  }

  @Test
  fun reverse_turnsTheKeyAroundButKeepsTiesInTorrentOrder() {
    val smallestFirst = FileSort(FileOrder.Size).reverse()

    assertTrue(smallestFirst.reversed)
    assertEquals(listOf(2, 4, 3, 1, 5, 0), sortedIndexes(smallestFirst))
  }

  @Test
  fun kind_groupsVideoAudioSubtitlesImagesThenDocuments() {
    assertEquals(
      listOf(
        "Show/episode 2.mkv",
        "Show/Episode 10.mkv",
        "Show/Episode 3.mp3",
        "Show/Episode 1.srt",
        "Show/cover.JPG",
        "Show/readme.txt",
      ),
      sorted(FileSort(FileOrder.Kind)),
    )
  }

  @Test
  fun selected_chosenFilesFirstThenByName() {
    assertEquals("Show/episode 2.mkv", sorted(FileSort(FileOrder.Selected)).last())
  }

  @Test
  fun progress_downloadingFirstThenDoneThenNotChosen() {
    val keys = listOf(
      key("a", size = 10, index = 0, progress = 1f),
      key("b", size = 10, index = 1, progress = 0.2f),
      key("c", size = 10, index = 2, selected = false),
      key("d", size = 10, index = 3, progress = 0.7f),
    )

    assertEquals(
      listOf("d", "b", "a", "c"),
      keys.sortedByFiles(FileSort(FileOrder.Progress)) { it }.map { it.path },
    )
  }

  @Test
  fun torrent_metainfoOrder() {
    assertEquals(listOf(0, 1, 2, 3, 4, 5), sortedIndexes(FileSort(FileOrder.Torrent)))
  }

  @Test
  fun decode_encoded_roundTrips() {
    for (surface in FileSortSurface.entries) {
      for (order in surface.orders) {
        for (sort in listOf(FileSort(order), FileSort(order).reverse())) {
          assertEquals(sort, FileSort.decode(sort.encode(), surface))
        }
      }
    }
    assertEquals("size:desc", FileSort(FileOrder.Size).encode())
  }

  @Test
  fun decode_malformed_fallsBackToTheSurfaceDefault() {
    val intake = FileSortSurface.Intake
    val inspector = FileSortSurface.Inspector

    for (value in listOf(null, "", "size", "size:up", "colour:asc", "size:desc:x")) {
      assertEquals(FileSort(FileOrder.Name), FileSort.decode(value, intake))
      assertEquals(FileSort(FileOrder.Progress), FileSort.decode(value, inspector))
    }
    // The add sheet has no Progress.
    assertEquals(FileSort(FileOrder.Name), FileSort.decode("progress:asc", intake))
    assertFalse(FileSort(FileOrder.Name).reversed)
  }

  @Test
  fun fileSort_saved_isReadBackPerSurface() {
    val settings = AppSettingsController()

    settings.saveFileSort(FileSortSurface.Inspector, FileSort(FileOrder.Kind).reverse())
    settings.saveFileSort(FileSortSurface.Intake, FileSort(FileOrder.Size))

    assertEquals(
      FileSort(FileOrder.Kind, descending = true),
      settings.fileSort(FileSortSurface.Inspector)
    )
    assertEquals(FileSort(FileOrder.Size), settings.fileSort(FileSortSurface.Intake))
    assertEquals("kind:desc", settings.ui.sort["torrentFiles.inspector"])
    assertEquals("size:desc", settings.ui.sort["torrentFiles.intake"])
  }

  @Test
  fun fileSort_unreadableValue_isTheDefault() {
    val ui = UiPreferences(sort = mapOf("torrentFiles.inspector" to "???"))
    val settings = AppSettingsController(RecordingConfigStore(KetchConfig(ui = ui)))

    assertEquals(FileSort(FileOrder.Progress), settings.fileSort(FileSortSurface.Inspector))
    assertEquals(FileSort(FileOrder.Name), settings.fileSort(FileSortSurface.Intake))
  }

  private fun sorted(sort: FileSort): List<String?> =
    files.sortedByFiles(sort) { it }.map { it.path }

  private fun sortedIndexes(sort: FileSort): List<Int> =
    files.sortedByFiles(sort) { it }.map { it.index }

  private fun key(
    path: String,
    size: Long,
    index: Int,
    selected: Boolean = true,
    progress: Float? = null,
  ) = FileSortKey(path, size, index, selected, progress)
}
