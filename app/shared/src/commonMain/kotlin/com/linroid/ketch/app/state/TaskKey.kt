package com.linroid.ketch.app.state

import com.linroid.ketch.app.util.isUnreserved
import com.linroid.ketch.app.util.percentEncode

/** Device id of the engine running inside the app. */
const val LOCAL_DEVICE_ID: String = "local"

/**
 * Identifies a task across devices; a task id is only unique on the device that runs it.
 *
 * Android saves lazy list item keys in a `Bundle`, which cannot hold this class, so lists key
 * their items by [encode].
 *
 * @property deviceId id of the device that runs the task, [LOCAL_DEVICE_ID] for the embedded one.
 * @property taskId id of the task on that device.
 */
data class TaskKey(
  val deviceId: String,
  val taskId: String,
) {
  /** Encodes this key as the in-app drag payload `ketch-task://{deviceId}/{taskId}`. */
  fun encode(): String = "$SCHEME${percentEncode(deviceId)}/${percentEncode(taskId)}"

  companion object {
    private const val SCHEME = "ketch-task://"

    /** Decodes a payload made by [encode], or returns `null` when [value] is not one. */
    fun decode(value: String): TaskKey? {
      if (!value.startsWith(SCHEME)) return null
      val parts = value.substring(SCHEME.length).split('/')
      if (parts.size != 2) return null
      val deviceId = percentDecode(parts[0]) ?: return null
      val taskId = percentDecode(parts[1]) ?: return null
      if (deviceId.isEmpty() || taskId.isEmpty()) return null
      return TaskKey(deviceId, taskId)
    }
  }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun percentDecode(value: String): String? {
  val bytes = ArrayList<Byte>(value.length)
  var i = 0
  while (i < value.length) {
    val c = value[i]
    if (c == '%') {
      if (i + 2 >= value.length) return null
      val high = HEX_DIGITS.indexOf(value[i + 1].uppercaseChar())
      val low = HEX_DIGITS.indexOf(value[i + 2].uppercaseChar())
      if (high < 0 || low < 0) return null
      bytes.add(((high shl 4) or low).toByte())
      i += 3
    } else {
      if (!isUnreserved(c)) return null
      bytes.add(c.code.toByte())
      i++
    }
  }
  return bytes.toByteArray().decodeToString()
}
