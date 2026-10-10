# Privacy policy

Effective 5 October 2026. This policy covers the Ketch apps for Android, iOS and iPadOS, macOS,
Windows, Linux and the web, the `ketch` command line and server, and the Ketch browser extension.

Ketch is an open-source download manager. It has no accounts, no ads, no analytics and no crash
reporting, and the people who make Ketch run no servers that it sends your data to. Everything
Ketch knows about you and your downloads stays on your devices, except what it has to send to do
what you ask, as described below. You can check this in the
[source code](https://github.com/linroid/Ketch).

## What stays on your device

- **Your downloads**: their links, the folders they save to, their progress and the files
  themselves.
- **Your settings**, including the names and access codes of devices you pair, and the API keys
  you enter for Discover. They are stored as plain text in Ketch's settings file, protected only
  by your device's own security.
- **Discover history** (Android and desktop): your searches and the links found, kept on the
  device. The steps it records have their web addresses shortened.
- **Logs**: a log file on the device, which leaves it only if you share it yourself, for example
  from Settings › About to report a problem. Logs leave out passwords and tokens in links.

Your device's own backup, such as Android backup or iCloud, may include Ketch's settings and task
list, under that service's terms. On Android, Discover history is excluded from backups and device
transfers.

## What Ketch sends, and to whom

Ketch only connects to the places you point it at:

- **The sites you download from.** To download a file, Ketch connects to its server, as a browser
  would. The server sees your IP address and the request, including a `User-Agent` header that
  names Ketch and its version, and any headers that came with the download, such as cookies and
  the referring page when the browser extension sends it.
- **BitTorrent peers and trackers.** For a torrent or magnet link, Ketch connects to the trackers
  it names and to other peers, and uses the distributed hash table (DHT) to find them. As with
  any BitTorrent client, the trackers and peers can see your IP address and which torrent you
  are downloading. Uploading is off by default. If you turn it on (Settings → BitTorrent, or
  `[torrent] upload`), peers also download the pieces you have from you; with *Keep seeding*,
  Ketch keeps sharing finished torrents at most until you remove them, turn it off or quit.
- **Your other devices.** When you pair devices, the device that controls another sends it your
  commands and receives its list of downloads, directly over your network. On a local network
  this traffic is not encrypted unless you set up HTTPS. Ketch looks for your devices on the local
  network with multicast DNS (Bonjour); a device you share announces itself there so your other
  devices can find it. Anyone with a device's access code can control Ketch on it.
- **The AI and search services you choose for Discover** (Android and desktop only). When you
  search with Discover, Ketch sends your request, the earlier messages of that search, the kind
  of device you search from and download to (its operating system and processor), and text from
  the web pages it reads to the AI service you set up, such as OpenAI, Anthropic, Google Gemini,
  Ollama or another service, with your own API key. If you set up a search service, such as Brave
  Search or Google, your search terms go to it. Those services handle the data under their own
  privacy policies. Discover sends nothing until you set up a service, and asks you before it
  opens a website unless you tell it not to.
- **GitHub, for updates** (desktop app and command line only). The desktop app checks GitHub for
  a new release once a day while that setting is on, and the command line when you run
  `ketch update`. GitHub sees your IP address. The Android and iOS apps from the app stores do not
  check for updates themselves.

The browser extension sends the downloads you hand to Ketch, with the cookies, referring page and
user agent that the site needs, only to the Ketch app or server you choose in its settings.

## Permissions

- **Notifications**, to show download progress and finished downloads.
- **Local network** (iOS) and **nearby Wi-Fi devices** (Android), to find your other devices
  running Ketch on the same network.
- **Storage and files**, only for the folders you choose to save downloads to.

## Children

Ketch is not directed at children under 13 and does not knowingly collect data from anyone.

## Changes

Changes to this policy are published in this file, with its history in the
[repository](https://github.com/linroid/Ketch/commits/main/PRIVACY.md).

## Contact

Ask questions or report a problem in the
[issue tracker](https://github.com/linroid/Ketch/issues), or write to the developer's contact
address shown on the app's store page.
