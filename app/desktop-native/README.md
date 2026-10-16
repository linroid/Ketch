# Native Image desktop experiment

An opt-in prototype that runs Ketch's shared Compose UI with an in-memory HTTP download engine as a
GraalVM Native Image, using [Nucleus 2.5.0](https://github.com/NucleusFramework/Nucleus/tree/v2.5.0)
and its Tao window backend. It is a compatibility and memory experiment, not a desktop release:
the real desktop app and its release pipeline use `app:desktop`.

## Build and run

The module is only included when you pass `-PnativeDesktop=true`. Build the native app:

```shell
./gradlew -PnativeDesktop=true :app:desktop-native:createGraalvmNativeDistributable
```

Nucleus downloads its GraalVM toolchain on the first build. On macOS, the Xcode Command Line Tools
are also required.

To run the same prototype on the JVM for comparison:

```shell
./gradlew -PnativeDesktop=true :app:desktop-native:run
```

## What the prototype includes

- The shared Downloads UI and real HTTP downloads: downloads added here are real downloads.
- No persistent configuration or task database; it does not read the desktop app's saved data.
- No AI discovery, torrent or FTP sources, embedded server, tray, menus, updater, browser
  registration or OS file handlers.
- Closing the window exits the prototype.

Shared JVM platform actions still depend on AWT; they need validation, and possibly replacement,
before a Tao-based release.

## Measuring memory

- Use the same window size, data and idle interval for every run.
- Measure the app process, not the build tool or compiler.
- Java heap usage is not total process memory: record both RSS and the macOS physical footprint.
- A successful compilation alone does not show UI or download compatibility, or that the full
  desktop app can run below 100 MB.

## Experiment results (2026-10-05)

Built on Apple Silicon with Nucleus 2.5.0 and its downloaded GraalVM Community Edition
25.2.4+7.1 (Java 25.0.4). The compiler used quick-build optimization (`-Ob`), Serial GC and a
256 MiB maximum heap. The output is a native ARM64 Mach-O executable, not a JVM launcher:

`build/compose/binaries/main/graalvm-app/Ketch Native Prototype.app`

The real shared Downloads screen was checked through its accessibility tree and a screenshot. A
1 MiB file served over loopback HTTP was added through the UI; it reached Done and its SHA-256
matched the source (`fbbab289f7f94b25736c58be46a994c441fd02552cc6022352e3d86d2fab7c83`). This
covers a basic HTTP download, not HTTPS, ranged transfers or the omitted integrations.

Fresh sequential runs of this prototype, with an empty list, a 1100 × 760 dp window and 60 seconds
idle before `ps` and `vmmap -summary` (no forced GC):

| Runtime | RSS (MiB) | Physical footprint (MiB) |
| --- | ---: | ---: |
| Native Image | 369.1 | 421.2 |
| OpenJDK 21.0.11, `-Xms32m -Xmx256m` | 349.0 | 451.9 |

The JVM run used the same module's generated uber JAR. These are single-run measurements, not a
statistically established improvement:

- An earlier Native Image run launched through macOS measured 223 MiB RSS and 310 MiB physical
  footprint at 74 seconds. Launch conditions and allocator retention materially affected the
  result.
- The timed native run still reported 126.8 MiB of owned unmapped graphics memory and 39.2 MiB of
  IOSurface residency. Native compilation does not remove graphics allocations, and none of these
  runs reached 100 MiB.

The quick-build executable is about 262 MiB and the app bundle about 300 MiB. Optimized builds and
a full desktop migration have not been evaluated. The build's transitive classpath still includes
shared-module dependencies even when this host does not initialize their features.

### Logging workaround

The first build, with SLF4J Simple, failed because kotlin-logging 8.0.01 ships a GraalVM
substitution that targets its removed `internal.KLoggerFactory`. Using Logback, as the native CLI
does, disables that conditional substitution and lets the build complete. This workaround is
confined to the experimental module.
