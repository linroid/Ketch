@file:Suppress("UseKtx")

package com.linroid.ketch.api

import android.net.Uri
import android.provider.DocumentsContract

actual fun Destination.isFile(): Boolean =
  !isName() && !isDirectory()

actual fun Destination.isDirectory(): Boolean {
  if (':' !in value) return value.endsWith('/') || value.endsWith('\\')
  val uri = Uri.parse(value)
  if (uri.scheme == "content") {
    return DocumentsContract.isTreeUri(uri)
  }
  return value.endsWith('/') || value.endsWith('\\')
}

actual fun Destination.isName(): Boolean {
  if (':' !in value) return '/' !in value && '\\' !in value
  val uri = Uri.parse(value)
  if (uri.scheme != null) return false
  return !value.contains('/') && !value.contains('\\')
}
