import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("bidcheck_acceptance", ROOT / "scripts/bidcheck_acceptance.py")
ACCEPTANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ACCEPTANCE)


class JavaAcceptanceTest(unittest.TestCase):
    def receipt(self, folder, failed=False):
        suite = ET.Element("testsuite", name="sample.RegressionTest", tests="2",
                           failures=str(int(failed)), errors="0", skipped="1")
        first = ET.SubElement(suite, "testcase", classname="sample.RegressionTest", name="runs")
        if failed:
            ET.SubElement(first, "failure")
        skipped = ET.SubElement(suite, "testcase", classname="sample.RegressionTest", name="skipped")
        ET.SubElement(skipped, "skipped")
        ET.ElementTree(suite).write(folder / "TEST-sample.RegressionTest.xml", encoding="utf-8")
        log = folder / "maven.log"
        log.write_text("[INFO] Running sample.RegressionTest\n"
                       f"[ERROR] Tests run: 2, Failures: {int(failed)}, Errors: 0, Skipped: 1\n"
                       "[INFO] BUILD FAILURE\n", encoding="utf-8")
        return log

    def test_counts_preserve_failed_and_skipped_cases(self):
        with tempfile.TemporaryDirectory() as path:
            folder = Path(path)
            observed = ACCEPTANCE.java_test_receipt(self.receipt(folder, True), folder)
            self.assertEqual([2, 1, 0, 1], observed["counts"])
            self.assertEqual(set(), observed["passed"])
            observed = ACCEPTANCE.java_test_receipt(self.receipt(folder), folder)
            self.assertEqual({"sample.RegressionTest#runs"}, observed["passed"])

    def test_stale_summary_or_missing_completion_is_rejected(self):
        with tempfile.TemporaryDirectory() as path:
            folder = Path(path)
            log = self.receipt(folder)
            log.write_text(log.read_text().replace("Tests run: 2", "Tests run: 3"))
            with self.assertRaises(ValueError):
                ACCEPTANCE.java_test_receipt(log, folder)
            log = self.receipt(folder)
            log.write_text(log.read_text().replace("[INFO] BUILD FAILURE", ""))
            with self.assertRaises(ValueError):
                ACCEPTANCE.java_test_receipt(log, folder)

    def test_xml_counters_cannot_replace_missing_cases(self):
        with tempfile.TemporaryDirectory() as path:
            folder = Path(path)
            log = self.receipt(folder)
            xml = folder / "TEST-sample.RegressionTest.xml"
            xml.write_text(xml.read_text().replace('tests="2"', 'tests="3"'))
            with self.assertRaises(ValueError):
                ACCEPTANCE.java_test_receipt(log, folder)


if __name__ == "__main__":
    unittest.main()
