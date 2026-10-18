# Automation hooks

Hooks run a command or post a webhook when a download is added, starts, completes, fails, is
canceled or is removed. Use them to move finished files, update a media library, send a chat
message or start the next step of a script. This page is for people who run Ketch on a computer
or server they control and edit its `config.toml`.

## Quick start

Add a `[[hooks]]` table to [`config.toml`](../cli/README.md#config-file-locations) and restart
Ketch. This one runs a script whenever a download completes:

```toml
[[hooks]]
events = ["completed"]
command = ["/usr/local/bin/ketch-done"]
```

The script finds the download in environment variables:

```sh
#!/bin/sh
# /usr/local/bin/ketch-done
echo "$KETCH_FILE_NAME saved to $KETCH_OUTPUT_PATH" >> "$HOME/ketch-hooks.log"
```

To post to a web service instead, give a `webhook` URL:

```toml
[[hooks]]
events = ["completed", "failed"]
webhook = "https://hooks.example.com/ketch"
secret = "change-me"
```

## Where hooks run

Hooks are read once, at launch, by the process that runs this device's downloads:

| Process | Runs hooks |
| --- | --- |
| `ketch server`, also in the [Docker image](docker.md) | Yes |
| `ketch mcp --standalone` | Yes |
| The desktop app, while it runs the downloads itself | Yes |
| The desktop app while `ketch server` runs the downloads | No: the server runs them |
| `ketch <url>`, `ketch add` and the other CLI commands | No |
| Android, iOS and the web app | No |

Only one process runs a device's downloads at a time (the one holding the config folder's
`instance.lock`), so a hook never runs twice for one event.

- **After editing hooks**, restart that process: hooks are only read when it starts.
- **Quit the desktop app** before editing `config.toml` by hand, as it writes the file again when
  its settings change.
- **Phones** do not run hooks yet: a background app cannot be relied on to run commands, and
  webhooks from the phone are a possible follow-up. To run hooks for a phone's downloads, send
  them to a `ketch server` instead.

## Configuration

```toml
[[hooks]]
name = "Move to library"                  # Optional, for the logs
events = ["completed"]                    # One or more events, below
command = ["/usr/local/bin/ketch-done", "--library", "/srv/media"]
workingDirectory = "/srv/media"           # Optional; Ketch's own otherwise
timeoutSeconds = 120                      # Optional, 30 by default
categories = ["Video"]                    # Optional filters, below

[[hooks]]
name = "Chat"
events = ["completed", "failed"]
webhook = "https://hooks.example.com/ketch"
headers = { Authorization = "Bearer change-me" }
secret = "another-secret"                 # Optional: signs the body
origins = ["browser", "agent"]
hosts = ["example.com"]
enabled = true                            # false keeps a hook without running it
```

