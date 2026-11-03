package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Only same-run, same-input published children can supply a dependency proof. */
final class RequirementDependencyProof {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Snapshot {
        private final boolean verified;
        private final List<Finding> findings;
        private final List<Checkpoint> checkpoints;
        private final List<String> issues;
        private final String revision;
        @com.fasterxml.jackson.annotation.JsonCreator
        Snapshot(@com.fasterxml.jackson.annotation.JsonProperty("verified") boolean verified, @com.fasterxml.jackson.annotation.JsonProperty("findings") List<Finding> findings, @com.fasterxml.jackson.annotation.JsonProperty("checkpoints") List<Checkpoint> checkpoints, @com.fasterxml.jackson.annotation.JsonProperty("issues") List<String> issues, @com.fasterxml.jackson.annotation.JsonProperty("revision") String revision) { this.verified = verified; this.findings = findings; this.checkpoints = checkpoints; this.issues = issues; this.revision = revision; }
        public boolean verified() { return verified; }
        public List<Finding> findings() { return findings; }
        public List<Checkpoint> checkpoints() { return checkpoints; }
        public List<String> issues() { return issues; }
        public String revision() { return revision; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Snapshot that = (Snapshot) other; return java.util.Objects.equals(this.verified, that.verified) && java.util.Objects.equals(this.findings, that.findings) && java.util.Objects.equals(this.checkpoints, that.checkpoints) && java.util.Objects.equals(this.issues, that.issues) && java.util.Objects.equals(this.revision, that.revision); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.verified); hash = 31 * hash + java.util.Objects.hashCode(this.findings); hash = 31 * hash + java.util.Objects.hashCode(this.checkpoints); hash = 31 * hash + java.util.Objects.hashCode(this.issues); hash = 31 * hash + java.util.Objects.hashCode(this.revision); return hash; }
        @Override public String toString() { return "Snapshot[" + "verified=" + verified + ", findings=" + findings + ", checkpoints=" + checkpoints + ", issues=" + issues + ", revision=" + revision + "]"; }
}
    private RequirementDependencyProof() {}
    static Snapshot current(RuleEvaluator evaluator){
        if(!(evaluator.context.get("_requirementDependencySnapshot") instanceof Supplier<?>))return invalid("未接入当前运行依赖结果");
        Object value=((Supplier<?>) (evaluator.context.get("_requirementDependencySnapshot"))).get();if(!(value instanceof Map<?,?>))return invalid("依赖结果快照无效");
        Map<String,Object> snapshot=new LinkedHashMap<>();((Map<?,?>) (value)).forEach((k,v)->snapshot.put(String.valueOf(k),v));
        String run=str(evaluator.context,"sourceRunId"),engine=str(evaluator.context,"requirementDependencyEngineIdentity");
        if(cn.bidcheck.compat.Java8Api.isBlank(run)||!run.equals(str(snapshot,"sourceRunId"))||cn.bidcheck.compat.Java8Api.isBlank(engine)||!engine.equals(str(snapshot,"engineIdentity"))||!Boolean.TRUE.equals(snapshot.get("verified")))return invalid("依赖结果不属于同一冻结运行与执行版本");
        List<Document> documents=new ArrayList<>(evaluator.corpus.documents);
        if(evaluator.context.get("tenderDocuments") instanceof Collection<?>)for(Object item:((Collection<?>) (evaluator.context.get("tenderDocuments"))))if(item instanceof Document)documents.add(((Document) (item)));
        Set<Map<String,Object>> expected=new HashSet<>();for(Document d:documents)expected.add(linked("id",d.id(),"version",d.version(),"sha256",d.sha256()));
        if(expected.isEmpty()||!new HashSet<>(RuleEvaluator.maps(snapshot.get("documentIdentities"))).equals(expected))return invalid("依赖结果的文档哈希或版本发生变化");
        List<Checkpoint> checkpoints=new ArrayList<>();if(snapshot.get("checkpoints") instanceof Collection<?>)for(Object item:((Collection<?>) (snapshot.get("checkpoints"))))if(item instanceof Checkpoint)checkpoints.add(((Checkpoint) (item)));
        Map<String,Checkpoint> byId=new HashMap<>();checkpoints.forEach(cp->byId.put(cp.id(),cp));List<Finding> findings=new ArrayList<>();
        if(snapshot.get("findings") instanceof Collection<?>)for(Object item:((Collection<?>) (snapshot.get("findings"))))if(item instanceof Finding&&run.equals(((Finding) (item)).runId())&&((Finding) (item)).checkpoint().equals(byId.get(((Finding) (item)).checkpoint().id()))){
            if(!SourceObligationDirectory.checkpointSourceCurrent(((Finding) (item)).checkpoint(),documents))continue;
            if(((Finding) (item)).assessment()==null||!(((Finding) (item)).assessment().get("atomicCheck") instanceof Map<?,?>))continue;
            if(!new HashSet<>(RuleEvaluator.maps(((Map<?,?>) (((Finding) (item)).assessment().get("atomicCheck"))).get("documentIdentities"))).equals(expected))continue;
            if(cn.bidcheck.compat.Java8Api.setOf("PASS", "NOT_APPLICABLE").contains(((Finding) (item)).evaluation().status())&&(!Boolean.TRUE.equals(((Map<?,?>) (((Finding) (item)).assessment().get("atomicCheck"))).get("complete"))||!RuleEvaluator.maps(((Map<?,?>) (((Finding) (item)).assessment().get("atomicCheck"))).get("gaps")).isEmpty()))continue;
            if("PASS".equals(((Finding) (item)).evaluation().status())&&!currentPassProof(((Finding) (item)),((Map<?,?>) (((Finding) (item)).assessment().get("atomicCheck"))),documents,evaluator))continue;
            findings.add(((Finding) (item)));
        }
        return new Snapshot(true,cn.bidcheck.compat.Java8Api.listCopyOf(findings),cn.bidcheck.compat.Java8Api.listCopyOf(checkpoints),cn.bidcheck.compat.Java8Api.listOf(),str(snapshot,"revision"));
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
        return !conditions.isEmpty()&&conditions.stream().allMatch(condition->cn.bidcheck.compat.Java8Api.setOf("PASS", "NOT_APPLICABLE").contains(str(condition,"status"))
            &&RuleEvaluator.maps(condition.get("gaps")).isEmpty());
    }
    private static boolean currentSource(Evidence ref,List<Document> documents,RuleEvaluator evaluator){
        if(ref==null||ref.quote()==null||cn.bidcheck.compat.Java8Api.isBlank(ref.quote()))return false;
        if(Objects.toString(ref.anchor(),"").startsWith("image:")){
            if(evaluator.corpus.byId.values().stream().anyMatch(s->s.evidence().equals(ref)))return true;
            for(Map<String,Object> observation:RuleEvaluator.maps(evaluator.context.get("visualObservations"))){
                if(!cn.bidcheck.verification.source.VisualObservationPolicy.assess(observation).valueUsable()
                    ||!cn.bidcheck.verification.source.VisualObservationPolicy.validSourceBounds(observation)
                    ||!"FIELD".equals(str(observation,"type"))||!(observation.get("source") instanceof Map<?,?>))continue;
                String image=str(observation,"imageHash"),value=str(observation,"value");if(cn.bidcheck.compat.Java8Api.isBlank(value))value=str(observation,"observedValue");
                if(cn.bidcheck.compat.Java8Api.isBlank(image)||!image.equals(((Map<?,?>) (observation.get("source"))).get("renderedSha256"))||!(ref.anchor().equals("image:"+image)||ref.anchor().startsWith("image:"+image+":"))
                    ||!ref.documentId().equals(str(observation,"documentId"))||!ref.locationType().equals(str(observation,"locationType"))
                    ||ref.page()!=RunSession.number(observation.get("location"),RunSession.number(observation.get("page"),-1))
                    ||!(ref.quote().equals(value)||ref.quote().equals(str(observation,"label")+"："+value)))continue;
                if(documents.stream().anyMatch(d->d.id().equals(ref.documentId())&&d.id().equals(((Map<?,?>) (observation.get("source"))).get("documentId"))
                    &&d.sha256().equals(((Map<?,?>) (observation.get("source"))).get("documentHash"))&&RunSession.number(((Map<?,?>) (observation.get("source"))).get("documentVersion"),-1)==d.version()
                    &&RunSession.number(((Map<?,?>) (observation.get("source"))).get("page"),-1)==ref.page()
                    &&d.pages().stream().anyMatch(p->p.number()==ref.page()&&p.locationType().equals(ref.locationType()))))return true;
            }
            return false;
        }
        return documents.stream().filter(d->d.id().equals(ref.documentId())).flatMap(d->d.pages().stream())
            .anyMatch(page->page.number()==ref.page()&&page.locationType().equals(ref.locationType())&&Objects.toString(page.text(),"").contains(ref.quote()));
    }
    private static Snapshot invalid(String issue){return new Snapshot(false,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(issue),"");}
}
