"""Behavior checks for the release evidence gate; no external peers are needed."""

from pathlib import Path
import tempfile
import unittest

from verify_conformance import verify


class ConformanceGateTest(unittest.TestCase):
  def setUp(self):
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    self.reports = Path(self.directory.name)
    self.manifest = {"schemaVersion": 1, "scenarios": [
      {"id": "download", "class": "PeerTest", "method": "download"}
    ]}

  def report(self, content, name="TEST-peer.xml"):
    (self.reports / name).write_text(f"<testsuite>{content}</testsuite>")

  def test_missing_report_fails(self):
    with self.assertRaisesRegex(ValueError, "did not execute"):
      verify(self.manifest, self.reports)

  def test_skipped_required_case_fails(self):
    self.report('<testcase classname="PeerTest" name="download[jvm]"><skipped/></testcase>')
    with self.assertRaisesRegex(ValueError, "did not execute"):
      verify(self.manifest, self.reports)

  def test_failure_elsewhere_cannot_produce_green_evidence(self):
    self.report('<testcase classname="PeerTest" name="download[jvm]"/>'
                '<testcase classname="Other" name="other"><failure/></testcase>')
    with self.assertRaisesRegex(ValueError, "Failed tests"):
      verify(self.manifest, self.reports)

  def test_duplicate_reports_fail(self):
    case = '<testcase classname="PeerTest" name="download[jvm]"/>'
    self.report(case)
    self.report(case, "TEST-duplicate.xml")
    with self.assertRaisesRegex(ValueError, "Duplicate reported"):
      verify(self.manifest, self.reports)

  def test_one_case_cannot_satisfy_two_scenarios(self):
    self.report('<testcase classname="PeerTest" name="download[jvm]"/>')
    self.manifest["scenarios"].append(
      {"id": "different", "class": "PeerTest", "method": "download"})
    with self.assertRaisesRegex(ValueError, "Duplicate scenario"):
      verify(self.manifest, self.reports)

  def test_executed_target_case_records_duration_and_report_digest(self):
    self.report('<testcase classname="PeerTest" name="download[jvm]" time="1.25"/>')
    evidence = verify(self.manifest, self.reports)
    self.assertEqual([{"id": "download", "seconds": 1.25}], evidence["scenarios"])
    self.assertEqual(64, len(evidence["reports"][0]["sha256"]))


if __name__ == "__main__":
  unittest.main()
