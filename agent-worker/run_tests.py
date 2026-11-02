"""Run offline worker tests and write a compact local verification receipt."""
from __future__ import annotations

import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import time
import unittest


if __name__ == "__main__":
    root = Path(__file__).resolve().parent
    started = time.monotonic()
    suite = unittest.defaultTestLoader.discover(str(root), pattern="test_*.py")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    receipt = {
        "scope": "offline-real-agents-sdk-and-chat-completions-wire-tests",
        "sdk": "openai-agents",
        "sdkVersion": importlib.metadata.version("openai-agents"),
        "openaiClientVersion": importlib.metadata.version("openai"),
        "pythonVersion": platform.python_version(),
        "tests": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "passed": result.wasSuccessful(),
        "elapsedSeconds": round(time.monotonic() - started, 3),
        "externalModelRequests": 0,
        "documentsSentExternally": False,
        "sourceHashes": {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
                         for name in ("worker.py", "probe.py", "test_worker.py", "test_context_projection.py", "requirements.lock")},
    }
    (root / "test-results.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    raise SystemExit(0 if result.wasSuccessful() else 1)
