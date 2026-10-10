package com.linroid.ketch.api.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentFileOrderingTest {
  private class File(val path: String, val size: Long, val selected: Boolean = true)

  private fun List<File>.sorted(order: TorrentFileOrder, descending: Boolean = false) =
    sortedByFileOrder(order, descending, { it.path }, { it.size }, { it.selected }).map { it.path }

  @Test
  fun naturalOrder_comparesNumbersByValue() {
    val names = listOf("Episode 10.mkv", "Episode 2.mkv", "Episode 1.mkv", "Episode 02b.mkv")
    assertEquals(
      listOf("Episode 1.mkv", "Episode 2.mkv", "Episode 02b.mkv", "Episode 10.mkv"),
      names.sortedWith(NaturalOrder),
    )
  }

  @Test
  fun naturalOrder_ignoresCase() {
    assertTrue(NaturalOrder.compare("alpha", "Beta") < 0)
    assertTrue(NaturalOrder.compare("Alpha", "beta") < 0)
    assertTrue(NaturalOrder.compare("b/Z", "B/a") > 0)
  }

  @Test
  fun naturalOrder_isTotalForCaseAndLeadingZeros() {
    assertTrue(NaturalOrder.compare("A", "a") != 0)
    assertEquals(-NaturalOrder.compare("A", "a"), NaturalOrder.compare("a", "A"))
    assertTrue(NaturalOrder.compare("file 7", "file 007") < 0)
    assertEquals(0, NaturalOrder.compare("same", "same"))
  }

  @Test
  fun naturalOrder_comparesLongNumbers() {
    assertTrue(NaturalOrder.compare("part 99999999999999999999", "part 100000000000000000000") < 0)
    assertTrue(NaturalOrder.compare("v1.10", "v1.9") > 0)
  }

  @Test
  fun naturalOrder_shorterPrefixComesFirst() {
    assertTrue(NaturalOrder.compare("Season 1", "Season 1/Episode 1") < 0)
  }

  @Test
  fun fileExtension_isLowercasedLastSuffix() {
    assertEquals("mkv", fileExtension("Show/Episode.1.MKV"))
    assertEquals("", fileExtension("Show/README"))
    assertEquals("", fileExtension("Show/.hidden"))
    assertEquals("", fileExtension("dir.d/file"))
    assertEquals("gz", fileExtension("a\\b.tar.gz"))
  }

  private val files = listOf(
    File("Show/Episode 10.mkv", 300),
    File("Show/episode 2.srt", 10, selected = false),
    File("Show/Episode 2.mkv", 300),
    File("Show/notes.txt", 10, selected = false),
    File("Show/Episode 1.mkv", 200),
  )

  @Test
  fun torrentOrder_keepsTheListAndReverses() {
    assertEquals(files.map { it.path }, files.sorted(TorrentFileOrder.TORRENT))
    assertEquals(files.map { it.path }.reversed(), files.sorted(TorrentFileOrder.TORRENT, true))
  }

  @Test
  fun nameOrder_isNatural() {
    assertEquals(
      listOf(
        "Show/Episode 1.mkv", "Show/Episode 2.mkv", "Show/episode 2.srt", "Show/Episode 10.mkv",
        "Show/notes.txt",
      ),
      files.sorted(TorrentFileOrder.NAME),
    )
  }

  @Test
  fun sizeOrder_breaksTiesByTorrentOrderInBothDirections() {
    assertEquals(
      listOf(
        "Show/episode 2.srt", "Show/notes.txt", "Show/Episode 1.mkv", "Show/Episode 10.mkv",
        "Show/Episode 2.mkv",
      ),
      files.sorted(TorrentFileOrder.SIZE),
    )
    assertEquals(
      listOf(
        "Show/Episode 10.mkv", "Show/Episode 2.mkv", "Show/Episode 1.mkv", "Show/episode 2.srt",
        "Show/notes.txt",
      ),
      files.sorted(TorrentFileOrder.SIZE, descending = true),
    )
  }

  @Test
  fun extensionOrder_groupsByExtensionThenName() {
    assertEquals(
      listOf(
        "Show/Episode 1.mkv", "Show/Episode 2.mkv", "Show/Episode 10.mkv", "Show/episode 2.srt",
        "Show/notes.txt",
      ),
      files.sorted(TorrentFileOrder.EXTENSION),
    )
  }

  @Test
  fun selectedOrder_putsSelectedFirstThenName() {
    assertEquals(
      listOf(
        "Show/Episode 1.mkv", "Show/Episode 2.mkv", "Show/Episode 10.mkv", "Show/episode 2.srt",
        "Show/notes.txt",
      ),
      files.sorted(TorrentFileOrder.SELECTED),
    )
    assertEquals(
      listOf(
        "Show/notes.txt", "Show/episode 2.srt", "Show/Episode 10.mkv", "Show/Episode 2.mkv",
        "Show/Episode 1.mkv",
      ),
      files.sorted(TorrentFileOrder.SELECTED, descending = true),
    )
  }

  @Test
  fun equalPaths_keepTorrentOrder() {
    val same = listOf(File("a", 1), File("a", 2), File("a", 3))
    val sorted = same.sortedByFileOrder(TorrentFileOrder.NAME, true, { it.path }, { 0 }, { true })
    assertEquals(listOf(1L, 2L, 3L), sorted.map { it.size })
  }

  @Test
  fun wireNames_roundTrip() {
    TorrentFileOrder.entries.forEach { assertEquals(it, TorrentFileOrder.fromWire(it.wireName)) }
    assertEquals(null, TorrentFileOrder.fromWire("kind"))
  }
}
