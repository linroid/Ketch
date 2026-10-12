# AI discovery

Discover turns a plain-language request, such as "latest Ubuntu 24.04 desktop ISO", into a short
answer and a ranked list of download links you can add with one click. This guide is for people
who use Discover in the Ketch apps or with `ketch ai-discover`: how to set it up, how to use it,
and what it does to keep you and your network safe.

Discover needs an LLM provider (OpenAI, Anthropic, Gemini, a local Ollama and many more). It
works much better with a web search provider too.

## Quick start

### In the desktop or Android app

1. Open **Settings** (⌘, or Ctrl+, on desktop, the sidebar's **Settings**, or the ⋮ menu on
   phones) and choose **Discover** under *This app*.
2. Pick a **Provider** and paste its **API key**. A hint under the provider says where to create
   one. Ollama and LM Studio need no key.
3. Optionally pick a **Web search** provider (Brave or Google) and enter its credentials.
4. Press **Test** to check the key and model right away.
5. Open **Discover** in the sidebar and type what you want.

Changes are saved as you make them; there is no Save button.

### On the command line

Export a key for any supported provider and run a search:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

```bash
ketch ai-discover "latest Ubuntu 24.04 desktop ISO"
```

The CLI reads the same `config.toml` as the desktop app, so a provider you set up there works
here too. See [command line](#command-line) for the options.

## Setting up

### The settings page

**Settings → Discover** holds everything Discover uses. The Discover page's setup screen links
here too.

| Field | What it does |
|-------|--------------|
| AI discovery | The master switch. Picking a provider on the Discover setup screen switches it on. |
| Content filter | Hides results that look unsafe. On by default; see [the content filter](#the-content-filter). |
| Before opening a website | **Allow automatically**, **Ask for each new site** (the default) or **Ask every time**; see [page access](#page-access). |
| Always allowed | Sites Discover opens without asking, each with its subdomains. Type one or more, such as `ubuntu.com`, separated by commas or spaces, and press **Add**; a pasted URL is reduced to its domain. Each site shows as a chip whose ✕ removes it. |
| Providers | The LLM providers you saved. The one in use is checked; **Use** switches to another, **Edit** changes one and **Add provider** saves a new one. |
| Web search | None, Brave, or Google Programmable Search, with their credentials. |

**Add provider** and **Edit** open a dialog with the provider, a name, the API key, the endpoint
and the models to choose from. The dialog can load the provider's model list for you, and
**Test** sends a one-line prompt so a wrong key or model shows up now instead of on your first
search. Nothing is saved until you confirm the dialog. **Remove**, pressed twice, deletes a
provider and its key.

In the dialog:

- **API key** is required for every provider except Ollama and LM Studio. A key the environment
  supplies ([below](#environment-variables)) is noted under the field, and **Test** works with
  the field left empty.
- **Model** left blank uses the provider's default. The buttons under it hold the provider's
  suggestions and the models you added; a typed id is added to them.
- **Endpoint** left blank uses the provider's default. It is required for OpenAI-compatible
  servers.

While the master switch is off, everything under it is disabled; what you entered is kept for
when you switch it back on.

### Several providers

You can save several providers at once, including more than one of the same kind, such as a
personal and a work OpenAI key, or two OpenAI-compatible servers. Each keeps its own name, key,
endpoint and models, and one of them is in use.

To switch, use **Use** in Settings, or the model chip in Discover's message field, which picks a
provider and model in one go. The switch applies to the next search: searches already running
finish with the provider and model they started with, and each turn of a chat remembers which
one it used.

A config written by an earlier version, with one provider under `[ai.llm]`, loads as one saved
provider in use, with nothing to enter again.

> [!WARNING]
> API keys are stored in plain text in `config.toml`, as the server's `apiToken` is. On a shared
> machine, prefer an [environment variable](#environment-variables).

## Providers

Ketch has presets for the providers below. The default model is the first one listed; the
suggestions track each provider's current models that can call tools (checked October 2026).
Where a provider has sites in several regions, the first endpoint is the default and the others
can be entered under **Endpoint**.

| Provider | `provider` | Models (default first) | Endpoint | Key variable |
|----------|------------|------------------------|----------|--------------|
| OpenAI | `openai` | `gpt-5.6-terra`, `gpt-6-astra`, `gpt-6.1-sol`, `gpt-6-luna` | `https://api.openai.com` | `OPENAI_API_KEY` |
| Anthropic | `anthropic` | `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`, `claude-fable-5-1` | `https://api.anthropic.com` | `ANTHROPIC_API_KEY` |
| Google Gemini | `google` | `gemini-3.8-flash`, `gemini-3.1-pro-preview`, `gemini-3.6-flash`, `gemini-3.5-flash-lite` | `https://generativelanguage.googleapis.com` | `GEMINI_API_KEY`, `GOOGLE_API_KEY` |
| OpenRouter | `openrouter` | `anthropic/claude-sonnet-5.5`, `openai/gpt-6.1-sol`, `google/gemini-3.8-flash`, `moonshotai/kimi-k3` | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` |
| DeepSeek | `deepseek` | `deepseek-flash`, `deepseek-v4-pro` | `https://api.deepseek.com/chat/completions` | `DEEPSEEK_API_KEY` |
| xAI | `xai` | `grok-4.7`, `grok-4.6`, `grok-4.3` | `https://api.x.ai/v1` | `XAI_API_KEY` |
| Mistral | `mistral` | `mistral-medium-latest`, `mistral-large-latest`, `mistral-small-latest` | `https://api.mistral.ai/v1` | `MISTRAL_API_KEY` |
| Groq | `groq` | `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`, `minimaxai/minimax-m2.7`, `openai/gpt-oss-20b` | `https://api.groq.com/openai/v1` | `GROQ_API_KEY` |
| Together AI | `together` | `moonshotai/Kimi-K3`, `zai-org/GLM-5.3`, `MiniMaxAI/MiniMax-M3`, `zai-org/GLM-5.3-Flash` | `https://api.together.ai/v1` | `TOGETHER_API_KEY` |
| Fireworks AI | `fireworks` | `accounts/fireworks/models/kimi-k3`, `…/glm-5p3`, `…/deepseek-v4p1-flash`, `…/minimax-m3` | `https://api.fireworks.ai/inference/v1` | `FIREWORKS_API_KEY` |
| Zhipu AI | `zhipu` | `glm-5.3`, `glm-5.2`, `glm-4.7`, `glm-4.7-flash` | `https://open.bigmodel.cn/api/paas/v4`, `https://api.z.ai/api/paas/v4` | `ZAI_API_KEY`, `ZHIPUAI_API_KEY` |
| Moonshot AI | `moonshot` | `kimi-k3`, `kimi-k2.6`, `kimi-k2.7-code` | `https://api.moonshot.cn/v1`, `https://api.moonshot.ai/v1` | `MOONSHOT_API_KEY` |
| Alibaba Model Studio | `qwen` | — (required) | `https://dashscope.aliyuncs.com/compatible-mode/v1`, `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `DASHSCOPE_API_KEY` |
| Volcengine Ark | `doubao` | — (required) | `https://ark.cn-beijing.volces.com/api/v3` | `ARK_API_KEY` |
| SiliconFlow | `siliconflow` | — (required) | `https://api.siliconflow.cn/v1`, `https://api.siliconflow.com/v1` | `SILICONFLOW_API_KEY` |
| MiniMax | `minimax` | `MiniMax-M3`, `MiniMax-M2.7`, `MiniMax-M2.7-highspeed` | `https://api.minimax.cn/v1`, `https://api.minimax.io/v1` | `MINIMAX_API_KEY` |
| Ollama | `ollama` | `qwen3`, `qwen3.6`, `gpt-oss`, `gemma4`, `llama3.1:8b` | `http://localhost:11434` | not used |
| LM Studio | `lmstudio` | — (required) | `http://localhost:1234/v1` | not used |
| OpenAI-compatible | `openai-compatible` | — (required) | — (required) | `OPENAI_API_KEY` |

Good to know:

- **The model must call tools.** The agent drives discovery through tools, so a model without
  tool calling returns nothing.
- **Any model id works.** The model field is free text, so a model released after this table
  works too. Alibaba Model Studio, Volcengine Ark, SiliconFlow and LM Studio suggest none: enter
  the id of one that can call tools, as the provider's console lists it.
- **Untested presets.** The endpoints and models come from each provider's documentation; not
  all of them have been tried with a real key yet.
- **APIs.** Every preset except OpenAI, Anthropic, Gemini and Ollama speaks OpenAI's chat
  completions API. The OpenAI preset calls models it does not know, such as ones newer than this
  version, through OpenAI's Responses API, which serves OpenAI's newest models.
- **Temperature.** Current frontier models reject a `temperature` setting, so Discover only
  sends one to models that accept it.
- **Unknown ids.** A `provider` id this version does not know, such as one a later version
  added, loads as `openai-compatible` with the rest of its settings.

### OpenAI-compatible servers

**OpenAI-compatible** covers any other server that speaks OpenAI's API, such as vLLM or LiteLLM,
and Gemini's OpenAI-compatible endpoint. Enter the endpoint as the provider documents it for
OpenAI's SDKs:

- An endpoint whose path names an API version is the base that `chat/completions` follows:
  `https://openrouter.ai/api/v1`, `https://open.bigmodel.cn/api/paas/v4` or
  `https://generativelanguage.googleapis.com/v1beta/openai`.
- An endpoint without one is the server's root, which `v1/chat/completions` follows:
  `http://localhost:8000`.
- A full URL ending in `/chat/completions` is used as it is.

## Web search

Without a search provider the agent can only read pages whose address it already knows, such as
the home pages of the sites you [limit it to](#limiting-discovery-to-websites), so results are
thin.

| Provider | What it needs |
|----------|---------------|
| Brave | A Brave Search API subscription token, from `api-dashboard.search.brave.com`. |
| Google | An API key and a Programmable Search engine id (`cx`). Google has closed its Custom Search JSON API to new customers and turns it off on January 1, 2027, so only existing keys work. |

When several searches run at once, their Brave or Google queries start at least 1.1 seconds
apart, which keeps them within Brave's free plan of one query a second.

Bing is no longer offered: Microsoft retired the Bing Search APIs on August 11, 2025. A config
file that still says `provider = "bing"` loads with web search set to None.

## Environment variables

Blank credentials are filled from the environment at startup, which keeps keys out of the config
file and makes CLI and CI use easy.

| Variable | Used for |
|----------|----------|
| `OPENAI_API_KEY` | OpenAI and OpenAI-compatible providers |
| `ANTHROPIC_API_KEY` | Anthropic |
| `GEMINI_API_KEY`, `GOOGLE_API_KEY` | Google Gemini |
| The **Key variable** column of [the presets](#providers) | Every other preset, such as `DEEPSEEK_API_KEY` |
| `BRAVE_SEARCH_API_KEY` | Brave web search |
| `GOOGLE_SEARCH_API_KEY` and `GOOGLE_SEARCH_CX` | Google web search |

How they combine with your settings:

- **Saved values always win.** The environment only fills blank keys, of every saved provider,
  each from its own provider's variable.
- **In the apps, your settings decide.** Discovery is on by default, so an exported key for the
  provider you chose is all it needs. Once you switch Discover off, a key never switches it back
  on, and the environment never adds a provider: an `ANTHROPIC_API_KEY` alone does nothing while
  no Anthropic provider is saved.
- **Web search follows the same rule.** Once you pick Brave or Google, a blank key (and Google's
  engine id) is filled from that provider's variables; your choice is never switched.
- **In the CLI, untouched settings take what the environment offers.** While `[ai]` is still at
  its defaults, each exported provider key adds a provider (the first in the order of
  [the presets](#providers) is used), and an exported search key picks the web search provider.
  `--provider` picks another. Once anything is configured, the environment only fills blank
  keys, and `--provider` with a preset you have not saved, such as `--provider deepseek`, takes
  its key from the environment. Settings that differ only in `[ai.access]` still count as
  untouched.

## config.toml

The apps save Discover's settings under `[ai]` in `config.toml`, which the CLI reads too. The
[portable Windows app](updates.md#the-portable-windows-app) keeps its `config.toml` in its own
`data` folder, which the CLI does not read.

```toml
[ai]
enabled = true
active = "work"
contentFilter = true

[[ai.providers]]
id = "openai"
provider = "openai"
apiKey = "sk-..."

[[ai.providers]]
id = "work"
name = "Work"
provider = "openai-compatible"
apiKey = "sk-..."
baseUrl = "https://llm.example.com/v1"
model = "llama-3.3-70b"
models = ["llama-3.3-70b", "qwen3-32b"]

[ai.search]
provider = "google"
apiKey = "..."
cx = "..."

[ai.access]
mode = "ask-site"
trustedSites = ["ubuntu.com", "blender.org"]
```

| Key | Default | Meaning |
|-----|---------|---------|
| `[ai] enabled` | `true` | The master switch. A file without an `[ai]` section has Discover on, waiting for a key. |
| `[ai] active` | first provider | The `id` of the provider in use; the first one when no entry has that id. |
| `[ai] contentFilter` | `true` | The [content filter](#the-content-filter). |
| `[[ai.providers]] id` | — | Unique among the entries. Entries without one, or with one another entry has, get one when the file loads. |
| `[[ai.providers]] name` | provider's name | What the apps call it. |
| `[[ai.providers]] provider` | `openai` | One of the ids in [the presets](#providers). |
| `[[ai.providers]] apiKey` | blank | The key; blank falls back to the [environment](#environment-variables). |
| `[[ai.providers]] baseUrl` | provider's | The endpoint. |
| `[[ai.providers]] model` | provider's | The model to call. |
| `[[ai.providers]] models` | `[]` | Model ids you added, offered next to the provider's suggestions. |
| `[ai.search] provider` | `none` | `none`, `brave` or `google`. |
| `[ai.search] apiKey`, `cx` | blank | The search provider's key, and Google's engine id. |
| `[ai.access] mode` | `ask-site` | `allow`, `ask-site` or `ask`; see [page access](#page-access). An unknown mode loads as `ask-site`. |
| `[ai.access] trustedSites` | `[]` | Sites opened without asking, each covering its subdomains. `Blender.org`, `www.blender.org` and `https://blender.org/` all count as `blender.org`. |
| `[ui] discoverHistory` | `true` | Whether the history is docked beside the chat on wide pages. |

A config with `[ai.llm]` instead of `[[ai.providers]]`, written by an earlier version, loads as
one entry in use.

## Using Discover in the apps

### Where Discover shows

The **Discover** destination shows in the sidebar, the rail and the phone's bottom bar on desktop
and Android while it is switched on, which it is by default, set up or not. Until a provider is
usable it shows a setup page: what Discover does, example searches, a button per provider that
opens the settings to finish, and **Turn off Discover** for those who do not want it.

Turning Discover off, from the setup page or the page's ⋯ menu, hides it and every way into it,
with **Undo** in the toast. Switch it back on in Settings → Discover; saved chats are kept.

You can also start a search from:

- the add sheet, with text that holds no link;
- the command palette;
- the phone's search;
- a failed download's **Find another source**;
- the downloads launchpad (**Or describe what you want**).

Each of these starts a new chat. A search started before Discover is set up waits on the setup
page and runs once setup is done. ⌘E (Ctrl+E) shows Discover as you left it, ready to type.

The page's controls:

- **With a sidebar or rail**, the header holds **History**, a ⋯ menu, **New search** and the model
  in use, such as "Anthropic · claude-opus-5-5", which opens its settings.
- **On phones**, the top bar holds **History** and **New search**, and its ⋮ menu starts with the
  page's items.
- The menu holds **Delete this search**, **Clear history**, **Discover settings** and **Turn off
  Discover**.

Switching Discover off, or clearing something it needs such as its key, stops the searches that
are running or waiting, as **Stop** does. While Discover is off or not set up, **History** still
opens your saved chats. They read as they were left: you can add and discard their results, but
*Set up Discover to continue* replaces the message field, and **Try again** and **Search the whole
web** are not offered.

### Chats

Type what you want in the message field at the bottom of the page and press **Find**, or **Send**
for a follow-up.

- On a computer, Enter sends and Shift+Enter starts a new line. On phones and tablets, Enter starts
  a new line and the button sends.
- The field grows to six lines, and each chat keeps your unsent text while the app runs.
- While a search runs, the button turns into **Stop** (Esc). **Stop** keeps the steps the agent
  reported.

An empty chat shows what Discover does and three example searches, picked at random, which run
when clicked. Each message becomes a turn in the thread:

1. your message, with the websites it was limited to;
2. the agent's steps as it reports them, with **Details** listing every step in full;
3. [page access questions](#answering-in-the-apps), and a line summing up your answers, such as
   "Allowed 3 sites · Denied 1 site";
4. the agent's short summary;
5. its results, such as "4 downloads", with a box that selects them all and **Discard all**.

Your messages and the agent's summaries can be selected and copied. The thread follows the newest
turn while you read at its end; scroll up and it stays where you are.

**Follow-ups.** Later messages refine the search, such as "only the LTS release". Each one runs
the agent again with fresh budgets and sends it the conversation so far: earlier requests and
their site limits, the results of finished turns (without the ones you discarded), and which
turns failed or were stopped. A long chat sends its first turn and the latest five. A follow-up
that only narrows, picks from or explains earlier results, such as "just the latest version", is
answered from them in a *Refining* step, without searching again. Either way you get the complete
list for the latest request.

**Devices.** Each search tells the agent the system and CPU of this device and of the device the
results will go to, such as *macOS aarch64* and *Linux amd64*. They are only hints: when you ask
for software that comes in builds per platform and name none, the agent prefers builds for those
devices, and says in each result which system and CPU a build is for. On computers and tablets
with two devices or more, an **On:** chip in the message field picks the target device before the
first search.

**Summary.** The agent writes one or two plain sentences above its results. When it finds nothing
it can recommend, such as for a request for pirated copies, the summary says why.

**Adding.** Click results to select them. The add bar above the message field works on
everything selected in the chat, across turns: it reads, for example, "2 selected · 1.2 GB · 1
from earlier". **Add now** adds them, and **Review & add** opens them in the add sheet first. With
two devices or more, the bar also picks the device. On phones the bar shows only while something
is selected; **Review & add** and the device are under its ⋮. Opening another chat clears the
selection.

**Trying again.** **Try again** reruns the newest turn after it failed (the turn explains why) or
was stopped. When a turn limited to websites finds nothing, **Search the whole web** reruns it
without the limit.

**Discarding.** Discard a result, or every result of a turn, to hide it in every turn of the chat.
A result's ✕ shows on hover or focus, and always on touch, where swiping a result toward the start
discards it too. The agent is told never to suggest it again, and later results leave it out even
if it does. **Restore** under the turn, **Undo** in the toast or ⌘Z (Ctrl+Z) brings it back.

**Several at once.** Each chat runs one turn at a time, and at most three searches run at once
across chats. The next one waits (*Waiting for another search to finish*) and starts when one
ends.

### Limiting discovery to websites

The **Limit to websites** chip under the message field opens a field for domains, separated by
commas. Leave it empty to use any public site. Each message keeps the sites it was sent with,
shown under it as "Limited to …". In the CLI this is `--sites`.

It is a hard limit, not a hint. With sites listed:

- **Searches** only cover those sites.
- **Pages and download links** on other domains are refused.
- **Results** on other domains are dropped, even when the agent found the link on a listed site.
- **Page access never asks**: every host the agent may request is one you named.

Each domain covers its subdomains: `ubuntu.com` allows `releases.ubuntu.com`, but not
`notubuntu.com` or `ubuntu.com.example.net`. A scheme, path, port or leading `www.` is ignored, so
pasting `https://www.blender.org/download/` limits discovery to `blender.org`. A list with no
usable domain is rejected with an error instead of searching everywhere.

The limit applies to the addresses the agent asks for, not to redirects a listed site answers
with. GitHub, for example, serves release assets from `objects.githubusercontent.com`: with
`github.com` listed, the agent can still check those downloads, and results keep the `github.com`
link. Every redirect hop still goes through the [safeguards](#what-the-agent-may-fetch).

### History

Every chat is saved, newest first by when it last ran, under *Today*, *Yesterday* and *Earlier*.
A chat is named by its first message until the agent gives it a short title in the language you
wrote in; follow-ups never rename it. Each row says what the chat is doing (*Needs your OK*,
*Searching…*, *Waiting to start*, *Failed*, *Stopped*) or, once done, how many results it has and
when it last ran. From six chats on, a **Search history** field filters them.

- **Open** a row to read the chat or continue it.
- **New search** (⇧⌘E) shows an empty chat; the current one stays in the history.
- **Delete** a chat with its trash button, ⌫ (Delete) on its row, or the ⋯ menu. Deleting a
  running chat stops it.
- **Clear history** deletes every chat that is not running.

Deleting and clearing can be undone from the toast or with ⌘Z (Ctrl+Z). The history keeps the 50
most recent chats, never dropping one that is running or shown. A search that was running when
the app quit shows as stopped.

Where the history shows:

- **Wide pages:** docked beside the chat. **History** (⇧⌘H) shows or hides it, and Ketch remembers
  the choice.
- **Narrower pages with a sidebar or rail:** floating over the chat. Esc, ✕, picking a chat,
  **New search** or **Clear history** closes it.
- **Phones:** a sheet from **History** in the top bar.

The history is saved to `discover-history.json`:

- **Desktop:** next to `config.toml`, in the directory the CLI reads its config from
  ([locations](../cli/README.md#config-file-locations)).
- **Android:** in the app's private files, left out of backups and device transfers.

It holds each chat's messages and site limits, the agent's last 30 steps per turn, its summaries
and results, the results you discarded, a note of the sites you allowed or denied, and the
provider and model each turn used. Links in the steps are redacted as in the logs (passwords and
token-like query values masked); result links are kept so you can still add them. A failed turn
keeps only the short explanation, such as *The AI provider rejected the API token (HTTP 401)*,
never the provider's reply, which may echo a key. Page access answers are not saved.

Ketch writes the file through a temporary file, so an interrupted write cannot corrupt it. A file
it cannot read, or one from a newer version, is moved to `discover-history.json.bak` and the
history starts empty. To remove the history, use **Clear history**, or delete the file while
Ketch is closed.

### Keyboard shortcuts

These work while Discover shows, and the shortcut sheet (⌘/) lists them under *Discover*.

| macOS | Windows and Linux | Does |
|-------|-------------------|------|
| ↩ | Enter | Sends the message. |
| ⇧↩ | Shift+Enter | Starts a new line. |
| Esc | Esc | Closes the floating history; otherwise stops the chat's search. |
| ⌘↩ | Ctrl+Enter | Answers the waiting question with its first button, **Allow** or **Allow once**. |
| ⌘⌫ | Ctrl+Backspace | Denies the waiting question. |
| ⌫ | Delete | Discards the focused result, or deletes the focused chat in the history. |
| ⇧⌘E | Ctrl+Shift+E | Shows an empty chat (**New search**). |
| ⇧⌘H | Ctrl+Shift+H | Shows or hides the history. |
| ⌘E | Ctrl+E | Shows Discover from anywhere in the window, with the cursor in the message field. |
| ⌘Z | Ctrl+Z | Undoes the last change that offered Undo, such as a discard or a deleted chat. |

↩, ⇧↩, ⌘↩, ⌘⌫, ⌫ and Esc (stopping a search) need the keyboard on the page; ⇧⌘E, ⇧⌘H and Esc
(closing the history) work anywhere in the window. While a text field has the cursor, ⌫ and ⌘Z
edit the text instead, and so does ⌘⌫ once something is typed. ↩ and ⇧↩ are left to an input
method while it composes text.

## Page access

The agent opens a website when it reads a page, or checks a download's type and size with a HEAD
request. The page access setting decides whether Discover asks you first:

| Setting | `mode` in `[ai.access]` | Asks |
|---------|-------------------------|------|
| Allow automatically | `allow` | Never. |
| Ask for each new site | `ask-site` (default) | The first time a chat opens a site; once allowed, the site stays allowed for the rest of the chat. |
| Ask every time | `ask` | Before every page and file check. |

Sites under **Always allowed** (`trustedSites`) open without asking in every mode, each with its
subdomains: `ubuntu.com` also covers `releases.ubuntu.com`.

### What asks

- **A page fetch or HEAD request** asks before anything is sent to the site, unless the mode,
  **Always allowed** or an earlier answer covers it. The host is not even looked up in DNS before
  you allow it, since a lookup would already tell the domain's name servers the name the agent
  chose.
- **A redirect to another host** asks too, naming the host it came from.
- **robots.txt** on a host you allowed is read without asking. If it redirects to another host of
  the same site, such as `www.`, that host asks like any redirect.
- **Searches never ask**: they go to the search provider, not to the sites.
- **A search [limited to websites](#limiting-discovery-to-websites) never asks.**
- **A request the safeguards refuse anyway**, such as one to a private address, outside the site
  limit or past the search's budget, is refused without asking.

### When you deny a request

- Nothing is sent to the site, and the request costs none of the search's budget.
- The agent is told you declined and not to request that host again. It looks elsewhere or returns
  what it has.
- Later requests to the same site and its subdomains are denied without asking, for the rest of
  the search, and in the apps for the rest of the chat. If you later switch to **Allow
  automatically** or add the site to **Always allowed**, later searches in the chat open it.

The agent may say why it wants a page, shown as "Discover says: …". That text comes from the
model, which the pages it read may have shaped, so Discover makes it one line of at most 160
characters, removes control, bidirectional and zero-width characters, and never uses it as the
question or on a button. The agent's summary gets the same treatment, up to 600 characters.

### Answering in the apps

The question is a card in the chat's running turn. Its title says what the agent wants: *Open
www.blender.org?* for a page, *Check a file on …?* for a download, or *Follow a redirect to …?*.
Under it are the address (shortened in the middle when long), the site a redirect came from, and
the agent's reason.

The buttons depend on the page access setting. For a page on `www.blender.org`:

| | Ask for each new site | Ask every time |
|-|-----------------------|----------------|
| First button (⌘↩) | **Allow**: `blender.org` for the rest of the chat | **Allow once**: only this request |
| Second button (⌘⌫) | **Deny** | **Deny** |
| Under them | **Always allow blender.org**, **Allow all sites in this chat** | **Allow blender.org in this chat**, **Always allow blender.org**, **Allow all sites in this chat** |

- **Deny** declines the request and, for the rest of the chat, the site.
- **Always allow blender.org** adds the site to **Always allowed**, saved in `config.toml`, so
  later chats and the CLI open it without asking.
- **Allow all sites in this chat** stops asking in this chat.
- **Page access settings**, at the foot of the card, opens Settings → Discover.

A site is the host without a leading `www.`, with its subdomains: allowing `blender.org` covers
`download.blender.org` too, while a question about `download.blender.org` offers only that host.
The `www.` stays when dropping it would leave a suffix unrelated sites share, such as `com`,
`co.uk` or `github.io`, so an answer never covers every GitHub Pages site.

Answers last while the app runs; after a restart a chat asks again, and only **Always allow** is
kept. An answer, or a change to the settings, also answers every other waiting question it covers,
in any chat. Stopping a search, deleting its chat or quitting the app while a question waits
denies it and ends the search.

With a keyboard:

- When a card appears while the keyboard is in the thread or on **Stop**, focus moves to its first
  button. In the message or website field focus stays put, so a key meant for your next message
  never answers a card you have not read.
- After you answer from a card, focus moves to the next card, or back to the message field.
- ⌘↩ (Ctrl+Enter) gives the first button's answer and ⌘⌫ (Ctrl+Backspace) denies, from anywhere
  in the chat.
- Screen readers announce the card as it appears.

### When a question waits out of sight

- **Out of view in the chat:** **Needs your OK** floats at the bottom of the chat; it scrolls to
  the card and focuses its first button.
- **In another chat or page:** a toast says *Discover needs your OK to open* the host, and
  **Review** opens that chat. The Discover item in the navigation counts every waiting question,
  and the History button counts the other chats that wait.
- **While the app is in the background:** the desktop and Android apps raise one system
  notification per question. Settings → Notifications does not apply to it, but the system's
  notification settings do (on Android, the *Requests for your OK* channel). On Android 13 and
  later the app offers to turn notifications on the first time Discover asks. On Android, Ketch
  keeps running in the background while a search runs or waits, so it can go on and ask while you
  use other apps.

## The content filter

Before results show, Discover hides those that look unsafe:

- links through URL shorteners and download aggregators;
- links or descriptions with piracy signals, such as "crack" or "keygen";
- hosts that imitate a well-known one (`fake-github.com`, `github.com.example.net`) and links with
  user info before the host (`https://github.com@evil.example/...`);
- installers (`.exe`, `.msi`, `.dmg`, `.pkg`, `.apk`) not served over HTTPS from a site outside
  Discover's short list of well-known hosts. Over HTTPS they show, ranked below the same file from
  a well-known host.

When the filter hid results, the turn says so, such as "2 hidden by the content filter", with a
link to the settings. Turning **Content filter** off (`contentFilter = false` under `[ai]`, or
`--no-filter` in the CLI) shows them from the next search on. With the filter off, the agent is
also no longer told to refuse pirated content or drop risky links; it still prefers official
sources and ranks risky links lower. The model may still decline a request on its own.

Some links are never shown, whatever the setting: links to private and local addresses, sites
outside a search's [limit](#limiting-discovery-to-websites), and links you discarded.

## What the agent may fetch

Every page and HEAD request goes through the same safeguards:

- **Public addresses only.** Only public `http` and `https` addresses are reached. Redirects are
  followed one hop at a time (at most 10) and each hop is checked, so a public page cannot redirect
  the agent to your router or `localhost`. Hosts are checked again when connecting, so a host
  cannot pass the check with a public address and then connect to a private one.
- **No proxy.** Discover's page requests never use a proxy, so the address checks above always
  apply.
- **A budget per search.** A search may make 25 page fetches and HEAD requests in total, read
  20 MB of page content (at most 2 MB per page), and call its tools 40 times, progress steps
  included. When a budget is spent the agent returns what it has found.
- **Polite pacing.** Requests to the same host are at least a second apart, and at most three run
  at once.
- **robots.txt.** Pages a site's `robots.txt` disallows for `KetchBot` are not fetched, even when a
  redirect leads to them. A `robots.txt` that redirects to another site counts as missing.
  Checking a download link's size and type with a HEAD request is not treated as crawling.
- **Your OK.** Before a page or HEAD request reaches a site, Discover may ask you
  ([page access](#page-access)).

When the provider fails during a search or a **Test**, such as a rejected key, an unknown model or
an unreachable endpoint, Discover shows why next to **Try again**, and `ketch ai-discover` prints
it, instead of reporting that nothing was found.

## Command line

`ketch ai-discover "<request>"` runs one search with the `[ai]` settings of the default config
file. It has no follow-ups or history, and tells the agent this machine's system and CPU as the
device hint.

```bash
ketch ai-discover "ffmpeg release" --sites ffmpeg.org
```

```bash
ketch ai-discover --yes "blender 4.2 macOS" > results.txt
```

| Option | What it does |
|--------|--------------|
| `--sites <a,b>` | [Limits the search](#limiting-discovery-to-websites) to these domains; never asks for page access. |
| `--max-results <n>` | Shows at most this many results (default 5). |
| `--provider <name>` | Searches with a saved provider, by its `id` or name, or with a preset such as `deepseek`: the first saved one of that kind, or one whose key comes from the environment. |
| `--model <id>` | Calls this model. |
| `-y`, `--yes` | Allows every website for the run without asking. |
| `--no-filter` | Turns the [content filter](#the-content-filter) off for the run. |

`--provider` and `--model` apply to the run only; an unknown provider exits with status 2 and
lists the saved providers and the presets. Only the summary and the results go to stdout. The
banner, the model in use, the agent's steps, questions, the number of filtered results and errors
go to stderr, so `> results.txt` keeps just the results.

### Page access in the terminal

The CLI follows `[ai.access]`. When that says to ask, it asks on the terminal before the agent
opens a website:

```text
Allow Discover to open www.blender.org? https://www.blender.org/download/
  Discover says: Read the Blender download page
[y] allow  [s] allow blender.org for this run  [a] allow all  [n] deny:
```

- `y` allows the site for the rest of the run with `ask-site`, and only this request with `ask`.
- `s` allows the site and its subdomains for the run; `a` allows every website.
- `n`, an empty or unknown answer, or the end of input denies. After the end of input (Ctrl+D),
  every later request is denied without asking.

Answers last for the run; the CLI never writes `config.toml`. It asks on the controlling terminal
(`/dev/tty`), so questions still reach you when stdin or stdout is redirected. On Windows it asks
on the console, and only while stdin and stdout are both the console.

Without a terminal, such as under cron or in CI, or with redirected streams on Windows, a run that
may ask stops before it starts, with a hint and exit status 1. Pass `--yes` or `--sites`, or set
`mode = "allow"`.

Exit status: 0 on success, 1 when the run fails or cannot ask, 2 for invalid arguments. See
[the CLI guide](../cli/README.md#ai-discovery) for more.

## Platform support

| Where | Discover |
|-------|----------|
| Desktop app | Yes, with chats and history |
| Android app | Yes, with chats and history |
| CLI | Yes, one search per run |
| iOS app, web app | No: there is no local engine, and the settings page reports it as unavailable |
| `ketch server` | No discovery API |

With a remote device selected, the desktop and Android apps still run Discover on your device, and
the results you pick are downloaded by the remote device.

## For developers

Code that embeds the engine (`ai:discover`) passes the site limit as `DiscoverQuery.sites` and can
also set `DiscoveryConfig.allowedDomains`. That list caps every search: a query's sites can only
narrow it, and a query whose sites all fall outside it fails with an error. Unlike
`DiscoverQuery.sites`, a limit set only by `allowedDomains` still asks for page access.

See [ai/discover/README.md](../ai/discover/README.md) for the engine's API, tools and internals.

## See also

- [CLI guide: AI discovery](../cli/README.md#ai-discovery)
- [Logging](logging.md), for what Discover writes to the logs
