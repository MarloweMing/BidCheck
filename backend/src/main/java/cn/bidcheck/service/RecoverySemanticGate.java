package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Per-target consumed evidence, not a model-call budget or a business verdict. */
final class RecoverySemanticGate {
    static final String VERSION="recovery-semantic-gate-1-consumed-target-inputs";
    static final String POLICY="recovery-admission-8-completed-packet-work";
    private static final String LEGACY_TARGET="__CHECKPOINT__";
    private static final String FIRST_ROUND="first-round-target-inputs-1";
    private RecoverySemanticGate() {}

    /** Carry consumption history across local recomputation, never an earlier business verdict. */
    static Finding preserveFirstRound(Finding before, Finding after) {
        if (before == after) return after;
        after=PendingTableWork.preserve(before,after);
        Map<String,Object> original = atomic(before);
        Map<String,Object> receipt = map(original.get("firstRoundSemanticInputs"));
        if (!FIRST_ROUND.equals(text(receipt, "version")) || text(receipt, "requestId").isEmpty()
                || !Boolean.FALSE.equals(receipt.get("businessProof"))) return after;
        Map<String,Object> updated = new LinkedHashMap<>(atomic(after));
        if (updated.containsKey("firstRoundSemanticInputs")) return after;
        updated.put("firstRoundSemanticInputs", receipt);
        Object investigation = original.getOrDefault("firstRoundInvestigation", original.get("agentInvestigation"));
        if (investigation instanceof Map<?,?>) updated.putIfAbsent("firstRoundInvestigation", investigation);
        Map<String,Object> assessment = new LinkedHashMap<>(after.assessment());
        assessment.put("atomicCheck", updated);
        return new Finding(after.id(), after.runId(), after.checkpoint(), after.evaluation(), assessment);
    }

