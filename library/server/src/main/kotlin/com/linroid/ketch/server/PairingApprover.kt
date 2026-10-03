package com.linroid.ketch.server

import com.linroid.ketch.endpoints.model.PairingRequest

/**
 * Decides whether a device that asked a [KetchServer] for its access token may have it, usually
 * by asking whoever sits at this one.
 */
fun interface PairingApprover {
  /**
   * Returns whether the device that sent [request] from [address] may control the server. The
   * call is cancelled when the device withdraws the request, nobody answers in time or the
   * server stops.
   *
   * @param address the IP address the request came from.
   */
  suspend fun approve(request: PairingRequest, address: String): Boolean
}