| Key | Default | Description |
| --- | --- | --- |
| `events` | `[]` | The [events](#events) the hook runs on |
| `command` | `[]` | Program and its arguments, as a list; never run through a shell |
| `workingDirectory` | Ketch's own | Folder the command runs in |
| `webhook` | none | `http` or `https` URL the event is posted to, used when there is no `command` |
| `headers` | `{}` | Extra webhook request headers |
| `secret` | none | Key that [signs](#checking-a-signature) the webhook body |
| `timeoutSeconds` | `30` | How long a command may run, or a webhook request take |
| `origins`, `categories`, `hosts` | `[]` | [Filters](#filters); every download passes an empty one |
| `name` | none | Label for the logs |
| `enabled` | `true` | `false` keeps the hook without running it |

A hook runs its `command` when it has one, and otherwise posts to its `webhook`; one with both
runs the command and logs a warning. A hook is left out, with a warning in the log, when it has
neither, names no event Ketch knows, or has a webhook that is not an `http` or `https` URL. Event
names Ketch does not know, such as ones a later version adds, are ignored, and a `timeoutSeconds`
that is not a positive number counts as 30.

### Events

| Event | When |
| --- | --- |
| `added` | A download is added while Ketch runs. Downloads restored at launch are not added again. |
| `started` | A download starts transferring: after it is added, resumed or restored, or retried after it failed. Pausing and resuming starts it again. |
| `completed` | A download finishes, once per completion. A torrent that gains files and finishes again completes again. |
| `failed` | A download stops with an error. |
| `canceled` | A download is canceled, which deletes its partial file (a torrent keeps its files until it is removed). |
| `removed` | A download is removed from the list. |

### Filters

A hook with filters runs only for downloads that pass all of them. Names are compared ignoring
case.

- `origins`: where the download was added from: `browser` (the extension), `discover`, `agent`
  (MCP), `app` or `cli`. Downloads that name no origin never pass.
- `categories`: the folder of the [category](../cli/README.md#downloadcategories) the download's
  file matches, such as `Video`.
- `hosts`: the URL's host or its subdomains: `example.com` (or `*.example.com`) covers
  `cdn.example.com`. Magnets never pass.

## Payload

Every hook gets the event as JSON. Fields that do not apply are left out.

```json
{
  "event": "completed",
  "timestamp": "2026-10-01T12:00:05Z",
  "deliveryId": "0f6c7d7e-6b1f-4c69-9a3e-3d4bdbbf2a31",
  "taskId": "b3c1a3a4-0d6e-4a0c-8f5e-4cbe4a7f2c10",
  "state": "completed",
  "url": "https://user:***@cdn.example.com/movie.mkv?token=***",
  "host": "cdn.example.com",
  "fileName": "movie.mkv",
  "outputPath": "/srv/downloads/Video/movie.mkv",
  "totalBytes": 1468006400,
  "downloadTimeMillis": 95000,
  "createdAt": "2026-10-01T11:58:30Z",
  "completedAt": "2026-10-01T12:00:05Z",
  "origin": "browser",
  "category": "Video",
  "sourceType": "http"
}
```

- `state` is the download's state when the event happened: `scheduled`, `queued`,
  `downloading`, `paused`, `completed`, `failed` or `canceled`.
- `downloadedBytes` is set while the download runs or is paused.
- `url` is redacted like the logs: passwords in the URL and the values of query parameters are
  replaced with `***`.
- A failed download has an `error`:

  ```json
  "error": { "kind": "http", "code": 404, "message": "HTTP error 404" }
  ```

  `kind` is one of `network`, `http`, `disk`, `unsupported`, `file_changed`,
  `corrupt_resume_state`, `canceled`, `source`, `auth_failed`, `checksum_mismatch` and `unknown`.
  `code` is only set for `http`.

## Commands

A command gets the JSON on its standard input, and these environment variables besides the ones
Ketch has, each only when it is set:

| Variable | Field |
| --- | --- |
| `KETCH_EVENT` | `event` |
| `KETCH_TIMESTAMP` | `timestamp` |
| `KETCH_DELIVERY_ID` | `deliveryId` |
| `KETCH_TASK_ID` | `taskId` |
| `KETCH_STATE` | `state` |
| `KETCH_URL` | `url` (redacted) |
| `KETCH_HOST` | `host` |
| `KETCH_FILE_NAME` | `fileName` |
| `KETCH_OUTPUT_PATH` | `outputPath` |
| `KETCH_TOTAL_BYTES` | `totalBytes` |
| `KETCH_DOWNLOADED_BYTES` | `downloadedBytes` |
| `KETCH_DOWNLOAD_TIME_MS` | `downloadTimeMillis` |
| `KETCH_CREATED_AT` | `createdAt` |
| `KETCH_COMPLETED_AT` | `completedAt` |
| `KETCH_ORIGIN` | `origin` |
| `KETCH_CATEGORY` | `category` |
| `KETCH_SOURCE_TYPE` | `sourceType` |
| `KETCH_ERROR_KIND` | `error.kind` |
| `KETCH_ERROR_CODE` | `error.code` |
| `KETCH_ERROR_MESSAGE` | `error.message` |

- What a command prints is discarded: write to a log file of your own to debug one.
- A command that exits with a status other than 0 is logged as failed.
- A command still running after `timeoutSeconds` is stopped, along with the processes it
  started, and logged.
- Commands still running when Ketch quits keep running.

A script that files finished videos away:

```sh
#!/bin/sh
# /usr/local/bin/ketch-done: run by a "completed" hook
[ "$KETCH_CATEGORY" = "Video" ] || exit 0
mv -- "$KETCH_OUTPUT_PATH" /srv/media/movies/
```

One that reads the JSON with `jq`:

```sh
#!/bin/sh
payload=$(cat)
echo "$(echo "$payload" | jq -r .fileName) finished" >> "$HOME/ketch-hooks.log"
```

On Windows, name the interpreter:

```toml
command = ["powershell", "-File", "C:\\Scripts\\done.ps1"]
```

## Webhooks

A webhook gets the JSON in a `POST` with these headers, besides the ones the hook lists:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `User-Agent` | `Ketch/<version>` |
| `X-Ketch-Event` | The event, such as `completed` |
| `X-Ketch-Delivery` | `deliveryId`, the same on every attempt |
| `X-Ketch-Signature` | `sha256=` and the hex HMAC-SHA256 of the body, keyed with `secret`; only with a `secret` |

- Each request has `timeoutSeconds` to answer, and any 2xx answer delivers the event.
- A request that fails on the way, times out, or is answered 408, 429 or 5xx is sent again after
  2 and then 10 seconds. Other answers, redirects included, are not retried.
- Webhooks connect directly, without the download proxy.

### Checking a signature

Compute the HMAC of the raw body before parsing it, for example in Python:

```python
import hashlib, hmac

def verify(secret: bytes, body: bytes, header: str) -> bool:
    expected = "sha256=" + hmac.new(secret, body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)
```

Use `X-Ketch-Delivery` to drop a delivery you have already handled.

## Delivery

Hooks run in the background and never hold up a download.

- At most four hooks run at once, and up to 256 events wait for them; events beyond that are
  dropped with a warning.
- Events of one download are queued in the order they happen, but as hooks run side by side, a
  slow hook for one event may finish after a quick hook for the next.
- Events are not saved: those whose hooks have not run when Ketch quits are lost.

Ketch logs a hook that fails at warn level, under the `Hooks` tag ([logging](logging.md)), and one
that runs at debug level.

## Security

Hooks run programs on the machine with the rights of the Ketch process, so only the local
`config.toml` sets them.

- **No API changes hooks.** `PUT /api/config` takes download settings only, and neither the apps
  nor the REST API, MCP or the browser extension read or write hooks.
- **Protect `config.toml`.** Keep it writable by its owner only; it also holds the server's
  `apiToken`. Webhook secrets and headers are stored in it in plain text, like the other tokens.
- **Commands never go through a shell.** `command` is the program and its arguments, and nothing
  about the download is put into them: the payload reaches the program on its standard input and
  in environment variables, so a file named `a.zip; rm -rf ~` stays a file name. To use shell
  features, run a script of your own, which must then quote the variables it uses.
- **Secrets stay out of logs.** Download URLs are redacted, and logs name a failing hook by its
  `name`, its program or its webhook's host, never the webhook URL, its headers, the secret or
  the arguments.

## See also

- [CLI configuration reference](../cli/README.md#hooks), including category folders
- [Docker](docker.md), where `/config/config.toml` holds the hooks
