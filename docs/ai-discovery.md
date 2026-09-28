# AI discovery configuration

AI discovery turns a plain-language request ("latest Ubuntu 24.04 desktop
ISO") into a ranked list of download links. It needs an LLM provider, and
works much better with a web search provider.

Configuration lives on the **Settings** page of the desktop and Android
apps, and is persisted in `config.toml` so the CLI uses the same values.

## Settings page

Open **Settings** from the sidebar (desktop) or the bottom bar
(Android), then the **AI discovery** category. It holds:

| Field | Notes |
|-------|-------|
| AI discovery | Master switch. While discovery cannot run, the **Discover** tab is hidden from the sidebar and bottom bar — Settings is where you switch it on. |
| Provider | OpenAI, Anthropic, Google Gemini, Ollama, or any OpenAI-compatible endpoint. |
| API key | Required for everything except Ollama. |
| Model | Blank uses the provider default (see below). |
| Endpoint | Blank uses the provider default; required for OpenAI-compatible. |
| Web search | None, Bing, or Google Programmable Search, plus credentials. |

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

Without a search provider the agent can only read pages it is pointed at
(the *Limit to websites* field on the Discover page), so results are
thin. Bing needs a subscription key; Google needs an API key plus a
Programmable Search engine id (`cx`).

## Environment variables

Blank credentials are filled from the environment at startup, which
keeps tokens out of the config file and makes CI and CLI use easy:

| Variable | Used for |
|----------|----------|
| `OPENAI_API_KEY` | OpenAI and OpenAI-compatible providers |
| `ANTHROPIC_API_KEY` | Anthropic |
| `GEMINI_API_KEY`, `GOOGLE_API_KEY` | Google Gemini |
| `BING_SEARCH_API_KEY` | Bing web search |
| `GOOGLE_SEARCH_API_KEY` + `GOOGLE_SEARCH_CX` | Google web search |

Rules:

- A token saved in the settings always wins; the environment only fills
  blanks.
- **In the apps, the Enable switch decides.** An exported key fills in a
  blank token once you switch discovery on, but it never switches the
  feature — or the Discover tab — on by itself.
- **Web search follows the same rule.** Once you pick Bing or Google, a
  blank key (and Google's engine id) is filled from that provider's
  variables; saved values are kept and your choice is never switched to
  another provider.
- **In the CLI**, which has no switch, untouched `[ai]` settings plus any
  provider key select that provider (LLM and web search) and turn
  discovery on — the "export a key and go" path. Once anything is
  configured, the environment only fills blank credentials there too.

The settings page judges the form the way the engine will: when a blank
field is covered by the environment it says so under the status line,
and **Test** works with the token left empty. It also works
with the switch off, so you can check a key before turning discovery on.

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
`openai-compatible`; search providers are `none`, `bing`, `google`. A
config file without an `[ai]` section keeps the defaults (discovery off).

## Where the tab goes

The **Discover** tab only appears once discovery is switched on and
complete, so the app never offers a tab that leads nowhere. An API key
in the environment alone doesn't count — the switch has to be on. Turn
discovery off and the tab disappears again; if you were on it, the app
returns you to Downloads.

## Platform support

Discovery runs in-process on the desktop and Android apps and in the
CLI. iOS and the web app have no local engine, so their settings page
reports AI discovery as unavailable. See
[ai/discover/README.md](../ai/discover/README.md) for the engine
internals.
