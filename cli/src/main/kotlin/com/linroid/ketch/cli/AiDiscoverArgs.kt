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
   * @param noFilter `--no-filter`: show the results the content filter would hide, whatever
   *   `[ai] contentFilter` says
   * @param provider `--provider`: the saved provider to search with, by its id or name, or a
   *   provider such as `deepseek`; `null` for the one the settings use
   * @param model `--model`: the model to call; `null` for the provider's own
   */
  data class Discover(
    val query: String,
    val sites: List<String> = emptyList(),
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val allowAll: Boolean = false,
    val noFilter: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
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
  var noFilter = false
  var provider: String? = null
  var model: String? = null

  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--help" || arg == "-h" -> return AiDiscoverArgs.Help
      arg == "--yes" || arg == "-y" -> allowAll = true
      arg == "--no-filter" -> noFilter = true
      arg in VALUE_OPTIONS -> {
        val value = args.getOrNull(++i)
          ?: return AiDiscoverArgs.Invalid("$arg requires a value")
        when (arg) {
          "--sites" -> {
            sites = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (sites.isEmpty()) return AiDiscoverArgs.Invalid("--sites requires a domain")
          }
          "--max-results" -> {
            maxResults = value.toIntOrNull()
              ?: return AiDiscoverArgs.Invalid("invalid number '$value'")
            if (maxResults <= 0) return AiDiscoverArgs.Invalid("--max-results must be > 0")
          }
          "--provider" -> provider = value.trim().ifEmpty {
            return AiDiscoverArgs.Invalid("--provider requires a name")
          }
          "--model" -> model = value.trim().ifEmpty {
            return AiDiscoverArgs.Invalid("--model requires a model id")
          }
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
    noFilter = noFilter,
    provider = provider,
    model = model,
  )
}

/** Options that take the argument after them as their value. */
private val VALUE_OPTIONS = setOf("--sites", "--max-results", "--provider", "--model")
