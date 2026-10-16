#!/usr/bin/env python3
"""Build the download section; GitHub appends its generated changelog in release.yml."""

import argparse
from pathlib import Path
from urllib.parse import quote


def download_notes(artifacts, server_url, repository, tag, docker_image, extension_tag=""):
  version = tag.removeprefix("v")
  release_url = f"{server_url}/{repository}/releases/download/{quote(tag, safe='')}"
  source_url = f"{server_url}/{repository}/tree/{quote(tag, safe='')}"
  setup_url = f"{source_url}/app/browser-extension/README.md#installing"
  links = []

  def download(label, asset):
    filename = f"ketch-{asset.format(version=version)}"
    if not (artifacts / filename).is_file():
      raise ValueError(f"Missing release download: {filename}")
    reference = f"download-{len(links) + 1}"
    links.append(f"[{reference}]: {release_url}/{quote(filename, safe='')}")
    return f"[{label}][{reference}]"

  def desktop(os, extension):
    builds = (
      (("Apple silicon", "arm64"), ("Intel", "x64")) if os == "macos"
      else (("Intel / AMD (x64)", "x64"), ("ARM64", "arm64"))
    )
    return " · ".join(
      download(label, f"desktop-{{version}}-{os}-{arch}{extension}")
      for label, arch in builds
    )

  def cli(os, arch):
    extension = "zip" if os == "windows" else "tar.gz"
    return download(extension.upper(), f"cli-{{version}}-{os}-{arch}.{extension}")

  # A link to the release itself: GitHub's release search is full text, so a query for the
  # extension's tags lists the app releases that mention them first.
  def extension_release():
    if not extension_tag:
      return f"[Set up your extension]({setup_url})"
    label = f"Browser extension {extension_tag.removeprefix('extension-v')}"
    return f"[{label}]({server_url}/{repository}/releases/tag/{quote(extension_tag, safe='')})"

  def table(rows):
    return "\n".join("| " + " | ".join(row) + " |" for row in rows)

  apps = table([
    ("Your device", "Download", "Package"),
    ("---", "---", "---"),
    ("**macOS**", desktop("macos", ".dmg"), "DMG"),
    ("**Windows**", desktop("windows", ".msi"), "MSI installer"),
    ("Windows · portable", desktop("windows", "-portable.zip"), "ZIP · no installation"),
    ("**Linux** · Debian / Ubuntu", desktop("linux", ".deb"), "DEB"),
    ("**Android** · 8.0+", download("Universal", "android-{version}.apk"), "APK"),
    ("**iPhone / iPad** · iOS 15+", download("Unsigned", "ios-{version}.ipa"), "IPA · sideload"),
  ])
  commands = table([
    ("Platform", "Processor", "Download"),
    ("---", "---", "---"),
    ("macOS", "Apple silicon (M series)", cli("macos", "arm64")),
    ("macOS", "Intel", cli("macos", "x64")),
    ("Windows", "Intel / AMD (x64)", cli("windows", "x64")),
    ("Linux", "Intel / AMD (x64)", cli("linux", "x64")),
    ("Linux", "ARM64", cli("linux", "arm64")),
  ])
  extensions = table([
    ("Your browser", "Install"),
    ("---", "---"),
    ("Chrome, Edge, Brave and other Chromium browsers",
     "[Chrome Web Store](https://chromewebstore.google.com/detail/flnjeochbgpaipiofdjmoijaeooemhka)"),
    ("Firefox and Safari", extension_release()),
  ])
  web = download("Download the web bundle", "web-{version}.zip")
  docker = f"`docker pull {docker_image}:{version}`"
  references = "\n".join(links)
  return f"""## Get Ketch

Pick your device below. Desktop apps include everything they need to run.

{apps}

Apple silicon covers M-series Macs. On Windows, choose Intel / AMD for most PCs,
or ARM64 for devices with processors such as Snapdragon.

**Already using Ketch?** Check Settings → About for desktop updates, or run `ketch update`
from the command line.

<details>
<summary><strong>Add Ketch to your browser</strong></summary>

Send links and downloads straight to Ketch from your browser.

{extensions}

[Set up your extension]({setup_url}).

</details>

<details>
<summary><strong>Command line, servers &amp; self-hosting</strong></summary>

Native binaries with the `ketch server` command and web interface included.
No Java installation needed.

{commands}

**Docker** (x64 and ARM64), for a NAS or home server: {docker}.
[Run it with Docker, on TrueNAS or fnOS]({source_url}/docs/docker.md).

**Hosting the web interface separately?** {web} (ZIP).
Connect it to a running Ketch server to manage your downloads.

</details>

<details>
<summary><strong>Installing on iPhone or iPad</strong></summary>

The IPA is unsigned: install it with a sideloading tool such as AltStore, SideStore or
Sideloadly, which signs it with your own Apple ID, or
[build from source with Xcode]({source_url}/app/ios).

</details>

{references}

---
"""


if __name__ == "__main__":
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--artifacts", type=Path, required=True)
  parser.add_argument("--server-url", default="https://github.com")
  parser.add_argument("--repository", required=True)
  parser.add_argument("--tag", required=True)
  parser.add_argument("--docker-image", required=True)
  parser.add_argument("--extension-tag", default="", help="the latest extension release's tag")
  args = parser.parse_args()
  print(download_notes(
    args.artifacts, args.server_url, args.repository, args.tag, args.docker_image,
    args.extension_tag,
  ))
