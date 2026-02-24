package com.linroid.ketch.ai.server

import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.api.KetchApi
import io.ktor.server.routing.Route

/**
 * Creates a KetchServer route plugin that installs AI discovery endpoints.
 *
 * Usage:
 * ```kotlin
 * KetchServer(
 *   ketch = ketch,
 *   plugins = listOfNotNull(
 *     aiConfig?.let { ketchAiPlugin(it) },
 *   ),
 * )
 * ```
 */
fun ketchAiPlugin(config: AiConfig): Route.(KetchApi) -> Unit {
  val aiModule = AiModule.create(config)
  return { ketch ->
    aiRoutes(ketch, aiModule.discoveryService, aiModule.siteProfiler, aiModule.siteProfileStore)
  }
}
