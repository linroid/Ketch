# Speed modes on every device

**Status:** Implemented · 2026-10-24

Full speed, the Slow lane and Auto with weekly rules used to be applied by an app-side
`SpeedModeController` to the embedded engine alone, from `[speed]` of the app's `config.toml`.
The Speed settings page offered remote devices only a plain speed limit ("No Slow lane or Auto for
it yet."). A phone that sleeps cannot drive a NAS's limit, so the schedule must run on the device
that does the downloads.

Paths: `api/` = `library/api/src/commonMain/kotlin/com/linroid/ketch/api/`, `core/` =
`library/core/src/commonMain/kotlin/com/linroid/ketch/core/`, `server/` =
`library/server/src/main/kotlin/com/linroid/ketch/server/`, `remote/` =
`library/remote/src/commonMain/kotlin/com/linroid/ketch/remote/`, `app/` =
`app/shared/src/commonMain/kotlin/com/linroid/ketch/app/`.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | The engine runs the schedule: `Ketch(speedModes = SpeedModeOptions(...))` builds a `SpeedModeKeeper` (`core/speed/`) that evaluates the rules (`SpeedScheduler`, moved from the app) and applies the limit, and records the observed peak from its own tasks' speed. | Every host that runs downloads (`ketch server`, the desktop, Android and iOS apps) gets it with one argument, and every server over such an engine, the apps' own included, serves it with no wiring of its own. |
| D2 | `SpeedModes` (`api/`, a `ControlKey` controller: `state()`, `update(SpeedModeChange)`), `SpeedModeState`, `Interest.SpeedMode` and `KetchFeatures.SPEED_MODES` (`speed.modes`). `SpeedLimitMode`, `SpeedRule`, `Weekday` and `SpeedSettings` move from `config` to `api`, unchanged on disk. | The controller pattern of `CountryDatabase` and `TorrentController`; the interest rides the event stream, so remote devices' modes reach the Pulse bar and the device list without polling. One type for the wire and `[speed]`. |
| D3 | `DownloadConfig.speedLimit` stays the limit in effect, as before; `SpeedSettings.standard` is the standing cap, now always current: at full speed every limit set is the cap, and outside it `updateConfig` takes a `speedLimit` other than the one in effect as the new cap, which the mode then holds to. | Older clients and the Pulse bar keep reading the limit in effect; a client that writes back the config it read changes nothing, and one that sets a limit sets the cap, rather than seeing it undone within a minute. |
| D4 | The engine starts from `config.speedLimit` as the cap; hosts save `[speed]` and `[download] speedLimit = standard` together when the settings change (`SpeedModeOptions.saveSettings`), and the peak to `[ui] observedPeak` (`savePeak`, at most once a minute). | `[download] speedLimit` already held the cap (the apps saved it so), and flags such as `ketch server --speed-limit` keep working. |
| D5 | REST: `GET /api/speed` (`SpeedModeState`), `PUT /api/speed` (`SpeedModeChange`, fields left out unchanged; `slowLane` `Unlimited` follows the suggestion), served when `controller(SpeedModes)` is not `null`. `RemoteKetch.controller(SpeedModes)` fails closed against servers without `speed.modes`. | One change can switch the mode and set the slow lane speed at once, as the popover's limit does. |
| D6 | App: `SpeedModeController` (`app/state/`) becomes the view of one device's `SpeedModes`, its state following `Interest.SpeedMode`. `InstanceManager` makes one for the embedded engine and one for each connected remote client, which stays empty against servers without the feature; `AppState.speedModeFor(entry)` returns it once it has a state. The Speed page, the Pulse pill and popover, device actions and `DevicePresence.speedMode` use it for any device. | The UI code stays as it was; remote devices get every speed mode control the embedded one has. |

## Out of scope

- `ketch mcp --standalone` runs without speed modes; its engine follows `[download] speedLimit`
  only.
- Speed modes are not exposed as MCP tools.
