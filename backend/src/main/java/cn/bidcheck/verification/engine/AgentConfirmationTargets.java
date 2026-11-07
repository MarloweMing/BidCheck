package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A waiting target is still business-unclosed. Only current, cited server candidates may pause acquisition. */
final class AgentConfirmationTargets {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AgentConfirmationTargets(){}
    static Map<String,Object> proof(Rule target,Decision decision,RuleEvaluator evaluator){
        if(decision==null||decision.gaps().isEmpty())return Collections.emptyMap();
        if(SourceScoringRules.professionReviewRequired(decision)
                &&decision.id().equals(str(target.parameters(),"scoringParentRuleId"))
                &&SourceScoringRules.VERSION.equals(str(target.parameters(),"sourceScoringReadVersion"))) {
            Map<String,Object> review=map(decision.proof().get("professionScopeReview"));
            List<String> originals=ProofVerifier.strings(review.get("sourceIds")), appointments=ProofVerifier.strings(review.get("appointmentSourceIds"));
            Set<String> ids=new LinkedHashSet<>(originals);ids.addAll(appointments);
            if(!originals.isEmpty()&&!appointments.isEmpty()&&!str(review,"question").isEmpty()
                    &&ids.stream().allMatch(evaluator.corpus.byId::containsKey))
                return linked("authority","SOURCE_BOUND_SCORING_CRITERION_REVIEW","targetObligationId",target.id(),
                    "conditionStatus","UNDETERMINED","gapCodes",Collections.singletonList("SCORING_TITLE_PROFESSION_SCOPE_REQUIRED"),
                    "question",review.get("question"),"sourceIds",new ArrayList<>(ids),"targetFingerprint",identity(JSON.valueToTree(target).toString()),
                    "businessProof",false,"sourceValidation","CURRENT_DOCUMENT_CREDENTIAL_AND_APPOINTMENT");
        }
        boolean legacy="NEEDS_CONFIRMATION".equals(decision.status())&&decision.gaps().stream().allMatch(g->"INFERENCE_CONFIRMATION_REQUIRED".equals(g.code()));
        Set<String> gapCodes=new LinkedHashSet<>();decision.gaps().forEach(g->gapCodes.add(g.code()));
        if(!legacy&&(!"UNDETERMINED".equals(decision.status())||!gapCodes.equals(new HashSet<>(ProofVerifier.strings(decision.proof().get("confirmationOnlyGapCodes"))))))return Collections.emptyMap();
        List<Map<String,Object>> supplied=RuleEvaluator.maps(decision.proof().get("inferredFacts"));
        List<Map<String,Object>> current=RuleEvaluator.maps(evaluator.context.get("inferredFacts"));
        String fingerprint=identity(JSON.valueToTree(target).toString());List<String> claims=new ArrayList<>();Set<String> sources=new LinkedHashSet<>();
        for(Map<String,Object> claim:supplied){
            if(!target.id().equals(str(claim,"targetId"))||!fingerprint.equals(str(claim,"targetFingerprint"))
                ||!"NEEDS_CONFIRMATION".equals(str(claim,"status"))||cn.bidcheck.compat.Java8Api.isBlank(str(claim,"id"))
                ||current.stream().noneMatch(c->c.equals(claim)))continue;
            List<Map<String,Object>> evidence=RuleEvaluator.maps(claim.get("evidences"));if(evidence.isEmpty())continue;
            Set<String> refs=new LinkedHashSet<>();boolean valid=true;
            for(Map<String,Object> e:evidence){Source source=evaluator.corpus.byId.get(str(e,"sourceId"));String quote=str(e,"quote");
                if(source==null||cn.bidcheck.compat.Java8Api.isBlank(quote)||source.index()||source.scopeContext().blocked()
                    ||!source.evidence().quote().contains(quote)||!source.evidence().documentId().equals(str(e,"documentId"))
                    ||!source.documentHash().equals(str(e,"documentHash"))||!(e.get("documentVersion") instanceof Number)||source.documentVersion()!=((Number) (e.get("documentVersion"))).intValue()
                    ||!(e.get("page") instanceof Number)||source.evidence().page()!=((Number) (e.get("page"))).intValue()
                    ||!source.evidence().locationType().equals(str(e,"locationType"))){valid=false;break;}
                refs.add(source.id());
            }
            Map<String,Object> proposed=map(claim.get("proposedFact"));
            if(!refs.equals(new LinkedHashSet<>(ProofVerifier.strings(proposed.get("sourceIds")))))valid=false;
            if(valid){claims.add(str(claim,"id"));sources.addAll(refs);}
        }
        if(claims.isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("authority","SOURCE_VALIDATED_PENDING_CONFIRMATION","targetObligationId",target.id(),"conditionStatus","NEEDS_CONFIRMATION",
            "gapCodes",Collections.singletonList("INFERENCE_CONFIRMATION_REQUIRED"),"businessGapCodes",new ArrayList<>(gapCodes),"claimIds",cn.bidcheck.compat.Java8Api.toList(claims.stream().distinct()),"sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(sources),
            "targetFingerprint",fingerprint,"businessProof",false,"sourceValidation","CURRENT_DOCUMENT_HASH_VERSION_PAGE_AND_EXACT_QUOTE");
    }
    private static Map<String,Object> map(Object raw){Map<String,Object> out=new LinkedHashMap<>();if(raw instanceof Map<?,?>)((Map<?,?>) (raw)).forEach((k,v)->out.put(String.valueOf(k),v));return out;}
}
