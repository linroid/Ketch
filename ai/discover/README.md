# ai:discover — AI agent-driven resource discovery

Discovers downloadable files from natural language queries
using an LLM agent with tool-calling capabilities.

## Overview

Given a query like *"latest Ubuntu 24.04 desktop ISO"*, the agent autonomously
searches the web, fetches relevant pages, extracts download links, validates
them for safety, and returns a ranked list of candidates.

The module depends on `library:api` and `config` (for the persisted
`AiSettings`) — it is independent from the server and remote modules.
It is a JVM module (Koog, Ktor CIO) used by the desktop and Android apps
and the CLI.

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
│   ├── SafeFetcher.kt           # SSRF-protected GET + HEAD, validated redirects
│   ├── UrlValidator.kt          # SSRF protection (blocks private IPs, non-HTTP)
│   ├── ValidatingDns.kt         # OkHttp DNS that only returns validated addresses
│   ├── ContentExtractor.kt      # HTML → text extraction
│   ├── RateLimiter.kt           # Per-host spacing + global concurrency cap
│   └── FetchBudget.kt           # Per-run request and byte allowance
│
├── search/                      # Web search abstraction
│   ├── SearchProvider.kt        # Interface
│   ├── BraveSearchProvider.kt   # Brave Search API
│   ├── GoogleSearchProvider.kt  # Google Custom Search JSON API
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
| `fetchPage(url)` | Fetch + extract text and links (honors robots.txt) | `SafeFetcher` + `ContentExtractor` + `LinkExtractor` |
| `headUrl(url)` | HTTP HEAD for metadata and the final URL after redirects | `SafeFetcher.head()` |
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
- Applied to every request, including each redirect hop: the fetcher's
  Ktor client has `followRedirects = false`, and `SafeFetcher` follows
  up to 10 redirects itself, validating each target before requesting it,
  so a public URL cannot redirect to a private or loopback address
- Applied again when connecting, against DNS rebinding. Validation
  resolves the host and the HTTP client resolves it again to connect, so
  a host could otherwise answer the check with a public address and the
  connection with `127.0.0.1` or `10.x`. `SafeFetcher.createHttpClient`
  builds the fetcher's client on Ktor's OkHttp engine, since CIO takes no
  custom resolver, with `ValidatingDns`: it resolves through
  `UrlValidator` and refuses the host if any address is blocked, so the
  client only connects to addresses that passed the check. IP-literal
  URLs skip DNS and are checked by validation alone
- The fetcher's client never uses a proxy, including a JVM or system
  proxy: a proxy would resolve the host itself, out of reach of the check

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
- Rate limiting (`RateLimiter`, shared by all runs of an `AiModule`):
  at least 1 s between requests to the same host, and at most
  `maxConcurrentRequests` requests in flight at once
- Per-run budget (`FetchBudget`): `fetchPage` and `headUrl` share
  `maxFetchesPerRequest` requests, and page bodies share
  `maxTotalBytesPerRequest` bytes; once either is spent the tools return
  an error telling the agent to stop fetching
- Content size cap: 2 MB per fetch. Bodies are streamed, so the cap
  holds for responses without a `Content-Length`
- robots.txt: `fetchPage` refuses paths the site disallows for the
  `KetchBot` token (the `User-Agent` up to its `/`), checking every
  redirect hop against its own origin's rules. A group naming `KetchBot`
  replaces the `*` groups, as RFC 9309 specifies. Each origin's
  robots.txt is read once per run and does not count toward the budget.
  A missing or unreadable robots.txt allows everything; `*` and `$`
  wildcards in rules are not supported, and `Crawl-delay` is not applied.
  `headUrl` checks of candidate links are not subject to robots.txt
- The system prompt also asks for at most 6 searches; that limit is
  advisory, since search calls are not counted. `AgentConfig.maxIterations`
  caps the agent's tool calls overall
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

aiModule.close() // releases the module's HTTP clients
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
| `SearchSettings` | `provider` | `None` | `None`, `Brave`, `Google` |
| | `apiKey` | `""` | Brave subscription token or Google API key |
| | `cx` | `""` | Google Programmable Search engine id |
| `AgentConfig` | `maxIterations` | `30` | Max agent tool-call iterations |
| | `temperature` | `0.2` | LLM sampling temperature |
| `FetcherConfig` | `maxContentBytes` | `2 MB` | Max page body per fetch |
| | `requestTimeoutMs` | `15000` | HTTP timeout for fetches and search |
| | `maxFetchesPerRequest` | `25` | Page fetches + HEAD requests per discovery run |
| | `maxTotalBytesPerRequest` | `20 MB` | Page body bytes per discovery run |
| `DiscoveryConfig` | `maxConcurrentRequests` | `3` | Requests in flight across all runs |
| | `userAgent` | `"KetchBot/1.0"` | User-Agent header |
| | `allowedDomains` | `[]` | Allowlist for `validateUrl`, plus `DiscoverQuery.sites` (empty = all public) |

## Testing

```bash
./gradlew :ai:discover:test
```

Tests cover:
- `LlmClientFactoryTest` — provider/model resolution, endpoint normalization
- `AiSettingsEnvTest` — environment credential fallbacks
- `UrlValidatorTest` — SSRF protection (20 tests)
- `SafeFetcherTest` — validated redirect hops, hop limit, final URL, size caps,
  and no connection when a host rebinds to loopback after validation
- `ValidatingDnsTest` — connect-time lookups refuse rebound and mixed hosts
- `RateLimiterTest` — per-host spacing and the concurrency cap
- `FetchBudgetTest` — per-run request and byte allowance
- `DiscoveryToolSetTest` — robots.txt, shared budget, links after redirects
- `BraveSearchProviderTest` — request shape and response parsing
- `RobotsTxtParserTest` — robots.txt parsing (11 tests)
- `ContentExtractorTest` — HTML extraction (9 tests)
- `LinkExtractorTest` — download link extraction (7 tests)
- `DeviceSafetyFilterTest` — URL safety scoring (10 tests)
- `AgentOutputParserTest` — agent output parsing + validation (9 tests)
- `GoogleSearchProviderTest` — query building; `GoogleSearchProviderIntegrationTest`
  parses responses from a mock engine

## Roadmap

- [x] **Real search provider** — `BraveSearchProvider` and `GoogleSearchProvider`
  replace `DummySearchProvider` once configured
- [ ] **Keyless search provider** — Google's Custom Search JSON API is closed to
  new customers and shuts down on 2027-01-01, leaving Brave as the only option;
  a self-hosted SearXNG provider would work without an API key
- [ ] **Streaming step events** — expose `DiscoveryStepListener` callbacks as
  SSE events for real-time UI updates during discovery
- [x] **Download integration** — the apps download selected candidates via
  `KetchApi.download()`
- [ ] **Caching** — cache fetched page content and HEAD results to avoid
  redundant requests across similar queries
- [ ] **Site-aware discovery** — leverage `SiteProfiler` data (sitemaps, RSS feeds)
  to improve discovery on allowlisted sites
- [ ] **Checksum verification** — when the agent finds checksums on the source page,
  attach them to candidates for post-download verification
- [ ] **Agent memory** — persist discovery history so the agent can learn from
  previous queries and avoid re-fetching known sources
