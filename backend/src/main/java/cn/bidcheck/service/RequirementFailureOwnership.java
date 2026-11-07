package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.verification.engine.SourceRequirementInventory;
import java.util.*;
import static cn.bidcheck.service.BidService.fields;
import static cn.bidcheck.service.BidService.text;
import static cn.bidcheck.service.CheckResolution.*;

/** A dependency's diagnostic stays visible, but cannot impersonate this item's failed request. */
final class RequirementFailureOwnership {
    private RequirementFailureOwnership(){}

    static List<Map<String,Object>> localGaps(Finding finding){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> gap:maps(atomic(finding).get("gaps")))if(!inherited(finding,gap))result.add(gap);
        return result;
    }

    static List<String> failedDependencies(Finding finding){
        Set<String> ids=new TreeSet<>();
        for(Map<String,Object> gap:maps(atomic(finding).get("gaps")))if(processingCode(text(gap,"code")))
            for(Map<String,Object> proof:proofs(finding))for(Map<String,Object> origin:origins(finding,proof))
                if(sameGap(gap,map(origin.get("gap"))))ids.add(text(origin,"sourceCheckpointId"));
        return new ArrayList<>(ids);
    }

    static boolean inherited(Finding finding,Map<String,Object> gap){
        if(!processingCode(text(gap,"code")))return false;
        // An independent local condition with the same diagnostic still owns its error.
        for(Map<String,Object> condition:maps(atomic(finding).get("conditions"))){
            if(maps(condition.get("gaps")).stream().noneMatch(g->sameGap(g,gap)))continue;
            if(origins(finding,map(condition.get("proof"))).stream().noneMatch(o->sameGap(map(o.get("gap")),gap)))return false;
        }
        return proofs(finding).stream().flatMap(p->origins(finding,p).stream())
            .anyMatch(o->sameGap(map(o.get("gap")),gap));
    }

    static boolean processingCode(String code){
        return code.startsWith("MODEL_")||code.startsWith("HTTP_")||Arrays.asList("AGENT_INVESTIGATION_FAILED",
            "AGENT_INVESTIGATION_STALLED","REQUEST_TIMEOUT","OUTPUT_SCHEMA_INVALID","OUTPUT_JSON_INVALID",
            "FACT_EXTRACTION_UNAVAILABLE","ENGINE_FAILURE").contains(code);
    }

    private static List<Map<String,Object>> proofs(Finding finding){
        List<Map<String,Object>> out=new ArrayList<>();out.add(map(atomic(finding).get("proof")));
        for(Map<String,Object> condition:maps(atomic(finding).get("conditions")))out.add(map(condition.get("proof")));
        return out;
    }

    private static List<Map<String,Object>> origins(Finding finding,Map<String,Object> proof){
        Map<String,Object> receipt=map(proof.get("dependencyGapOwnership"));
        if(!SourceRequirementInventory.VERSION.equals(text(proof,"executor"))
                ||!SourceRequirementInventory.FAILURE_OWNERSHIP_VERSION.equals(text(receipt,"version"))
                ||!"CURRENT_SERVER_PROOF".equals(text(receipt,"authority"))||!Boolean.FALSE.equals(receipt.get("businessProof"))
                ||!finding.checkpoint().id().equals(text(receipt,"ownerCheckpointId"))||!finding.runId().equals(text(receipt,"runId")))return Collections.emptyList();
        Set<String> dependencies=new HashSet<>(RecoveryGapKeys.strings(proof.get("dependencyCheckpointIds")));
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> origin:maps(receipt.get("origins"))){
            String source=text(origin,"sourceCheckpointId");
            if(source.isEmpty()||source.equals(finding.checkpoint().id())||!dependencies.contains(source)
                    ||text(origin,"sourceFindingId").isEmpty()||!finding.runId().equals(text(origin,"sourceRunId")))continue;
            boolean linked=maps(proof.get("children")).stream().anyMatch(child->{
                Map<String,Object> childProof=map(child.get("proof"));
                return source.equals(text(child,"id"))&&source.equals(text(childProof,"sourceCheckpointId"))
                    &&text(origin,"sourceFindingId").equals(text(childProof,"sourceFindingId"))
                    &&finding.runId().equals(text(childProof,"runId"))
                    &&maps(child.get("gaps")).stream().anyMatch(g->sameGap(g,map(origin.get("gap"))));
            });
            if(linked)result.add(origin);
        }
        return result;
    }

    private static boolean sameGap(Map<String,Object> first,Map<String,Object> second){
        return !text(first,"code").isEmpty()&&Arrays.asList("code","description","requiredInput","resumeTrigger")
            .stream().allMatch(k->text(first,k).equals(text(second,k)));
    }
}
