package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

/** Re-evaluates a complete original occurrence set; it does not overwrite the immutable scan or human review. */
public final class ConsistencyDerivedConclusion {
    public static final String VERSION="consistency-derived-4-verified-peer-positions";
    public static String identity(){return VERSION+"|"+ConsistencyObjectRecovery.VERSION;}
    private final Document document;
    private final Map<String,Map<String,Object>> occurrences=new LinkedHashMap<>();
    private final Set<String> duplicatedIds=new HashSet<>();
    private final ConsistencyObjectRecovery recovery;

    ConsistencyDerivedConclusion(Document document,Collection<Map<String,Object>> sourceOccurrences){
        this(document,sourceOccurrences,cn.bidcheck.compat.Java8Api.listOf());
    }
    ConsistencyDerivedConclusion(Document document,Collection<Map<String,Object>> sourceOccurrences,List<Map<String,Object>> confirmedRelations){
        this.document=document;
        for(java.util.Map<java.lang.String, java.lang.Object> occurrence:sourceOccurrences){String id=string(occurrence,"id");if(occurrences.putIfAbsent(id,occurrence)!=null)duplicatedIds.add(id);}
        recovery=new ConsistencyObjectRecovery(document,occurrences.values(),confirmedRelations);
    }
    /** Pure local revalidation, suitable both after retrieval and when consuming a frozen current-run report. */
    public static Map<String,Evaluation> recompute(Document document,Map<String,Object> report){
        Objects.requireNonNull(document,"document");Objects.requireNonNull(report,"report");
        String reportDocument=string(report,"documentId"),reportHash=string(report,"documentHash");
        if(!cn.bidcheck.compat.Java8Api.isBlank(reportDocument)&&!reportDocument.equals(document.id())||!cn.bidcheck.compat.Java8Api.isBlank(reportHash)&&!reportHash.equals(document.sha256()))
            throw new IllegalArgumentException("CONSISTENCY_SOURCE_MANIFEST_MISMATCH");
        ConsistencyDerivedConclusion evaluator=new ConsistencyDerivedConclusion(document,maps(report.get("occurrences")),maps(report.get("confirmedEvidenceRelations")));
        Map<String,Evaluation> results=new LinkedHashMap<>();
        for(java.util.Map<java.lang.String, java.lang.Object> group:maps(report.get("groups")))evaluator.evaluate(group).ifPresent(result->results.put(string(group,"id"),result));
        return Collections.unmodifiableMap(results);
    }
    Optional<Evaluation> evaluate(Map<String,Object> group){
        // Existing explicit conflicts/reviewer decisions are not silently replaced by this recovery path.
        if(!"EVIDENCE_INSUFFICIENT".equals(group.get("status"))&&map(map(group.get("targetedReview")).get("objectRecovery")).isEmpty())return Optional.empty();
        List<String> requested=strings(group.get("occurrenceIds"));
        Map<String,Object> plan=recovery.plan(group,occurrences);
        List<Map<String,Object>> decisions=new ArrayList<>();LinkedHashSet<String> resolved=new LinkedHashSet<>(),unresolved=new LinkedHashSet<>(),excluded=new LinkedHashSet<>();
        List<Evidence> evidence=new ArrayList<>();List<String> failures=new ArrayList<>();
        if(requested.isEmpty())failures.add("ORIGINAL_OCCURRENCE_SET_EMPTY");
        if(new HashSet<>(requested).size()!=requested.size())failures.add("ORIGINAL_OCCURRENCE_IDS_DUPLICATED");
        for(String id:requested)if(!occurrences.containsKey(id)||duplicatedIds.contains(id)){unresolved.add(id);failures.add("ORIGINAL_OCCURRENCE_MISSING_OR_AMBIGUOUS");}
        List<Evidence> candidates=new ArrayList<>();
        for(String id:requested){java.util.Map<java.lang.String, java.lang.Object> o=occurrences.get(id);if(o!=null){addEvidence(candidates,o.get("evidence"));addEvidences(candidates,o.get("contextEvidences"));}}
        Map<String,Object> review=map(group.get("targetedReview"));addEvidences(candidates,review.get("evidences"));
        for(java.util.Map<java.lang.String, java.lang.Object> oldObject:maps(map(review.get("objectRecovery")).get("objects")))addEvidences(candidates,oldObject.get("evidences"));
        int singleValues=0,comparedPairs=0,conflicts=0;
        for(java.util.Map<java.lang.String, java.lang.Object> object:maps(plan.get("objects"))){
            List<String> ids=strings(object.get("originalOccurrenceIds"));
            List<Evidence> local=new ArrayList<>(candidates);addEvidences(local,object.get("evidences"));
            Map<String,Object> relation=recovery.verifyRelations(object,ConsistencyObjectRecovery.unique(local));
            Set<String> values=new LinkedHashSet<>(strings(object.get("values")));values.remove("");
            List<String> comparisonIds=strings(object.get("occurrenceIds"));
            boolean sourceComplete=!comparisonIds.isEmpty()&&comparisonIds.containsAll(ids)&&comparisonIds.stream().allMatch(id->!duplicatedIds.contains(id)&&validOccurrence(occurrences.get(id))
                &&Objects.equals(group.get("field"),occurrences.get(id).get("field")));
            boolean verified=sourceComplete&&Boolean.TRUE.equals(object.get("identityVerified"))&&Boolean.TRUE.equals(relation.get("contextResolved"))&&!values.isEmpty();
            String state;
            if(sourceComplete&&Boolean.TRUE.equals(relation.get("exclusionValidated"))){state="EXCLUDED_WITH_SOURCE_PROOF";excluded.addAll(ids);}
            else if("RELATION_CONFLICT".equals(relation.get("status"))){state="RELATION_CONFLICT";conflicts++;unresolved.addAll(ids);}
            else if(!verified){state="UNRESOLVED";unresolved.addAll(ids);}
            else if(values.size()>1){state="DIFFERENT_VALUES_SAME_OBJECT";comparedPairs++;conflicts++;resolved.addAll(ids);}
            else{
                long positions=comparisonIds.stream().map(occurrences::get)
                    .map(this::verifiedPosition).distinct().count();
                if(positions>=2){state="MATCHING_VALUES_SAME_OBJECT";comparedPairs++;}
                else{state="SINGLE_OBSERVED_VALUE";singleValues++;}
                resolved.addAll(ids);
            }
            addEvidences(evidence,object.get("evidences"));
            for(java.util.Map<java.lang.String, java.lang.Object> r:maps(relation.get("relations"))){addEvidence(evidence,r.get("source"));addEvidence(evidence,r.get("headerSource"));addEvidence(evidence,r.get("invoiceSource"));}
            decisions.add(values("objectKey",object.get("objectKey"),"objectKind",object.get("objectKind"),"status",state,
                "originalOccurrenceIds",ids,"occurrenceIds",object.get("occurrenceIds"),"values",cn.bidcheck.compat.Java8Api.listCopyOf(values),
                "identityVerified",object.get("identityVerified"),"identityProof",object.get("identityProof"),"relationValidation",relation,
                "sourceComplete",sourceComplete,"provesQualificationValidity",false,"provesCurrentBidPrice",false));
        }
        // No omitted/unknown record may disappear merely because another object was successfully proved.
        for(String id:requested)if(!resolved.contains(id)&&!unresolved.contains(id)&&!excluded.contains(id))unresolved.add(id);
        resolved.removeAll(unresolved);
        excluded.removeAll(unresolved);
        boolean complete=!requested.isEmpty()&&failures.isEmpty()&&unresolved.isEmpty()&&resolved.size()+excluded.size()==new HashSet<>(requested).size();
        String status=conflicts>0?"WARNING":complete&&comparedPairs>0?"PASS":"UNDETERMINED";
        String reason=conflicts>0?"已识别记录中有 "+conflicts+" 个同身份字段或关系存在差异；请对照原文核实。"
            :complete&&comparedPairs>0?"原组 "+requested.size()+" 条已识别记录已分别核对，"+resolved.size()+" 条完成同口径对象验证、"+excluded.size()+" 条有原文依据排除出当前比较，未发现同口径差异。"
            :complete&&comparedPairs==0?"已识别范围内没有可比较的同对象原文对。":"已核对原组 "+requested.size()+" 条记录，其中 "+resolved.size()+" 条已完成对象与关系验证，"+excluded.size()+" 条有依据排除，"+unresolved.size()+" 条仍缺来源、身份或比较口径；未把未明记录排除或把全被排除当作通过。";
        reason+=" 本结论仅覆盖本组已识别记录，不证明全文完整或资格、报价等业务条件全部满足。";
        Map<String,Object> derived=values("policyVersion",VERSION,"relationPolicyVersion",ConsistencyObjectRecovery.VERSION,
            "sourceDocumentId",document.id(),"sourceDocumentHash",document.sha256(),"sourceDocumentVersion",document.version(),
            "status",status,"reason",reason,"proofScope","OBSERVED_OCCURRENCES","originalOccurrenceIds",cn.bidcheck.compat.Java8Api.listCopyOf(requested),
            "resolvedOccurrenceIds",cn.bidcheck.compat.Java8Api.listCopyOf(resolved),"excludedOccurrenceIds",cn.bidcheck.compat.Java8Api.listCopyOf(excluded),"unresolvedOccurrenceIds",cn.bidcheck.compat.Java8Api.listCopyOf(unresolved),"comparisonCoverageComplete",complete,
            "documentCoverageComplete",false,"singleValueObjects",singleValues,"comparedPairObjects",comparedPairs,
            "separatedObjectCount",decisions.size(),"conflictObjects",conflicts,"sourceFailures",cn.bidcheck.compat.Java8Api.toList(failures.stream().distinct()),
            "objects",decisions,"originalGroupStatus",group.get("status"),"originalRecordsPreserved",true,"humanReviewsPreserved",true);
        group.put("derivedConclusion",derived);
        return Optional.of(new Evaluation(status,reason,complete&&conflicts==0?"":"查看未完成对象及其原文依据；仅对具体缺口继续补查。",
            ConsistencyObjectRecovery.unique(evidence),complete?0.95:conflicts>0?0.8:0,"CONSISTENCY_DERIVED_OBSERVED_OCCURRENCES"));
    }
    private List<Object> verifiedPosition(Map<String,Object> occurrence){
        Evidence e=ConsistencyObjectRecovery.evidence(occurrence.get("evidence"));
        Object at=occurrence.get("sourceOffset");
        int offset=at instanceof Number ? ((Number)at).intValue() :
            document.pages().stream().filter(p->p.number()==e.page() && p.locationType().equals(e.locationType()))
                .map(p->p.text().indexOf(e.quote())).findFirst().orElse(-1);
        return Arrays.asList(e.documentId(),e.locationType(),e.page(),offset);
    }
    private boolean validOccurrence(Map<String,Object> occurrence){
        if(occurrence==null)return false;Evidence e=ConsistencyObjectRecovery.evidence(occurrence.get("evidence"));
        if(e==null||!document.id().equals(e.documentId())||e.quote()==null||cn.bidcheck.compat.Java8Api.isBlank(e.quote()))return false;
        List<Page> pages=cn.bidcheck.compat.Java8Api.toList(document.pages().stream().filter(p->p.number()==e.page()&&Objects.equals(p.locationType(),e.locationType())));
        if(pages.size()!=1)return false;String text=Objects.toString(pages.get(0).text(),"");
        if(occurrence.get("sourceOffset") instanceof Number){int at=((Number) (occurrence.get("sourceOffset"))).intValue();return at>=0&&at+e.quote().length()<=text.length()&&text.startsWith(e.quote(),at);}
        int at=text.indexOf(e.quote());return at>=0&&text.indexOf(e.quote(),at+1)<0;
    }
    private static List<String> strings(Object raw){return raw instanceof List<?>?cn.bidcheck.compat.Java8Api.toList(((List<?>) (raw)).stream().map(Object::toString)):cn.bidcheck.compat.Java8Api.listOf();}
    private static void addEvidence(List<Evidence> target,Object raw){Evidence e=ConsistencyObjectRecovery.evidence(raw);if(e!=null)target.add(e);}
    private static void addEvidences(List<Evidence> target,Object raw){if(raw instanceof List<?>)for(Object e:((List<?>) (raw)))addEvidence(target,e);}
}
