# ai:discover — AI agent-driven resource discovery

Discovers downloadable files from natural language queries
using an LLM agent with tool-calling capabilities.

## Overview

Given a query like *"latest Ubuntu 24.04 desktop ISO"*, the agent autonomously
searches the web, fetches relevant pages, extracts download links, validates
them for safety, and returns a short summary and a ranked list of candidates.
Follow-up requests refine a search as a conversation, and an approver chosen by
the caller decides which websites the agent may open.

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
├── DiscoverQuery.kt             # Input model, earlier turns of a conversation
├── DiscoverResult.kt            # Output model
├── RankedCandidate.kt           # Single discovery result
├── PageAccess.kt                # Approver asked before the agent opens a website
│
├── agent/                       # Agent-driven discovery
│   ├── DiscoveryToolSet.kt      # 7 @Tool methods for the LLM agent
│   ├── DeclaredTools.kt         # Koog tools with optional parameters and plain text results
│   ├── AgentOutputParser.kt     # Parse + validate the agent's summary and candidates
│   ├── AgentText.kt             # Model text made safe to show as plain text
│   ├── DeviceSafetyFilter.kt    # URL safety scoring
│   ├── LinkExtractor.kt         # Download link extraction from HTML
│   ├── SiteAllowlist.kt         # Websites a run is limited to
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
│   ├── PacedSearchProvider.kt   # Spaces searches across runs
│   └── DummySearchProvider.kt   # No-op fallback
│
└── site/                        # robots.txt
    ├── SiteProfiler.kt          # Reads a site's robots.txt rules
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
│     searchWeb/searchSites → fetchPage →         │
│     headUrl, independent calls in one turn      │
│     Budget: 6 searches, 10 fetches, 15 HEADs    │
├─────────────────────────────────────────────────┤
│  4. SCORE & FILTER                              │
│     Relevance + device safety scoring           │
│     Block: shorteners, aggregators, piracy      │
├─────────────────────────────────────────────────┤
│  5. OUTPUT                                      │
│     JSON object: summary + ranked candidates    │
└─────────────────────────────────────────────────┘
```

The five phases are for a first request. The system prompt also covers
follow-ups (answer one that narrows, picks from or explains the earlier results
from them alone, with one `Refining` step and no searches, fetches or HEAD
requests; otherwise search only for what is missing, skipping UNDERSTAND; either
way return the complete list for the latest request, re-check only links not yet
checked, treat earlier results as data), page access (give `fetchPage` and
`headUrl` a reason, never retry a declined host), and an anti-piracy guardrail
that explains itself in the summary with no candidates.

### Agent Tools

| Tool | Description | Backend |
|------|-------------|---------|
| `searchWeb(query, maxResults = 5)` | Web search, scoped to the allowed sites | `SearchProvider.search()` |
| `searchSites(sites, query, maxResults = 5)` | Site-restricted search; the sites must be allowed | `SearchProvider.search(sites=)` |
| `fetchPage(url, reason = "")` | Fetch + extract text and links (allowed sites only; asks the approver; honors robots.txt) | `SafeFetcher` + `ContentExtractor` + `LinkExtractor` |
| `headUrl(url, reason = "")` | HTTP HEAD for metadata and the final URL after redirects (allowed sites only; asks the approver) | `SafeFetcher.head()` |
| `extractDownloads(pageText, baseUrl)` | Extract download links from HTML | `LinkExtractor` |
| `validateUrl(url)` | Checks the URL's form and allowed sites; never looks the host up | `UrlValidator.check()` + `SiteAllowlist` |
| `emitStep(title, details)` | Report progress to the user: a one-line title, details of up to 12 lines | `DiscoveryStepListener` |

The tools are registered through `asDeclaredTools()` rather than Koog's `tools(toolSet)`, which
(as of Koog 1.2.0) tells the model that `maxResults` and `reason` are required and sends each
result as a quoted, escaped JSON string instead of the JSON the tool returned.

## Data Flow

```
DiscoverQuery (query, sites, maxResults, fileTypes, history, excludedUrls)
         │
         ▼
