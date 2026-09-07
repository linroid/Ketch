package com.linroid.ketch.torrent

internal actual fun createTorrentEngine(config: TorrentConfig): TorrentEngine =
  KotlinTorrentEngine(config)

internal actual fun platformTorrentFileSystem(): okio.FileSystem =
  if (System.getProperty("java.vm.name") == "Dalvik") AndroidTorrentFileSystem
  // Android host tests run without ART; device tests exercise OS descriptors.
  else okio.FileSystem.SYSTEM

internal actual fun platformTorrentFileIdentity(path: okio.Path): String? =
  nioTorrentFileIdentity(path)
