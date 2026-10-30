package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Reuses only the server-loaded prior-step channel and the same version/model/status/
 * input fingerprint boundary as RunSession.prior. These are replay candidates, not
 * business receipts: the caller must revalidate the protocol and frozen sources. */
final class AgentReviewState {
    static final String PLAN="AGENT_REVIEW_PLAN_APPROVED";
    static final String JUDGMENT="AGENT_REVIEW_JUDGMENT_ACCEPTED";
    private AgentReviewState(){}

    static Map<String,Object> findApprovedPlan(RunSession session,String requirementFingerprint,String planRevision){
        if(requirementFingerprint==null||requirementFingerprint.isBlank()||planRevision==null||planRevision.isBlank())return Map.of();
        String fingerprint=session.fingerprint(PLAN,Map.of("planRevision",planRevision));
        List<Map<String,Object>> prior=prior(session);
        for(int i=prior.size()-1;i>=0;i--){Map<String,Object> step=prior.get(i);
            if(!current(session,step,PLAN,fingerprint))continue;
            Map<String,Object> output=output(session,step);
            if(requirementFingerprint.equals(output.get("requirementFingerprint")))return output;
        }
        return Map.of();
    }

    static List<Map<String,Object>> acceptedJudgments(RunSession session,String planRevision){
        if(planRevision==null||planRevision.isBlank())return List.of();
        List<Map<String,Object>> accepted=new ArrayList<>();
        for(Map<String,Object> step:prior(session)){
            Map<String,Object> output=output(session,step);String target=str(output,"targetObligationId");
            if(target.isBlank()||!planRevision.equals(output.get("planRevision")))continue;
            String fingerprint=session.fingerprint(JUDGMENT,Map.of("targetObligationId",target,"planRevision",planRevision));
            if(current(session,step,JUDGMENT,fingerprint))accepted.add(output);
        }
        // Keep all eligible revisions in persistence encounter order. Only the caller
        // knows which exact evidence dependency revision is still valid today.
        return List.copyOf(accepted);
    }

    private static List<Map<String,Object>> prior(RunSession session){
        // previousAtomicCheck/proof is a result projection, never an authorization source.
        return RuleEvaluator.maps(session.context.get("priorSteps"));
    }
    private static boolean current(RunSession session,Map<String,Object> step,String type,String fingerprint){
        return type.equals(step.get("type"))&&"COMPLETED".equals(step.get("status"))
            &&ExtractionContracts.stepSchema(type).equals(step.get("schemaVersion"))
            &&session.model.identity().equals(step.get("modelIdentity"))
            &&fingerprint.equals(step.get("inputFingerprint"))&&step.get("output") instanceof Map<?,?>;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> output(RunSession session,Map<String,Object> step){
        return step.get("output") instanceof Map<?,?> value?session.json.convertValue(value,Map.class):Map.of();
    }
}
