package com.linroid.ketch.api

/** How [sortedByFileOrder] sorts files; ties keep the [SOURCE] order. */
enum class FileOrder {
  /** As the source lists them. */
  SOURCE,

  /** The full path in [NaturalOrder]. */
  NAME,

  /** Smallest first. */
  SIZE,

  /** [fileExtension], then [NAME]. */
  EXTENSION,

  /** Selected files first, then [NAME]. */
  SELECTED,
}

/**
 * Case-insensitive order in which digit runs compare by value ("Episode 2" before "Episode 10").
 * Case and leading zeros only break ties, so the order is total.
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

  /** Compares two digit runs by value. */
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

/** The lowercased extension of [path]'s last segment, or empty (`.hidden` has none). */
fun fileExtension(path: String): String {
  val name = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
  val dot = name.lastIndexOf('.')
  return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
}

/**
 * These files, in source order, sorted by [order] ([descending] reverses it); ties keep source
 * order. File pages, such as the torrent controller's, sort the same way.
 */
fun <T> List<T>.sortedByFileOrder(
  order: FileOrder,
  descending: Boolean = false,
  path: (T) -> String,
  size: (T) -> Long,
  selected: (T) -> Boolean,
): List<T> {
  val byName = Comparator<IndexedValue<T>> { a, b ->
    NaturalOrder.compare(path(a.value), path(b.value))
  }
  val key: Comparator<IndexedValue<T>> = when (order) {
    FileOrder.SOURCE -> compareBy { it.index }
    FileOrder.NAME -> byName
    FileOrder.SIZE -> compareBy { size(it.value) }
    FileOrder.EXTENSION ->
      compareBy<IndexedValue<T>> { fileExtension(path(it.value)) }.then(byName)
    FileOrder.SELECTED ->
      compareBy<IndexedValue<T>> { !selected(it.value) }.then(byName)
  }
  val directed = if (descending) key.reversed() else key
  return withIndex().sortedWith(directed.thenBy { it.index }).map { it.value }
}
