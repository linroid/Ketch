# Ketch UX Redesign: "Lanes & Fleet"

Status: approved design spec, the single source of truth for the app redesign.
Date: 2026-10-01. Scope: `app/shared`, `app/desktop`, `app/android`, `app/ios`, `app/web`, plus the
library API additions listed in Wave 5 and Wave 6.

Paths below are relative to the repository root. `app/shared/...` is short for
`app/shared/src/commonMain/kotlin/com/linroid/ketch/app/...` unless another source set is named.
`file:line` evidence refers to commit `4ed6981f` (the review baseline).

> **Prerequisite for every package.** The `ux-redesign` worktree is based on `4ed6981f` (#304).
> Package W0-CONTRACTS (§7.2) rebases it onto `main` at `5b5d770a` or later before Wave 1 starts.
> `main` already contains:
> - #305: `DownloadState.Completed.totalBytes: Long?` and `downloadTime: Duration?`, plus
>   `util/DownloadSummary.kt` (`transferSummary()`), which formats size, time spent and average
>   speed for finished rows. The list, inspector, notifications and Devices work below
>   **consume these fields and that helper** and must not re-implement them.
> - #306: `ui/list/ProgressSection.kt` deleted.
>
> Line numbers for files changed by #305 (`DownloadListItem.kt`, `DownloadExpandedPanel.kt`,
> `FormatUtils.kt`) shift by a few lines after the rebase. The findings still apply.

---

## Contents

1. Summary
2. Review: scores and prioritized findings
3. Design system
4. Screens
5. Interaction
6. Platform adaptation
7. Implementation plan (waves and work packages)
8. Out of scope and open questions

---

## 1. Summary

### 1.1 The problem, in five bullets

1. **Ketch hides what only Ketch can do.**
   - Live multi-connection segments show only inside an expanded accordion, the `KetchSegmentBar`
     component is dead code, and segment "health" is hard-coded to `1f`.
   - Controlling many devices (NAS, laptop, phone) looks like a connection setting: a small pill
     opens an `AlertDialog`, every device you are not viewing shows "Not connected", and each
     switch tears down the remote client (`InstanceManager.kt:107-113`).
   - AI Discover is invisible until it is configured.
2. **Common jobs take far too many steps.**
   - Opening a finished file is impossible in the app (about 8 steps through Finder).
   - Adding one link takes 3 actions plus a 500 ms wait, and adding 5 links takes about 20.
   - Throttling for a video call takes 5 clicks, and the same again to undo.
   - Changing connections on a running task is impossible.
   - There is no keyboard model (the only shortcut is `⌘,`), no multi-select, no context menu,
     and no table.
3. **Trust is broken in places.**
   - Per-task commands run unguarded: `RemoteDownloadTask.reschedule` always throws into the
     shell scope.
   - Retry on canceled rows silently does nothing.
   - "Clear completed" is an irreversible icon placed next to Add.
   - "Pause all" lets queued tasks start.
   - Closing the window quits the app and kills every download.
   - The Android foreground service never sees state changes, so background downloads can be
     killed.
   - Errors read "HTTP error 403: null".
4. **The look is flat, inconsistent and touch-sized on desktop.**
   - Design tokens exist but have zero uses: 517 dp literals and 15 different radii.
   - Surfaces are grey on grey with no depth (contrast 1.05-1.21:1).
   - Material baseline pink leaks into the UI, and two accents fail AA contrast.
   - Fonts are system defaults.
   - List rows are 80 dp cards, so only about 5.7 rows fit at the default window size.
5. **Nothing reaches the user outside the window.** There is no tray, menu bar, notifications,
   Dock or taskbar progress, magnet handler or share target. iOS stops in the background without
   saying so. Phones lose about 37% of the screen to stacked chrome.

### 1.2 Chosen direction

**"Lanes & Fleet"** is the *Signature Ketch* direction, used for identity, information architecture
and fleet gestures. It is built on the *Helm* direction's efficiency core and rendering
discipline. *Calm Glass* contributes its actionable-surface ideas.

Why this combination:

- **Signature Ketch** has the strongest brand story, and it maps one-to-one onto real engine
  capability:
  - *Lanes*: every connection is a visible lane with a moving write head, and pressing `+` splits
    it live.
  - *Fleet*: every device is a live sidebar row, a drag or drop target, and a target for adds.

  It keeps **Downloads as home**, so no click is added to common jobs. Both judges rated it the
  best fit for Ketch's strengths.
- **Helm** gives the parts a power user needs:
  - a dense table as the default desktop view (40 dp rows, about 15 rows at 1280x800);
  - one `KetchCommands` registry that drives shortcuts, menus, the tray, `⌘K`, tooltips and the
    cheat sheet;
  - global status in a **Pulse bar inside the content card**, so it stays visible when the sidebar
    collapses;
  - **no blur under live data**;
  - concrete performance budgets.
- **Calm Glass** gives:
  - "every surface carries its next action";
  - the generated status sentence;
  - deferred-commit Undo;
  - the Remove dialog copy rules;
  - the completion sheen;
  - Devices-card actions.

**Rejected** (from the judges):

- a dashboard as the landing page, Arrange mode and tile presets;
- Haze blur over any scrolling or live surface;
- the sidebar-only Pulse card;
- split view per device;
- saved views (deferred);
- the full Gmail-style single-key verb set;
- `⇧L` as "clear limit";
- `⌥⌘↑` promoting a task to Urgent;
- `⌘L` for Downloads;
- automatic revert of a control after 5 s;
- renaming Discover to "Find";
- user-changeable device hues;
- the noise bitmap on the wash;
- placeholder UI for data the API does not expose.

### 1.3 Design principles

1. **Show the engine, not decoration.** Every animation maps to an engine event:
   - a lane splits when the connection count changes;
   - a write head stops when its connection stalls;
   - a sheen crosses the bar when a file completes.

   *Reduce motion* turns all of them into static states.
2. **Devices are places, not settings.**
   - Every device is live in the sidebar, one chord away (`⌘⌥1-9`), and switching never
     reconnects.
   - Any download can be added to, dropped on, or sent to any device without switching the
     window.
   - One word everywhere: **Device**.
3. **Fast path first, dialog second.**
   - `⌘V` adds a link, `Space` pauses, `↩` opens, `⌫` removes.
   - Undo replaces confirmation for anything reversible. Only deleting files asks first.
4. **Finished means openable.** A completed row is a launcher (Open, Show in Finder, drag out),
   never a dead end.
5. **Never a silent failure.** Every error names its device, says why in plain words, and offers
   the one fix that can work.
6. **One action model.** `RowAction` decides what a task can do. Row buttons, hover actions, the
   context menu, the selection bar, `⌘K`, shortcuts, the tray and notifications all read it.
7. **Dense for pointers, roomy for thumbs.**
   - Density follows the **input method**: pointer → Compact, touch → Comfortable.
   - Layout follows **window width**: breakpoints at 600 and 1024 dp. Never the OS name.
8. **Solid surfaces under live data.**
   - The pastel wash lives only on the canvas.
   - Everything that scrolls, or updates every 200 ms, sits on opaque surfaces.
9. **Plain words, nautical pictures.**
   - The ketch motif lives in visuals: the segmented-sail logo, lane illustrations, device
     pennants.
   - It reaches copy in exactly two places: "Slow lane" and the accent names Signal, Harbor,
     Fathom and Beacon.
   - Status, error and button copy stays literal.
10. **Tokens or nothing.** Colors, radii, spacing, type and durations all come from `KetchTheme`.
    A `jvmTest` fails the build on new literals.
11. **Honest data.** If the API does not expose something, the UI hides it or shows an honest
    fallback (for example "Added 14:02" instead of a finish time). It never shows invented values
    or placeholder tiles.

---

## 2. Review

### 2.1 Scores by dimension (current app, 1-10)

| Dimension | Modern | Creative | Efficient | Headline |
|---|---|---|---|---|
| Information architecture, navigation, home | 5 | 3 | 4 | Sidebar is a list of status filters, devices sit in a modal, status is split across 3 places |
| Download list and task rows | 4 | 3 | 2 | 80 dp cards, 4 of 7 states show nothing, finished files are a dead end, no select/keys/menu |
| Task detail and per-task controls | 4 | 3 | 3 | Accordion, fake health, raw errors, connections read-only, unguarded commands |
| Add-download flow and intake | 4 | 2 | 3 | One URL per dialog, no clipboard/keyboard/folder/headers, no OS intake |
| Visual design system | 4 | 3 | 4 | Tokens unused, flat layering, color collisions, AA failures, no bundled fonts |
| Desktop-native integration and feedback | 3 | 2 | 2 | Close quits, no tray/menu/notifications, one overwritable error string |
| Settings, speed, onboarding, cross-platform | 5 | 3 | 3 | Speed 5 clicks deep, fake "/ ∞", typed paths, no first run, OS-based layout |

Judges' scores for the three candidate directions (power-user lens / design-lead lens):

| Direction | Modern | Efficient | Creative | Fit | Feasibility | Coherence |
|---|---|---|---|---|---|---|
| Calm Glass | 9 / 9 | 6 / 7 | 8 / 7 | 8 / 8 | 6 / 6 | 8 / 8 |
| Helm: Pro Console | 7 / 6 | 10 / 10 | 8 / 7 | 9 / 8 | 8 / 9 | 8 / 8 |
| **Signature Ketch: Lanes & Fleet** | 8 / 9 | 8 / 8 | 9 / 9 | 9 / 10 | 7 / 7 | 8 / 9 |

### 2.2 Prioritized findings: every critical and high item

Severity: **C** is critical, **H** is high. The **Fix** column points to the section that
specifies the solution, and **Wave** is where it ships (see §7). Duplicate findings from
different reviewers are merged into one row; all their ids are listed.

| # | Sev | Finding (ids) | Evidence | Fix | Wave |
|---|---|---|---|---|---|
| 1 | C | Per-task commands are unguarded. Remote Schedule always throws into the shell scope, so a network blip fails silently and the UI shows values the server never accepted (`unguarded-task-commands`, `unguarded-task-actions`) | `ScheduleToggle.kt:116-120`; `RemoteDownloadTask.kt:146-153`; `DownloadListItem.kt:144,182,359-367`; `SpeedLimitSlider.kt:201`; `PrioritySelector.kt:83`; `AppShell.kt:84` | `AppState.runTaskCommand` with a SupervisorJob scope and error toasts; `RowAction` capability gating hides Schedule on remote devices (§5.5, §5.6) | W1 logic, W2 wiring |
| 2 | C | Android foreground service never sees task state changes, so background downloads can be killed and the notification is wrong (`android-foreground-stale`) | `KetchService.kt:194-199,219-225`; `Ketch.kt:183,288,364`; `MainActivity.kt:47-49` | `ForegroundPolicy` that combines every embedded task's `state` flow, sampled at 1 s (§6.2) | W1 |
| 3 | C | Closing the window quits Ketch and kills all downloads; there is no tray (`close-quits-no-tray`, `desktop-lifecycle`) | `app/desktop/main.kt:225,210-214`; `NativeMessagingHost.kt:56-63` | Close-to-tray, tray menu, login item, `--background` launches (§6.1) | W2 |
| 4 | C | No system notifications on any platform (`no-system-notifications`, `notifications`) | `KetchService.kt:181-189,231-270`; no notifier in desktop, iOS or web | `ActivityMonitor` + `SystemNotifier` per platform (§5.5, §6) | W1 logic, W2 platforms |
| 5 | C | Finished files cannot be opened, revealed or shared (`finished-file-dead-end`, `open-reveal`) | `DownloadListItem.kt:286,369-374`; `DownloadExpandedPanel.kt:149-158`; only `LogFilesAction.jvm.kt:15-22` opens anything | `FileActions` expect/actual; Open is the primary action on completed rows; double-click/`↩` opens, `⌘↩` reveals (§4.7) | W2 platform, W3 UI |
| 6 | C | 80 dp cards show about 5.7 rows, and 4 of 7 states show no data (`density-and-state-matrix`, `desktop-density`) | `DownloadListItem.kt:108-114,212-213,284-288`; `DownloadList.kt:71`; `KetchButton.kt:78-82,121-125`; `WindowStateStore.kt:29-30` | 36 dp table rows and 56/64 dp two-line rows, with a per-state content matrix and density tokens (§3.7, §4.7) | W1 tokens, W3 list |
| 7 | C | Devices are a hidden modal setting: switching reconnects, devices you are not viewing show "Not connected", names are `host:port` (`device-switcher-first-class`) | `SidebarNavigation.kt:163-223`; `InstanceSelectorSheet.kt:157-190`; `InstanceManager.kt:107-113`; `RemoteKetch.kt:119-121`; `InstanceFactory.kt:59`; `AddRemoteServerDialog.kt:182-185` | `DevicePresence` keep-alive, naming, sidebar DEVICES section, `⌘⌥1-9` (§4.5) | W3 state, W4 UI |
| 8 | C | No fast path for adding: no paste-to-add, no clipboard prefill, no `⌘N`, Enter does not submit (`paste-anywhere-keyboard`, `clipboard-detection`) | `AddDownloadDialog.kt:159-163,213-243,397-421`; `main.kt:229-231` | `⌘V` quick add with Undo, `⌘N` prefill, Enter submits, `LinkParser` (§4.9) | W1 logic, W3 UI |
| 9 | C | One link per dialog; a multi-line paste becomes one invalid URL; several torrents open one dialog each (`batch-intake`) | `AddDownloadDialog.kt:225`; `AppState.kt:149-152,198-233,162-178` | Intake sheet with one row per item, 4 resolves in parallel, ranges and duplicates detected (§4.9) | W3 |
| 10 | C | Design tokens exist but are bypassed (`tokens-bypassed`) | 0 uses of `spacing/shapes/elevation/motion`; 517 dp literals; 15 radii; 79 `MaterialTheme.*` uses; 3 kinds of text field | New theme API, `ketchSurface`, `DesignTokenUsageTest` with a shrinking allowlist (§3.14) | W1, then every wave |
| 11 | C | Global speed limit is 5 clicks deep, and the toolbar readout is hard-coded to "/ ∞" (`speed-mode-control`, `speed-mode-one-click`, `global-pulse`) | `AppShell.kt:334`; `KetchToolbar.kt:54,80-83,115-174`; `DownloadSettings.kt:183-245`; `SpeedStatusBar.kt:25-112` | `PulseState` + `SpeedModeController` (Full speed / Slow lane / Auto), Pulse bar, `⇧⌘L` (§4.4) | W1 logic, W3 UI |
| 12 | C | Download folder is a typed path; phone downloads land in folders users cannot see (`download-folder-picker`, `destination-folder`) | `DownloadSettings.kt:95-110`; `PlatformInfo.android.kt:28`; `Ketch-Info.plist` (no `UIFileSharingEnabled`); `AppState.kt:284-285` | `FilePicker` expect/actual, "Save to" pill with recent folders, SAF Download folder for HTTP/FTP, iOS Files visibility (§4.9, §4.10, §6) | W2 platform, W3 UI |
| 13 | H | Retry on a canceled row does nothing; non-retryable failures are retried blindly (`canceled-retry-noop`) | `DownloadListItem.kt:365-368`; `RealDownloadTask.kt:58-64` | `RowAction.DownloadAgain` → `AppState.redownload` (§5.4) | W1 logic, W2 wiring |
| 14 | H | Row titles read "magnet:", ".", the folder name, or are still percent-encoded (`display-name-wrong`) | `DownloadListItem.kt:87-94`; `FormatUtils.kt:5-12`; `request.js:80-88`; `KetchToolSet.kt:72-76`; `DownloadArgs.kt:89` | `util/DisplayName.kt` precedence chain (§4.7.6) | W1 logic, W2 wiring |
| 15 | H | Failed rows hide the reason; raw messages read "HTTP error 403: null" (`failed-reason-inline`, `error-presentation`, `error-catalog`) | `DownloadListItem.kt:287`; `DownloadExpandedPanel.kt:159-161`; `KetchError.kt:52,21` | `ErrorCopy` catalog with title, hint and recovery; one-line fix in library:api (§5.6) | W1 |
| 16 | H | "Clear completed" removes everything with no confirmation or undo and sits next to Add; "Pause all" misses queued tasks (`destructive-clear-no-undo`, `pause-all-and-undo`, `remove-flow`) | `BatchActionBar.kt:27-35`; `AppState.kt:378-404`; `KetchToolbar.kt:91-104`; `DownloadQueue.kt:214-227`; `RemoveDownloadDialog.kt:50-90` | Deferred-commit Undo, Clear moved into the Done header, pause queued tasks first, `RemoveTasksDialog` with Trash (§5.5, §4.7.9) | W1 logic, W2 wiring, W3 UI |
| 17 | H | Single feedback channel: one overwritable error string, shown only on Downloads (`feedback-model-toasts`, `device-problems-inline`, `remote-connection-feedback`) | `AppState.kt:108,181-475`; `AppShell.kt:392-423`; `AppState.kt:184-195` | `MessageCenter`, `ToastHost`, `DeviceStatusBanner`, Activity popover (§5.5, §4.12) | W1, W2, W3 |
| 18 | H | No table view; no sort; no grouping (`desktop-table-mode`, `sort-and-smart-groups`) | `DownloadList.kt:62-82`; `AppState.kt:98-104` | `DownloadTable` with columns, sort, Smart groups (§4.7) | W3 |
| 19 | H | No multi-select; batch actions only apply to everything (`multi-select-action-bar`) | `BatchActionBar.kt:27-35`; `DownloadListItem.kt:211` | `SelectionState` and a selection bar that replaces the tab row (§5.3) | W1 logic, W3 UI |
| 20 | H | No keyboard model or menu bar; `⌘,` is the only shortcut (`keyboard-navigation`, `keyboard-shortcuts-menubar`, `keyboard-and-palette`) | `main.kt:229-231,254-258`; `KetchSurfaces.kt:136` | `KetchCommands` registry, `ShortcutHost`, macOS `MenuBar`, `⌘/` cheat sheet (§5.1) | W1 registry, W2 menus, W3 shell |
| 21 | H | No right-click menu or hover actions; per-task controls are buried (`context-menu-hover-actions`) | No `ContextMenuArea` or secondary-button handling; `DownloadListItem.kt:377-434`; `TaskSettingsPanel.kt:102-135` | `RowMenu` built from `RowAction`, plus hover actions (§5.4) | W3 |
| 22 | H | Inline accordion instead of an inspector (`adaptive-inspector`) | `DownloadListItem.kt:96,125-168`; `DownloadList.kt:73-81` | `TaskInspector`: docked, overlay or bottom sheet (§4.8) | W3 |
| 23 | H | Segment view is equal-width with fake health; torrent files are shown as "connections" with unbounded height (`connection-map`, `torrent-files-as-segments`, `row-segment-strip`, `lanes-signature`) | `KetchDownloadComponents.kt:42-150`; `DownloadExpandedPanel.kt:95-100`; `TorrentDownloadSource.kt:383-388,490-493` | Byte-accurate `LaneStrip` and `ConnectionMap`, `SegmentRateTracker`, a Files tab for torrents (§4.7.5, §4.8) | W1 logic, W2 component, W3 |
| 24 | H | Live controls hidden behind toggles; connections read-only (`live-controls`) | `DownloadListItem.kt:147-166,377-434`; `TaskSettingsPanel.kt:72-79`; `DownloadTask.kt:68-80` | Inspector Controls card: speed presets, connection stepper, priority, Start (§4.8.3) | W3 |
| 25 | H | Status filters are in the sidebar on wide windows and chips on narrow ones; counts contradict each other (`status-tabs-on-page`) | `SidebarNavigation.kt:90-101`; `DownloadFilters.kt:19-50`; `StatusFilter.kt:14-16`; `AppShell.kt:162-166` | `StatusTabs`: All · Downloading · Waiting · Paused · Done · Failed, `⌘1-6` (§4.7.2) | W3 |
| 26 | H | Adding to another device means switching the whole UI there and back (`download-on-device`, `send-to-device`) | `AppState.kt:271-298,459-479`; `InstanceManager.kt:101-120` | "On:" device chip, Send to device, drop berths (§4.9, §4.5) | W4 |
| 27 | H | Phones spend about 294 dp on chrome; Add sits in the top-right corner (`phone-chrome-stack`, `phone-shell`) | `AppShell.kt:235-239,344-391`; `SpeedStatusBar.kt:41-45` | Phone shell with an Extended FAB, swipe, sheets, collapsing chips (§4.2.4) | W3 |
| 28 | H | Request options the API supports (headers, connections) are missing; options hidden behind toggles (`request-options-pills-advanced`) | `AddDownloadDialog.kt:96-104,323-394`; `AppState.kt:203,282-291` | Option pills, Advanced section, cURL paste (§4.9.3) | W3 |
| 29 | H | Resolve errors are raw; Download stays enabled on error (`actionable-resolve-errors`) | `AddDownloadDialog.kt:419,683-717`; `HttpDownloadSource.kt:35-38`; `SourceResolver.kt:51` | `IntakeProblems` mapping, auto-fixes, Retry and "Add anyway" (§4.9.5) | W1 logic, W3 UI |
| 30 | H | Magnet metadata wait is a bare spinner; clicking Download early grabs every file; the file picker is cramped (`torrent-metadata-and-file-picker`) | `AddDownloadDialog.kt:407-411,455-460,763-864`; `TorrentConfig.kt:26` | Torrent stage: elapsed timer, finish in background, filters, folder tree, extras skipped (§4.9.6) | W3 |
| 31 | H | Links cannot reach Ketch from outside: no magnet handler, share target or text drop (`os-intake-channels`, `os-link-intake`, `link-intake`) | `FileDropTarget.kt:85,140`; `FileDrop.jvm.kt`; `OpenedFiles.kt:14-32`; `AndroidManifest.xml:29-56`; `Ketch-Info.plist`; `manifest.json` | `IncomingDownload.Links`, OS handlers, text drops (§4.9.7, §6) | W1, W2, W3 |
| 32 | H | Flat layering, no depth, native title strip (`flat-layering`, `window-chrome-glass`, `shell-chrome-dedupe`, `mac-title-bar`) | Surface/background contrast 1.06:1; `SidebarNavigation.kt:64-75,131-161`; `main.kt:224-228` | Canvas + wash + floating card, full-window content on macOS (§3.9, §4.2) | W1, W3 |
| 33 | H | Semantic color collisions; Material baseline pink leaks through (`semantic-color-collisions`, `status-dots-and-halo`) | `Color.kt:100,110`; `KetchColors.kt:94-106`; `PriorityBadge.kt:10-15`; `Theme.kt:99-130`; `ConnectionStatusDot.kt:41-46`; `DownloadListItem.kt:296-347` | Status palette, priority glyphs, every M3 slot mapped, dot + label (§3.2) | W1, W2 |
| 34 | H | Accent buttons fail WCAG AA (Harbor, Fathom, dark danger, logo) (`contrast-failures-accents`) | `KetchButton.kt:72,75`; `SidebarNavigation.kt:148`; `ThemeContrastTest.kt:11-18` | New fills, computed `onAccent`, contrast test over every accent (§3.2.4) | W1 |
| 35 | H | No bundled fonts; numbers set in monospace (`typography-bundled-fonts`) | `KetchTypography.kt:32-34`; `KetchToolbar.kt:63`; `Theme.kt:141` | Inter + Inter Display + JetBrains Mono, tabular numerals (§3.3) | W1 |
| 36 | H | iOS stops downloading in the background and never notifies (`ios-background-notifications`, `ios-background-honesty`) | `Ketch-Info.plist`; `MainViewController.kt` | `beginBackgroundTask` + clean pause, banner, local notifications (§6.3) | W2, W4 |
| 37 | H | No first run: Android fires 3 permission prompts at once; web opens a raw form (`first-run`, `web-connect-landing`) | `MainActivity.kt:47-49`; `AppShell.kt:97-101` | Launchpad checklist (desktop), WelcomeFlow (phones), ConnectLanding (web) (§4.13) | W1 (permissions), W4 |
| 38 | H | Pairing a device takes an IP, a port and a 32-character token (`device-pairing`) | `RemoteAccessSettings.kt:44-159`; `AddRemoteServerDialog.kt:89-120`; `MdnsDiscoverer.wasmJs.kt` | "Allow another device" + QR code + `ketch://pair` link (§4.10.4) | W3, W4 |
| 39 | H | Settings mixes app scope and device scope, has dead-end pages and no deep links (`settings-ia`, `settings-surface-and-scope`) | `SettingsCategory.kt:16-22`; `SettingsDialog.kt:52-68`; `BitTorrentSettings.kt:46-53`; `AiSettingsController.kt:95-96` | Two groups (This app / Device ▾), non-modal window, search, live summaries (§4.10) | W3 |

Medium and low findings are also covered. Section numbers in brackets.

- **List:** `list-state-model` (TaskListModel) [4.7], `facet-filters-and-search` [4.7.3],
  `queued-scheduled-context` and `why-not-moving` [4.7.4], `empty-state-launchpad` [4.12],
  `drag-out-finished-files` [4.7.10], `toolbar-chrome-height` [4.7.1].
- **Inspector:** `schedule-control` [4.8.3], `details-copy` [4.8.3], `activity-tab` [4.8.5],
  `custom-speed-per-keystroke` [3.11], `handoff-device` [4.5].
- **Intake:** `submit-feedback-duplicates` [4.9.4], `schedule-start-mode` [4.9.3],
  `ai-discover-handoff` [4.11], `retry-with-options` [4.9.8], `queue-outcome-preview` [4.9.3],
  `omnibox-command-bar` [5.2], `intake-sheet-shell-visuals` [4.9].
- **Visual system:** `motion-spec` [3.10], `shape-spacing-scale` [3.5-3.6],
  `icon-system-split` [3.12], `dead-code-stale-docs` [7 W2], `hue-tile-language` [3.11],
  `brand-segmented-sail` [3.13].
- **Desktop:** `dock-taskbar-progress` [6.1], `pause-reason-preemption` [4.7.4, W6],
  `multi-device-tray` [6.1], `command-palette` [5.2].
- **Settings and platforms:** `speed-schedule` [4.10.3], `adaptive-layout` [4.1],
  `medium-width-rail` [4.2.3], `web-remote-console` [6.4],
  `speed-picker-dedup` [3.11], `i18n-language` [8].
- **Navigation:** `unified-all-devices-list` [4.5.4], `devices-overview-not-dashboard` [4.6],
  `discover-placement` [4.11].

---
## 3. Design system

### 3.1 Theme architecture and migration map

The current structure stays: `KetchTheme { }` provides seven `CompositionLocal`s and wraps
`MaterialTheme` (`theme/Theme.kt`). The redesign changes values, adds fields and adds two locals.
Old field names are kept as `@Deprecated` aliases for one wave, so feature code keeps compiling
while the waves land.

| File | Change |
|---|---|
| `theme/KetchColors.kt` | New values (§3.2). New fields: `canvas`, `surfaceRaised`, `surfaceSunken`, `surfacePressed`, `rowSelected`, `rowSelectedFocused`, `sidebarItemSelected`, `sidebarItemHover`, `hairline`, `borderStrong`, `divider`, `scrim`, `inverseSurface`, `inverseOnSurface`, `inverseAccent`, `textPrimary`, `textSecondary`, `textTertiary`, `textDisabled`, `accent`, `accentHover`, `accentSoft`, `accentText`, `onAccent`, `dangerFill`, `brandEmber`, `wash: KetchWash`, `status: KetchStatusColors`, `lanes: List<Color>` (generated), `deviceHues`. The old names map as follows and become `@Deprecated` getters: `background`→`canvas`, `surfaceVariant`→`surfaceSunken`, `outline`→`borderStrong`, `outlineVariant`→`hairline`, `onBackground`→`textPrimary`, `onSurfaceVariant`→`textSecondary`, `onSurfaceDim`→`textTertiary`, `primary`→`accent`, `primaryContainer`→`accentSoft`, `onPrimaryContainer`→`accentText`, `success`/`warning`/`error`→`status.completed`/`status.paused`/`status.failed`, `segments`→`lanes`. The 12 short aliases (`bg`, `bgElev`, `panel`, ...) have zero references and are **deleted**. The 64 segment hexes (`KetchColors.kt:103-134`) are deleted. |
| `theme/Color.kt` | Delete the 34 unused legacy constants (`Color.kt:6-70`). `LightStateColors`/`DarkStateColors` and `LocalDownloadStateColors` stay (deprecated) until W2-QUICKWINS migrates the last consumer (`DownloadListItem`; `StatusIndicator` is deleted by W2-CONTROLS) to `KetchTheme.colors.status` and deletes them. |
| `theme/KetchTypography.kt` | Bundled families (§3.4). New styles (§3.3). The old 12 names become aliases (table in §3.3). It becomes `@Composable fun rememberKetchTypography(): KetchTypography`, because compose-resources `Font()` is composable. |
| `theme/KetchShapes.kt` | New radius scale (§3.5). Fixes the current `md` (8) being smaller than `sm` (10). `round` becomes a deprecated alias of `full`. |
| `theme/KetchSpacing.kt` | Numeric 4-pt scale (§3.6). The old names become deprecated aliases of the same value (`xxs` = `s0_5`, `xs` = `s1`, `md` = `s2`, `lg` = `s3`, `xl` = `s4`, `xxl` = `s5`, `xxxl` = `s6`, `x4l` = `s8`, `x5l` = `s10`, `x6l` = `s16`). `sm` (6) has no grid step: it stays a deprecated 6 dp alias, and its call sites move to `s1` or `s2` as their files are rewritten. |
| `theme/KetchFonts.kt` (new) | `rememberKetchTypography()` and `rememberKetchFontsLoaded()`. Compose resources generate an internal `Res`, so other modules (such as `app/web`) use these functions instead of `Res.font.*`. |
| `theme/KetchElevation.kt` | Levels `e0..e4` (§3.8). Each level is a list of Compose `Shadow`s, drawn with `Modifier.dropShadow(shape, shadow)` (CMP 1.12). |
| `theme/KetchMotion.kt` | New durations, easings and springs (§3.10). Fixes the degenerate `easeDecelerate`. |
| `theme/KetchDensity.kt` (new) | `LocalKetchDensity`, values `Compact` and `Comfortable` (§3.7). |
| `theme/KetchWindowChrome.kt` (new) | `LocalWindowChrome = WindowChrome(top: Dp, leading: Dp)`, default `WindowChrome.None` (0, 0). Desktop macOS provides `(28.dp, 78.dp)`. |
| `theme/Surfaces.kt` (new) | `Modifier.ketchSurface(level: KetchElevationLevel, shape: Shape, fill: Color, border: Color?)`. This is the only way feature code builds a surface. |
| `theme/Theme.kt` | Provides the new locals. `toMaterialColorScheme` fills **every** M3 slot (§3.2.7). `KetchTheme` gains `density`, `windowChrome` and `reduceMotion` accessors. |

### 3.2 Color tokens

All pairs are given as **light / dark**. Every text-on-fill pair is at least 4.5:1, enforced by
`ThemeContrastTest` (§3.14).

#### 3.2.1 Canvas, wash and surfaces

| Token | Light | Dark | Use |
|---|---|---|---|
| `canvas` | `#EEF1F8` | `#0C0E13` | Window background behind the sidebar and around the card |
| `wash.start` (160°, 0%) | `#E9EEFC` | `#151A33` | Linear gradient over the canvas, drawn once in `Modifier.drawBehind` on the root |
| `wash.mid` (50%) | `#F1F0FA` | `#0E1016` | |
| `wash.end` (100%) | `#FBF0EA` | `#1C1310` | |
| `wash.ember` (radial, r = 520 dp, centre = sidebar bottom-left) | `#FFD9C2` @ 35% | `#E0482B` @ 10% | Warm corner that echoes the app icon (`art/icon-app.svg`) |
| `surface` | `#FFFFFF` | `#16181D` | Content card, table body, inspector card |
| `surfaceRaised` | `#FFFFFF` | `#1E2128` | Menus, popovers, palette, dialogs, toasts, intake sheet |
| `surfaceSunken` | `#F4F6FA` | `#101217` | Inputs, segmented tracks, lane tracks, table header, Pulse bar, inspector wells |
| `surfaceHover` | `#F1F3F8` | `#1F232A` | Row and item hover |
| `surfacePressed` | `#E9ECF3` | `#272B33` | Press state |
| `rowSelected` | accent @ 10% over `surface` (Signal ≈ `#EEF0FD`) | accent @ 16% over `surface` (Signal ≈ `#20243F`) | Selected row, with a 2 dp accent bar on the left |
| `rowSelectedFocused` | accent @ 15% (Signal ≈ `#E5E7FB`) | accent @ 24% (Signal ≈ `#262D5C`) | Selected row while the table has keyboard focus |
| `sidebarItemSelected` | `#FFFFFF` @ 72% | `#FFFFFF` @ 8% | Selected sidebar item pill (never the accent color) |
| `sidebarItemHover` | `#FFFFFF` @ 45% | `#FFFFFF` @ 5% | |
| `hairline` | `#E2E6EE` | `#2A2E37` | 1 dp borders on the card, menus and dividers between regions |
| `borderStrong` | `#C9D0DC` | `#3A404C` | Inputs, Secondary buttons, checkboxes |
| `divider` | `#EDF0F4` | `#22252C` | Row dividers (inset 48 dp in the table, 60 dp in lists) |
| `scrim` | `#0B0D12` @ 32% | `#0B0D12` @ 56% | Behind dialogs and sheets |

Rule: **the wash is drawn only on the canvas.** The sidebar is transparent over it. Every surface
that scrolls or updates live is opaque. No blur ships in Waves 1-6 (see §8). The selection tints
are computed from the accent with the alphas above; the hex values are the Signal reference.

#### 3.2.2 Text

| Token | Light | Dark | Rule |
|---|---|---|---|
| `textPrimary` | `#141A26` | `#EEF0F4` | Names, titles, values |
| `textSecondary` | `#4B5466` | `#AEB4C0` | Meta and labels. Use this, not tertiary, on the wash or sidebar |
| `textTertiary` | `#667080` | `#858D9C` | Captions, units, shortcut hints. Only on `surface` or `surfaceSunken` |
| `textDisabled` | `#A3AAB7` | `#565D6A` | Disabled controls (exempt from the 4.5 rule) |

`textTertiary` reaches only 3.9–4.4:1 on `rowSelected`, `rowSelectedFocused` and `surfacePressed`,
so rows in those states promote tertiary text to `textSecondary` (6.2:1 or better). Measured
ratios: `textTertiary` is 5.0 / 5.3 on `surface`, 4.6 / 5.6 on `surfaceSunken`, and 4.5 / 4.7 on
`surfaceHover`; `textSecondary` is at least 6.4 on every wash stop, the ember glow and
`sidebarItemSelected`.

#### 3.2.3 Accents (Settings → General → Accent)

`KetchAccent` keeps its names. **`onAccent` is computed** from the relative luminance of the fill
(white when contrast is at least 4.5, otherwise `#141A26`), so `KetchButton` never hard-codes
`Color.White` again (`KetchButton.kt:72,75`). Hover adds an 8% black overlay on the fill in
light, and an 8% white overlay in dark.

