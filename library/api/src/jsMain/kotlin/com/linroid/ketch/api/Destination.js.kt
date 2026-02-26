package com.linroid.ketch.api

actual fun Destination.isFile(): Boolean =
  !isName() && !isDirectory()

actual fun Destination.isDirectory(): Boolean =
  value.endsWith('/')

actual fun Destination.isName(): Boolean =
  !value.contains('/')
