# Release licensing

Ketch's own code is distributed under the root Apache-2.0 `LICENSE`. Dependency
licenses remain applicable to their respective components.

## Bundled notices

The maintained notice files live in
`app/shared/src/commonMain/composeResources/files/licenses/`. Compose packages them
for the apps, and Settings → About → Open-source licenses displays them. The web
distribution also includes a top-level `licenses/` directory. CLI JVM distributions
and native release archives include that directory beside the program.
The shell installer retains archive notices in `ketch-licenses/` beside the installed
executable. Older releases without a notices directory remain installable.

The notices cover js-joda, Logback, SLF4J, JNA and the fonts the apps bundle. They are
not a complete transitive-dependency inventory. Keep upstream copyright statements unchanged.

- Logback 1.6.3 is distributed using its EPL-2.0 option. The notices include the
  full EPL text and links to the corresponding upstream source archive and tag.
  Update both links when changing Logback; provide any distributed modifications
  to Logback under the applicable EPL terms.
- Logback ships only with the CLI; the desktop app logs through slf4j-simple. Both
  use SLF4J's MIT-licensed API.
- JNA uses its Apache-2.0 option. Retain licenses for any bundled native components
  as well; choosing this option does not replace their separate terms.
- js-joda's full BSD text is included. Preserve webpack's generated
  `*.LICENSE.txt` files too: they carry additional copyright statements and are
  referenced by the minified JavaScript.
- The apps bundle subsets of Inter 4.1, Inter Display 4.1 and JetBrains Mono 2.304
  (`app/shared/src/commonMain/composeResources/font/`) under the SIL OFL 1.1. The notices
  carry both OFL texts and describe the subsetting; neither font declares a Reserved Font
  Name. Update them when the fonts or their subsets change.

The Logback notice was obtained from the `v_1.6.3` upstream `LICENSE.txt`; EPL-2.0
from SPDX's `license-list-data/text/EPL-2.0.txt`; the JNA notice from the 5.19.1
JAR's `META-INF/LICENSE`; the SLF4J notice from the 2.0.18 slf4j-simple JAR's
`META-INF/LICENSE.txt`; and js-joda's license from the installed npm package.

Before publishing, audit the resolved runtime graph for each target, including
native components and bundled JREs. Preserve each component's required license,
copyright and NOTICE text and satisfy any source-distribution obligations. Inspect
the final archives/installers, not just repository files. A POM license URL alone
does not demonstrate that a binary distribution includes the required materials.

## Torrent implementation provenance

The production torrent implementation is Kotlin. Its build declares libtorrent4j
only in `jvmTest`; Transmission is an external interoperability test process.
These test clients are not evidence that Ketch's implementation is derived from
their source. Redistributing the clients themselves, including in test images,
requires preserving their licenses and meeting their distribution terms.

The initial review found BEP specification references and a NIST algorithm
reference in `Sha256.kt`, but no copied-code declarations or conflicting license
headers in the production torrent source. This is not a source-similarity audit
or proof of independent authorship. Authorship remains unverified; do not label
the implementation "clean room" on the basis of that review.

For any copied or translated implementation, record its upstream URL, revision,
original license and modifications, and retain required notices. Translation into
Kotlin does not remove upstream obligations. Check individual files: libtorrent's
main BSD license has exceptions, and Transmission is GPL with some more permissive
files. Do not incorporate GPL implementation code into the Apache-only library
without resolving the licensing implications first.

Before declaring provenance verified, review the introducing commits and their
source references, especially hashing, DHT, peer wire and Merkle code. If code was
adapted, establish the actual applicable terms before adding attribution or
replacing it. Do not invent attribution or assume that missing headers prove
originality.

References:

- https://www.bittorrent.org/beps/bep_0003.html
- https://github.com/arvidn/libtorrent/blob/RC_2_0/LICENSE
- https://github.com/transmission/transmission/blob/main/COPYING
- https://github.com/java-native-access/jna/blob/5.19.1/LICENSE
- https://logback.qos.ch/license.html
