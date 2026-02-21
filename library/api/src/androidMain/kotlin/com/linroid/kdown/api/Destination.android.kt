@file:Suppress("UseKtx")

package com.linroid.kdown.api

import android.net.Uri
import android.provider.DocumentsContract

actual fun Destination.isFile(): Boolean =
  !isName() && !isDirectory()

actual fun Destination.isDirectory(): Boolean {
  val uri = Uri.parse(value)
  if (uri.scheme == "content") {
    return DocumentsContract.isTreeUri(uri)
  }
  return value.endsWith('/') || value.endsWith('\\')
}

actual fun Destination.isName(): Boolean {
  val uri = Uri.parse(value)
  if (uri.scheme != null) return false
  return !value.contains('/') && !value.contains('\\')
}
