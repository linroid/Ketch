package com.linroid.kdown.server.mdns

import com.linroid.kdown.core.log.KDownLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MdnsRegistrar] implementation using the native `dns-sd` command
 * on macOS.
 *
 * JmDNS conflicts with macOS's built-in mDNSResponder daemon, so
 * this delegates to `dns-sd -R` which registers through the system
 * mDNS stack. The subprocess stays alive to keep the service
 * advertised and is destroyed on [unregister].
 */
internal class NativeMdnsRegistrar : MdnsRegistrar {
  @Volatile private var process: Process? = null

  override suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  ) {
    unregister()
    val cmd = mutableListOf(
      "dns-sd", "-R",
      serviceName, serviceType, ".", port.toString(),
    )
    metadata.forEach { (key, value) -> cmd.add("$key=$value") }
    KDownLogger.d(TAG) { "Exec: ${cmd.joinToString(" ")}" }
    process = withContext(Dispatchers.IO) {
      ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    }
  }

  override suspend fun unregister() {
    process?.let { proc ->
      runCatching { proc.destroyForcibly() }
    }
    process = null
  }

  companion object {
    private const val TAG = "NativeMdnsRegistrar"
  }
}