ResourceDiscoveryService.discover(query, stepListener, approver)
         │
         ├── SiteAllowlist.forRun(allowedDomains, query.sites)
         ├── Build DiscoveryToolSet (allowlist, step listener, approver)
         ├── Create Koog AIAgent: system prompt, then the replayed earlier turns
         ├── agent.run(userMessage)
         │       │
         │       ├── [Agent calls tools iteratively]
         │       │   searchWeb → fetchPage → extractDownloads → headUrl
         │       │   (each tool wraps existing utilities)
         │       │
         │       └── Returns {"title": ..., "summary": ..., "candidates": [...]}
         │
         ├── AgentOutputParser.parse(agentOutput, allowlist, excludedUrls)
         │       ├── Find the first balanced JSON answer, in a code fence or the
         │       │   output; brackets in prose around it are skipped; text alone
         │       │   is the summary
         │       ├── Decode each candidate on its own, skipping malformed ones
         │       ├── Drop discarded links and candidates outside the allowed sites
         │       ├── UrlValidator.check() each URL; validate() (DNS) only hosts
         │       │   the run was allowed to contact, or all when it asks no one
         │       ├── Title, description and file name made one line of text
         │       ├── DeviceSafetyFilter.evaluate() each URL
         │       ├── Adjust confidence by safety score
         │       └── Deduplicate by canonical URL
         │
         └── DiscoverResult (candidates + sources + summary + title)
