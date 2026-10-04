package com.linroid.ketch.ai.agent

/**
 * Callback for receiving progress updates during agent-driven
 * resource discovery.
 */
interface DiscoveryStepListener {

  /**
   * Called when the agent completes a notable step. [title] and [details] are the agent's own
   * words as plain text (see [sanitizeAgentText]): [title] is one line, while [details] keeps
   * up to 12 lines, joined with `\n`, such as the items of a numbered plan.
   */
  fun onStep(title: String, details: String)

  companion object {
    /** No-op listener that discards all steps. */
    val None: DiscoveryStepListener = object : DiscoveryStepListener {
      override fun onStep(title: String, details: String) {}
    }
  }
}
