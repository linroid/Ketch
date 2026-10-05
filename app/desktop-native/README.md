# Native Image desktop experiment

This opt-in host runs Ketch's shared Compose UI and an in-memory HTTP download engine using
[Nucleus 2.5.0](https://github.com/NucleusFramework/Nucleus/tree/v2.5.0), its Tao window backend,
and GraalVM Native Image. It is a compatibility and memory experiment, not a desktop release.

```shell
./gradlew -PnativeDesktop=true :app:desktop-native:createGraalvmNativeDistributable
```

Nucleus downloads its GraalVM toolchain on the first build. On macOS, Xcode Command Line Tools
are also required. To run the same prototype on the JVM for comparison:

```shell
./gradlew -PnativeDesktop=true :app:desktop-native:run
```

The module is excluded unless `-PnativeDesktop=true` is supplied. The existing desktop app and
release pipeline continue to use `app:desktop`.

The prototype has no persistent configuration or task database and does not read the desktop
app's saved data. Downloads added here are real downloads. It does not wire up AI, torrent/FTP
sources, the embedded server, tray, menus, updater, browser registration or OS file handlers.
Shared JVM platform actions still contain AWT dependencies; these need validation and possibly
replacement before a Tao-based release. Closing the window exits the prototype.

For memory comparisons, use the same window dimensions, data and idle interval. Measure the app
process, excluding the build tool and compiler. Java heap usage is not total process memory;
record both RSS and macOS physical footprint. A successful compilation alone does not establish
UI or download compatibility, or that the full desktop app can run below 100 MB.

## Experiment results (2026-10-05)

Built successfully on Apple Silicon with Nucleus 2.5.0 and its downloaded GraalVM Community
Edition 25.2.4+7.1 (Java 25.0.4). The compiler used quick-build optimization (`-Ob`), Serial GC
and a 256 MiB maximum heap. The output is a native ARM64 Mach-O executable, not a JVM launcher:

`build/compose/binaries/main/graalvm-app/Ketch Native Prototype.app`

Verified the actual shared Downloads screen through its accessibility tree and screenshot.
Added a 1 MiB file served over loopback HTTP through the UI; it reached Done and its SHA-256
matched the source (`fbbab289f7f94b25736c58be46a994c441fd02552cc6022352e3d86d2fab7c83`).
This covers a basic HTTP download, not HTTPS, ranged transfers or the omitted integrations.

Fresh sequential runs of this same prototype, with an empty list, a 1100 × 760 dp window and
60 seconds idle before `ps` and `vmmap -summary` (no forced GC):

| Runtime | RSS (MiB) | Physical footprint (MiB) |
| --- | ---: | ---: |
| Native Image | 369.1 | 421.2 |
| OpenJDK 21.0.11, `-Xms32m -Xmx256m` | 349.0 | 451.9 |

The JVM run used the same module's generated uber JAR. These are single-run measurements,
not a statistically established improvement: an earlier Native Image run launched through
macOS measured 223 MiB RSS / 310 MiB physical footprint at 74 seconds. Launch conditions and
allocator retention materially affected the result. The timed native run still reported
126.8 MiB of owned unmapped graphics and 39.2 MiB of IOSurface residency. Native compilation
does not remove graphics allocations, and none of these runs reached 100 MiB.

The quick-build executable is about 262 MiB and the app bundle about 300 MiB. Optimized builds
and a full desktop migration have not been evaluated. The build's transitive classpath still
includes shared-module dependencies even when this host does not initialize their features.

The initial build with SLF4J Simple failed because kotlin-logging 8.0.01 ships a GraalVM
substitution targeting its removed `internal.KLoggerFactory`. Using Logback, as the native CLI
does, disables that conditional substitution and lets the build complete. This workaround is
confined to the experimental module.