    /** A completed text investigation consumes its current inputs, not its unresolved business
     * obligation. In particular, reading/searching can stop without exhausting every semantic
     * repair path. Keep that distinction instead of demanding an impossible exhaustion receipt. */
    static Finding captureFirstRound(Finding finding,Map<String,Object> context,String policy){
        if(context.containsKey("recoveryStrategy")||context.containsKey("recoveryMode"))return finding;
        Map<String,Object> atomic=atomic(finding),audit=map(atomic.get("agentInvestigation"));
        if(!cn.bidcheck.compat.Java8Api.setOf("NO_PROGRESS","EVIDENCE_UPDATED").contains(text(audit,"status"))
                ||!"CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority"))
                ||text(audit,"requestId").isEmpty()||!(audit.get("failedToolCount") instanceof Number)
                ||((Number)audit.get("failedToolCount")).longValue()!=0)return finding;
        Set<String> remaining=new HashSet<>(stringList(audit.get("remainingTargetObligationIds")));
        remaining.removeAll(stringList(audit.get("pendingConfirmationTargetObligationIds")));
        Set<String> serverConsumed = serverConsumedTargets(finding, audit);
        boolean repeated = "REPEATED_ACTION_WITHOUT_NEW_EVIDENCE".equals(text(audit, "code"));
        if (!repeated && serverConsumed.isEmpty()) return finding;
        Set<String> visited=new TreeSet<>();
        for(Map<String,Object> row:maps(audit.get("targetProgressLedger"))){String id=text(row,"targetObligationId");
            boolean read=row.get("navigationCount") instanceof Number&&((Number)row.get("navigationCount")).longValue()>0;
            boolean attempted=row.get("noEvidenceProgressRounds") instanceof Number&&((Number)row.get("noEvidenceProgressRounds")).longValue()>0
                || stableRoundCount(audit, row);
            if(remaining.contains(id)&&Boolean.TRUE.equals(row.get("visited"))&&(read||attempted)
                    && (repeated || serverConsumed.contains(id))
                    && targetConsumptionSafe(finding, audit, id)) visited.add(id);
        }
        if(visited.isEmpty())return finding;
        Map<String,Object> scoped=new LinkedHashMap<>(context);scoped.put("recoveryTargetObligationIds",new ArrayList<>(visited));
        Map<String,Object> updated=new LinkedHashMap<>(atomic);
        updated.put("firstRoundSemanticInputs",fields("version",FIRST_ROUND,"requestId",audit.get("requestId"),
            "strategy","TARGETED_EVIDENCE","inputs",inputs(finding,scoped,policy),"businessProof",false,
            "reason","FIRST_INVESTIGATION_STOPPED_WITHOUT_NEW_FACTS","exhaustedBusinessTargets",false));
        Map<String,Object> assessment=new LinkedHashMap<>(map(finding.assessment()));assessment.put("atomicCheck",updated);
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),finding.evaluation(),assessment);
    }

    private static Map<String,Object> packetRule(Finding finding, String target) {
        Object rules = map(atomic(finding).get("plan")).get("rules");
        Map<String,Map<String,Object>> indexed = new LinkedHashMap<>();
        index(maps(rules), indexed);
        Map<String,Object> rule = indexed.get(target);
        if (rule == null) rule = map(cn.bidcheck.verification.engine.RecoveryTargets.readDependencies(rules)
            .getOrDefault(target, Collections.emptyMap()).get("rule"));
        Object row=map(rule.get("parameters")).get("declaredPerformanceRow");
        return "CONTRACT".equals(text(rule,"predicate")) && row instanceof Map<?,?> ? map(row) : null;
    }

    private static boolean requiresPacketCoverage(Finding finding, String target) {
        return packetRule(finding,target)!=null;
    }

    private static boolean serverCoverageComplete(Map<String,Object> audit, String targetId) {
        Map<String,Object> receipt = verifiedConsumptionReceipt(audit);
        for (Map<String,Object> target : maps(receipt.get("targets"))) {
            if (targetId.equals(text(target, "targetObligationId"))
                    && Objects.equals(map(audit.get("targetEvidenceRevisions")).get(targetId), target.get("evidenceRevision"))
                    && Boolean.TRUE.equals(target.get("readCoverageComplete"))
                    && Boolean.TRUE.equals(map(target.get("readCoverage")).get("complete"))) return true;
        }
        return false;
    }

    private static Map<String,Object> verifiedConsumptionReceipt(Map<String,Object> audit) {
        Map<String,Object> receipt = map(audit.get("serverTargetInputConsumption"));
        boolean callLimited = "INVESTIGATION_CALL_LIMIT".equals(text(audit, "code"));
        if (callLimited && (!cn.bidcheck.compat.Java8Api.setOf("NO_PROGRESS", "EVIDENCE_UPDATED").contains(text(audit, "status"))
                || !"CURRENT_SERVER_PROOF".equals(text(audit, "targetReceiptAuthority"))
                || text(audit, "requestId").isEmpty()
                || !(audit.get("failedToolCount") instanceof Number)
                || ((Number) audit.get("failedToolCount")).longValue() != 0)) return Collections.emptyMap();
        if (!cn.bidcheck.compat.Java8Api.setOf("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", "SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", "INVESTIGATION_CALL_LIMIT").contains(text(audit, "code"))
                || !"server-target-input-consumption-1".equals(text(receipt, "version"))
                || !"CURRENT_SERVER_PROOF".equals(text(receipt, "authority"))
                || !Boolean.FALSE.equals(receipt.get("businessProof"))
                || !text(audit, "requestId").equals(text(receipt, "requestId"))
                || text(audit, "serverPlanRevision").isEmpty()
                || !text(audit, "serverPlanRevision").equals(text(receipt, "serverPlanRevision"))) return Collections.emptyMap();
        return receipt;
    }

    private static boolean stableDefaultGuidance(Map<String,Object> audit, Map<String,Object> receipt,
            Map<String,Object> work) {
        if (!"host-repair-action-origin-1".equals(text(receipt, "repairActionOriginVersion"))
                || !"DEFAULT_GUIDANCE".equals(text(work, "repairActionOrigin"))
                || !cn.bidcheck.compat.Java8Api.setOf("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", "INVESTIGATION_CALL_LIMIT").contains(text(audit, "code"))
                || !"CURRENT_SERVER_PROOF".equals(text(audit, "targetReceiptAuthority"))
                || !Boolean.TRUE.equals(work.get("readCoverageComplete"))
                || !(work.get("pendingReadActions") instanceof Collection<?>)
                || !((Collection<?>) work.get("pendingReadActions")).isEmpty()
                || !Collections.singletonList("READ_COMPLETE_RECORD").equals(stringList(work.get("remainingRepairActions")))) return false;
        for (Map<String,Object> row : maps(audit.get("targetProgressLedger"))) {
            if (text(work, "targetObligationId").equals(text(row, "targetObligationId"))
                    && !text(work, "evidenceRevision").isEmpty()
                    && text(work, "evidenceRevision").equals(text(row, "relevantEvidenceRevision"))
                    && text(audit, "serverPlanRevision").equals(text(row, "ruleVersion"))
                    && Boolean.TRUE.equals(row.get("visited"))
                    // A global budget stop supplies no legacy per-target stop authority.
                    && (!"INVESTIGATION_CALL_LIMIT".equals(text(audit, "code"))
                        || "java-agent-target-rounds-1".equals(text(row, "noNewFactCounterVersion")))
                    && stableRoundCount(audit, row)) return true;
        }
        return false;
    }

    private static boolean stableRoundCount(Map<String,Object> audit, Map<String,Object> row) {
        if (row.containsKey("noNewFactCounterVersion")) {
            return "java-agent-target-rounds-1".equals(text(row, "noNewFactCounterVersion"))
                && text(audit, "requestId").equals(text(row, "noNewFactRequestId"))
                && "THREE_ROUNDS_NO_NEW_FACT".equals(text(row, "noNewFactStopReason"))
                && row.get("noNewFactModelRounds") instanceof Number
                && ((Number) row.get("noNewFactModelRounds")).longValue() >= 3;
        }
        // Retain the existing legacy receipt contract; do not rewrite its counter semantics.
        return row.get("noEvidenceProgressRounds") instanceof Number
            && ((Number) row.get("noEvidenceProgressRounds")).longValue() >= 3;
    }
    /** Re-reading a bounded record after a validation rejection needs a new page, fact or
     * repair action. The host receipt only consumes this input revision. */
    private static boolean completedPacketReread(Map<String,Object> audit, Map<String,Object> receipt,
            Map<String,Object> work) {
        if (work.containsKey("contractFieldWorkPlan")) return verifiedContractWorkStop(audit, work);
        if (!"SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE".equals(text(audit, "code"))
                || !"host-repair-action-origin-1".equals(text(receipt, "repairActionOriginVersion"))
                || !"VALIDATION_REPAIR".equals(text(work, "repairActionOrigin"))
                || !Collections.singletonList("READ_COMPLETE_RECORD").equals(stringList(work.get("remainingRepairActions")))
                || !Boolean.TRUE.equals(work.get("readCoverageComplete"))
                || !(work.get("pendingReadActions") instanceof Collection<?>)
                || !((Collection<?>) work.get("pendingReadActions")).isEmpty()) return false;
        String target = text(work, "targetObligationId");
        Map<String,Object> packet = map(work.get("readCoverage"));
        if (!"CURRENT_SERVER_PROOF".equals(text(packet, "authority")) || !Boolean.FALSE.equals(packet.get("businessProof"))
                || !Boolean.TRUE.equals(packet.get("scopeAvailable")) || !Boolean.TRUE.equals(packet.get("complete"))
                || target.isEmpty() || text(work, "evidenceRevision").isEmpty()
                || !target.equals(text(packet, "targetObligationId")) || text(packet, "documentId").isEmpty()
                || !(packet.get("documentVersion") instanceof Number) || ((Number)packet.get("documentVersion")).intValue() < 1
                || text(packet, "documentHash").isEmpty() || stringList(packet.get("availablePages")).isEmpty()
                || stringList(packet.get("readSourceIds")).isEmpty()
                || !Collections.emptyList().equals(packet.get("pendingPages"))
                || !Collections.emptyList().equals(packet.get("missingSourcePages"))) return false;
        return maps(audit.get("targetProgressLedger")).stream().anyMatch(row ->
            target.equals(text(row, "targetObligationId")) && Boolean.TRUE.equals(row.get("visited"))
            && "java-agent-target-rounds-1".equals(text(row, "noNewFactCounterVersion"))
            && text(work, "evidenceRevision").equals(text(row, "relevantEvidenceRevision"))
            && text(audit, "serverPlanRevision").equals(text(row, "ruleVersion"))
            && stableRoundCount(audit, row));
    }

    /** A budget stop is global. This independent, witnessed repair plan is for one
     * submitted record and one bounded packet revision; it never closes the business rule. */
    private static boolean verifiedContractWorkStop(Map<String,Object> audit, Map<String,Object> work) {
        Map<String,Object> plan = map(work.get("contractFieldWorkPlan"));
        Map<String,Object> coverage = map(work.get("readCoverage"));
        List<Map<String,Object>> tasks = maps(plan.get("tasks"));
        if (!"bounded-contract-field-work-1".equals(text(plan,"version"))
                || !"CURRENT_SERVER_PROOF".equals(text(plan,"authority"))
                || !"NO_EXECUTABLE_CORRECTION".equals(text(plan,"status"))
                || !Boolean.FALSE.equals(plan.get("businessProof"))
                || !Boolean.TRUE.equals(plan.get("targetWorkComplete"))
                || !(plan.get("rejectedFieldCount") instanceof Number) || ((Number)plan.get("rejectedFieldCount")).longValue()<0
                || !(plan.get("attemptedFieldCount") instanceof Number) || ((Number)plan.get("attemptedFieldCount")).longValue()<1
                || tasks.isEmpty() || tasks.size()!=((Number)plan.get("attemptedFieldCount")).longValue()
                || tasks.stream().anyMatch(task -> text(task,"field").isEmpty()
                    || !Boolean.FALSE.equals(task.get("businessProof")) || !text(task,"fieldPath").startsWith("/contractFields/")
                    || !Arrays.asList("ACCEPTED_OR_KNOWN","NO_GROUNDED_CORRECTION").contains(text(task,"status")))
                || !emptyCollection(plan.get("remainingActions"))
                || !emptyCollection(work.get("remainingRepairActions")) || !emptyCollection(work.get("pendingReadActions"))
                || !Boolean.TRUE.equals(work.get("terminalForRevision")) || !Boolean.TRUE.equals(work.get("consumed"))
                || !Boolean.TRUE.equals(work.get("readCoverageComplete")) || !Boolean.TRUE.equals(coverage.get("complete"))
                || !Boolean.TRUE.equals(coverage.get("scopeAvailable")) || !Boolean.FALSE.equals(coverage.get("businessProof"))
                || !"CURRENT_SERVER_PROOF".equals(text(coverage,"authority"))
                || !emptyCollection(coverage.get("pendingPages")) || !emptyCollection(coverage.get("missingSourcePages"))
                || stringList(coverage.get("availablePages")).isEmpty() || stringList(coverage.get("readSourceIds")).isEmpty()) return false;
        Set<String> workedFields=new HashSet<>();
        for(Map<String,Object> task:tasks) {
            String field=text(task,"field");
            if("person".equals(field))field="personRole";
            if(Arrays.asList("amount","signedDate","party","personRole").contains(field))workedFields.add(field);
        }
        // Zero rejections is valid only when every submitted field has an actual accept/reuse receipt.
        if(((Number)plan.get("rejectedFieldCount")).longValue()==0
                &&(workedFields.size()<2||Collections.disjoint(workedFields,Arrays.asList("amount","signedDate","personRole"))
                    ||tasks.stream().anyMatch(task->!"ACCEPTED_OR_KNOWN".equals(text(task,"status")))))return false;
        String target=text(work,"targetObligationId"), revision=text(work,"evidenceRevision");
        if (target.isEmpty() || revision.isEmpty() || !revision.equals(map(audit.get("targetEvidenceRevisions")).get(target))
                || !revision.equals(text(plan,"evidenceRevision")) || !target.equals(text(plan,"targetObligationId"))
                || !target.equals(text(coverage,"targetObligationId")) || !text(audit,"requestId").equals(text(plan,"requestId"))
                || !text(audit,"serverPlanRevision").equals(text(plan,"serverPlanRevision"))
                || text(plan,"documentId").isEmpty() || !text(plan,"documentId").equals(text(coverage,"documentId"))
                || text(plan,"documentHash").isEmpty() || !text(plan,"documentHash").equals(text(coverage,"documentHash"))
                || !(plan.get("documentVersion") instanceof Number) || ((Number)plan.get("documentVersion")).longValue()<1
                || !Objects.equals(plan.get("documentVersion"),coverage.get("documentVersion"))) return false;
        return maps(audit.get("targetProgressLedger")).stream().anyMatch(row -> target.equals(text(row,"targetObligationId"))
            && Boolean.TRUE.equals(row.get("visited")) && revision.equals(text(row,"relevantEvidenceRevision"))
            && text(audit,"serverPlanRevision").equals(text(row,"ruleVersion")));
    }

    private static boolean emptyCollection(Object value) {
        return value instanceof Collection<?> && ((Collection<?>)value).isEmpty();
    }

    private static boolean validatedOriginalsAlreadyRead(Map<String,Object> audit,Map<String,Object> receipt,Map<String,Object> work) {
        if (!cn.bidcheck.compat.Java8Api.setOf("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE","SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE").contains(text(audit,"code"))
            || !"host-repair-action-origin-1".equals(text(receipt,"repairActionOriginVersion"))
            || !"VALIDATION_REPAIR".equals(text(work,"repairActionOrigin"))
            || !Collections.singletonList("READ_COMPLETE_RECORD").equals(stringList(work.get("remainingRepairActions")))
            || !Boolean.TRUE.equals(work.get("readCoverageComplete")) || !Collections.emptyList().equals(work.get("pendingReadActions"))) return false;
        Map<String,Object> record=map(work.get("validationReadWork"));
        String id=text(work,"targetObligationId"),revision=text(work,"evidenceRevision");
        List<String> required=stringList(record.get("requiredSourceIds"));
        if (!"validated-read-work-1".equals(text(record,"version")) || !"CURRENT_SERVER_PROOF".equals(text(record,"authority"))
            || !Boolean.FALSE.equals(record.get("businessProof")) || id.isEmpty() || revision.isEmpty()
            || !id.equals(text(record,"targetObligationId")) || !revision.equals(text(record,"evidenceRevision"))
            || !cn.bidcheck.compat.Java8Api.setOf("APPOINTMENT_ENDPOINT_UNCITED","OBSERVED_CERTIFICATE_RECORDS_READ").contains(text(record,"reason"))
            || required.isEmpty() || !stringList(record.get("readSourceIds")).containsAll(required)
            || !Collections.emptyList().equals(record.get("pendingSourceIds"))) return false;
        return maps(audit.get("targetProgressLedger")).stream().anyMatch(row -> id.equals(text(row,"targetObligationId"))
            && Boolean.TRUE.equals(row.get("visited")) && revision.equals(text(row,"relevantEvidenceRevision"))
            && text(audit,"serverPlanRevision").equals(text(row,"ruleVersion"))
            && "java-agent-target-rounds-1".equals(text(row,"noNewFactCounterVersion")) && stableRoundCount(audit,row));
    }

    private static Set<String> verifiedRepairActions(Map<String,Object> audit, String targetId) {
        Set<String> actions = new TreeSet<>();
        Map<String,Object> receipt = verifiedConsumptionReceipt(audit);
        for (Map<String,Object> work : maps(receipt.get("targets"))) {
            if (!targetId.equals(text(work, "targetObligationId"))
                    || !Objects.equals(map(audit.get("targetEvidenceRevisions")).get(targetId), work.get("evidenceRevision"))) continue;
            if (!stableDefaultGuidance(audit, receipt, work) && !completedPacketReread(audit, receipt, work)
                    && !validatedOriginalsAlreadyRead(audit,receipt,work))
                actions.addAll(stringList(work.get("remainingRepairActions")));
            for(String id : stringList(map(work.get("validationReadWork")).get("pendingSourceIds"))) actions.add("PENDING_ORIGINAL:"+id);
            for (Map<String,Object> read : maps(work.get("pendingReadActions")))
                actions.add("PENDING_READ:" + RecoveryEvidenceIdentity.canonical(read));
        }
        return actions;
    }

    /** Local revalidation omits investigation receipts; retain the server-verified read history.
     * A current authoritative receipt always wins, including pending coverage or repair work. */
    private static Map<String,Object> effectiveAudit(Finding finding) {
        Map<String,Object> body = atomic(finding);
        Map<String,Object> current = map(body.get("agentInvestigation"));
        return "CURRENT_SERVER_PROOF".equals(text(current, "targetReceiptAuthority"))
            ? current : map(body.get("firstRoundInvestigation"));
    }

    private static boolean targetConsumptionSafe(Finding finding, Map<String,Object> audit, String targetId) {
        if(!PendingTableWork.pending(finding,targetId).isEmpty())return false;
        Map<String,Object> current = map(atomic(finding).get("agentInvestigation"));
        // A real failed attempt is not the model-disabled local recomputation above.
        // Its recovery must not be suppressed by an older successful read receipt.
        if ("FAILED".equals(text(current, "status"))
                || current.get("failedToolCount") instanceof Number
                    && ((Number) current.get("failedToolCount")).longValue() > 0) return false;
        Map<String,Object> receipt = verifiedConsumptionReceipt(audit);
        // An invalid supplied receipt must not silently fall back to legacy admission.
        if (audit.containsKey("serverTargetInputConsumption") && receipt.isEmpty()) return false;
        for (Map<String,Object> work : maps(receipt.get("targets"))) {
            if (targetId.equals(text(work,"targetObligationId")) && work.containsKey("contractFieldWorkPlan")) {
                Map<String,Object> rule = packetRule(finding,targetId);
                Map<String,Object> coverage = map(work.get("readCoverage"));
                if (rule==null || !text(rule,"documentId").equals(text(coverage,"documentId"))
                        || !text(rule,"documentHash").equals(text(coverage,"documentHash"))
                        || !Objects.equals(rule.get("documentVersion"),coverage.get("documentVersion"))
                        || !(rule.get("proofPageStart") instanceof Number) || !(rule.get("proofPageEnd") instanceof Number)) return false;
                long start=((Number)rule.get("proofPageStart")).longValue(), end=((Number)rule.get("proofPageEnd")).longValue();
                if (!(coverage.get("availablePages") instanceof Collection<?>) || start<1 || end<start
                        || ((Collection<?>)coverage.get("availablePages")).stream().anyMatch(p -> !(p instanceof Number)
                            || ((Number)p).longValue()<start || ((Number)p).longValue()>end)) return false;
            }
        }
        if ("INVESTIGATION_CALL_LIMIT".equals(text(audit, "code")))
            return callLimitedStableTarget(finding, audit, receipt, targetId);
        for (Map<String,Object> work : maps(receipt.get("targets"))) {
            if (!targetId.equals(text(work, "targetObligationId"))) continue;
            if (!Objects.equals(map(audit.get("targetEvidenceRevisions")).get(targetId), work.get("evidenceRevision"))) return false;
            if (work.containsKey("contractFieldWorkPlan") && !verifiedContractWorkStop(audit, work)) return false;
            if ("host-repair-action-origin-1".equals(text(receipt, "repairActionOriginVersion"))
                    && !Boolean.TRUE.equals(work.get("readCoverageComplete"))) return false;
        }
        return verifiedRepairActions(audit, targetId).isEmpty()
            && (!requiresPacketCoverage(finding, targetId) || serverCoverageComplete(audit, targetId));
    }

    /** A whole-investigation budget is not exhaustion. Only a separately stopped,
     * fully read target may retain its current input snapshot under that budget code. */
    private static boolean callLimitedStableTarget(Finding finding, Map<String,Object> audit,
            Map<String,Object> receipt, String targetId) {
        if (!"INVESTIGATION_CALL_LIMIT".equals(text(audit, "code")) || receipt.isEmpty()) return false;
        for (Map<String,Object> work : maps(receipt.get("targets"))) {
            if (targetId.equals(text(work, "targetObligationId")) && work.containsKey("contractFieldWorkPlan"))
                return verifiedContractWorkStop(audit, work);
            if (targetId.equals(text(work, "targetObligationId"))
                    && Objects.equals(map(audit.get("targetEvidenceRevisions")).get(targetId), work.get("evidenceRevision"))
                    && (serverCoverageComplete(audit, targetId)
                        || flatCertificateUrlEvidence(finding, audit, work, targetId))
                    && stableDefaultGuidance(audit, receipt, work)) return true;
        }
        return false;
    }

    /** A certificate URL may have a flat receipt instead of a contract packet. Three
     * empty recomputations are insufficient: the host must attest an actual returned
     * original or a fact accepted for this exact target and immutable source. */
    private static boolean flatCertificateUrlEvidence(Finding finding, Map<String,Object> audit,
            Map<String,Object> work, String targetId) {
        if (!(work.get("readCoverage") instanceof Map<?,?>) || !map(work.get("readCoverage")).isEmpty()) return false;
        Map<String,Map<String,Object>> indexed=new LinkedHashMap<>();
        Object rules=map(atomic(finding).get("plan")).get("rules");
        index(maps(rules),indexed);
        Map<String,Object> rule=indexed.get(targetId);
        if(rule==null)rule=map(cn.bidcheck.verification.engine.RecoveryTargets.readDependencies(rules)
            .getOrDefault(targetId,Collections.emptyMap()).get("rule"));
        if(!"MATERIAL".equals(text(rule,"predicate"))
                || text(map(rule.get("parameters")),"certificateProofCategory").isEmpty()
                || !"查询网址".equals(text(rule,"expected")) && !"查询网址".equals(text(rule,"targetExpected")))return false;
        Map<String,Object> witness=map(work.get("flatReadEvidence"));
        if(!"server-flat-target-read-1".equals(text(witness,"version"))
                || !"CURRENT_SERVER_PROOF".equals(text(witness,"authority")) || !Boolean.FALSE.equals(witness.get("businessProof"))
                || !targetId.equals(text(witness,"targetObligationId")) || !text(audit,"requestId").equals(text(witness,"requestId"))
                || !text(audit,"serverPlanRevision").equals(text(witness,"serverPlanRevision"))
                || text(work,"evidenceRevision").isEmpty() || !text(work,"evidenceRevision").equals(text(witness,"evidenceRevision")))return false;
        Set<String> originals=new HashSet<>();
        for(Map<String,Object> source:maps(witness.get("sources"))){
            String id=text(source,"id");
            if(id.isEmpty()||!originals.add(id)||text(source,"documentId").isEmpty()
                    ||!text(source,"documentHash").matches("[a-fA-F0-9]{64}") || text(source,"parserFingerprint").isEmpty()
                    || !(source.get("documentVersion") instanceof Number)||((Number)source.get("documentVersion")).longValue()<1
                    || !(source.get("page") instanceof Number)||((Number)source.get("page")).longValue()<1
                    || !Arrays.asList("PAGE","BLOCK").contains(text(source,"locationType")))return false;
        }
        Set<String> used=new HashSet<>(stringList(witness.get("readSourceIds"))),factIds=new HashSet<>();
        for(Map<String,Object> fact:maps(witness.get("acceptedFacts"))){
            String id=text(fact,"factId");List<String> sources=stringList(fact.get("sourceIds"));
            if(id.isEmpty()||!factIds.add(id)||sources.isEmpty()||!originals.containsAll(sources))return false;
            used.addAll(sources);
        }
        return !originals.isEmpty()&&!used.isEmpty()&&originals.equals(used)
            &&factIds.equals(new HashSet<>(stringList(witness.get("acceptedFactIds"))))
            &&new HashSet<>(stringList(audit.get("acceptedFactIds"))).containsAll(factIds);
    }

    private static Set<String> serverConsumedTargets(Finding finding, Map<String,Object> audit) {
        Map<String,Object> receipt = verifiedConsumptionReceipt(audit);
        Map<String,Object> revisions = map(audit.get("targetEvidenceRevisions"));
        Set<String> consumed = new TreeSet<>();
        for (Map<String,Object> target : maps(receipt.get("targets"))) {
            String id = text(target, "targetObligationId");
            if (target.containsKey("contractFieldWorkPlan")) {
                if (verifiedContractWorkStop(audit, target)) consumed.add(id);
                continue;
            }
            if (!id.isEmpty() && (callLimitedStableTarget(finding, audit, receipt, id)
                    || Objects.equals(revisions.get(id), target.get("evidenceRevision")) && (completedPacketReread(audit, receipt, target)
                        || validatedOriginalsAlreadyRead(audit,receipt,target)))) {
                consumed.add(id);
                continue;
            }
            if (id.isEmpty() || !Objects.equals(revisions.get(id), target.get("evidenceRevision"))
                    || !Boolean.TRUE.equals(target.get("terminalForRevision"))
                    || !Boolean.TRUE.equals(target.get("readCoverageComplete"))
                    || !Boolean.TRUE.equals(target.get("consumed"))
                    || !(target.get("remainingRepairActions") instanceof Collection<?>)
                    || !((Collection<?>) target.get("remainingRepairActions")).isEmpty()) continue;
            consumed.add(id);
        }
        return consumed;
    }

    static Map<String,Object> inspect(Finding finding,String strategy,Map<String,Object> context,
            List<Map<String,Object>> attempts,String policy) {

        List<Map<String,Object>> inputs=inputs(finding,context,policy);
        Map<String,String> unavailable = "TARGETED_EVIDENCE".equals(strategy)
            ? cn.bidcheck.verification.engine.RecoveryTargets.unavailableEvidenceTargets(
                map(atomic(finding).get("plan")).get("rules"), atomic(finding).get("conditions"),
                cn.bidcheck.compat.Java8Api.toList(inputs.stream().map(input -> text(input, "targetId"))))
            : Collections.emptyMap();
        List<Map<String,Object>> consumed=new ArrayList<>();
        for(Map<String,Object> attempt:attempts)if(strategy.equals(text(attempt,"strategy"))
                &&cn.bidcheck.compat.Java8Api.setOf("FINISHED", "NO_PROGRESS").contains(text(attempt,"status")))
            for(Map<String,Object> input:maps(attempt.get("semanticConsumedInputs")))
                if(VERSION.equals(text(input,"version")))consumed.add(input);
        Set<String> firstRound=firstRoundConsumed(strategy,context,map(map(atomic(finding).get("agentInvestigation")).get("firstRoundTextWork")));
        firstRound=new TreeSet<>(firstRound);
        // Legacy admission keys have no per-target input snapshot. Contract packets must
        // use the comparable receipt below, so new evidence/repair inputs cannot be hidden.
        firstRound.removeIf(id -> requiresPacketCoverage(finding, id));
        Map<String,Object> firstReceipt=map(atomic(finding).get("firstRoundSemanticInputs"));
        if("TARGETED_EVIDENCE".equals(strategy)&&FIRST_ROUND.equals(text(firstReceipt,"version"))
                &&!text(firstReceipt,"requestId").isEmpty()&&Boolean.FALSE.equals(firstReceipt.get("businessProof")))
            for(Map<String,Object> current:inputs)if(maps(firstReceipt.get("inputs")).stream().anyMatch(prior->consumed(prior,current)))firstRound.add(text(current,"targetId"));
        Map<String,Object> audit = effectiveAudit(finding);
        if ("TARGETED_EVIDENCE".equals(strategy)) firstRound.removeIf(id ->
            !targetConsumptionSafe(finding, audit, id));
        List<String> eligible=new ArrayList<>(),blocked=new ArrayList<>();
        for(Map<String,Object> input:inputs){String id=text(input,"targetId");
            boolean consumptionSafe = !"TARGETED_EVIDENCE".equals(strategy)
                || targetConsumptionSafe(finding, audit, id);
            (unavailable.containsKey(id) || consumptionSafe && (firstRound.contains(id)
                || consumed.stream().anyMatch(prior->consumed(prior,input))) ? blocked : eligible).add(id);
        }
        boolean allowed=!eligible.isEmpty();
        return fields("version",VERSION,"allowed",allowed,"reasonCode",allowed
                ?blocked.isEmpty()?"NEW_TARGET_OR_RELEVANT_EVIDENCE":"PARTIAL_TARGET_PROGRESS"
                :unavailable.isEmpty()?"TARGET_SEMANTIC_INPUT_CONSUMED":"NO_EXECUTABLE_EVIDENCE_TARGET",
            "unavailableEvidenceTargets",unavailable,
            "eligibleTargetIds",cn.bidcheck.compat.Java8Api.toList(eligible.stream().filter(id->!LEGACY_TARGET.equals(id))),
            "blockedTargetIds",cn.bidcheck.compat.Java8Api.listCopyOf(blocked),"firstRoundConsumedTargetIds",firstRound,"inputs",inputs,"businessProof",false,
            "description",allowed?"仅调查具有未处理输入的目标条件。"
                :unavailable.isEmpty()?"同一条件的规则、事实及失败原因未发生实质变化，已停止重复调查；原有缺口和处理责任保持不变。"
                :"当前目标没有可执行的取证规则，或招标基础要求来源尚未确定；保留未完成缺口及系统处理责任，待相应输入变化后重算。");
    }

    static Set<String> firstRoundConsumed(String strategy,Map<String,Object> context,Map<String,Object> receipt){
        if(!"TARGETED_EVIDENCE".equals(strategy)||!"first-round-consumed-text-1".equals(text(receipt,"version"))
                ||text(receipt,"requestId").isEmpty()||text(receipt,"key").isEmpty()
                ||!text(receipt,"key").equals(text(context,"agentAdmissionKey"))
                ||!map(context.get("recoveryDependencyTrigger")).isEmpty()||!stringList(context.get("recoveryChangedSourceIds")).isEmpty()
                ||!stringList(context.get("recoveryChangedLocations")).isEmpty())return Collections.emptySet();
        return new TreeSet<>(stringList(receipt.get("targets")));
    }

    static List<Map<String,Object>> inputs(Finding finding,Map<String,Object> context,String policy) {
        List<String> targets=stringList(context.get("recoveryTargetObligationIds"));
        if(targets.isEmpty())targets=RecoveryInputs.targets(finding,"TARGETED_EVIDENCE");
        if(targets.isEmpty())targets=cn.bidcheck.compat.Java8Api.listOf(LEGACY_TARGET);
        Map<String,Object> atomic=atomic(finding);Map<String,Map<String,Object>> rules=new LinkedHashMap<>();
        index(maps(map(atomic.get("plan")).get("rules")),rules);
        Map<String,Map<String,Object>> reads=cn.bidcheck.verification.engine.RecoveryTargets.readDependencies(map(atomic.get("plan")).get("rules"));
        Map<String,Object> audit = effectiveAudit(finding);
        Set<String> remaining=new HashSet<>(stringList(audit.get("remainingTargetObligationIds")));
        if("CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority"))){
            Set<String> expanded=new TreeSet<>();
            for(String target:targets){List<String> children=new ArrayList<>();
                reads.forEach((id,read)->{if(target.equals(text(read,"parentId"))&&remaining.contains(id))children.add(id);});
                if(children.isEmpty())expanded.add(target);else expanded.addAll(children);
            }
            targets=new ArrayList<>(expanded);
        }
        Map<String,Map<String,Object>> dependencies=new HashMap<>();
        for(Map<String,Object> dependency:maps(atomic.get("recoveryEvidenceDependencies")))dependencies.put(text(dependency,"obligationId"),dependency);
        List<Map<String,Object>> result=new ArrayList<>();
        for(String targetId:targets){
            Map<String,Object> read=reads.getOrDefault(targetId,Collections.emptyMap());
            String parentId=text(read,"parentId");
            Map<String,Object> rule=rules.getOrDefault(targetId,map(read.get("rule")));
            String inventory=text(map(map(rule.get("parameters")).get("declaredPerformanceRow")),"inventoryIdentity");
            List<Map<String,Object>> bindings=new ArrayList<>();collectBindings(rule,dependencies,bindings);
            if(bindings.isEmpty()&&dependencies.containsKey(targetId))bindings.add(dependencies.get(targetId));
            Set<String> relevantFacts=new TreeSet<>();
            for(Map<String,Object> fact:maps(atomic.get("facts"))){
                List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.toList(stringList(fact.get("sourceIds")).stream().map(id->fields("id",id)));
                if(sources.isEmpty())continue;
                Map<String,Object> change=fields("itemId","semantic-input","recordType","FACT","fact",fact,"sources",sources);
                if(bindings.stream().anyMatch(binding->OrdinaryRecoveryDependencies.matches(binding,change)))relevantFacts.add(RecoveryEvidenceIdentity.key(change));
            }
            Set<String> gapCodes=new TreeSet<>(),proofFactIds=new TreeSet<>();
            List<Map<String,Object>> conditions=new ArrayList<>(maps(atomic.get("conditions")));conditions.addAll(maps(atomic.get("obligations")));
            for(Map<String,Object> condition:conditions)if(targetId.equals(text(condition,"id"))||(!parentId.isEmpty()&&parentId.equals(text(condition,"id")))){
                maps(condition.get("gaps")).forEach(g->gapCodes.add(text(g,"code")));
                if(!parentId.isEmpty()&&!inventory.isEmpty()){
                    for(Map<String,Object> row:maps(map(condition.get("proof")).get("contractChecks")))
                        if(inventory.equals(text(map(row.get("declaredRow")),"inventoryIdentity")))collectFactIds(row,proofFactIds);
                }else collectFactIds(condition.get("proof"),proofFactIds);
            }
            if(LEGACY_TARGET.equals(targetId))maps(atomic.get("gaps")).forEach(g->gapCodes.add(text(g,"code")));
            gapCodes.remove("");
            // Published findings deliberately omit the full fact corpus. Their per-condition
            // proof contains canonical, source-validated fact ids. Use those lightweight
            // dependencies instead of issuing another full shared-fact database query.
            Map<String,Object> trustedDependencies=map(atomic.get("recoveryDependencies"));
            String sourceRevision=text(context,"recoverySourceRevision"),validationIdentity=text(context,"recoveryValidationIdentity");
            if(cn.bidcheck.compat.Java8Api.isBlank(sourceRevision))sourceRevision=text(trustedDependencies,"sourceRevision");
            if(cn.bidcheck.compat.Java8Api.isBlank(validationIdentity))validationIdentity=text(trustedDependencies,"validationIdentity");
            // The source/validator identities are server-issued dependencies. Do not use
            // inputRevision or the global fact clock: raw observations change those too.
            if(!"".equals(targetId)){
                Map<String,Object> trigger=map(context.get("recoveryDependencyTrigger"));
                if(stringList(context.get("recoveryTargetObligationIds")).contains(targetId)){
                    Map<String,Object> selected=trigger.containsKey("byTarget")?map(map(trigger.get("byTarget")).get(targetId)):trigger;
                    relevantFacts.addAll(stringList(selected.get("semanticEvidenceKeys")));
                }
            }
            Map<String,Object> base=fields("policy",policy,"status",finding.evaluation().status(),"rule",ruleInput(rule),"gapCodes",gapCodes,
                "sourceRevision",sourceRevision,"validationIdentity",validationIdentity);
            Map<String,Object> priorWork=map(map(context.get("gapWorkByTarget")).get(targetId));
            String gapKey=RecoveryGapKeys.key(finding,targetId,RecoveryGapKeys.workReceipt(finding,targetId,priorWork));
            if(!gapKey.isEmpty())base.put("gapKey",gapKey);
            // A new host-validated repair path is executable input even if accepted facts did not change.
            base.put("serverRepairActions", verifiedRepairActions(audit, targetId));
            if(!parentId.isEmpty())base.put("parentRule",ruleInput(rules.getOrDefault(parentId,Collections.emptyMap())));
            String dependencyKey=hash(RecoveryEvidenceIdentity.canonical(base));
            Set<String> evidenceKeys=new TreeSet<>();relevantFacts.forEach(k->evidenceKeys.add("FACT:"+k));proofFactIds.forEach(k->evidenceKeys.add("PROOF:"+k));
            Map<String,Object> value=fields("dependencyKey",dependencyKey,"evidenceKeys",evidenceKeys);
            result.add(fields("version",VERSION,"targetId",targetId,"key",hash(RecoveryEvidenceIdentity.canonical(value)),
                "dependencyKey",dependencyKey,"evidenceKeys",cn.bidcheck.compat.Java8Api.listCopyOf(evidenceKeys),
                "relevantFactCount",Math.max(relevantFacts.size(),proofFactIds.size()),"gapCodes",cn.bidcheck.compat.Java8Api.listCopyOf(gapCodes)));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }

    /** Raw observations authorize local revalidation, never a second investigation by themselves. */
    static void applyTargets(Map<String,Object> context,Map<String,Object> receipt){
        List<String> eligible=stringList(receipt.get("eligibleTargetIds"));
        if(!eligible.isEmpty()){
            context.put("recoveryTargetObligationIds",eligible);
            Map<String,Object> trigger=map(context.get("recoveryDependencyTrigger"));
            if(trigger.containsKey("byTarget")){
                context.put("recoveryChangedSourceIds",RecoveryInputs.triggerValues(trigger,eligible,"sourceIds"));
                context.put("recoveryDependencyRevision",RecoveryInputs.dependencyKey(context));
            }
        }
    }
    private static void collectBindings(Map<String,Object> rule,Map<String,Map<String,Object>> dependencies,List<Map<String,Object>> out){
        if(rule.isEmpty())return;
        List<Map<String,Object>> children=maps(rule.get("children"));
        if(!children.isEmpty()){children.forEach(child->collectBindings(child,dependencies,out));return;}
        Map<String,Object> binding=dependencies.get(text(rule,"id"));
        out.add(binding!=null?binding:fields("target",ruleInput(rule),"resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf()));
    }
    private static Map<String,Object> ruleInput(Map<String,Object> rule){
        Map<String,Object> out=new TreeMap<>();for(String key:cn.bidcheck.compat.Java8Api.listOf("predicate", "subject", "field", "operator", "expected", "unit", "sourceQuote", "logic"))if(rule.containsKey(key))out.put(key,rule.get(key));
        Map<String,Object> parameters=new TreeMap<>(map(rule.get("parameters")));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("obligationIdentity", "ruleId", "parentRuleId", "compilationIssue", "focusedScopeIssue", "requirementContextIssue", "repairReason"))parameters.remove(key);
        out.put("parameters",parameters);out.put("children",cn.bidcheck.compat.Java8Api.toList(maps(rule.get("children")).stream().map(RecoverySemanticGate::ruleInput)));return out;
    }
    private static void index(List<Map<String,Object>> rules,Map<String,Map<String,Object>> result){for(Map<String,Object> rule:rules){result.put(text(rule,"id"),rule);index(maps(rule.get("children")),result);}}
    private static void collectFactIds(Object value,Set<String> out){
        if(value instanceof Map<?,?>){Map<String,Object> proof=map(((Map<?,?>) (value)));out.addAll(stringList(proof.get("factIds")));proof.values().forEach(v->collectFactIds(v,out));}
        else if(value instanceof Collection<?>)((Collection<?>) (value)).forEach(v->collectFactIds(v,out));
    }
    private static boolean consumed(Map<String,Object> prior,Map<String,Object> current){
        if(!text(prior,"targetId").equals(text(current,"targetId")))return false;
        if(text(prior,"key").equals(text(current,"key")))return true;
        // Dropping an old observation is not new information that warrants another call.
        return !cn.bidcheck.compat.Java8Api.isBlank(text(prior,"dependencyKey"))&&text(prior,"dependencyKey").equals(text(current,"dependencyKey"))
            &&new HashSet<>(stringList(prior.get("evidenceKeys"))).containsAll(stringList(current.get("evidenceKeys")));
    }
    private static List<String> stringList(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(v->Objects.toString(v,"")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().sorted()):cn.bidcheck.compat.Java8Api.listOf();}
}
