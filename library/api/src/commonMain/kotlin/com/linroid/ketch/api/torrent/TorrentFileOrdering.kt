package com.linroid.ketch.api.torrent

/**
 * Case-insensitive natural order of strings such as file paths: runs of digits compare by their
 * numeric value, so "Episode 2" comes before "Episode 10", and other characters compare without
 * case. Strings that differ only in case or in leading zeros are ordered by those last, so the
 * order is total.
 */
object NaturalOrder : Comparator<String> {
  override fun compare(a: String, b: String): Int {
    var i = 0
    var j = 0
    // Decides between strings that are equal apart from case or leading zeros.
    var tieBreak = 0
    while (i < a.length && j < b.length) {
      val ca = a[i]
      val cb = b[j]
      if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
        val startA = i
        val startB = j
        while (i < a.length && a[i].isAsciiDigit()) i++
        while (j < b.length && b[j].isAsciiDigit()) j++
        val digits = compareNumbers(a, startA, i, b, startB, j)
        if (digits != 0) return digits
        // Equal values: fewer leading zeros first.
        if (tieBreak == 0) tieBreak = (i - startA).compareTo(j - startB)
        continue
      }
      if (ca != cb) {
        val folded = ca.lowercaseChar().compareTo(cb.lowercaseChar())
        if (folded != 0) return folded
        if (tieBreak == 0) tieBreak = ca.compareTo(cb)
      }
      i++
      j++
    }
    val remaining = (a.length - i).compareTo(b.length - j)
    return if (remaining != 0) remaining else tieBreak
  }

  /** Compares the digit runs `a[startA until endA]` and `b[startB until endB]` by value. */
  private fun compareNumbers(
    a: String,
    startA: Int,
    endA: Int,
    b: String,
    startB: Int,
    endB: Int,
  ): Int {
    var sa = startA
    var sb = startB
    while (sa < endA - 1 && a[sa] == '0') sa++
    while (sb < endB - 1 && b[sb] == '0') sb++
    val lengths = (endA - sa).compareTo(endB - sb)
    if (lengths != 0) return lengths
    while (sa < endA) {
      val digit = a[sa].compareTo(b[sb])
      if (digit != 0) return digit
      sa++
      sb++
    }
    return 0
  }

  private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/**
 * The lowercased extension of the last segment of [path] (after its last `.`), or an empty string
 * when it has none. A leading dot, as in `.hidden`, starts a name rather than an extension.
 */
fun fileExtension(path: String): String {
  val name = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
  val dot = name.lastIndexOf('.')
  return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
}

/**
 * These files, in torrent order, sorted by [order] the way [TorrentController.files] sorts them:
 * [descending] reverses the order, and files the order ranks equal keep their torrent order, so
 * the result is total and stable. [path], [size] and [selected] read a file's full path inside
 * the torrent, its size and whether it is chosen.
 */
fun <T> List<T>.sortedByFileOrder(
  order: TorrentFileOrder,
  descending: Boolean = false,
  path: (T) -> String,
  size: (T) -> Long,
  selected: (T) -> Boolean,
): List<T> {
  val byName = Comparator<IndexedValue<T>> { a, b ->
    NaturalOrder.compare(path(a.value), path(b.value))
  }
  val key: Comparator<IndexedValue<T>> = when (order) {
    TorrentFileOrder.TORRENT -> compareBy { it.index }
    TorrentFileOrder.NAME -> byName
    TorrentFileOrder.SIZE -> compareBy { size(it.value) }
    TorrentFileOrder.EXTENSION ->
      compareBy<IndexedValue<T>> { fileExtension(path(it.value)) }.then(byName)
    TorrentFileOrder.SELECTED ->
      compareBy<IndexedValue<T>> { !selected(it.value) }.then(byName)
  }
  val directed = if (descending) key.reversed() else key
  return withIndex().sortedWith(directed.thenBy { it.index }).map { it.value }
}