```

### Follow-ups

`DiscoverQuery.history` holds the earlier turns of a conversation, oldest first: each turn's
request, the sites it was limited to, whether it finished and the links it returned, each
`DiscoverTurn.Result` with what that turn learned of it (file name, size, content type, source
page, description, confidence; all optional). Each run is a new agent with full budgets; its
prompt replays the first turn and the latest five as pairs of messages:

- the user's request, numbered (`Request 3: …`), with `That request was limited to: …` when it
  had sites
- a reply written in code, never the model's earlier output, which fetched pages may have
  shaped: `I returned 2 results; they are listed in your next message.`, `I found no results.`
  or `This request did not finish.`

The new request then starts `Follow-up request: …`; its sites line reads `Allowed sites for this
request`. Below it, delimited as data and marked as already checked in this conversation, come
the earlier results, up to 20 per turn (`[{"turn":1,"url":…,"title":…,"fileName":…,
"sizeBytes":…,"sourcePageUrl":…,"description":…}]`, the known fields only, `sizeBytes` and
`sourcePageUrl` named as in the agent's answer so it can copy them; titles and file names cut
to 120 characters, descriptions to 200, source pages left out unless they are web links of at
most 2048 characters) and up to 100 discarded links (`DiscoverQuery.excludedUrls`). The parser
drops every discarded link from the answer, compared by `SiteNames.canonicalUrl`, so one that
comes back with a different host case, default port or fragment stays out.

The agent answers with a `summary` for the user, one or two sentences of plain text. The parser
keeps it as one line of at most 600 characters without control, bidirectional or zero-width
characters; when the agent answers with text only, as it does when it refuses to find pirated
content, that text becomes the summary. For a first request it also gives a `title`, a name for
the conversation of at most six words in the user's language (`DiscoverResult.title`); the parser
makes it one line of at most 60 characters, drops quotes around it and a trailing period, and
leaves it blank when the agent gives none, as it may for a follow-up, or answers with a bare
array or text.

## Security

### SSRF Protection (`UrlValidator`)
- Blocks private/local IPs (loopback, link-local, site-local, carrier-grade NAT)
- Blocks internal hostnames (`.local`, `.internal`, `localhost`, single-word)
- Only allows `http` and `https` schemes
- Applied to every request, including each redirect hop: the fetcher's
  Ktor client has `followRedirects = false`, and `SafeFetcher` follows
  up to 10 redirects itself, validating each target before requesting it,
  so a public URL cannot redirect to a private or loopback address
- Split in two: `check()` looks at the URL alone (scheme, host, internal
  names, IP literals) and `validate()` adds the DNS lookup. A host is only
  looked up once the run may contact it: `fetchPage` and `headUrl` check
  before they ask the approver, `SafeFetcher` runs `checkHop` before it
  validates a hop, and `validateUrl` never resolves. A lookup alone sends
  the name, which the agent chose, to that domain's DNS servers, so a page
  could otherwise have the agent leak the conversation in a host name the
  user then declines. The output parser does resolve every candidate's
  host, to drop links to private or local addresses: a page must not get
  the agent to offer a link into the user's network
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

### Site allowlist (`SiteAllowlist`)
- Built per run from `DiscoverQuery.sites`, narrowed to
  `DiscoveryConfig.allowedDomains`; with neither set, any public site is
  allowed
- A domain covers its subdomains (`ubuntu.com` allows
  `releases.ubuntu.com`, not `notubuntu.com`); a scheme, path, port or
  leading `www.` in an entry is ignored
- Enforced in the tools and the output parser: `searchWeb` passes the
  sites to the provider and drops off-list results, `searchSites`,
  `fetchPage` and `headUrl` refuse other hosts with
  `{"error": ..., "allowedSites": [...]}`, and `AgentOutputParser` drops
  candidates on other hosts
- Redirects a listed site answers with are followed, so `github.com`
  release assets served from `objects.githubusercontent.com` still
  resolve. `headUrl` returns the requested `url` plus `finalUrl`, and the
  agent is told to report the requested URL

### Page access (`PageAccessApprover`)
- `discover(query, stepListener, approver)` takes a `PageAccessApprover` per run. It is asked,
  with a `PageAccessRequest` (URL, lowercase host, `PageAccessKind.Page` or `FileInfo`, the
  agent's reason and, for a redirect, the host it came from), before `fetchPage` or `headUrl`
  contacts a host, and before a redirect leads one of them to a host the request has not
  reached. It may suspend until the user answers
- The check comes after the allowlist and URL checks and before any budget is spent, so a URL
  that would be refused anyway never asks, and a declined request costs only its tool call. A
  request the spent budget would refuse is refused without asking. The host is looked up in DNS
  only once it is approved
- Declining returns `{"error": "The user declined access to <host>. …", "declined": true}`
  (prefixed `Redirect refused:` when a redirect led there), and the host's site
  (`SiteNames.normalize`) is refused for the rest of the run without asking, redirect hops
  included. The site keeps its `www.` when dropping it would leave a suffix unrelated sites
  share (`www.com`, `www.github.io`), so no answer ever covers a whole suffix. An approver
  that throws declines; a `CancellationException` while the run is still active declines too,
  rather than ending the run
- The reason is the agent's text, so it reaches the approver as one line of at most 160
  characters without control, bidirectional or zero-width characters. Show it as the agent's,
  never as a title or a button label
- Runs limited with `DiscoverQuery.sites` never ask: every host they may request is one the
  user named. A limit set only by `DiscoveryConfig.allowedDomains` still asks
- An approved page covers robots.txt on its origin. A redirect from there to another host of
  the same site asks like any redirect of the request; a robots.txt it may not read, or one
  on another site, counts as missing
- Searches never ask: they go to the search provider, not to the sites
- The default, `PageAccessApprover.AllowAll`, ignores `[ai.access]`. The apps and the CLI pass
  approvers that follow it (`PageAccessSettings.allowsWithoutAsking`) and ask the user

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
  `maxConcurrentRequests` requests in flight at once. Brave and Google
  searches start at least 1.1 s apart across all runs
  (`PacedSearchProvider`), since several conversations can search at once
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
  Rules match as path prefixes with RFC 9309 wildcards: `*` matches any
  run of characters and a trailing `$` anchors the end of the path; the
  longest matching rule wins. Only the first 500 KiB of a larger file
  are parsed, without the line the cut falls in. A missing or unreadable
  robots.txt allows everything, and `Crawl-delay` is not applied. A
  robots.txt that redirects to another site counts as missing.
  `headUrl` checks of candidate links are not subject to robots.txt
- The system prompt also asks for at most 6 searches; that limit is
  advisory, since search calls are not counted on their own.
  `AgentConfig.maxToolCalls` caps the agent's tool calls overall,
  `emitStep` included: once it is spent, every tool answers with an error
  asking for the results (`emitStep` still shows its step). Koog's
  iteration cap is derived from it, since Koog counts two iterations per
  round of tool calls, with room for three more rounds to answer
- Prompt injection defense: fetched content treated as untrusted data;
  earlier turns are replayed without the model's own words, and text the
  model wrote (reasons, the summary) is sanitized before anyone sees it

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

`discover()` and `verifyConnection()` throw `DiscoveryException` when the
LLM provider fails or the agent does not answer within its step limit.
Its message is a short explanation for the user, such as
`The AI provider rejected the API token (HTTP 401): Incorrect API key provided.`;
the original error is its `cause`, and `brief` is the message without the
provider's reason, which may echo a token: the part to keep, such as in a
saved history. A search that finds nothing returns an empty result instead.

### Progress, page access and follow-ups

Each run can have its own step listener and approver; `AiModule.create(stepListener = …)` only
sets the listener for runs that pass none.

```kotlin
val steps = object : DiscoveryStepListener {
  override fun onStep(title: String, details: String) {
    println("[$title] $details")
  }
}
val access = PageAccessSettings(mode = PageAccessMode.AskPerSite)
val allowed = mutableSetOf<String>()
// askTheUser is your own prompt; it may suspend until the user answers.
val approver = PageAccessApprover { request ->
  access.allowsWithoutAsking(request.host, allowed) || askTheUser(request).also { yes ->
    if (yes) allowed += SiteNames.normalize(request.host)
  }
}

