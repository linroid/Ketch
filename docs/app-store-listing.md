# App store listings

Status: assets and answers prepared on 2026-10-05 for a first Google Play and App Store release.
Nothing has been submitted. The [distribution plan](plans/v1-distribution.md) treats both stores
as follow-up work after v1.0.0; this page is what that work starts from.

## What is where

| What | Where |
| --- | --- |
| Play title, short and full description, release notes | [`app/android/fastlane/metadata/android/en-US/`](../app/android/fastlane/metadata/android/en-US/) |
| Play icon, feature graphic, phone and 10" tablet screenshots | `…/en-US/images/` (rendered) |
| App Store name, subtitle, description, keywords, promotional text, URLs | [`app/ios/fastlane/metadata/en-US/`](../app/ios/fastlane/metadata/en-US/) |
| App Store categories, copyright, review notes | [`app/ios/fastlane/metadata/`](../app/ios/fastlane/metadata/) |
| iPhone 6.9" and iPad 13" screenshots | `app/ios/fastlane/screenshots/en-US/` (rendered) |
| Privacy policy, for both stores and the extension stores | [`PRIVACY.md`](../PRIVACY.md) |
| iOS privacy manifest (draft, not yet in the Xcode target) | [`app/ios/PrivacyInfo.xcprivacy`](../app/ios/PrivacyInfo.xcprivacy) |

The folders follow fastlane's layout, so `fastlane supply` (run in `app/android`) and
`fastlane deliver` (run in `app/ios`) can upload them, and F-Droid and IzzyOnDroid read the
Android one as it is. Without fastlane, paste the text files into the consoles.

## Screenshots

```bash
./art/render-store-assets.sh
```

