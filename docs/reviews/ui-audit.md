# UI review: design-system PR

Audit performed before this design pass with an isolated desktop profile and local fixtures.

## Findings

| Area | Observed issue | Correction applied |
| --- | --- | --- |
| List at 380px and 800px | Floating Add download overlaps rows and controls | Move Add to the top bar on compact layouts |
| Paused details | Empty segment and speed panels consume most of the card | Show metadata when not transferring |
| Completed / failed rows | Empty or full progress tracks compete with status | Show progress only for active / paused transfers |
| Navigation | Long Active category label wraps on narrow screens; heavy selection outline | Short Active label and tonal selection |
| Actions | Tiny icons mix Material and custom styles; limited visual feedback | Labeled advanced controls; shared hover / focus treatment and tooltips |
| Remote server | Selector stays behind Add remote server, doubling the scrim | Close selector before opening the form |
| Forms | Several dialog colors, title styles, spacing patterns | Neutral modal surfaces and consistent rounded shapes |
| Search | Only inner text region is the editing target; no visible focus border or clear action | Decorate the field itself; add focus border and clear action |
| Metadata | Secondary labels and monospaced values dominate the expanded view | Clearer type hierarchy and more compact details |

## Exercised before changes

- All / Active / Completed / Paused / Failed categories and empty category recovery.
- Completed and failed details, remove confirmation (without destructive confirmation).
- Paused details, speed limits, priority / schedule options, and lower-panel scrolling.
- Add download and its schedule options at 380px width.
- AI discovery form and disabled empty-submit state.
- Instance selector, Add remote server, and disabled empty-host submit state.
- A real 32 MiB loopback transfer with two connections; paused and resumed while retaining progress.
- Desktop, medium, and narrow navigation layouts.

## Coverage limits

Native automated text entry did not reach the fields, so typed search, invalid-input submission, authentication, and AI result retrieval are not UI-verified. No external AI service or remote server was used. Desktop checks do not replace Android/iOS keyboard testing. Dark appearance and post-change verification are recorded below when performed.

## Post-change verification

- JVM and Wasm compilation pass; 77 shared tests pass (0 failures / errors).
- Added search tests for whitespace, case, renamed destinations, original URLs, absent destinations, and nonmatches.
- Added light/dark palette contrast checks for primary/secondary/dim text and primary button labels.
- Rechecked 380px, 800px, and wider desktop layouts with long filenames and mixed completed/paused/failed states.
- Confirmed Add stays in the toolbar, with no row or footer overlap at 380px.
- Confirmed paused details omit unused graphs and advanced controls have visible labels.
- Confirmed narrow Add download speed presets fit and remain reachable in the scrollable form.
- Confirmed the remote-server form replaces the selector instead of stacking modal scrims.
- Re-exercised a real local transfer after the redesign: resume retained progress and showed live segments / throughput. The 32 MiB fixture then completed; the detail panel changed to metadata, the completed count increased, and the footer returned to Idle.
- Requested dark native appearance in the isolated build: macOS chrome changed, but Compose stayed in the system light theme. Dark palette is contrast-tested, not visually verified.
- Retested text entry after expanding the search focus area; the computer-use clipboard timed out. Search logic is unit-tested, but typed search is still not desktop-automation verified.

Review fixtures and the throttled HTTP server use `/private/tmp/ketch-ui-review`; no production remote connection or external AI request was submitted.

## Standalone AI discovery

- AI discovery is a separate main-navigation destination: a sidebar item on desktop and a Discover tab in compact layouts.
- Add download contains only the direct-link form. AI drafts and results survive navigation via shell-owned state.
- Starting selected AI downloads returns to All downloads. Closing a direct-link dialog does not cancel AI work.
- Verified desktop/compact discovery layouts, navigation back to the library, and removal of AI controls from Add download.
- Compact layouts now have two primary destinations (Downloads / Discover), with counted status filters inside Downloads. The desktop sidebar retains direct access to all statuses.
- JVM/Wasm compilation, desktop packaging, and 77 shared tests pass. External AI results and native typed drafts remain outside automated UI coverage.

## Deeper UX pass

- Simplified compact primary navigation and kept library filter selection when switching pages. Horizontal filters scroll only when the selected item is outside the visible area.
- Rebuilt discovery around a multiline description, example queries, an optional website filter, and a search action independent of download selection.
- Added cancellable loading, recoverable errors, empty-result guidance, and an unavailable-provider fallback to direct links.
- Result rows are fully selectable, show readable long titles and file sizes, and omit unexplained confidence percentages. Duplicate URLs are shown/downloaded once; stale selections cannot be submitted.
- Selected speed, priority, and schedule remain visible in Add download. Scheduled submissions use “Schedule download”. Mobile modal bodies scroll with actions outside the scrolling body.
- Added a first-download action, full filenames in expanded metadata, wrapping progress metrics, and sidebar hover/keyboard-focus feedback.
- Verified real desktop layouts at 380px and 800px, preserved discovery draft/website-panel state across navigation, and library empty/filter states.
- Used a temporary local discovery provider to exercise loading, populated/duplicate results, select one/all, empty results, errors, and cancellation with no external AI request. The fixture source was restored immediately after building the preview and is absent from the final production build.
- 79 shared tests pass, including new regressions for search context, stale selections, and duplicate links. JVM/Wasm compilation and desktop packaging pass.

- A 1100 × 450 window exposed clipped sidebar destinations; the navigation group now scrolls while the device selector stays visible.

- Verified the sidebar scroll fix in the production build at 1100 × 450 and the first-download action using a separate empty profile.
