package com.linroid.ketch.ai.agent

/**
 * Callback for receiving progress updates during agent-driven
 * resource discovery.
 */
interface DiscoveryStepListener {

  /** Called when the agent completes a notable step. */
  fun onStep(title: String, details: String)

  companion object {
    /** No-op listener that discards all steps. */
    val None: DiscoveryStepListener = object : DiscoveryStepListener {
      override fun onStep(title: String, details: String) {}
    }
  }
}
