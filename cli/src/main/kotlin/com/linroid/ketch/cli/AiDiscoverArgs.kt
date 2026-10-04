package com.linroid.ketch.cli

/** Arguments of `ketch ai-discover <query> [options]`. */
internal sealed interface AiDiscoverArgs {
  /** No arguments, `--help` or `-h`. */
  data object Help : AiDiscoverArgs

  /** The arguments cannot be used; [message] says why. */
  data class Invalid(val message: String) : AiDiscoverArgs

  /**
   * @param query the words that are not options, joined by spaces
   * @param sites websites the search is limited to
   * @param maxResults candidates to show at most
   * @param allowAll `--yes`: open every website without asking
   */
  data class Discover(
    val query: String,
    val sites: List<String> = emptyList(),
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val allowAll: Boolean = false,
  ) : AiDiscoverArgs
}

private const val DEFAULT_MAX_RESULTS = 5

/** Parses [args], the arguments after `ai-discover`, which no longer contain the global flags. */
internal fun parseAiDiscoverArgs(args: List<String>): AiDiscoverArgs {
  if (args.isEmpty()) return AiDiscoverArgs.Help
  val words = mutableListOf<String>()
  var sites = emptyList<String>()
  var maxResults = DEFAULT_MAX_RESULTS
  var allowAll = false

  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--help" || arg == "-h" -> return AiDiscoverArgs.Help
      arg == "--yes" || arg == "-y" -> allowAll = true
      arg == "--sites" || arg == "--max-results" -> {
        val value = args.getOrNull(++i)
          ?: return AiDiscoverArgs.Invalid("$arg requires a value")
        if (arg == "--sites") {
          sites = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
          if (sites.isEmpty()) return AiDiscoverArgs.Invalid("--sites requires a domain")
        } else {
          maxResults = value.toIntOrNull()
            ?: return AiDiscoverArgs.Invalid("invalid number '$value'")
          if (maxResults <= 0) return AiDiscoverArgs.Invalid("--max-results must be > 0")
        }
      }
      arg.length > 1 && arg.startsWith("-") ->
        return AiDiscoverArgs.Invalid("unknown option '$arg'")
      else -> words += arg
    }
    i++
  }

  val query = words.joinToString(" ").trim()
  if (query.isEmpty()) return AiDiscoverArgs.Invalid("missing <query>")
  return AiDiscoverArgs.Discover(
    query = query,
    sites = sites,
    maxResults = maxResults,
    allowAll = allowAll,
  )
}
