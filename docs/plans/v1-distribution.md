# Distribution before v1.0.0

Status: proposed implementation plan. No signing accounts or store submissions are configured
by this document. Baseline checked on 2026-10-05 against the release workflow and packaging code.

## Goal and scope

Before tagging `v1.0.0`, users should be able to install the desktop app and browser extension
through supported distribution channels without enabling developer mode or bypassing an
unverified publisher warning. Windows SmartScreen reputation is separate from a valid signature:
new signed releases may still show an unrecognized-app warning. Zero SmartScreen prompts is not
a release gate.

Keep GitHub Releases as the primary channel for the desktop app, native CLI, Android APK and
web archive. Keep Maven Central for the SDK. Publish the extension through Chrome Web Store,
Edge Add-ons and Mozilla Add-ons, and provide a Mozilla-signed Firefox XPI on GitHub.

Microsoft Store/MSIX, Mac App Store, Google Play, iOS App Store, package-manager listings and
Docker distribution are follow-up work under this plan. They do not block v1.0.0. In particular,
do not advertise an iOS store download before one exists; preserve the documented source-build
route. Safari remains outside the extension's supported browsers.

## Current baseline

- [Release CI](../../.github/workflows/release.yml) builds desktop DMGs and DEBs for x64 and
  arm64, Windows MSIs and portable ZIPs for x64 and arm64, and native CLI archives for macOS,
  Linux and Windows. The Windows CLI currently has only an x64 release.
- Desktop and CLI releases have no trusted Windows signing or Apple notarization steps.
  Windows CLI binaries are compressed with UPX before archiving. The ARM64 MSI is patched
  after packaging. macOS DMGs are converted to LZMA before uploading.
- Android release CI accepts keystore secrets, but the plan must verify the final APK's actual
  signing identity rather than infer production signing from a successful build.
