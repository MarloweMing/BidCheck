package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Only same-run, same-input published children can supply a dependency proof. */
final class RequirementDependencyProof {
    record Snapshot(boolean verified,List<Finding> findings,List<Checkpoint> checkpoints,List<String> issues,String revision) {}
    private RequirementDependencyProof() {}
    static Snapshot current(RuleEvaluator evaluator){
        if(!(evaluator.context.get("_requirementDependencySnapshot") instanceof Supplier<?> supplier))return invalid("未接入当前运行依赖结果");
        Object value=supplier.get();if(!(value instanceof Map<?,?> raw))return invalid("依赖结果快照无效");
        Map<String,Object> snapshot=new LinkedHashMap<>();raw.forEach((k,v)->snapshot.put(String.valueOf(k),v));
        String run=str(evaluator.context,"sourceRunId"),engine=str(evaluator.context,"requirementDependencyEngineIdentity");
        if(run.isBlank()||!run.equals(str(snapshot,"sourceRunId"))||engine.isBlank()||!engine.equals(str(snapshot,"engineIdentity"))||!Boolean.TRUE.equals(snapshot.get("verified")))return invalid("依赖结果不属于同一冻结运行与执行版本");
        List<Document> documents=new ArrayList<>(evaluator.corpus.documents);
        if(evaluator.context.get("tenderDocuments") instanceof Collection<?> docs)for(Object item:docs)if(item instanceof Document d)documents.add(d);
        Set<Map<String,Object>> expected=new HashSet<>();for(Document d:documents)expected.add(linked("id",d.id(),"version",d.version(),"sha256",d.sha256()));
        if(expected.isEmpty()||!new HashSet<>(RuleEvaluator.maps(snapshot.get("documentIdentities"))).equals(expected))return invalid("依赖结果的文档哈希或版本发生变化");
        List<Checkpoint> checkpoints=new ArrayList<>();if(snapshot.get("checkpoints") instanceof Collection<?> rows)for(Object item:rows)if(item instanceof Checkpoint cp)checkpoints.add(cp);
        Map<String,Checkpoint> byId=new HashMap<>();checkpoints.forEach(cp->byId.put(cp.id(),cp));List<Finding> findings=new ArrayList<>();
        if(snapshot.get("findings") instanceof Collection<?> rows)for(Object item:rows)if(item instanceof Finding f&&run.equals(f.runId())&&f.checkpoint().equals(byId.get(f.checkpoint().id()))){
            if(!SourceObligationDirectory.checkpointSourceCurrent(f.checkpoint(),documents))continue;
            if(f.assessment()==null||!(f.assessment().get("atomicCheck") instanceof Map<?,?> atomic))continue;
            if(!new HashSet<>(RuleEvaluator.maps(atomic.get("documentIdentities"))).equals(expected))continue;
            if(Set.of("PASS","NOT_APPLICABLE").contains(f.evaluation().status())&&(!Boolean.TRUE.equals(atomic.get("complete"))||!RuleEvaluator.maps(atomic.get("gaps")).isEmpty()))continue;
            if("PASS".equals(f.evaluation().status())&&!currentPassProof(f,atomic,documents,evaluator))continue;
            findings.add(f);
        }
        return new Snapshot(true,List.copyOf(findings),List.copyOf(checkpoints),List.of(),str(snapshot,"revision"));
    }
    /** A parent's proof changes only with its selected child receipts, never with an unrelated run revision. */
    static String relevantRevision(Snapshot snapshot,Collection<String> checkpointIds){
        if(!snapshot.verified())return "";
        Set<String> wanted=new HashSet<>(checkpointIds);List<String> rows=new ArrayList<>();
        for(Finding finding:snapshot.findings())if(wanted.contains(finding.checkpoint().id())){
            Map<?,?> atomic=(Map<?,?>)finding.assessment().get("atomicCheck");
            rows.add(finding.checkpoint().id()+"|"+finding.evaluation().status()+"|"+finding.evaluation().evidences()+"|"
                    +atomic.get("gaps")+"|"+atomic.get("conditions")+"|"+atomic.get("documentIdentities"));
        }
        Collections.sort(rows);return identity(SourceObligationDirectory.VERSION+"|"+String.join("\n",rows));
    }
    /** A complete flag is an execution receipt, not a substitute for a proved business condition. */
    private static boolean currentPassProof(Finding finding,Map<?,?> atomic,List<Document> documents,RuleEvaluator evaluator){
        List<Evidence> evidence=finding.evaluation().evidences();
        if(evidence==null||evidence.isEmpty()||evidence.stream().anyMatch(ref->!currentSource(ref,documents,evaluator)))return false;
        List<Map<String,Object>> conditions=RuleEvaluator.maps(atomic.get("conditions"));
        return !conditions.isEmpty()&&conditions.stream().allMatch(condition->Set.of("PASS","NOT_APPLICABLE").contains(str(condition,"status"))
            &&RuleEvaluator.maps(condition.get("gaps")).isEmpty());
    }
    private static boolean currentSource(Evidence ref,List<Document> documents,RuleEvaluator evaluator){
        if(ref==null||ref.quote()==null||ref.quote().isBlank())return false;
        if(Objects.toString(ref.anchor(),"").startsWith("image:")){
            if(evaluator.corpus.byId.values().stream().anyMatch(s->s.evidence().equals(ref)))return true;
            for(Map<String,Object> observation:RuleEvaluator.maps(evaluator.context.get("visualObservations"))){
                if(!cn.bidcheck.verification.source.VisualObservationPolicy.assess(observation).valueUsable()
                    ||!cn.bidcheck.verification.source.VisualObservationPolicy.validSourceBounds(observation)
                    ||!"FIELD".equals(str(observation,"type"))||!(observation.get("source") instanceof Map<?,?> origin))continue;
                String image=str(observation,"imageHash"),value=str(observation,"value");if(value.isBlank())value=str(observation,"observedValue");
                if(image.isBlank()||!image.equals(origin.get("renderedSha256"))||!(ref.anchor().equals("image:"+image)||ref.anchor().startsWith("image:"+image+":"))
                    ||!ref.documentId().equals(str(observation,"documentId"))||!ref.locationType().equals(str(observation,"locationType"))
                    ||ref.page()!=RunSession.number(observation.get("location"),RunSession.number(observation.get("page"),-1))
                    ||!(ref.quote().equals(value)||ref.quote().equals(str(observation,"label")+"："+value)))continue;
                if(documents.stream().anyMatch(d->d.id().equals(ref.documentId())&&d.id().equals(origin.get("documentId"))
                    &&d.sha256().equals(origin.get("documentHash"))&&RunSession.number(origin.get("documentVersion"),-1)==d.version()
                    &&RunSession.number(origin.get("page"),-1)==ref.page()
                    &&d.pages().stream().anyMatch(p->p.number()==ref.page()&&p.locationType().equals(ref.locationType()))))return true;
            }
            return false;
        }
        return documents.stream().filter(d->d.id().equals(ref.documentId())).flatMap(d->d.pages().stream())
            .anyMatch(page->page.number()==ref.page()&&page.locationType().equals(ref.locationType())&&Objects.toString(page.text(),"").contains(ref.quote()));
    }
    private static Snapshot invalid(String issue){return new Snapshot(false,List.of(),List.of(),List.of(issue),"");}
}
