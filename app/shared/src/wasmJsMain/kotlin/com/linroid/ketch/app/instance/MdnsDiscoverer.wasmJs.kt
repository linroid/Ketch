package com.linroid.ketch.app.instance

internal actual fun createMdnsDiscoverer(): MdnsDiscoverer =
  NoOpMdnsDiscoverer