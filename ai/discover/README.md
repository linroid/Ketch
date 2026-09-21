# ai:discover — AI agent-driven resource discovery

Discovers downloadable files from natural language queries
using an LLM agent with tool-calling capabilities.

## Overview

Given a query like *"latest Ubuntu 24.04 desktop ISO"*, the agent autonomously
searches the web, fetches relevant pages, extracts download links, validates
them for safety, and returns a ranked list of candidates.

The module depends on `library:api` and `config` (for the persisted
`AiSettings`) — it is independent from the server and remote modules.

## Architecture

```
ai/discover/
├── AiModule.kt                  # Public entry point + factory
├── AiConfig.kt                  # Configuration + env credential fallbacks
├── LlmClientFactory.kt          # Provider → Koog client and model
├── ResourceDiscoveryService.kt  # Koog AIAgent orchestrator
├── DiscoverQuery.kt             # Input model
├── DiscoverResult.kt            # Output model
├── RankedCandidate.kt           # Single discovery result
│
├── agent/                       # Agent-driven discovery
│   ├── DiscoveryToolSet.kt      # 7 @Tool methods for the LLM agent
│   ├── AgentOutputParser.kt     # Parse + validate agent JSON output
│   ├── DeviceSafetyFilter.kt    # URL safety scoring
│   ├── LinkExtractor.kt         # Download link extraction from HTML
│   └── DiscoveryStepListener.kt # Progress callback interface
│
├── fetch/                       # HTTP fetching with security
│   ├── SafeFetcher.kt           # SSRF-protected GET + HEAD
│   ├── UrlValidator.kt          # SSRF protection (blocks private IPs, non-HTTP)
│   ├── ContentExtractor.kt      # HTML → text extraction
│   └── RateLimiter.kt           # Per-domain + global rate limiting
│
├── search/                      # Web search abstraction
│   ├── SearchProvider.kt        # Interface
│   └── DummySearchProvider.kt   # No-op fallback
│
└── site/                        # Site profiling
    ├── SiteProfiler.kt          # robots.txt, sitemap, RSS discovery
    ├── SiteProfile.kt           # Profile data model
    ├── SiteProfileStore.kt      # In-memory cache
    └── RobotsTxtParser.kt       # robots.txt parser
```

## Agent Workflow

