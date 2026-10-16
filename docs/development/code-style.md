# Code style

The formatting and coding rules for Kotlin code in Ketch. They apply to everyone who changes the
code, people and coding agents alike. Most formatting rules come from the repository's
`.editorconfig`, so IntelliJ IDEA and Android Studio apply them when you reformat a file; the rest
are conventions that reviewers check.

## Quick reference

- 2 spaces, no tabs; lines of at most 100 characters.
- Explicit imports only, never `import foo.*`.
- Trailing commas on multiline **named** parameters and arguments, never on positional ones.
- Public APIs get explicit types and KDoc; everything else that is not API is `internal`.
- User-facing UI text lives in string resources, never in literals.
- Prefer simple, correct code to clever or micro-optimized code.

## Files

| Setting | Value |
| --- | --- |
| Indentation | 2 spaces, no tabs (continuation indent is also 2) |
| Line length | 100 characters at most |
| Line endings | LF (Unix style) |
| Charset | UTF-8 |
| Final newline | Required: every file ends with a newline |

## Imports

- **No star imports.** Import each name explicitly, so a reader (and a diff) shows exactly what a
  file uses. The `.editorconfig` sets the star-import thresholds out of reach, so the IDE never
  folds imports into `*`.
- **Order**: everything else, then `java.**`, `javax.**`, `kotlin.**`, then aliases (`^`). The
  IDE's *Optimize Imports* applies it.

```kotlin
import com.linroid.ketch.api.DownloadRequest
import io.ktor.client.HttpClient
import java.io.File
import kotlin.time.Duration.Companion.seconds
```

## Spacing

- **Colons**: no space before a type colon, one after (`val size: Long`); spaces on both sides of
  a supertype colon (`class RealDownloadTask : DownloadTask`).
- **Spaces around** assignment, arithmetic (additive and multiplicative), logical, equality and
  relational operators, the Elvis operator `?:`, function type arrows (`(Int) -> Unit`) and `when`
  arrows.
- **No spaces around** the range operator (`0..10`, not `0 .. 10`) or unary operators (`-x`,
  `!done`).
- **Space before parentheses** of `if`, `for`, `while`, `when` and `catch`.
- **Space before a lambda arrow** (`{ value -> ... }`) and after a comma.

```kotlin
val remaining = (total - downloaded).coerceAtLeast(0)
for (index in 0..<segments.size) {
  if (!segments[index].isComplete) return index
}
val name = request.fileName ?: "download"
```

## Wrapping

The formatter never re-wraps parameters, arguments, call chains or supertype lists on its own:
keep line breaks where you put them, and break lines yourself when they pass 100 characters.

### Trailing commas

Add a trailing comma only after the last **named** item of a list that spans several lines:

- named parameter declarations (`name: Type`);
- named arguments (`name = value`).

Positional arguments never get one.

```kotlin
class KermitLogger(
  minSeverity: Severity = Severity.Info,
  private val tag: String = "Ketch",
  config: StaticConfig? = null,
)

val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = logger,
)

val hosts = listOf(
  "example.com",
  "example.org"
)
```

Why: trailing commas keep diffs to one line when a named item is added, while a comma after
positional arguments reads like a missing argument.

## Alignment

- Align multiline parameters in declarations: **yes**.
- Align multiline arguments in calls: **no**.
- Align multiline binary operations: **no**.

## Blank lines

- At most 2 blank lines in a row, in code and in declarations.
- No blank line right after a class header.
- 1 blank line before a declaration whose comment or annotation is on its own line.

## Writing code

- **Public APIs**: give them explicit types and KDoc, and keep them minimal. Every public
  declaration is a promise to library users.
- **Everything else**: mark implementation details `internal`. In `library:api`, helpers that
  only Ketch's own modules use are annotated `@InternalKetchApi`, and implementing `KetchApi` or
  `DownloadTask` requires `@KetchImplementationApi`; the root build opts every module in.
- **Names**: meaningful and descriptive. A name should say what a thing is or does without a
  comment.
- **Functions**: focused and short. Split a function when it does two separate things.
- **Closed hierarchies**: use sealed classes or interfaces (for example `KetchError`), so a `when`
  over them is exhaustive and the compiler finds every place a new case must be handled.
- **Simplicity**: favor simple correctness over micro-optimizations. Optimize when a
  measurement shows a need.
- **User-facing text**: new UI text goes through string resources, never literals in
  `app/shared` code; see [localization](localization.md). `HardcodedTextTest` fails when a file
  gains literal text.
- **Third-party code**: when you copy, translate or adapt code from elsewhere, follow
  [licensing and provenance](licensing.md): keep its source revision, license and required
  notices.

## See also

- [Testing](testing.md): the rules for unit tests.
- [Localization](localization.md): how UI text is written and translated.
- [Logging](../logging.md): log levels, tags and what never goes in a log.
