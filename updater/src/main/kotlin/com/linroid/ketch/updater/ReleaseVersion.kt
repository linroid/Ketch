package com.linroid.ketch.updater

/**
 * A Ketch version, such as `0.0.1-rc15`: MAJOR.MINOR.PATCH and an optional pre-release after a
 * dash.
 *
 * Versions order as releases do: a pre-release comes before its release, and pre-release parts
 * compare runs of digits by value, so `rc9` comes before `rc15` (semantic versioning would
 * compare them as text).
 *
 * @property preRelease the part after the dash, such as `rc15`; `null` for a release.
 */
data class ReleaseVersion(
  val major: Int,
  val minor: Int,
  val patch: Int,
  val preRelease: String? = null,
) : Comparable<ReleaseVersion> {

  override fun compareTo(other: ReleaseVersion): Int {
    val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    return if (core != 0) core else comparePreRelease(preRelease, other.preRelease)
  }

  override fun toString(): String = "$major.$minor.$patch" + preRelease?.let { "-$it" }.orEmpty()

  companion object {
    private val PATTERN =
      Regex("""v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?""")

    /**
     * Parses [text], such as `0.0.1-rc15` or the tag `v0.0.1-rc15`; build metadata after a `+`
     * is dropped. Returns `null` when [text] is not a version.
     */
    fun parse(text: String): ReleaseVersion? {
      val match = PATTERN.matchEntire(text.trim()) ?: return null
      val (major, minor, patch, preRelease) = match.destructured
      return ReleaseVersion(
        major = major.toInt(),
        minor = minor.toInt(),
        patch = patch.toInt(),
        preRelease = preRelease.ifEmpty { null },
      )
    }
  }
}

/** A release comes after its pre-releases; pre-releases compare dot-separated parts in order. */
private fun comparePreRelease(a: String?, b: String?): Int = when {
  a == b -> 0
  a == null -> 1
  b == null -> -1
  else -> {
    val left = a.split('.')
    val right = b.split('.')
    left.zip(right).firstNotNullOfOrNull { (x, y) -> naturalCompare(x, y).takeIf { it != 0 } }
      ?: left.size.compareTo(right.size)
  }
}

/** Compares runs of digits by value and other runs as text, so `rc9` comes before `rc15`. */
private fun naturalCompare(a: String, b: String): Int {
  val left = RUNS.findAll(a).map { it.value }.toList()
  val right = RUNS.findAll(b).map { it.value }.toList()
  for ((x, y) in left.zip(right)) {
    val order = if (x[0].isDigit() && y[0].isDigit()) compareNumbers(x, y) else x.compareTo(y)
    if (order != 0) return order
  }
  return left.size.compareTo(right.size)
}

/** Compares two runs of digits of any length by value. */
private fun compareNumbers(a: String, b: String): Int {
  val x = a.trimStart('0')
  val y = b.trimStart('0')
  return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
}

private val RUNS = Regex("""\d+|\D+""")
