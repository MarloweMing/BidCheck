"""Explicit live-provider compatibility probe using fictional, short case text.

Run only when the caller intentionally requests a real configured-provider probe.
Credentials come exclusively from BIDCHECK_AGENT_* environment variables. This
script neither reads project documents nor loads any local credential store.
"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
import importlib.metadata
import json
from pathlib import Path
import re
import time
from typing import Any
from urllib.parse import urlsplit

import worker


SOURCE_ID = "probe-source-appointment-1"
FACT_ID = "probe-accepted-appointment-1"
SOURCE_TEXT = "虚构工具兼容性样本\n本次投标拟在本合同任职的项目负责人：测试甲。\n这段内容不对应任何真实项目、企业或人员。"


def object_schema(properties: dict[str, Any]) -> dict[str, Any]:
    return {"type": "object", "properties": properties, "required": list(properties), "additionalProperties": False}


def probe_start() -> dict[str, Any]:
    string = {"type": "string"}
    properties: dict[str, Any] = {key: string for key in ("kind", "subject", "field", "rawValue", "value", "unit", "basis", "scope")}
    properties["kind"] = {"type": "string", "enum": ["PERSON_ROLE"]}
    properties["scope"] = {"type": "string", "enum": ["CURRENT_PROJECT"]}
    properties["sourceIds"] = {"type": "array", "items": string, "minItems": 1, "maxItems": 1}
    properties["attributes"] = object_schema({})
    definitions = [
        {"name": "search_evidence", "description": "检索本次虚构样本中的当前项目任职原文，返回可引用的sourceId。",
         "parameters": object_schema({"query": string})},
        {"name": "submit_candidate_fact", "description": "提交PERSON_ROLE候选事实。subject为岗位，value为姓名；rawValue需引用含岗位和姓名的原文。仅服务器接受的事实有效。",
         "parameters": object_schema({"fact": object_schema(properties)})},
        {"name": "recompute_check", "description": "让服务器检查所需任职事实是否已接受；此回执仅供兼容性验证。",
         "parameters": object_schema({})},
    ]
    return {
        "type": "start", "protocol": worker.PROTOCOL, "requestId": "synthetic-provider-probe-1",
        "context": {
            "domain": "PERSONNEL_AND_SIGNING_AUTHORITY",
            "requirement": {"logic": "ALL", "rules": [{"id": "probe-role", "predicate": "PERSON_ROLE",
                             "subject": "项目负责人", "field": "任职", "operator": "EXISTS", "expected": ""}]},
            "gaps": [{"code": "PERSON_ROLE_UNPROVEN", "description": "需要查明本次虚构样本的项目负责人并提交有来源的任职事实。"}],
            "knownFacts": [], "attemptedActions": [],
        },
        "tools": definitions,
    }


class ProbeHost:
    """A minimal authoritative host with a single immutable fictional source."""
    def __init__(self):
        self.responses: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self.disclosed = False
        self.accepted = False
        self.tools: Counter[str] = Counter()
        self.events: Counter[str] = Counter()
        self.permission_acks = 0
        self.rejected_candidates = 0

    def send(self, message: dict[str, Any]) -> None:
        if message.get("type") == "event":
            event = str(message.get("event", ""))
            self.events[event] += 1
            if event == "model_started":
                self.permission_acks += 1
                self.responses.put_nowait({"type": "event_ack", "id": message["id"],
                                           "requestId": message["requestId"], "allowed": True})
            return
        if message.get("type") != "tool_call":
            raise worker.WorkerError("PROTOCOL_MESSAGE_TYPE_INVALID")
        name = message["name"]
        self.tools[name] += 1
        args = message["arguments"]
        if name == "search_evidence":
            self.disclosed = True
            result = {"status": "COMPLETED", "sources": [{"id": SOURCE_ID, "sourceId": SOURCE_ID,
                       "documentId": "synthetic-document-1", "locationType": "PDF_PAGE", "location": 1,
                       "quote": SOURCE_TEXT, "historicalContext": False, "navigationOnly": False}],
                      "partial": False}
        elif name == "submit_candidate_fact":
            fact = args.get("fact", {})
            raw = fact.get("rawValue", "")
            correct = (self.disclosed and fact.get("kind") == "PERSON_ROLE"
                       and fact.get("scope") == "CURRENT_PROJECT"
                       and fact.get("subject") == "项目负责人" and fact.get("value") == "测试甲"
                       and fact.get("sourceIds") == [SOURCE_ID]
                       and isinstance(raw, str) and raw in SOURCE_TEXT
                       and "项目负责人" in raw and "测试甲" in raw)
            if correct:
                added = [] if self.accepted else [FACT_ID]
                self.accepted = True
                result = {"status": "COMPLETED", "acceptedFactIds": added, "businessProof": False}
            else:
                self.rejected_candidates += 1
                result = {"status": "REJECTED", "acceptedFactIds": [], "rejections": [{"code": "SYNTHETIC_SOURCE_BINDING_INVALID"}]}
        elif name == "recompute_check":
            result = {"status": "COMPLETED", "businessCheckCompleted": self.accepted,
                      "remainingGapIds": [] if self.accepted else ["PERSON_ROLE_UNPROVEN"]}
        else:
            result = {"status": "FAILED", "error": "TOOL_NOT_ALLOWED"}
        self.responses.put_nowait({"type": "tool_result", "id": message["id"],
                                   "requestId": message["requestId"], "result": result})

    async def receive(self, timeout: float) -> dict[str, Any]:
        try:
            return await asyncio.wait_for(self.responses.get(), timeout)
        except TimeoutError as exc:
            raise worker.WorkerError("TOOL_RESPONSE_TIMEOUT") from exc


async def run_probe(model_override: Any = None, settings_override: worker.Settings | None = None) -> dict[str, Any]:
    started = time.monotonic()
    host = ProbeHost()
    settings: worker.Settings | None = None
    try:
        settings = settings_override or worker.Settings.from_env()
        result = await worker.investigate(probe_start(), host, settings, model_override)
    except worker.WorkerError as exc:
        result = {"status": "FAILED", "code": exc.code}
    except Exception:
        result = {"status": "FAILED", "code": "PROBE_INTERNAL_ERROR"}
    required = {"search_evidence", "submit_candidate_fact", "recompute_check"}
    passed = result.get("status") == "COMPLETED" and host.accepted and required.issubset(host.tools)
    # Only fixed enums/counts and sanitized configuration identifiers leave this
    # process. No prompts, source text, tool arguments or provider error bodies.
    raw_model = settings.model if settings else ""
    model_name = raw_model if re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", raw_model) else ""
    return {
        "scope": "synthetic-provider-sdk-compatibility-probe",
        "probePassed": passed,
        "fixture": "fictional-personnel-appointment",
        "productionDocumentReads": 0,
        "productionDocumentsSent": False,
        "dataSent": "synthetic-fixture-only" if model_override is None else "none-offline-model",
        "externalModelEnabled": model_override is None,
        "model": model_name,
        "providerHost": urlsplit(settings.base_url).hostname if settings else None,
        "sdk": "openai-agents", "sdkVersion": importlib.metadata.version("openai-agents"),
        "status": result.get("status", "FAILED"), "code": result.get("code", ""),
        "modelCalls": result.get("modelCalls", 0), "toolCalls": result.get("toolCalls", 0),
        "permissionAcknowledgements": host.permission_acks,
        "toolsExecuted": dict(host.tools), "localEvents": dict(host.events),
        "acceptedSyntheticFacts": int(host.accepted), "rejectedCandidates": host.rejected_candidates,
        "inputTokens": result.get("inputTokens", 0), "outputTokens": result.get("outputTokens", 0),
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "tracingExportEnabled": False,
        "limitation": "仅验证已配置模型的SDK、工具调用与协议兼容性，不代表真实109项检查效果。",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run an explicitly requested live SDK probe with fictional data only.")
    parser.add_argument("--output", type=Path, help="Optional local metadata JSON output path.")
    args = parser.parse_args()
    receipt = asyncio.run(run_probe())
    rendered = json.dumps(receipt, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if receipt["probePassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
