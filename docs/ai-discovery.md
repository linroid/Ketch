# AI discovery configuration

AI discovery turns a plain-language request ("latest Ubuntu 24.04 desktop
ISO") into a ranked list of download links. It needs an LLM provider, and
works much better with a web search provider.

Configuration lives on the **Settings** page of the desktop and Android
apps, and is persisted in `config.toml` so the CLI uses the same values.

## Settings page

Open **Settings** (⌘, or Ctrl+, on desktop, the sidebar's Settings, or
the ⋮ menu on phones), then **Discover** under *This app*. The Discover
page's own setup links there too. It holds:

| Field | Notes |
|-------|-------|
| AI discovery | Master switch. Picking a provider on the Discover page's setup switches it on. |
| Provider | OpenAI, Anthropic, Google Gemini, Ollama, or any OpenAI-compatible endpoint. |
| API key | Required for everything except Ollama. |
| Model | Blank uses the provider default (see below). |
| Endpoint | Blank uses the provider default; required for OpenAI-compatible. |
| Web search | None, Brave, or Google Programmable Search, plus credentials. |

Changes are saved as you make them — there is no Save button — and the
discovery engine is rebuilt in place, without restarting the app. **Test**
sends a one-line prompt to the provider with the saved settings, so a
wrong key or model shows up immediately instead of on the first search.

The token is stored in plain text in the app's config file, the same way
the server's `apiToken` is. On a shared machine, prefer an environment
variable (below) over saving the token.

## Providers

Defaults and the suggestion chips under the model field track each
provider's current recommended model (checked September 2026):

| Provider | Default model | Other suggestions | Default endpoint | Token |
|----------|---------------|-------------------|------------------|-------|
| OpenAI | `gpt-5.6-terra` | `gpt-6-astra`, `gpt-5.6-sol`, `gpt-5.6-luna` | `https://api.openai.com` | required |
| Anthropic | `claude-opus-5` | `claude-sonnet-5`, `claude-haiku-4-5` | `https://api.anthropic.com` | required |
| Google Gemini | `gemini-3.8-flash` | `gemini-3.7-flash`, `gemini-3.5-flash-lite` | `https://generativelanguage.googleapis.com` | required |
| Ollama | `qwen3` | `llama3.1:8b`, `gemma4` | `http://localhost:11434` | not used |
| OpenAI-compatible | — (required) | — | — (required) | required |

The model field is free text: any id your provider accepts works, so a
model released after this table does too. Ids that Koog ships in its
catalog come with accurate context limits; anything else is treated as a
custom model. The model must support tool calling — the agent drives
discovery through tools, and a model without them returns nothing.

Two details Ketch handles for you:

- **Endpoint.** The newest OpenAI models are served by the Responses
  API, so the OpenAI provider targets it for ids outside Koog's catalog;
  the OpenAI-compatible provider targets `/v1/chat/completions`, which is
  what third-party servers implement.
- **Sampling.** Current frontier models (the Claude 5 family, OpenAI's
  newest) reject a `temperature`, so discovery only sends one to models
  that advertise support for it.

**OpenAI-compatible** covers OpenRouter, DeepSeek, LM Studio, vLLM and
similar servers, as well as Gemini's OpenAI-compatible endpoint. Enter
the endpoint with or without the trailing `/v1`; both work.

## Web search

Without a search provider the agent can only read pages whose address
it already knows, such as the home pages of the sites listed under
*Limit to websites* (below), so results are thin.

- **Brave** needs a Brave Search API subscription token, created at
  `api-dashboard.search.brave.com`.
- **Google** needs an API key plus a Programmable Search engine id
  (`cx`). Google has closed the Custom Search JSON API to new customers
  and turns it off on January 1, 2027, so only existing keys work.

Bing is no longer offered: Microsoft retired the Bing Search APIs on
August 11, 2025. A config file that still says `provider = "bing"`
loads with web search set to None; pick another provider in Settings.

## What the agent may fetch

Every page and HEAD request goes through the same safeguards:

- Only public `http` and `https` addresses are reached. Redirects are
  followed one hop at a time (at most 10), and each hop is checked, so a
  public page cannot redirect the agent to your router or `localhost`.
- A discovery run may make 25 page fetches and HEAD requests in total
  and read 20 MB of page content, with at most 2 MB per page, and call
  its tools 40 times, progress steps included. When a budget is spent
  the agent returns what it has found.
- Requests to the same host are at least a second apart, and at most
  three run at once.
- Pages a site's `robots.txt` disallows for `KetchBot` are not fetched,
  even when a redirect leads to them.
  Checking a download link's size and type with a HEAD request is not
  treated as crawling.

## Limiting discovery to websites

The **Limit to websites** field on the Discover page (`--sites` in the
CLI, `DiscoverQuery.sites` in code) is a hard limit, not a hint. Enter
domains separated by commas, or leave it empty to use any public site.
With sites listed:

- **Searches** only cover those sites; results elsewhere are dropped.
- **Pages and download links** on other domains are refused: the agent
  can neither read them nor check their size.
- **Results** on other domains are discarded, even when the agent found
  the link on a listed site.

Each domain covers its subdomains: `ubuntu.com` also allows
`releases.ubuntu.com`, but not `notubuntu.com` or
`ubuntu.com.example.net`. A scheme, path, port or leading `www.` is
ignored, so pasting `https://www.blender.org/download/` limits discovery
to `blender.org`. A list with no usable domain in it is rejected with an
error instead of searching everywhere.

The limit applies to the addresses the agent asks for, not to the
redirects a listed site answers with. GitHub, for example, serves
release assets from `objects.githubusercontent.com`: with `github.com`
listed, the agent can still check those downloads, and results keep the
`github.com` link (a result pointing at the CDN address would be
dropped). Every redirect hop still goes through the safeguards above.

Code that embeds the engine can also set
`DiscoveryConfig.allowedDomains`. That list caps every run: the sites a
query names can only narrow it, and a query whose sites all fall outside
it fails with an error.

## Environment variables

Blank credentials are filled from the environment at startup, which
keeps tokens out of the config file and makes CI and CLI use easy:

| Variable | Used for |
|----------|----------|
| `OPENAI_API_KEY` | OpenAI and OpenAI-compatible providers |
| `ANTHROPIC_API_KEY` | Anthropic |
| `GEMINI_API_KEY`, `GOOGLE_API_KEY` | Google Gemini |
| `BRAVE_SEARCH_API_KEY` | Brave web search |
| `GOOGLE_SEARCH_API_KEY` + `GOOGLE_SEARCH_CX` | Google web search |

Rules:

- A token saved in the settings always wins; the environment only fills
  blanks.
- **In the apps, the Enable switch decides.** An exported key fills in a
  blank token once you switch discovery on, but it never switches the
  feature on by itself.
- **Web search follows the same rule.** Once you pick Brave or Google, a
  blank key (and Google's engine id) is filled from that provider's
  variables; saved values are kept and your choice is never switched to
  another provider.
- **In the CLI**, which has no switch, untouched `[ai]` settings plus any
  provider key select that provider (LLM and web search) and turn
  discovery on — the "export a key and go" path. Once anything is
  configured, the environment only fills blank credentials there too.

The settings page judges the form the way the engine will: a blank API
key that the environment supplies is noted under the **API key** field
and counts as filled in the status line, and **Test** works with the
token left empty. It also works with the switch off, so you can check a
key before turning discovery on.

When the provider fails during a search or a **Test**, such as a rejected
API key, an unknown model or an unreachable endpoint, Discover shows why
next to **Try again**, and `ketch ai-discover` prints it, instead of
reporting that nothing was found.

## config.toml

```toml
[ai]
enabled = true

[ai.llm]
provider = "openai-compatible"
apiKey = "sk-..."
model = "llama-3.3-70b"
baseUrl = "https://openrouter.ai/api/v1"

[ai.search]
provider = "google"
apiKey = "..."
cx = "..."
```

Provider values are `openai`, `anthropic`, `google`, `ollama`,
`openai-compatible`; search providers are `none`, `brave`, `google`. A
config file without an `[ai]` section keeps the defaults (discovery off).

## Where Discover shows

The **Discover** destination shows in the sidebar, the rail and the
phone's bottom bar wherever discovery can run (desktop and Android), set
up or not. Until a provider is usable it shows a setup page with example
searches and a button per provider, which opens the Discover settings. A
search started meanwhile, from the add sheet with text that holds no
link, the command palette or a failed download's **Find another source**,
waits on that page and runs as soon as setup is done. The downloads
launchpad offers Discover too (**Or describe what you want**, ⌘E).

## Platform support

Discovery runs in-process on the desktop and Android apps and in the
CLI. iOS and the web app have no local engine, so their settings page
reports AI discovery as unavailable. The daemon's REST API has no
discovery endpoints: with a remote instance selected, the desktop and
Android apps still run discovery on the device, and the candidates you
pick are downloaded by that instance. See
[ai/discover/README.md](../ai/discover/README.md) for the engine
internals.
