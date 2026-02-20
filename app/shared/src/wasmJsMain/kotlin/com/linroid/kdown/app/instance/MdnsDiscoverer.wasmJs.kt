package com.linroid.kdown.app.instance

internal actual fun createMdnsDiscoverer(): MdnsDiscoverer =
  NoOpMdnsDiscoverer