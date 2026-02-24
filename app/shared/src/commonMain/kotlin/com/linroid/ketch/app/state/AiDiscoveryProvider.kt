package com.linroid.ketch.app.state

import com.linroid.ketch.endpoints.model.DiscoverRequest
import com.linroid.ketch.endpoints.model.DiscoverResponse
import com.linroid.ketch.remote.RemoteKetch
import com.linroid.ketch.remote.discoverResources

/**
 * Abstraction for AI resource discovery, allowing both remote
 * (server-side) and embedded (in-process) implementations.
 */
interface AiDiscoveryProvider {
  suspend fun discover(request: DiscoverRequest): DiscoverResponse
}

/**
 * Delegates AI discovery to a remote Ketch server via HTTP.
 */
class RemoteAiDiscoveryProvider(
  private val remote: RemoteKetch,
) : AiDiscoveryProvider {
  override suspend fun discover(
    request: DiscoverRequest,
  ): DiscoverResponse = remote.discoverResources(request)
}
