# Localization

How the Ketch apps show their text in the user's language, and how to add or translate a string or
a whole language. It is for contributors who write UI text and for translators.

## What gets translated

The apps show their text in the system's language and fall back to English, unless the user picks
one in **Settings → General → Language**:

- Android 13+ and iOS keep that choice in their per-app language setting (on iOS the row opens it
  in the Settings app).
- The desktop app, the web app and older Android keep it in `config.toml` as
  `[appearance] language` and apply it themselves (`i18n/AppLanguage.kt`).

These stay in English on purpose: library, server and CLI output, log messages, `KetchError`
messages, REST error responses, the prompts AI discovery sends to the model and the bug report
that "Copy details" puts on the clipboard.

## Adding a string

1. Add it to the English file of its area (see [where text lives](#where-text-lives)), with a
   comment when it needs one.
2. Use it from code, as described in [writing text](#writing-text).
3. Add the translation to the other languages' copies of the file if you can. Otherwise leave
   them: the string shows in English there until a translator adds it.

## Where text lives

| Part | File |
| --- | --- |
| Shared UI (`app/shared`) | `src/commonMain/composeResources/values/strings_<area>.xml` |
| Desktop (`app/desktop`): menu bar, tray and dialogs | `src/main/composeResources/values/strings.xml` |
| Android (`app/android`): notifications and the service | `src/main/res/values/strings.xml` |
| iOS (`app/ios`): the permission prompt and document type names | `<language>.lproj/InfoPlist.strings` |
| Browser extension (`app/browser-extension`) | `src/_locales/<language>/messages.json` |

The shared UI has one file per area, such as `strings_tasks.xml` for what download rows say or
`strings_settings.xml` for the Settings pages. `strings_common.xml` holds units, durations, dates
and the buttons every screen uses. The browser extension's own conventions are in
[its README](../../app/browser-extension/README.md#translations).

## Writing text

Never write user-facing text as a literal in `app/shared/src/commonMain`.

### In a composable

Read a string with `stringResource(Res.string.key)`, `stringResource(Res.string.key, arg)` or
`pluralStringResource(Res.plurals.key, count, count)`.

### In state and model code

Code that builds text outside composition returns a `UiText` (`com.linroid.ketch.app.i18n`). It
keeps the resource and its arguments until the text is shown:

```kotlin
val detail: UiText = listOfNotNull(
  Res.plurals.row_connections.text(connections),
  host?.let(::verbatim),
).joinText()
```

- `Res.string.key.text(args)` and `Res.plurals.key.text(count)` build it; an argument may be a
  `UiText` itself. A plural's count is its `%1$d` unless other arguments are given.
- `verbatim(text)` marks text that is never translated: file names, hosts, paths, device names the
  user chose, messages from servers.
- `joinText()` joins parts with " · ", as rows and headers do.
- Composables show it with `.resolve()`; coroutines, such as notifications, read it with
  `.load()`, in the language of the app's window.
- Never put a `UiText` in a string template or `joinToString`: it prints its key, as
  `⟦row_paused⟧`.

### Numbers, sizes and dates

Locale-aware formats live in `i18n/Formats.kt`: `sizeText`, `speedText`, `etaText`,
`durationText`, `spanText`, `percentText`, `decimal`, `shortDateText`, `clockTime` and
`priorityText`. Use them rather than formatting numbers, sizes or dates by hand.

### Whole sentences

Write one resource per sentence, with placeholders for what changes. Do not build sentences from
fragments ("Couldn't " + verb + " on " + device) or pick words by count in code: word order,
grammar and plural rules differ between languages. A phrase that starts in lower case because it
follows other text, as the hints after a row's error do, gets a resource of its own.

### The literal-text guard

`HardcodedTextTest` counts the literals that read like words in each file and fails when a count
goes up. The counts in `app/shared/src/jvmTest/resources/hardcoded-text-allowlist.txt` only hold
text that stays English on purpose, such as product names and the bug report.

- When a count goes down, lower it with
  `./gradlew :app:shared:jvmTest -PupdateTokenAllowlist`.
- To see the literals left in a file, set its count to 0 in the allowlist and run the test: the
  failure lists them.

## Resource files

```xml
<!-- %1$s is a device name, such as "NAS-Basement". -->
<string name="row_saved_on">Saved on %1$s</string>
<plurals name="row_files">
  <item quantity="one">%1$d file</item>
  <item quantity="other">%1$d files</item>
</plurals>
```

- **Keys** are snake_case and start with their area: `row_`, `intake_`, `settings_`, `device_`.
  Reuse a key only when the text means the same thing in the same kind of place.
- **Placeholders** are positional, `%1$s` or `%1$d`; Compose resources replace nothing else.
  Write a literal percent sign as `%`, not `%%`.
- **Escaping**: Compose resources keep text as written. Write apostrophes and quotes plainly
  (`Couldn't`, not `Couldn\'t`), escape only `<` and `&` (`&lt;`, `&amp;`), and keep each string on
  one line, which may run past 100 columns. Android's own `res/values` files follow Android's
  rules instead, where apostrophes need a backslash.
- **Comments**: comment a string whose placeholders or place are not obvious, so translators know
  what fills it and where it shows.
- **Plurals**: English has `one` and `other`; translations use the categories their language has
  (`few` and `many` in Russian, only `other` in Chinese, Japanese and Korean).

Translations follow [the glossary](translation-glossary.md), which fixes how each language names
the app's features and units.

## Tests

JVM tests read the English strings: `app/shared/build.gradle.kts` sets the test JVM's language and
region to `en-US`, whatever the machine's. The iOS simulator tests read the simulator's language,
so keep it English. Assert text inside `runTest` with `load()`:

```kotlin
@Test
fun rowContent_paused_showsPercent() = runTest {
  assertEquals("Paused · 40%", rowContent(request, paused, now, context).detail.load())
}
```

`LocalizationResourcesTest` checks every translation against English: no keys English lacks, the
same placeholders, plurals with an `other` form and no Android-style escapes. A key a translation
lacks falls back to English.

## Languages

| Language | Shared UI and desktop | Android | iOS | Browser extension |
| --- | --- | --- | --- | --- |
| English (source) | `values` | `values` | `en.lproj` | `en` |
| Chinese, Simplified | `values-zh` | `values-zh-rCN` | `zh-Hans.lproj` | `zh_CN` |
| Chinese, Traditional | `values-zh-rTW` | `values-zh-rTW` | `zh-Hant.lproj` | `zh_TW` |
| Korean | `values-ko` | `values-ko` | `ko.lproj` | `ko` |
| Japanese | `values-ja` | `values-ja` | `ja.lproj` | `ja` |
| Spanish | `values-es` | `values-es` | `es.lproj` | `es` |
| Portuguese (Brazil) | `values-pt` | `values-pt` | `pt-BR.lproj` | `pt_BR` |
| German | `values-de` | `values-de` | `de.lproj` | `de` |
| French | `values-fr` | `values-fr` | `fr.lproj` | `fr` |
| Russian | `values-ru` | `values-ru` | `ru.lproj` | `ru` |

### Chinese scripts

Chinese is written once per script: Simplified in `values-zh`, Traditional in `values-zh-rTW`. The
build copies Traditional for Hong Kong and Macau, which browsers and Windows report without a
script, and each script under its tag (`zh-Hans`, `zh-Hant`) for systems that report one. So
Simplified Chinese set up with a Hong Kong region still reads Simplified; see
`app/shared/build.gradle.kts` and `LocaleFallbackTest`.

### Right-to-left languages

Not supported yet: nothing has been checked in mirrored layouts.

## Adding a language

1. **Shared UI and desktop**: copy each `strings_*.xml` of
   `app/shared/src/commonMain/composeResources/values` and the desktop's `strings.xml` into a
   `values-<language>` folder next to it, and translate them. Folders take an ISO 639-1 code, a
   region after `-r` (`values-pt-rBR`) or a BCP 47 tag (`values-b+sr+Latn`). Set `language_tag` in
   `strings_common.xml` to the language's tag, such as `pt-BR` or `zh-Hant`: the web app marks its
   page with it.
2. **Android**: translate `app/android/src/main/res/values/strings.xml` into
   `values-<language>`. Android 13+ offers the languages of these folders in the app's language
   setting; the build generates the list.
3. **iOS**: add `<language>.lproj/InfoPlist.strings` to `app/ios` and register it in the Xcode
   project, in the `InfoPlist.strings` variant group and in `knownRegions`. iOS offers the app in
   the languages of its `.lproj` folders, and the macOS bundle lists the languages of the
   desktop's `values-*` folders.
4. **Browser extension**: add `app/browser-extension/src/_locales/<language>/messages.json`.
5. **Web app**: check it in the new language. Browsers give Compose no system fonts; for scripts
   the bundled Inter and JetBrains Mono lack, Compose downloads Noto font slices from
   fonts.gstatic.com as text needs them, choosing the Chinese, Japanese or Korean variant from the
   browser's language. Offline, such text shows as boxes.
6. **Settings**: add it to `AppLanguages` in `i18n/AppLanguage.kt`, named in itself, so Settings
   lists it.
7. **Check**: add it to the [table above](#languages) and run `./gradlew :app:shared:jvmTest`;
   `LocaleFallbackTest` checks that every translation is listed. To see the screens in it, render
   the UI snapshots with `-PsnapshotLocale=<tag>` ([testing](testing.md#ui-snapshots)).

Translations come in by pull request. The files use the Android string resource format, which
translation platforms such as Weblate and Crowdin read, should the project use one later.

## README translations

The root `README.md` is the English source. Full translations live beside it in
`README.zh-CN.md`, `README.es.md`, `README.ja.md`, `README.de.md` and `README.fr.md`. Each README
links to all the others at the top, with language names written in their own language.

When you change the README, keep the translations in sync, including feature limits and roadmap
items. Use the glossary and existing UI labels, keep command examples and link destinations, and
keep the explicit navigation anchors in translated files. Linked technical documentation stays in
English.

## See also

- [Translation glossary](translation-glossary.md)
- [Testing: UI snapshots](testing.md#ui-snapshots)
- [Code style](code-style.md)
