package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Validates navigation actions from server-authored proof against the frozen corpus. */
final class RequiredEvidenceReads {
    static List<Map<String,Object>> actions(List<Decision> decisions,Set<String> remaining,EvidenceCorpus corpus){
        List<Map<String,Object>> collected=new ArrayList<>();
        for(Decision decision:decisions)if(!decision.gaps().isEmpty())collect(decision.proof(),collected,0);
        return collected.stream().filter(action->{
            Source source=corpus.byId.get(str(action,"sourceId"));
            return "read_table_record".equals(str(action,"tool"))&&remaining.contains(str(action,"targetId"))
                &&source!=null&&!source.id().startsWith("V")&&source.evidence().documentId().equals(str(action,"documentId"))
                &&source.documentHash().equals(str(action,"documentHash"))&&source.documentVersion()==RunSession.number(action.get("documentVersion"),-1)
                &&source.evidence().page()==RunSession.number(action.get("page"),-1)&&source.evidence().locationType().equals(str(action,"locationType"));
        }).distinct().toList();
    }
    private static void collect(Object value,List<Map<String,Object>> out,int depth){
        if(depth>32)return;
        if(value instanceof Map<?,?> map)for(var entry:map.entrySet()){
            if("requiredEvidenceActions".equals(entry.getKey()))out.addAll(RuleEvaluator.maps(entry.getValue()));
            else if(entry.getValue() instanceof Map<?,?>||entry.getValue() instanceof Collection<?>)collect(entry.getValue(),out,depth+1);
        }else if(value instanceof Collection<?> items)items.forEach(item->collect(item,out,depth+1));
    }
    private RequiredEvidenceReads() {}
}
