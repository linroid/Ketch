# AI discovery configuration

AI discovery turns a plain-language request ("latest Ubuntu 24.04 desktop
ISO") into a short answer and a ranked list of download links. It needs
an LLM provider, and works much better with a web search provider.

In the apps, Discover is a chat. Follow-up messages refine a search
("only the arm64 build"), past searches stay in a history you can reopen
and continue, and results you don't want can be discarded. Before the
agent opens a website, Discover asks you, unless you let it open
websites on its own ([page access](#page-access)).

Configuration lives on the **Settings** page of the desktop and Android
apps, and is persisted in `config.toml` so the CLI uses the same values.
The [portable Windows app](updates.md#the-portable-windows-app) keeps its
`config.toml` in its own `data` folder, which the CLI does not read.

## Settings page

Open **Settings** (⌘, or Ctrl+, on desktop, the sidebar's Settings, or
the ⋮ menu on phones), then **Discover** under *This app*. The Discover
page's own setup links there too. It holds:

| Field | Notes |
|-------|-------|
| AI discovery | Master switch. Picking a provider on the Discover page's setup switches it on. |
| Before opening a website | **Allow automatically**, **Ask for each new site** (the default) or **Ask every time**; see [page access](#page-access). |
| Always allowed | Sites Discover opens without asking, each with its subdomains. Type one or more, such as `ubuntu.com`, separated by commas or spaces, and press **Add**; a pasted URL is reduced to its domain. Each site shows as a chip whose ✕ removes it, and **Always allow** on a question adds one. |
| Provider | OpenAI, Anthropic, Google Gemini, Ollama, or any OpenAI-compatible endpoint. |
| API key | Required for everything except Ollama. |
| Model | Blank uses the provider default (see below). |
| Endpoint | Blank uses the provider default; required for OpenAI-compatible. |
| Web search | None, Brave, or Google Programmable Search, plus credentials. |

Changes are saved as you make them — there is no Save button. A change
to the provider, key, model, endpoint or web search rebuilds the
discovery engine in place, without restarting the app. Page access
changes keep the engine: searches that are running carry on under the
new rules, and questions waiting for an answer that the change covers
are answered by it. **Test** sends a one-line prompt to the provider
with the saved settings, so a wrong key or model shows up immediately
instead of on the first search.

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

Several searches can run at once (see [chats](#chats)). Their Brave or
Google queries start at least 1.1 seconds apart, which keeps them within
Brave's free plan of one query a second.

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
  even when a redirect leads to them. A `robots.txt` that redirects to
  another site is not followed and counts as missing.
  Checking a download link's size and type with a HEAD request is not
  treated as crawling.
- Before a page or HEAD request reaches a site, Discover may ask for
  your OK ([page access](#page-access)).

## Page access

The agent opens a website when it reads a page or checks a download's
type and size with a HEAD request. The page access setting decides
whether Discover asks you first:

| Setting | `mode` in `[ai.access]` | Asks |
|---------|-------------------------|------|
| Allow automatically | `allow` | Never. |
| Ask for each new site | `ask-site` (default) | The first time a chat opens a site; once you allow it, the site stays allowed for the rest of the chat. |
| Ask every time | `ask` | Before every page and file check. |

Sites under **Always allowed** (`trustedSites`) are opened without
asking in every mode, each with its subdomains: `ubuntu.com` also covers
`releases.ubuntu.com`.

What asks:

- A page fetch or HEAD request asks before anything is sent to the
  site, unless the mode, **Always allowed** or an earlier answer covers
  it. Its host is not even looked up in DNS before you allow it: a
  lookup would already tell the domain's name servers the name the agent
  chose. A request the safeguards above refuse anyway, such as one to a
  private address, outside *Limit to websites* or past the search's
  budget, is refused without asking.
- A redirect to another host asks too, unless that host is covered the
  same way; the question names the host it came from.
- Allowing a page also allows reading `robots.txt` on its host. When
  that `robots.txt` redirects to another host of the same site, such as
  `www.`, that host asks like any redirect.
- Searches never ask: they go to the search provider, not to the sites.
- A search limited to websites never asks
  ([below](#limiting-discovery-to-websites)).

When you deny a request:

- Nothing is sent to the site, and the request spends none of the run's
  budget.
- The agent is told that you declined and not to request that host
  again in this search. It looks elsewhere or returns what it has.
- Later requests to the same site and its subdomains are denied without
  asking, for the rest of the search, and in the apps for the rest of
  the chat. Settings changed since win in the chat's later searches:
  once **Allow automatically** or **Always allowed** covers the site,
  they open it.

The agent may say why it wants a page; the question shows it as
"Discover says: …". That text comes from the model, which the pages it
read may have shaped, so Discover makes it one line of at most 160
characters, removes control, bidirectional and zero-width characters,
and never uses it as the question or on a button. The agent's summary
above the results gets the same treatment, up to 600 characters.

### Answering in the apps

The question is a card in the chat's running turn, in place of the
placeholder results. Its title says what the agent wants: *Open
www.blender.org?* for a page, *Check a file on …?* for a download's
size and type, or *Follow a redirect to …?*. Under it are the address,
shortened in the middle when it is long (the query goes first; the
scheme, host and file name stay), for a redirect the site it came from
(*Redirected from github.com*), and the agent's reason as "Discover
says: …".

The answers depend on the page access setting. For a page on
`www.blender.org`:

| | Ask for each new site | Ask every time |
|-|-----------------------|----------------|
| First button (⌘↩) | **Allow**: `blender.org` for the rest of the chat, as the line under it says | **Allow once**: only this request |
| Second button (⌘⌫) | **Deny** | **Deny** |
| Under them | **Always allow blender.org**, **Allow all sites in this chat** | **Allow blender.org in this chat**, **Always allow blender.org**, **Allow all sites in this chat** |

- **Deny** declines the request and, for the rest of the chat, the site.
- **Always allow blender.org** adds the site to **Always allowed**,
  which is saved in `config.toml`, so later chats and the CLI open it
  without asking too.
- **Allow all sites in this chat** stops asking in this chat.
- **Page access settings**, at the foot of the card, opens Settings →
  Discover.

The site is the host without a leading `www.`, with its subdomains:
allowing `blender.org` covers `download.blender.org` too, while a
question about `download.blender.org` offers only that host and its
subdomains. The `www.` stays when dropping it would leave a suffix that
unrelated sites share, such as `com`, `co.uk` or `github.io`: an answer
about `www.github.io` covers that site only, never every GitHub Pages
site.

Answers for a chat last while the app runs. They are not saved, so after
a restart the chat asks again; only **Always allow** is kept. An answer,
or a change to the page access settings, also answers every other
waiting question it now covers, in any chat. Stopping a search, deleting
its chat or quitting the app while a question waits denies it and ends
the search.

Each turn sums up your answers in one line, such as "Allowed 3 sites ·
Denied 1 site", which lists the sites when opened. Requests allowed
without asking leave no note.

With a keyboard:

- When a card appears while the keyboard is in the thread or on
  **Stop**, the keyboard moves to its first button. In the message or
  website field it stays where it is, even with nothing typed, so a key
  meant for your next message never answers a card you have not read.
- Once you answer from a card, the keyboard moves to the next card,
  including one the agent asks for right after, or back to the message
  field. An answer from the message field leaves it there.
- ⌘↩ (Ctrl+Enter) gives the first button's answer and ⌘⌫
  (Ctrl+Backspace) denies, from anywhere in the chat, the message field
  included; while you are typing a message or websites, ⌘⌫ edits the
  text instead.
- Screen readers announce the card as it appears.

While a waiting card is out of view, such as when you have scrolled up
to read, **Needs your OK** floats over the bottom of the chat; it
scrolls to the card and puts the keyboard on its first button.

When a question waits in a chat that Discover is not showing, because
another page or chat is shown or the window is in the background, a
toast says *Discover needs your OK to open* the host, and **Review**
opens that chat. The toast goes away once the request is answered or
ends, or its chat shows. A question whose card you have seen gets no
toast when you only switch to another app or window, only when another
page or chat shows.

While the app is not in front, the desktop and Android apps also raise
a system notification for the question's toast, also when you leave
the app while the toast shows. Each question raises one at most. The
download settings under Settings → Notifications do not apply to it;
the system's notification settings do (on Android, the *Requests for
your OK* channel). On Android 13 and later, the app offers to turn
notifications on when Discover first asks, unless it offered them
already. On Android, tapping a notification opens the chat, and it
goes away once the request is answered. While a search runs or waits,
Android keeps Ketch running in the background, as it does for
downloads, so the search can go on and ask while you use other apps;
with nothing else running, the ongoing notification says *Discover is
searching for downloads*. Counts mark the questions that wait:

- **Navigation:** the Discover item in the sidebar, the rail and the
  phone's bottom bar counts every question that waits, in any chat.
- **History:** the History button counts the other chats that wait, and
  their rows in the history say *Needs your OK*.

## Limiting discovery to websites

The **Limit to websites** chip under Discover's message field opens a
field above the message (`--sites` in the CLI, `DiscoverQuery.sites` in
code). It is a hard limit, not a hint. Enter domains separated by commas,
or leave it empty to use any public site. Each message keeps the sites
it was sent with, shown under it as "Limited to …", and the field keeps
them for the next message. With sites listed:

- **Searches** only cover those sites; results elsewhere are dropped.
- **Pages and download links** on other domains are refused: the agent
  can neither read them nor check their size.
- **Results** on other domains are dropped, even when the agent found
  the link on a listed site.
- **Page access never asks**: every host the agent may request is one
  you named.

Each domain covers its subdomains: `ubuntu.com` also allows
`releases.ubuntu.com`, but not `notubuntu.com` or
`ubuntu.com.example.net`. A scheme, path, port or leading `www.` is
ignored, so pasting `https://www.blender.org/download/` limits discovery
to `blender.org`. A list with no usable domain in it is rejected with an
error instead of searching everywhere.

The limit applies to the addresses the agent asks for, not to the
redirects a listed site answers with. GitHub, for example, serves
release assets from `objects.githubusercontent.com`: with `github.com`
listed, the agent can still check those downloads, without asking, and
results keep the `github.com` link (a result pointing at the CDN address
would be dropped). Every redirect hop still goes through the safeguards
above.

Code that embeds the engine can also set
`DiscoveryConfig.allowedDomains`. That list caps every run: the sites a
query names can only narrow it, and a query whose sites all fall outside
it fails with an error. It does not skip page access: a run limited only
by `allowedDomains` still asks.

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
  Page access does not count: settings that differ only in `[ai.access]`
  are still untouched.

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

[ai.access]
mode = "ask-site"
trustedSites = ["ubuntu.com", "blender.org"]
```

Provider values are `openai`, `anthropic`, `google`, `ollama`,
`openai-compatible`; search providers are `none`, `brave`, `google`;
page access modes are `allow`, `ask-site` (the default) and `ask`, and
an unknown mode loads as `ask-site`. `trustedSites` holds bare domains,
each covering its subdomains; an entry written another way, such as
`Blender.org`, `www.blender.org` or `https://blender.org/`, counts as
`blender.org`. A config file without an `[ai]` section keeps the
defaults (discovery off).

## Where Discover shows

The **Discover** destination shows in the sidebar, the rail and the
phone's bottom bar wherever discovery can run (desktop and Android), set
up or not. Until a provider is usable it shows a setup page: what
Discover does, example searches and a button per provider, which
switches discovery on with that provider and opens the Discover settings
to finish.

Searches also start from outside the page:

- the add sheet, with text that holds no link;
- the command palette;
- the phone's search;
- a failed download's **Find another source**.

Each of these starts a new chat. One started before Discover is set up
waits on the setup page (*Your search for … runs as soon as Discover is
set up*) and runs once setup is done. The downloads launchpad offers
Discover too (**Or describe what you want**). ⌘E (Ctrl+E) shows
Discover as you left it, ready to type.

The page's own buttons:

- **With a sidebar or rail:** the page header holds **History**, a ⋯
  menu with **Delete this search** and **Clear history**, **New
  search**, and the model Discover uses, such as "Anthropic ·
  claude-opus-5", which opens its settings.
- **Phone layout:** the top bar holds **History** and **New search** in
  place of the search button.

Switching Discover off, or clearing what it needs such as its key,
stops the searches that run or wait to start, as **Stop** does, and
declines the questions they wait on. Choosing another model or provider
lets them finish on the one they started with.

While Discover is switched off or not set up, saved chats stay in the
history, which **History** still opens (in the header, or the top bar
in the phone layout). A chat opened from it reads as it was left:

- *Set up Discover to continue*, with **Discover settings**, takes the
  place of the message field.
- **Try again** and **Search the whole web** are not offered.
- Its results can still be added and discarded.

**New search** returns to the setup page.

## Chats

Type what you want in the message field at the bottom of the Discover
page and press **Find**, or **Send** for a follow-up. Enter sends and
Shift+Enter starts a new line, and Tab moves on to **Limit to
websites**. On phones and tablets, Enter starts a new line, the button
sends and the keyboard goes away. On a computer, the *Comfortable*
density in Settings → General only hides the key hints; Enter still
sends. The field grows to
six lines, and each chat keeps what you have not sent yet while the app
runs. The **Limit to websites** chip under the message limits the next
one ([below](#limiting-discovery-to-websites)). While the chat searches,
the button turns into **Stop** (Esc), and the next message can be sent
once the search ends.

An empty chat shows what Discover does and example searches, which run
when clicked. Each message becomes a turn in the thread:

1. your message, with the websites it was limited to;
2. the agent's steps, as it reports them, the running one with what the
   agent says about it (four lines, then **Show more**), and **Details**,
   which lists every step with all it said, such as the agent's plan;
3. page access questions ([answering](#answering-in-the-apps)), and a
   line summing up your answers;
4. the agent's short summary, folded to six lines with **Show more**;
5. its results, such as "4 downloads", with a box that selects them all
   and **Discard all**.

The thread follows the newest turn while you read at its end. Scroll up
and it stays where you are; scroll back down and it follows again.

- **Follow-ups.** Later messages refine the search, such as "only the
  LTS release". Each runs the agent again, with its own budgets, and
  sends it the conversation: the earlier requests and the sites they
  were limited to, the results of the turns that finished with their
  details (file name, size, source page, description), without the
  ones you discarded, and which turns failed or were stopped. A long
  chat sends its first turn and the latest five. A follow-up that only
  narrows, picks from or explains the earlier results, such as "just the
  latest version", is answered from them in a *Refining* step, without
  searching or opening pages again. Others search only for what the
  earlier results lack. Either way the agent answers with the complete
  list for the latest request and re-checks only links it has not
  checked in the chat; a result it returns again keeps the size, type
  and source page shown before.
- **Summary.** The agent writes one or two plain sentences above its
  results. When it finds nothing it can recommend, such as for a request
  for pirated copies, the summary says why.
- **Adding.** Click results to select them. The add bar above the
  message field works on everything selected in the chat, so you can
  pick from several turns. It reads, for example, "2 selected · 1.2 GB ·
  1 from earlier", where *from earlier* counts the selected results that
  are not among the newest turn's. **Add now** adds them, and **Review &
  add** opens them in the add sheet first. With two devices or more, the
  bar also picks the device they go to.
  - On phones the bar shows only while something is selected and the
    keyboard is down; **Review & add** and the device are under its ⋮
    (*More ways to add*).
  - Opening another chat clears the selection.
- **Try again** runs the chat's newest turn again after it failed, which
  the turn explains next to **Discover settings**, or was stopped. When a
  turn limited to websites finds nothing (*No downloads found*), **Search
  the whole web** runs it again without them. Both show on the newest
  turn only, while the chat is not searching.
- **Discarding.** Discard a result, or every result of a turn, to hide
  it in every turn of the chat and take it out of the selection.
  - A result's ✕ shows on hover or keyboard focus, and always on touch,
    where swiping a result toward the start discards it too. ⌫ (Delete)
    discards the focused result.
  - The agent is told never to suggest it again, and later results
    leave it out even if it does.
  - A turn shows how many results it discarded, with **Restore**, and
    the toast's **Undo** or ⌘Z (Ctrl+Z) takes a discard back.
- **Several at once.** Each chat runs one turn at a time, and at most
  three searches run at once across chats; the next one waits (*Waiting
  for another search to finish*) and starts when one finishes. **Stop**
  ends the chat's turn and keeps the steps it reported.

## History

Every chat is saved to the history, newest first by when it last ran,
under *Today*, *Yesterday* and *Earlier*. A row names the chat by its
first message until the agent answers it with a short title, such as
"Blender 4.2 LTS for Apple silicon", in the language you wrote in;
follow-ups never rename it, and downloads added from the chat still
record the first message as their search. Under the name, the row says
what the chat is doing:

- *Needs your OK*, *Searching…*, *Waiting to start*, *Failed* or
  *Stopped*;
- once it is done, how many results it shows and when it last ran, such
  as "4 results · 14:02" (a date under *Earlier*), or "No results".

From six chats on, a **Search history** field above them filters them by
their titles and messages.

- **Open** a row to read the chat or continue it with a follow-up.
- **New search** (⇧⌘E) shows an empty chat; the one shown stays in the
  history.
- **Delete** a chat with its trash, which shows on hover or keyboard
  focus (always on touch), with ⌫ (Delete) on its row, or from the ⋯
  menu for the chat shown. Deleting a running chat stops it.
- **Clear history** deletes every chat that is not running.

Deleting and clearing can be undone from the toast or with ⌘Z (Ctrl+Z).
The history keeps the 50 most recent chats; older ones are dropped, but
never one that is running or shown. A search that was running when the
app quit shows as stopped.

Where it shows:

- **Wide pages:** docked beside the chat. **History** in the page
  header (⇧⌘H) shows or hides it, and Ketch remembers the choice
  (`discoverHistory` under `[ui]`, on by default). While there are no
  chats it stays hidden, unless you ask for it.
- **Narrower pages with a sidebar or rail:** over the chat, from
  **History**. Esc, its ✕, picking a chat, **New search** or **Clear
  history** closes it.
- **Phone layout:** a sheet from **History** in the top bar. Back closes
  it, as picking, deleting or clearing does, so the toast with Undo
  shows.

The history is a JSON file, `discover-history.json`:

- **Desktop:** next to `config.toml`, in the directory the CLI reads
  its config from ([locations](../cli/README.md#config-file-locations)).
- **Android:** in the app's private files. It is left out of backups
  and of transfers to a new device.

It holds each chat's messages and the sites they were limited to, the
agent's last 30 steps per turn, its summaries and results, the results
you discarded and a note of the sites you allowed or denied. Links in
the steps are redacted as in the logs (passwords and token-like query
values masked); result links are kept as they are, so you can still add
them.
A failed turn keeps only the short explanation, such as *The AI provider
rejected the API token (HTTP 401)*, never the provider's reply, which may
echo a key. Page access answers themselves are not saved, so a chat
reopened after a restart asks again.

Ketch writes the file through a temporary file, so an interrupted write
cannot leave it half written. A file it cannot read, or one written by a
newer version, is moved to `discover-history.json.bak` and the history
starts empty. To remove the history, use **Clear history**, or delete
the file while Ketch is closed.

## Keyboard shortcuts

These keys work while Discover shows, and the shortcut sheet (⌘/)
lists them under *Discover*. Those of the chat (↩, ⇧↩, ⌘↩, ⌘⌫ and Esc
stopping a search) and ⌫ need the keyboard on the page; ⇧⌘E, ⇧⌘H and
Esc closing the history work wherever it is, as on the setup page:

| macOS | Windows and Linux | Does |
|-------|-------------------|------|
| ↩ | Enter | Sends the message. |
| ⇧↩ | Shift+Enter | Starts a new line. |
| Esc | Esc | Closes the history floating over the chat; otherwise stops the chat's search. |
| ⌘↩ | Ctrl+Enter | Answers the chat's waiting question with its first button, **Allow** or **Allow once**. |
| ⌘⌫ | Ctrl+Backspace | Denies the chat's waiting question. |
| ⌫ | Delete | Discards the focused result, or deletes the focused chat in the history. |
| ⇧⌘E | Ctrl+Shift+E | Shows an empty chat (**New search**). |
| ⇧⌘H | Ctrl+Shift+H | Shows or hides the history. |

Two keys of the whole window work with Discover too:

- ⌘E (Ctrl+E) shows Discover from anywhere in the window and puts the
  keyboard in the message field.
- ⌘Z (Ctrl+Z) undoes the last change that offered Undo, such as a
  discard, a deleted chat or **Clear history**.

While a text field has the cursor, ⌫ and ⌘Z edit the text instead, and
so does ⌘⌫ once something is typed in it. ↩ and ⇧↩ are left to an input
method while it composes text, and on phones and tablets Enter starts a
new line.

## Command line

`ketch ai-discover "<request>"` runs one search with the `[ai]` settings
of the default config file. It has no follow-ups or history. Only the
summary and the results go to stdout; the version banner, the model and
query it uses, the agent's steps as they happen (one line each), the
questions and errors go to stderr, so `> results.txt` keeps just the
results.

It follows `[ai.access]`: when that says to ask, it asks on the terminal
before the agent opens a website:

```text
Allow Discover to open www.blender.org? https://www.blender.org/download/
  Discover says: Read the Blender download page
[y] allow  [s] allow blender.org for this run  [a] allow all  [n] deny:
```

- `y` allows the site for the rest of the run with `ask-site`, and only
  this request with `ask`; `s` allows the site and its subdomains for
  the run, and `a` every website.
- `n`, an empty or unknown answer, or the end of input denies. After
  the end of input (Ctrl+D), every later request is denied without
  asking.
- `--yes` (`-y`) allows every website for the run, and `--sites` limits
  the run to websites, which never asks.

Answers last for the run; the CLI never writes `config.toml`. It asks on
the controlling terminal (`/dev/tty`), so the questions still reach you
when stdin or stdout is redirected. On Windows it asks on the console,
and only while stdin and stdout are both the console. Without a
terminal, such as under cron or in CI, or with redirected streams on
Windows, a run that may ask stops before it starts, with a hint and exit
status 1: pass `--yes` or `--sites`, or set `mode = "allow"`. A run that
fails exits with 1 too, and invalid arguments with 2. See
[the CLI guide](../cli/README.md#page-access).

## Platform support

Discovery runs in-process on the desktop and Android apps and in the
CLI. Chats and their history are kept by the desktop and Android apps;
the CLI runs single searches. iOS and the web app have no local engine,
so their settings page reports AI discovery as unavailable. The daemon's
REST API has no discovery endpoints: with a remote instance selected,
the desktop and Android apps still run discovery on the device, and the
candidates you pick are downloaded by that instance. See
[ai/discover/README.md](../ai/discover/README.md) for the engine
internals.
