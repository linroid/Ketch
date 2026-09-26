package com.linroid.ketch.app.state

import com.linroid.ketch.api.SpeedLimit

/** Unit a custom speed limit is typed in. */
enum class SpeedUnit(val label: String, val bytes: Long) {
  KB("KB/s", 1024L),
  MB("MB/s", 1024L * 1024L),
}

/** Speed limits offered in the settings drop-down, slowest first. */
val SpeedLimitPresets: List<SpeedLimit> = listOf(
  SpeedLimit.Unlimited,
  SpeedLimit.kbps(256),
  SpeedLimit.kbps(512),
  SpeedLimit.mbps(1),
  SpeedLimit.mbps(2),
  SpeedLimit.mbps(5),
  SpeedLimit.mbps(10),
  SpeedLimit.mbps(20),
  SpeedLimit.mbps(50),
)

/**
 * [presets] plus [current], so a value set in the config file by hand
 * still shows up as a choice. Sorted ascending, with `0` — which the
 * download settings use for "no limit" — last.
 */
fun countChoices(presets: List<Int>, current: Int): List<Int> =
  (presets + current).distinct().sortedWith(compareBy({ it == 0 }, { it }))

/** "Unlimited", "512 KB/s", "2 MB/s" or "1.5 MB/s". */
fun formatSpeedLimit(limit: SpeedLimit): String {
  if (limit.isUnlimited) return "Unlimited"
  val unit = preferredUnit(limit)
  return "${formatSpeedAmount(limit, unit)} ${unit.label}"
}

/** MB/s from 1 MB/s up, KB/s below. */
fun preferredUnit(limit: SpeedLimit): SpeedUnit =
  if (limit.bytesPerSecond >= SpeedUnit.MB.bytes) SpeedUnit.MB else SpeedUnit.KB

/** [limit] in [unit], with up to two decimals: "2", "1.5", "0.25". */
fun formatSpeedAmount(limit: SpeedLimit, unit: SpeedUnit): String {
  val hundredths = (limit.bytesPerSecond * 100 + unit.bytes / 2) / unit.bytes
  val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
  return if (fraction.isEmpty()) "${hundredths / 100}" else "${hundredths / 100}.$fraction"
}

/**
 * Reads a typed custom limit such as "750" or "1.5", or `null` when
 * [amount] is not a positive number.
 */
fun parseSpeedLimit(amount: String, unit: SpeedUnit): SpeedLimit? {
  val value = amount.trim().toDoubleOrNull() ?: return null
  if (value.isNaN() || value <= 0.0 || value > 1_000_000.0) return null
  val bytes = (value * unit.bytes).toLong()
  return if (bytes > 0) SpeedLimit.of(bytes) else null
}

/** Why [text] is not a usable TCP port, or `null` when it is. */
fun portError(text: String): String? {
  val port = text.trim().toIntOrNull()
  return if (port == null || port !in 1..65535) "Enter a port from 1 to 65535." else null
}
