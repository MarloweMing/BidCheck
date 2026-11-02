"""Provider-view regressions. Host receipts, source rights and full audit stay immutable."""
import copy
import json
import unittest
from types import SimpleNamespace

import worker
import test_worker as fixtures


def function(identifier):
    return {"type": "function_call", "call_id": identifier, "name": "read_section", "arguments": "{}"}


def output(identifier, result=None):
    return {"type": "function_call_output", "call_id": identifier,
            "output": worker.canonical({"trustedTool": "read_section", "result": result or {"sources": [{"id": identifier, "quote": "body-"+identifier}]}})}


def prose(value):
    return {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": value}]}


class ContextProjectionTests(unittest.TestCase):
    def state(self):
        request = fixtures.TargetWorkflowTests().request("read_section", targets=("A", "B"))
        return worker.Investigation(request, fixtures.MemoryHost(), fixtures.SETTINGS.tool_timeout_seconds)

    def receipt(self):
        return {"kind": "HOST_RULE_RECOMPUTE", "status": "COMPLETED", "targetEvidenceRevision": "global-v1",
            "businessCheckCompleted": False, "targetCheckCompleted": False,
            "remainingTargetObligationIds": ["A", "B"], "remainingGapIds": ["A_GAP", "B_GAP"],
            "gaps": [{"code": "A_GAP", "description": "read A certificate"}, {"code": "B_GAP", "description": "read B certificate"}],
            "obligations": [{"id": "root", "status": "UNDETERMINED", "proof": {"operator": "ANY", "children": [
                {"id": "A", "status": "UNDETERMINED", "gaps": [{"code": "A_GAP", "description": "read A certificate"}],
                 "proof": {"field": "CERTIFICATE_NUMBER", "value": "ACTIVE-A-DETAIL", "sourceIds": ["s-a"]},
                 "evidences": [{"documentId": "bid", "page": 1, "quote": "actual-A-quote", "anchor": "chars:0-14"}]},
                {"id": "B", "status": "UNDETERMINED", "gaps": [{"code": "B_GAP", "description": "read B certificate"}],
                 "proof": {"value": "ACTIVE-B-DETAIL", "largeUnrelatedProof": "SIBLING-BODY-"*25000}}
            ]}}]}

    def data(self, items):
        return SimpleNamespace(model_data=worker.ModelInputData(input=items, instructions="unchanged system instructions"))

    def test_assistant_messages_do_not_consume_two_complete_tool_batches(self):
        history = [prose("first analysis"), function("old"), output("old"),
                   prose("parallel reasoning"), function("left"), function("right"), output("left"),
                   prose("between parallel outputs"), output("right"), prose("next reasoning"),
                   function("last"), output("last"), prose("final connective message")]
        original = copy.deepcopy(history)
        recent, kept, total = worker.recent_tool_batches(history)
        self.assertEqual((kept, total), (2, 3))
        self.assertEqual([x["call_id"] for x in recent if x.get("type") == "function_call"], ["left", "right", "last"])
        self.assertEqual({x["call_id"] for x in recent if x.get("type") == "function_call"},
                         {x["call_id"] for x in recent if x.get("type") == "function_call_output"})
        self.assertIn("parallel reasoning", worker.canonical(recent))
        self.assertIn("final connective message", worker.canonical(recent))
        self.assertNotIn("first analysis", worker.canonical(recent))
        self.assertEqual(original, history)

    def test_orphan_output_and_incomplete_batch_fail_explicitly(self):
        for history in ([output("unknown")], [function("unfinished")], [function("a"), output("wrong")]):
            with self.assertRaises(worker.WorkerError):
                worker.recent_tool_batches(history)

    def test_receipt_projection_preserves_active_detail_and_ancestor_logic_and_restores_another_target(self):
        receipt = self.receipt()
        frozen = copy.deepcopy(receipt)
        a = worker.project_computation_receipt(receipt, "A")
        self.assertIn("ACTIVE-A-DETAIL", worker.canonical(a))
        self.assertNotIn("SIBLING-BODY-", worker.canonical(a))
        self.assertLess(len(worker.canonical(a)), len(worker.canonical(receipt)) // 50)
        root = a["obligations"][0]
        self.assertEqual(root["proof"]["operator"], "ANY")
        self.assertEqual(root["children"][1]["status"], "UNDETERMINED")
        self.assertEqual(a["gaps"], [{"code": "A_GAP", "description": "read A certificate"}])
        self.assertEqual(a["remainingTargetObligationIds"], ["A", "B"])
        b = worker.project_computation_receipt(receipt, "B")
        self.assertIn("ACTIVE-B-DETAIL", worker.canonical(b))
        self.assertNotIn("ACTIVE-A-DETAIL", worker.canonical(b))
        self.assertEqual(a["fullReceiptRef"], b["fullReceiptRef"])
        self.assertEqual(receipt, frozen)

    def test_active_group_keeps_children_but_drops_duplicate_aggregate_quotes(self):
        receipt = self.receipt()
        root = receipt["obligations"][0]
        root["evidences"] = [{"quote": "DUPLICATE_AGGREGATE_QUOTE"*2000}]
        view = worker.project_computation_receipt(receipt, "root")
        self.assertIn("actual-A-quote", worker.canonical(view))
        self.assertNotIn("DUPLICATE_AGGREGATE_QUOTE", worker.canonical(view))
        self.assertEqual(view["obligations"][0]["aggregateEvidenceRef"]["count"], 1)
        self.assertEqual(len(root["evidences"]), 1)

    def test_latest_receipt_supplied_once_and_model_only_projection_does_not_change_audit_or_decision(self):
        state = self.state()
        receipt = self.receipt()
        state.latest_computation_receipt = copy.deepcopy(receipt)
        history = [{"role": "user", "content": "original"}, function("a"),
                   output("a", {"computationReceipt": receipt, "sources": [{"id": "s-a", "quote": "read source"}]}), prose("next")]
        frozen = copy.deepcopy(history)
        result = state.filter_model_input(self.data(history))
        user = json.loads(result.input[0]["content"])
        self.assertEqual(user["currentHostState"]["latestComputationReceipt"]["activeTargetObligationId"], "A")
        tool_output = json.loads(next(x["output"] for x in result.input if x.get("type") == "function_call_output"))
        self.assertEqual(tool_output["result"]["computationReceipt"]["activeViewRef"], "currentHostState.latestComputationReceipt")
        self.assertEqual(worker.canonical(result.input).count("ACTIVE-A-DETAIL"), 1)
        self.assertNotIn("SIBLING-BODY-", worker.canonical(result.input))
        self.assertEqual(history, frozen)
        self.assertEqual(state.latest_computation_receipt, receipt)
        self.assertFalse(state.completed)
        self.assertEqual(state.completed_target_ids, set())
        # Even if the receipt leaves the two-batch window, switch from saved full host data.
        state.active_target = "B"
        restored = state.filter_model_input(self.data([{"role": "user", "content": "original"}]))
        self.assertIn("ACTIVE-B-DETAIL", worker.canonical(restored.input))
        self.assertNotIn("ACTIVE-A-DETAIL", worker.canonical(restored.input))

    def test_initial_rebuild_without_actual_reduction_is_not_a_compaction(self):
        state = self.state()
        state.filter_model_input(self.data([{"role": "user", "content": "initial"}]))
        self.assertEqual(state.context_rebuilds, 1)
        self.assertEqual(state.context_compactions, 0)
        self.assertEqual(state.context_removed_chars, 0)
        self.assertEqual(state.context_retained_tool_batches, 0)
        events = [x["event"] for x in state.channel.messages if x.get("type") == "event"]
        self.assertIn("context_rebuilt", events)
        self.assertNotIn("context_compacted", events)

    def test_new_approved_plan_does_not_restore_an_old_plan_receipt(self):
        fixture = fixtures.AgentLedReviewTests()
        state = worker.Investigation(fixture.request(), fixtures.MemoryHost(), fixtures.SETTINGS.tool_timeout_seconds)
        state.accept_review_plan(fixture.plan("plan-1"), {"conditions": [{"obligationId": "O1"}]})
        state.latest_computation_receipt = {"planRevision": "plan-1", "obligations": [{"id": "O1", "proof": {"value": "old-plan-detail"}}]}
        state.accept_review_plan(fixture.plan("plan-2"), {"conditions": [{"obligationId": "O1"}]})
        self.assertEqual(state.latest_computation_receipt, {})
        result = state.filter_model_input(self.data([{"role": "user", "content": "original"}]))
        self.assertNotIn("old-plan-detail", worker.canonical(result.input))


class ContextProjectionSdkTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_runner_assistant_prose_keeps_both_latest_tool_batches(self):
        request = fixtures.TargetWorkflowTests().request("read_section")
        host = fixtures.MemoryHost([{"sources": [{"id": str(i), "quote": f"ACTUAL_SOURCE_{i}"}]} for i in range(3)])
        model = fixtures.ScriptedModel([
            [fixtures.message("reading first"), fixtures.call("read_section", {"query": "one"}, "a")],
            [fixtures.message("reading second"), fixtures.call("read_section", {"query": "two"}, "b")],
            [fixtures.message("reading third"), fixtures.call("read_section", {"query": "three"}, "c")],
            [fixtures.message()]])
        result = await worker.investigate(request, host, fixtures.SETTINGS, model)
        self.assertEqual(result["status"], "NO_PROGRESS")
        latest = worker.canonical(model.inputs[-1]["input"])
        self.assertIn("ACTUAL_SOURCE_1", latest)
        self.assertIn("ACTUAL_SOURCE_2", latest)
        self.assertNotIn("ACTUAL_SOURCE_0", latest)
        self.assertEqual(result["contextRetainedToolBatches"], 2)
        self.assertEqual(result["contextRebuilds"], 4)

    async def test_source_verified_table_recompute_completes_without_extra_model_round(self):
        request = fixtures.TargetWorkflowTests().request("read_table_record")
        receipt = {"kind": "HOST_RULE_RECOMPUTE", "status": "COMPLETED", "targetEvidenceRevision": "layout-v2",
                   "businessCheckCompleted": False, "targetCheckCompleted": True, "remainingGapIds": []}
        host = fixtures.MemoryHost([{"status": "COMPLETED", "targetEvidenceRevision": "layout-v2", "layoutChanged": True,
                                    "acceptedFactIds": ["table-fact"], "computationReceipt": receipt}])
        model = fixtures.ScriptedModel([[fixtures.call("read_table_record", {"query": "same source"}, "table")]])
        result = await worker.investigate(request, host, fixtures.SETTINGS, model)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["acceptedFactIds"], ["table-fact"])
        self.assertEqual(result["automaticRecomputations"], 1)
        self.assertEqual(result["modelCalls"], 1)