The core of the module is a [Koog](https://github.com/JetBrains/koog) `AIAgent`
that follows a structured 5-phase workflow:

```
┌─────────────────────────────────────────────────┐
│  1. UNDERSTAND                                  │
│     Analyze query → file types, platform, etc.  │
├─────────────────────────────────────────────────┤
│  2. PLAN                                        │
│     Create 3-6 search/fetch steps with budgets  │
├─────────────────────────────────────────────────┤
│  3. DISCOVER (iterative loop)                   │
│     searchWeb/searchSites → validateUrl →       │
│     fetchPage → extractDownloads → headUrl      │
│     Budget: 6 searches, 10 fetches, 15 HEADs   │
├─────────────────────────────────────────────────┤
│  4. SCORE & FILTER                              │
│     Relevance + device safety scoring           │
│     Block: shorteners, aggregators, piracy      │
├─────────────────────────────────────────────────┤
│  5. OUTPUT                                      │
│     JSON array of ranked candidates             │
└─────────────────────────────────────────────────┘
```

### Agent Tools

| Tool | Description | Backend |
|------|-------------|---------|
| `searchWeb(query, maxResults)` | Web search | `SearchProvider.search()` |
| `searchSites(sites, query, maxResults)` | Site-restricted search | `SearchProvider.search(sites=)` |
| `fetchPage(url)` | Fetch + extract text and links | `SafeFetcher` + `ContentExtractor` + `LinkExtractor` |
| `headUrl(url)` | HTTP HEAD for metadata | `SafeFetcher.head()` |
| `extractDownloads(pageText, baseUrl)` | Extract download links from HTML | `LinkExtractor` |
| `validateUrl(url)` | SSRF + allowlist check | `UrlValidator` |
| `emitStep(title, details)` | Report progress to the user | `DiscoveryStepListener` |

## Data Flow

```
DiscoverQuery (query, sites, maxResults, fileTypes)
         │
         ▼
ResourceDiscoveryService.discover()
         │
         ├── Build DiscoveryToolSet (with allowedDomains)
         ├── Create Koog AIAgent (system prompt + tools)
         ├── agent.run(userMessage)
         │       │
         │       ├── [Agent calls tools iteratively]
         │       │   searchWeb → fetchPage → extractDownloads → headUrl
         │       │   (each tool wraps existing utilities)
         │       │
         │       └── Returns JSON array of candidates
         │
         ├── AgentOutputParser.parse(agentOutput)
         │       ├── Extract JSON from markdown/raw text
         │       ├── UrlValidator.validate() each URL
         │       ├── DeviceSafetyFilter.evaluate() each URL
         │       ├── Adjust confidence by safety score
         │       └── Deduplicate by URL
         │
         └── DiscoverResult (candidates + sources)
```

## Security

### SSRF Protection (`UrlValidator`)
- Blocks private/local IPs (loopback, link-local, site-local, carrier-grade NAT)
- Blocks internal hostnames (`.local`, `.internal`, `localhost`, single-word)
- Only allows `http` and `https` schemes
- Applied before every fetch and HEAD request

### Device Safety (`DeviceSafetyFilter`)
- Base score 0.7, adjusted by heuristics:
  - HTTPS → +0.1; HTTP → -0.2
  - Trusted domain (github.com, gitlab.com, etc.) → +0.2
  - Checksums mentioned → +0.15
  - Content-type mismatch → -0.3
- Hard-blocked: URL shorteners, aggregator sites, piracy signals,
  high-risk extensions from untrusted sources
- Blocked if final score < 0.3

### Other Protections
- Rate limiting: per-domain delays + global concurrent cap
- Content size caps: 2 MB per fetch, 20 MB per request
- robots.txt compliance
- Prompt injection defense: fetched content treated as untrusted data

## Usage

### Programmatic

```kotlin
val aiModule = AiModule.create(
  config = AiConfig(
    settings = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "sk-..."),
    ),
  ),
)

val result = aiModule.discoveryService.discover(
  DiscoverQuery(
    query = "latest Ubuntu 24.04 desktop ISO",
    sites = listOf("ubuntu.com", "releases.ubuntu.com"),
    maxResults = 5,
    fileTypes = listOf("iso"),
  ),
)

for (candidate in result.candidates) {
  println("${candidate.title}: ${candidate.url}")
}
```

### With Progress Listener

```kotlin
val listener = object : DiscoveryStepListener {
  override fun onStep(title: String, details: String) {
    println("[$title] $details")
  }
}

val aiModule = AiModule.create(
  config = AiConfig(
    settings = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant-..."),
    ),
  ),
  stepListener = listener,
)
```

### Apps

The desktop and Android apps configure discovery on the **Settings**
page (provider, API token, model, endpoint and web search). Settings are
persisted in `config.toml` under `[ai]`, so the CLI picks up the same
configuration. See [docs/ai-discovery.md](../../docs/ai-discovery.md).

### CLI

```bash
# Uses [ai] from config.toml; blank credentials fall back to the env.
ketch ai-discover "latest Ubuntu 24.04 ISO"
OPENAI_API_KEY=sk-... ketch ai-discover "ffmpeg release" --sites ffmpeg.org
```

## Configuration

User-facing settings live in the `config` module (`AiSettings`) so the
apps, the CLI and this module share one representation; the remaining
sections are engine tuning knobs.

| Config | Field | Default | Description |
|--------|-------|---------|-------------|
| `AiSettings` | `enabled` | `false` | Master switch |
| `LlmSettings` | `provider` | `OpenAi` | `OpenAi`, `Anthropic`, `Google`, `Ollama`, `OpenAiCompatible` |
| | `apiKey` | `""` | Provider API token (not needed for Ollama) |
| | `model` | `""` | Model id; blank = provider default |
| | `baseUrl` | `""` | Endpoint; blank = provider default |
| `SearchSettings` | `provider` | `None` | `None`, `Bing`, `Google` |
| | `apiKey` | `""` | Search API key |
| | `cx` | `""` | Google Programmable Search engine id |
| `AgentConfig` | `maxIterations` | `30` | Max agent tool-call iterations |
| | `temperature` | `0.2` | LLM sampling temperature |
| `FetcherConfig` | `maxContentBytes` | `2 MB` | Max content per fetch |
| | `requestTimeoutMs` | `15000` | HTTP timeout |
| | `maxFetchesPerRequest` | `20` | Fetch budget per discovery |
| `DiscoveryConfig` | `maxConcurrentRequests` | `3` | Global concurrent cap |
| | `userAgent` | `"KetchBot/1.0"` | User-Agent header |
| | `allowedDomains` | `[]` | Domain allowlist (empty = all public) |

## Testing

```bash
./gradlew :ai:discover:test
```

Tests cover:
- `LlmClientFactoryTest` — provider/model resolution, endpoint normalization
- `AiSettingsEnvTest` — environment credential fallbacks
- `UrlValidatorTest` — SSRF protection (20 tests)
- `RobotsTxtParserTest` — robots.txt parsing (13 tests)
- `ContentExtractorTest` — HTML extraction (7 tests)
- `LinkExtractorTest` — download link extraction (7 tests)
- `DeviceSafetyFilterTest` — URL safety scoring (10 tests)
- `AgentOutputParserTest` — agent output parsing + validation (9 tests)

## Roadmap

- [ ] **Real search provider** — integrate a web search API (Google Custom Search,
  Brave Search, or SearXNG) to replace `DummySearchProvider`
- [ ] **Streaming step events** — expose `DiscoveryStepListener` callbacks as
  SSE events for real-time UI updates during discovery
- [ ] **Download integration** — after discovery, allow one-click download of
  selected candidates via `KetchApi.download()`
- [ ] **Caching** — cache fetched page content and HEAD results to avoid
  redundant requests across similar queries
- [ ] **Site-aware discovery** — leverage `SiteProfiler` data (sitemaps, RSS feeds)
  to improve discovery on allowlisted sites
- [ ] **Checksum verification** — when the agent finds checksums on the source page,
  attach them to candidates for post-download verification
- [ ] **Agent memory** — persist discovery history so the agent can learn from
  previous queries and avoid re-fetching known sources
