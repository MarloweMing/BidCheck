import json
from pathlib import Path
import tempfile
import unittest
import uuid
import shutil
from contextlib import contextmanager
from model_audit import ModelAudit

@contextmanager
def audit_directory():
    root = Path(__file__).resolve().parent.parent / "artifacts" / "agent-plan-implementation-20261102" / "audit-tests"
    path = root / str(uuid.uuid4())
    path.mkdir(parents=True)
    try:
        yield str(path)
    finally:
        assert path.resolve().parent == root.resolve()
        shutil.rmtree(path)


class ModelAuditTest(unittest.TestCase):
    def test_disabled_without_directory(self):
        self.assertEqual("DISABLED", ModelAudit().record("r", 1, "input", {})["status"])

    def test_roundtrip_redacts_credentials_keeps_evidence_and_refuses_overwrite(self):
        with audit_directory() as temp:
            audit = ModelAudit(temp, ("private-key-example",))
            result = audit.record("../../unsafe", 1, "input", {
                "authorization": "Bearer private-key-example", "quote": "第22页：张国超 项目负责人",
                "messages": [{"content": "literal private-key-example"}]})
            self.assertEqual("SAVED", result["status"])
            p = Path(temp) / result["file"]
            payload = json.loads(p.read_text(encoding="utf-8"))["payload"]
            self.assertEqual("第22页：张国超 项目负责人", payload["quote"])
            self.assertNotIn("private-key-example", p.read_text(encoding="utf-8"))
            self.assertEqual("WRITE_FAILED", audit.record("../../unsafe", 1, "input", {})["status"])

    def test_large_payload_is_explicitly_omitted(self):
        with audit_directory() as temp:
            result = ModelAudit(temp, max_bytes=1024).record("r", 2, "output", {"text": "x" * 2000})
            self.assertEqual("PAYLOAD_OMITTED", result["status"])
            saved = json.loads((Path(temp)/result["file"]).read_text(encoding="utf-8"))
            self.assertTrue(saved["payloadOmitted"])
            self.assertNotIn("payload", saved)


if __name__ == "__main__":
    unittest.main()