The script runs the `StoreScreenshots` snapshot scenario (about 8 minutes), copies the images
into the folders above, with the Play icon made from the iOS one, and checks that every image
has the size its store slot takes and no alpha channel, which App Store Connect refuses (the
Play icon needs one). The scenario renders the real
app headlessly, as the [README showcase](development/testing.md#ui-snapshots) does, over
brand-free sample data: the phone or tablet downloads its own files, with a Mac called Studio and
a home server paired. Captions are English constants in `StoreScreenshots.kt`, and
`STORE_FRAME` there picks the frame: a graphite phone with a border wider than a real phone's,
and graphite tablets. Its other styles are graphite with a real or a still wider border, silver,
clay, frameless, a phone running off the bottom edge, and the bare screen without a caption.

| Slot | Size (px) | Shots |
| --- | --- | --- |
| App Store, iPhone 6.9" (required) | 1320 × 2868 | Connections, downloads, devices, add, speed, all devices (dark) |
| App Store, iPad 13" (required: the app runs on iPad) | 2752 × 2064 | Connections, all devices, speed, add (dark) |
| Play, phone | 1440 × 2560 (9:16) | The iPhone set plus Discover |
| Play, 10" tablet | 2560 × 1440 (16:9) | The iPad set plus Discover |
| Play, feature graphic | 1024 × 500 | Icon, name and the downloads list on a phone |
| Play, icon | 512 × 512 | `icon-square.svg`; Play applies its own mask |

Smaller iPhones and iPads are scaled from these, so no other sizes are needed. Play's 7" tablet
slot is optional; the 10" images can go there too. The App Store set leaves out Discover, which
the iOS app does not offer, and torrents (see [BitTorrent](#bittorrent-on-the-app-store)).

## Google Play

### Before the first release

- [ ] **Upload an app bundle.** Play takes `.aab` files only, and the release workflow builds
  an APK (`assembleRelease`). Add `:app:android:bundleRelease` with the same version code.
- [ ] **Keep one signing key across channels.** Play App Signing re-signs what Play delivers. If
  Play generates its own key, the Play build and the GitHub APK can never update each other.
  Upload the existing release key as the app signing key (Play Console's "Use existing app
  signing key" option), and use a separate upload key.
- [ ] **Closed test first, on a new personal account.** Personal developer accounts created
  after 13 November 2023 need a closed test with at least 12 testers opted in for 14 days in a
  row before they can apply for production.
- [ ] The package name `com.linroid.ketch.app` is permanent once uploaded. Target SDK 36 meets
  the current requirement.

### App content answers

- **Privacy policy**: `https://github.com/linroid/Ketch/blob/main/PRIVACY.md`.
- **Ads**: no. **App access**: all functionality is available without special access; no
  account exists.
- **Target audience**: 18 and over (any set without children under 13 avoids the Families
  policy). The app is not designed for children.
- **Content rating** (IARC, category utility or productivity): no violence, sexuality, language,
  drugs, gambling or purchases; no user-to-user communication or user-generated content; no
  location sharing. BitTorrent exchanges file data with peers but not messages; answer the
  sharing questions by IARC's definitions.
- **Data safety**: Ketch's developer receives nothing, and the app sends data only where the
  user points it (see [PRIVACY.md](../PRIVACY.md)). Downloads are like a browser's requests, and
  are not declared. Discover is the one judgment call: it sends the request and page text to the
  AI service the user sets up with their own key. "No data collected" is defensible; the cautious
  answer is *App activity › Other user-generated content*: collected, optional, for app
  functionality, not shared (a transfer the user starts, to a service they chose, is exempt from
  "sharing"), not encrypted in transit in every case (a local Ollama runs over HTTP), with no
  deletion request since nothing is kept by the developer.
- **Government, financial, health, news apps**: no.

### Declarations and policy risks

- **Foreground service (`dataSync`)**: Play asks what the service does and for a short video of
  it. Suggested text: "Downloads the user starts keep transferring while the app is in the
  background; a notification shows their progress with Pause. Deferring them would stall or
  fail the user's downloads." Record the video on a device: add a download, leave the app, show
  the notification. Android 15 limits `dataSync` services to six hours a day; long downloads may
  need the user-initiated data transfer job API later.
- **Nearby Wi-Fi devices**: Ketch uses it only to find devices with multicast DNS. Consider
  declaring it with `android:usesPermissionFlags="neverForLocation"` (and checking discovery
  still works) so it is never read as a location permission.
- **AI-generated content**: Google's policy asks apps that generate content with AI to let
  people report offensive output in the app. Discover's summaries and result descriptions come
  from a model; a Report action on a Discover result, even one that opens the issue tracker,
  would cover it.
- **Torrents** are allowed on Play. The listing describes the protocol, not content, and ends
  with a line about only downloading what you have the right to.

## App Store

### Before the first submission

- [ ] **Name.** App names are unique across the store. If "Ketch: Download Manager" is taken,
  try "Ketch - Downloads" or "Ketch Downloader", and keep the subtitle.
- [ ] **Team and version.** The Xcode target signs Debug with team `MJ7RE83LT9` and Release
  with `WHHN7J96UQ`, and sets `MARKETING_VERSION = 1.0` while `Config.xcconfig` says `0.0.1`.
  Release must use the team that owns the App Store Connect record, and the version must match it.
- [ ] **Privacy manifest.** Add `app/ios/PrivacyInfo.xcprivacy` to the Ketch target (Copy Bundle
  Resources), then compare it with Xcode's privacy report of an archive. Uploads without one are
  refused (ITMS-91053).
- [ ] **Export compliance.** Ketch uses only the system's TLS and hashes, which are exempt.
  Adding `ITSAppUsesNonExemptEncryption` = `NO` to `Ketch-Info.plist` saves answering this on
  every upload; confirm before setting it.
- [ ] **Screenshots** are ready; re-render them if the app changes.

### Answers

- **Category**: Utilities, then Productivity. **Price**: free.
- **App Privacy**: Data Not Collected. Nothing reaches the developer; downloads go to the
  servers the user chose, pairing stays between the user's devices, and Discover is not in the
  iOS app.
- **Age rating**: no to every content question, including unrestricted web access (Ketch has no
  browser), user-generated content, messaging and advertising.
- **Review notes**: in `review_information/notes.txt`. No demo account is needed.
- **Support URL**: the issue tracker; **marketing URL**: the repository.

### BitTorrent on the App Store

App Review has historically rejected BitTorrent clients (guidelines 5.2.3 and 5.2), and the iOS
app ships the torrent engine, opens `.torrent` files and `magnet:` links (`Ketch-Info.plist`) and
has a BitTorrent settings page. Decide before submitting:

1. Build the App Store app without torrents: leave out `TorrentDownloadSource`, the `.torrent`
   document type, the `magnet` URL scheme and the BitTorrent settings page. Recommended.
2. Submit as is and expect a likely rejection.
3. Offer torrents only through alternative distribution in the EU.

The App Store listing and the iPhone screenshots already leave torrents out; the iPad speed
screenshot shows "BitTorrent" in the settings list, so re-render it after option 1.

## More languages

The app speaks ten languages, the listings only English. For each language, add a folder with
translated texts (Play: `zh-CN`, `zh-TW`, `ja-JP`, `ko-KR`, `es-ES`, `pt-BR`, `de-DE`,
`fr-FR`, `ru-RU`; App Store: `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es-ES`, `pt-BR`, `de-DE`,
`fr-FR`, `ru`), with keywords chosen for that language, following the
[glossary](development/translation-glossary.md). Localized screenshots also need translated
captions in `StoreScreenshots.kt`; the app's own text follows `-PsnapshotLocale=<tag>`.

## Optional extras

- A promo video: a YouTube link in `video.txt` for Play, and 15 to 30 second app previews for
  the App Store, recorded on a device or simulator.
- Chromebook and 7" tablet screenshots for Play.
