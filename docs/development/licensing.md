# Licensing and provenance

What a Ketch release must carry to meet the licenses of the code it bundles, and the rules for
bringing outside code into the repository. It is for maintainers who cut releases or update
bundled dependencies, and for contributors who copy, translate or adapt code from elsewhere.

Ketch's own code is distributed under the Apache-2.0 license in the root `LICENSE`. Each
dependency's license still applies to that component.

## Before publishing a release

1. Audit the resolved runtime dependency graph of each target, including native components and
   bundled JREs.
2. Keep each component's required license, copyright and NOTICE text, and meet any obligation to
   provide source code.
3. Inspect the final archives and installers, not just the repository files.

A license URL in a POM does not show that a binary distribution includes the required materials.

## Bundled notices

The maintained notice files live in
`app/shared/src/commonMain/composeResources/files/licenses/` (`LICENSE.txt` and
`THIRD-PARTY-NOTICES.txt`). They ship with every distribution:

| Distribution | Where the notices are |
| --- | --- |
| Apps | Packaged by Compose; shown in **Settings → About → Open-source licenses** |
| Web app | A top-level `licenses/` directory |
| CLI (JVM distribution and native release archives) | A `licenses/` directory beside the program |
| Shell installer (`install.sh`) | A `ketch-licenses/` directory beside the installed executable |

Older releases without a notices directory remain installable.

The notices cover js-joda, Logback, SLF4J, JNA and the fonts the apps bundle. They are not a
complete inventory of transitive dependencies. Keep upstream copyright statements unchanged, and
update a notice whenever its component's version changes.

### Per-component notes

- **Logback** (CLI only) is distributed under its EPL-2.0 option. The notices include the full
  EPL text and links to the upstream source archive and tag of the version shipped. Update both
  links when changing Logback, and provide any distributed modifications to Logback under the EPL
  terms.
- **SLF4J**: the desktop app logs through slf4j-simple and the CLI through Logback; both use
  SLF4J's MIT-licensed API.
- **JNA** is used under its Apache-2.0 option. Keep the licenses of any bundled native components
  as well: choosing this option does not replace their separate terms.
- **js-joda** (web app, including the web app the CLI bundles): its full BSD text is included.
  Keep webpack's generated `*.LICENSE.txt` files too: they carry additional copyright statements
  and the minified JavaScript refers to them.
- **Fonts**: the apps bundle subsets of Inter 4.1, Inter Display 4.1 and JetBrains Mono 2.304
  (`app/shared/src/commonMain/composeResources/font/`) under the SIL OFL 1.1. The notices carry
  both OFL texts and describe the subsetting; neither font declares a Reserved Font Name. Update
  them when the fonts or their subsets change.

### Where the notice texts came from

| Notice | Source |
| --- | --- |
| Logback | The upstream `LICENSE.txt` at the release tag the notice names |
| EPL-2.0 | SPDX `license-list-data/text/EPL-2.0.txt` |
| JNA | The 5.19.1 JAR's `META-INF/LICENSE` |
| SLF4J | The slf4j-simple JAR's `META-INF/LICENSE.txt` |
| js-joda | The installed npm package |

The versions in `gradle/libs.versions.toml` are the ones that ship; check that the notices name
the same ones.

## Third-party code

For any copied or translated implementation:

- record its upstream URL, revision, original license and your modifications;
- keep its required notices.

Translating code into Kotlin does not remove upstream obligations. Check the license of each
individual file: libtorrent's main BSD license has exceptions, and Transmission is GPL with some
more permissive files. Do not bring GPL implementation code into the Apache-licensed libraries
without resolving the licensing implications first.

## Torrent implementation provenance

The production torrent implementation is Kotlin. Its build declares libtorrent4j only for
`jvmTest`, and Transmission is an external process that the interoperability tests start. These
test clients are not evidence that Ketch's implementation derives from their source.
Redistributing the clients themselves, including in test images, requires keeping their licenses
and meeting their distribution terms.

The initial review found BEP specification references and a NIST algorithm reference (now in
core's `digest/Sha256.kt`, which the torrent module uses), but no copied-code declarations or
conflicting license headers in the production torrent source. That review is not a
source-similarity audit or proof of independent authorship. Authorship remains unverified: do not
call the implementation "clean room" on the basis of that review.

Before declaring provenance verified, review the introducing commits and their source references,
especially the hashing, DHT, peer wire and Merkle code. If code was adapted, establish the terms
that actually apply before adding attribution or replacing it. Do not invent attribution, and do
not assume that missing headers prove originality.

## References

- [BEP 3: The BitTorrent protocol specification](https://www.bittorrent.org/beps/bep_0003.html)
- [libtorrent license](https://github.com/arvidn/libtorrent/blob/RC_2_0/LICENSE)
- [Transmission license](https://github.com/transmission/transmission/blob/main/COPYING)
- [JNA 5.19.1 license](https://github.com/java-native-access/jna/blob/5.19.1/LICENSE)
- [Logback license](https://logback.qos.ch/license.html)
