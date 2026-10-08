#!/usr/bin/env python3
"""Build the download section; GitHub appends its generated changelog in release.yml."""

import argparse
from pathlib import Path
from urllib.parse import quote


def download_notes(artifacts, server_url, repository, tag):
  version = tag.removeprefix("v")
  release_url = f"{server_url}/{repository}/releases/download/{quote(tag, safe='')}"
  source_url = f"{server_url}/{repository}/tree/{quote(tag, safe='')}"
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
    ("Your browser", "Download"),
    ("---", "---"),
    ("Chrome, Edge, Brave and other Chromium browsers",
     "[Chrome Web Store](https://chromewebstore.google.com/detail/flnjeochbgpaipiofdjmoijaeooemhka)"),
    ("Firefox", download("Unsigned extension ZIP", "extension-{version}-firefox.zip")),
    ("Safari (requires Xcode packaging and signing)",
     download("Extension source ZIP", "extension-{version}-safari.zip")),
  ])
  web = download("Download the web bundle", "web-{version}.zip")
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

[Set up your extension]({source_url}/app/browser-extension/README.md#installing).

</details>

<details>
<summary><strong>Command line, servers &amp; self-hosting</strong></summary>

Native binaries with the `ketch server` command and web interface included.
No Java installation needed.

{commands}

**Hosting the web interface separately?** {web} (ZIP).
Connect it to a running Ketch server to manage your downloads.

</details>

<details>
<summary><strong>Build for iPhone or iPad</strong></summary>

For iOS 18+, [build from source with Xcode]({source_url}/app/ios).

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
  args = parser.parse_args()
  print(download_notes(args.artifacts, args.server_url, args.repository, args.tag))
