"""Require successful, executed JUnit scenarios and write revision-bound conformance evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def verify(manifest, reports):
  if manifest.get("schemaVersion") != 1 or not manifest.get("scenarios"):
    raise ValueError("Unknown or empty conformance manifest")
  cases = {}
  report_evidence = []
  clients = set()
  for report in sorted(reports.glob("TEST-*.xml")):
    data = report.read_bytes()
    document = ET.fromstring(data)
    report_evidence.append({"file": report.name, "sha256": hashlib.sha256(data).hexdigest()})
    for case in document.iter("testcase"):
      # Kotlin's JUnit runner appends the target name to the method.
      method = re.sub(r"\[[^\]]+\]$", "", case.get("name", ""))
      key = (case.get("classname"), method)
      if key in cases:
        raise ValueError(f"Duplicate reported testcase: {key}")
      cases[key] = case
    for output in document.iter("system-out"):
      clients.update(line.removeprefix("CONFORMANCE_CLIENT ")
                     for line in (output.text or "").splitlines()
                     if line.startswith("CONFORMANCE_CLIENT "))
    if (next(document.iter("failure"), None) is not None or
        next(document.iter("error"), None) is not None):
      raise ValueError(f"Failed tests in {report.name}")
  executed = []
  identities = set()
  test_keys = set()
  for scenario in manifest["scenarios"]:
    identity = scenario["id"]
    key = (scenario["class"], scenario["method"])
    if identity in identities or key in test_keys:
      raise ValueError(f"Duplicate scenario identity or test: {identity}")
    identities.add(identity)
    test_keys.add(key)
    case = cases.get(key)
    if case is None or case.find("skipped") is not None:
      raise ValueError(f"Required scenario did not execute: {identity}")
    executed.append({"id": identity, "seconds": float(case.get("time", "0"))})
  return {"scenarios": executed, "reports": report_evidence, "clients": sorted(clients)}


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  root = Path(__file__).resolve().parents[2]
  parser.add_argument("--manifest", type=Path,
                      default=root / "test-fixtures/torrent/scenarios.json")
  parser.add_argument("--reports", type=Path, required=True)
  parser.add_argument("--revision", required=True)
  parser.add_argument("--output", type=Path, required=True)
  args = parser.parse_args()
  # A failed invocation must not leave an earlier green evidence file available for upload.
  args.output.unlink(missing_ok=True)
  if not re.fullmatch(r"[0-9a-f]{40,64}", args.revision):
    raise ValueError("Evidence requires a full Git revision")
  raw_manifest = args.manifest.read_bytes()
  evidence = verify(json.loads(raw_manifest), args.reports)
  evidence.update({
    "schemaVersion": 1, "revision": args.revision,
    "recordedAt": datetime.now(timezone.utc).isoformat(),
    "manifestSha256": hashlib.sha256(raw_manifest).hexdigest(),
  })
  args.output.parent.mkdir(parents=True, exist_ok=True)
  args.output.write_text(json.dumps(evidence, indent=2) + "\n")
  print(f"Verified {len(evidence['scenarios'])} conformance scenarios at {args.revision}")


if __name__ == "__main__":
  main()
