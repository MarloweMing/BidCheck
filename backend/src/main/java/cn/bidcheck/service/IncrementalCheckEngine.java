package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.engine.*;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Re-evaluates only rules dependent on new evidence. Preliminary outputs never certify whole-document coverage. */
public final class IncrementalCheckEngine {
    public static final String VERSION="incremental-check-1";
    private final CheckEngine rules=new CheckEngine();
    public boolean affected(Checkpoint cp, Page changed, List<Fact> before, List<Fact> after) {
        if(!cp.enabled()||!cp.confirmed())return false;
        if(cn.bidcheck.compat.Java8Api.setOf("VALUE_COMPARE", "DATE_VALIDITY", "ENTITY_FIELD_CONSISTENCY").contains(Objects.toString(cp.type(),""))) {
            String field="DATE_VALIDITY".equals(cp.type())?"certificateExpiry":cp.field();
            return java.util.stream.Stream.concat(before.stream(),after.stream()).anyMatch(f->Objects.equals(field,f.field())
                ||"ENTITY_FIELD_CONSISTENCY".equals(cp.type())&&"plateNumber".equals(cp.field())&&"VEHICLE".equals(f.entityType()));
        }
        if("MATERIAL_EXISTENCE".equals(cp.type()))return cp.expected()!=null&&!cn.bidcheck.compat.Java8Api.isBlank(cp.expected())&&Objects.toString(changed.text(),"").contains(cp.expected());
        return false; // Semantic, visual and exhaustive requirements retain explicit dependencies until the final run.
    }
    public Map<String,Object> evaluate(Checkpoint cp,Document partial,List<Fact> facts,boolean parsingComplete) {
        boolean supported=cn.bidcheck.compat.Java8Api.setOf("VALUE_COMPARE", "DATE_VALIDITY", "ENTITY_FIELD_CONSISTENCY", "MATERIAL_EXISTENCE").contains(Objects.toString(cp.type(),""));
        Evaluation candidate=supported?OcrReviewPolicy.apply(rules.evaluate(cp,partial,facts),cp,partial,null):null;
        List<Evidence> evidence=candidate==null||candidate.evidences()==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(candidate.evidences().stream()
            .filter(e->partial.id().equals(e.documentId())&&e.quote()!=null&&!cn.bidcheck.compat.Java8Api.isBlank(e.quote())));
        boolean grounded=!evidence.isEmpty();
        boolean risk=grounded&&cn.bidcheck.compat.Java8Api.setOf("FAIL", "WARNING").contains(candidate.status());
        String state=risk?"REVIEW_REQUIRED":grounded?"EVIDENCE_FOUND":supported?"WAITING_EVIDENCE":"WAITING_ANALYSIS";
        // Even a material FAIL with citations may be an absence/count conclusion over an incomplete scope.
        if("MATERIAL_EXISTENCE".equals(cp.type())&&candidate!=null&&"FAIL".equals(candidate.status()))state="WAITING_EVIDENCE";
        String reason=state.equals("REVIEW_REQUIRED")?candidate.reason():state.equals("EVIDENCE_FOUND")
            ?"已找到可追溯证据；后续页面到达后继续更新，完整结论以正式检查为准。"
            :supported?"等待更多相关证据；未读到不等于材料缺失。":"等待语义、原图或完整范围核验。";
        return fields("checkpointId",cp.id(),"title",cp.title(),"severity",cp.severity(),"type",cp.type(),"field",cp.field(),
            "state",state,"businessDecision","UNDETERMINED","provisional",true,"parsingComplete",parsingComplete,
            "reason",reason,"evidences",evidence,"sourcePages",cn.bidcheck.compat.Java8Api.toList(evidence.stream().map(Evidence::page).distinct().sorted()),
            "updatedAt",now(),"engineVersion",VERSION);
    }
    public static int priority(Checkpoint cp){return cn.bidcheck.compat.Java8Api.setOf("HIGH", "CRITICAL", "高", "废标").contains(Objects.toString(cp.severity(),""))?0:1;}
    /** A second extraction of the same page is not a second independent occurrence for consistency checks. */
    public static List<Fact> distinctEvidence(List<Fact> input){
        Map<List<Object>,Fact> out=new LinkedHashMap<>();for(Fact f:input){Evidence e=f.evidence();if(e==null)continue;
            out.putIfAbsent(Arrays.asList(f.entityType(),f.entityKey(),f.field(),f.normalizedValue(),e.documentId(),e.locationType(),e.page()),f);
        }return cn.bidcheck.compat.Java8Api.listCopyOf(out.values());
    }
}
