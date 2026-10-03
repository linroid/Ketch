package com.linroid.ketch.ai.agent

/**
 * Callback for receiving progress updates during agent-driven
 * resource discovery.
 */
interface DiscoveryStepListener {

  /**
   * Called when the agent completes a notable step. [title] and [details] are the agent's own
   * words, each reduced to one line of plain text (see [sanitizeAgentText]).
   */
  fun onStep(title: String, details: String)

  companion object {
    /** No-op listener that discards all steps. */
    val None: DiscoveryStepListener = object : DiscoveryStepListener {
      override fun onStep(title: String, details: String) {}
    }
  }
}
