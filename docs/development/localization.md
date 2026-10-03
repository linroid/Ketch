# Localization

The apps show their text in the language of the system, or of the app where the platform has a
per-app language setting (Android 13+, iOS), and fall back to English. Library, server and CLI
output stay in English, as do log messages, `KetchError` messages, REST error responses, the
prompts AI discovery sends to the model and the bug report "Copy details" puts on the clipboard.

## Languages

| Language | Shared UI and desktop | Android | iOS | Browser extension |
| --- | --- | --- | --- | --- |
| English (source) | `values` | `values` | `en.lproj` | `en` |
| Japanese | `values-ja` | `values-ja` | `ja.lproj` | `ja` |

[The glossary](translation-glossary.md) fixes how each language names the app's features and
units; keep translations to it.

Right-to-left languages are not supported yet: nothing has been checked in mirrored layouts.

## Where text lives

- **Shared UI** (`app/shared`): `src/commonMain/composeResources/values/strings_<area>.xml`, one
  file per area, such as `strings_tasks.xml` for what download rows say or `strings_settings.xml`
  for the Settings pages. `strings_common.xml` holds units, durations, dates and the buttons
  every screen uses.
- **Desktop** (`app/desktop`): its menu bar, tray and dialogs, in
  `src/main/composeResources/values/strings.xml`.
- **Android** (`app/android`): notifications and the service, in `src/main/res/values/strings.xml`.
- **iOS** (`app/ios`): the permission prompt and document type names, in
  `<language>.lproj/InfoPlist.strings`.
- **Browser extension** (`app/browser-extension`): `src/_locales/<language>/messages.json`.

## Writing text

Never write user-facing text as a literal in `app/shared/src/commonMain`. `HardcodedTextTest`
counts the literals that read like words in each file and fails when a count goes up; the counts
in `src/jvmTest/resources/hardcoded-text-allowlist.txt` only hold text that stays English on
purpose, such as product names and the bug report. When a count goes down, run
`./gradlew :app:shared:jvmTest -PupdateTokenAllowlist` to lower it. To see the literals left in a
file, set its count to 0 in the allowlist and run the test: the failure lists them.

In a composable, read a string with `stringResource(Res.string.key)`,
`stringResource(Res.string.key, arg)` or `pluralStringResource(Res.plurals.key, count, count)`.

State and model code, which builds text outside composition, returns a `UiText`
(`com.linroid.ketch.app.i18n`), which keeps the resource and its arguments until the text is
shown:

```kotlin
val detail: UiText = listOfNotNull(
  Res.plurals.row_connections.text(connections),
  host?.let(::verbatim),
).joinText()
```

- `Res.string.key.text(args)` and `Res.plurals.key.text(count)` build it; an argument may be a
  `UiText` itself. A plural's count is its `%1$d` unless other arguments are given.
- `verbatim(text)` marks text that is never translated: file names, hosts, paths, device names
  the user chose, messages from servers.
- `joinText()` joins parts with " · ", as rows and headers do.
- Composables show it with `.resolve()`; coroutines, such as notifications, read it with
  `.load()`, in the language of the app's window.
- Never put a `UiText` in a string template or `joinToString`: it prints its key, as
  `⟦row_paused⟧`.

Locale-aware formats live in `i18n/Formats.kt`: `sizeText`, `speedText`, `etaText`,
`durationText`, `spanText`, `percentText`, `decimal`, `shortDateText`, `clockTime` and
`priorityText`. Use them rather than formatting numbers, sizes or dates by hand.

Write one resource per sentence, with placeholders for what changes. Do not build sentences from
fragments ("Couldn't " + verb + " on " + device) or pick words by count in code: word order,
grammar and plural rules differ between languages. A phrase that starts in lower case because it
follows other text, as the hints after a row's error do, gets a resource of its own.

### Resource files

```xml
<!-- %1$s is a device name, such as "NAS-Basement". -->
<string name="row_saved_on">Saved on %1$s</string>
<plurals name="row_files">
  <item quantity="one">%1$d file</item>
  <item quantity="other">%1$d files</item>
</plurals>
```

- Keys are snake_case and start with their area: `row_`, `intake_`, `settings_`, `device_`.
  Reuse a key only when the text means the same thing in the same kind of place.
- Placeholders are positional, `%1$s` or `%1$d`; Compose resources replace nothing else. Write a
  literal percent sign as `%`, not `%%`.
- Compose resources keep text as written: write apostrophes and quotes plainly (`Couldn't`, not
  `Couldn\'t`), escape only `<` and `&` (`&lt;`, `&amp;`), and keep each string on one line, which
  may run past 100 columns. Android's own `res/values` files follow Android's rules instead, where
  apostrophes need a backslash.
- Comment a string whose placeholders or place are not obvious, so translators know what fills it
  and where it shows.
- English plurals have `one` and `other`; translations use the categories their language has
  (`few` and `many` in Russian, only `other` in Chinese, Japanese and Korean).

## Tests

JVM tests read the English strings: `app/shared/build.gradle.kts` sets the test JVM's language
and region to `en-US`, whatever the machine's. The iOS simulator tests read the simulator's
language, so keep it English. Assert text inside `runTest` with `load()`:

```kotlin
@Test
fun rowContent_paused_showsPercent() = runTest {
  assertEquals("Paused · 40%", rowContent(request, paused, now, context).detail.load())
}
```

`LocalizationResourcesTest` checks every translation against English: no keys English lacks, the
same placeholders, plurals with an `other` form and no Android-style escapes. A key a translation
lacks falls back to English.

## Adding a string

1. Add it to the English file of its area, with a comment when it needs one.
2. Use it from code as above.
3. Add the translation to the other languages' copies of the file if you can; otherwise leave
   them, and the string shows in English there until a translator adds it.

## Adding a language

1. Copy each `strings_*.xml` of `app/shared/src/commonMain/composeResources/values` and the
   desktop's `strings.xml` into a `values-<language>` folder next to it, and translate them.
   Folders take an ISO 639-1 code, a region after `-r` (`values-pt-rBR`) or a BCP 47 tag
   (`values-b+sr+Latn`).
2. Translate `app/android/src/main/res/values/strings.xml` into `values-<language>`. Android
   13+ offers the languages of these folders in the app's language setting; the build generates
   the list.
3. Add `<language>.lproj/InfoPlist.strings` to `app/ios` and register it in the Xcode project:
   in the `InfoPlist.strings` variant group and in `knownRegions`. iOS offers the app in the
   languages of its `.lproj` folders, and the macOS bundle lists the languages of the desktop's
   `values-*` folders.
4. Add `app/browser-extension/src/_locales/<language>/messages.json`.
5. Check the web app in the new language. Browsers give Compose no system fonts; for scripts the
   bundled Inter and JetBrains Mono lack, Compose downloads Noto font slices from
   fonts.gstatic.com as text needs them, choosing the Chinese, Japanese or Korean variant from the
   browser's language. Offline, such text shows as boxes.
6. Add it to the table above and run `./gradlew :app:shared:jvmTest`. To see the screens in it,
   render the UI snapshots with `-PsnapshotLocale=<tag>` ([testing](testing.md#ui-snapshots)).

Translations come in by pull request. The files use the Android string resource format, which
translation platforms such as Weblate and Crowdin read, should the project use one later.
