"""Server-generated cards stop only their current targets, without business completion."""
import copy
import json
import unittest
import worker
from test_worker import CanonicalTargetReceiptTests, MemoryHost, ScriptedModel, SETTINGS, call


def proof(target):
    return {"authority": "SOURCE_VALIDATED_PENDING_CONFIRMATION", "targetObligationId": target,
            "conditionStatus": "NEEDS_CONFIRMATION", "gapCodes": ["INFERENCE_CONFIRMATION_REQUIRED"],
            "claimIds": ["claim-" + target], "sourceIds": ["source-" + target], "targetFingerprint": "rule-" + target,
            "businessProof": False, "sourceValidation": "CURRENT_DOCUMENT_HASH_VERSION_PAGE_AND_EXACT_QUOTE"}


def receipt(pending=("A", "B", "C"), done=()):
    result = CanonicalTargetReceiptTests().receipt(done=done, remaining=tuple(x for x in ("A", "B", "C") if x not in done))
    result.update(pendingConfirmationTargetObligationIds=list(pending),
                  confirmationTargetProofs={target: proof(target) for target in pending})
    return result


def request(initial=None):
    value = CanonicalTargetReceiptTests().request("recompute_check", "read_section")
    if initial is not None:
        value["context"].update(initial)
    return value


class ProgrammaticConfirmationReceiptTests(unittest.IsolatedAsyncioTestCase):
    async def test_initial_server_cards_need_zero_model_calls_and_no_false_business_completion(self):
        host = MemoryHost()
        result = await worker.investigate(request(receipt()), host, SETTINGS, ScriptedModel([]))
        self.assertEqual(result["status"], "NO_PROGRESS")
        self.assertEqual(result["code"], "WAIT_CONFIRMATION")
        self.assertEqual(result["modelCalls"], 0)
        self.assertEqual(result["toolCalls"], 0)
        self.assertTrue(result["waitingForConfirmation"])
        self.assertFalse(result["targetCheckCompleted"])
        self.assertFalse(result["businessCheckCompleted"])
        self.assertEqual(result["businessUnclosedTargetObligationIds"], ["A", "B", "C"])

    async def test_local_recompute_program_cards_stop_without_model_submitting_inferred(self):
        host = MemoryHost([receipt()])
        model = ScriptedModel([[call("recompute_check", {"query": "current sources"})]])
        result = await worker.investigate(request(), host, SETTINGS, model)
        self.assertEqual(result["modelCalls"], 1)
        self.assertEqual(result["toolCalls"], 1)
        self.assertEqual(result["code"], "WAIT_CONFIRMATION")
        self.assertFalse(result["businessCheckCompleted"])

    async def test_submitting_one_card_does_not_hide_same_targets_other_content_gap(self):
        req = CanonicalTargetReceiptTests().request("submit_candidate_fact")
        current = receipt(pending=()); current["kind"] = "HOST_RULE_RECOMPUTE"
        response = {**current, "status": "NEEDS_CONFIRMATION", "confirmationId": "one-card",
                    "computationReceipt": current, "semanticWorkItems": [{"workKey": "path-A",
                    "targetObligationId": "A", "evidenceRevision": "A-1", "terminalForRevision": False,
                    "targetExhaustedForRevision": False, "remainingRepairActions": ["READ_COMPLETE_RECORD"]}]}
        state = worker.Investigation(req, MemoryHost([response]), 10)
        await state.invoke("submit_candidate_fact", req["tools"][0]["parameters"],
                           json.dumps({"query": "cited candidate", "targetObligationId": "A"}))
        self.assertFalse(state.confirmation_targets)
        self.assertFalse(state.waiting_for_confirmation())
        self.assertIn("A", state.remaining_target_ids())

    def test_other_material_target_remains_schedulable(self):
        state = worker.Investigation(request(), MemoryHost(), 10)
        state.observe_completion(receipt(pending=("A",), done=("B",)))
        self.assertEqual(state.confirmation_targets, {"A"})
        self.assertEqual(state.remaining_target_ids(), {"C"})
        self.assertFalse(state.waiting_for_confirmation())
        self.assertNotEqual(state.finish()["code"], "WAIT_CONFIRMATION")

    def test_exhausted_other_target_does_not_become_wait_confirmation(self):
        state = worker.Investigation(request(), MemoryHost(), 10)
        state.observe_completion(receipt(pending=("A",), done=("B",)))
        state.exhausted_targets["C"] = "C-1"
        self.assertFalse(state.remaining_target_ids())
        self.assertFalse(state.waiting_for_confirmation())
        self.assertNotEqual(state.finish()["code"], "WAIT_CONFIRMATION")

    def test_failure_is_not_suppressed_by_confirmation(self):
        state = worker.Investigation(request(), MemoryHost(), 10)
        state.observe_completion(receipt())
        state.failed_tools.add("source-read")
        result = state.finish()
        self.assertEqual(result["status"], "FAILED")
        self.assertFalse(result["waitingForConfirmation"])

    def test_changed_revision_reopens_only_affected_confirmation_target(self):
        state = worker.Investigation(request(), MemoryHost(), 10)
        state.observe_completion(receipt())
        state.refresh_target_revisions({"targetEvidenceRevisions": {"A": "A-2"}})
        self.assertEqual(state.confirmation_targets, {"B", "C"})
        self.assertEqual(state.remaining_target_ids(), {"A"})

    def test_stale_revision_cannot_pause_work(self):
        state = worker.Investigation(request(), MemoryHost(), 10)
        old = receipt(); old["targetEvidenceRevisions"]["A"] = "A-old"
        with self.assertRaisesRegex(worker.WorkerError, "HOST_TARGET_RECEIPT_INVALID"):
            state.observe_completion(old)
        self.assertFalse(state.confirmation_targets)

    def test_unverified_or_missing_card_receipt_is_rejected(self):
        for mutate in (lambda x: x.pop("confirmationTargetProofs"),
                       lambda x: x["confirmationTargetProofs"]["A"].update(claimIds=[]),
                       lambda x: x["confirmationTargetProofs"]["A"].update(gapCodes=["CONTENT_EVIDENCE_REQUIRED"]),
                       lambda x: x["confirmationTargetProofs"]["A"].update(businessProof=True)):
            state = worker.Investigation(request(), MemoryHost(), 10)
            invalid = receipt(); mutate(invalid)
            with self.assertRaisesRegex(worker.WorkerError, "HOST_CONFIRMATION_RECEIPT_INVALID"):
                state.observe_completion(invalid)

    def test_new_empty_authoritative_pending_set_removes_old_cards(self):
        state = worker.Investigation(request(), MemoryHost(), 10)
        state.observe_completion(receipt())
        state.observe_completion(receipt(pending=()))
        self.assertFalse(state.confirmation_targets)
        self.assertEqual(state.remaining_target_ids(), {"A", "B", "C"})


if __name__ == "__main__":
    unittest.main()
