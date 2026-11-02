"""Opt-in, local-only model payload audit; never writes to stdout or external tracing."""
from __future__ import annotations

import dataclasses
import hashlib
import json
import os
from pathlib import Path
import re
from datetime import datetime, timezone
from typing import Any


class ModelAudit:
    def __init__(self, directory: str = "", secrets: tuple[str, ...] = (), max_bytes: int = 8 * 1024 * 1024):
        self.directory = Path(directory).resolve() if directory else None
        self.secrets = tuple(s for s in secrets if s)
        self.max_bytes = max(1024, min(max_bytes, 32 * 1024 * 1024))

    @classmethod
    def from_env(cls) -> "ModelAudit":
        return cls(os.environ.get("BIDCHECK_AGENT_AUDIT_DIRECTORY", ""),
                   (os.environ.get("BIDCHECK_AGENT_API_KEY", ""),))

    def sanitize(self, value: Any, key: str = "") -> Any:
        if re.sub(r"[^a-z]", "", key.lower()) in {
            "apikey", "authorization", "password", "secret", "accesstoken", "refreshtoken", "cookie", "headers"
        }:
            return "[REDACTED]"
        if isinstance(value, dict):
            return {str(k): self.sanitize(v, str(k)) for k, v in value.items()}
        if isinstance(value, (list, tuple)):
            return [self.sanitize(v) for v in value]
        if isinstance(value, str):
            for secret in self.secrets:
                value = value.replace(secret, "[REDACTED]")
            return value
        if value is None or isinstance(value, (bool, int, float)):
            return value
        if hasattr(value, "model_dump"):
            return self.sanitize(value.model_dump(mode="json"))
        if dataclasses.is_dataclass(value):
            return self.sanitize(dataclasses.asdict(value))
        # Never repr() an opaque SDK client, which can include configuration.
        return {"unserializedType": type(value).__name__}

    def record(self, request_id: str, model_index: int, direction: str, payload: Any) -> dict[str, Any]:
        if self.directory is None:
            return {"status": "DISABLED"}
        if direction not in {"input", "output", "error"} or model_index < 1:
            return {"status": "INVALID_METADATA"}
        try:
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o777 if os.name == "nt" else 0o700)
            request_key = hashlib.sha256(request_id.encode()).hexdigest()[:32]
            filename = f"{request_key}-{model_index:05d}-{direction}.json"
            body = {"version": "agent-model-audit-1", "requestId": request_id,
                    "modelIndex": model_index, "direction": direction,
                    "savedAt": datetime.now(timezone.utc).isoformat(), "payload": self.sanitize(payload),
                    "boundary": "SDK hook payload, credentials redacted; not HTTP headers or provider billing record."}
            encoded = json.dumps(body, ensure_ascii=False, allow_nan=False, indent=2).encode("utf-8")
            truncated = len(encoded) > self.max_bytes
            if truncated:
                body.pop("payload")
                body.update(payloadOmitted=True, payloadBytes=len(encoded),
                            payloadSha256=hashlib.sha256(encoded).hexdigest(), reason="AUDIT_PAYLOAD_TOO_LARGE")
                encoded = json.dumps(body, ensure_ascii=False, indent=2).encode("utf-8")
            # Exclusive creation also refuses a pre-existing symlink and preserves prior evidence.
            fd = os.open(self.directory / filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(encoded)
            return {"status": "PAYLOAD_OMITTED" if truncated else "SAVED", "file": filename,
                    "sha256": hashlib.sha256(encoded).hexdigest(), "bytes": len(encoded)}
        except Exception as exc:
            return {"status": "WRITE_FAILED", "errorType": type(exc).__name__}
