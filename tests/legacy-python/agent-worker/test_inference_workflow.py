"""Regression tests for cited inference scheduling and revision-scoped rejection reuse."""
import copy
import json
import os
import pathlib
import unittest
import uuid
from unittest.mock import patch
import worker
from test_worker import MemoryHost, ScriptedModel, SETTINGS, call, start


def request(typed=False):
    value = start("submit_candidate_fact")
    value["tools"][0]["parameters"] = {"type": "object", "properties": {
        "targetObligationId": {"type": "string"}, "fact": {"type": "object"}},
        "required": ["fact"], "additionalProperties": False}
    if typed:
        value["context"].update(workflowPolicy=worker.TARGET_WORKFLOW_POLICY,
                                targetObligationIds=["A"], targetEvidenceRevision="r1")
    return value


def candidate(raw="原文一", source="s1"):
    return {"targetObligationId": "A", "fact": {"kind": "PERSON_ROLE", "subject": "项目负责人",
        "field": "role", "rawValue": raw, "value": "张国超", "sourceIds": [source]}}


class InferenceWorkflowTests(unittest.IsolatedAsyncioTestCase):
    async def test_semantic_rejection_replays_reason_for_changed_wording_but_new_source_can_retry(self):
        rejection = {"status": "REJECTED", "error": "FACT_OUTSIDE_TARGET",
                     "targetEvidenceRevision": "first-host-revision",
                     "rejections": [{"code": "CURRENT_ROLE_SOURCE_UNPROVEN"}], "correctionHints": [{"action": "READ_APPOINTMENT"}]}
        host = MemoryHost([rejection, {"status": "COMPLETED", "acceptedFactIds": ["new"]}])
        req = request(); state = worker.Investigation(req, host, 2)
        schema = req["tools"][0]["parameters"]
        await state.invoke("submit_candidate_fact", schema, json.dumps(candidate()))
        reply = json.loads(await state.invoke("submit_candidate_fact", schema, json.dumps(candidate("换一种写法"))))
        self.assertTrue(reply["cached"]); self.assertEqual(reply["priorRejections"], rejection["rejections"])
        self.assertEqual(len(host.tool_calls()), 1)
        await state.invoke("submit_candidate_fact", schema, json.dumps(candidate("原文一", "s2")))
        self.assertEqual(len(host.tool_calls()), 2)

    async def test_changed_evidence_revision_reopens_semantic_rejection(self):
        host = MemoryHost([{"status": "REJECTED", "error": "FACT_OUTSIDE_TARGET", "targetEvidenceRevision": "v1"},
                           {"status": "COMPLETED", "acceptedFactIds": ["now-grounded"]}])
        req = request(); state = worker.Investigation(req, host, 2); schema = req["tools"][0]["parameters"]
        await state.invoke("submit_candidate_fact", schema, json.dumps(candidate()))
        state.host_revision = "v2"
        await state.invoke("submit_candidate_fact", schema, json.dumps(candidate("重新读取的新证据")))
        self.assertEqual(len(host.tool_calls()), 2)

    async def test_quote_repair_is_not_suppressed(self):
        host = MemoryHost([{"status": "REJECTED", "rejections": [{"code": "RAW_VALUE_UNGROUNDED"}]},
                           {"status": "COMPLETED", "acceptedFactIds": ["fixed"]}])
        req = request(); state = worker.Investigation(req, host, 2); schema = req["tools"][0]["parameters"]
        await state.invoke("submit_candidate_fact", schema, json.dumps(candidate()))
        await state.invoke("submit_candidate_fact", schema, json.dumps(candidate("真实原文")))
        self.assertEqual(len(host.tool_calls()), 2)

    async def test_pending_confirmation_stops_paid_turns_without_claiming_business_completion(self):
        host = MemoryHost([{"status": "NEEDS_CONFIRMATION", "confirmationId": "claim-1", "acceptedFactIds": []}])
        model = ScriptedModel([[call("submit_candidate_fact", candidate())]])
        result = await worker.investigate(request(True), host, SETTINGS, model)
        self.assertEqual(result["modelCalls"], 1); self.assertEqual(result["toolCalls"], 1)
        self.assertEqual(result["pendingConfirmationTargetObligationIds"], ["A"])
        self.assertFalse(result["businessCheckCompleted"]); self.assertFalse(result["targetCheckCompleted"])
        self.assertNotEqual(result["status"], "FAILED")

    async def test_real_sdk_hooks_record_inputs_outputs_only_when_enabled(self):
        folder = pathlib.Path(__file__).resolve().parents[1] / "artifacts" / "agent-plan-implementation-20261102" / ("audit-test-" + uuid.uuid4().hex)
        folder.mkdir(parents=True)
        try:
            with patch.dict(os.environ, {"BIDCHECK_AGENT_AUDIT_DIRECTORY": str(folder)}):
                host = MemoryHost([{"status": "NEEDS_CONFIRMATION", "confirmationId": "claim-1", "acceptedFactIds": []}])
                await worker.investigate(request(True), host, SETTINGS, ScriptedModel([[call("submit_candidate_fact", candidate())]]))
            events = [m for m in host.messages if m.get("event") in {"model_request_authorized", "model_finished"}]
            self.assertEqual(len(events), 2)
            self.assertTrue(all(m.get("modelAudit", {}).get("status") == "SAVED" for m in events), events)
            self.assertTrue(list(folder.rglob("*.json")))
        finally:
            for file in sorted(folder.rglob("*"), reverse=True):
                if file.is_file(): file.unlink()
                elif file.is_dir(): file.rmdir()
            folder.rmdir()


if __name__ == "__main__":
    unittest.main()
