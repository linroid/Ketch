# Automation hooks

Hooks run a command or post a webhook when a download is added, starts, completes, fails, is
canceled or is removed. Use them to move finished files, update a media library, send a chat
message or start the next step of a script.

## Where hooks run

Hooks are `[[hooks]]` tables of `config.toml`, read once at launch by the process that runs this
device's downloads:

| Process | Runs hooks |
| --- | --- |
| `ketch server`, also in the Docker image | Yes |
| `ketch mcp --standalone` | Yes |
| The desktop app, while it runs the downloads itself | Yes |
| The desktop app while `ketch server` runs the downloads | No: the server runs them |
| `ketch <url>`, `ketch add` and the other CLI commands | No |
| Android, iOS and the web app | No |

Only the process holding the config directory's `instance.lock` runs the downloads, so a hook
never runs twice for one event. Hooks are read when that process starts: restart it after editing
them. Quit the desktop app before editing `config.toml` by hand, as it writes the file again when
its settings change.

Android does not run hooks yet: a background app cannot be relied on to run commands, and
webhooks from the phone are a possible follow-up. To run hooks for a phone's downloads, send them
to a `ketch server` instead.

## Security

Hooks run programs on the machine with the rights of the Ketch process, so only the local
`config.toml` sets them. No API changes them: `PUT /api/config` takes download settings only,
and neither the apps nor the REST API, MCP or the browser extension read or write hooks. Keep
`config.toml` writable by its owner only; it also holds the server's `apiToken`. Webhook secrets
and headers are stored in plain text there, like the other tokens.

Commands never go through a shell. `command` is the program and its arguments, as a list, and
nothing about the download is put into them: the payload reaches the program on its standard input
and in environment variables, so a file named `a.zip; rm -rf ~` stays a file name. To use shell
features, run a script of your own, which then must quote the variables it uses.

Download URLs pass through the same redaction as the logs: passwords in the URL and the values of
query parameters are replaced with `***`. Logs name a failing hook by its `name`, its program or
its webhook's host, never the webhook URL, its headers, the secret or the arguments.

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

A hook runs its `command` when it has one, else posts to its `webhook`. One with neither, with no
event Ketch knows, or with a webhook that is not an `http` or `https` URL is left out with a
warning in the log. Event names Ketch does not know, such as ones a later version adds, are
ignored, and a `timeoutSeconds` that is not a positive number is 30.

### Events

| Event | When |
| --- | --- |
| `added` | A download is added while Ketch runs. Downloads restored at launch are not added again. |
| `started` | A download starts transferring: after it is added, resumed or restored, or retried after it failed. Pausing and resuming starts it again. |
| `completed` | A download finishes, once per completion. A torrent that gains files and finishes again completes again. |
| `failed` | A download stops with an error. |
| `canceled` | A download is canceled, which deletes its partial file. |
| `removed` | A download is removed from the list. |

### Filters

A hook with filters runs only for downloads that pass all of them:

- `origins`: where the download was added from, the `ketch.origin` request property: `browser`
  (the extension), `discover`, `agent` (MCP), `app` or `cli`. Downloads that name no origin never
  pass.
- `categories`: the folder of the [category](../cli/README.md#downloadcategories) the download's
  file matches, such as `Video`, ignoring case.
- `hosts`: the URL's host or its subdomains: `example.com` covers `cdn.example.com`. Magnets
  never pass.

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

`state` is the download's state when the event happened (`scheduled`, `queued`, `downloading`,
`paused`, `completed`, `failed` or `canceled`), `downloadedBytes` is set while it runs or is
paused, and a failed download has an `error`:

```json
"error": { "kind": "http", "code": 404, "message": "HTTP error 404" }
```

`kind` is one of `network`, `http`, `disk`, `unsupported`, `file_changed`,
`corrupt_resume_state`, `canceled`, `source`, `auth_failed`, `checksum_mismatch` and `unknown`.

## Commands

A command gets the JSON on its standard input and these environment variables, besides the ones
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

What a command prints is discarded: write to a log file of your own to debug one. A command that
exits with a status other than 0 is logged as failed. One still running after `timeoutSeconds` is
stopped, with the processes it started, and logged. Commands still running when Ketch quits keep
running.

For example, a script that files finished videos away:

```sh
#!/bin/sh
# /usr/local/bin/ketch-done: run by a "completed" hook
[ "$KETCH_CATEGORY" = "Video" ] || exit 0
mv -- "$KETCH_OUTPUT_PATH" /srv/media/movies/
```

Or one that reads the JSON with `jq`:

```sh
#!/bin/sh
payload=$(cat)
echo "$(echo "$payload" | jq -r .fileName) finished" >> "$HOME/ketch-hooks.log"
```

On Windows, name the interpreter: `command = ["powershell", "-File", "C:\\Scripts\\done.ps1"]`.

## Webhooks

A webhook gets the JSON in a `POST` with these headers, besides the ones the hook lists:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `User-Agent` | `Ketch/<version>` |
| `X-Ketch-Event` | The event, such as `completed` |
| `X-Ketch-Delivery` | `deliveryId`, the same on every attempt |
| `X-Ketch-Signature` | `sha256=` and the hex HMAC-SHA256 of the body, keyed with `secret`; only with a `secret` |

A request has `timeoutSeconds` to answer. Any 2xx answer delivers the event. A request that fails
on the way, times out, or is answered 408, 429 or 5xx is sent again after 2 and then 10 seconds;
other answers, redirects included, are not retried. Webhooks connect directly, without the
download proxy.

To check a signature, compute the HMAC of the raw body before parsing it, for example in Python:

```python
import hashlib, hmac

def verify(secret: bytes, body: bytes, header: str) -> bool:
    expected = "sha256=" + hmac.new(secret, body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)
```

## Delivery

Hooks run in the background and never hold up a download. At most four run at once, and up to
256 events wait for them; events beyond that are dropped with a warning. Events of one download
are queued in the order they happen, but as hooks run side by side, a slow hook of one event may
finish after a quick hook of the next. Events of a hook are not saved: those that have not run
when Ketch quits are lost.

Ketch logs a hook that fails at warn, under the `Hooks` tag ([logging](logging.md)), and one
that runs at debug.