| Accent | Fill light | Fill dark | `accentText` light / dark | `accentSoft` light / dark | White on fill |
|---|---|---|---|---|---|
| **Signal** (default) | `#4F5DE4` | `#5563F0` | `#3C47B7` / `#8E9BFF` | `#ECEEFE` / `#1D2140` | 5.2 / 4.7 |
| Harbor | `#00818D` | `#00818D` | `#006A74` / `#4FD1DB` | `#DDF5F7` / `#0D2E33` | 4.6 / 4.6 |
| Fathom | `#007F35` | `#007F35` | `#00692C` / `#5FD08A` | `#DCF3E3` / `#0E2C1A` | 5.1 / 5.1 |
| Beacon | `#C9431C` | `#C9431C` | `#A8370F` / `#FF9A6B` | `#FFE9E0` / `#34160D` | 4.9 / 4.9 |

Signal's dark fill changes from azure `#319CFC` to indigo `#5563F0`. Signal is therefore one
hue family in both themes, and white text on it passes AA.

#### 3.2.4 Status (`KetchColors.status: KetchStatusColors`)

Each status has a `color` (the dot and, for Failed only, the label) and a `soft` variant (10% over
`surface`) for pills and backgrounds. Every `color` below is at least 5.2:1 on `surface` and at
least 4.5:1 on its own `soft` fill and on `surfaceSunken` in both themes. The light paused,
completed, failed and seeding values are darker than the first draft (`#A35F00`, `#1E7F45`,
`#C8344A`, `#00818D`), which fell to 4.1–4.5:1 on their soft fills.

| Status | Light | Dark | Glyph / dot | Shown on rows? |
|---|---|---|---|---|
| downloading | = accent fill | = accent fill | ● pulsing 1600 ms halo | dot + lanes |
| queued (Waiting) | `#646D7E` | `#8C94A3` | ○ | dot + reason |
| scheduled (new) | `#6E4FE0` | `#A891FF` | ◷ | dot + start time |
| paused | `#965700` | `#E8A93F` | ◐ | dot + "Paused" |
| completed | `#1B7540` | `#45C27A` | none | no badge (success is the default) |
| failed | `#BE2F44` | `#FF6B6B` | ✕ | dot + reason in failed color |
| canceled | = `textTertiary` | = `textTertiary` | ⊘ | dot + "Canceled" (label in `textSecondary`) |
| seeding (future) | `#00747F` | `#4FD1DB` | ⇅ | — |

White text never sits on a status color: in dark the status colors are light (white on
`#FF6B6B` is 2.8:1). Count badges for failures (tab count, pennant badge, Dock-style badges) use
white on `dangerFill` (5.2 / 4.9:1).
| stalled | = paused color, no pulse | | ● static | dot + "Stalled · no data for 12 s" |
| throttled marker `⤓` | = paused color | | `⤓` after speed | tooltip names the cap |
| `dangerFill` (buttons) | `#C8344A` | `#D2353F` | | white text 5.2 / 4.9 |

**Priority never borrows status colors.** It also fixes `PriorityBadge.kt:10-15`.
- LOW = `ChevronDown` glyph in `textTertiary`.
- NORMAL = no glyph.
- HIGH = `ChevronUp` in `accentText`.
- URGENT = `Bolt` glyph on an accent-filled pill (h16, padding 4, white glyph).

Connection health shares one function, `healthColor(health)` on a `DeviceHealth`, used by the
device pennant ring, `ConnectionStatusDot` and the Pulse bar badge:

| State | Color | Behavior |
|---|---|---|
| Embedded / Connected | `status.completed` | — |
| Connecting | `status.paused` | ring pulses |
| Disconnected / Unauthorized | `status.failed` | — |

#### 3.2.5 Lanes, devices and brand

- **Lanes:** `lanes[i] = accent.copy(alpha = [1, .72, .88, .60, .94, .66, .80, .54][i % 8])`
  composited over `surfaceSunken`. These replace the hand-picked segment hexes.
  - Write head: accent at 100%, 2 dp wide, height + 4 dp, with a 4 dp glow at 30%.
  - Paused lanes use the same ramp on `status.paused`.
  - Failed tasks show their map in `textTertiary` at 40%.
  - Completed tasks show one merged run in `status.completed` at 70% (only during the
    completion sheen, then the strip is removed).
- **Device hues** (pennants): a stable hash of `deviceId` picks from these `FileTypeHue` entries:
  `Sky, Teal, Magenta, Lime, Orange, Slate, Jade, Brown`.
  - The pennant fill is `hue.light` in **both** themes, with a white glyph or monogram (every
    one passes at least 4.9:1).
  - Tints (chips, stacked sparkline bands) are `hue.dark` at 16% in dark and `hue.light` at 13%
    in light.
  - Hues are not user-editable.
- **Brand ember gradient:** `#FFB25B` → `#E0482B` at 135°. Allowed only on the logo tile, the
  web splash, onboarding, About, the completion sheen and the Add button's hover and drop fills
  (§4.7.1). Never on other controls, status or selection. `DesignTokenUsageTest` enforces this:
  `KetchColors.brandEmber` may be referenced only from `components/KetchLogoTile.kt`,
  `components/SailLanesIllustration.kt`, `components/LaneStrip.kt` (the sheen) and
  `components/KetchAddButton.kt` (hover and drop fills only).

#### 3.2.6 File-type hue tiles

`FileTypeHue` (14 hues, `KetchColors.kt:141-163`) stays as is. `KetchHueTile` (§3.11) reuses it
for settings categories, device types and Discover results.

#### 3.2.7 Material 3 slot mapping (`toMaterialColorScheme`)

Every slot is set explicitly, so no baseline pink or mauve can appear (`ConnectionStatusDot.kt:41-46`).

| M3 slot | Token |
|---|---|
| primary / onPrimary | accent / onAccent |
| primaryContainer / onPrimaryContainer | accentSoft / accentText |
| secondary / onSecondary | textSecondary / surface |
| secondaryContainer / onSecondaryContainer | surfaceSunken / textPrimary |
| tertiary / onTertiary | status.completed / white |
| tertiaryContainer / onTertiaryContainer | status.completed.soft / status.completed |
| error / onError | status.failed / white |
| errorContainer / onErrorContainer | status.failed.soft / status.failed |
| background / onBackground | canvas / textPrimary |
| surface / onSurface | surface / textPrimary |
| surfaceVariant / onSurfaceVariant | surfaceSunken / textSecondary |
| surfaceContainerLowest..Highest | surface, surface, surfaceSunken, surfaceHover, surfacePressed |
| surfaceBright / surfaceDim | surfaceRaised / surfaceSunken |
| inverseSurface / inverseOnSurface | `#141A26` / `#F4F6FA` (light); `#EEF0F4` / `#141A26` (dark) |
| inversePrimary | accentText (dark value in light, light value in dark) |
| outline / outlineVariant | borderStrong / hairline |
| scrim | `#0B0D12` |

### 3.3 Typography

| Style (new name) | Family / weight | Size / line height | Tracking | Use | Old alias |
|---|---|---|---|---|---|
| `pageTitle` | Inter Display SemiBold | 22 / 28 sp | -0.3 | Desktop page header ("Downloads") | `displayMedium` |
| `largeTitle` | Inter Display Bold | 28 / 34 | -0.5 | Phone large title, onboarding, empty-state title | `displayLarge` |
| `titleL` | Inter SemiBold | 20 / 26 | -0.3 | Dialogs, intake sheet, Devices page title | `displaySmall` |
| `titleM` | Inter SemiBold | 15 / 20 | -0.1 | Inspector file name, device-card name, setting group titles | — |
| `bodyStrong` | Inter Medium | 14 / 20 | 0 | List-row name | — |
| `body` | Inter Regular | 14 / 20 | 0 | Body text | `bodyLarge` |
| `bodyS` | Inter Regular | 13 / 18 | 0 | Secondary body, inspector values | `bodyMedium` |
| `cell` / `cellStrong` | Inter Regular / Medium | 13 / 18 | 0 | Table cells / table Name column | — |
| `caption` | Inter Regular | 12 / 16 | 0 | Row meta, hints, reasons | `bodySmall` |
| `label` | Inter Medium | 13 / 16 | 0 | Buttons, sidebar items, tabs | `labelLarge` |
| `labelS` | Inter Medium | 12 / 16 | 0 | Chips, pills, menu shortcut hints | `labelMedium` |
| `eyebrow` | Inter SemiBold | 11 / 14 | +0.8, uppercase | Group headers, table header, card labels | `labelSmall` |
| `numeralXL` | Inter Display SemiBold | 40 / 44 | -1.0 | Device-card speed, phone Pulse sheet | — |
| `numeralL` | Inter Display SemiBold | 20 / 24 | -0.3 | Inspector speed | — |
| `numeral` | Inter Medium | 13 / 18 | 0 | Row speed, size, ETA, %, Pulse bar speed | `monoMedium` |
| `numeralS` | Inter Medium | 11 / 14 | 0 | Tab counts, sidebar live lines, rail readout | — |
| `mono` | JetBrains Mono Regular | 12 / 18 | 0 | URLs, paths, hashes, task IDs, cURL, intake input (13/18) | `monoSmall` |
| `monoS` | JetBrains Mono Regular | 11 / 16 | 0 | Lane index "#3", hash snippets | `monoXSmall` |

Rules:
- Every `numeral*` style sets `fontFeatureSettings = "tnum"`, so digits do not jitter on the
  200 ms progress tick.
- Units sit on the same baseline in `caption` + `textTertiary` ("8.2" then " MB/s").
- Monospace is **never** used for speeds or sizes. Today they use it (`DownloadListItem.kt:287`,
  `KetchSurfaces.kt:76,164`).
- `eyebrow` uppercases through the style helper `TextStyle.eyebrow()`, not by hand. This removes
  the three copy-pasted 0.6 sp eyebrows (`SidebarNavigation.kt:228-235`,
  `SettingsComponents.kt:110`, `DownloadExpandedPanel.kt:136-142`).
- M3 mapping: `titleLarge` = `titleL`, `titleMedium` = `titleM` (this removes the SemiBold patch
  at `AppShell.kt:353`), `titleSmall` = `label`, `bodyLarge` = `body`, `bodyMedium` = `bodyS`,
  `bodySmall` = `caption`, `labelLarge` = `label`, `labelMedium` = `labelS`,
  `labelSmall` = `eyebrow`.
- Phones (`Comfortable` density) raise `bodyStrong` to 15/20, `caption` to 13/18 and
  `pageTitle` to 22/28 in the top bar.

### 3.4 Font decision

- **Bundle Inter v4 (with Inter Display) and JetBrains Mono**, all under SIL OFL 1.1.
  - Files go in `app/shared/src/commonMain/composeResources/font/`: `inter_regular.ttf`,
    `inter_medium.ttf`, `inter_semibold.ttf`, `interdisplay_semibold.ttf`,
    `interdisplay_bold.ttf`, `jetbrainsmono_regular.ttf`.
  - Subset each with `pyftsubset` to Latin, Latin-Ext, Greek and Cyrillic, with the `tnum`, `cv11`
    and `ss01` features kept. Target 90-140 KB per file and **under 1 MB in total**.
- Add both OFL texts to `composeResources/files/licenses/THIRD-PARTY-NOTICES.txt`. Check the
  Reserved Font Name clauses before subsetting.
- Loading:
  - `org.jetbrains.compose.resources.Font(Res.font.inter_regular, FontWeight.Normal)` inside
    `rememberKetchTypography()`.
  - Compose resources generate an **internal** `Res`, so `app/web` cannot reference
    `Res.font.*`. `theme/KetchFonts.kt` exposes `rememberKetchFontsLoaded()`, which calls
    `preloadFont` for regular and semibold; web `main` composes `App` only once it returns true.
    The HTML splash
    (§3.13) stays visible until both have loaded, so no fallback-font flash appears.
- CJK falls back to system fonts on every platform.

### 3.5 Radii (`KetchShapes`)

| Token | dp | Use |
|---|---|---|
| `xs` | 4 | Lanes, progress tracks, inner focus ring |
| `sm` | 8 | Compact inputs, icon buttons, tooltips, row highlight inside lists, sidebar items |
| `md` | 12 | Menus, popovers, toasts, Comfortable inputs, row highlight inset in the card, problem cards |
| `lg` | 16 | **Content card**, inspector card, device cards, intake rows group, settings groups, drop berths |
| `xl` | 20 | Dialogs, intake sheet, command palette, settings window card |
| `xxl` | 28 | Bottom-sheet top corners |
| `full` | 50% | Buttons, chips, segmented controls, tabs, count pills, search field, pennants, FAB |

Nesting rule: inner radius = outer radius − inset. For example, a row highlight inset 4 dp inside
the r16 card uses r12. Semantic aliases: `button = full`, `textField = sm` (Compact) or `md`
(Comfortable), `card = lg`, `dialog = xl`, `menu = md`, `toast = md`, `badge = full`,
`progressBar = xs`, `sheetTop = xxl`.

### 3.6 Spacing and layout constants (`KetchSpacing`)

The scale sits on a 4-pt grid: `s0_5 = 2`, `s1 = 4`, `s2 = 8`, `s3 = 12`, `s4 = 16`, `s5 = 20`,
`s6 = 24`, `s8 = 32`, `s10 = 40`, `s12 = 48`, `s16 = 64`. Off-grid literals (3, 5, 7, 9, 10,
14, 17, 22, 28) are forbidden outside `components/`.

| Constant | Value |
|---|---|
| Sidebar width (Expanded) | 220 dp |
| Rail width (Medium or collapsed) | 72 dp |
| Content card inset | 8 dp top, end and bottom (the sidebar side has no inset) |
| Page header height / padding | 52 dp / 16 dp horizontal |
| Tab row height | 40 dp (segmented control 32 dp inside) |
| Table header / row / group header | 28 / 40 / 28 dp (Compact preference: row 32) |
| List row (pointer / touch) | 56 / 64 dp |
| Pulse bar height | 32 dp |
| Inspector width | 320 dp default, drag-resizable 280–480 |
| Icon-to-label gap | `s2` (8) |
| Row horizontal padding | 12 dp (table cell padding 8) |
| Card page padding (Devices, Settings) | 24 dp desktop, 16 dp phone |
| Section gap | 24 dp |
| Default desktop window | **1280 × 800** (was 1024 × 720, `WindowStateStore.kt:29-30`); minimum 720 × 480 |

### 3.7 Density (`KetchDensity`)

Density is chosen by **input method**. Pointer-primary platforms (JVM, Wasm, and tablets with a
hover-capable pointer, detected via `LocalInputModeManager` plus pointer-event history) get
`Compact`; touch gets `Comfortable`. It can be overridden in Settings → General → Density (Auto /
Compact / Comfortable).

| Element | Compact | Comfortable |
|---|---|---|
| Button (S / M / L) | 28 / 32 / 36 | 36 / 44 / 48 |
| Icon button | 28 visual, 32 hit area, 16 glyph | 40 visual, 48 touch target, 20 glyph |
| Input | 32 | 48 |
| Chip, segmented control, tab | 28 | 32 (40 row) |
| Sidebar item / device row | 36 / 40 | 40 / 48 |
| Table row | 40 (32 with the "Compact rows" preference) | 44 |
| List row | 56 | 64 (72 with lanes) |
| Menu item | 28 | 48 (as a bottom sheet) |
| Glyph in nav / controls | 18 / 16 | 24 / 20 |

The KDoc in `KetchButton.kt:49-52` and `KetchTextField.kt:30` is corrected to match.

### 3.8 Elevation (`KetchElevation`)

| Level | Layers (x/y/blur, spread, color) | Use |
|---|---|---|
| `e0` | none | Flat rows, table, sidebar |
| `e1` | 0/1/2 `#0F172A` 6% + 0/8/24 spread −4 `#0F172A` 8% | Content card, inspector card (docked) |
| `e2` | 0/1/2 5% + 0/4/12 6% | Device cards, hovered pills, selection bar, Pulse popover |
| `e3` | 0/4/12 10% + 0/16/40 spread −8 16% | Menus, toasts, tooltips, popovers, overlay inspector |
| `e4` | 0/12/24 12% + 0/32/64 spread −12 24% | Dialogs, intake sheet, command palette |

In dark, shadow alpha is multiplied by 3, and raised surfaces get a 1 dp top highlight of
`#FFFFFF` at 6% (a top-border gradient in `ketchSurface`). The old `level0..level5` map to
`e0, e1, e2, e3, e4, e4` and are deprecated.

### 3.9 Materials

1. **Layer order:** canvas + wash → transparent sidebar → floating content card (r16, e1,
   1 dp `hairline`) → flat rows. Rows are never cards.
2. **No blur.** Haze or any backdrop blur is out of scope for Waves 1-6 (§8). Sticky headers, the
   Pulse bar and the selection bar use opaque `surface`/`surfaceSunken` with a hairline.
3. **Rail and sidebar:** the card keeps its 8 dp inset and r16 corners at every width; only the
   phone shell (below 600 dp) goes full-bleed.
4. **Phones:** full-bleed `surface`. The wash appears only as a 200 dp gradient fading out under
   the top bar.

### 3.10 Motion (`KetchMotion`)

| Token | Value | Use |
|---|---|---|
| `micro` | 90 ms | Hover and press colors (`animateColorAsState`) |
| `short` | 150 ms | Toggles, segmented thumb, tab switch, chip → checkbox morph, tab row ↔ selection bar |
| `medium` | 220 ms | Inspector slide, list placement, lane resize, banners |
| `long` / `longExit` | 320 / 200 ms | Sheet and dialog enter / exit |
| `xlong` | 480 ms | Onboarding, the add lane flight |
| `easeStandard` | `CubicBezier(0.2, 0, 0, 1)` | Default |
| `easeDecelerate` (enter) | `CubicBezier(0.05, 0.7, 0.1, 1)` | Enter |
| `easeAccelerate` (exit) | `CubicBezier(0.3, 0, 0.8, 0.15)` | Exit |
| `progressSpring` | `spring(dampingRatio = 1f, stiffness = 200f)` | Progress fill, lane widths |
| `placementSpring` | `spring(stiffness = 400f)` | `Modifier.animateItem(fadeInSpec = tween(150), placementSpec = placementSpring)` |
| `headGlide` | `tween(200, LinearEasing)` | Write heads, matching the engine's 200 ms progress cadence |
| `pulse` | 1600 ms infinite, alpha 0.28 → 0, radius r → r + 5 dp | Only Downloading dots and Connecting rings |
| Copied-link sheen (`KetchAddButton`) | 720 ms, once per clip | The Add button turning into its split button (§4.7.1) |
| Add lane flight (`AddFlight`) | `xlong` (480 ms), `easeStandard` | From the header's Add button to the first new row on screen (§4.9.1) |
| Drop-target lanes (`KetchAddButton`) | 1.8 s loop | The Add button's lanes while a drag hovers the window |

Motion tied to engine events:

| Event | Motion |
|---|---|
| Resegment (connections changed) | Boundaries spring with `progressSpring`. New seams flash accent for 400 ms. New lanes in the Connections tab enter with `expandVertically + fadeIn` (`medium`). |
| Stall (no bytes for 3 s) | The head stops pulsing. The dot turns static amber. |
| Completion | Seams fade (220 ms), an ember sheen crosses the merged bar (320 ms), then the file chip cross-fades to a check for 1.2 s. This is the only celebratory motion. |
| Live sort | The list re-sorts at most every 2 s. Re-sorting freezes while the pointer is over the list, a row has focus or a menu is open. |

**Reduce motion:** `expect fun rememberReduceMotion(): Boolean` in `platform/Accessibility.kt`.

| Platform | Source |
|---|---|
| Android | `Settings.Global.ANIMATOR_DURATION_SCALE == 0` |
| iOS | `UIAccessibility.isReduceMotionEnabled` |
| Web | `matchMedia('(prefers-reduced-motion: reduce)')` |
| macOS | `defaults read com.apple.universalaccess reduceMotion` (read once at startup, plus on window focus) |
| Windows / Linux | false |

A Settings → General switch "Reduce motion" (Auto / On) overrides it. When reduce motion is on:
all durations are 0, there is no pulse, glide or sheen, lanes jump to their values, and the
illustration is static.

### 3.11 Component rules (Compact sizes; Comfortable values come from §3.7)