- [Extension packaging](../../app/browser-extension/build.mjs) produces Chromium and Firefox
  ZIPs. The [installation instructions](../../app/browser-extension/README.md#installing) still
  require unpacked or temporary installation. Firefox's stable ID is `ketch@linroid.github.io`.
- `NativeHostRegistration` currently allows one development Chromium ID. Its comment calls for
  adding Chrome Web Store and Edge Add-ons IDs when published.
- The [updater](../updates.md) selects assets by exact naming conventions and verifies GitHub's
  SHA-256 asset digest. It does not independently authenticate a platform signing identity.
- Maven publication waits for app builds but not extension builds. Tag-triggered release CI
  does not itself gate on completion of the full test workflow or set an explicit prerelease flag.

## 1. Accounts, identities and external lead times

Start these tasks before the final release candidate; account validation and store review have
no guaranteed completion date. The release maintainer owns applications, fees and approvals.

- [ ] Apply to [SignPath Foundation][signpath] with the public repository and existing Windows
  releases. Confirm acceptance and the exact binary types that the project may sign.
- [ ] Meet its [published requirements][signpath-terms]: an eligible open-source license,
  verifiable builds, maintainer MFA, named signing roles, a code-signing policy, privacy
  disclosure and manual approval of each release's signing request. Account for bundled
  dependencies; do not sign upstream binaries as if Ketch maintained their source.
- [ ] Confirm the displayed Windows publisher identity. A Foundation certificate identifies
  SignPath Foundation; installation documentation must describe the identity users will see.
- [ ] If SignPath is unavailable, select a paid signing provider before the final RC. Check
  eligibility for Azure Artifact Signing or a traditional CA, budget and CI integration first.
  Do not silently replace the required signed Windows release with an unsigned one.
- [ ] Enroll in, or reuse, the Apple Developer Program and obtain a Developer ID Application
  certificate plus notarization credentials. SignPath's macOS integration does not supply an
  Apple identity. Direct Apple tooling in CI is sufficient for this plan.
- [ ] Register Chrome Web Store, Edge Add-ons and Mozilla Add-ons publisher accounts. Chrome
  requires a one-time registration fee; Edge and Mozilla publication are free. Apple membership
  normally costs US$99/year or its local equivalent; confirm current charges when enrolling.
- [ ] Reserve extension listings early, record the assigned IDs and store URLs, and establish
  recovery access for publisher accounts and signing credentials.

## 2. Windows desktop and CLI signing

Keep MSI and portable ZIP distribution on GitHub. Free Microsoft Store signing applies to its
MSIX distribution path and does not sign Ketch's existing GitHub MSI or executable assets.

- [ ] Integrate the chosen signing service with CI artifacts from the release's exact commit.
  With SignPath, use its verified build integration and required approval workflow. Store
  credentials in protected CI secrets/environments and keep them out of untrusted PR jobs.
- [ ] Resolve signing coverage for the generated jpackage launcher and native components with
  the provider. Inventory bundled runtime and library binaries, retaining upstream signatures
  where present and recording any unsigned dependencies that the policy permits.
- [ ] Restructure desktop packaging so the verified, signed `Ketch.exe` is included in both
  the MSI and portable ZIP. Ensure later Gradle tasks do not regenerate an unsigned launcher.
  If the service signs nested artifacts, verify the extracted final payloads too.
- [ ] Finish the existing ARM64 MSI metadata correction before signing the outer MSI.
  Preserve the pinned UpgradeCode and monotonic installer version mapping.
- [ ] Sign the Windows CLI after UPX compression and before ZIP packaging. If compression
  conflicts with signing or clean-machine execution, remove it and measure the size impact.
- [ ] Apply trusted timestamps and verify final MSI and executable signatures, certificate
  chains, expected publisher and timestamps using Windows signing tools. Fail on missing or
  invalid signatures; generating a ZIP or MSI successfully is not sufficient.
- [ ] Test downloaded artifacts on clean Windows machines for both desktop architectures:
  install, launch, native messaging, magnet and torrent handlers, uninstall, MSI upgrade and
  portable update with its `data` directory preserved. Record any SmartScreen prompts without
  treating reputation as proof of signature validity.

## 3. macOS desktop and CLI signing

Use Developer ID signing and Apple's notarization service for GitHub downloads. No Mac App
Store listing is required. Use one stable bundle identity and signing team across releases.

- [ ] Configure release signing in desktop packaging. Sign nested native libraries, helpers
  and the JVM runtime in the correct order, then the app bundle, using the hardened runtime
  and only the entitlements the packaged JVM and native integrations require.
- [ ] Exercise the packaged app with those entitlements: Compose rendering, SQLite, torrents,
  discovery, notifications, browser native messaging and self-update.
- [ ] Complete the current LZMA DMG conversion before signing the final DMG. Submit that final
  image for notarization, require acceptance, staple its ticket and validate it before upload.
- [ ] Sign each macOS native CLI binary before archiving. Submit it in a supported notarization
  container, require acceptance and ship the same signed executable in the existing `.tar.gz`.
  A raw executable or TAR archive cannot carry a stapled ticket; document and test the first-run
  online check. Decide on an additional staplable package if offline CLI installation is needed.
- [ ] Verify signatures and Gatekeeper behavior on browser-downloaded releases for Apple
  silicon and Intel. Test first launch, DMG installation, CLI execution, native messaging and
  upgrade from an existing unsigned release as well as between two signed builds.
- [ ] Use a temporary CI keychain, protect signing/notarization credentials, and remove imported
  secrets after the job. Compute release checksums only after signing and stapling finish.

## 4. Browser extension stores and signed Firefox downloads

Browser signing is independent of desktop signing. Chrome, Edge and Firefox on macOS do not
require an Apple certificate for the extension itself; the native Ketch app still needs one.

- [ ] Prepare store descriptions, screenshots, support links, permission justifications and a
  privacy policy matching the implementation. Explain download interception, native messaging,
  cookies and headers forwarded to the user's selected Ketch instance. Check each store's data
  disclosures against those behaviors, including Firefox's current `none` declaration.
- [ ] Submit the Chromium package to Chrome Web Store and Edge Add-ons. Normal users should
  install from store links; keep unpacked ZIP installation documented for development only.
- [ ] Add the actual store IDs to `NativeHostRegistration.CHROMIUM_EXTENSION_IDS` before
  freezing desktop release candidates. Keep the development ID for documented local builds,
  and test that unlisted origins remain refused.
- [ ] Publish Firefox through Mozilla Add-ons with its existing stable add-on ID. Obtain a
  signed XPI for GitHub distribution through Mozilla's supported signing workflow; never
  rename the unsigned ZIP to XPI and present it as signed.
- [ ] Choose and document Firefox update ownership. Prefer AMO-managed updates for the stable
  listed extension. If an unlisted beta channel is needed, configure and host its update
  manifest deliberately; verify its relationship to the stable channel before publishing.
- [ ] Preserve increasing numeric extension versions across RCs, stable releases and retries.
  The current build uses the workflow run number as a fourth component; test store acceptance
  and define retry behavior so an already-submitted version is not reused with different bytes.
- [ ] Test store-installed extensions in Chrome, Edge and Firefox on Windows, macOS and Linux:
  cold-start Ketch, connect to a remote instance, hand off a download, handle a torrent/magnet,
  fall back when Ketch is unavailable, and update the extension without losing settings.
- [ ] Update the extension README, root download table and app integration links with approved
  listings and the signed XPI. Keep extension/store version compatibility with the desktop
  release explicit so independent store review delays do not break handoff.

## 5. Release workflow and remaining channels

- [ ] Separate build, signing, verification and publication. A signing failure, rejected
  notarization, missing artifact or failed required test must prevent stable publication.
- [ ] Gate on required tests for the exact release commit, including extension tests, before
  irreversible Maven Central publication. Make the Maven job depend on all required gates.
- [ ] Stage verified assets in a draft GitHub release, then publish after the artifact inventory
  and smoke results are complete. Explicitly mark RC tags as prereleases so `/releases/latest`
  and the updater do not offer them as stable releases.
- [ ] Preserve every updater-facing asset name from [updates](../updates.md). Generate a
  SHA-256 manifest from the final distributable bytes and retain signing/notarization evidence
  with the release run. Keep GitHub digest checking; checksums alone are not publisher signing.
- [ ] Verify the Android APK with `apksigner`, require the intended release certificate and
  test upgrade from the previous APK without losing data. Document key backup and recovery.
- [ ] Smoke-test Linux DEBs and CLI archives on x64 and arm64, including install, permissions,
  native messaging and updates. Retain bundled license notices on every platform.
- [ ] Verify Maven Central coordinates, signatures and a minimal consumer using `1.0.0`.
  Check that the web archive and CLI-bundled web UI connect to a compatible daemon.
- [ ] Define failure recovery: retry submissions using recorded artifact hashes; never replace
  a published version with different bytes. Withdraw a broken release from stable discovery
  and publish a new patch version. Do not force data-schema downgrades as a rollback mechanism.
- [ ] Update unsigned-build warnings in `docs/updates.md` only when the shipped artifacts are
  signed, and document verified publishers, install routes and remaining platform limitations.

## Milestones and release gate

1. **Before the final RC:** signing applications accepted or a fallback provider chosen;
   Apple credentials working; extension listings and IDs established; policy pages ready.
2. **Signed RC rehearsal:** run the entire pipeline without publishing `1.0.0`; install the
   downloaded artifacts on clean machines and exercise updates between two signed RC builds.
   Use explicit RC selection for updater tests since latest-release discovery excludes RCs.
3. **Launch readiness:** record evidence below against the exact candidate commit. Store
   approvals must already be available for the extension versions advertised at launch.
4. **v1.0.0:** build and verify final-version artifacts, obtain required signing approvals,
   publish Maven and the complete GitHub release, then verify public downloads and store links.

The release maintainer records the commit, workflow run, artifact hashes, signing identities,
store versions/URLs, tested OS/architecture and results in the release checklist. Every required
row needs evidence; a successful RC does not substitute for verifying the final release files.

- [ ] Windows MSI, portable executable and CLI signatures verified.
- [ ] macOS app and CLI signed, notarization accepted, final DMG ticket validated.
- [ ] Fresh installations and supported upgrades pass without data loss.
- [ ] Chrome, Edge and Firefox production install routes work with the packaged native host.
- [ ] Firefox GitHub XPI is Mozilla-signed and its update path is verified.
- [ ] Android, Linux, SDK and web checks pass; notices and final hashes are included.
- [ ] Required CI passed on the release commit; stable/prerelease handling is verified.
- [ ] Public installation instructions match the artifacts and approved listings.

## Optional Microsoft Store follow-up

Evaluate MSIX after GitHub distribution is reliable. Check packaging, filesystem access, native
messaging registration, protocol/file handlers, migration of existing data and coexistence with
MSI installations. Store builds must use the Store's update path instead of the GitHub installer
updater. Submitting the existing MSI/EXE route still requires publisher signing. A Store listing
is an additional channel, not a signing service for GitHub releases.

## References

Provider rules and prices can change; recheck these when creating accounts and implementing CI.

- [SignPath Foundation][signpath] and [open-source conditions][signpath-terms].
- [Windows signing options][windows-signing] and [SmartScreen reputation][smartscreen].
- [Apple Developer ID distribution][apple-id] and [membership][apple-membership].
- [Apple notarization workflow][apple-notarization].
- [Chrome publisher registration][chrome-register] and [distribution rules][chrome-distribute].
- [Edge publisher registration][edge-register].
- [Mozilla signing and distribution][mozilla-signing] and [self-distribution][mozilla-self].

[signpath]: https://signpath.org/
[signpath-terms]: https://signpath.org/terms.html
[windows-signing]:
  https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options
[smartscreen]:
  https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation
[apple-id]: https://developer.apple.com/developer-id/
[apple-membership]: https://developer.apple.com/programs/enroll/
[apple-notarization]:
  https://developer.apple.com/documentation/security/customizing-the-notarization-workflow
[chrome-register]: https://developer.chrome.com/docs/webstore/register
[chrome-distribute]: https://developer.chrome.com/docs/extensions/how-to/distribute
[edge-register]:
  https://learn.microsoft.com/en-us/microsoft-edge/extensions/publish/create-dev-account
[mozilla-signing]:
  https://extensionworkshop.com/documentation/publish/signing-and-distribution-overview/
[mozilla-self]: https://extensionworkshop.com/documentation/publish/self-distribution/
