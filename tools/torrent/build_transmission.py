"""Build the pinned, test-only Transmission daemon in an isolated output directory."""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--output", type=Path, required=True)
  args = parser.parse_args()
  root = Path(__file__).resolve().parents[2]
  pins = dict(
    line.split("=", 1)
    for line in (root / "test-fixtures/torrent/clients.properties").read_text().splitlines()
    if line and not line.startswith("#")
  )
  output = args.output.resolve()
  output.mkdir(parents=True, exist_ok=True)
  archive = output / "source.tar.xz"
  if not archive.exists():
    temporary = output / "source.tar.xz.part"
    with urllib.request.urlopen(pins["transmission.url"], timeout=60) as response:
      with temporary.open("wb") as destination:
        while chunk := response.read(1024 * 1024):
          destination.write(chunk)
    temporary.replace(archive)
  with archive.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
  if digest != pins["transmission.sha256"]:
    raise SystemExit(f"Transmission archive digest mismatch: {digest}")
  source = output / f"transmission-{pins['transmission.version']}"
  if not source.exists():
    with tarfile.open(archive) as package:
      package.extractall(output, filter="data")
  build = output / "build"
  subprocess.run([
    "cmake", "-S", str(source), "-B", str(build), "-DCMAKE_BUILD_TYPE=Release",
    "-DENABLE_DAEMON=ON", "-DENABLE_GTK=OFF", "-DENABLE_QT=OFF", "-DENABLE_MAC=OFF",
    "-DENABLE_TESTS=OFF", "-DENABLE_UTILS=OFF", "-DENABLE_CLI=OFF", "-DENABLE_NLS=OFF",
    "-DINSTALL_WEB=OFF", "-DINSTALL_DOC=OFF", "-DRUN_CLANG_TIDY=OFF",
    # Use the implementations shipped in the authenticated release archive.
    *[f"-DUSE_SYSTEM_{name}=OFF" for name in (
      "EVENT2", "DEFLATE", "DHT", "MINIUPNPC", "NATPMP", "UTP", "B64", "PSL"
    )],
  ], check=True)
  subprocess.run([
    "cmake", "--build", str(build), "--target", "transmission-daemon", "--parallel",
    str(min(os.cpu_count() or 2, 4)),
  ], check=True)
  binary = build / "daemon/transmission-daemon"
  result = subprocess.run([str(binary), "--version"], check=True, capture_output=True,
                          text=True, timeout=10)
  version = (result.stdout + result.stderr).strip()
  if not version.startswith(f"transmission-daemon {pins['transmission.version']} "):
    raise SystemExit(f"Unexpected Transmission version: {version}")
  print(binary)


if __name__ == "__main__":
  main()