| Component | Spec |
|---|---|
| `KetchButton` | **Primary:** accent fill, `onAccent` label, `full` radius, h32, padding 14, `label`, 16 dp icon, **at most one per surface**. **Secondary:** `surface` + 1 dp `borderStrong`. **Tonal:** `accentSoft` / `accentText`. **Ghost:** transparent, `surfaceHover` on hover. **Danger:** `dangerFill` + white, used for every destructive confirm. **States:** hover overlay 8% (`micro`); press scale .98 + overlay 12%; focus ring 2 dp accent at 50%, offset 2 dp (only on focus-visible); disabled 40% alpha. Loading replaces the icon with a 14 dp spinner, and the width stays fixed. |
| `KetchIconButton` | 28 visual / 32 hit area, r8, 16 dp glyph; ghost by default. The tooltip is required and includes the shortcut. |
| `KetchPillGroup` | Bordered `full` container with up to four 28 dp icon buttons and 1 dp internal dividers (e.g. `☰ \| ▦`). |
| `KetchSegmented` | `surfaceSunken` track h32, 3 dp padding. The thumb is a `surface` pill at e2 and slides over 150 ms. Label `label`. Optional count `numeralS`. Replaces `SettingsSegmented` (`SettingsComponents.kt:327-355`) and the theme picker. |
| `KetchChip` | h28 `full`, 1 dp `borderStrong`. Selected: `accentSoft` fill + 12 dp check + `accentText`. Count in `numeralS` `textTertiary`. Removable chips have a 14 dp ✕. Replaces M3 `FilterChip`. |
| `KetchTextField` | `surfaceSunken` fill, `sm` radius, h32. Label **above** (`labelS`, `textSecondary`), never floating. On focus: 1 dp accent border plus a 3 dp accent ring at 20%. Error text below in `caption` failed. Optional leading glyph, trailing clear/paste buttons. A multi-line variant is used for intake. Replaces M3 `OutlinedTextField` (`AddDownloadDialog.kt:213,298,491,737,744`; `AddRemoteServerDialog.kt:88,96,112`) and `SettingsTextField`. |
| `KetchCheckbox` / `KetchSwitch` | 16 dp box, r4, `borderStrong`; checked = accent fill + white check. Tri-state dash. Switch 32 × 18. |
| `KetchMenu` | `surfaceRaised`, r12, e3, 4 dp inset. Items h28, r6, `label`. Shortcut hints right-aligned in `labelS` `textTertiary`. Submenus open on hover after 150 ms or on →. Dividers are 1 dp `divider`. Destructive items use the failed text color. On touch, a menu opens as a `ModalBottomSheet` with 48 dp items. |
| `KetchTooltip` | Inverse surface (`#141A26` with `#F4F6FA`; swapped in dark), r8, padding 6 × 10, `caption`, 500 ms delay. The shortcut sits at the end in a 60% alpha label ("Pause all   ⇧⌘P"). Touch devices show it on long-press. |
| `KetchDialog` (via `AdaptiveModal`) | `surfaceRaised`, `xl`, e4, max width 520 (intake 640), padding 24, `titleL`. Footer right-aligned: Secondary, then Primary or Danger. Esc and scrim click dismiss unless there is unsaved typed input. Compact width: `ModalBottomSheet` with `xxl` top, chosen by **window width**, not `isMobilePlatform` (`AdaptiveModal.kt:49`). Touch dialogs anchor at 15% of the height (keeps the IME fix from #135). |
| `KetchToast` | Inverse surface, `md`, e3, h40, max width 480, padding 12 × 16. 16 dp level icon, `bodyS` text, up to 2 text actions in `inverseAccent` (Signal: `#8E9BFF` in light, 6.9:1 on `#141A26`; `#3C47B7` in dark, 6.6:1 on `#EEF0F4`; other accents use their opposite-theme `accentText`). Info and Success last 4 s, or 8 s with an action. Errors stay until dismissed. Hover pauses the timer. At most 3 stacked, 8 dp apart. Swipe dismisses on touch. `liveRegion = Polite` (errors `Assertive`). |
| `KetchBadge` / count pill | h20 `full`, `surfaceSunken`, `numeralS`. Failed count: white on `dangerFill` (never on `status.failed`, which is light in dark). |
| `StatusDot` | 8 dp (6 dp in table rows) + `caption` `textSecondary` label. Only Failed colors its label. The Downloading pulse is drawn with `drawBehind` (the current halo is invisible: `DownloadListItem.kt:327-337`). |
| `PriorityGlyph` | See §3.2.4. |
| `LaneStrip` | One `Canvas`, `xs` radius. Heights: 4 dp (list row), 6 dp (table progress cell), 10 dp (inspector header), 16 dp (Connections tab). Each segment is drawn at `start/total` to `(end+1)/total` with its downloaded part filled. 1 dp `surface` seams only between unfinished segments, and only when there are 32 segments or fewer. A write head per active segment. Unknown size: a 30%-wide shimmer cycling every 1.2 s. Semantics: "8 connections, 6 active, 42 percent". Replaces `KetchSegmentBar`, `KetchSegmentDetail`, `HealthDot` and `KetchProgressBar` for tasks. |
| `KetchFileTypeChip` | 20 dp (table), 28 dp (list), 36 dp (phone), 40 dp (inspector, intake preview). Hue at 13% + glyph. |
| `KetchHueTile` | 32/40/48 dp, r = size × 0.28, hue gradient 18% → 6% top-left to bottom-right, 1 dp `#FFFFFF` at 40% top highlight (light only). Used for settings categories, device types and Discover results. |
| `DevicePennant` | 16/20/24/32/40 dp circle in the device hue with the white solid glyph of its `DeviceType` (Laptop, Desktop, Server, Phone, Tablet, Browser) at 60% of the circle; a device whose type is not known yet shows a monogram instead (two letters from the name: "NB", "LM"). 2 dp **health ring** (§3.2.4) offset 2 dp. Failed badge: 14 dp `dangerFill` circle with a white `numeralS` count at top-end. The ring encodes **health only**. Aggregate progress is a separate outer arc, shown only on the rail's All-devices avatar. |
| `SpeedLimitPicker` (shared) | Chips: Unlimited · 512 KB/s · 1 · 2 · 5 · 10 MB/s · Custom…. Custom is an inline field that accepts `500k`, `2m`, `1.5m`, `unlimited` (decimals parsed client-side). It commits on ↩, on blur, or after 600 ms without typing, never per keystroke (`SpeedLimitSlider.kt:145-155,198-202`). The selected state is derived from `requestState`, not remembered flags. The caption names the limit that wins ("Slow lane 1 MB/s applies to all downloads"). Used by Settings, intake, inspector and the Pulse popover. Deletes `presetSpeedOptions` (`SpeedLimitSlider.kt:40-50`). |
| `StartTimePicker` (shared) | Menu: Start now · In 1 hour · Tonight 01:00 · Tomorrow 08:00 · Off-peak (from Speed → Auto rules, when set) · Pick date & time… (M3 `DatePicker`, then `TimePicker`) · Clear. It always produces `DownloadSchedule.AtTime` (kotlinx-datetime, `TimeZone.currentSystemDefault()`). Label: "Starts 01:00 tonight", "Starts Fri 08:00". |
| `ConnectionStepper` | `[−] 8 [+]`, 28 dp buttons, `numeral` value, range 1–32 (torrent: "Peer limit" 1–512, step 10). Debounced 400 ms. "Auto (4)" when `request.connections == 0`. |
| Pending control state | While a command is in flight, the control shows a 12 dp spinner (stroke 1.5 dp) and keeps the requested value. **No automatic revert.** On failure, the value returns to `requestState` and an error toast with Try again appears. |

### 3.12 Iconography

- One family, `KetchIcon`: a 20-unit grid, 1.7 stroke, round caps and joins.
- Convert to lazily cached `ImageVector` (`ImageVector.Builder` + `addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = Round)`), drawn through `rememberVectorPainter` with a tint. This removes per-draw SVG parsing (`KetchIconRenderer.kt:37-51`).
- Remove `compose.materialIconsExtended` (`app/shared/build.gradle.kts:74`) once the last uses are
  gone. They sit in six files: `AddDownloadDialog` (deleted by W3-INTAKE-SHEET),
  `ScheduleToggle`, `PrioritySelector`, `SpeedLimitSlider`, `TaskSettingsPanel` (deleted by
  W3-TABLE) and `AddRemoteServerDialog` (deleted by W4-FLEET-SHELL, which also drops the
  dependency).
- New glyphs:

| Group | Glyphs |
|---|---|
| Brand / speed | `Sail`, `SlowLane` (two lanes with a short arrow), `Auto` (clock with a lane) |
| Engine | `Lanes`, `Bolt` (Urgent) |
| File actions | `Open` (arrow up-right), `Reveal` (folder with arrow), `Copy`, `Drop` |
| Status | `CheckCircle`, `Warning`, `ChevronUp`, `ChevronDown` |
| Devices | `Laptop`, `Desktop`, `Server`, `Phone`, `Tablet`, `Browser`, `Fleet` (three linked dots); solid `Pennant*` glyphs of the same devices, drawn inside a `DevicePennant` |
| Navigation | `Discover` (sparkle magnifier), `Devices`, `Bell`, `Command` (⌘), `Columns`, `Inspector` (panel-right), `Sidebar` (panel-left), `Undo`, `Pennant`, `QrCode` |

- Sizes: 16 in Compact controls, 18 in nav, 20 in touch controls, 24 in the phone bottom bar
  and top bar.

### 3.13 Illustration and brand motif: the segmented sail

The app icon (`art/icon-app.svg`) is an ember-gradient squircle with two white sails split into
horizontal stripes. The stripes read as download lanes. That **is** the motif.

- **`KetchLogoTile(size)`:** radius = size × 0.28, ember gradient at 135°, white `Sail` glyph.
  Sizes: 20 dp (web sidebar header next to "Ketch" in `label` SemiBold), 64 dp (onboarding),
  96 dp (About). It replaces the indigo "K" wordmark (`SidebarNavigation.kt:131-160`).
- **`SailLanesIllustration(modifier, animate)`:** a single `Canvas`, 280 × 200 dp (200 wide on
  phones).
  - The main sail has 6 lanes and the mizzen 4. Lane widths follow the sail's edges. Lanes are r3
    with 4 dp gaps.
  - Each lane fills left to right in the accent ramp, with the top lane in ember, staggered by
    90 ms, looping every 5 s with a 2 s hold.
  - The hull is one wide lane in `textTertiary` at 20%, over a radial glow of accent and ember at
    12%.
  - It is static (fully filled) under reduce motion.
  - Used in the empty Downloads launchpad, onboarding, About, ConnectLanding and the web splash.
- **Web splash** (`app/web/src/wasmJsMain/resources/index.html`): the same lanes as inline SVG
  with CSS keyframes, on `#EEF1F8` (dark: `#0C0E13` via `prefers-color-scheme`). It is removed on
  the first Compose frame after `rememberKetchFontsLoaded()` returns true.
- **Device pennants** carry the sailing metaphor into the fleet: each device is a flag of its own
  hue.
- **Copy rule:** the nautical motif appears in copy only as "Slow lane" and the accent names.
  About carries one brand line: "A ketch is a two-masted sailboat. Ketch splits every download
  into lanes, like its sails."

### 3.14 Enforcement

1. **`DesignTokenUsageTest`** in `app/shared/src/jvmTest/.../theme/DesignTokenUsageTest.kt` reads
   `commonMain` sources outside `theme/` and `components/`, and fails on:
   `RoundedCornerShape(<number>.dp`, `Color(0x`, `<number>.sp`, `MaterialTheme.`,
   `androidx.compose.material.icons`, and `brandEmber` outside the four allowed files
   (§3.2.5; `components/KetchAddButton.kt` for its hover and drop fills only).
   - The allowlist `app/shared/src/jvmTest/resources/design-token-allowlist.txt` lists
     `path:pattern:count` and starts with today's offenders.
   - The test also **fails when a count goes down** without the allowlist being updated, so the
     list can only shrink.
   - Each wave's packages must remove the entries for the files they own. The allowlist is the
     one file every package may edit, and only on the lines for files it owns.
     `./gradlew :app:shared:jvmTest -PupdateTokenAllowlist` rewrites the counts (W0 forwards the
     property to the test JVM).
2. **`ThemeContrastTest`** loops over `KetchAccent.entries × {light, dark}` and asserts a ratio of
   at least 4.5 for:
   - `onAccent` on accent;
   - `accentText` on `surface`;
   - `accentText` on `accentSoft` (Tonal buttons, selected chips);
   - each status color (queued, scheduled, paused, completed, failed, seeding) on `surface`,
     `surfaceSunken` and its soft fill;
   - white on `dangerFill` (destructive buttons and failure count badges);
   - `textTertiary` on `surface`, `surfaceSunken` and `surfaceHover`;
   - `textSecondary` on `rowSelected` and `rowSelectedFocused` (where tertiary text is promoted);
   - the tooltip and toast pairs: `inverseOnSurface` and `inverseAccent` on `inverseSurface`;
   - `textSecondary` on `wash.start/mid/end` and on `sidebarItemSelected` composited over the
     wash;
   - white on every device-hue pennant fill.
3. **Registry test** (`KetchCommandsTest`): no chord is bound twice in the same scope on any
   platform. Every command has a label and an icon or explicitly none.

---
## 4. Screens

### 4.1 Layout tiers

`ui/shell/KetchLayout.kt` derives one `KetchLayout` from the window width
(`currentWindowAdaptiveInfo().windowSizeClass`) and passes it down. `isMobilePlatform` is no longer
used for layout. It stays only for platform capabilities such as the clipboard policy.

| Tier | Width | Navigation | Downloads view | Inspector | Dialogs | Settings |
|---|---|---|---|---|---|---|
| Compact | < 600 dp | Phone top bar; bottom bar only if there are ≥ 3 destinations | Two-line rows, 64 dp | Bottom sheet | Bottom sheets | Page |
| Medium | 600–1023 dp | 72 dp rail | Table if the table area is ≥ 720 dp **and** pointer input, otherwise two-line rows | Overlay card | Centered, max 560 | Two-pane page |
| Expanded | ≥ 1024 dp | 220 dp sidebar (`⌃⌘S` collapses it to the rail) | Table (pointer) / rows (touch) | Docked if card width ≥ 1040 dp, otherwise overlay | Centered | Separate window (desktop) or in-shell page (web, tablet) |

Default desktop window: 1280 × 800. Card width = 1280 − 220 − 8 = 1052 dp, so the inspector
**docks** (320 dp) and the table keeps 731 dp. Screenshot tests run at 360, 600, 840, 1024, 1280
and 1440 dp widths.

### 4.2 Shell and window chrome

#### 4.2.1 Expanded (desktop 1280 × 800, macOS, This Mac active, one task inspected)

```
┌ canvas + wash (ember glow bottom-left) ─────────────────────────────────────────────────────────────────────┐
│ ● ● ●   ◧  ☾       ┌ content card r16 e1 ────────────────────────────────────────────────────────────────────┐ │
│                    │ Downloads (2↓/14) [LM This Mac ▾]   [⌕ Search or paste a link   ⌘K] (☰│▦)   ⋯ (+ Add) │ │ 52
│ ⤓ Downloads     2  │ (All 14|Downloading 2|Waiting 3|Paused 1|Done 7|Failed 1●)          Sort: Smart ▾       │ │ 40
│ ✦ Discover         │─────────────────────────────────────────────────────────────┬────────────────────────────│ │
│ ◇ Devices          │ ☐ NAME ▾                       SIZE       PROGRESS     SPEED │ ▣ ubuntu-24.04-desktop-…  ✕ │ │ 28
│                    │ DOWNLOADING · 2 · 9.1 MB/s · all done ≈ 14:32               │ 5.7 GB · releases.ubuntu… │ │ 24
│ DEVICES     ⇧⌘D    │ ● ▣ ubuntu-24.04-desktop.iso  2.4/5.7 GB ▰▰▱▰▰▱▰ 42% 6.4 MB/s│ (LM) This Mac             │ │ 36
│ ⊚ All devices 9.1M │ ● ▣ imagenet-part03.tar ⤓     12/38 GB   ▰▰▱▱▱▱▱ 32% 2.7 MB/s│ ▰▰▰▱▰▰▱▰▰▱▰▰▱▱▱▰▰▱ 10dp   │ │
│ (LM) This Mac 6.4M │ WAITING · 3                                                 │ 42% · 2.4 of 5.7 GB        │ │
│ (NB) NAS-Base 2.7M │ ○ ▣ blender-4.2.dmg   412 MB  Waiting for a free slot (2/2) ▷│ 6.4 MB/s · 2m 10s left     │ │
│ (DP) Den-PC  Off ² │ NEEDS ATTENTION · 1                            Retry all (1) │ (‖ Pause) (⧉ Copy link) (⋯) │ │
│ + Add device       │ ✕ ▣ q3-report.pdf      –      Access denied (403) [Edit link]│ (Overview|Connections 8|…) │ │
│                    │ TODAY · 5 · 3.2 GB                                          │ CONTROLS                   │ │
│                    │   ▣ ketch-cli.zip   1.2 GB  took 3m · avg 6.4 MB/s    14:02 │ Speed (∞|5M|2M|1M|⋯)       │ │
│                    │                                                             │ Conn. [−] 8 [+]  Auto (4)  │ │
│                    │                    ╭ ✓ Added ubuntu.iso → This Mac  Undo ╮  │ Priority (Low|Norm|High|⚡) │ │
│ ⚙ Settings  ⌘,     │                    ╰─────────────────────────────────────╯  │ Start  Now ▾               │ │
│                    │ (» Full speed ▾) ↓ 9.1 MB/s ▁▂▃▅▆▅▃ · 2↓ · 3 waiting · 1 failed · 412 GB free  ● Sharing :8642 🔔3 │ │ 32
│                    └──────────────────────────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Window chrome (`app/desktop`, `MacWindowChrome.kt`):**

- **macOS.**
  - Inside `Window { }`, set `rootPane.putClientProperty` for `apple.awt.fullWindowContent = true`,
    `apple.awt.transparentTitleBar = true` and `apple.awt.windowTitleVisible = false` on first
    composition. Desktop provides `LocalWindowChrome(top = 28.dp, leading = 78.dp)`, which is zero
    in full screen.
  - The sidebar's top 52 dp is the **title zone**:
    - traffic lights at x 12–80;
    - `◧` (28 dp ghost, "Hide sidebar  ⌃⌘S") at x 92;
    - the appearance toggle (28 dp ghost) at the zone's end: a moon in the light appearance
      ("Switch to dark") and a sun in the dark one ("Switch to light"). A click that lands on
      the system's own appearance goes back to following the system (`ThemeMode.System`);
      otherwise it saves Light or Dark. Settings → General still offers all three. Android's
      system bar icons and the iOS window's interface style follow the picked appearance.
    - There is no add button here: the page header's `+ Add` (§4.7.1), `⌘N`, `⌘V` and drops
      add downloads, so a second ⊕ beside it only competed with it.
  - Empty title-zone space and the page header's empty space are a `WindowDraggableArea`, and a
    double-click zooms the window.
  - `apple.awt.windowAppearance` follows `ThemeMode`.
  - Fallback flag: if spike testing on the bundled Temurin 21 shows clicks lost in the top 28 dp,
    move the title-zone buttons down 4 dp. As a last resort, `-Dketch.decoratedWindow=true`
    restores the native title bar.
- **Window title:** the status sentence (§4.4.3), e.g. "Ketch — 3 downloading · 45%", or
  "Ketch" when idle. Mission Control and the Window menu show it.
- **Windows and Linux:** native frame, with the same wash, sidebar and card inside.
  `◧` and the appearance toggle sit in the sidebar header row. A JBR custom title bar is out
  of scope (§8).
- **Web:** no title zone. The sidebar header is a 32 dp row with a 20 dp `KetchLogoTile`,
  "Ketch", `◧` and the appearance toggle at the end.
- **The 80 dp wordmark header is deleted on every platform** (`SidebarNavigation.kt:65-75`).

#### 4.2.2 Sidebar (Expanded, 220 dp, transparent over the wash)

From top to bottom:

1. **Title zone** (§4.2.1).
2. **Destinations**, 32 dp items, r8, inset 8. No section labels.
   - `⤓ Downloads`, with a downloading-count pill and a 6 dp failed dot when anything has failed.
   - `✦ Discover`: shown wherever `aiSettings.supported` is true (desktop JVM and Android), even
     when unconfigured (§4.11).
   - `◇ Devices` (`⌘0`).
   - The selected item uses the `sidebarItemSelected` pill. It is never accent-filled.
3. **`DEVICES`** eyebrow, with "⇧⌘D" shown on hover. Device rows, 36 dp each (§4.5.1):
   - `All devices`, only when there are 2 or more devices;
   - one row per device;
   - `+ Add device`.
   - It scrolls after 6 rows.
4. **Spacer.**
5. **`⚙ Settings  ⌘,`.**

Global status lives in the card's Pulse bar, not the sidebar, so it survives the collapsed rail.
`⌃⌘S` (Ctrl+Shift+S elsewhere) toggles the sidebar to the rail at any width of 600 dp or more.
The choice is persisted in `UiPreferences.sidebarCollapsed`.

#### 4.2.3 Medium and collapsed rail (72 dp)

```
┌──────┬────────────────────────────────────────────────────┐
│● ● ● │ Downloads (2↓/14) [NB NAS ▾]  [⌕ ⌘K]     ⋯  (+)   │
│  ◧   │ (All|Downloading 2|Waiting 3|Paused|Done|Failed 1●) │
│  ☾   │ ▣ ubuntu-24.04-desktop.iso          6.4 MB/s · 2:10 │
│ ⤓ 2  │   2.4 of 5.7 GB · 8 connections                     │
│ ✦    │   ▰▰▱▰▰▱▰▰▱▰▰▰▱▱                                   │
│ ◇    │ ▣ blender-4.2.dmg                ○ Waiting   ▷ Start │
│ ──── │   Waiting for a free slot (2 of 2 in use)           │
│ (⊚)◔ │                                                     │
│ (LM)●│                                                     │
│ (NB)²│                                                     │
│ (DP)○│                                                     │
│  ⚙   │ (» Full ▾) ↓ 9.1 MB/s · 2↓ · 3 waiting   ● Live     │
└──────┴────────────────────────────────────────────────────┘
```

- Header area holds `◧` (wide windows only) and the appearance toggle, as in the sidebar's
  title zone. On macOS, content starts 40 dp below the top.
- Destinations: 24 dp icons with 11 sp labels. Downloads carries a badge with the downloading
  count.
- Device stack below them, 40 dp pennants:
  - the All-devices avatar carries the **aggregate progress arc** (Σ downloaded ÷ Σ total of
    downloading tasks);
  - each device shows its health ring and failed badge;
  - tapping a pennant switches device;
  - each pennant is a drop target.
- Settings sits at the bottom.
- The Pulse bar stays in the card, so status is always visible.
- The page header shows a **scope chip** (`[NB NAS ▾]`) whenever the sidebar is collapsed or the
  rail is shown.

#### 4.2.4 Compact (phone)

```
┌──────────────────────────────────────┐
│ (NB)● Downloads                ⌕   ⋮ │ 64dp, collapses on scroll (enterAlways)
│       ↓ 4.2 MB/s · 2 active · Full ▾ │ subtitle: tap opens Pulse sheet
│ (All 14)(Downloading 2)(Waiting 3)(… │ 40dp chips, sticky, collapse with the bar
├──────────────────────────────────────┤
│ ╭ ⧉ Download copied link · ubuntu… ✕╮│ 40dp, only when a link is detected
│ ▣ ubuntu-24.04-desktop.iso        ‖  │ 72dp
│   2.4 of 5.7 GB · 6.4 MB/s · 2:10    │
│   ▰▰▰▱▰▰▱▰▰▱▰▰▱▰▰▰▱▰▰▱▱▰▰▱▰▰        │
│ ▣ blender-4.2.dmg                 ▷  │
│   Waiting for a free slot (2 of 2)   │
│ ▣ q3-report.pdf               Retry  │
│   Access denied (403)                │
│ ▣ ketch-cli.zip                   ↗  │ 64dp
│   1.2 GB · took 3m · 14:02           │
│                       ╭───────────╮  │
│                       │  + Add    │  │ ExtendedFAB → 56dp FAB on scroll
│                       ╰───────────╯  │
│  ⤓ Downloads    ✦ Discover  ◇ Devices│ only with ≥ 3 destinations (Android)
└──────────────────────────────────────┘
```

**Top bar:**
- Leading: a 32 dp pennant with its health ring and failed badge. Tap opens the **device sheet**
  (§4.5.3); long-press switches back to the previous device.
- Title: "Downloads" in `pageTitle`, with the subtitle in `numeralS` + `caption`:
  "↓ 4.2 MB/s · 2 active · Full ▾". Tapping the subtitle opens the **Pulse sheet**: `numeralXL`
  speed, a 60 s chart, the Full / Slow lane / Auto segmented control, counts and the disk bar.
- `⌕` expands into a full-width M3 `SearchBar`. It is also the command bar: a link becomes
  "Download on This phone", and plain text offers "✦ Discover …".
- `⋮` holds: Pause all, Resume all, Retry failed, Clear finished…, Clear missing (where files
  can be checked), Activity (with badge), Settings.

**Bottom bar:**
- The `NavigationBar` appears only when there are at least 3 destinations (Android with
  Discover: Downloads · Discover · Devices).
- iOS and web phones have no bottom bar; Devices is reached through the device sheet.
- **Settings is never a tab.**

**Add button:**
- `ExtendedFloatingActionButton("Add")`, 16 dp from the bottom-end corner above the insets.
- It shrinks to a 56 dp FAB when `firstVisibleItemIndex > 0`.
- Long-press adds the clipboard link immediately, to the active device.
- The list has 88 dp bottom `contentPadding`, so the FAB never covers the last row's action.

**Chrome budget:** about 104 dp static (64 + 40), and about 0 while scrolling, versus about
294 dp today.

### 4.3 Navigation model

| Destination | Shortcut | Notes |
|---|---|---|
| Downloads (home) | `⌘1` (All tab) | Landing on every platform. Launches triggered by a link, magnet, `.torrent`, extension capture or notification land here with the task highlighted (`rowSelected` fading over 1.2 s). |
| Discover | `⌘E` | Visible where `aiSettings.supported`. A setup page is shown when unconfigured (§4.11). |
| Devices | `⌘0` | Fleet overview (§4.6). |
| Settings | `⌘,` | A separate window on desktop, an in-shell page on web and tablets, a page on phones (§4.10). |
| Activity | `⌘J` | A 380 × 520 popover opened from the Pulse bar 🔔 badge. On phones it is a sheet from `⋮`. It is not a destination. |

`AppDestination` becomes `{ Downloads, Discover, Devices }`. Settings is a surface, not a
destination.

### 4.4 Pulse bar, speed modes and the status sentence

#### 4.4.1 `PulseState` (computed in `AppState`, no API change)

| Field | Source |
|---|---|
| `scope` | Active device, or All devices |
| `totalSpeed` | Σ `Downloading.progress.bytesPerSecond` over the scope |
| `history` | 60-sample ring buffer at 1 Hz (per device; stacked when the scope is All devices) |
| `counts` | `downloading, waiting, paused, done, failed`, using **exactly** the `StatusTabs` definitions (§4.7.2) |
| `cap` | The real global limit from `InstanceSettingsController.download.speedLimit`. The controller is hoisted into `AppState` and refreshed from `api.status().config` on device switch, on window focus, and after every `updateConfig`. This fixes `AppShell.kt:334`. |
| `disk` | `status().system.usableSpace` / `totalSpace` / `downloadDirectory`, polled every 30 s and after each completion |
| `mode` | `SpeedMode.Full` / `SlowLane` / `Auto(until)` from `SpeedModeController` |
| `health` | Connection state of the scoped device(s); `Sharing on :8642` for the embedded device |

#### 4.4.2 Pulse bar (32 dp, inside the card, at the bottom, `surfaceSunken`, hairline on top)

```
(» Full speed ▾) ↓ 9.1 MB/s ▁▂▃▅▆▅▃ · 2↓ · 3 waiting · 1 failed · 412 GB free           ● Sharing :8642   🔔 3
```

1. **`SpeedModePill`** (h24, `full`).
   - Full speed (no cap): outline style, label "Full speed", or "Capped · 20 MB/s" when a
     standing cap exists.
   - Slow lane: `status.paused` soft fill, `SlowLane` glyph, label "Slow lane · 1 MB/s".
   - Auto: `Auto` glyph, label "Auto · Slow lane until 18:00".
   - Any active cap tints the pill amber.
   - Click toggles Full ↔ Slow lane (`⇧⌘L`).
   - The chevron opens a 280 dp popover:
     - segmented control Full speed | Slow lane | Auto;
     - `SpeedLimitPicker` chips;
     - checkbox "Use as Slow lane speed";
     - link "Speed settings…" (deep-links to `SettingsCategory.Speed`).
   - **Apply** = `api.updateConfig(current.copy(speedLimit = …))` on the active device, or on
     every connected device under All devices (toast: "Slow lane on 3 devices · Undo").
2. **Speed:** "↓ 9.1 MB/s" in `numeral`, then a 64 × 14 dp sparkline (accent stroke 1.5 dp, 12%
   fill). Under All devices it stacks one band per device in its pennant hue. Clicking it opens a
   320 dp popover with a 5-minute chart, one row per device, and the limit as a dashed line.
3. **Counts:** "2↓ · 3 waiting · 1 failed". Each part is clickable and opens its tab. "failed" is
   in the failed color when greater than 0.
4. **Disk:** "412 GB free" plus a 24 × 3 dp bar. It turns amber when the bytes still to download
   for waiting tasks exceed the free space.
5. **Right side:** the health badge (`● Live`, `◐ Reconnecting · 8 s`, `● Sharing :8642`,
   `○ Not shared`), then `🔔 n` (Activity, unread count).

While rows are selected, the right side shows "3 selected · 2.4 GB · 9.1 MB/s". Parts collapse
from the right as the card narrows: Disk goes below 720 dp, Counts below 600 dp.
`SpeedStatusBar.kt` and `BandwidthReadout` are **deleted**.

**Slow lane default value:** 30% of the observed 7-day peak (max of `totalSpeed`, stored in
`UiPreferences.observedPeak`), never below 256 KB/s, and 1 MB/s until there is history. Settings
copy: "≈ 3.1 MB/s · 30% of your usual top speed".

Rows within 10% of an active cap append "· limited by Slow lane" (or "· limited by its own 2 MB/s
limit") and show `⤓` after the speed.

#### 4.4.3 Status sentence (`PulseState.sentence()`, pure and unit-tested)

| Condition | Sentence |
|---|---|
| Idle | "All quiet · 14 finished this week · 412 GB free on This Mac" (the "finished this week" part is shown only once completion times exist, W6; until then: "All quiet · 412 GB free on This Mac") |
| Active | "Downloading 3 files on 2 devices · all done ≈ 14:32" (Σ remaining ÷ total speed, using the effective cap) |
| Failures | "1 download needs attention" |
| Device offline | "NAS-Basement is offline · retrying" |
| Throttled | the sentence plus " · Slow lane until 18:00" |

Used as the macOS window title (short form "Ketch — 3 downloading · 45%"), the tray header and
tooltip, the Devices page subtitle, and the phone top-bar subtitle.

### 4.5 Devices: switching, presence and targeting

#### 4.5.1 Naming and presence

- **One word: "Device".** The embedded device is named by `expect fun localDeviceNoun()`: "This
  Mac", "This PC", "This computer" (Linux), "This phone", "This iPad". Its host name is secondary
  text.
- Remote names: `RemoteConfig.name` (new optional field), else `DiscoveredServer.name` (no longer
  discarded, `AddRemoteServerDialog.kt:182-185`), else `KetchStatus.name` fetched on first
  connect. `host:port` is secondary text. "Rename…" is available everywhere a device appears.
- **`DevicePresence`** (`instance/DevicePresence.kt`), one per configured device:
  - `api`, `connection`, `name`, `version`, `uptime`;
  - `speed`, `counts`, `unseenFailures`;
  - `disk`, `speedMode`, a 60-sample history.
  - Derived from that device's `tasks` flow, plus `status()` every 30 s.
- **Keep-alive.**
  - `InstanceManager.switchTo` only changes the active entry and **never closes clients**.
  - Reconnection builds a **fresh** `RemoteKetch` through `InstanceFactory`. This fixes the reuse
    of a closed client (`InstanceManager.kt:108-116`).
  - Desktop and web keep every device with `RemoteConfig.watch = true` connected (new field,
    default true, at most 5 devices auto-watched).
  - Mobile disconnects non-active remotes 10 minutes after the app goes to the background, and
    reconnects them on resume.
  - The last active device is remembered.
- No library change is needed. The optional summary SSE event is in W6.

#### 4.5.2 Sidebar device row (36 dp)

```
(NB) NAS-Basement            2.7 MB/s
 ↑ 24dp pennant, health ring, failed badge ²      live line: numeralS, right-aligned
```

| Live line | Shown when |
|---|---|
| "6.4 MB/s" | Downloading |
| "2 waiting" | Only waiting tasks |
| "Idle" | Nothing active |
| "Offline" (failed color) | Disconnected |
| "Needs token" (failed color) | Unauthorized |
| "Slow lane" glyph | Next to the speed when that device is capped |

- **Click** switches device instantly (no reconnect).
- **Right-click** opens a menu:
  - Switch `⌘⌥n`
  - Pause all here
  - Resume all here
  - Retry failed here (n)
  - Speed ▸ Full speed / Slow lane
  - Settings for this device…
  - Rename…
  - Stay connected ✓
  - Remove…
- **Drop target:** while links, files or dragged rows hover over it, the row grows to 48 dp and
  reads "Drop to download here · 1.8 TB free", or "Send 2 downloads here · ⌥ moves" for rows.
- **`All devices` row:** three stacked 16 dp pennants, total speed, `⌘⌥0`.

#### 4.5.3 Device switcher popover (`⇧⌘D`) and phone device sheet

```
╭── 320dp, surfaceRaised r12 e3 ───────────────────────────────╮
│ (⊚)  All devices        ↓ 9.1 MB/s · 4 active          ⌘⌥0  │
│ ──────────────────────────────────────────────────────────── │
│ (LM)✓ This Mac          Lins-MacBook-Pro · 6.4 MB/s · 2  ⌘⌥1 │
│ (NB)  NAS-Basement      2.7 MB/s · Slow lane · 1 failed  ⌘⌥2 │
│ (DP)  Den-PC            Offline · last seen 2 h ago      ⌘⌥3 │
│ ──────────────────────────────────────────────────────────── │
│ + Add device…      ⌕ Find on network      ⇪ Share this Mac…  │
╰──────────────────────────────────────────────────────────────╯
```

- Rows are 56 dp.
- "Find on network" is hidden when `LanServerDiscovery.supported == false` (web).
- On phones the same rows appear in a `ModalBottomSheet`, plus "Overview" (opens Devices),
  "Scan pairing code" and "Settings".
- `InstanceSelectorSheet.kt` (the `AlertDialog`) is deleted.

#### 4.5.4 All devices mode (`⌘⌥0`)

- The Downloads table merges the tasks of every connected device. The **Device** column turns on
  automatically, and list rows get a 16 dp pennant before the name.
- Actions route to the owning `KetchApi` (each `DownloadTask` belongs to its device).
- Tabs, the Pulse bar and the tray aggregate across devices.
- Pause all and Resume all apply to every device; the toast reads "Paused 7 downloads on
  3 devices · Undo".
- Offered only when there are at least 2 devices.

#### 4.5.5 Targeting another device

| Surface | How |
|---|---|
| Intake sheet / AI results | `On: (LM) This Mac ▾` chip (§4.9.3), `⌘⌥1-9` while open |
| Drop | Drop berths (§4.9.7), or onto a sidebar or rail device |
| Existing task | Row menu "Send to ▸", inspector "Send to ▾", drag a row onto a device row |
| Phone share sheet | QuickAdd sheet device chip |

**Send to** calls `target.download(task.request.copy(resolvedSource = null))` with the same
speed, priority and connections. Progress starts over on the target. Copy rules:

- Toast: "Sent ubuntu.iso to NAS-Basement · Show · Remove here".
- Holding `⌥` while dropping moves the task (removes it here after a successful add), with Undo.
- When `request.headers` is not empty, the dialog warns: "Cookies from your browser will be sent
  to NAS-Basement." [Send] [Cancel]

### 4.6 Home decision and the Devices page

There is **no dashboard landing page.** Downloads is home, because the common jobs (add, watch,
open, retry) happen there. The Pulse bar answers the dashboard questions on every screen: how
fast, what is throttling me, how much is waiting, and how much room is left. What a
single-engine dashboard cannot show is a fleet, so the overview page is **Devices** (`⌘0`).

```
╭ card ──────────────────────────────────────────────────────────────────────────────────────────────╮
│ Devices                                                      (Pair a phone) (+ Add device)          │
│ Downloading 3 files on 2 devices · all done ≈ 14:32                                                 │
│ ╭ (LM) THIS MAC ────────── ● ⋯ ╮ ╭ (NB) NAS-BASEMENT ─────── ● ⋯ ╮ ╭ (DP) DEN-PC ──────────── ○ ⋯ ╮ │
│ │ Ketch 0.0.1 · macOS arm64 · up 3 h │ Ketch 0.0.1 · Linux x64 · up 12 d│ Offline for 2 h · retrying  │ │
│ │ 6.4 MB/s          (» Full ▾)  │ │ 2.7 MB/s       (⤓ Slow lane ▾)│ │                              │ │
│ │ ▰▰▰▰▱▰▰▱▱▱  device lane       │ │ ▰▰▱▱▱▱▱▱▱▱                    │ │ (Retry now)  (Remove)        │ │
│ │ 2 Down │ 1 Wait │ 0 Paused │ 1 ✕│ │ 1 Down │ 2 Wait │ 0 │ 0 ✕     │ │                              │ │
│ │ ▬▬▬▬▬▬▱ 412 GB free of 1 TB   │ │ ▬▬▬▱▱▱▱ 1.8 TB free of 4 TB   │ │                              │ │
│ │ ~/Downloads                   │ │ /volume1/downloads            │ │                              │ │
│ │ (en0 + en7)(Sharing :8642)    │ │ (eth0)                        │ │                              │ │
│ │ (Retry 1 failed) (Pause all here)│ (▷ Start blender.dmg now)     │ │                              │ │
│ │ ▁▂▃▅▆▅▇▆▅▄▅▆▇▆▅ edge sparkline│ │ ▁▁▂▂▃▂▂▃▂▂▃▂                  │ │                              │ │
│ ╰───────────────────────────────╯ ╰───────────────────────────────╯ ╰──────────────────────────────╯ │
╰─────────────────────────────────────────────────────────────────────────────────────────────────────╯
```

**Grid:** `LazyVerticalGrid(GridCells.Adaptive(300.dp))`, 16 dp gaps, 24 dp padding (16 on
phones, single column).

**`DeviceCard`** (`ui/devices/DeviceCard.kt`): 236 dp tall, `lg`, e2, padding 20.

- **Header:** 32 dp pennant with a device-type glyph; the name in `eyebrow` style; health dot;
  `⋯` menu (Rename, Scope to this device, Settings for this device, Stay connected ✓, Remove).
- **Meta:** `caption` line "Ketch {version} · {os} {arch} · up {uptime}" from `KetchStatus`.
- **Speed:** `numeralXL` with the unit in `caption`, plus that device's `SpeedModePill`. Clicking
  the pill toggles that device's mode.
- **Device lane:** a 6 dp strip; each downloading task is a block sized by its remaining bytes.
- **Counts row** with 1 dp dividers: Downloading | Waiting | Paused | Failed. Clicking a count
  switches to that device **and** that tab (1 click).
- **Storage bar** (4 dp): "{usable} free of {total}", then `downloadDirectory` in `mono`.
- **Chips:** the selected network interfaces from `networkInterfaces()`, by the name the OS
  reports (`NetworkInterfaceInfo.name`, e.g. "en0 + en7"; all available ones when none are
  selected). Friendly kinds such as "Wi-Fi + Ethernet" need a `NetworkInterfaceInfo.kind` field
  (API work, not scheduled in these waves; §8). Two or more chips make multi-network bonding visible. For the
  embedded device: "Sharing on :8642 · discoverable", or "Not shared · Share…".
- **Next actions** (Calm Glass graft; each shown only when relevant): `Retry {n} failed`,
  `Pause all here`, `▷ Start {first waiting} now`.
- **Footer:** a 40 dp sparkline that bleeds to the bottom edge.
- **Offline card:** dims to 60%, shows "Offline for 2 h · retrying" (or "Needs a new access
  token") with [Retry now] / [Enter token] and [Remove].
- The whole card is a drop target: "Drop to download on NAS-Basement".

**With only one device:** its card, plus a dashed "Add a device" card:

> Control a NAS or another computer from here. Run `ketch server` there, or turn on
> Settings → Sharing in its Ketch app.

with buttons [Pair a phone] and [Find on network] (the latter hidden on web).

**Hidden until W6:** "Transferred today / all time" and the activity heatmap (they need
`completedAt` plus a per-day ledger); an upload tile (needs a `TorrentController`).

### 4.7 Downloads

#### 4.7.1 Page header (52 dp)

Left to right:

1. `Downloads` in `pageTitle`.
2. A count pill "2↓/14" (downloading / total in scope).
3. The scope chip `[(LM) This Mac ▾]`, shown when there are 2 or more devices **or** the sidebar
   is collapsed.
4. **Right side:**
   - The command field `⌕ Search or paste a link  ⌘K`: 32 dp tall, 240 dp wide, 320 dp on focus,
     collapsing to a `⌕` icon below 640 dp card width.
   - `KetchPillGroup` (`☰` List | `▦` Table), where the table fits. The inspector has no toggle;
     it follows the selection (§4.8).
   - `⋯` overflow, **always present**, with items disabled rather than hidden:
     - Pause all `⇧⌘P`
     - Resume all `⇧⌘R`
     - Retry failed `⌥⌘R`
     - Clear {n} finished…
     - Select all `⌘A`
     - Copy all links
     - Columns…
     - Row density ▸ Compact / Default
   - Primary `+ Add` (h36, `⌘N`, `KetchAddButton`). Width capped at 28% of the card
     (180–300 dp). Static under reduce motion.
     - Hover: the + splits into three lanes and the fill picks up an ember-to-accent gradient
       (ember behind the glyph, accent by 55%).
     - Where the clipboard reads silently (Windows, Linux; Android before 12) and holds a link
       Ketch doesn't have: one sheen, then a split button "⤓ Add {name, shortened in the middle
       to fit} | ▾". The main part quick-adds it and stops offering it; the caret opens the add
       sheet. Tooltip "Download {name} from {host}" with ⇧⌘V.
     - While a drag hovers the window: a dashed "Drop to download" target with filling lanes,
       lit while the drag is over it. Drops add as on the window (the drop berths start below
       the page header on Downloads).

Nothing destructive sits next to Add. Pause all and Resume all no longer appear or disappear with
state (`BatchActionBar.kt:27-35`). Chrome above the table is 52 + 40 + 28 = 120 dp, versus
148 dp today plus the 28 dp bottom strip.

#### 4.7.2 Status tabs (40 dp row)

`KetchSegmented` with: **All · Downloading · Waiting · Paused · Done · Failed** (`⌘1`–`⌘6`).

- Counts follow each label in `numeralS` and are omitted at 0. The Failed count is white on
  `status.failed` when greater than 0.
- `StatusFilter` becomes `{All, Downloading, Waiting, Paused, Done, Failed}`:
  - Waiting = Queued + Scheduled.
  - Failed = Failed + Canceled.
  - Downloading = `DownloadState.Downloading`.

  The Pulse bar, the tray and device rows use **exactly** these definitions. This ends "Active 3"
  appearing next to "1 active" (`StatusFilter.kt:14-16`, `AppShell.kt:162-166`).
- Right side of the row: `Sort: Smart ▾` in List mode. In Table mode it is replaced by
  `Group: Smart ▾`, because sorting happens through the column headers.
- **Facet chips** appear in a 32 dp row under the tabs when any are active: Type ▾, Site ▾,
  Origin ▾ (Device ▾ under All devices).
- The **Done** tab header shows `Clear {n} finished` (text button, undoable). The **Failed** tab
  header shows "{n} failed · Retry all · {k} need a new link". The **Waiting** tab sorts by
  priority (Urgent first), then `createdAt`, and scheduled tasks by start time.
- With 2 or more rows selected, the tab row **becomes the selection bar** at the same height
  (150 ms cross-fade, §5.3).

#### 4.7.3 Search and facets

The command field filters live as you type. It matches the decoded display name, host, referer
host, output path and error title. Tokens turn into removable chips:

| Token | Values |
|---|---|
| `is:` | `downloading`, `waiting`, `paused`, `done`, `failed`, `scheduled`, `urgent`, `stalled`, `limited` |
| `type:` | `video`, `audio`, `image`, `doc`, `archive`, `app`, `torrent`, `other` (FileKind folded into 8 groups) |
| `host:` | e.g. `host:github.com` |
| `origin:` | `browser`, `discover`, `agent`, `app`, `cli` |
| `device:` | a device name |
| `size:` | e.g. `size:>1gb` |
| `added:` | `today`, or e.g. `added:<7d` |

- `⌥`-clicking a cell value (such as a host) adds it as a token.
- The match count "12 of 340" sits right-aligned in the facet row.
- **Origin** needs only a key convention: `request.properties["ketch.origin"]`, set to
  `browser` (extension `request.js`), `discover` (plus `ketch.query`, from Discover), `agent`
  (`KetchToolSet`), `cli`, or `app`. W3-ORIGIN-TAGS sets it in the extension, MCP and CLI;
  W3-INTAKE-SHEET and W4-DISCOVER set it in the app. Tasks without the key show "Unknown" and are
  excluded from origin facets rather than guessed.
- `util/DownloadSearch.kt` is rewritten to search decoded fields.

#### 4.7.4 Per-state row content (single source: `util/RowContent.kt`, consumed by the table, list rows and inspector)

| State | Status cell / dot | Line 2 or reason | Size | Speed | Time | Primary action | Hover / secondary |
|---|---|---|---|---|---|---|---|
| Downloading | ● accent (pulsing) | "{n} connections · {host}" (+ "· limited by Slow lane") | "2.4 / 5.7 GB" | "6.4 MB/s" (+`⤓`) | ETA "2m 10s" | Pause (`Space`) | ⋯ |
| Stalled (Downloading at 0 B/s for more than 5 s, client-side) | ● amber, static | "Stalled · no data for 12 s" | as above | "0 B/s" | "–" | Reconnect (pause, then resume) | ⋯ |
| Paused | ◐ amber | "Paused · 42%" | "2.4 / 5.7 GB" | blank | blank | Resume | ⋯ |
| Queued | ○ grey | Reason from `QueueReason`: "Waiting for a free slot (2 of 2 in use)" or "Waiting for github.com (8 per site)", using `status().config` and the running tasks | size or "–" | blank | blank | ▷ Start now | ⋯ |
| Scheduled | ◷ violet | `AtTime`: "Starts today at 23:00 · in 3h 12m" (refreshed every 60 s). `AfterDelay`: "Starts after 30 min" (no invented clock time) | size | blank | blank | ▷ Start now (local only) | ⋯ |
| Completed | none | `transferSummary()` from #305: "took 3m 12s · avg 6.4 MB/s · {host}" | `Completed.totalBytes` | blank | "took 3m" | **Open** (double-click / `↩`) | `↗ Open`, `⌂ Show in Finder` |
| Completed, file missing (checked via `FileActions.exists` on hover, menu open and while the Done tab shows; "Clear 2 missing" in the Done header and the `⋯` menu removes them, with Undo) | ⚠ tertiary | "File moved or deleted" | size | | | Download again | |
| Completed on a remote device | none | "Saved on NAS-Basement" | size | | | Copy path | ⋯ |
| Failed | ✕ red | `ErrorCopy.title` in failed color, then " · " + short hint | size or "–" | blank | blank | `ErrorCopy.primary` (Retry / Download again / Retry with 1 connection / Edit link… / Enter credentials… / Show folder) | ⋯ |
| Canceled | ⊘ tertiary | "Canceled · {host}" | | | | Download again | ⋯ |

**Start now:**
- **Queued:** `setPriority(URGENT)`. The task that gets bumped is detected as a
  Downloading→Paused transition within 2 s. Toast: "Started blender.dmg · paused debian.iso to
  make room · Undo". Undo restores the old priority and resumes the bumped task.
- **Scheduled on a local device:** `reschedule(Immediate)`.
- **Scheduled on a remote device:** hidden. `RowAction` capability `canReschedule = false` until
  W6.

**Download again** = `AppState.redownload(task)`: `api.download(request.copy(schedule = Immediate,
resolvedSource = null))`, then `task.remove(deleteFiles = false)`. Toast: "Restarted
ubuntu-24.04.iso". This replaces the Retry that does nothing on canceled tasks.

#### 4.7.5 Table (default whenever the table area is at least 720 dp with pointer input)

```
 ☐  NAME ▾                          SIZE        PROGRESS               SPEED      LEFT     ADDED          STATUS
 ─ DOWNLOADING · 2 · 9.1 MB/s · all done ≈ 14:32 ─────────────────────────────────────────────────────────────────
 ●  ▣ ubuntu-24.04-desktop.iso      2.4/5.7 GB  ▰▰▰▱▰▰▱▱▰▱▰▰ 42%     6.4 MB/s   2m 10s   Today 11:42   Downloading
 ●  ▣ imagenet-part03.tar ⚡         12/38 GB    ▰▰▱▱▱▱▱▱▱▱▱▱ 32%     2.7 MB/s⤓  9m 41s   Today 09:10   Downloading
 ─ WAITING · 3 · in start order ──────────────────────────────────────────────────────────────────────────────────
 ○  ▣ blender-4.2-macos-arm64.dmg   412 MB      Waiting for a free slot (2 of 2 in use)            [▷ Start now]
 ◷  ▣ Big.Buck.Bunny               –           Starts today at 23:00 · in 9h 08m                  [▷ Start now]
 ─ NEEDS ATTENTION · 1 ─────────────────────────────────────────────────────────────────── Retry all (1) ─────────
 ✕  ▣ q3-report.pdf                 –           Access denied (403) · the link may have expired    [Edit link…]
 ─ TODAY · 5 · 3.2 GB ────────────────────────────────────────────────────────────────────────────────────────────
    ▣ ketch-cli-0.1.0-macos.zip     1.2 GB      took 3m 12s · avg 6.4 MB/s                 Today 14:02  [↗][⌂] ← hover
 ─ EARLIER · 315 ▸ (collapsed) ───────────────────────────────────────────────────────────────────────────────────
```

**Columns** (Compact widths; numerals right-aligned in `numeral`):

| Column | Width | Content |
|---|---|---|
| Status | 28 | 6 dp dot per §3.2.4; becomes a 16 dp checkbox on hover or whenever anything is selected |
| Name | flex, min 240 | 20 dp file chip + display name in `cellStrong` (middle ellipsis keeps the extension) + priority glyph + `↧2 MB/s` pill for a per-task cap; device pennant under All devices |
| Size | 96 | "2.4/5.7 GB" running; "5.7 GB" done; "–" unknown |
| Progress | 140 | 6 dp `LaneStrip` + "42%" in `numeralS` |
| Speed | 92 | "6.4 MB/s"; "0 B/s" for a real zero; muted "–" unknown; blank when not applicable. Sorted numerically |
| Left | 76 | ETA; "took 3m" when done |
| Added | 104 | "Today 11:42", "Yesterday", "Sep 28" (kotlinx-datetime, local zone) |
| Status text | 120 | dot label / short reason |
| Optional: Connections | 72 | count + 24 × 8 mini strip |
| Optional: Source | 128 | Referer host, else URL host; magnet glyph |
| Optional: Origin | 88 | Browser / Discover / Agent / App / CLI |
| Optional: Priority | 72 | glyph + word |
| Optional: Device | 112 | pennant + name; automatic under All devices |
| W6: Finished | 104 | needs `completedAt` |

**Behavior:**

- **Reason spans:** for Waiting, Failed and Done rows, the cells from Progress through Left merge
  into one reason line (`caption`), as in the wireframe.
- **Auto-hide by table width:** columns hide in this order when they do not fit: Added, then
  Status text, then Left, then Size. With the default widths, Added hides below 900 dp and Status
  text below 800 dp; Left and Size always fit above the 720 dp table minimum. Below 720 the view
  switches to List rows. Name, Progress and Speed never hide. With the inspector docked at the
  default 1280 × 800 window (table ≈ 732 dp), Status, Name, Size, Progress, Speed and Left show.
- **Header:** 28 dp, sticky, `eyebrow` on `surfaceSunken`.
  - Click to sort, click again to reverse (a 12 dp chevron shows the direction).
  - Right-click opens the column chooser (`KetchCheckbox` menu).
  - 6 dp handles resize columns.
  - Widths, order and visibility persist **per tab** in `UiPreferences.table`.
- **Smart grouping** (the default for All; `Group: Smart / Status / Day / Device / Site / Type /
  None`): sticky 24 dp group headers.

  | Group | Content |
  |---|---|
  | `DOWNLOADING · n · speed · all done ≈ HH:MM` | Priority descending, then progress descending |
  | `WAITING · n · in start order` | Queued by priority then `createdAt`; Scheduled by start time |
  | `PAUSED · n` | |
  | `NEEDS ATTENTION · n` | Failed and canceled, with `Retry all (n)` |
  | `TODAY · n · size`, `YESTERDAY`, `THIS WEEK` | Bucketed by `createdAt` and labelled "Added today" etc. until `completedAt` exists |
  | `EARLIER · n` | Collapsed when it has more than 50 rows |

  Sorting by a column orders rows inside each group. Clicking a group header collapses it.
- **Re-sort stability:** at most every 2 s; frozen while the pointer is over the list, a row has
  focus or a menu is open. Speed sorts use a 3-sample average.

#### 4.7.6 Display names (`util/DisplayName.kt`)

`displayName(request, state)` takes the first non-blank value from:

1. The basename of `Completed.outputPath`.
2. A file-name `Destination`, or the basename of a file destination. A directory destination
   (trailing separator, `./`, `.`) is skipped.
3. `request.resolvedSource?.suggestedFileName`.
4. The magnet `dn` parameter, URL-decoded.
5. The percent-decoded URL basename, ignoring `.`, `..` and empty segments.
6. "Magnet {first 8 hex of btih}".
7. The host.

The same function is used for rows, search, sort, dialogs, toasts and notifications. A live name
while a magnet is still downloading needs `DownloadTask.fileName` (W5).

#### 4.7.7 List rows (card width < 720 dp, touch input, or the user picks ☰)

```
▣  ubuntu-24.04-desktop-amd64.iso ⚡                 ● 6.4 MB/s · 2m 10s
   2.4 of 5.7 GB · 8 connections · releases.ubuntu.com
   ▰▰▰▱▰▰▱▰▰▱▰▰▱▰▰▰▱▰▰▱▱▰▰▱▰▰  4dp lanes
```

- Height: 56 dp (pointer) or 64/72 dp (touch, with lanes).
- Layout: 12 dp padding; a 28 dp chip (36 dp on touch); line 1 is the name in `bodyStrong` plus
  the priority glyph, with the metric right-aligned in `numeral`; line 2 is `caption` text joined
  with " · ", wrapping instead of truncating the speed; a 4 dp `LaneStrip` under line 2 while
  Downloading or Paused.
- Dividers are inset 60 dp.
- Hover: `surfaceHover`, `md`, inset 4. Two 28 dp hover actions fade in over the metric in 120 ms.
- Touch: a 44 dp trailing primary action. `SwipeToDismissBox`, acting past 40% of the row's
  width or on a fling, after which the row springs back:
  - start → end: Pause / Resume (`accentSoft`);
  - end → start: the Remove dialog, its box to also delete the file unchecked; removing then
    shows the Undo snackbar.
- Long-press enters selection mode (§5.3).

#### 4.7.8 Empty states and the launchpad

```
                       (SailLanesIllustration 280×200)
                              No downloads yet
               Paste a link, drop a file, or send one from your browser.
   ╭──────────────────────╮ ╭──────────────────────────╮ ╭──────────────────────────╮
   │ ⌘V  Paste a link     │ │ ⤓  Drop a link, magnet   │ │ ◫  Capture from browser  │
   │ ubuntu-24.04.iso     │ │    or .torrent anywhere  │ │ Chrome ✓  Firefox: Get → │
   │ (Download it)        │ │                          │ │                          │
   ╰──────────────────────╯ ╰──────────────────────────╯ ╰──────────────────────────╯
                     ✦ Or describe what you want · Discover  ⌘E
   SET UP KETCH                                                          ✕
   ✓ Downloads go to ~/Downloads                                (Change…)
   ○ Browser extension · Chrome detected                        (Get it)
   ○ Control this Mac from your phone                           (Show QR)
```

- Max width 560 dp, centered. The title is in `largeTitle`.
- **Tile 1, "Paste a link ⌘V".** When the clipboard holds a link not yet in Ketch, the tile shows
  it ("ubuntu-24.04.iso · releases.ubuntu.com") and becomes `[Download it]`. On window focus the
  desktop app only checks whether text is available (`isDataFlavorAvailable`); it reads the text
  on Windows and Linux, but on macOS it reads only after a user action, because macOS 26 can show
  a "paste from other apps" prompt for programmatic reads (the tile then shows `[Paste]`). Android
  and iOS detect a link without reading it (`ClipDescription` URL confidence,
  `UIPasteboard.detectPatterns`) and read on tap. Web reads only on an explicit tap.
- **Tile 2, "Drop a link, magnet or .torrent anywhere".** A 1.5 dp dashed outline lights up
  during a drag.
- **Tile 3, "Capture from your browser".** Shows per-browser status from
  `LocalIntegrationStatus`, which desktop provides from `NativeHostRegistration` and
  `BrowserExtensionServer`. On phones it becomes "Control your computer's downloads" and opens
  pairing.
- The Discover line is shown only where Discover is supported.
- **Setup checklist** (desktop and web): shown for 7 days or until done. It is dismissible and
  reopened from Help → Setup checklist. It replaces a modal desktop onboarding flow.
- **Remote device:** "Downloads on NAS-Basement will appear here" with [Add a link to
  NAS-Basement].
- **Filtered empty:**
  - "Nothing needs attention" (Failed tab, with a check glyph).
  - "Nothing waiting · This Mac runs 2 at a time" (Waiting tab).
  - "No downloads match "ubu"" [Clear search].
  - "No failed downloads on NAS-Basement" [Show all devices].

#### 4.7.9 Remove and undo

- **`⌫` / "Remove from list"** hides the rows at once (`AppState.pendingRemovals`) and shows a
  6 s toast: "Removed 5 downloads · Undo". `task.remove(deleteFiles = false)` runs when the toast
  expires, on `⌘Z`-free timeout, or when the app quits (pending removals are flushed on dispose).
  No dialog.
- **`⇧⌫` / "Remove and delete files…"** opens `RemoveTasksDialog`:

  ```
  ╭ Remove 3 downloads? ──────────────────────────────────╮
  │ From the list on This Mac.                            │
  │ ☑ Also move the files to the Trash · 2.4 GB           │
  │   Frees 2.4 GB · 48.2 GB free on This Mac             │
  │                       (Cancel) (Remove and trash 2.4 GB)│
  ╰───────────────────────────────────────────────────────╯
  ```

  - The checkbox is unchecked by default and pre-checked when the dialog is opened with Shift.
  - The confirm button reads "Remove" (Secondary) when the box is unchecked, and becomes **Danger**
    "Remove and trash 2.4 GB" when checked.
  - On a local desktop device: `remove(deleteFiles = false)`, then
    `Desktop.moveToTrash(File(outputPath))` (macOS and Windows; Linux falls back to delete). Toast:
    "Moved 3 files to the Trash".
  - Everywhere else the label is "Also delete the files permanently · 2.4 GB" and the button reads
    "Remove and delete 2.4 GB".
  - Single task: title "Remove "ubuntu-24.04.iso"?". Multi-file torrent: "all 14 files · 3.2 GB".
    Partial download: "the partial file · 812 MB of 2.1 GB".
- **Cancel** is no longer a one-click icon. It lives in the overflow as "Stop and discard
  progress…", with the confirmation "ubuntu.iso can't be resumed after this." [Keep] [Discard
  progress].
- **`Clear {n} finished`** (Done tab header and `⋯`) uses the same deferred commit: "Cleared 12
  downloads · Undo".
- **Pause all** pauses Queued tasks first, then Downloading ones, inside `supervisorScope`. Toast:
  "Paused 6 downloads · 2 scheduled still start at 02:00 · Undo". Undo resumes exactly those IDs.

#### 4.7.10 Drag

- **Completed local rows** (desktop): `Modifier.dragAndDropSource` with a file-list `Transferable`.
  A multi-selection carries all completed files, and the preview shows chip + name + "3 files"
  badge.
- **Remote rows and the web:** the drag carries the link (`text/uri-list`).
- **Dragging rows onto a device row, rail pennant or Devices card** sends them there (§4.5.5).

### 4.8 Task inspector

`ui/inspector/TaskInspector.kt` replaces `DownloadExpandedPanel`, `ExpandedSettingsRow` and
`TaskSettingsPanel`.

**Placements:**

| Tier | Placement |
|---|---|
| Card width ≥ 1040 | **Docked** right pane inside the card. 320 dp, drag-resizable 280–480 with a 6 dp handle; the width persists, capped so the table keeps its 720 dp minimum and a click never swaps it for list rows. It slides in from the card's edge, pushing the table aside. 1 dp divider; the table reflows by auto-hiding columns. |
| Card 600–1039, or Medium | **Overlay** card: `surfaceRaised`, `lg`, e3, inset 8, 340 dp. Slides in from x+24 dp over 220 ms with decelerate easing. Esc or a click on empty space closes it. |
| Compact | `ModalBottomSheet`. The 55% peek shows the header, actions, lanes and Controls; drag up for the tabs. |

**State and opening:**
- `AppState.inspectedTaskId` survives filter and search changes and clears when the task is
  removed.
- There is no toggle: the inspector shows while a row is inspected or 2 or more are selected,
  and goes away with the selection (Esc, a click on empty space) or its ✕.
- A single click on a row selects and inspects it; the arrow keys and ⌘-clicks that leave one row
  selected show that row.
- `⌘I` (Show details) shows the selected row again after ✕ closed it. `→` moves focus into the
  inspector, `←` or Esc returns it.
- While it closes it keeps what it showed, so it never flashes another view on the way out.
- With **nothing selected** there is no inspector. A device's numbers live on the Pulse bar and
  on its card on the Devices page (speed, lane, counts, free space, networks, Start next).
- With **2 or more selected**, it shows "3 selected · 2.4 GB · 9.1 MB/s", one stacked 4 dp map per
  task, and shared Controls ("—" where values differ).

#### 4.8.1 Header

```
▣  ubuntu-24.04-desktop-amd64.iso                          ✕
   5.7 GB · releases.ubuntu.com · (LM) This Mac
▰▰▰▰▱▰▰▱▰▰▱▰▰▰▱▰▰▱▰▰▰▱  10dp lanes with write heads
42% · 2.4 of 5.7 GB · 6.4 MB/s · 2m 10s left · done ≈ 14:32
↳ Limited by Slow lane (1 MB/s)                 [Full speed]
(‖ Pause)  (⇄ Send to ▾)  (⋯)
(Overview | Connections 8 | Activity)
```

- A 40 dp chip. The name in `titleM`, at most 2 lines; click copies it.
- Subline: size · host · device chip with health dot.
- A 10 dp `LaneStrip`, then the metric line in `numeral`.
- **Reason line** (`labelS` `textSecondary` plus one inline chip):
  - "Waiting for a free slot · 2 of 2 in use" [Start now]
  - "Starts 01:00 · in 3h 12m" [Start now]
  - "Stalled · no data for 12 s" [Reconnect]
  - "Paused · 2.4 of 5.7 GB" [Resume]
  - "Limited by Slow lane (1 MB/s)" [Full speed]
- **Action bar** (32 dp buttons):

  | State | Buttons |
  |---|---|
  | Downloading | [‖ Pause] [⇄ Send to ▾] [⋯] |
  | Paused | [▷ Resume] |
  | Waiting | [▷ Start now] |
  | Failed | [primary recovery] [⋯] |
  | Completed, local | [↗ Open] [⌂ Show in Finder] (phones: [Open] [Share]) |
  | Completed, remote | "Saved on NAS-Basement" [⧉ Copy path] |

  `⋯` holds: Copy link, Copy path, Retry with options…, Download again, Send to ▸, Stop and discard
  progress…, Remove….
- **Tabs** (28 dp segmented control; a tab with no data is hidden):
  - Overview.
  - Connections {n} for HTTP/FTP, or Files {n} for torrents. A task is a torrent when its URL
    starts with `magnet:` or `torrent:`, ends with `.torrent`, or
    `resolvedSource.sourceType == "torrent"`.
  - Activity.
  - Peers, Trackers and Pieces appear only once `KetchApi.torrents` is implemented (W6).

#### 4.8.2 Problem card (Overview, failed tasks only)

`status.failed` at 8% fill, 1 dp border at failed 30%, `md`, padding 12. Title in `titleM`, hint
in `bodyS`, up to 2 small buttons from `ErrorCopy`, then "Copy details · Open logs" links. See the
catalog in §5.6.

```
╭──────────────────────────────────────────╮
│ ✕ Access denied (403)                    │
│ The link has probably expired. It came   │
│ from Chrome with cookies; capture it     │
│ again from the page.                     │
│ (Edit link…) (Open source page)          │
│ Copy details · Open logs                 │
╰──────────────────────────────────────────╯
```

#### 4.8.3 Controls and Details (Overview)

**CONTROLS**, for non-terminal tasks. Rows are 36 dp with an 88 dp label column in `caption`
`textTertiary`.

| Control | Spec |
|---|---|
| Speed | Segmented: `Unlimited │ 5 MB/s │ 2 MB/s │ 1 MB/s │ ⋯`. The three numeric presets are the round values just below the current speed (at 8.4 MB/s: 5, 2, 1). `⋯` opens a 280 dp popover: a log slider from 64 KB/s to 100 MB/s with detents at 256K, 512K, 1M, 2M, 5M, 10M, 20M and 50M, plus the `SpeedLimitPicker` field. The global cap is marked on the slider ("Global 5 MB/s"). Caption when the global cap is lower: "Slow lane 1 MB/s applies to all downloads". |
| Connections | `ConnectionStepper` 1–32, debounced 400 ms, calling `setConnections`. The lanes animate the split or merge. Shows "Auto (4)" when `request.connections == 0` (from `status().config.maxConnectionsPerDownload`); going back to Auto needs `setConnections(0)` (W5). If segments stay at 1 for 3 s after a raise: disabled with "This server allows 1 connection". Torrents: "Peer limit" 1–512, step 10. |
| Priority | Segmented Low │ Normal │ High │ ⚡ Urgent (28 dp; Urgent segment in accent). If Urgent is chosen while all `maxConcurrentDownloads` slots are busy, an inline note appears before applying: "Starts now; may pause "debian-12.iso" (Low)". |
| Start | "Now ▾" or "Starts 01:00 · in 3h 12m ▾" opens `StartTimePicker`. For a running task it confirms inline: "Pauses now and starts at 01:00". On remote devices it is disabled with the tooltip "Scheduling remote downloads isn't supported yet" until W6. |
| Device | "(LM) This Mac", plus "Send to ▸". |

**DETAILS** rows are click-to-copy: a 14 dp copy glyph appears on hover, and the value reads
"Copied" for 1.5 s. On phones, long-press copies.

| Row | Content |
|---|---|
| Source | "HTTPS · releases.ubuntu.com", "FTP · …", or "BitTorrent · magnet" |
| Link | Host in Medium plus the path middle-ellipsized on one line. The query string (signed tokens) stays behind "Show full link". This fixes the 10-line URL in `ketch-current-expanded-row.jpg`. |
| Saved to | `mono` path + "Show ›" |
| Size | |
| Added | "Today 11:42" |
| Time spent / Avg speed | Completed only, from #305 `downloadTime` and `transferSummary()` |
| Connections | |
| Device | |
| Captured | "From browser, with cookies and referrer" when the extension forwarded headers |
| ▸ Advanced | Task ID, headers summary (names only, values masked), properties |

The "Task ID" row moves under Advanced. URL and priority duplicates are removed.

#### 4.8.4 Connections tab (the signature view)

```
6 active · 2 finished · 6.4 MB/s                    [−] 8 [+]
▰▰▰▰▰▰▰▰▰▰▰▰▌▰▰▍▱▱▰▏▱▱▱▰▰▰▌▱▱▰▰▍▱▱▰▏▱▱▏   16dp file map, heads
#3 ▰▰▰▰▰▱▱▱▱  1.4–2.1 GB       1.3 MB/s  ●
#4 ▰▰▰▰▱▱▱▱▱  2.1–2.8 GB       1.1 MB/s  ●
#5 ▰▰▱▱▱▱▱▱▱  2.8–3.5 GB         0 B/s  ◐ stalled 6 s
#6 ▰▰▰▱▱▱▱▱▱  3.5–4.2 GB       1.2 MB/s  ●
✓ 2 finished · 1.4 GB
Changing connections re-splits the remaining bytes live.
```

- **File map** (16 dp): segments at their byte offsets. Finished runs merge into one. Seams appear
  only between unfinished segments and only when there are 32 or fewer. Each active segment has a
  write head.
- **Lanes:** one per unfinished segment, keyed by `segment.start`:
  - "#3" in `monoS`;
  - an 8 dp bar of that segment's own progress;
  - range in `caption`;
  - rate right-aligned in 72 dp;
  - a 6 dp dot: green = moving; amber = stalled 3–10 s; red = over 10 s.
- **Rates** come from `util/SegmentRateTracker` (pure, commonTest):
  - diffs consecutive snapshots matched by `start`, using `TimeSource.Monotonic`;
  - smooths with an EMA, α = 0.35;
  - ignores negative deltas (from resegmenting);
  - marks a lane stalled after 3 s with Δ = 0 while the task is Downloading.

  This replaces `health = List(n) { 1f }` (`DownloadExpandedPanel.kt:100`).
- **Scale:**

  | Lanes | Row height |
  |---|---|
  | 2–8 | 28 dp |
  | 9–16 | 20 dp |
  | 17–32 | 12 dp, no text, sorted by rate, slowest 3 annotated |

  The panel is capped at 320 dp and scrolls.
- **Captions:**
  - 1 lane: "Single connection".
  - 1 lane for 3 s after more were requested: "This server allows only 1 connection".
- Paused, failed and queued tasks with progress show the map without heads.
- Hovering a lane (tapping on phones) highlights its region in the map and in the row strip.
- Semantics: "8 connections, 6 active, 42 percent, 1 stalled".
- **W6 / API:** per-lane speed from the engine, retry count, and tinting by network interface.

**Files tab (torrents):**

```
14 files · 9 done · 3.2 of 7.9 GB          [⌕ Filter files]  Incomplete first ▾
▰▰▰▰|▰▰▰|▰▱▱|▰▰▰▰▰|▱▱▱   one block per file, sized by bytes
▶ S01E01.1080p.mkv                 1.1 GB   100%           ↗
▶ S01E02.1080p.mkv                 1.1 GB    42%  ▰▰▰▱▱
```

- A virtualized `LazyColumn` of table-height rows (`density.tableRow`) that fills the inspector's
  height: scrolling it scrolls the inspector's header away first, so the summary, sort and map
  stay above the list. Shown outside the inspector, the tab is capped at 360 dp.
- Names come from `resolvedSource.files` by id, else "File 12".
- Sort: Incomplete first / Name / Size. The filter field appears above 20 files.
- **Persisted names, and changing the selection after adding, need W5/W6.**

#### 4.8.5 Activity tab

- **`SpeedHistoryStore`** (`state/`): 1 Hz, 300 samples per Downloading task (about 2.4 KB each),
  fed from task creation, so the chart is already full when opened.
- **Chart**, 120 dp:
  - y-axis from 0 to a round ceiling of max(peak, limit), labelled top-left in `numeralS`;
  - dashed lines for the task limit and the global limit;
  - stacked per-connection bands;
  - hover crosshair "11:42:08 · 9.8 MB/s".
  - Footer: "Peak 31.2 MB/s · Avg 14.0 MB/s · 8 connections".
- **Session timeline**, titled "Since Ketch opened": Added, Started, Paused, Resumed,
  Connections 4 → 8, Limit → 2 MB/s, Priority → Urgent, Failed (problem title), Completed. Built
  from observed `state` and `requestState` changes. Persistent history is in W6.

### 4.9 Add flow (intake)

All entry points feed **one** `IntakeController` (`state/IntakeState.kt`) and
`util/LinkParser.parseIntake(text): List<IntakeItem>`.

**Parser rules:**
- Finds http(s), ftp(s), magnet and `.torrent` URLs anywhere: one per line, in prose or in HTML.
- A bare 40-hex or 32-base32 info hash becomes `magnet:?xt=urn:btih:…`.
- A scheme-less `host.tld/path` gets `https://`.
- `curl …` goes to `util/CurlParser`: URL, `-H`, `-b`/`--cookie`, `-e`/`--referer`, `-A`;
  `--compressed` is ignored.
- Ranges `part[01-12].rar` and `img{a,b,c}.png` expand.
- Text that is not a link becomes a Discover intent.
- **Duplicates:** normalized URL (fragment removed) or the magnet's btih matches an existing task.

#### 4.9.1 Quick add (paste without a dialog)

Trigger: `⌘V` while the shell or list has focus and no text field is focused, through a
**bubbling** `onKeyEvent` so text fields keep normal paste. On web the text comes from a
document `paste` listener (`Clipboard.pasteEvents`), which needs no permission prompt; a keydown
alone cannot read the clipboard there. Exactly **one plain http(s) or ftp link**. "Add pasted
links immediately" is on (default on desktop and web).

- The link is added at once to the target device (the active device; under All devices, the last
  target used) with sticky defaults.
- Toast: "✓ Added ubuntu-24.04.iso · 5.7 GB → This Mac" [Options] [Undo].
  - Undo = `remove(deleteFiles = true)`, available for 8 s.
  - Options opens the sheet bound to the new task and applies changes through `setSpeedLimit`,
    `setPriority` and `setConnections`.
- If another status tab is shown, the view switches to All.
- Every add from this window (`AppState.addedTasks`: quick add, drops, the add sheet, Discover)
  flies a lane from the header's Add button to the first new row on screen; that row and the
  other new ones glow (`rowSelected`, 300 ms hold then 900 ms fade). Rows off screen glow when
  they show within 20 s. No lane under reduce motion or without the header button (phones).
- **Two or more links, a magnet, a `.torrent`, a cURL command, or plain text open the intake sheet
  prefilled instead.**

#### 4.9.2 Intake sheet (`ui/intake/IntakeSheet.kt`, replaces `AddDownloadDialog`)

- Desktop and web: 640 dp wide (480–720), **anchored 72 dp below the window top** like a command
  palette, `xl`, e4, scrim. It grows to 80% of the window height.
- Phones: a bottom sheet as tall as its input while empty, then full height with a sticky 56 dp
  primary button.

```
╭─ Add downloads ───────────────────────────────────────────── On: (LM) This Mac ▾ ─╮
│ ╭───────────────────────────────────────────────────────────────────────────────╮ │
│ │ https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso              │ │
│ │ https://cdn.example.org/set/part[01-04].rar                                   │ │
│ │ magnet:?xt=urn:btih:3f2a91c0…&dn=Big.Buck.Bunny                               │ │
│ │ https://intranet.example.com/q3-report.pdf                                    │ │
│ │ From clipboard ✕  7 links · 5 ready · … · 29.1 GB                    [📋][🧲] │ │
│ ╰───────────────────────────────────────────────────────────────────────────────╯ │
│ ▣ ubuntu-24.04-desktop-amd64.iso   5.7 GB · HTTPS · resumable · up to 16       ✕ │
│ ▣ part01.rar … part04.rar          4 × 1.1 GB · expands to 4 links              ✕ │
│ ◌ Big.Buck.Bunny                   Fetching file list from peers… 0:14 (Add all) ✕ │
│ ! q3-report.pdf   The server refused access (403) (Paste as cURL)(Add headers)  ✕ │
│ ▣ report.zip      Already in Ketch · finished 2 days ago   (Open)(Download again) │
│ (⌂ Save to: Downloads · 412 GB free ▾) (≡ Options ▾) (⚡ Urgent ✕)                │
│ ⓘ 2 start now (2 of 2 slots free) · 4 queued · ≈ 12 min at current speed          │
│                             ↩ to download   (Cancel) (Download 6 items · 29.1 GB) │
╰───────────────────────────────────────────────────────────────────────────────────╯
```

**Input** (`ui/intake/PasteArea.kt`):
- One paste/drop area, `body` sans 14/20, 3 lines tall when empty, growing with its text to 8
  lines (4 while the list shows), then scrolling. It switches to `mono` only when a line is a
  `curl` command.
- Placeholder: "Paste links or magnets — or drop a .torrent file" (phones: "Paste links or
  magnets").
- Inside the area, at its end: Paste (tooltip "Paste from clipboard ⌘V"; hidden when the
  clipboard mode is Off) and Open .torrent file (tooltip "Open .torrent file ⌘O"). The area's
  bottom line holds "From clipboard ✕" and, for a batch, the summary (or "cURL command").
- While empty, a link on the clipboard (read silently; or system-detected on phones in Suggest
  mode) shows the accent chip "Paste ubuntu-24.04.iso from clipboard" (phones: "Paste
  ubuntu-24.04.iso", or "Paste copied link" when only detected); never after the sheet was filled
  from that clip.
- `⌘N` prefills it from the clipboard when the clipboard holds a link that is not in Ketch and was
  not offered before (a hash of the last offer is kept in `UiPreferences`). The text is selected,
  with "From clipboard ✕" on the area's bottom line.
- A drag over the sheet gives the area an accent border, `accentSoft` fill and "Drop to add".
- Everything below the input (rows, options, outcome, main button) appears only once a link or
  torrent is in (expand + fade, `medium`; instant under reduce motion). Retry and edit sheets
  show it at once.
- Keys: `↩` submits once at least one item is ready; `⇧↩` adds a line; `⌥↩` sends the text to
  Discover; Esc closes (asks first only when the user typed more than 1 line). `⇧↩` shows as the
  input's tooltip once it holds text.
- A paste (the field grows by more than 8 characters at once) resolves immediately; typing keeps
  the 500 ms debounce.

**Items:**
- One 44 dp row per item: 24 dp chip; name in `bodyStrong` (click to rename); a `mono` 12 meta or
  status line.
- Resolution runs on the target device, 4 at a time (`Semaphore(4)`). Editing a line cancels that
  line's job. Rows beyond 50 resolve lazily; at most 200 rows.
- **Status sequence:**
  - spinner "Checking…";
  - "5.7 GB · HTTPS · resumable · up to 16 connections";
  - amber pill "No resume";
  - red `IntakeProblems` title with actions;
  - amber "Already in Ketch · finished 2 days ago" [Open] [Show] [Download again]. Duplicates are
    excluded by default.
- **Single item:** a preview card replaces the list. It has a 40 dp chip and a 6 dp lanes preview
  under Connections, "16 × 360 MB", redrawn as the stepper changes. The main button reads
  "Download <name>" (middle ellipsis at 36 characters).
- **Summary**, on the input's bottom line for a batch: "7 links · 5 ready · 1 checking · 1 needs
  attention · 29.1 GB". When the total exceeds the target's `usableSpace`, an amber line reads
  "Needs 48.2 GB · only 31.0 GB free on This Mac".

**Footer:** one hint left of the buttons, "↩ to download / schedule / retry / start over /
apply". The main button says what it does: "Download <name>", "Download 3 files · 1.2 GB"
("items" when a torrent is in the batch), "Schedule 2 files", "Download 16 files · 9.3 GB" in the
torrent stage, "Waiting for file list", "Retry", "Start over", "Apply changes". It is hidden, not
greyed, while the sheet holds nothing to add. Cancel stays (phones close from the header's ✕).
Phones: the empty sheet is as tall as its input.

#### 4.9.3 Option pills

Pills are 28 dp, `full`. The label is `labelS` `textTertiary` and the value `labelS` SemiBold.
The accent tint marks a non-default value. Each pill opens a `KetchMenu` on desktop or a sub-sheet
on phones, and applies to every item.

| Pill | Menu |
|---|---|
| `⌂ Save to: Downloads · 412 GB free` | **Default** (`status().system.downloadDirectory`) · **Recent:** up to 5 parent folders of this device's `Completed.outputPath` and directory destinations (no new storage) · **Pinned** (`UiPreferences.favoriteFolders[deviceId]`, "+ Pin current") · **Choose folder…** (this device only; `FilePicker.pickFolder()`) · **Sort by type** (Video → Movies, Audio → Music, others → Downloads; remembers the last folder per `FileKind`). Remote targets and web: a path field with completion from recent folders, captioned "Folder on NAS-Basement". The destination is built as `folder + system.separator (+ name)`, using the target device's `separator`. An Android SAF tree URI is used as is (a directory `Destination`); a custom file name cannot be combined with it until the API gains a per-request file name, so the File name field is disabled for SAF folders with the caption "Uses the server's file name". Torrents cannot write to SAF folders (`docs/torrent.md`, "Storage and restart"), so for magnet and torrent items the pill falls back to the device's app folder and says so. Remote browsing needs W6 (`GET /api/fs/dirs`). |
| `≡ Options: Unlimited · Normal · Now · Auto` | One popover (a bottom sheet on touch) holding `SpeedLimitPicker`, the priority segmented control with its caption (Low "Runs when nothing else is waiting" · Normal "Default order" · High "Ahead of Normal and Low" · ⚡ Urgent "Jumps the queue; may pause a lower-priority download"), `StartTimePicker` (not for Retry) and `ConnectionStepper` (range 1 to min(resolved.maxSegments, 32), Auto reset; "Peer limit" for torrents; disabled with "This server allows 1 connection" when `maxSegments ≤ 1`), then "Advanced options…". When scheduled, the primary button reads "Schedule 6 files". "Add paused" needs `DownloadSchedule.Manual` (W5). "Only on Wi-Fi" and "While charging" need serializable conditions (W5). |
| `On: (LM) This Mac` (header) | Only with 2 or more devices. Rows: pennant, health, "1.8 TB free · 2 active · Slow lane", `⌘⌥n`. Resolution re-runs on the new target. Remembered per kind for the session ("magnets → NAS"). Cookie warning when headers are present (§4.5.5). |

- Values changed from their defaults leave the summary for removable chips after the pill
  ("Max 5 MB/s ✕", "⚡ Urgent ✕", "High priority ✕", "Starts 23:00 tonight ✕", "8 connections ✕";
  ✕ restores the default), and once anything changed the pill reads just "Options".
- The row never wraps: it drops the "Save to" label, then the free space, then the summary, then
  the "Options" label, then scrolls.
- The sheet that edits a task shows the four controls in place.
- **Sticky defaults** per device: folder, priority and connections. Speed and start reset each
  time.
- **Advanced** (opened from "Advanced options…"; an "ADVANCED · Hide" header; open state still
  remembered): File name (single item only), Referer, User-Agent (Ketch / Chrome / Firefox /
  Safari / Custom), Cookie (masked, multi-line), Authorization, "+ Add header", and [Paste cURL].
  A "2 headers" chip after the pill opens it while it is closed.
  - Headers go to **both** `resolve(url, properties = headers)` (for HTTP sources the
    `properties` of `resolve` *are* request headers) and `DownloadRequest.headers`. Bookkeeping keys
    such as `ketch.origin` go only to `DownloadRequest.properties`, which Ketch never reads, and
    never to `resolve()`.
  - Caption: "Headers are saved with the task and visible to devices connected to this one."
- **Outcome line**, computed from task states and `status().config`:
  - "Starts now · 1 of 2 slots free · ≈ 4 min at current speed"
  - "Queued · 3rd in line"
  - "⚡ Urgent: starts now and pauses debian-12.iso (Low)"
  - "Waits for github.com · 8 per server"
  - A scheduled start is left to the Start chip.

#### 4.9.4 Submit

- The primary button shows a spinner until every `api.download` returns. **Each item is added on
  its own**, so one failure never stops the rest.
- Toast:
  - single item: "✓ Added ubuntu.iso → This Mac" [Show] [Undo];
  - batch: "Added 5 downloads · 1 failed" [Review] (reopens the sheet with the failed rows);
  - another device: "Added to NAS-Basement" [Show], where Show switches the device.
- If the active tab or search hides the new rows, the view switches to All and clears the
  search; the new rows are pointed out as in §4.9.1.

#### 4.9.5 Problems (`util/IntakeProblems.kt`)

| Input / error | Behavior and copy |
|---|---|
| Missing scheme | Fixed silently (`https://`) |
| `KetchError.Unsupported` | "Ketch can't download this kind of link · it supports http(s), ftp(s), magnet and .torrent"; for a magnet or a dropped `.torrent` file, "This device can't download torrents" |
| A dropped file that can't be read | "Couldn't read this file", with the reason |
| HTTP 401 / `AuthenticationFailed` | "Sign-in required", with inline user and password fields sent as `Authorization: Basic …` (FTP: embedded in the URL as today) |
| HTTP 403 | "The server refused access (403) · links copied from a signed-in page often need its cookies" [Paste as cURL] [Add headers] |
| HTTP 404 / 410 | "Not found · the link may have expired" [✦ Find a working mirror] (only where Discover is usable) |
| HTTP 429 / 5xx | "Server busy (503) · Ketch will retry automatically" (adding still allowed) |
| Network | "Can't reach example.com" [Retry] |
| Magnet timeout (120 s) | "No peers sent the file list in 2 min" [Keep waiting] [Add anyway] |

While an item is in error, it is excluded from the batch. The primary button becomes "Retry" only
when **every** item failed, with "Add anyway" as the secondary.

#### 4.9.6 Torrent stage (a magnet or torrent row expands in place; full-screen step on phones)

```
╭ ‹ Big.Buck.Bunny · 3 of 128 files · 4.2 of 61.3 GB          On: (NB) NAS-Basement ╮
│ [⌕ Filter files  ⌘F]  (Video 12)(Subtitles 12)(Audio 3)(Images 40)(Other 61)      │
│ (Largest file) (Skip samples)                                       Sort: Name ▾  │
│ ◐ ▾ Season 1/                                                          4.2 GB     │
│   ☑ ▶ S01E01.1080p.mkv                                                 1.4 GB     │
│   ☐ ▶ S01E02.1080p.mkv                                                 1.4 GB     │
│   ☑ ▤ S01E01.en.srt                                                     48 KB     │
│ ☐ ▸ Extras/  (sample.mkv, info.nfo)                                    880 MB     │
│ Skipped 3 extras · Undo        ▰▰▱▱▱▱▱▱  needs 4.2 GB · 1.8 TB free on NAS        │
│                                     (Download all) (Download 3 files · 4.2 GB)    │
╰───────────────────────────────────────────────────────────────────────────────────╯
```

**Before metadata arrives:**
- "Fetching file list from peers… 0:14" with a 3 dp indeterminate bar.
- [Download all files now] and [Finish in background]. Finish in background closes the sheet and
  puts a "Resolving 1" chip in the page header; when metadata arrives, the sheet reopens, or a
  toast appears if the user has moved on.
- The primary button is **disabled** and reads "Waiting for file list", so an early click can no
  longer grab 61 GB.

**Picker:**
- Height min(60% of the window, 420 dp).
- Folder tree from the metadata path, with tri-state checkboxes. Rows are 28 dp with sizes in
  `numeral`.
- `⇧`-click selects a range; Space toggles.
- Kind chips select only that kind; `⌥`-click adds it.
- Extras are skipped by default: `.nfo`, `.txt` under 1 KB, names containing "sample".
- A free-space bar compares the selection with the target's disk.

#### 4.9.7 Drop berths

While links, magnets, `.torrent` files, or lists of links (`.txt`, `.csv`, or `.url` and
`.webloc` shortcuts) are dragged over the window, the drop overlay (`surfaceRaised` at 96%) splits into one berth per **online** device. Up
to 4 sit in a row; more form a 2 × n grid. Offline devices show disabled berths.

```
 ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  ┏━━━━━━━━━━━━━━━━━━━━━━━┓  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ┐
         (LM)           ┃         (NB)          ┃        (DP)
   Drop on This Mac     ┃ Drop on NAS-Basement  ┃       Den-PC
 412 GB free · 2 active ┃ 1.8 TB free · 3 links ┃       Offline
 └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘  ┗━━━━━━━━━━━━━━━━━━━━━━━┛  └ ─ ─ ─ ─ ─ ─ ─ ─ ┘
             Links, magnets, .torrent files and lists of links
```

- Each berth: 1.5 dp dashed outline, `lg`, 48 dp pennant, name, "free · active".
- The berth under the pointer fills with `accentSoft` and shows what will be added ("3 links",
  "ubuntu.torrent").
- With one device, there is a single berth: "Drop to download · links, magnets and .torrent
  files".
- A drop follows the quick-add rules with the berth's device as the target.
- `FileDropTarget` and `FileDrop` gain `text/uri-list` and plain text on JVM
  (`DataFlavor.stringFlavor`), web and Android. This also ends the "Only .torrent files can be
  dropped" error (`AppState.kt:227-229`).

#### 4.9.8 Retry with options

`⌥`-click Retry, or row menu "Retry with options…", opens the sheet prefilled from `requestState`
(url, headers, destination, speed, priority, connections), with the problem card on top.

- If only speed, priority or connections changed: the setters run, then `resume()`. Progress is
  kept.
- If the URL or headers changed: warning "Starts over · 1.2 GB downloaded so far will be
  discarded.", then remove + add. Keeping progress needs `DownloadTask.updateSource` (W5, open
  question).

### 4.10 Settings

**Surfaces:**

| Platform | Surface |
|---|---|
| Desktop | A separate, **non-modal** `Window("Settings")`, 860 × 640 (min 640 × 480), bounds remembered. `⌘,` opens or focuses it; `⌘W` or Esc closes it. The main window, Pulse bar and live limits stay visible. |
| Web, tablets | An in-shell page (sidebar Settings item shown as selected; Esc returns to the previous destination) |
| Phones | A grouped list page that opens sub-pages |

Changes still apply as they are made. The footer reads "Changes apply as you make them." Desktop
has no landing grid of category cards; the category list opens straight onto a page.

```
╭ Settings ───────────────────────────────────────────────────────────────────────────────╮
│ [⌕ Search settings  ⌘F]      │ Speed                                on (LM) This Mac ▾ │
│ THIS APP                     │ MODE                                                     │
│ (▣) General                  │ (Full speed | [Slow lane] | Auto)                        │
│ (▣) Notifications            │ Full speed cap        (Unlimited ▾)                      │
│ (▣) Integration              │ Slow lane speed       (1 MB/s ▾) · ≈ 3.1 MB/s suggested  │
│ (▣) Discover                 │ AUTO                                                     │
│ (▣) About                    │ Slow lane on (M)(T)(W)(T)(F) S S   09:00 – 18:00   ✕     │
│ DEVICE  (LM) This Mac ▾      │ + Add rule                                               │
│ (▣) Downloads                │ On metered networks (Android)  (Slow lane ▾)             │
│ (▣) Speed                    │ PER DOWNLOAD                                             │
│ (▣) Network                  │ Connections per download     [−] 4 [+]                   │
│ (▣) BitTorrent               │                                                          │
│ (▣) Sharing                  │                Changes apply as you make them.           │
╰─────────────────────────────────────────────────────────────────────────────────────────╯
```

#### 4.10.1 Information architecture (`state/SettingsCategory.kt`)

- **THIS APP:** General · Notifications · Integration · Discover (hidden where unsupported; About
  then says "Discover runs in the desktop and Android apps") · About.
- **DEVICE [chip ▾]:** Downloads · Speed · Network · BitTorrent (embedded device only) · Sharing
  (embedded device only; renamed from "Remote access").
  - The chip edits **any connected device** without switching the main window.
  - The same pages open from a device's menu or card ("Settings for NAS-Basement").
- **Nav items** beside the open page are single-line sidebar items (32 dp in Compact, §3.7)
  with a 24 dp `KetchHueTile` and the page name, like the app's sidebar. The phone's list of
  pages adds a live summary in `caption` `textTertiary`, which the nav items give screen readers
  as their state instead:
  - General: "Light · Signal"
  - Downloads: "~/Downloads · 2 at a time"
  - Speed: "Slow lane · 1 MB/s · Auto weekdays"
  - Network: "en0 + en7" (interface names)
  - BitTorrent: "3 trackers"
  - Sharing: "On · 192.168.1.20:8642"
  - Discover: "Claude · Brave search"
- **Copy:** a row's helper text is one short line (about 60 characters at most) and only says
  what its label does not. Caveats such as "after a restart" join that line, or a one-line note
  under the group.
- **Hue map:** General = Slate, Notifications = Amber, Integration = Indigo, Discover = Violet,
  About = Slate, Downloads = Blue, Speed = Orange, Network = Teal, BitTorrent = Jade,
  Sharing = Sky.
- **Search settings** (`⌘F` inside Settings) filters rows by title and description, highlights the
  matches, and `↩` jumps to the row.
- **Deep links:** `settingsCategory` is hoisted out of `SettingsDialog`'s local `rememberSaveable`
  (`SettingsDialog.kt:52`), so Settings reopens where it was left.
  `openSettings(category, deviceId)` is called from the Speed pill, error cards, device menus, the
  palette ("/speed") and the launchpad checklist.

#### 4.10.2 Pages

- **General:**
  - Device name.
  - Appearance: Theme (System / Light / Dark, segmented), Accent (4 swatches with names),
    Density (Auto / Compact / Comfortable), Reduce motion (Auto / On).
  - Language: System only for now (§8).
  - **Startup & window** (desktop):
    - "When I close the window": [Keep downloading in the menu bar | Quit Ketch] (Windows: "…in
      the notification area").
    - "Open Ketch at login".
    - "Start hidden when opened at login".
    - "Dock badge": [Active count | Failures only | Off].
  - "Keyboard shortcuts…" opens the `⌘/` sheet.
- **Notifications:**
  - "Download finished" [Notify | In-app only | Off].
  - "Download failed" [Notify | In-app only | Off].
  - "All downloads finished" [On/Off].
  - "Devices going offline" [In-app | Off].
  - "Only when Ketch is in the background" ✓.
  - Per device: "Notify me about this device" (also keeps that device connected).
  - On web, the permission is requested from these toggles, never on load.
- **Integration:**
  - Browser extension: one row per detected browser (Chrome, Edge, Brave, Firefox) with live
    status "Connected ✓" or [Get extension].
  - "Open magnet links with Ketch": status "Ketch is the default ✓" or [Make default].
  - "Open .torrent files with Ketch".
  - "Suggest links from the clipboard": [Fill automatically | Suggest | Off]. Default Fill on
    desktop, Suggest on phones and web.
  - "Add pasted links immediately" ✓.
- **Discover:**
  - Provider buttons: OpenAI, Anthropic, Gemini, "Ollama · runs locally, no key", Custom
    (OpenAI-compatible).
  - Token, model, endpoint, search keys.
  - [Test] shows "Connected · claude-… responded in 1.2 s".
- **About:** version and revision, links, licenses (including font OFL notices), "Open logs
  folder" / "Share logs", "Show setup checklist", "Show welcome again" (phones), the brand line.
- **Downloads (device):**
  - "Save downloads to": `mono` path pill with middle ellipsis, "412 GB free", [Change…]
    (`FilePicker.pickFolder()`, this device only) and [Show]. The raw field moves behind
    "Enter path…".
  - Android: a warning row appears when the path is under `/Android/data`: "Only Ketch can see this
    folder. Choose a folder such as Download so other apps can open your files." [Choose folder].
  - Recent / Pinned folders.
  - Steppers: Run at once, Per server, Retries.
  - For remotes, a notice until W6: "Saved on NAS-Basement until it restarts".
- **Speed (device, new):**
  - Mode segmented control.
  - Full speed cap.
  - Slow lane speed with the suggestion.
  - **Auto rules:** at most 3, each "Slow lane on [day chips] from [HH:MM] to [HH:MM]". Android
    adds "On metered networks: [Slow lane | Pause | Full speed]".
  - Connections per download.
  - `state/SpeedScheduler.kt` evaluates rules with kotlinx-datetime and applies them through
    `updateConfig` for the embedded device while the app runs (the Android service keeps it alive).
    For remotes it works only while this client is connected; daemon-side rules are not scheduled
    (§8).
- **Network:** interface chips with live state and the copy "HTTP only. FTP and torrents use
  the system default." The runtime-only note stays, as one line under the group.
- **BitTorrent:** trackers editor (unchanged).
- **Sharing:** the Pair a device card (§4.10.4), then **Advanced** (collapsed): port, token
  (masked, [Rotate]), discoverable (mDNS), CORS hosts, start with Ketch.

#### 4.10.3 One speed picker

`SpeedLimitPicker` (§3.11) is used in Settings, intake, the inspector and the Pulse popover.
`SettingsChoices.SpeedLimitPresets` and `presetSpeedOptions` merge into it. Decimals are accepted
everywhere.

#### 4.10.4 Pair a device

```
PAIR A DEVICE
╭────────────╮  Control this Mac from your phone, tablet or another browser.
│ ▓▓▓ ▓ ▓▓▓▓ │  (Allow another device)   ← one button: host 0.0.0.0, token if missing,
│ ▓ ▓▓▓ ▓ ▓▓ │                            mDNS on, server started
│ ▓▓ ▓ ▓▓▓ ▓ │  Scan with your phone's camera, or open http://192.168.1.20:8642
│ ▓▓▓▓ ▓ ▓▓▓ │  Lins-MacBook-Pro · 192.168.1.20 · also 10.0.0.4
╰────────────╯  (⧉ Copy pairing link) (↗ Open web app)
                Anyone with it can control This Mac.   (New code)
```

- **QR** (180 dp, `qrose`) of
  `ketch://pair?host=192.168.1.20&port=8642&name=Lins-MacBook-Pro#token=…`. The token sits
  **only in the fragment**, so it never appears in requests or logs.
- LAN addresses come from `networkInterfaces().available[].addresses`, with the first private IPv4
  in bold. When the device reports `supported = false`, the card shows the host name only.
- **Add device sheet** (replaces `AddRemoteServerDialog`):
  - Its first field is "Pairing link or address". It parses `ketch://pair…`,
    `http(s)://host:port/#token=…` and `host[:port]`.
  - The Host / Port / Token / HTTPS fields collapse under "Enter details manually".
  - The Token field is revealed automatically after a 401.
- Phones register the `ketch` scheme. The camera opens a confirmation sheet: "Connect to
  Lins-MacBook-Pro?" [Connect].
- The web app reads `#token=` on load and clears it with `history.replaceState`.

### 4.11 Discover (AI) placement

- **Visibility:** shown wherever `aiSettings.supported` (desktop JVM, Android), **even when not
  configured**. Hidden on iOS and web until a server discovery endpoint exists (§8).
  `AppDestination.visible` changes from `available` to `supported` (`AppDestination.kt:21-22`).
- **Unconfigured setup page:**

  ```
                         (KetchHueTile Violet 48, Discover glyph)
                      Describe it. Ketch finds the download.
       (Blender for Apple silicon) (Ubuntu 24.04 server ISO) (Public-domain 4K nature footage)
       (OpenAI) (Anthropic) (Gemini) (Ollama · runs locally, no key)       Set up in Settings ›
  ```

  A provider button opens Settings → Discover with that provider preselected.
- **Configured:**

  ```
  ╭ Discover ─────────────────────────────────────────────────────────────────────────────╮
  │ ┌─────────────────────────────────────────────────────────┐ (Limit to websites ▾)(Find)│
  │ │ ✦ Blender 4.2 for Apple silicon                          │                           │
  │ └─────────────────────────────────────────────────────────┘                           │
  │ ✓ Searched the web · ✓ Opened blender.org/download · ◌ Checking mirrors…              │
  │ ☑ (▣48) blender-4.2.1-macos-arm64.dmg   412 MB · download.blender.org        92%     │
  │ ☐ (▣48) blender-4.2.1-macos-x64.dmg     430 MB · download.blender.org        61%     │
  │ ─────────────────────────────────────────────────────────────────────────────────────│
  │ 1 selected · 412 MB          On: (NB) NAS-Basement ▾       (Add now) (Review & add 1)│
  ╰───────────────────────────────────────────────────────────────────────────────────────╯
  ```

  - A 48 dp query field with the "Limit to websites" chip.
  - A **live step timeline**. This needs a change to the app's own interface only:
    `AiDiscoveryProvider.discover(request, onStep: (DiscoveryStep) -> Unit = {})` (today it is
    `discover(request: AiDiscoverRequest)`) wired to ai:discover's
    `DiscoveryStepListener`. No library API change.
  - Result rows (56 dp): `KetchHueTile` 48, title, "size · host", a confidence badge ("92%"),
    and the safety note.
  - **Review & add** opens the intake sheet with the candidates as rows, Referer = `sourceUrl`,
    and `properties["ketch.origin"] = "discover"` / `"ketch.query"`.
  - **Add now** keeps the one-click path.
  - Items are added independently ("3 added · 1 failed"). This fixes the `forEach` inside a single
    `runCatching` (`AppState.kt:459-479`).
- **Entry from intake and the palette:** text that is not a link offers "✦ Discover
  "ubuntu 24.04 server iso"" (`⌥↩`). A 404 offers "Find a working mirror".

### 4.12 Error, offline and loading states

- **`DeviceStatusBanner`** (40 dp at the top of the card, on every destination, `md` inset 8):

  | Condition | Copy |
  |---|---|
  | Connecting for more than 3 s | "Connecting to NAS-Basement…" (paused soft) |
  | Disconnected | "NAS-Basement is offline · retrying · Connection refused" [Retry now] [Switch to This Mac] (failed soft) |
  | Unauthorized | "NAS-Basement needs a new access token" [Enter token] — **never** auto-opens a modal (`AppState.kt:184-195`) |

  - Metrics from an offline device dim to 50%, and speeds show "—".
  - On reconnect: toast "Reconnected to NAS-Basement" (3 s).
  - A countdown "retrying in 8 s" needs W6 (`ConnectionState.Disconnected(nextRetryAt)`).
- **App errors** (add failed, switch failed, …) are error toasts with an action, never a banner
  pushed into the content (`AppShell.kt:392-423` is deleted).
- **Loading:**
  - First task sync on a device: 6 skeleton rows (`surfaceSunken` bars at 36 dp, no shimmer under
    reduce motion).
  - Resolve: per-row spinners.
  - Command in flight: a 12 dp spinner on the touched control.
  - Desktop window exception handler (`LocalWindowExceptionHandlerFactory`): logs to `FileLogger`
    and posts "Something went wrong" [Copy details] instead of closing the window.
- **Web with no device:** the **ConnectLanding** page replaces the auto-opened dialog
  (`AppShell.kt:97-101`):

  ```
                 ╭──────────── 480dp, surface card on the wash ────────────╮
                 │              (SailLanes 200)                            │
                 │          Connect to a Ketch device                      │
                 │ [ Pairing link or address      nas.local:8642        ] │
                 │                                            (Connect)    │
                 │ RECENT  (NB) NAS-Basement · nas.local:8642   ● Online   │
                 │ On your computer: Ketch › Settings › Sharing ›          │
                 │ Allow another device, then copy the pairing link.       │
                 │ Or run `ketch server`.                                  │
                 │ ▸ Enter address manually                                │
                 ╰─────────────────────────────────────────────────────────╯
  ```

  The Token field appears only after a 401. There is no "Find nearby" on web.

### 4.13 First run

- **Desktop and web:** no modal flow. The empty Downloads launchpad shows the **setup checklist**
  (§4.7.8). The extension row flips to "Connected ✓" live when the native host connects.
- **Android** (`ui/onboarding/WelcomeFlow.kt`, full screen, progress dots, Skip; shown while
  `KetchConfig.onboardingVersion < 1`):
  1. "Where should downloads go?" [Use the Download folder] (SAF `OpenDocumentTree`) / Not now.
     The tree URI becomes the destination of HTTP and FTP downloads added on this phone; torrents
     keep the app folder because `library:torrent` does not write to SAF URIs. The caption says
     "Torrents stay in Ketch's folder".
  2. "How will you use Ketch here?" Two 64 dp cards: "Download on this phone" / "Control a
     computer" (which opens the pairing scan).
  3. Ends on the launchpad.

  `POST_NOTIFICATIONS` is requested after the **first add** ("Get notified when downloads
  finish" [Allow]). `NEARBY_WIFI_DEVICES` is requested only when "Find on network" is tapped
  (`MainActivity.kt:47-49`). A branded splash replaces the blank window while the service binds.
- **iOS:** the same steps, with "Control a computer" recommended ("Downloads pause when Ketch is in
  the background").
- **Web:** ConnectLanding.

---
## 5. Interaction

### 5.1 Keyboard map

All shortcuts come from **one registry**, `input/KetchCommands.kt`. Each entry has an id, label,
icon, scope, chord per platform, and whether it shows in menus. The registry drives:
- `ShortcutHost` (a `Modifier.onPreviewKeyEvent` on the shell root for global chords, plus an
  `onKeyEvent` on the list for list keys);
- the macOS `MenuBar`;
- the tray menu;
- the `⌘K` palette;
- context-menu hints;
- tooltips ("Pause all  ⇧⌘P");
- the generated `⌘/` cheat sheet.

`KetchCommandsTest` asserts that no chord is bound twice in the same scope.

**Platform notation:**
- macOS uses ⌘ ⌥ ⌃ ⇧.
- Windows and Linux replace ⌘ with Ctrl, ⌥ with Alt and ⌃⌘ with Ctrl+Shift. Device chords use
  **Alt+digit** instead of Ctrl+Alt+digit, because Ctrl+Alt acts as AltGr on many layouts.
- iPad and Android keyboards use the macOS or Windows mapping respectively.

**Guards:**
- List keys fire only when the list has focus, no text field has focus, no IME composition is
  active, and no menu is open.
- Global chords always fire, except `⌘V` quick add, which requires no focused text field.

| Scope | Chord (mac) | Win/Linux | Action |
|---|---|---|---|
| Global | `⌘N` | Ctrl+N | Add downloads (sheet, clipboard prefill) |
| Global | `⌘V` (list or shell focused) | Ctrl+V | Quick add / open sheet with pasted content |
| Global | `⇧⌘V` | Ctrl+Shift+V | Add clipboard link now (also tray/Dock, works while hidden) |
| Global | `⌘O` | Ctrl+O | Open .torrent… |
| Global | `⌘K` | Ctrl+K | Command palette |
| Global | `⌘F` | Ctrl+F | Focus search (switches to Downloads) |
| Global | `⌘1`…`⌘6` | Ctrl+1…6 | All / Downloading / Waiting / Paused / Done / Failed |
| Global | `⌘E` | Ctrl+E | Discover |
| Global | `⌘0` | Ctrl+0 | Devices |
| Global | `⌘⌥1`…`⌘⌥9` | Alt+1…9 | Switch to device n (in the intake sheet: set the target) |
| Global | `⌘⌥0` | Alt+0 | All devices |
| Global | `⇧⌘D` | Ctrl+Shift+D | Device switcher |
| Global | `⇧⌘P` | Ctrl+Shift+P | Pause all in scope (queued included) |
| Global | `⇧⌘R` | Ctrl+Shift+R | Resume all in scope |
| Global | `⌥⌘R` | Ctrl+Alt+R | Retry all failed in scope |
| Global | `⇧⌘L` | Ctrl+Shift+L | Slow lane on/off |
| Global | `⌘I` | Ctrl+I | Show details of the selected download |
| Global | `⌃⌘S` | Ctrl+Shift+S | Toggle sidebar / rail |
| Global | `⌘J` | Ctrl+J | Activity popover |
| Global | `⌘Z` | Ctrl+Z | Undo last remove / clear / pause / move |
| Global | `⌘,` | Ctrl+, | Settings |
| Global | `⌘/` | Ctrl+/ | Keyboard shortcuts |
| Global | `⌘W` / `⌘M` / `⌘Q` | Ctrl+W / – / Ctrl+Q | Close window (Ketch keeps running) / minimize / quit (asks if active) |
| List | `↑` `↓` / `⇧↑` `⇧↓` | same | Move focus / extend selection |
| List | Home, End, PgUp, PgDn | same | Jump |
| List | `⌘A` / Esc | Ctrl+A / Esc | Select all visible / clear selection, then search |
| List | Space | Space | Pause / resume; failed → retry; canceled → download again |
| List | `↩` | Enter | Open completed file; otherwise open inspector |
| List | `⌘↩` | Ctrl+Enter | Show in Finder / Explorer / folder |
| List | `⌫` | Delete | Remove from list (Undo) |
| List | `⇧⌫` | Shift+Delete | Remove and trash files… (dialog) |
| List | `⌘C` / `⌥⌘C` | Ctrl+C / Ctrl+Alt+C | Copy link(s) / copy file path(s) |
| List | `⌘R` | Ctrl+R | Retry / resume selection |
| List | `=` / `−` | same | Connections ±1 (debounced) |
| List | `⌥⌘↑` / `⌥⌘↓` | Ctrl+Alt+↑/↓ | Priority up/down between Low, Normal and High **only**. Urgent needs the menu, the inspector or Start now, with the preemption preview. |
| List | `→` / `←` | same | Into / out of the inspector |
| Intake | `↩` / `⇧↩` / `⌥↩` / `⌘O` / Esc | Enter / Shift+Enter / Alt+Enter / Ctrl+O / Esc | Add / new line / Discover / .torrent / close |
| Palette | `↑↓` `↩` `⌘↩` `⌥↩` Esc | same | Move / run / alternate / Discover / close |

**Web fallbacks.** The browser owns ⌘N, ⌘T, ⌘W, ⌘R, ⇧⌘P and ⇧⌘R. When no field is focused, the
web app uses single keys instead:

| Key | Action |
|---|---|
| `n` | Add |
| `/` | Search |
| `k` (`⌘K` / Ctrl+K also kept) | Palette |
| Space, Enter, Delete | Same as desktop |
| `⇧P` / `⇧R` | Pause all / resume all |
| `⇧L` | Slow lane |
| Alt+1…6 | Tabs |
| Alt+⇧+0…9 | Devices |
| `i` | Inspector |
| `?` | Shortcuts |

### 5.2 Command palette (`⌘K`, `ui/palette/CommandPalette.kt`)

- **Layout:** a `Popup` at top center, width min(600 dp, window − 32), 72 dp from the top, `xl`,
  e4, 24% scrim. A 48 dp input with placeholder "Paste a link, search downloads, or type a
  command". 40 dp rows (16 dp icon, `body` title, `caption` subtitle, right-aligned shortcut chip),
  at most 9 visible. A 28 dp footer: "↑↓ move · ↩ run · ⌘↩ alternate · ⌥↩ Discover · esc close".
- The page-header search field opens the palette when focused with `⌘K`. Plain typing in the field
  filters the list live **and** shows the palette rows.

**Providers, in ranking order** (exact prefix > word prefix > subsequence; the 5 most recently
used commands are boosted):

| # | Provider | Example rows |
|---|---|---|
| 1 | Links | "⤓ Download ubuntu-24.04.iso on This Mac ↩", "on NAS-Basement ⌘⌥2", "Add with options… ⌘↩" |
| 2 | Speed tokens | "5m" → "Set Slow lane to 5 MB/s"; "full" → "Full speed" |
| 3 | Commands with live counts | "Retry 2 failed downloads", "Resume 4 paused", "Pause all on NAS-Basement" |
| 4 | Downloads by fuzzy name | `↩` opens a finished file, otherwise toggles pause; `⌘↩` reveals |
| 5 | Navigation | Tabs, "Devices", every settings page ("Settings › Speed"; also "/speed") |
| 6 | Devices | "Switch to NAS-Basement · 2 active" |
| 7 | Discover fallback (where supported) | "✦ Discover "blender for mac"" (`⌥↩`) |

### 5.3 Selection model (`state/SelectionState.kt`)

- **State:** `selected: Set<TaskKey>`, `anchor`, `focused`. `TaskKey = deviceId + taskId`. All
  operations work over the **visible ordered keys** (after filter, sort and grouping). The
  selection survives filter changes and is pruned when tasks are removed.
- **Pointer:**
  - Click selects one row and inspects it.
  - `⌘`-click toggles; `⇧`-click selects a range from the anchor.
  - `⌘A` selects all visible rows, including rows in collapsed groups, with the hint "Selected all
    315, including EARLIER".
  - A rubber band from empty space (`pointerInput` + `LazyListState.layoutInfo` hit-test)
    auto-scrolls within 48 dp of the edges.
  - Clicks on the chip/checkbox column toggle a row. Clicks on inline buttons or hover actions
    never change the selection.
  - Double-click runs the primary action.
- **Touch:** long-press enters selection mode. Chips morph into 24 dp checkboxes (150 ms). The top
  bar becomes "3 selected ✕ … Select all". A bottom action bar replaces the FAB.
- **Selection bar:** with 2 or more selected, the 40 dp tab row turns into the bar at the same
  height, `surfaceRaised` with a hairline:

  ```
  ✕  3 selected · 2.4 GB │ ‖ Pause 2 │ ▷ Resume 1 │ ↻ Retry 1 │ Priority ▾ │ Speed ▾ │ Send to ▾ │ ⧉ Copy links │ ⌫ Remove… │ ⋯
  ```

  - Only verbs that apply are shown, with counts. Verbs that don't fit go into `⋯`.
  - Tooltips read "Pause 2 of 3 selected".
  - Speed ▾ includes "Share 5 MB/s across these 3", which calls `setSpeedLimit(cap / n)` on each.
  - Results report partial outcomes: "Paused 2 · 1 already finished".
  - Batches run per task inside `supervisorScope`. On remote devices that means N REST calls.

### 5.4 Row actions and context menus

**`RowAction`** (`state/RowAction.kt`) maps `(state, error, capabilities)` to an ordered action
list. `capabilities` comes from the owning device (`canReschedule`, `canOpenFiles`, `canTrash`,
`isRemote`). Covered by commonTest: 7 states × 10 `KetchError` types × local/remote.

**Context menu:**
- Opened by right-click (`pointerInput` with `isSecondaryPressed`). On web the browser's own menu
  is suppressed with `preventDefault`.
- Right-clicking an unselected row selects it first. Right-clicking inside a selection acts on the
  whole selection ("Pause 3 downloads").
- Touch: the trailing `⋯` opens the same items in a sheet.

```
 Downloading / Paused / Waiting       Completed                         Failed
 ‖ Pause               Space          ↗ Open                    ↩       ↻ Retry                    ⌘R
 ▷ Start now                          ⌂ Show in Finder         ⌘↩       ✎ Retry with options…  ⌥-click
 ⤓ Speed limit                 ▸      ⧉ Copy link              ⌘C       ⧉ Copy error
 ≡ Connections                 ▸      ⧉ Copy file path        ⌥⌘C       ⧉ Copy link                ⌘C
 ⚡ Priority                    ▸      ⇄ Send to                 ▸       ↻ Download again
 ◷ Start later                 ▸      ↻ Download again                  ✦ Find another source
 ⇄ Send to                     ▸      ──────────────────────            ⇄ Send to                  ▸
 ⧉ Copy link                  ⌘C      ⌫ Remove from list        ⌫       ──────────────────────
 ⓘ Details                    ⌘I      ⌫ Remove and trash file…  ⇧⌫      ⌫ Remove from list         ⌫
 ──────────────────────
 ⊘ Stop and discard progress…
 ⌫ Remove from list            ⌫
```

**Submenus:**
- **Speed limit ▸** Unlimited · 256 KB/s · 512 KB/s · 1 · 2 · 5 · 10 MB/s · Custom…
- **Connections ▸** 1 · 2 · 4 · 8 · 16 · 32, with the current value checked; "Peer limit" for
  torrents.
- **Priority ▸** "⚡ Urgent (starts now; may pause lecture-07.mp4)" · High · Normal · Low.
- **Start later ▸** In 1 hour · Tonight 01:00 · Tomorrow 08:00 · Pick date & time…. Hidden when
  `!canReschedule`.
- **Send to ▸** lists every other device with its health; offline devices are disabled.

**Hover actions** (pointer only): two 28 dp icon buttons fade in over Left and Added in 120 ms.

| State | Hover actions |
|---|---|
| Completed | ↗ Open, ⌂ Show |
| Running | ‖ Pause, ⋯ |
| Failed | ↻ Retry, ⋯ |
| Waiting | ▷ Start now, ⋯ |

### 5.5 Feedback: toasts, notifications, undo

**`MessageCenter`** (`feedback/MessageCenter.kt`) replaces `AppState.errorMessage`.

- `AppMessage(id, level: Info|Success|Warning|Error, title, detail?, taskKey?, deviceId?, actions: List<MessageAction> (≤ 2), at, toast: Auto|Sticky|Silent, notify: Boolean)`.
- Keeps an in-memory history of the last 100 messages and an `unreadCount`.
- `ToastHost` renders `KetchToast` at bottom center, 8 dp above the Pulse bar (on phones above the
  FAB or bottom bar), at most 3 stacked.
- The Activity popover (`⌘J`, 🔔 in the Pulse bar) lists the history grouped Today / Earlier. Each
  entry has a device chip when it is not the active device, its actions, "Mark all read" and
  "Clear".

**`AppState.runTaskCommand(task, label) { … }`:**
- Runs in a `SupervisorJob` scope owned by `AppState`, not by the composition scope.
- Rethrows `CancellationException`.
- On failure: logs with `taskId=` and `describeCauses()`, then posts an Error message: "Couldn't set
  speed limit on NAS-Basement · Connection lost" [Try again].
- Exposes `pending: StateFlow<Set<Pair<TaskKey, String>>>`, which the touched control uses for its
  spinner.

**Deferred-commit undo (`PendingOps`):**
- Remove, Clear finished, Cancel ("Stop and discard"), Pause all and Move-to-device hide or apply
  immediately and register an inverse.
- The commit runs on toast expiry (6 s), on app quit (flush), or never when undone.
- `⌘Z` undoes the most recent one. The Edit menu shows "Undo Clear Finished".

**`ActivityMonitor`** (`feedback/ActivityMonitor.kt`, owned outside the UI: the desktop application
scope, Android `KetchService`, the iOS controller, or web `main`):
- Observes the embedded device and every connected remote.
- Records a baseline first, so tasks that are already terminal produce no events.
- Emits the `ActivityEvent`s declared in W0 (`feedback/ActivityEvent.kt`): `Added`,
  `Completed(taskKey, request, state)` (the state carries `outputPath`, `totalBytes` and
  `downloadTime` from #305; names come from `displayName()`), `Failed`, `Recovered(n)` (tasks found
  Queued or Downloading at first load), `QueueDrained(files, bytes)`, `DeviceOffline` and
  `DeviceOnline`.
- The host that owns the monitor also owns the `SystemNotifier`. It is in the foreground when the
  desktop window is focused, the Android activity is resumed, the iOS scene is active or the web
  document has focus.

**`SystemNotifier`** (interface in commonMain, one implementation per platform, owned by the host):
- The host calls it only when the app is in the background **and** the notification setting allows
  it. In the foreground the host passes the event to `App(activityEvents = …)`, which shows an
  in-app toast.
- More than 3 completions within 10 s are coalesced into "4 downloads finished · 8.2 GB".
  `QueueDrained` replaces the last individual completion.

| Event | Notification copy | Actions |
|---|---|---|
| Completed | "Download complete" / "ubuntu-24.04.iso · 5.7 GB in 3 min" | Open · Show in Finder (desktop) / Open · Share (phones) |
| Failed | "Download failed" / "q3-report.pdf: Access denied (403)" | Retry |
| Queue drained | "All downloads finished" / "6 files · 8.2 GB" | — |
| Recovered (toast only) | "Resuming 3 downloads from your last session" | Review |
| Remote device events | Prefix "On NAS-Basement: …" | same |
| Device offline (in-app) | "NAS-Basement went offline" | Retry now |

**Toast copy catalog:**
- "✓ Added ubuntu.iso → This Mac" [Options] [Undo]
- "Added 5 downloads · 1 failed" [Review]
- "Paused 6 downloads · 2 scheduled still start at 02:00" [Undo]
- "Resumed 4 downloads"
- "Removed 5 downloads" [Undo]
- "Cleared 12 finished downloads" [Undo]
- "Moved 3 files to the Trash"
- "Started blender.dmg now · paused debian.iso to make room" [Undo]
- "Sent ubuntu.iso to NAS-Basement" [Show] [Remove here]
- "Slow lane on · 1 MB/s" [Undo]
- "Slow lane on 3 devices" [Undo]
- "Copied 3 links"
- "Restarted ubuntu-24.04.iso"
- "Couldn't set speed limit on NAS-Basement · Connection lost" [Try again]
- "Reconnected to NAS-Basement"

### 5.6 Error catalog (`util/ErrorCopy.kt`)

`fun KetchError.toCopy(request: DownloadRequest, retryCount: Int, device: DeviceInfo): ErrorCopy`
returns `ErrorCopy(title, hint, primary: RowAction, secondary: List<RowAction>)`. It is an
**exhaustive `when`**, so a new subtype fails to compile until it has copy. `Throwable.toCopy()`
covers non-Ketch exceptions. Every problem also offers **Copy details** (error type, code,
`redactUrl(url)`, `taskId`, `KetchApi.VERSION`/`REVISION`) and **Open logs**.

| `KetchError` | Title | Hint | Primary / secondary |
|---|---|---|---|
| `Network` | "Connection lost" | "Couldn't reach {host}. Ketch retried {n} times." | Retry / Retry with 2 connections |
| `Http` 401, 407 | "Sign-in required ({code})" | "{host} wants you signed in. Send it from the Ketch browser extension so your cookies come along." | Edit link… (Retry with options) / Copy link |
| `Http` 403 | "Access denied (403)" | "The link may have expired. Captured from {browser}? Capture it again from the page." | Edit link… / Open source page (Referer, when present) |
| `Http` 404, 410 | "File not found ({code})" | "The server no longer has this file." | Find another source (Discover prefilled with the display name; only where usable) / Copy link |
| `Http` 416 | "Server refused to resume" | "The saved position is no longer valid." | Download again |
| `Http` 429 | "Server is rate-limiting" | "Try again in {retryAfterSeconds} s with fewer connections." | Retry with 1 connection (`setConnections(1)` then resume) |
| `Http` 5xx | "Server error ({code})" | "Usually temporary." | Retry |
| `Http` other | "Server answered {code}" | `statusMessage` if present | Retry / Copy details |
| `Disk` | "Couldn't write the file" | "{usableSpace} free on {device}. Check space and folder permissions." (from the device that failed) | Show folder / Retry |
| `Unsupported` | "This server can't do this download" | "It doesn't report a size or support ranges." | Download again / Copy link |
| `FileChanged(reason)` | "File changed on the server" | "Resuming would corrupt it ({reason})." | Download again |
| `CorruptResumeState` | "Saved progress is damaged" | "The downloaded part can't be reused." | Download again |
| `Canceled` | "Canceled" | — | Download again |
| `SourceError("torrent")` | "The torrent stopped" | "No reachable peers or trackers." | Retry |
| `SourceError("ftp")` | "The FTP server reported an error" | first line of `describeCauses()` | Retry |
| `AuthenticationFailed(source)` | "Wrong user name or password" | "The {FTP} server rejected the sign-in." | Enter credentials… (sheet prefilled, credential fields shown) |
| `Unknown(msg)` | "Something went wrong" | `msg` under "Technical details" | Retry / Copy details |

**Library fix (W1):** `KetchError.Http` builds its message as
`"HTTP error $code" + statusMessage?.let { ": $it" }.orEmpty()`, so "HTTP error 403: null"
disappears (`KetchError.kt:52`). Richer `Disk` and `Network` kinds (NoSpace, ReadOnly,
PermissionDenied, Dns, Refused, Timeout, Tls) are W5.

### 5.7 Click and keystroke counts (now → after)

| Job | Now | After |
|---|---|---|
| Add one copied link (desktop) | 3 actions + 0.5 s wait | **1** (`⌘V`, Undo toast) or 2 (`⌘N`, `↩`) |
| Add one copied link (phone) | 4 taps | **1** (clipboard chip, or long-press the FAB) |
| Add 5 links | ~20 | **2** after copying (`⌘V` opens "5 links · 5 ready · 23.4 GB", then `↩`) |
| Add 3 opened `.torrent` files | ≥ 6 (3 dialogs) | **1** (one batch sheet, `↩`) |
| Magnet clicked in a browser | 5 | **2** (click, `↩`) |
| Share a link from Android Chrome | 6 | **3** (Share, Ketch, Download) |
| Add a link to the NAS while viewing This Mac | 7 + 2 reconnects | **3** (`⌘N`, `⌘⌥2`, `↩`) or **1** drag onto the NAS berth/row |
| Pick 2 of 128 files from a magnet | 5 clicks + scrolling + blind wait | `⌘V`, Video chip, 2 ticks, `↩`; the wait can run in the background |
| Save to a different folder while adding | 10+ (via Settings) | **3** (Save to pill, recent folder, `↩`) |
| Open a finished file | ~8 (via Finder) | **1** (double-click, hover Open, notification Open) or 2 keys (`↓`, `↩`) |
| Show a finished file in Finder | ~7 | **1** (hover ⌂ or `⌘↩`) |
| Find a failed download, see why, retry | 3, with a raw message; Retry does nothing on canceled rows | **2** (`⌘6`, the right recovery button; reason inline) or **1** from the notification; all failures: `⌥⌘R` |
| Change a running task's speed limit | 4 | **2** (right-click › Speed limit › 2 MB/s) or 1 preset in the docked inspector |
| Change a running task's connections | impossible | **2** (right-click › Connections › 16) or **1** key (`=`) |
| Start a queued task now | 3, no explanation | **1** (▷ Start now; preemption named, Undo) |
| Schedule a task for 01:00 tonight | impossible | **2** (right-click › Start later › Tonight 01:00) |
| Throttle everything for a call and undo | 8–10 | **2** (`⇧⌘L` twice) or 2 clicks on the Pulse pill; 1 tap on the Android notification action |
| Pause everything (queued tasks start anyway today) | 1, broken | **1** (`⇧⌘P`, queued included, Undo) |
| Pause everything on every device | 3 per device + reconnects | **2** (`⌘⌥0`, `⇧⌘P`) or tray › Devices › NAS › Pause all (3) |
| Remove 5 finished downloads, keep files | 15 | **3** (click, `⇧`-click, `⌫`) |
| Copy 3 links | 9 | **4** (click, `⌘`-click, `⌘`-click, `⌘C`) |
| Clear finished safely | 1 irreversible click next to Add | **1** ("Clear 7 finished" in the Done header, Undo) |
| See what the NAS is doing | 4 + 2 reconnects | **0** (sidebar live line) |
| Edit the NAS's download settings | 5 + reconnect | **3** (`⌘,`, device chip › NAS, Downloads) |
| Pair a phone | ~10 actions + ~45 typed characters | **4**, nothing typed (Settings › Sharing › Allow another device; scan; Connect) |
| First use of AI discovery | 6+ and you must already know it exists | **3** (Discover, provider, paste key) |
| Keep downloading after closing the window | impossible | **0** (default) |
| Learn a download finished while in another app | manual polling | **0** (notification) |
| Rows visible at the default window size | ~5.7 | **~17** table (1280 × 800), ~15 with group headers; ~11 list rows; phone 8–9 (was 4–5) |

---

## 6. Platform adaptation

### 6.1 Desktop (JVM: macOS, Windows, Linux)

- **Window:** default 1280 × 800, minimum 720 × 480, bounds remembered (`WindowStateStore`).
  Title chrome as in §4.2.1.
- **Close behavior:**
  - `Window(visible = windowVisible, onCloseRequest = ::onClose)`.
  - `[desktop] closeAction = Ask|Background|Quit` (default **Ask**).
  - The first close with active or queued tasks shows a 420 × 200 `DialogWindow`: "Keep
    downloading in the background?" / "Ketch stays in the menu bar and finishes 3 downloads. Quit
    any time from the menu bar icon or with ⌘Q." [Quit Ketch] [**Keep Running**] ☐ Don't ask
    again.
  - Windows: the first hide also posts "Ketch is still running in the notification area".
  - **GNOME without AppIndicator** (`isTraySupported == false`): closing minimizes instead of
    hiding, and notifications use `notify-send`.
  - `⌘Q` / the Dock Quit item go through `Desktop.setQuitHandler`: "Quit Ketch? 3 downloads will
    pause and resume next time you open Ketch." [Cancel] [Quit]. Pending undo operations are
    flushed.
- **Tray / menu bar extra** (`DesktopTray.kt`):

  ```
   ↓ 4.2 MB/s · 3 active · 2 waiting            (disabled header = status sentence)
   New Download…                         ⌘N
   Download Link from Clipboard         ⇧⌘V
   Open Torrent File…                    ⌘O
   ─────────────────────────────────────────
   Pause All                            ⇧⌘P
   Resume All                           ⇧⌘R
   Speed ▸  ◉ Full speed  ○ Slow lane · 1 MB/s  ○ Auto
   ─────────────────────────────────────────
   Devices ▸  This Mac — 6.4 MB/s · 2 active      ▸ Show · Pause all · Resume all · Speed ▸ · Add clipboard link here
              NAS-Basement — 2.7 MB/s · 1 failed  ▸ (same)
              Den-PC — offline                    ▸ Retry now
   Recent ▸  (last 5 finished; click opens the file)
   ─────────────────────────────────────────
   Show Ketch     Settings… ⌘,     Quit Ketch ⌘Q
  ```

  - Icon: a 16 dp **template** `Sail` glyph with an aggregate progress ring, a 4 dp red dot for
    unseen failures, dimmed when everything is paused. Recomposed at most once per second.
    `-Dapple.awt.enableTemplateImages=true` (verify on Temurin 21).
  - Tooltip: "Ketch — ↓ 4.2 MB/s · 3 active".
- **macOS extras:**
  - `AppReopenedListener` shows the window on a Dock click.
  - The Dock menu (`Taskbar.setMenu`) offers New Download, Download Link from Clipboard, Pause All
    and Resume All.
  - `Desktop.setDefaultMenuBar` keeps `⌘N` working while the window is hidden.
- **Menu bar** (macOS, `DesktopMenuBar.kt`, generated from the registry):
  - **Ketch:** About, Settings… ⌘,, Quit.
  - **File:** New Download ⌘N, Download Link from Clipboard ⇧⌘V, Open Torrent File… ⌘O, Add
    Device…, Close Window ⌘W.
  - **Edit:** Undo {action} ⌘Z, Cut, Copy, Paste, Select All, Find ⌘F, Command Palette ⌘K.
  - **View:** All…Failed ⌘1–6, Table/List, Group By ▸, Columns ▸, Density ▸, Toggle Sidebar ⌃⌘S,
    Discover ⌘E, Devices ⌘0, Activity ⌘J.
  - **Downloads:** Pause/Resume, Open ↩, Show Details ⌘I, Show in Finder ⌘↩, Copy Link ⌘C, Start Now,
    Speed Limit ▸, Connections ▸, Priority ▸, Start Later ▸, Send To ▸, Retry ⌘R, Remove ⌫,
    Pause All, Resume All, Retry Failed, Slow Lane ⇧⌘L.
  - **Device:** All Devices ⌘⌥0, one checkable item per device ⌘⌥1–9, Pair a Device…
  - **Window.**
  - **Help:** Keyboard Shortcuts ⌘/, Setup Checklist, Open Logs Folder, Report an Issue.
  - Windows and Linux get no in-window Swing menu bar. The same commands are in the palette,
    tooltips and context menus.
- **Dock / taskbar** (`TaskbarFeedback.kt`). Every call is guarded by
  `Taskbar.isTaskbarSupported() && isSupported(Feature.X)`.
  - macOS:
    - `setIconBadge` shows downloading + waiting, or "!" for unseen failures (setting: Active count
      / Failures only / Off).
    - `setProgressValue` shows aggregate % for tasks with known totals, and -1 when idle.
    - `requestUserAttention(true, false)` fires once per failure while the window is unfocused.
  - Windows:
    - `setWindowProgressValue` and `setWindowProgressState`: NORMAL / PAUSED (everything paused, or
      a task was preempted) / ERROR (failure) / OFF (idle).
    - `setWindowIconBadge` with a count bitmap.
  - Linux: nothing.
  - Unseen failures clear once the Failed tab has been viewed.
- **Launch modes:**
  - `--background` starts hidden in the tray. `AppCommand.launchDetached` appends it (macOS:
    `open <bundle> --args --background`), so browser-extension captures never pop a window.
  - **Open at login:**
    - macOS: `~/Library/LaunchAgents/com.linroid.ketch.plist`.
    - Windows: the `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` value.
    - Linux: `~/.config/autostart/ketch.desktop`.
- **OS intake:**
  - macOS: `CFBundleURLTypes` for `magnet` and `ketch` via `nativeDistributions.macOS.infoPlist.extraKeysRawXml`,
    plus `Desktop.setOpenURIHandler`.
  - Windows: an opt-in `HKCU\Software\Classes\magnet` entry (URL Protocol; `shell\open\command =
    "<launcher>" "%1"`), written from Settings → Integration.
  - Linux: `MimeType=x-scheme-handler/magnet` in the `.desktop` file plus `xdg-mime default`.
  - `fileArguments` routes `^(magnet:|https?://|ftps?://)` to `IncomingDownload.Links` instead of
    files.
- **Integration status:** desktop provides `LocalIntegrationStatus` (detected browsers, extension
  connected, default magnet handler) from `NativeHostRegistration` and `BrowserExtensionServer`.
- **Settings** opens as a separate `Window` (§4.10).
- **Notifications:** `TrayNotifier` uses `trayState.sendNotification`. On macOS, verify delivery in
  the signed `.app`; AWT uses the deprecated `NSUserNotificationCenter`, so add a small
  `UNUserNotificationCenter` bridge if delivery fails (§8). Linux without a tray uses
  `notify-send`.

### 6.2 Android

- **Foreground service** (`ForegroundPolicy`, the critical fix):
  - Watches `InstanceManager.embedded`, not `activeApi`:
    `embedded.tasks.flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else combine(it.map { t -> t.state }) { a -> a.toList() } }.sample(1.seconds)`.
  - Stays in the foreground while any task is Downloading or Queued, or the server runs.
  - Unit-tested in commonTest with fake tasks.
- **Notification channels:**
  - `downloads_active` (IMPORTANCE_LOW), updated at most once per second:
    - title "Downloading 3 files · 4.2 MB/s";
    - text "1.2 GB of 3.4 GB · about 6 min left";
    - `setProgress(1000, permille, indeterminate when totals are unknown)`;
    - InboxStyle with up to 5 lines like "ubuntu-24.04.iso  45% · 2.1 MB/s";
    - actions [Pause all] [Slow lane] via `PendingIntent.getService` (`ACTION_PAUSE_ALL`,
      `ACTION_SLOW_LANE`);
    - tap opens the Downloading tab.
  - **Android 16 (API 36), exactly one active task:** `Notification.ProgressStyle` with one segment
    per connection range (accent over track), plus `setRequestPromotedOngoing(true)` for the
    status-bar chip "45%". The lanes reach the shade; verify the promotion rules.
  - `downloads_done` (DEFAULT): [Open] [Share] via FileProvider or the SAF URI, grouped beyond 3.
  - `downloads_failed` (HIGH): the `ErrorCopy` title, with [Retry].
  - Every tap deep-links to the task via a `taskKey` extra.
- **Permissions:** `POST_NOTIFICATIONS` after the first add; `NEARBY_WIFI_DEVICES` only on "Find
  on network". The three-prompt burst at `MainActivity.kt:47-49` is removed (W1).
- **Default folder:** onboarding offers the SAF Download tree for HTTP and FTP downloads
  (torrents keep the app folder, see §4.13). Settings warns about
  `Android/data`.
- **Intake:**
  - `ACTION_SEND text/plain` extracts every URL from `EXTRA_TEXT`.
  - `VIEW` with `scheme=magnet`.
  - `ACTION_PROCESS_TEXT` adds "Download with Ketch" to the text-selection toolbar.
  - `VIEW scheme=ketch host=pair` handles pairing.
  - Shares open a translucent `QuickAddActivity` that shows only the intake sheet (with the device
    chip) and finishes back to the source app.
- **Clipboard:** `primaryClipDescription` plus `TextClassifier.TYPE_URL` confidence (API 31+) shows
  the chip without reading the clip. The text is read only on tap.
- **Layout:** phone shell (§4.2.4). Tablets and foldables use the rail. Foldable posture (half
  open) puts the list on one half and the inspector on the other via material3-adaptive.

### 6.3 iOS / iPadOS

- **Background honesty:**
  - On `scenePhase == .background` with active downloads, `beginBackgroundTask(withName:
    "ketch.downloads")`, then `KetchBackground.prepareForSuspension()` pauses the tasks and
    persists segments before expiry.
  - Tasks resume when the app returns.
  - A banner above the list while tasks run: "Downloads pause when Ketch is in the background."
    [Use a computer instead], which opens pairing.
  - Local notification: "3 downloads paused · open Ketch to continue".
- **iOS 26+:** `BGContinuedProcessingTaskRequest` ("Downloading ubuntu.iso", "1.2 of 3.4 GB") fed by
  an aggregate progress flow exported to Swift. Verify eligibility (§8).
- **Notifications:** `UNUserNotificationCenter`, with authorization requested after the first add.
  The app badge shows the failure count.
- **Files visibility:** `UIFileSharingEnabled = YES`. Downloads default to `Documents/Downloads`, so
  they appear under On My iPhone › Ketch › Downloads. `config.toml` (which holds AI keys) moves to
  Application Support.
- **Open and share:** `QLPreviewController` to open, `UIActivityViewController` to share, and
  "Show in Files" via `shareddocuments://<folder>`.
- **Intake:**
  - `CFBundleURLTypes` for `magnet` and `ketch`; `onOpenURL` routes by scheme.
  - Clipboard via `UIPasteboard.detectPatterns(for: [.probableWebURL])`, so no paste banner
    appears.
  - A Share Extension is out of scope (§8).
- **Layout:**
  - iPhone: phone shell, no bottom bar; Devices via the device sheet.
  - iPad: rail (portrait) or sidebar (landscape at 1024 dp or wider).
  - Dialogs are centered (window-class based).
  - Hardware keyboards use the shared shortcut handler.

### 6.4 Web (Wasm, remote-only console)

- **No embedded device.** ConnectLanding is shown whenever nothing is connected (§4.12). A pairing
  link or `#token=` is parsed on load and cleared with `history.replaceState`.
- **Discovery and features:**
  - `LanServerDiscovery.supported = false`, so "Find on network" is hidden everywhere.
  - Device health is always visible in the Pulse bar, and the banner appears on disconnect.
  - Hidden local-only controls: local folder picker, Open/Reveal (completed rows offer Copy path),
    Discover (until a server endpoint exists).
  - "Save to this computer" is W6: streaming `fetch` into `showSaveFilePicker`, with an
    `<a download>` fallback, against `GET /api/tasks/{id}/file`.
- **Shortcuts:** the single-key fallbacks in §5.1.
- **PWA** (`manifest.json`, `index.html`):
  - per-scheme `<meta name="theme-color">` `#EEF1F8` / `#0C0E13`, replacing `#000000`;
  - `background_color` `#EEF1F8`;
  - `protocol_handlers: [{"protocol":"magnet","url":"./?add=%s"}]`;
  - `share_target {action:"./", params:{url:"add", text:"text"}}`;
  - `main.kt` reads `?add=` into `IncomingDownload.Links`.
- **Notifications:** the Notification API after opt-in. `navigator.setAppBadge(failures)`.
  `document.title` reads "↓ 45% · Ketch" while active.
- **Splash:** the inline-SVG sail lanes until `rememberKetchFontsLoaded()` returns true.
- No `window-controls-overlay`: the Compose canvas would sit under the window controls unless the
  shell read `navigator.windowControlsOverlay` insets (§8).

---
## 7. Implementation plan

### 7.1 Rules

- **Wave 0 first.** W0-CONTRACTS rebases the worktree onto `main` at `5b5d770a` or later (see the
  note at the top) and lands the shared types every later package compiles against. The list,
  inspector, notification and Devices packages **consume**
  `DownloadState.Completed.totalBytes`/`downloadTime` and `util/DownloadSummary.kt`
  (`transferSummary()`, which returns `List<String>`) from #305. They do not own or re-implement
  them.
- **File ownership:** packages in the same wave own **disjoint files**. A package may edit files
  owned by a package in an *earlier* wave.
- **Dependencies:** packages in a wave run in parallel unless *Depends on* names a package of the
  **same** wave; that package must merge first. There are no cycles.
- **Signature stability:** a composable or class that another package of the same wave calls keeps
  its signature source-compatible (new parameters get defaults) until both have merged.
- **Single owners:** `ui/AppShell.kt`, `state/AppState.kt`, `state/AppController.kt`, `App.kt` and
  `theme/*` each have at most one owner per wave.
- **Seams:** W2-SEAMS creates thin entry composables with stable signatures. W3 packages rewrite
  what is behind them in parallel while `AppShell` keeps calling them.

  | Seam | Signature |
  |---|---|
  | `ui/downloads/DownloadsScreen.kt` | `DownloadsScreen(state: AppState, layout: KetchLayoutInfo, modifier: Modifier)` |
  | `ui/inspector/TaskInspector.kt` | `TaskInspector(state: AppState, taskKey: TaskKey?, placement: InspectorPlacement, onClose: () -> Unit)` (content only; the docked, overlay and sheet containers belong to `DownloadsScreen`) |
  | `ui/intake/IntakeHost.kt` | `IntakeHost(state: AppState)` (reads `state.intakeRequest`) |
  | `ui/settings/SettingsHost.kt` | `SettingsHost(state: AppState, target: SettingsTarget?, onClose: () -> Unit)` |
  | `ui/discover/DiscoverScreen.kt` | `DiscoverScreen(state: AppState)` |
  | `ui/devices/DevicesScreen.kt` | `DevicesScreen(state: AppState)` |

- **App controller.** From W1 on, `AppState` no longer lives in a composition scope. W1-APPSTATE
  adds `state/AppController.kt`: a plain class that owns a `SupervisorJob` scope, the
  `AppSettingsController`, the `AiSettingsController`, `AppState` and (from W2) the list, Pulse and
  speed models. `App(controller: AppController = rememberAppController(...), activityEvents:
  Flow<ActivityEvent> = emptyFlow(), ...)` keeps today's call sites compiling. Desktop creates the
  controller in the application scope, so the tray, the menu bar and the Settings window share it.
- **Activity events.** Each host owns its `ActivityMonitor` (W1-PULSE) and its `SystemNotifier`:
  desktop `main`, Android `KetchService`, the iOS `MainViewController` and web `main`. When the app
  is in the foreground the host forwards events to `App(activityEvents = …)`, which shows toasts;
  otherwise it calls the notifier.
- **Banners and toasts:** both go through `MessageCenter` (`AppMessage.placement =
  Toast | Banner`). Any package can post them without touching the shell.
- **Token guard:** `design-token-allowlist.txt` is the one file every package may edit, and only
  the lines for files it owns (one line per `path:pattern`, sorted). Regenerate those lines with
  `./gradlew :app:shared:jvmTest -PupdateTokenAllowlist` (W0 forwards the property to the test
  JVM). The guard fails if a count rises, and also if a count falls without the file being
  updated.
- **Shorthand:** `S/` = `app/shared/src/commonMain/kotlin/com/linroid/ketch/app/`. Tests live in
  the matching `commonTest` path unless stated otherwise. JVM-only modules (`app:desktop`,
  `library:server`, `library:mcp`, `cli`, `ai:discover`) use `src/test` and the `test` task.
- **Standard verification set** ("**std**"):
  - `./gradlew :app:shared:jvmTest :app:shared:compileKotlinJvm :app:shared:compileKotlinWasmJs :app:shared:compileKotlinIosSimulatorArm64 :app:shared:compileTestKotlinIosSimulatorArm64 :app:shared:compileAndroidMain :app:desktop:compileKotlin :app:android:compileDebugKotlin :app:web:compileKotlinWasmJs`
  - Backtick test names avoid commas and punctuation (Kotlin/Native). Test names follow
    `functionName_condition_expectedResult` (`docs/development/testing.md`).
- **Code rules for every package:** 2-space indent, 100 columns, no star imports, trailing commas
  only on multi-line *named* parameters and arguments, KDoc on public declarations, `internal` for
  implementation details, `KetchLogger` per component with `taskId=` in task messages,
  `redactUrl()` for URLs, and `describeCauses()` for warnings (AGENTS.md).

### 7.2 Wave 0: Rebase and shared contracts

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W0-CONTRACTS** | Rebase, dependencies and the types several W1 packages share | the rebase itself; `app/shared/build.gradle.kts`; `gradle/libs.versions.toml`; new `S/state/TaskKey.kt`, `S/state/AppIntents.kt`, `S/feedback/ActivityEvent.kt`, `S/platform/DesktopIntegration.kt`; `S/state/StatusFilter.kt` + `StatusFilterTest.kt`; `S/ui/sidebar/SidebarNavigation.kt` (filter icons and labels only); `config/src/commonMain/kotlin/com/linroid/ketch/config/KetchConfig.kt` + new `SpeedSettings.kt`, `UiPreferences.kt`, `DesktopSettings.kt`, `NotificationSettings.kt`, `IntegrationSettings.kt`; new `config/src/commonTest/.../AppSectionsConfigTest.kt` | — |

**W0-CONTRACTS scope.**
- Rebase onto `main` ≥ `5b5d770a` and run **std**.
- `app/shared/build.gradle.kts`: add `implementation(libs.kotlinx.datetime)` (already in the
  catalog at 0.8.0; `Instant` is `kotlin.time.Instant`) and `implementation(libs.qrose)` to
  `commonMain`; forward `-PupdateTokenAllowlist` to the `jvmTest` task as a system property.
- `gradle/libs.versions.toml`: add `qrose` (`io.github.alexzhirkevich:qrose`). Check that the
  version ships `wasmJs` and iOS artifacts; if not, add it to `jvmMain` and `androidMain` only and
  have the pairing card show the link without a QR on other platforms.
- `TaskKey(deviceId: String, taskId: String)` with `LOCAL_DEVICE_ID`, plus
  `encode()`/`TaskKey.decode()` for the in-app drag payload (`ketch-task://{deviceId}/{taskId}`).
- `StatusFilter` becomes `{All, Downloading, Waiting, Paused, Done, Failed}` (Waiting = Queued +
  Scheduled, Failed = Failed + Canceled, Downloading = `DownloadState.Downloading`). Only the
  sidebar's `filterIcon` and labels change in the UI; the sidebar now lists six filters.
- `AppIntents.kt`: `IntakeRequest(text: String = "", seeds: List<IntakeSeed> = emptyList(),
  targetDeviceId: String? = null, editTask: TaskKey? = null, retryOf: TaskKey? = null)`,
  `IntakeSeed(url, fileName?, headers, properties)`, `SettingsTarget` (General, Notifications,
  Integration, Discover, About, Downloads, Speed, Network, BitTorrent, Sharing) with an optional
  `deviceId`, and `DiscoverRequest(query, sites)`.
- `ActivityEvent.kt`: sealed `ActivityEvent` (`Added`, `Completed(taskKey, request, state)`,
  `Failed`, `Recovered(count)`, `QueueDrained(files, bytes)`, `DeviceOffline`, `DeviceOnline`), and
  `interface SystemNotifier { fun notify(event: ActivityEvent, copy: NotificationCopy) }` with
  `SystemNotifier.None`. Events carry the task's request and state; consumers compute names with
  `displayName()` (W1).
- `DesktopIntegration.kt`: `IntegrationStatus` (detected browsers, extension connected, Ketch is the
  magnet handler) and `DesktopHooks` (close action, login item, start hidden, Dock badge mode,
  register as magnet handler, open logs folder), each with a no-op default and a
  `CompositionLocal`.
- Config sections with defaults: `[speed]` (`mode`, `standard`, `slowLane`, `rules`),
  `[ui]` (`UiPreferences`: list layout, columns and sort per tab as strings, sidebar collapsed,
  inspector width and open, favorite folders per device, sticky intake defaults per device,
  clipboard mode, quick add, last offered clip hash, observed peak, `onboardingVersion`, density,
  reduce motion), `[desktop]`, `[notifications]`, `[integration]`. All KDoc'd.

*Acceptance:*
1. The worktree contains #305 and #306; **std** passes.
2. A `config.toml` without the new sections loads with their defaults
   (`AppSectionsConfigTest`), and the new sections round-trip through `FileConfigStore`.
3. `StatusFilterTest` covers `matches()` for all 7 `DownloadState` types × 6 filters.

*Verification:* **std**, `./gradlew :config:jvmTest`.

### 7.3 Wave 1: Foundation (tokens, logic, state safety; no UI rewrites)

Goal: land every token, pure-logic model and state-safety primitive the later waves build on. User
visible changes are limited to the new fonts and colors, Android background reliability, guarded
commands, and no more three-prompt permission burst.

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W1-THEME** | Design tokens, fonts, density, reduce motion, token guard | `S/theme/**` (all existing files plus new `KetchDensity.kt`, `KetchWindowChrome.kt`, `Surfaces.kt`, `KetchFonts.kt`); `app/shared/src/commonMain/composeResources/font/**`; `.../composeResources/files/licenses/THIRD-PARTY-NOTICES.txt`; new `S/platform/Accessibility.kt` + `jvmMain`/`androidMain`/`iosMain`/`wasmJsMain` actuals; `ThemeContrastTest.kt`; new `app/shared/src/jvmTest/.../theme/DesignTokenUsageTest.kt`; new `app/shared/src/jvmTest/resources/design-token-allowlist.txt`; `app/web/src/wasmJsMain/kotlin/.../main.kt` (font gate only); `app/web/src/wasmJsMain/resources/index.html` (splash) | W0 |
| **W1-ICONS** | Icon set: cached ImageVectors, new glyphs | `S/icons/KetchIcon.kt`, `S/icons/KetchIconRenderer.kt`, `KetchIconTest.kt` | W0 |
| **W1-TASK-COPY** | Display names, error catalog, row actions, per-state row copy, queue reasons | new `S/util/DisplayName.kt`, `S/util/ErrorCopy.kt`, `S/util/RowContent.kt`, `S/util/QueueReason.kt`, `S/state/RowAction.kt` + their tests; `library/api/.../KetchError.kt` (Http message only) + `library/api/src/commonTest/.../KetchErrorTest.kt` | W0 |
| **W1-LIST-MODEL** | Task list model, selection, sort and groups, rate tracking, speed history, search | new `S/state/TaskListModel.kt`, `S/state/SelectionState.kt`, `S/state/SortAndGroup.kt`, `S/state/SpeedHistoryStore.kt`, `S/util/SegmentRateTracker.kt` + tests; `S/util/DownloadSearch.kt` + `DownloadSearchTest.kt` | W0, W1-TASK-COPY |
| **W1-INTAKE-LOGIC** | Link and cURL parsing, intake problems, duplicates, `IncomingDownload.Links` | new `S/util/LinkParser.kt`, `S/util/CurlParser.kt`, `S/util/IntakeProblems.kt`, `S/util/DuplicateDetector.kt` + tests; `S/state/IncomingDownloads.kt`, `app/shared/src/iosMain/.../state/IncomingDownloads.ios.kt`, `IncomingDownloadsTest.kt` | W0 |
| **W1-COMMANDS** | Command registry and chord matching | new `S/input/KetchCommand.kt`, `S/input/KetchCommands.kt`, `S/input/KeyChord.kt`, `S/input/ShortcutMatcher.kt`, `KetchCommandsTest.kt` | W0 |
| **W1-APPSTATE** | App controller, command guard, messages, undo, batch and intent APIs | `S/state/AppState.kt`; new `S/state/AppController.kt`, `S/state/PendingOps.kt`, `S/state/AiDiscoverController.kt`, `S/feedback/MessageCenter.kt`; `S/state/AiDiscoverState.kt`; `S/state/InstanceSettingsController.kt`; `S/state/AppSettingsController.kt`; `S/App.kt`; `S/ui/AppShell.kt` (wiring only); tests `AppStateCommandsTest.kt` (new), `PendingOpsTest.kt` (new), `AppStateDroppedFileTest.kt`, `InstanceSettingsControllerTest.kt`, `AppSettingsControllerTest.kt`, `AiDiscoverDraftTest.kt` | W0 |
| **W1-PULSE** | Pulse model, speed modes, speed rules, activity monitor | new `S/state/PulseState.kt`, `S/state/SpeedModeController.kt`, `S/state/SpeedScheduler.kt`, `S/feedback/ActivityMonitor.kt` + tests (`PulseStateTest`, `SpeedModeControllerTest`, `SpeedSchedulerTest`, `ActivityMonitorTest`) | W0 |
| **W1-ANDROID-FG** | Foreground service policy and permission deferral | `S/instance/InstanceManager.kt` (add `embedded` accessor only); new `S/state/ForegroundPolicy.kt` + test; `app/android/src/main/kotlin/.../KetchService.kt`; `app/android/src/main/kotlin/.../MainActivity.kt` | W0 |

**W1-THEME scope.**
- Implement §3.1–§3.10 and §3.14 exactly:
  - New `KetchColors` fields and values; deprecated aliases; short aliases and legacy constants
    deleted; generated lanes; status, device-hue and brand tokens; every M3 slot mapped.
  - `rememberKetchTypography()` with bundled fonts. Compose resources generate an **internal**
    `Res`, so `app/web` cannot call `preloadFont` itself: `theme/KetchFonts.kt` exposes
    `@Composable fun rememberKetchFontsLoaded(): Boolean`, and web `main` renders `App` only once it
    returns true, then removes the HTML splash.
  - New radius, spacing, elevation and motion scales. `KetchTheme` gains `density`, `reduceMotion`
    and `windowChrome` parameters with defaults, so `App.kt` keeps compiling.
  - `KetchDensity` (Auto by input method).
  - `LocalWindowChrome`; `Modifier.ketchSurface`; `rememberReduceMotion()` actuals.
- Contrast test across all accents. Token guard with the generated allowlist and the
  `-PupdateTokenAllowlist` regeneration mode.

*Acceptance:*
1. `ThemeContrastTest` covers every accent × theme for every pair in §3.14 and passes.
2. `DesignTokenUsageTest` passes with an allowlist that matches the current offenders exactly, and
   fails when a count rises or falls without the allowlist being updated.
3. The font files total under 1 MB, and the OFL texts are in THIRD-PARTY-NOTICES.
4. All existing call sites compile, using deprecated aliases where needed.
5. The desktop app renders in light and dark with Inter and the new palette, with no Material pink
   anywhere (check the Connected chip and tooltips).
6. The web splash shows sail lanes and disappears after the fonts load, with no fallback-font
   flash.

*Verification:* **std**; launch the desktop app (`./gradlew :app:desktop:run`) in both themes;
`./gradlew :app:web:wasmJsBrowserDevelopmentRun` for the splash.

**W1-ICONS scope.**
- Convert `KetchIcon` to lazily built and cached `ImageVector`s rendered via
  `rememberVectorPainter`. Keep the public enum API.
- Add the glyphs listed in §3.12.

*Acceptance:* `KetchIconTest` checks that every glyph parses; no per-draw `PathParser` calls;
existing screens render unchanged.

*Verification:* **std**.

**W1-TASK-COPY scope:**
- `displayName()` (§4.7.6), using `Destination.isFile()/isDirectory()/isName()`.
- `ErrorCopy` (§5.6): `KetchError.toCopy(...)` and `Throwable.toCopy()`.
- `RowAction` with capabilities (§5.4).
- `RowContent`: per-state strings (§4.7.4), consuming `transferSummary()`; local dates via
  kotlinx-datetime.
- `QueueReason`: slot and host reasons from `KetchStatus.config` and the running tasks.
- `KetchError.Http` message fix.

*Acceptance:*
1. commonTest covers `displayName` for a magnet with and without `dn`, `dir/`, `./`, `%20`, a
   `content://` URI and a completed `outputPath`; `ErrorCopy` for all 10 `KetchError` subtypes and
   every HTTP band; `RowAction` for 7 states × 10 errors × local/remote (Canceled, FileChanged,
   CorruptResumeState and 416 → Download again; remote → no reschedule); `QueueReason` for slot and
   per-host limits.
2. The `KetchError.Http` message no longer contains ": null".

*Verification:* `./gradlew :library:api:jvmTest`, **std**.

**W1-LIST-MODEL scope:**
- `TaskListModel`: immutable `TaskRow` (uses `displayName` and `RowContent`);
  `flatMapLatest` + `combine` + `sample(250.milliseconds)` on `Dispatchers.Default`; 60-sample
  speed ring per task; N devices keyed by `TaskKey`; filtering by `StatusFilter`.
- `SelectionState`: range, toggle, prune, `selectAllVisible`.
- `SortAndGroup`: `SortKey`; Smart groups; 2 s stable re-sort with a freeze flag.
- `SegmentRateTracker`: EMA α 0.35, stall after 3 s, negative deltas ignored.
- `SpeedHistoryStore`: 300 samples at 1 Hz per Downloading task.
- `DownloadSearch`: decoded fields and tokens (§4.7.3).

*Acceptance:*
1. commonTest covers grouping and sort stability (including the freeze flag), selection ranges
   over grouped rows, search tokens (`is:`, `type:`, `host:`, `origin:`, `size:`, `added:`), and
   `SegmentRateTracker` across resegment and stall.
2. With 1,000 tasks and 30 active ticking every 200 ms on a fake clock, `TaskListModel` emits at
   most once per 250 ms and unchanged rows keep referential equality (rows can skip
   recomposition).

*Verification:* **std**.

**W1-INTAKE-LOGIC scope:**
- `LinkParser.parseIntake`: URLs in prose and HTML, magnets, bare info hashes, missing schemes,
  ranges `[01-12]` and `{a,b}`, `.txt`/`.csv` lists, non-link text as a Discover intent.
- `CurlParser`; `IntakeProblems` (§4.9.5); `DuplicateDetector` (normalized URL and btih).
- Add `IncomingDownload.Links(urls, source)`.

*Acceptance:*
1. Table-driven commonTest for each parser rule, including malformed cURL, zero-padded ranges, the
   range-expansion cap (a pattern expanding past 200 items is cut at 200 with the warning
   "Expanded to the first 200 links"), and the `%` decode edge cases.
2. `IncomingDownloads` emits `Links` for links offered by the OS.

*Verification:* **std**.

**W1-COMMANDS scope:**
- The `KetchCommand` registry: every command in §5.1 with ids, labels, glyphs, scope, and chords
  for macOS, Windows/Linux and web.
- `ShortcutMatcher` picks the primary modifier by platform and applies the guards in §5.1.
- No handlers yet (bound in W2 and W3).

*Acceptance:* no chord is duplicated per scope per platform; every chord has a printable label
("⇧⌘P", "Ctrl+Shift+P"); web fallbacks exist for every browser-reserved chord.

*Verification:* **std**.

**W1-APPSTATE scope:**
- `AppController` (§7.1): owns a `SupervisorJob` scope (main dispatcher), the settings
  controllers and `AppState`; `close()` flushes pending operations. `rememberAppController(...)`
  keeps today's `App(instanceManager, …)` callers working. `App(controller, activityEvents)`
  parameters are added with defaults.
- `AppState` no longer takes the composition scope. `InstanceSettingsController` is hoisted into
  it (`settingsFor(entry)` builds one for any connected device) and refreshed from
  `api.status().config` on device switch, on window focus and after each `updateConfig`.
  `AppShell` reads it from `AppState` instead of creating it (`AppShell.kt:203-211`).
- `runTaskCommand(task, label) { … }`: `SupervisorJob`, rethrows `CancellationException`, logs with
  `taskId=` and `describeCauses()`, posts one Error `AppMessage` naming the device and carrying the
  `Throwable` (W2 renders it with `ErrorCopy`), and exposes `pending: StateFlow<Set<Pair<TaskKey,
  String>>>`.
- `MessageCenter` (§5.5). `errorMessage` stays as a deprecated value derived from the latest Error
  message, so the current banner keeps working until W2.
- `PendingOps` deferred commit: remove, clear finished, cancel, pause all, move; 6 s; `undoLast()`;
  flush on `close()`.
- Batch APIs that take their targets: `pauseAll(targets)`, `resumeAll(targets)`,
  `retryFailed(targets)` (default: the active device). `pauseAll` pauses Queued tasks, then
  Downloading ones, inside `supervisorScope`, and reports partial results.
- Task APIs: `redownload(task)`, `startNow(task)` (Urgent + preemption detection + Undo),
  `sendTo(tasks, target, move)`, `quickAdd(urls, target)` (sticky defaults from `UiPreferences`,
  Options/Undo toast).
- Intent APIs: `intakeRequest` + `openIntake(IntakeRequest)`, `settingsRequest` +
  `openSettings(SettingsTarget)`, `openDiscover(DiscoverRequest)`, `inspectedTask`,
  `selectedKeys`, `focusSearchRequests`.
- `addDroppedFiles` routes link text to `openIntake` instead of the "Only .torrent files" error.
- Move AI discovery orchestration into `AiDiscoverController` (AppState keeps thin forwarding
  methods, so `AiDiscoveryContent` compiles), and make "Download selected" add each candidate on its
  own (`AppState.kt:459-479`).
- `AppSettingsController` loads and saves `UiPreferences`.

*Acceptance (unit tests with `FakeKetchApi`):*
1. A failed command emits exactly one Error message naming the device; a cancellation emits none;
   the controller scope survives the failure.
2. Pause all leaves no task Downloading even when the queue would promote, and Undo resumes exactly
   those IDs.
3. Remove hides rows at once; Undo restores them; the commit fires after 6 s and on `close()`.
4. `sendTo` copies headers, speed, priority and connections, and removes the source only when
   `move` is set and the add succeeded.
5. One failing AI candidate does not stop the others.
6. Dropping a text link opens an `IntakeRequest` (`AppStateDroppedFileTest`).

*Verification:* **std**, `./gradlew :config:jvmTest`.

**W1-PULSE scope:**
- `PulseModel` (§4.4.1): counts with the `StatusFilter` definitions, total speed, 60-sample ring
  per device, cap, disk (polled every 30 s and after each completion), mode, health, and
  `sentence()` (§4.4.3). Built from injected flows (tasks per device, current config, a
  `status()` function), so it needs no `AppState` changes.
- `SpeedModeController`: Full / Slow lane / Auto, applying through an injected
  `suspend (DownloadConfig) -> Unit`; Slow lane default from the observed peak.
- `SpeedScheduler` (kotlinx-datetime, `TimeZone.currentSystemDefault()`).
- `ActivityMonitor`: baseline first, then `ActivityEvent`s from the device flows it is given;
  coalescing of more than 3 completions within 10 s; `QueueDrained`.

*Acceptance:* counts match `StatusFilter`; the cap reflects a config change; Slow lane never drops
below 256 KB/s; `SpeedScheduler` boundary math crosses midnight and DST; `ActivityMonitor` emits
nothing for tasks already terminal at load and coalesces 4 completions in 10 s into one event.

*Verification:* **std**.

**W1-ANDROID-FG scope:**
- `InstanceManager.embedded` accessor.
- `ForegroundPolicy`: combines every embedded task's state flow, sampled at 1 s, as a pure function
  of states plus server state.
- `KetchService` uses it.
- `MainActivity`: drops the launch-time `NEARBY_WIFI_DEVICES` request (it moves to "Find on
  network" in W4) and requests `POST_NOTIFICATIONS` the first time the embedded task count goes
  from 0 to 1 or more, with the rationale "Get notified when downloads finish".

*Acceptance:*
1. commonTest: the policy is true while any task is Queued or Downloading or the server runs, and
   false within 1 s after the last task completes.
2. On an emulator (API 34), a 2 GB download keeps running with the screen off for 10 minutes.
3. At most one permission prompt appears at first launch (storage on API ≤ 28 only).

*Verification:* **std**; manual emulator run.

### 7.4 Wave 2: Components, platform primitives, seams, quick wins, platform presence

Goal: ship the component library and the platform primitives, insert the seams, wire the W1
models into the app, wire the safety fixes into the **existing** list (guarded commands, Download
again, real names, inline reasons, Undo, Clear moved, toasts), and give desktop, Android, iOS and
web their notification, tray and menu presence.

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W2-CONTROLS** | Controls: buttons, inputs, menus, toasts, pickers | `S/components/KetchButton.kt`, `KetchTextField.kt`, `KetchSurfaces.kt`; new `S/components/KetchChip.kt`, `KetchSegmented.kt`, `KetchPillGroup.kt`, `KetchMenu.kt`, `KetchTooltip.kt`, `KetchCheckbox.kt` (checkbox and switch), `KetchToast.kt`, `PriorityGlyph.kt`, `SpeedLimitPicker.kt`, `StartTimePicker.kt`, `ConnectionStepper.kt`, `preview/ControlsPreview.kt`; `S/ui/common/AdaptiveModal.kt`, `S/ui/common/PriorityBadge.kt` (wrapper), `S/ui/common/StatusIndicator.kt` (delete) | W1-THEME, W1-ICONS |
| **W2-VISUALS** | Visuals: lanes, charts, pennants, brand | `S/components/KetchDownloadComponents.kt`, `KetchFileTypeChip.kt`; new `S/components/LaneStrip.kt`, `KetchSpeedChart.kt`, `KetchHueTile.kt`, `DevicePennant.kt`, `DeviceTargetChip.kt`, `StatusDot.kt`, `KetchLogoTile.kt`, `SailLanesIllustration.kt`, `preview/VisualsPreview.kt`; `S/ui/common/ConnectionStatusDot.kt` | W2-CONTROLS |
| **W2-PLATFORM** | File actions, clipboard, file and folder pickers, device noun | new `S/platform/FileActions.kt`, `Clipboard.kt`, `FilePicker.kt`, `DeviceNoun.kt` + `jvmMain`/`androidMain`/`iosMain`/`wasmJsMain` actuals of each; new `app/shared/src/androidMain/.../platform/DownloadFileProvider.kt`; `app/shared/src/androidMain/AndroidManifest.xml`; new `app/shared/src/androidMain/res/xml/ketch_download_paths.xml`; new `app/shared/src/jvmTest/.../platform/FileActionsJvmTest.kt`; `app/shared/build.gradle.kts` (`androidMain` activity-compose for result launchers) | W0 |
| **W2-SEAMS** | Seams, model wiring and toasts | `S/ui/AppShell.kt`; `S/App.kt`; `S/state/AppState.kt`; `S/state/AppController.kt`; new `S/ui/downloads/DownloadsScreen.kt`, `S/ui/inspector/TaskInspector.kt`, `S/ui/intake/IntakeHost.kt`, `S/ui/settings/SettingsHost.kt`, `S/ui/discover/DiscoverScreen.kt`, `S/ui/devices/DevicesScreen.kt`, `S/ui/feedback/ToastHost.kt`, `S/state/LocalAppState.kt`; `S/ui/dialog/AddDownloadDialog.kt` (initial URL parameter only) | W2-CONTROLS, W1-LIST-MODEL, W1-PULSE, W1-APPSTATE |
| **W2-QUICKWINS** | Safety fixes in the current list | `S/ui/list/**` (`DownloadList.kt`, `DownloadListItem.kt`, `DownloadExpandedPanel.kt`; delete `TaskActionButtons.kt`); `S/ui/common/ScheduleToggle.kt`, `PrioritySelector.kt`, `SpeedLimitSlider.kt`, `TaskSettingsPanel.kt`; `S/ui/toolbar/**`; `S/ui/DownloadFilters.kt`; `S/state/StatusFilter.kt` (absorb `countTasksByFilter`); `S/ui/dialog/RemoveDownloadDialog.kt`; `S/theme/Color.kt`, `S/theme/Theme.kt` (remove `LocalDownloadStateColors` only) | W2-SEAMS, W2-CONTROLS, W1-TASK-COPY |
| **W2-ANDROID-NOTIFY** | Android notifications and activity events | new `app/shared/src/androidMain/.../feedback/AndroidNotifier.kt`; `app/android/src/main/kotlin/.../KetchService.kt`; `app/android/src/main/kotlin/.../MainActivity.kt`; `app/android/src/main/AndroidManifest.xml`; new `app/android/src/main/res/drawable/ic_stat_ketch.xml` | W1-PULSE, W1-ANDROID-FG, W2-PLATFORM |
| **W2-APPLE-WEB-NOTIFY** | iOS notifications and clean background pause; web notifications | new `app/shared/src/iosMain/.../feedback/IosNotifier.kt`, `app/shared/src/iosMain/.../KetchBackground.kt`; `app/shared/src/iosMain/.../MainViewController.kt`; `app/ios/iOSApp.swift`; `app/ios/Ketch-Info.plist`; new `app/shared/src/wasmJsMain/.../feedback/WebNotifier.kt`; `app/web/src/wasmJsMain/kotlin/.../main.kt` | W1-PULSE |
| **W2-DESKTOP-TRAY** | Tray, menu bar, Dock and taskbar, tray notifier | new `app/desktop/src/main/kotlin/com/linroid/ketch/app/desktop/DesktopTray.kt`, `DesktopMenuBar.kt`, `TaskbarFeedback.kt`, `TrayNotifier.kt`; new `app/desktop/src/test/.../DesktopTrayModelTest.kt` | W1-PULSE, W1-COMMANDS, W1-APPSTATE |
| **W2-DESKTOP-LIFECYCLE** | Close-to-background, launch modes, login item, magnet handler, exception handler | `app/desktop/src/main/kotlin/com/linroid/ketch/app/desktop/main.kt`; new `CloseBehavior.kt`, `LoginItem.kt`, `MagnetHandler.kt`, `DesktopHooksImpl.kt`; `AppCommand.kt`; `NativeMessagingHost.kt`; `OpenedFiles.kt`; `WindowStateStore.kt`; `app/desktop/build.gradle.kts`; `app/desktop/src/test/**` except `DesktopTrayModelTest.kt` | W2-DESKTOP-TRAY, W1-INTAKE-LOGIC |

**W2-CONTROLS scope.**
- Every control in §3.11 against the tokens, with hover, press, focus and disabled states and
  Compact/Comfortable sizes: buttons, icon button, pill group, segmented, chip, text field (label
  above, multi-line variant), checkbox, switch, menu (sheet on touch), tooltip with shortcut, toast,
  badge (failed count = white on `dangerFill`), `PriorityGlyph`.
- Shared pickers: `SpeedLimitPicker` (decimal parsing, commit on ↩, blur or 600 ms),
  `StartTimePicker` (always `AtTime`), `ConnectionStepper` (400 ms debounce).
- `AdaptiveModal` chooses its form by window width and keeps the 15% top anchor on touch.
- Keep the existing public signatures (`KetchButton`, `PriorityBadge`) compiling as wrappers.

*Acceptance:*
1. `@Preview` functions in `components/preview/ControlsPreview.kt` render every control in light,
   dark, Compact and Comfortable.
2. `SpeedLimitPicker` commits once for "500" typed quickly; `StartTimePicker` "Tonight 01:00"
   yields the next local 01:00 (commonTest on the pure helpers).
3. `StatusIndicator.kt` is deleted; the allowlist entries for the owned files reach zero.

*Verification:* **std**.

**W2-VISUALS scope.**
- `LaneStrip` per §3.11 and §4.8.4 (one `Canvas`, byte offsets, write heads, seams, shimmer,
  completion sheen), `KetchSpeedChart` with axis and limit lines, `KetchHueTile`, `DevicePennant`
  (health ring only), `DeviceTargetChip` (pure: takes a list of device options), `StatusDot`
  (`drawBehind` pulse), `KetchLogoTile`, `SailLanesIllustration`, file-type chip sizes.
- `KetchSegmentBar` and `KetchSegmentDetail` (`KetchDownloadComponents.kt`) keep compiling as
  wrappers over `LaneStrip` until W3 deletes their last callers. `KetchProgressBar` lives in
  `KetchSurfaces.kt` (W2-CONTROLS) and stays until W3-TABLE removes its last caller.

*Acceptance:* previews in `components/preview/VisualsPreview.kt` for every visual in both themes
and densities; `LaneStrip` draws 32 segments in one `Canvas` pass; reduce motion disables the
pulse, glide and sheen; owned allowlist entries reach zero.

*Verification:* **std**.

**W2-PLATFORM scope.**
- `FileActions` (`rememberFileActions(): FileActions?`): `open`, `reveal` (with `revealLabel`:
  "Show in Finder" / "Show in Explorer" / "Show in folder"), `share`, `exists`, `moveToTrash`.
  - JVM: `Desktop.open`; reveal via `open -R` (macOS), `explorer.exe /select,` (Windows),
    `Desktop.browseFileDirectory` or the parent folder (Linux); `Desktop.moveToTrash` where
    `Action.MOVE_TO_TRASH` is supported.
  - Android: `ACTION_VIEW`/`ACTION_SEND` through the new `DownloadFileProvider` for file paths, or
    the SAF URI as is; no reveal.
  - iOS: `QLPreviewController`, `UIActivityViewController`; "Show in Files" via
    `shareddocuments://` only when `canOpenURL` allows it.
  - wasmJs: `null`.
- `Clipboard`: `hasLink()` without reading the text where the platform allows it
  (`ClipDescription.getConfidenceScore(TextClassifier.TYPE_URL)` on API 31+,
  `UIPasteboard.detectPatterns`, AWT `isDataFlavorAvailable`), `readText()` only from a user
  action, `writeText()`, and on web a `pasteEvents: Flow<String>` from a document `paste` listener
  (no permission prompt).
- `FilePicker`: `pickFolder()` (macOS `FileDialog` with `apple.awt.fileDialogForDirectories`,
  `JFileChooser` elsewhere, Android `OpenDocumentTree` + `takePersistableUriPermission`, iOS
  `UIDocumentPickerViewController` for folders, wasmJs `null`) and `pickTorrentFiles()` (all
  platforms, wasmJs via `<input type=file accept=.torrent multiple>`).
- `localDeviceNoun()`: "This Mac", "This PC", "This computer", "This phone", "This iPad",
  "This browser".

*Acceptance:* `FileActionsJvmTest` checks the reveal command per OS; the four actuals compile on
every target; on Android a completed download opens in another app through the provider.

*Verification:* **std**; manual open/reveal on macOS and Android.

**W2-SEAMS scope.**
- Create the six seams wrapping today's composables. `DownloadsScreen` hosts today's toolbar, the
  status chips **at every width** (so W3-SHELL can remove the sidebar's filter group on its own)
  and the list. `IntakeHost` shows `AddDownloadDialog` for an `IntakeRequest` (one URL prefilled)
  and for `IncomingDownload.Links`.
- Wire the W1 models into `AppController`: `TaskListModel`, `SpeedHistoryStore`, `PulseModel`,
  `SpeedModeController`, `SpeedScheduler`. `AppShell` reads tasks and counts from `TaskListModel`
  instead of collecting every task in the root (`AppShell.kt:110-119`).
- `ToastHost` with `KetchToast` (bottom center, above the status strip); delete the error banner
  card (`AppShell.kt:392-423`). Error messages render their cause with `Throwable.toCopy()`.
  `activityEvents` from `App(...)` become toasts.
- `BandwidthReadout` shows the real cap from `PulseModel`.
- Provide `LocalAppState`, so list and dialog composables owned by other packages reach
  `runTaskCommand`, `MessageCenter` and the intent APIs without new parameters.
- `KetchTheme` receives density and reduce-motion preferences from `UiPreferences`.

*Acceptance:*
1. `IncomingDownload.Links` and a dropped text link open the add dialog prefilled.
2. A failing command on a remote device shows one toast and never cancels other actions.
3. The readout shows "/ 5 MB/s" when a 5 MB/s cap is set.
4. `AppShell` no longer collects every task's state in composition.

*Verification:* **std**; manual run against `ketch server` as a remote device (stop the server
mid-action).

**W2-QUICKWINS scope.**
- Route **every** per-task call through `runTaskCommand`.
- Use `RowAction` for the row's trailing action: Canceled → Download again; Failed → the
  `ErrorCopy` primary. Hide Schedule for remote devices.
- Use `displayName` everywhere; show the `ErrorCopy` title in the Failed row metric.
- Move Clear completed into a stable `⋯` overflow with Undo; Pause all and Resume all are always
  visible and disabled rather than hidden.
- Remove from list → Undo toast with no dialog; the dialog appears only for "Also delete".
- Debounce the custom speed field (commit on Enter, blur or 600 ms).
- Migrate `DownloadListItem` to `KetchTheme.colors.status` and delete `LocalDownloadStateColors`.

*Acceptance:*
1. On a remote device the Schedule control is not shown, and a network failure on pause shows a
   toast and never crashes.
2. Retry on a canceled row creates a new task and removes the old row.
3. A magnet captured from the extension shows its `dn` name.
4. Clear completed is undoable for 6 s; Pause all leaves zero tasks downloading.
5. Typing "500" in the custom speed field sends one `setSpeedLimit`.

*Verification:* **std**; manual run against an embedded and a remote device.

**W2-ANDROID-NOTIFY scope.**
- `KetchService` owns an `ActivityMonitor` for the embedded device and an `AndroidNotifier`:
  - Channels `downloads_active` / `downloads_done` / `downloads_failed`.
  - The ongoing notification with aggregate progress, InboxStyle and [Pause all] [Slow lane]
    actions (`ACTION_PAUSE_ALL`, `ACTION_SLOW_LANE`).
  - Android 16 (API 36) `Notification.ProgressStyle` segments when exactly one task is active,
    behind an SDK check.
  - Done notifications with Open and Share through `DownloadFileProvider` (W2-PLATFORM); Failed
    with Retry; deep-link extras.
- `MainActivity` forwards the monitor's events to `App(activityEvents = …)` while it is resumed.

*Acceptance:* a completion while the app is backgrounded posts exactly one notification with a
working Open; 4 completions in 10 s post one coalesced notification; [Pause all] works from the
shade.

*Verification:* **std**; Android emulator API 34 and 36.

**W2-APPLE-WEB-NOTIFY scope.**
- iOS: `IosNotifier` (`UNUserNotificationCenter`, authorization after the first add);
  `beginBackgroundTask` + `KetchBackground.prepareForSuspension()` (pause and persist, resume on
  return); the background banner via `MessageCenter`; `UIFileSharingEnabled`; downloads default to
  `Documents/Downloads` (`DownloadConfig.defaultDirectory`); `config.toml` moves to Application
  Support (the old file is migrated once).
- Web: `WebNotifier` (Notification API; permission only from a user action, the first completion
  toast's "Notify me" until W3 adds the Settings toggle), `document.title` progress,
  `navigator.setAppBadge` in installed PWAs.

*Acceptance:* iOS backgrounding with an active task results in Paused (not Failed) and resumes on
return; the iOS Files app shows On My iPhone › Ketch › Downloads; the web tab title reads
"↓ 45% · Ketch" while downloading.

*Verification:* **std**; iOS simulator via `app/ios/Ketch.xcodeproj`; Chrome.

**W2-DESKTOP-TRAY scope.**
- `KetchTray(controller, …)` (an `ApplicationScope` extension): the full tray menu in §6.1 except
  the Devices section (W4), the template `Sail` icon with a progress ring and failure dot, and the
  status-sentence header and tooltip.
- `KetchMenuBar(controller, …)` (a `FrameWindowScope` extension) generated from `KetchCommands`,
  with handlers bound to `AppState`.
- `TaskbarFeedback` (badge, progress, attention; every call guarded by `isSupported`).
- `TrayNotifier` (`TrayState.sendNotification`; `notify-send` when there is no tray).
- The menu and tray item lists are built by pure functions, tested in `DesktopTrayModelTest`.

*Acceptance:* `DesktopTrayModelTest` covers enabled states (Pause All disabled when nothing runs)
and the header sentence.

*Verification:* `./gradlew :app:desktop:test :app:desktop:compileKotlin`.

**W2-DESKTOP-LIFECYCLE scope.**
- Restructure `main.kt`: the `InstanceManager`, the `AppController` and the `ActivityMonitor` live
  in the application scope; `Window(visible = …)`; `KetchTray` and `KetchMenuBar` wired.
- Close behavior with the Ask dialog (§6.1); `AppReopenedListener`; `QuitHandler` with
  confirmation and undo flush; Dock menu; `Desktop.setDefaultMenuBar`.
- `--background` launches (`AppCommand.launchDetached` appends it; extension relaunches start
  hidden).
- `LoginItem` for all three OSes; `DesktopHooksImpl` provides `LocalDesktopHooks`.
- Magnet handling: `CFBundleURLTypes` (`magnet`, `ketch`) via
  `nativeDistributions.macOS.infoPlist.extraKeysRawXml` + `Desktop.setOpenURIHandler`; Linux
  `.desktop` `MimeType=x-scheme-handler/magnet`; the Windows opt-in registration helper (run from
  Settings in W3 through `DesktopHooks`).
- `fileArguments` routes links to `IncomingDownload.Links`.
- `LocalWindowExceptionHandlerFactory` → error toast instead of closing the window.
- Default window 1280 × 800, minimum 720 × 480.

*Acceptance:*
1. Closing with active downloads keeps them running, and the tray shows the status sentence.
2. An extension capture with Ketch closed starts it hidden and posts a notification.
3. `⌘N`, `⇧⌘P` and `⇧⌘L` work from the menu bar and while the window is hidden.
4. The Dock badge shows the count.
5. Clicking a magnet in Safari opens Ketch with the add dialog prefilled.
6. GNOME without a tray minimizes instead of hiding.
7. `src/test`: `fileArguments` routes `magnet:`/`https:` to links; the login-item file contents are
   correct per OS.

*Verification:* `./gradlew :app:desktop:test :app:desktop:compileKotlin`; packaged macOS run
(`:app:desktop:packageDmg`), plus a Windows VM smoke test.

### 7.5 Wave 3: Screens

Goal: the new shell, the Downloads table and rows, the inspector, the intake sheet, Settings,
macOS window chrome and device presence state, built in parallel behind the W2 seams.

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W3-PULSE-UI** | Pulse bar, speed-mode pill, banners, toasts, Activity | new `S/ui/pulse/**` (`PulseBar.kt`, `SpeedModePill.kt`, `SpeedModePopover.kt`, `PulseSheet.kt`); `S/ui/feedback/**` (`ToastHost.kt`, new `BannerHost.kt`, `ActivityPopover.kt`) | W2-CONTROLS, W2-VISUALS, W2-SEAMS |
| **W3-SHELL** | Shell: layout tiers, sidebar, rail, phone shell, shortcuts | `S/ui/AppShell.kt`; `S/ui/sidebar/**`; new `S/ui/shell/**` (`KetchLayout.kt`, `ShellScaffold.kt`, `NavRail.kt`, `PhoneTopBar.kt`, `PhoneBottomBar.kt`, `AddFab.kt`, `ShortcutHost.kt`, `ShortcutSheet.kt`); `S/state/AppDestination.kt` | W3-PULSE-UI, W2-PLATFORM, W1-COMMANDS |
| **W3-DESKTOP-CHROME** | macOS full-window chrome, Settings window, integration status | `app/desktop/.../main.kt`; new `MacWindowChrome.kt`, `SettingsWindow.kt`, `DesktopIntegrationStatus.kt`; `NativeHostRegistration.kt`; `BrowserExtensionServer.kt` | W2-DESKTOP-LIFECYCLE |
| **W3-ROW-ACTIONS** | Selection, row menus, hover actions, list keys, remove dialog, drag-out | new `S/ui/downloads/actions/**` (`RowMenu.kt`, `HoverActions.kt`, `SelectionBar.kt`, `ListKeyboard.kt`, `RubberBand.kt`, `DragOut.kt`); `S/state/SelectionState.kt`; `S/ui/dialog/RemoveDownloadDialog.kt` (→ `RemoveTasksDialog`) | W2-CONTROLS, W2-PLATFORM, W2-SEAMS |
| **W3-TABLE** | Downloads page: header, tabs, facets, table, rows, inspector containers, launchpad | `S/ui/downloads/*.kt` (top-level files; not `actions/`); `S/ui/list/**` (rewrite; delete `DownloadExpandedPanel.kt`); `S/ui/toolbar/**` (delete); `S/ui/DownloadFilters.kt` (delete); `S/ui/common/ScheduleToggle.kt`, `PrioritySelector.kt`, `SpeedLimitSlider.kt`, `TaskSettingsPanel.kt` (delete, after their last callers go); `S/state/TaskListModel.kt`; `S/state/SortAndGroup.kt`; `S/util/DownloadSearch.kt`; `S/util/RowContent.kt` | W3-ROW-ACTIONS, W2-VISUALS |
| **W3-INSPECTOR-TABS** | Connections, Files and Activity tabs | new `S/ui/inspector/tabs/**` (`ConnectionsTab.kt`, `FilesTab.kt`, `ActivityTab.kt`); `S/util/SegmentRateTracker.kt`; `S/state/SpeedHistoryStore.kt` | W2-VISUALS, W2-SEAMS |
| **W3-INSPECTOR** | Inspector content: header, reason line, problem card, Controls, Details, overviews | `S/ui/inspector/*.kt` (top-level files; not `tabs/`) | W3-INSPECTOR-TABS, W2-CONTROLS, W2-PLATFORM |
| **W3-INTAKE-SHEET** | Intake sheet, pills, Advanced, torrent stage, problems, Retry with options | `S/ui/intake/**`; `S/ui/dialog/AddDownloadDialog.kt` (delete); new `S/state/IntakeState.kt`; `S/util/LinkParser.kt`, `CurlParser.kt`, `IntakeProblems.kt`, `DuplicateDetector.kt` | W2-CONTROLS, W2-VISUALS, W2-PLATFORM, W2-SEAMS |
| **W3-OS-INTAKE** | Drops, share targets and URL schemes on Android, iOS and web | `S/platform/FileDrop.kt` + 4 actuals; `app/shared/src/jvmTest/.../platform/FileDropJvmTest.kt`; `S/ui/FileDropTarget.kt`; `S/state/IncomingDownloads.kt`, `app/shared/src/iosMain/.../state/IncomingDownloads.ios.kt`, `IncomingDownloadsTest.kt`; `app/android/src/main/AndroidManifest.xml`; `app/android/.../MainActivity.kt`; new `app/android/.../QuickAddActivity.kt`; `app/ios/Ketch-Info.plist`; `app/ios/iOSApp.swift`; `app/web/src/wasmJsMain/resources/manifest.json`; `app/web/src/wasmJsMain/kotlin/.../main.kt` | W2-SEAMS |
| **W3-SETTINGS-DEVICE** | Device settings pages, shared settings rows, pairing | `S/ui/settings/SettingsComponents.kt`, `DownloadSettings.kt`, `NetworkSettings.kt`, `BitTorrentSettings.kt`, `RemoteAccessSettings.kt` (→ `SharingSettings.kt`), new `SpeedSettingsPage.kt`; `S/ui/dialog/EmbeddedServerControls.kt`; `S/state/SettingsChoices.kt` + `SettingsChoicesTest.kt`; new `S/util/PairingLink.kt` + test | W2-CONTROLS, W2-VISUALS, W2-PLATFORM |
| **W3-SETTINGS** | Settings IA, search, deep links, app pages | `S/ui/settings/SettingsHost.kt`, `SettingsDialog.kt`, `SettingsPage.kt`, `GeneralSettings.kt`, `AboutSettings.kt`, `AiDiscoverySettings.kt`, new `NotificationSettingsPage.kt`, `IntegrationSettingsPage.kt`, `SettingsSearch.kt`; `S/state/SettingsCategory.kt` | W3-SETTINGS-DEVICE |
| **W3-DEVICES-STATE** | Device presence, keep-alive, naming, scope | `S/instance/**` (`InstanceManager.kt`, `InstanceFactory.kt`, `InstanceEntry.kt`, `LanServerDiscovery.kt`, `MdnsDiscoverer.kt`; new `DevicePresence.kt`, `DeviceScope.kt`); `app/shared/src/wasmJsMain/.../instance/MdnsDiscoverer.wasmJs.kt`; `config/.../RemoteConfig.kt`; `FakeInstanceFactory.kt`, `InstanceManagerServerTest.kt`, `LanServerDiscoveryTest.kt`, new `DevicePresenceTest.kt` | W1-APPSTATE |
| **W3-ORIGIN-TAGS** | Origin property for the Origin facet | `app/browser-extension/src/lib/request.js` + `app/browser-extension/test/request.test.js`; `library/mcp/src/main/kotlin/com/linroid/ketch/mcp/KetchToolSet.kt` + its test in `library/mcp/src/test/`; `cli/src/main/kotlin/com/linroid/ketch/cli/DownloadArgs.kt` | — |

**W3-PULSE-UI scope.** §4.4 and §4.12: Pulse bar (speed, sparkline, counts, disk, health,
Activity badge, selection summary, collapse by width), `SpeedModePill` with its popover
(`SpeedLimitPicker`, "Use as Slow lane speed", "Speed settings…" → `openSettings(Speed)`), the
phone Pulse sheet, `BannerHost` (`MessageCenter` banners plus the active device's connection
banner), restyled `ToastHost`, and the Activity popover (`⌘J`).

*Acceptance:* `⇧⌘L` (via `ShortcutHost`, W3-SHELL) or a pill click toggles Slow lane and the pill
turns amber; the Pulse counts equal the `StatusFilter` counts; an Unauthorized device shows the
banner and never opens a modal.

*Verification:* **std**.

**W3-SHELL scope.** §4.1–§4.3 and the §5.1 global bindings:
- `KetchLayout` tiers from window width; the card scaffold with wash, inset and r16.
- Expanded sidebar: title zone, destinations (Downloads, Discover, Devices), a DEVICES section built
  on today's `InstanceManager` API (names, health from the active device's connection state; live
  lines arrive in W4), Settings. The LIBRARY filter group and the wordmark are removed.
- Rail with the scope chip; phone top bar, chips collapsing through nested scroll exposed to
  `DownloadsScreen`, `ExtendedFloatingActionButton` (long-press = `quickAdd` from the clipboard),
  bottom bar only with 3 or more destinations.
- `ShortcutHost` binds every global command: `⌘V` quick add through a **bubbling** `onKeyEvent`
  (`LinkParser` + `AppState.quickAdd`; on web, `Clipboard.pasteEvents`), `⌘O` through
  `FilePicker.pickTorrentFiles()`, `⌘K` focuses search until W4 adds the palette, `⇧⌘D` is bound
  in W4.
- `AppDestination` = Downloads, Discover, Devices (Discover visibility unchanged until W4).
- Delete `SpeedStatusBar`.

*Acceptance:*
1. At 1280 × 800: sidebar 220 dp and the Pulse bar visible.
2. At 700 dp the rail shows; at 400 dp the phone shell shows with about 104 dp of static chrome.
3. Every global chord in §5.1 that exists in W3 works on desktop, and its fallback works on web.
4. `⌘V` with one plain link and no focused text field adds it with an Undo toast.
5. Owned allowlist entries reach zero.

*Verification:* **std**; screenshots at 360, 600, 840, 1024 and 1280 dp; desktop and web runs.

**W3-DESKTOP-CHROME scope.**
- macOS client properties (`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar`,
  `apple.awt.windowTitleVisible`) and the `LocalWindowChrome` provider; title-zone
  `WindowDraggableArea` and double-click zoom; `apple.awt.windowAppearance` follows the theme;
  window title = status sentence; fallback flag `-Dketch.decoratedWindow=true`.
- `SettingsWindow` (non-modal, 860 × 640, remembered bounds) hosting `SettingsHost`; `⌘,` opens or
  focuses it.
- `LocalIntegrationStatus`: detected browsers, extension connected, magnet default.

*Acceptance:*
1. Traffic lights sit inside the sidebar, and `◧`/`⊕` are clickable (spike first; if the top 28 dp
   swallows clicks, offset by 4 dp).
2. `⌘,` opens or focuses the Settings window while the main window keeps updating.
3. The checklist row flips to "Connected ✓" when the extension connects.
4. Windows and Linux keep their native decorations.

*Verification:* `./gradlew :app:desktop:compileKotlin :app:desktop:test`; packaged macOS run;
Windows/Linux smoke test.

**W3-ROW-ACTIONS scope:** §5.3–§5.4 as standalone pieces the table plugs in:
- `SelectionState` wiring helpers, rubber band (`pointerInput` + `LazyListState.layoutInfo`
  hit-test, auto-scroll within 48 dp), and the selection bar (replaces the tab row at 40 dp).
- `RowMenu` from `RowAction` (right-click via `isSecondaryPressed`; sheet on touch), hover actions,
  and the list keyboard model (`ListKeyboard`).
- `RemoveTasksDialog` with Trash (`FileActions.moveToTrash`) and the deferred-commit Undo.
- Drag-out (desktop file list via `dragAndDropSource`, the link elsewhere) and the in-app
  `TaskKey` payload.
- "Start now" with the preemption toast and Undo (`AppState.startNow`).

*Acceptance:* commonTest for keyboard mapping (key → `RowAction`), selection-bar verb counts
("Pause 2 of 3 selected"), and the Remove dialog copy (unchecked, Shift pre-check, sizes,
local Trash vs permanent delete).

*Verification:* **std**.

**W3-TABLE scope:** §4.7:
- `DownloadsScreen`: header (count pill, scope chip, search field, view toggle, stable `⋯`),
  `StatusTabs`, facet row, per-tab headers (Clear n finished, Retry all), and the inspector
  containers: docked (resizable 280–480 dp, persisted), overlay, bottom sheet (§4.8).
- `DownloadTable`: columns, auto-hide by priority, sort, header chooser, resize, persistence per
  tab, Smart sticky groups, reason spans.
- Two-line rows (pointer and touch) with swipe and long-press; `LaneStrip` in rows;
  `Modifier.animateItem`; 2 s stable re-sort.
- Plug in W3-ROW-ACTIONS (selection, menus, hover, keys, remove, drag) and `FileActions`
  (double-click/`↩` opens, `⌘↩` reveals).
- Launchpad with setup checklist, filtered-empty states, skeleton loading.
- Delete the old toolbar, filter chips, expanded panel and task-settings composables.

*Acceptance:*
1. At 1280 × 800 with the inspector closed, 17 table rows are visible (Compact rows preference:
   19); with a row inspected the inspector docks at 320 dp and Name, Size, Progress, Speed and
   Left stay visible.
2. Double-click on a completed row opens the file; `⌘↩` reveals it.
3. Click, `⇧`-click, `⌫` removes 5 rows with Undo.
4. Right-click › Connections › 16 re-splits the lanes live.
5. Failed rows show the `ErrorCopy` title; Canceled shows Download again; completed rows show
   size, time spent and average speed from `transferSummary()`.
6. Owned allowlist entries reach zero.

*Verification:* **std**; manual run against embedded and remote devices; scroll 1,000 fake tasks
in the desktop app and check for dropped frames.

**W3-INSPECTOR-TABS scope:** §4.8.4–§4.8.5: Connections (file map, lanes, rates and stalls from
`SegmentRateTracker`, scale tiers, captions), Files for torrents (virtualized, at most 360 dp,
names from `resolvedSource.files`), Activity (5-minute chart with limit lines and crosshair,
session timeline). Each tab is a composable taking the task and the history store.

*Acceptance:* a lane with no bytes for 3 s turns amber (test of the tracker integration with a
fake clock); a 300-file torrent renders a Files tab no taller than 360 dp and no "connection"
rows.

*Verification:* **std**.

**W3-INSPECTOR scope:** §4.8.1–§4.8.3: header, reason line, action bar (Open and Show via
`FileActions`, "Retry with options…" via `openIntake`), problem card, Controls (speed presets from
the live speed, log slider popover, `ConnectionStepper`, priority with preemption preview,
`StartTimePicker`), click-to-copy Details with link elision, the nothing-selected scope overview,
and the multi-select summary. Tabs come from W3-INSPECTOR-TABS.

*Acceptance:* one custom speed entry sends exactly one `setSpeedLimit`; Start is disabled on
remote devices with the tooltip; choosing Urgent with busy slots shows the preemption note first.

*Verification:* **std**; manual run with an HTTP download at 8 and 16 connections and a torrent.

**W3-INTAKE-SHEET scope:** §4.9.2–§4.9.6 and §4.9.8:
- `IntakeController` (`IntakeState.kt`) consuming `IntakeRequest`s; the top-anchored sheet;
  multi-line input; items with 4 parallel resolves on the target device; summary and free-space
  check; the single-item preview with the segment preview; option pills (Save to with recent,
  pinned and `FilePicker.pickFolder()`, Speed, Priority, Start, Connections); Advanced headers and
  cURL (headers go to `resolve(url, properties = headers)` and `DownloadRequest.headers`;
  `properties["ketch.origin"] = "app"` goes only to `DownloadRequest.properties`); outcome line;
  per-item submit and feedback; `IntakeProblems`; `⌥↩` → `openDiscover`.
- Torrent stage: background metadata fetch, filters, tree, extras skipped.
- Retry with options.
- The single-device drop overlay content (berths per device come in W4).

*Acceptance:*
1. Pasting 5 links shows "5 links · 5 ready", and `↩` adds 5 tasks; one failing link does not block
   the others.
2. A magnet's primary button stays disabled until metadata arrives.
3. A bare info hash becomes a magnet; a missing scheme is fixed silently.
4. "Save to" offers recent folders from history and "Choose folder…".

*Verification:* **std**; manual run on desktop and an Android emulator.

**W3-OS-INTAKE scope:** §4.9.7 (text drops) and §6:
- `FileDrop` readers accept `text/uri-list` and plain text (JVM `DataFlavor.stringFlavor`, web,
  Android `ClipDescription`); `FileDropTarget` keeps its signature and forwards text links.
- Android `ACTION_SEND text/plain`, `VIEW magnet`, `ACTION_PROCESS_TEXT`, `VIEW ketch://pair`, and
  a translucent `QuickAddActivity` that shows only `IntakeHost`.
- iOS `CFBundleURLTypes` (`magnet`, `ketch`) and `onOpenURL` routing by scheme.
- Web `protocol_handlers` (magnet) and `share_target`, and `?add=` read on load into
  `IncomingDownload.Links`.
- `ketch://pair…` links (Android intent filter, iOS URL type) become a new
  `IncomingDownload.Pairing(link)`; W4-CONNECT shows the confirmation sheet for them.

*Acceptance:* sharing from Android Chrome opens `QuickAddActivity` and returns; dropping a browser
link on the desktop window opens the sheet; an installed web PWA opens magnet links.

*Verification:* **std**; manual on desktop, an Android emulator and an installed PWA.

**W3-SETTINGS-DEVICE scope:** §4.10.2 device pages and §4.10.4:
- Restyled shared settings rows (`SettingsComponents.kt`) on the W2 controls.
- Downloads: folder row with Change… (`FilePicker`) and Show, the Android `/Android/data` warning,
  and the torrent note for SAF folders (§4.10.2).
- Speed page with modes, Slow lane suggestion and Auto rules (`SpeedModeController`,
  `SpeedScheduler`).
- Network; BitTorrent; Sharing with the Pair a device card (QR via `qrose`, LAN addresses from
  `networkInterfaces().available`, Copy link, Open web app, New code) and Advanced.
- `PairingLink` parse and build; `SettingsChoices` merged into `SpeedLimitPicker` presets.
- Pages take `(state: AppState, device: InstanceEntry)`, so they edit any connected device through
  `state.settingsFor(device)`.

*Acceptance:* `PairingLinkTest` shows the token only in the fragment and round-trips every accepted
form; changing the Slow lane speed updates the Pulse pill live.

*Verification:* **std**.

**W3-SETTINGS scope:** §4.10.1 and the app pages: two groups with the device chip, live summaries,
`KetchHueTile` icons, search (`⌘F` inside Settings), `openSettings(SettingsTarget)` deep links and
reopening on the last category; General (appearance, density, reduce motion, startup & window via
`LocalDesktopHooks`, Device name), Notifications, Integration (extension status from
`LocalIntegrationStatus`, magnet default with the Windows opt-in, clipboard mode, quick add),
Discover, About. Delete the old two-pane internals behind `SettingsHost`.

*Acceptance:* the device chip edits NAS settings without switching the main window; searching
"trackers" jumps to the BitTorrent row; Settings reopens on the last category; BitTorrent and
Sharing are hidden for remote devices.

*Verification:* **std**; manual run on desktop (window), web (page) and a phone (list).

**W3-DEVICES-STATE scope:** §4.5.1:
- `switchTo` never closes clients; a fresh `RemoteKetch` per reconnect (fixes the reuse of a closed
  client, `InstanceManager.kt:108-116`).
- `DevicePresence` per device with live speed, counts, failures, disk and mode.
- Keep-alive policy: watch at most 5 devices on desktop and web; close non-active remotes 10 minutes
  after the app goes to the background on mobile.
- `DeviceScope` (single device or All).
- Naming from `RemoteConfig.name` / `DiscoveredServer.name` / `KetchStatus.name`; new optional
  `RemoteConfig.name` and `watch` (hue is derived, not stored).
- `LanServerDiscovery.supported` (false on wasm).

*Acceptance:*
1. commonTest with `FakeInstanceFactory`: switching A → B → A reuses no closed client and
   reconnects at most once; presence counts match `StatusFilter`.
2. The mobile background policy closes after 10 minutes (virtual time).
3. Old config files without `name` or `watch` load.
4. `InstanceManager`'s existing public members (`instances`, `activeInstance`, `activeApi`,
   `serverState`, `switchTo`, `addRemote`, `removeInstance`, `reconnectWith`, `startServer`,
   `stopServer`, `embedded`) stay source-compatible, because other W3 packages build against them.

*Verification:* **std**, `./gradlew :config:jvmTest`.

**W3-ORIGIN-TAGS scope:** set `properties["ketch.origin"]` on requests the browser extension
(`browser`), the MCP tool set (`agent`) and the CLI (`cli`) create. The key never reaches
`resolve()`, whose `properties` are HTTP headers.

*Acceptance:* `request.test.js` and the MCP test assert the property; existing extension tests
pass.

*Verification:* `npm test` in `app/browser-extension`; `./gradlew :library:mcp:test :cli:test`.

### 7.6 Wave 4: Fleet, Discover, palette, phone and web reach

Goal: make devices first-class (live sidebar rows, switcher, All devices, Send to, drop berths,
Devices page, pairing), put Discover in front of users, add `⌘K`, and finish onboarding and the
platform polish.

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W4-CONNECT** | Add device sheet and ConnectLanding | new `S/ui/connect/**` (`AddDeviceSheet.kt`, `ConnectLanding.kt`); `app/web/src/wasmJsMain/kotlin/.../main.kt` (`#token=` pairing on load) | W3-DEVICES-STATE, W3-SETTINGS-DEVICE |
| **W4-PALETTE** | Command palette | new `S/ui/palette/**` + `PaletteRankingTest.kt` | W3-INTAKE-SHEET, W1-COMMANDS |
| **W4-DISCOVER** | Discover placement, setup page, live steps, review handoff | `S/ui/discover/**`; `S/ui/AiDiscoveryPage.kt`, `S/ui/AiDiscoveryContent.kt` (moved under `ui/discover`); `S/state/AiDiscoverController.kt`; `S/state/AiDiscoverState.kt`; `S/state/AiDiscoveryProvider.kt`; `S/state/AiSettingsController.kt`; `S/state/AppDestination.kt`; `app/shared/src/jvmMain/.../state/EmbeddedAiDiscoveryProvider.kt`; `app/shared/src/androidMain/.../state/EmbeddedAiDiscoveryProvider.kt`; `AiDiscoverDraftTest.kt`, `AiSettingsControllerTest.kt`, `app/shared/src/jvmTest/.../EmbeddedAiDiscoveryProviderFactoryTest.kt` | W3-INTAKE-SHEET |
| **W4-FLEET-SHELL** | Live device rows, switcher, device sheet, rail stack, drop berths | `S/ui/AppShell.kt`; `S/ui/sidebar/**`; `S/ui/shell/**` (new `DeviceSwitcherPopover.kt`, `DeviceSheet.kt`, `DropBerths.kt`); `S/ui/FileDropTarget.kt`; `S/ui/dialog/AddRemoteServerDialog.kt` (delete); `S/ui/dialog/InstanceSelectorSheet.kt` (delete); `app/shared/build.gradle.kts` (remove `compose.materialIconsExtended`) | W4-CONNECT, W4-PALETTE, W4-DISCOVER |
| **W4-DEVICES-PAGE** | Devices overview page | `S/ui/devices/**` | W3-DEVICES-STATE |
| **W4-ALL-DEVICES** | All devices list, Send to device, intake target chip | `S/state/AppState.kt`; `S/state/AppController.kt`; `S/state/TaskListModel.kt`; `S/state/IntakeState.kt`; `S/ui/downloads/**`; `S/ui/list/**`; `S/ui/intake/**`; `S/ui/inspector/**`; new `AllDevicesTest.kt` | W3-TABLE, W3-INSPECTOR, W3-INTAKE-SHEET, W3-DEVICES-STATE |
| **W4-DESKTOP-FLEET** | Tray and menu-bar Devices sections | `app/desktop/.../DesktopTray.kt`, `DesktopMenuBar.kt`, `TaskbarFeedback.kt`, `DesktopTrayModelTest.kt` | W3-DEVICES-STATE |
| **W4-MOBILE-WEB** | Phone onboarding, iOS continued processing, web PWA polish | new `S/ui/onboarding/**`; `S/App.kt`; `app/android/.../MainActivity.kt`; `app/ios/iOSApp.swift`; `app/ios/Ketch-Info.plist`; `app/shared/src/iosMain/.../KetchBackground.kt`; `app/shared/src/iosMain/.../MainViewController.kt`; `app/web/src/wasmJsMain/resources/manifest.json`; `app/web/src/wasmJsMain/resources/index.html` | W3-SHELL, W3-SETTINGS |

**W4-CONNECT scope:** the confirmation sheet for `IncomingDownload.Pairing` ("Connect to
Lins-MacBook-Pro?" [Connect]); §4.10.4 Add device sheet (pairing link or address field, manual fields under
"Enter details manually", the Token field revealed after a 401, "Find on network" only when
`LanServerDiscovery.supported`; `NEARBY_WIFI_DEVICES` is requested here on Android) and §4.12
ConnectLanding. Web `main` reads `#token=`, connects, and clears it with `history.replaceState`.

*Acceptance:* the web app with no devices shows ConnectLanding (no modal); a pairing link connects
in one step; the token never appears in the address bar afterwards.

*Verification:* **std**; web run against `ketch server`.

**W4-PALETTE scope:** §5.2: `CommandPalette(state, onDismiss)` with every provider (links via
`LinkParser`, speed tokens, commands with live counts, downloads by fuzzy name, navigation,
devices, the Discover fallback) and ranking.

*Acceptance:* `PaletteRankingTest` covers exact prefix > word prefix > subsequence and the recency
boost; "5m" yields "Set Slow lane to 5 MB/s"; "pause nas" yields the NAS command.

*Verification:* **std**.

**W4-DISCOVER scope:** §4.11:
- `AppDestination.visible` keys on `supported`; the setup page with examples and provider buttons
  that deep-link to Settings.
- `AiDiscoveryProvider.discover(request, onStep: (DiscoveryStep) -> Unit = {})` wired to
  `ai:discover`'s `DiscoveryStepListener` in both embedded providers, driving the live timeline.
- Results with confidence and the On: chip (`DeviceTargetChip`).
- Review & add → `openIntake` with seeds carrying `Referer = sourceUrl` and
  `ketch.origin=discover` / `ketch.query`; Add now with per-item adds; pending `DiscoverRequest`s
  from intake and the palette.

*Acceptance:* a fresh desktop install shows Discover in the sidebar with the setup page; one
failing candidate does not stop the others; requests carry `ketch.origin=discover`.

*Verification:* **std**, `./gradlew :ai:discover:test`; manual run with Ollama.

**W4-FLEET-SHELL scope:** §4.5.2–§4.5.3, §4.9.7:
- Sidebar device rows with full `DevicePresence` live lines, health rings, failure badges, the
  right-click menu (using `pauseAll(targets)`, `resumeAll(targets)`, `retryFailed(targets)`) and
  drop targets (links → `openIntake(targetDeviceId)` or `quickAdd`; dragged rows → `sendTo`).
- `All devices` row; `⌘⌥0-9`, `⇧⌘D`; the switcher popover and phone device sheet; the rail pennant
  stack with the aggregate arc.
- Drop berths; ConnectLanding and the Add device sheet wired in place of the old dialogs; `⌘K`
  opens the palette; Discover visibility switched to `aiSettings.supported`.
- Remove the `compose.materialIconsExtended` dependency once its last user
  (`AddRemoteServerDialog`) is deleted.

*Acceptance:*
1. Switching devices with `⌘⌥2` is instant for a watched device (no "Connecting" state).
2. Dropping a link on the NAS berth adds it there, and the view stays on This Mac.
3. `⌘K`, paste a link, `↩` adds it.
4. No file imports `androidx.compose.material.icons`.

*Verification:* **std**; manual run with two `ketch server` instances.

**W4-DEVICES-PAGE scope:** §4.6: adaptive grid of `DeviceCard`s (header, meta, speed, mode pill,
device lane, clickable counts, storage, interface chips, sharing, next actions, sparkline, offline
state, drop target); single-device "Add a device" card. Transfer stats are not shown (W6).

*Acceptance:* clicking "1 failed" on the NAS card switches to the NAS with the Failed tab in one
click; "Retry 1 failed" retries without switching; the phone layout is a single column.

*Verification:* **std**.

**W4-ALL-DEVICES scope:** §4.5.4–§4.5.5: `AppState` scope (single device / All) with the merged
`TaskListModel`; Device column and pennants; scoped Pause all, Resume all and Retry failed with
device-count toasts; Send to (menu, inspector button via `RowAction`, drag onto device rows) with
the move-with-`⌥` option and the cookie warning; intake `On:` chip resolving on the target with
`⌘⌥n` while open; last target remembered per kind.

*Acceptance:* with 2 fake devices, `⌘⌥0` shows tasks from both and pausing a NAS row calls the NAS
API; Send to copies headers and warns when cookies are present; an intake item targeted at the NAS
resolves through the NAS `KetchApi`.

*Verification:* **std**.

**W4-DESKTOP-FLEET scope:** tray Devices ▸ submenus (live line, Show, Pause all, Resume all,
Speed ▸, Add clipboard link here, Retry now when offline); the menu-bar Device menu with checkable
items and Pair a Device…; the tray icon and tooltip aggregate watched devices.

*Acceptance:* `DesktopTrayModelTest` covers the Devices section; the NAS can be paused from the
tray without opening a window (manual).

*Verification:* `./gradlew :app:desktop:test :app:desktop:compileKotlin`; packaged macOS run.

**W4-MOBILE-WEB scope:** §4.13, §6.3, §6.4:
- `WelcomeFlow` on Android and iOS, gated in `App.kt` by `UiPreferences.onboardingVersion`;
  Android branded splash while the service binds.
- iOS 26+ `BGContinuedProcessingTaskRequest` behind an availability check, with
  `BGTaskSchedulerPermittedIdentifiers` in `Ketch-Info.plist`, fed by aggregate progress exported to
  Swift.
- Web manifest `theme_color`/`background_color` and per-scheme `theme-color` metas.

*Acceptance:* Android first launch shows the flow, and "Use the Download folder" stores a
persistable tree URI used for HTTP and FTP downloads; the installed web PWA title bar matches the
canvas color in both schemes; iOS 26 shows system progress for a user-started download, and older
iOS is unchanged.

*Verification:* **std**; Android emulator; iOS simulator; Chrome PWA install.

### 7.7 Wave 5: Library API and engine (no app changes)

Goal: add the API that unlocks the honest-data features. New `DownloadTask` and `KetchApi` members
get **default implementations** (for example `val fileName: StateFlow<String?> get() = NoFileName`
with a shared `MutableStateFlow(null)` constant, or `suspend fun stats(): TransferStats? = null`),
so `RemoteKetch` and the fakes keep compiling. Remote parity follows in W6.

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W5-API-TASK** | Task state, metadata, config flow | `library/api/.../DownloadState.kt`, `DownloadTask.kt`, `KetchApi.kt`, new `PauseReason.kt`, `TaskCapabilities.kt`, `TransferStats.kt`; `library/core/src/commonMain/.../core/task/**`; `library/core/.../engine/DownloadQueue.kt`, `DownloadCoordinator.kt`, `DownloadExecution.kt`; `library/core/.../core/Ketch.kt`; `library/sqlite/**`; tests | — |
| **W5-API-SCHEDULE** | Manual schedule and serializable conditions | `library/api/.../DownloadSchedule.kt`, `DownloadCondition.kt`; `library/core/.../engine/DownloadScheduler.kt`; new `library/core/src/commonMain/.../core/condition/**` + platform actuals in `androidMain`, `iosMain`, `jvmMain`, `jsMain`, `wasmWasiMain`; tests | — |
| **W5-API-ERRORS-SEGMENTS** | Error kinds and per-segment telemetry | `library/api/.../KetchError.kt`, `Segment.kt`; `library/core/.../core/segment/**`; `library/core/.../engine/MultiNetworkHttpEngine.kt`, `HttpEngine.kt`; tests | — |
| **W5-API-TORRENT** | Torrent controller implementation | `library/torrent/**`; `library/api/.../api/torrent/**`; tests | — |

**W5-API-TASK scope** (land as three commits: state fields, task metadata, persistence):
- `DownloadState.Completed.completedAt: Instant?`.
- `DownloadState.Paused(progress, reason: PauseReason = PauseReason.User)` with
  `PauseReason { User, Preempted(byTaskId), WaitingForCondition, Shutdown }`, set by
  `DownloadQueue` preemption.
- `DownloadState.Queued` stays an object; `DownloadTask.queuePosition: StateFlow<Int?>`.
- `DownloadTask.fileName`, `files: StateFlow<List<SourceFile>>` (persisted in `TaskRecord`),
  `sourceType`, `capabilities` (`canReschedule`, `maxConnections`).
- `setConnections(0)` = Auto (`RealDownloadTask.kt:92` currently requires > 0).
- Optional `DownloadTask.restart()` keeping the id.
- `KetchApi.config: StateFlow<DownloadConfig>?` (default `null`), implemented by `Ketch`;
  `KetchApi.stats(): TransferStats?` declared with a `null` default (implemented in W6).
- SQLite migration file `4.sqm` (schema 4 → 5; #305 added `3.sqm`): `completed_at`, `files_json`,
  `pause_reason`.

*Acceptance:* old snapshot JSON without the new fields deserializes (test); queue preemption sets
`Preempted(byTaskId)`; the migration upgrades an existing database; `completedAt` is set once on
completion; `setConnections(0)` restores the configured default.

*Verification:* `./gradlew :library:api:jvmTest :library:core:jvmTest :library:sqlite:jvmTest
:library:core:compileKotlinIosSimulatorArm64 :library:remote:compileKotlinJvm`.

**W5-API-SCHEDULE scope:** `DownloadSchedule.Manual` (added paused; `reschedule(Immediate)` starts
it, so `RealDownloadTask.resume` is unchanged); serializable `DownloadCondition.UnmeteredNetwork`
and `Charging`, evaluated by platform providers in core (Android `ConnectivityManager` and battery
broadcasts, iOS `NWPathMonitor` and `UIDevice` battery state, JVM/JS/Wasi report "met").

*Acceptance:* old JSON with `Test` conditions still deserializes; a Manual task never starts until
rescheduled; conditions persist across restart (test with `InMemoryTaskStore`).

*Verification:* `./gradlew :library:api:jvmTest :library:core:jvmTest
:library:core:compileKotlinIosSimulatorArm64`.

**W5-API-ERRORS-SEGMENTS scope:**
- `KetchError.Disk(cause, kind: DiskFailure = DiskFailure.classify(cause), path: String? = null,
  detail: String? = cause?.message)` with `DiskFailure { NoSpace, ReadOnly, PermissionDenied,
  PathMissing, Other }`, and `KetchError.Network(cause, kind: NetworkFailure =
  NetworkFailure.classify(cause), detail)` with `NetworkFailure { Dns, Refused, Timeout, Tls,
  Reset, Other }`. The defaults classify from the cause, so the existing call sites in core, ktor,
  ftp and torrent need no change; `detail` is serializable and survives SSE and restart.
- `Segment.bytesPerSecond: Long?`, `retryCount: Int`, `networkInterfaceId: String?`, set by
  `SegmentedDownloadHelper` and reported by `MultiNetworkHttpEngine`.

*Acceptance:* classification tests map `ENOSPC`/`EACCES`/`UnknownHost`/`ConnectException`
messages and types; old JSON without the new fields deserializes; `bytesPerSecond` is reported
within one progress interval.

*Verification:* `./gradlew :library:api:jvmTest :library:core:jvmTest :library:ktor:jvmTest
:library:core:compileKotlinIosSimulatorArm64`.

**W5-API-TORRENT scope:** implement `TorrentController` for the embedded engine: paged peers
(address, client, rate down and up, progress, flags), trackers (label via `trackerLabel()`, status,
peers, next announce), piece bitfield snapshots, upload rate, ratio, and the file-selection
mutation (`TorrentCapability.FILE_SELECTION`). Exposed through a factory that W6 wires into `Ketch`.

*Acceptance:* interoperability tests cover snapshots and selection changes; logs never contain
tracker credentials.

*Verification:* `./gradlew :library:torrent:jvmTest :library:torrent:compileKotlinIosSimulatorArm64`.

### 7.8 Wave 6: Remote parity and API-backed UI

Goal: bring the Wave 5 members to remote clients, add the server endpoints, then light up
everything marked "W6" in this spec.

| ID | Title | Owned files | Depends on |
|---|---|---|---|
| **W6-API-CORE** | Transfer ledger and torrent wiring in the engine | `library/core/.../core/Ketch.kt`; new `library/core/.../core/stats/**`; `library/sqlite/**` (ledger table, `5.sqm`); tests | — |
| **W6-API-SERVER** | Endpoints, routes and SSE events | `library/endpoints/**`; `library/server/**`; `cli/src/main/kotlin/com/linroid/ketch/cli/Main.kt` (config persistence hook for `ketch server`); tests | — |
| **W6-API-REMOTE** | Remote client parity | `library/remote/**`; tests | W6-API-SERVER |
| **W6-UI-LIST-API** | List, intake and row copy on the new API | `S/ui/downloads/**`, `S/ui/list/**`, `S/ui/intake/**`; `S/state/RowAction.kt`, `TaskListModel.kt`, `SortAndGroup.kt`, `IntakeState.kt`; `S/util/ErrorCopy.kt`, `DisplayName.kt`, `RowContent.kt`, `QueueReason.kt` | W6-API-REMOTE |
| **W6-UI-INSPECTOR-API** | Inspector on the new API | `S/ui/inspector/**`; `S/util/SegmentRateTracker.kt` | W6-API-REMOTE, W6-API-CORE |
| **W6-UI-FLEET-API** | Fleet UI on the new API | `S/ui/devices/**`, `S/ui/pulse/**`, `S/ui/feedback/**`, `S/ui/settings/**`; `S/instance/**`; `S/state/PulseState.kt`, `SpeedModeController.kt`, `SpeedScheduler.kt` | W6-API-REMOTE, W6-API-CORE |

**W6-API-CORE scope:** `Ketch.stats()` backed by a `TransferLedger` (today, all time, per day for
182 days) persisted in SQLite so it survives task removal; `Ketch.torrents` wired to the W5
controller.

*Acceptance:* the ledger survives task removal and restart; `torrents` is non-null for the
embedded engine.

*Verification:* `./gradlew :library:core:jvmTest :library:sqlite:jvmTest`.

**W6-API-SERVER scope:**
- `TaskSnapshot` carries `fileName`, `files`, `sourceType`, `capabilities`, `queuePosition` and
  `completedAt`; `TaskMapper` fills them.
- `PUT /api/tasks/{id}/schedule`; `GET /api/tasks/{id}/file` with Range and bearer auth, plus a
  short-lived signed URL variant; `GET /api/fs/dirs?path=`; paged torrent peers, trackers and
  pieces; `GET /api/stats`.
- SSE `summary` (per-state counts + total bytesPerSecond, 1 Hz, under 1 KB) and `config_changed`
  events.
- `updateConfig` persisted through an optional `ConfigStore` hook that `ketch server` supplies.

*Acceptance:* `testApplication` tests for each endpoint, including Range and auth on the file
endpoint.

*Verification:* `./gradlew :library:server:test :library:endpoints:jvmTest :cli:test`.

**W6-API-REMOTE scope:** `RemoteDownloadTask` overrides the new members (including a working
`reschedule`); `RemoteKetch` implements `config` (from `config_changed`), `stats()`,
`fileDownloadUrl(taskId)`, `listDirectories(path)` and `reconnectNow()`;
`ConnectionState.Disconnected(reason, nextRetryAt)`; `RemoteTorrentController`.

*Acceptance:* a contract test runs the same assertions against `Ketch` and `RemoteKetch` (via
`testApplication`); rescheduling a remote task round-trips; two clients observe `config_changed`
within 1 s.

*Verification:* `./gradlew :library:remote:jvmTest :library:server:test`.

**W6-UI-LIST-API scope:** Finished column, Done sorted by finish time, day groups from
`completedAt`; pause-reason copy ("Paused for an urgent download · resumes automatically");
"Queued · 3rd in line"; live magnet names via `fileName`; `Disk`/`Network` kinds in `ErrorCopy`
("Disk is full · needs 2.1 GB"); remote Start later enabled by `capabilities.canReschedule`; intake
"Add paused", plus "Only on Wi-Fi" and "While charging" on phones; remote folder browsing in the
Save to pill; "Save to this computer" for completed remote tasks via `fileDownloadUrl`; Retry with
options keeping progress via `restart()` when only speed, priority or connections change.

*Acceptance:* each feature is hidden when its capability or field is absent (old daemons) and
shown when present.

*Verification:* **std**; manual run against an updated and an old `ketch server`.

**W6-UI-INSPECTOR-API scope:** Files tab from `DownloadTask.files`, with selection changes when
`FILE_SELECTION` is available; Peers, Trackers and Pieces tabs; upload rate and ratio in the header
for torrents; per-lane engine speed, retry count and interface tint in Connections; the stepper's
"Auto" via `setConnections(0)`.

*Acceptance:* the torrent tabs render for an active torrent and hide for old daemons; Auto restores
the default connection count.

*Verification:* **std**.

**W6-UI-FLEET-API scope:** `DevicePresence` switches to the summary SSE for background devices;
speed mode synced across clients through `config` / `config_changed`; banner countdown "retrying in
8 s" and [Retry now] via `reconnectNow()`; Devices page "Transferred today / all time" tile and a
26-week activity heatmap from `stats()`; upload tile for torrents; the Settings "until it restarts"
notice disappears when the server persists config; remote folder browsing in the Downloads
settings row.

*Acceptance:* two clients connected to one daemon show the same speed mode within 1 s of a change;
the banner countdown matches `nextRetryAt`; stats tiles appear only when `stats()` returns data.

*Verification:* **std**; manual run with two clients and one daemon.

---

## 8. Out of scope and open questions

### 8.1 Out of scope for this redesign

- **Backdrop blur / Haze** anywhere, including desktop-only frost. All surfaces are opaque (§3.9).
  This can be revisited behind a capability check after W4.
- **JetBrains Runtime switch** for custom title bars on Windows and Linux (macOS uses AWT client
  properties on Temurin 21).
- **Split view per device**, and **saved views** pinned in the sidebar. Search tokens ship
  instead.
- **Full Gmail-style single-key verbs** (`1-4`, `S`, `T`, `D`, `E`, `X`, `J/K`). These actions are
  in `⌘K` and menus.
- **Arrange mode and tile presets** for a dashboard (there is no dashboard).
- **Internationalization.** String extraction touches every UI file and would conflict with every
  wave. It should be a dedicated follow-up after W4 (composeResources `strings.xml` with plurals,
  zh-CN first, a language picker, `locales_config` on Android). Until then, new copy in this spec
  is written so it can be extracted, with no string concatenation of sentence fragments where a
  template works.
- **iOS Share Extension.** Discover on iOS and web, which needs `POST /api/discover` with SSE step
  events.
- **Push notifications** while the phone app is closed (needs a push relay).
- **Remote Trash semantics.** Remote deletes stay permanent, and the copy says so.
- **Menu-bar speed text** on macOS (AWT `TrayIcon` has no title text). The tray tooltip and the
  progress ring cover it. The "Show speed in the menu bar" setting is **not** built.
- **User-editable device hues** and per-category glossy icons beyond `KetchHueTile`.
- **PWA `window-controls-overlay`.** It needs title-bar insets from
  `navigator.windowControlsOverlay` fed into `LocalWindowChrome`; the web app stays `standalone`.
- **Torrents in Android SAF folders.** `library:torrent` writes only to filesystem paths
  (`docs/torrent.md`), so SAF folders apply to HTTP and FTP downloads only.
- **Friendly network kinds** ("Wi-Fi", "Ethernet"): needs `NetworkInterfaceInfo.kind`; until then
  the UI shows interface names.
- **Per-request file name with an Android SAF folder:** needs a `DownloadRequest` file-name field.
- **Daemon-side Auto speed rules** (`DownloadConfig.speedSchedule`): not scheduled; Auto runs in the
  app (open question 8).

### 8.2 Open questions for the maintainer

1. Default close action: should the first close **Ask** (as specified) or silently keep running in
   the tray?
2. Should `⌘V` quick add (no dialog, Undo toast) be on by default on desktop and web? The spec says
   yes. The alternative is to always open the sheet prefilled.
3. Accept the default desktop window change to 1280 × 800 and the docking threshold of 1040 dp?
   On a 1280 × 800 screen the window starts maximized.
4. Should **Slow lane**, as a name, replace "Low speed" in all copy (tray, notification action,
   CLI help)?
5. How many remote devices should stay connected in the background by default? The spec says up to
   5 on desktop and web, and 10 minutes in the background on mobile.
6. Windows magnet registration: opt-in from Settings (as specified), or offered at first run?
7. Should the setup checklist replace a desktop onboarding flow entirely? The spec says yes; phones
   keep a short WelcomeFlow.
8. API choices for Wave 5:
   - Should `Queued` carry its position, or should a separate `queuePosition` flow carry it (as
     specified)?
   - Should `DownloadTask.updateSource(url, headers)` exist, so Retry with options keeps progress
     after a link refresh?
   - Should `DownloadConfig.speedSchedule` move Auto speed rules into the daemon, or stay app-side?
9. The Origin facet relies on `properties["ketch.origin"]`, which W3-ORIGIN-TAGS sets in the
   browser extension (`browser`), the MCP tool set (`agent`) and the CLI (`cli`). Is that key name
   acceptable as a cross-client convention, and should it be documented in the public KDoc of
   `DownloadRequest.properties`?
10. When should i18n extraction be scheduled, and is zh-CN the right first locale?