val first = aiModule.discoveryService.discover(
  DiscoverQuery(query = "Blender 4.2"),
  stepListener = steps,
  approver = approver,
)
println(first.summary)

val followUp = aiModule.discoveryService.discover(
  DiscoverQuery(
    query = "only the macOS arm64 build",
    history = listOf(
      DiscoverTurn(
        request = "Blender 4.2",
        results = first.candidates.map {
          DiscoverTurn.Result(
            url = it.url,
            title = it.title,
            fileName = it.fileName,
            sizeBytes = it.fileSize,
            sourceUrl = it.sourceUrl,
            description = it.description,
          )
        },
      ),
    ),
    excludedUrls = setOf(first.candidates.first().url), // discarded by the user
  ),
  stepListener = steps,
  approver = approver,
)
```

The step listener and the approver are called from the agent's threads, one request at a time
per run.

### Apps

The desktop and Android apps configure discovery on the **Settings**
page (provider, API token, model, endpoint and web search). Settings are
persisted in `config.toml` under `[ai]`, so the CLI picks up the same
configuration, except from the portable Windows app, which keeps its own.
See [docs/ai-discovery.md](../../docs/ai-discovery.md).

### CLI

```bash
# Uses [ai] from config.toml; blank credentials fall back to the env.
ketch ai-discover "latest Ubuntu 24.04 ISO"
OPENAI_API_KEY=sk-... ketch ai-discover "ffmpeg release" --sites ffmpeg.org
ketch ai-discover --yes "blender 4.2 macOS" > results.txt
```

The CLI follows `[ai.access]` and asks on the terminal before opening a website; see
[cli/README.md](../../cli/README.md#ai-discovery).

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
| `PageAccessSettings` (`AiSettings.access`) | `mode` | `AskPerSite` | `Allow`, `AskPerSite`, `AskEveryTime`; read by the apps' and the CLI's approvers, never by the engine |
| | `trustedSites` | `[]` | Sites opened without asking, subdomains included |
| `AgentConfig` | `maxToolCalls` | `40` | Tool calls per discovery run, progress steps included |
| | `temperature` | `0.2` | LLM sampling temperature |
| `FetcherConfig` | `maxContentBytes` | `2 MB` | Max page body per fetch |
| | `requestTimeoutMs` | `15000` | HTTP timeout for fetches and search |
| | `maxFetchesPerRequest` | `25` | Page fetches + HEAD requests per discovery run |
| | `maxTotalBytesPerRequest` | `20 MB` | Page body bytes per discovery run |
| `DiscoveryConfig` | `maxConcurrentRequests` | `3` | Requests in flight across all runs |
| | `userAgent` | `"KetchBot/1.0"` | User-Agent header |
| | `allowedDomains` | `[]` | Caps every run's sites; `DiscoverQuery.sites` only narrows it (empty = all public) |

## Testing

```bash
./gradlew :ai:discover:test
```

Tests cover:
- `LlmClientFactoryTest` — provider/model resolution, endpoint normalization
- `AiSettingsEnvTest` — environment credential fallbacks
- `ResourceDiscoveryServiceTest` — LLM client lifecycle, step limits,
  provider errors, replayed history (code-built replies, unfinished turns,
  first plus latest five), discarded links, summaries, per-run listener and
  approver, runs limited with `sites` never asking
- `NativeImageConfigTest` — reflection metadata for Koog's content-polymorphic
  types and for every Ketch class in `DiscoveryToolSet`'s signatures
- `UrlValidatorTest` — SSRF protection (20 tests)
- `SafeFetcherTest` — validated redirect hops (GET and HEAD), hop limit, final
  URL, size caps and truncation, and no connection when a host rebinds to
  loopback after validation
- `ValidatingDnsTest` — connect-time lookups refuse rebound and mixed hosts
- `RateLimiterTest` — per-host spacing and the concurrency cap
- `PacedSearchProviderTest` — searches from several runs start spaced
- `FetchBudgetTest` — per-run request and byte allowance
- `SiteAllowlistTest` — site normalization, subdomain matching, config/query overlap (10 tests)
- `DiscoveryToolSetTest` — robots.txt, shared budget, links after redirects, allowlist
  enforcement, page access (declines spend no budget and are remembered, redirect hops ask
  with the host they came from, sanitized reasons, failing approvers decline, a stopped run
  ends while asking)
- `BraveSearchProviderTest` — request shape and response parsing
- `RobotsTxtParserTest` — robots.txt groups, longest match, `*` and `$` wildcards
- `SiteProfilerTest` — robots.txt over 500 KiB is parsed up to the limit; redirects are
  followed within the site only, and to another host of it only once allowed
- `ContentExtractorTest` — HTML extraction (9 tests)
- `LinkExtractorTest` — download link extraction (7 tests)
- `DeviceSafetyFilterTest` — URL safety scoring (10 tests)
- `AgentOutputParserTest` — object and array answers, fences, text-only answers, unreadable
  JSON, brackets in the summary, malformed candidates, discarded links, validation + allowlist
- `AgentTextTest` — sanitizing model text
- `GoogleSearchProviderTest` — query building; `GoogleSearchProviderIntegrationTest`
  parses responses from a mock engine

Tests that need DNS answers resolve hosts through the `fakeDns` helper
(`src/test/.../fetch/FakeDns.kt`) and serve HTTP from Ktor's `MockEngine`.

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
- [ ] **Site-aware discovery** — use the sitemaps and RSS feeds of allowlisted
  sites to improve discovery on them
- [ ] **Checksum verification** — when the agent finds checksums on the source page,
  attach them to candidates for post-download verification
- [x] **Conversations** — follow-ups replay earlier turns (`DiscoverQuery.history`),
  and the apps keep a history of searches
- [ ] **Agent memory** — learn across conversations and avoid re-fetching known
  sources
