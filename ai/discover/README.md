# ai:discover

`ai:discover` is the engine behind Ketch's Discover feature: an LLM agent that turns a request
such as *"latest Ubuntu 24.04 desktop ISO"* into a short summary and a ranked list of download
links. This readme is for developers who embed or change the engine. For setting Discover up and
using it in the apps and the CLI, see the user guide, [AI discovery](../../docs/ai-discovery.md).

Given a request, the agent searches the web, reads relevant pages, extracts download links, checks
them for safety and returns its answer. Follow-up requests refine a search as a conversation, and
an approver chosen by the caller decides which websites the agent may open.

The module is a Kotlin/JVM library built on [Koog](https://github.com/JetBrains/koog) 1.3.0. It
depends on `library:api` and `config` (for the persisted `AiSettings`) and not on the server or
remote modules. The desktop and Android apps and the CLI use it; iOS and the web app have no
discovery.

## Quick start

```kotlin
val aiModule = AiModule.create(
  config = AiConfig(
    settings = AiSettings(
      llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant-..."),
      search = SearchSettings(provider = SearchProvider.Brave, apiKey = "..."),
    ),
  ),
)

try {
  val result = aiModule.discoveryService.discover(
    DiscoverQuery(
      query = "latest Ubuntu 24.04 desktop ISO",
      sites = listOf("ubuntu.com"),
      maxResults = 5,
      fileTypes = listOf("iso"),
    ),
  )
  println(result.summary)
  for (candidate in result.candidates) {
    println("${candidate.title}: ${candidate.url}")
  }
} finally {
  aiModule.close() // releases the module's HTTP clients
}
```

To fill blank keys from the environment the way the CLI does (`OPENAI_API_KEY`,
`BRAVE_SEARCH_API_KEY` and so on), pass the settings through `resolveAiSettingsFromEnv(settings)`
first. The apps call it with `autoConfigure = false`, so the environment only fills keys of
providers the user saved; see [environment variables](../../docs/ai-discovery.md#environment-variables).

## API

| Type | What it is |
|------|------------|
| `AiModule` | The entry point. `AiModule.create(config, searchProvider, stepListener)` wires the fetcher, search provider and service; `close()` releases the HTTP clients it made (their thread pools would otherwise leak, and the fetcher's keeps the JVM alive for up to a minute). |
| `AiConfig` | `AiSettings` (what the user configures) plus engine tuning: `FetcherConfig`, `DiscoveryConfig`, `AgentConfig`. See [configuration](#configuration). |
| `ResourceDiscoveryService` | `discover(query, stepListener, approver)` runs one search; `verifyConnection()` sends a one-line prompt to check the provider, key and model. |
| `DiscoverQuery` | The request: `query`, `sites`, `maxResults` (10), `fileTypes`, `history`, `excludedUrls`, `contentFilter` (on), `userDevice`, `downloadDevice`. |
| `DiscoverTurn` | An earlier turn of a conversation: `request`, `sites`, `completed`, and its `results` (`DiscoverTurn.Result`). |
| `DiscoverDevice` | A device's `os` and `arch`, as a hint for builds per platform. |
| `DiscoverResult` | `candidates` (`RankedCandidate`), `sources`, `summary`, `title` and `filtered`, the number of results the content filter hid. |
| `RankedCandidate` | `url`, `title`, `fileName`, `fileSize`, `mimeType`, `sourceUrl`, `confidence`, `description`. |
| `PageAccessApprover` | Asked with a `PageAccessRequest` before the agent opens a website; may suspend. `PageAccessApprover.AllowAll` is the default. |
| `DiscoveryStepListener` | Receives the agent's progress steps, `onStep(title, details)`. |
| `DiscoveryException` | Thrown when the provider fails or the agent does not answer within its limits. |
| `LlmModelLister` | `list(settings)` lists the models a provider offers, for a settings screen. |
| `resolveAiSettingsFromEnv` | Fills blank credentials from environment variables. |

### Errors

`discover()` and `verifyConnection()` throw `DiscoveryException` when the LLM provider fails or the
agent does not answer within its step limit. Its message is a short explanation for the user, such
as `The AI provider rejected the API token (HTTP 401): Incorrect API key provided.`. The original
error is its `cause`, and `brief` is the message without the provider's reason, which may echo a
key: keep `brief` when you save it, as the apps' history does.

`discover()` throws `IllegalArgumentException` for a blank query, for `sites` that name no domain,
or for `sites` all outside `DiscoveryConfig.allowedDomains`. A search that finds nothing returns an
empty result, and so does a service whose settings are switched off or incomplete.

`LlmModelLister.list` throws `IllegalArgumentException` when the settings lack an endpoint or a
required key, and `DiscoveryException` when the provider cannot be reached; its messages never
quote the provider's reply.

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

The step listener and the approver are called from the agent's threads, one request at a time per
run.

The default approver, `PageAccessApprover.AllowAll`, ignores `[ai.access]`. The apps and the CLI
pass approvers that follow it (`PageAccessSettings.allowsWithoutAsking`) and ask the user.

## How it works

### Source layout

```
ai/discover/
├── AiModule.kt                  # Public entry point + factory
├── AiConfig.kt                  # Configuration + env credential fallbacks
├── LlmClientFactory.kt          # Provider → Koog client and model
├── LlmModelLister.kt            # A provider's model list
├── ResourceDiscoveryService.kt  # Koog AIAgent orchestrator
├── DiscoverQuery.kt             # Input model, earlier turns of a conversation
├── DiscoverResult.kt            # Output model
├── DiscoveryException.kt        # Provider failures explained for users
├── RankedCandidate.kt           # Single discovery result
├── PageAccess.kt                # Approver asked before the agent opens a website
│
├── agent/                       # Agent-driven discovery
│   ├── DiscoveryToolSet.kt      # The 7 tools of the LLM agent
│   ├── TextTool.kt              # Hand-described Koog tools with plain text results
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

### Providers

Providers are data: each `LlmProvider` preset in `config` names the API it speaks (`LlmApi`: OpenAI
Responses, OpenAI chat completions, Anthropic, Gemini or Ollama), its endpoints, suggested models
and key variables. `LlmClientFactory` maps the API to a Koog client and resolves the model: ids in
Koog's catalog get its context limits, others become custom models, and `temperature` is only sent
to models that accept it. The user guide lists [the presets](../../docs/ai-discovery.md#providers).

The fetcher's HTTP client uses Ktor's OkHttp engine, since it accepts a custom DNS resolver
(`ValidatingDns`); the search and LLM clients use the app's default engine.

### Agent workflow

The agent is a Koog `AIAgent` whose system prompt sets out five phases for a first request:

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

The prompt also covers:

- **Follow-ups.** One that narrows, picks from or explains the earlier results is answered from
  them alone, with one `Refining` step and no searches, fetches or HEAD requests. Others search
  only for what is missing, skipping UNDERSTAND. Either way the agent returns the complete list
  for the latest request, re-checks only links not yet checked, and treats earlier results as
  data.
- **Page access.** `fetchPage` and `headUrl` take a reason, and a declined host is never retried.
- **Piracy.** With the content filter on, the agent refuses pirated content and explains why in
  the summary, with no candidates.

The agent runs Koog's single-run loop with one change (`discoveryStrategy`): a reply with neither
tool calls nor a JSON answer, such as a model narrating its next step, gets a reminder to call a
tool or answer, at most twice a run, instead of ending the run with no results.

### Agent tools

| Tool | Description | Backend |
|------|-------------|---------|
| `searchWeb(query, maxResults = 5)` | Web search, scoped to the allowed sites | `SearchProvider.search()` |
| `searchSites(sites, query, maxResults = 5)` | Site-restricted search; the sites must be allowed | `SearchProvider.search(sites=)` |
| `fetchPage(url, reason = "")` | Fetch + extract text and links (allowed sites only; asks the approver; honors robots.txt) | `SafeFetcher` + `ContentExtractor` + `LinkExtractor` |
| `headUrl(url, reason = "")` | HTTP HEAD for metadata and the final URL after redirects (allowed sites only; asks the approver) | `SafeFetcher.head()` |
| `extractDownloads(pageText, baseUrl)` | Extract download links from HTML | `LinkExtractor` |
| `validateUrl(url)` | Checks the URL's form and allowed sites; never looks the host up | `UrlValidator.check()` + `SiteAllowlist` |
| `emitStep(title, details)` | Report progress to the user: a one-line title, details of up to 12 lines | `DiscoveryStepListener` |

The tools are `TextTool`s, whose names, descriptions and parameters `DiscoveryToolSet.tools()`
writes out by hand, rather than Koog's reflective `ToolSet`. That one needs kotlin-reflect, which
the apps and the CLI leave out, tells the model that optional parameters such as `maxResults` and
`reason` are required, and sends each result as a quoted, escaped JSON string instead of the JSON
the tool returned.

### Data flow

```
DiscoverQuery (query, sites, maxResults, fileTypes, history, excludedUrls,
               contentFilter, userDevice, downloadDevice)
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
         └── DiscoverResult (candidates + sources + summary + title + filtered)
```

### Follow-ups

`DiscoverQuery.history` holds the earlier turns, oldest first: each turn's request, its sites,
whether it finished, and its results with what that turn learned of them (file name, size, content
type, source page, description, confidence; all optional). Each run is a new agent with full
budgets. Its prompt replays the first turn and the latest five as pairs of messages:

- the user's request, numbered (`Request 3: …`), with `That request was limited to: …` when it had
  sites;
- a reply written in code, never the model's earlier output, which fetched pages may have shaped:
  `I returned 2 results; they are listed in your next message.`, `I found no results.` or
  `This request did not finish.`

The new request then starts `Follow-up request: …`, with `Allowed sites for this request` when it
has sites. Below it, delimited as data and marked as already checked, come:

- the earlier results, up to 20 per turn, with only the known fields (`[{"turn":1,"url":…,
  "title":…,"fileName":…,"sizeBytes":…,"sourcePageUrl":…,"description":…}]`). `sizeBytes` and
  `sourcePageUrl` are named as in the agent's answer so it can copy them. Titles and file names
  are cut to 120 characters and descriptions to 200; source pages are left out unless they are web
  links of at most 2048 characters;
- up to 100 discarded links (`excludedUrls`). The parser drops every discarded link from the
  answer, compared by `SiteNames.canonicalUrl`, so one that comes back with a different host case,
  default port or fragment stays out.

### Summary and title

The agent answers with a `summary` for the user, one or two sentences of plain text. The parser
keeps it as one line of at most 600 characters without control, bidirectional or zero-width
characters. When the agent answers with text only, as it does when it refuses to find pirated
content, that text becomes the summary.

For a first request the agent also gives a `title`, a name for the conversation of at most six
words in the user's language (`DiscoverResult.title`). The parser makes it one line of at most 60
characters, drops surrounding quotes and a trailing period, and leaves it blank when the agent
gives none, as it may for a follow-up, or answers with a bare array or text.

### Devices

`DiscoverQuery.userDevice` is the device the user searches from and `downloadDevice` the one that
downloads the files, each a `DiscoverDevice` with the system and CPU it reports (`os.name` and
`os.arch` on the JVM, `KetchStatus.system` for an app's devices). Either may be `null`, and they may
be the same. The request names each known one on a line of its own, such as
`User's device: macOS aarch64` and `Downloading device: Linux amd64`, at most 60 characters per
part, since a remote device reports its own; the JVM's `Mac OS X` reads as `macOS`.

The system prompt calls them hints, often irrelevant: the agent uses them only when the files come
in builds per system or CPU and the request names neither, picks the downloading device's builds
for software meant to run there (it is often a server), includes builds for both when unsure, and
says in each description which system and CPU a build is for.

## Security

### SSRF protection (`UrlValidator`)

- Blocks private and local IPs (loopback, link-local, site-local, carrier-grade NAT), internal host
  names (`.local`, `.internal`, `localhost`, single-word) and schemes other than `http` and
  `https`.
- Applies to every request, including each redirect hop. The fetcher's client has
  `followRedirects = false`, and `SafeFetcher` follows up to 10 redirects itself, validating each
  target before requesting it, so a public URL cannot redirect to a private or loopback address.
- Has two steps. `check()` looks at the URL alone (scheme, host, internal names, IP literals) and
  `validate()` adds the DNS lookup. A host is only looked up once the run may contact it:
  `fetchPage` and `headUrl` check before they ask the approver, `SafeFetcher` runs `checkHop`
  before it validates a hop, and `validateUrl` never resolves. A lookup alone sends the name,
  which the agent chose, to that domain's DNS servers, so a page could otherwise have the agent
  leak the conversation in a host name the user then declines.
- Resolves every candidate's host in the output parser, to drop links to private or local
  addresses: a page must not get the agent to offer a link into the user's network.
- Applies again when connecting, against DNS rebinding. The HTTP client resolves the host again to
  connect, so a host could otherwise answer the check with a public address and the connection with
  `127.0.0.1`. `SafeFetcher.createHttpClient` builds the client on OkHttp with `ValidatingDns`,
  which resolves through `UrlValidator` and refuses the host if any address is blocked.
  IP-literal URLs skip DNS and are checked by validation alone.
- The fetcher never uses a proxy, including a JVM or system proxy: a proxy would resolve the host
  itself, out of reach of the check.

### Site allowlist (`SiteAllowlist`)

- Built per run from `DiscoverQuery.sites`, narrowed to `DiscoveryConfig.allowedDomains`; with
  neither set, any public site is allowed.
- A domain covers its subdomains (`ubuntu.com` allows `releases.ubuntu.com`, not `notubuntu.com`);
  a scheme, path, port or leading `www.` in an entry is ignored.
- Enforced in the tools and the output parser: `searchWeb` passes the sites to the provider and
  drops off-list results; `searchSites`, `fetchPage` and `headUrl` refuse other hosts with
  `{"error": ..., "allowedSites": [...]}`; `AgentOutputParser` drops candidates on other hosts.
- Redirects a listed site answers with are followed, so `github.com` release assets served from
  `objects.githubusercontent.com` still resolve. `headUrl` returns the requested `url` plus
  `finalUrl`, and the agent is told to report the requested URL.

### Page access (`PageAccessApprover`)

- The approver is asked with a `PageAccessRequest` (URL, lowercase host, `PageAccessKind.Page` or
  `FileInfo`, the agent's reason and, for a redirect, the host it came from) before `fetchPage` or
  `headUrl` contacts a host, and before a redirect leads one of them to a host the request has not
  reached. It may suspend until the user answers.
- The check comes after the allowlist and URL checks and before any budget is spent, so a URL that
  would be refused anyway never asks, and a declined request costs only its tool call. A request
  the spent budget would refuse is refused without asking. The host is looked up in DNS only once
  approved.
- Declining returns `{"error": "The user declined access to <host>. …", "declined": true}`
  (prefixed `Redirect refused:` for a redirect), and the host's site (`SiteNames.normalize`) is
  refused for the rest of the run without asking, redirect hops included. The site keeps its
  `www.` when dropping it would leave a suffix unrelated sites share (`www.com`, `www.github.io`),
  so no answer covers a whole suffix. An approver that throws declines; so does a
  `CancellationException` while the run is still active, rather than ending the run.
- The reason is the agent's text, so it reaches the approver as one line of at most 160 characters
  without control, bidirectional or zero-width characters. Show it as the agent's, never as a
  title or a button label.
- Runs limited with `DiscoverQuery.sites` never ask: every host they may request is one the user
  named. A limit set only by `DiscoveryConfig.allowedDomains` still asks.
- An approved page covers robots.txt on its origin. A redirect from there to another host of the
  same site asks like any redirect; a robots.txt it may not read, or one on another site, counts as
  missing.
- Searches never ask: they go to the search provider, not to the sites.

### Device safety (`DeviceSafetyFilter`)

- Base score 0.7, adjusted by heuristics: HTTPS +0.1, HTTP −0.2, trusted domain (github.com,
  gitlab.com and the like) +0.2, checksums mentioned +0.15, content-type mismatch −0.3.
- Hard-blocked: URL shorteners, aggregator sites, piracy signals, look-alikes of trusted hosts, URLs
  with user info, and installers from untrusted hosts without HTTPS.
- Blocked if the final score is below 0.3.
- With `DiscoverQuery.contentFilter = false`, nothing is blocked and the agent's own confidence is
  kept; the count of hidden results is `DiscoverResult.filtered`.

### Other protections

- **Rate limiting** (`RateLimiter`, shared by all runs of an `AiModule`): at least 1 s between
  requests to the same host, and at most `maxConcurrentRequests` in flight. Brave and Google
  searches start at least 1.1 s apart across all runs (`PacedSearchProvider`); a custom
  `SearchProvider` passed to `AiModule.create` is used as it is.
- **Per-run budget** (`FetchBudget`): `fetchPage` and `headUrl` share `maxFetchesPerRequest`
  requests, and page bodies share `maxTotalBytesPerRequest` bytes; once either is spent the tools
  tell the agent to stop fetching.
- **Size cap**: 2 MB per fetch. Bodies are streamed, so the cap holds without a `Content-Length`.
- **robots.txt**: `fetchPage` refuses paths the site disallows for the `KetchBot` token (the
  `User-Agent` up to its `/`), checking every redirect hop against its own origin's rules. A group
  naming `KetchBot` replaces the `*` groups (RFC 9309). Rules match as path prefixes with `*` and a
  trailing `$`; the longest match wins. Each origin's robots.txt is read once per run, outside the
  budget, and only its first 500 KiB are parsed. A missing or unreadable robots.txt allows
  everything, and `Crawl-delay` is not applied. `headUrl` checks of candidate links are not subject
  to robots.txt.
- **Tool-call cap**: `AgentConfig.maxToolCalls` caps the agent's tool calls, `emitStep` included;
  once spent, every tool answers with an error asking for the results. Koog's iteration cap is
  derived from it, with room for three more rounds to answer. The prompt's limit of 6 searches is
  advisory.
- **Prompt injection**: fetched content is treated as untrusted data, earlier turns are replayed
  without the model's own words, and text the model wrote (reasons, the summary, titles) is
  sanitized before anyone sees it.

## Configuration

User-facing settings live in the `config` module (`AiSettings`), so the apps, the CLI and this
module share one representation; the user guide documents them as
[`config.toml` keys](../../docs/ai-discovery.md#configtoml). The other sections of `AiConfig` are
engine tuning knobs that no UI exposes.

| Config | Field | Default | Description |
|--------|-------|---------|-------------|
| `AiSettings` | `enabled` | `true` | Master switch; discovery runs once the active provider and search are complete |
| | `providers`, `active` | none, `""` | Saved `LlmSettings` and the id of the one in use; with none saved, a default OpenAI entry |
| | `contentFilter` | `true` | Read by the apps and the CLI, which pass it as `DiscoverQuery.contentFilter` |
| `LlmSettings` | `provider` | `OpenAi` | Any `LlmProvider` preset, or `OpenAiCompatible` |
| | `apiKey` | `""` | Provider API key (not needed for Ollama or LM Studio) |
| | `model` | `""` | Model id; blank = provider default |
| | `baseUrl` | `""` | Endpoint; blank = provider default |
| `SearchSettings` | `provider` | `None` | `None`, `Brave`, `Google` |
| | `apiKey` | `""` | Brave subscription token or Google API key |
| | `cx` | `""` | Google Programmable Search engine id |
| `PageAccessSettings` (`AiSettings.access`) | `mode` | `AskPerSite` | `Allow`, `AskPerSite`, `AskEveryTime`; read by the apps' and the CLI's approvers, never by the engine |
| | `trustedSites` | `[]` | Sites opened without asking, subdomains included |
| `AgentConfig` | `maxToolCalls` | `40` | Tool calls per run, progress steps included |
| | `temperature` | `0.2` | LLM sampling temperature, for models that accept one |
| `FetcherConfig` | `maxContentBytes` | 2 MiB | Max page body per fetch |
| | `requestTimeoutMs` | `15000` | HTTP timeout for fetches and search |
| | `maxFetchesPerRequest` | `25` | Page fetches + HEAD requests per run |
| | `maxTotalBytesPerRequest` | 20 MiB | Page body bytes per run |
| `DiscoveryConfig` | `maxConcurrentRequests` | `3` | Requests in flight across all runs |
| | `userAgent` | `"KetchBot/1.0"` | User-Agent header; the part before `/` is the robots.txt token |
| | `allowedDomains` | `[]` | Caps every run's sites; `DiscoverQuery.sites` only narrows it (empty = all public) |

## Testing

```bash
./gradlew :ai:discover:test
```

Tests that need DNS answers resolve hosts through the `fakeDns` helper
(`src/test/.../fetch/FakeDns.kt`) and serve HTTP from Ktor's `MockEngine`.

| Test | Covers |
|------|--------|
| `LlmClientFactoryTest` | Provider and model resolution, endpoint normalization |
| `LlmModelListerTest` | Model lists of each provider API |
| `AiSettingsEnvTest` | Environment credential fallbacks |
| `DiscoveryExceptionTest` | User-facing provider errors and `brief` |
| `ResourceDiscoveryServiceTest` | LLM client lifecycle, step limits, provider errors, replayed history, discarded links, summaries, per-run listener and approver, runs limited with `sites` never asking |
| `NativeImageConfigTest` | Reflection metadata for Koog's content-polymorphic types |
| `TextToolTest` | Optional parameters, defaults and validation, results passed on as they are |
| `UrlValidatorTest` | SSRF protection |
| `SafeFetcherTest` | Validated redirect hops (GET and HEAD), hop limit, final URL, size caps, no connection when a host rebinds to loopback |
| `ValidatingDnsTest` | Connect-time lookups refuse rebound and mixed hosts |
| `RateLimiterTest`, `PacedSearchProviderTest` | Per-host spacing, the concurrency cap, searches spaced across runs |
| `FetchBudgetTest` | Per-run request and byte allowance |
| `SiteAllowlistTest` | Site normalization, subdomain matching, config and query overlap |
| `DiscoveryToolSetTest` | robots.txt, shared budget, links after redirects, allowlist enforcement, page access |
| `BraveSearchProviderTest`, `GoogleSearchProviderTest`, `GoogleSearchProviderIntegrationTest` | Request shape and response parsing |
| `RobotsTxtParserTest`, `SiteProfilerTest` | robots.txt groups, longest match, wildcards, the 500 KiB limit, redirects within a site |
| `ContentExtractorTest`, `LinkExtractorTest` | HTML text and download link extraction |
| `DeviceSafetyFilterTest` | URL safety scoring |
| `AgentOutputParserTest`, `AgentTextTest` | Answer parsing (objects, arrays, fences, text-only, malformed candidates) and sanitizing model text |

Changes that touch Koog's clients also need a native CLI check: build with
`./gradlew :cli:nativeCompile` and run `ketch ai-discover` with each LLM provider.

## Roadmap

- [x] **Real search provider**: `BraveSearchProvider` and `GoogleSearchProvider` replace
  `DummySearchProvider` once configured.
- [ ] **Keyless search provider**: Google's Custom Search JSON API is closed to new customers and
  shuts down on 2027-01-01, leaving Brave as the only option; a self-hosted SearXNG provider would
  work without an API key.
- [ ] **Streaming step events**: expose `DiscoveryStepListener` callbacks as SSE events for
  real-time UI updates.
- [x] **Download integration**: the apps download selected candidates via `KetchApi.download()`.
- [ ] **Caching**: cache fetched pages and HEAD results across similar queries.
- [ ] **Site-aware discovery**: use the sitemaps and RSS feeds of allowlisted sites.
- [ ] **Checksum verification**: attach checksums found on the source page to candidates, for
  verification after download.
- [x] **Conversations**: follow-ups replay earlier turns (`DiscoverQuery.history`), and the apps
  keep a history of searches.
- [ ] **Agent memory**: learn across conversations and avoid re-fetching known sources.

## See also

- [AI discovery user guide](../../docs/ai-discovery.md)
- [CLI guide: AI discovery](../../cli/README.md#ai-discovery)
