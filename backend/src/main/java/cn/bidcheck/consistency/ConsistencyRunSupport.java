package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.verification.facts.FactLedgerService;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bridges a complete, immutable scan into the shared evidence ledger and ordinary reviewable findings. */
public final class ConsistencyRunSupport {
    private ConsistencyRunSupport() {}
    public static String reportId(String runId) { return id("consistency-report:" + runId); }
    public static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    public static boolean enabled(Checkpoint cp) {
        return cp.enabled() && cp.confirmed() && "FULL_TEXT_REVIEW".equals(cp.type()) && "fullTextConsistency".equals(cp.field());
    }

    /** Caller owns a fenced transaction and the project row lock. Batches never truncate the scan. */
    public static void publish(Store store, FactLedgerService ledger, String runId, Document doc,
                               String engineIdentity, Map<String,Object> report) {
        publish(store,ledger,runId,List.of(doc),engineIdentity,report);
    }

    /** Separate source documents remain separate in both provenance and the shared snapshot. */
    public static void publish(Store store, FactLedgerService ledger, String runId, List<Document> documents,
                               String engineIdentity, Map<String,Object> report) {
        publish(store,ledger,runId,documents,engineIdentity,report,true);
    }

    /** A baseline scan can publish before other checks; only the completed run may freeze its shared ledger. */
    public static void publish(Store store, FactLedgerService ledger, String runId, List<Document> documents,
                               String engineIdentity, Map<String,Object> report,boolean freezeSnapshot) {
        List<Map<String,Object>> batches=publicationBatches(store,runId,documents,report);
        String projectId=documents.get(0).projectId();
        for(Map<String,Object> batch:batches) {
            Map<String,Object> result=ledger.publish(projectId,runId,"full-text-consistency",engineIdentity,documents,batch);
            if(!maps(result.get("rejected")).isEmpty() || ((Number)result.get("accepted")).intValue()!=maps(batch.get("facts")).size())
                throw new IllegalStateException("一致性事实未通过原文验证，不能发布报告");
        }
        Map<String,Object> snapshot = freezeSnapshot?ledger.snapshot(projectId,runId,engineIdentity,documents):ledger.view(projectId,runId,engineIdentity,documents);
        attachPublication(report,runId,documents,engineIdentity,snapshot,freezeSnapshot);
    }
    public static void attachPublication(Map<String,Object> report,String runId,List<Document> documents,
                                         String engineIdentity,Map<String,Object> snapshot,boolean freezeSnapshot) {
        report.put(freezeSnapshot?"ledgerSnapshotId":"ledgerViewId",snapshot.get("id"));
        report.put("ledgerFrozen",freezeSnapshot);
        report.put("ledgerSummary",snapshot.get("summary"));
        report.put("id",reportId(runId));
        report.put("runId",runId);
        report.put("documentIds",documents.stream().map(Document::id).toList());
        report.put("sourceManifest",documents.stream().map(doc->values("documentId",doc.id(),"documentName",doc.name(),"documentHash",doc.sha256(),"documentVersion",doc.version())).toList());
        if(documents.size()==1){Document doc=documents.get(0);report.put("documentId",doc.id());report.put("documentHash",doc.sha256());report.put("documentVersion",doc.version());}
        report.put("engineIdentity",engineIdentity);
    }
    /** Pure preparation: all source binding and bounded unit construction happen before task locks. */
    public static List<Map<String,Object>> publicationBatches(Store store,String runId,List<Document> documents,Map<String,Object> report) {
        return publicationBatches(store,runId,documents,report,40,128,0);
    }
    /** A byte target isolates an oversized single fact; validation still applies to its entire content. */
    public static List<Map<String,Object>> publicationBatches(Store store,String runId,List<Document> documents,Map<String,Object> report,
                                                             int maxFacts,int maxSources,int targetEncodedBytes) {
        if(maxFacts<1||maxFacts>40||maxSources<16||maxSources>128||targetEncodedBytes<0)
            throw new IllegalArgumentException("CONSISTENCY_BATCH_POLICY_INVALID");
        if(documents==null||documents.isEmpty())throw new IllegalArgumentException("一致性扫描缺少来源文档");
        String projectId=documents.get(0).projectId();
        if(documents.stream().anyMatch(doc->!projectId.equals(doc.projectId())))throw new IllegalArgumentException("一致性来源跨越不同项目");
        List<Map<String,Object>> batches = new ArrayList<>();
        Map<String,String> fingerprints = new HashMap<>();
        List<Map<String,Object>> occurrences = maps(report.get("occurrences"));
        Map<String,Page> pages = new HashMap<>();
        Map<String,Document> sourcesByDocument=new HashMap<>();
        for(Document doc:documents) {
            if(sourcesByDocument.putIfAbsent(doc.id(),doc)!=null)throw new IllegalArgumentException("一致性来源文档重复");
            for(Page page:doc.pages())pages.put(doc.id()+":"+page.locationType()+":"+page.number(),page);
        }
        for (int offset=0; offset<occurrences.size();) {
            List<Map<String,Object>> sources = new ArrayList<>(), facts = new ArrayList<>();
            int start=offset;
            while(offset<occurrences.size()&&facts.size()<maxFacts) {
                Map<String,Object> occurrence = occurrences.get(offset);
                Evidence evidence = evidence(store, occurrence.get("evidence"));
                Document doc=sourcesByDocument.get(evidence.documentId());
                Page page = pages.get(evidence.documentId()+":"+evidence.locationType() + ":" + evidence.page());
                if (doc==null||page == null) throw new IllegalArgumentException("一致性事实缺少原文位置");
                List<Evidence> references=new ArrayList<>();references.add(evidence);
                references.addAll(evidences(store,occurrence.get("contextEvidences")));
                String contextQuote=string(occurrence,"contextQuote");
                if(!contextQuote.isBlank()&&!evidence.quote().contains(contextQuote))
                    references.add(new Evidence(doc.id(),doc.name(),page.number(),page.locationType(),contextQuote,evidence.anchor()+"-context",evidence.confidence()));
                references=references.stream().distinct().toList();
                if(references.size()>16)throw new IllegalArgumentException("一致性事实引用超限");
                if(sources.size()+references.size()>maxSources)break;
                List<String> sourceIds=new ArrayList<>();
                List<Map<String,Object>> candidateSources=new ArrayList<>();
                for(Evidence reference:references) {
                    Document referenceDoc=sourcesByDocument.get(reference.documentId());
                    Page referencePage=pages.get(reference.documentId()+":"+reference.locationType()+":"+reference.page());
                    if(referenceDoc==null||referencePage==null)throw new IllegalArgumentException("一致性上下文缺少原文位置");
                    String sourceId="source-"+(sources.size()+candidateSources.size());sourceIds.add(sourceId);
                    candidateSources.add(values("id",sourceId,"projectId",referenceDoc.projectId(),"evidence",reference,
                            "documentHash",referenceDoc.sha256(),"documentVersion",referenceDoc.version(),
                            "parserFingerprint",fingerprints.computeIfAbsent(referenceDoc.id()+":"+referencePage.locationType()+":"+referencePage.number(),key->FactLedgerService.parserFingerprint(referencePage))));
                }
                String scope = string(occurrence,"scope");
                if (!Set.of("CURRENT_PROJECT","HISTORICAL_PROJECT","UNRESOLVED").contains(scope)) scope="UNRESOLVED";
                Map<String,Object> attributes = new LinkedHashMap<>();
                for (String key : List.of("id","lot","time","scopeReason","comparability","extractionMethod","confidence","contextQuote","revision",
                        "categoryId","fieldLabel","dimensions","ruleId","sourceOffset","sourceRowLine","comparator","precision",
                        "rowId","parameterName","operator","valueType","missingContext","sourceUnit",
                        "recordKey","credentialType","personCredentialField","personCredentialContext","contextAssignment","subjectAssignment","sectionId","tableAssociation"))
                    if (occurrence.containsKey(key)) attributes.put(key, occurrence.get(key));
                Map<String,Object> candidateFact=values("id",string(occurrence,"id"),"kind","FIELD","subject",string(occurrence,"subject"),
                        "field",string(occurrence,"field"),"rawValue",string(occurrence,"rawValue"),
                        "value",string(occurrence,"value"),"unit",string(occurrence,"unit"),
                        "basis",string(occurrence,"basis"),"scope",scope,"sourceIds",sourceIds,"attributes",attributes);
                if(targetEncodedBytes>0&&!facts.isEmpty()) {
                    List<Map<String,Object>> nextSources=new ArrayList<>(sources);nextSources.addAll(candidateSources);
                    List<Map<String,Object>> nextFacts=new ArrayList<>(facts);nextFacts.add(candidateFact);
                    if(store.encode(values("operationIdentity",reportId(runId)+":"+start,"engineVersion",ConsistencyEngine.VERSION,
                            "sources",nextSources,"facts",nextFacts)).getBytes(StandardCharsets.UTF_8).length>targetEncodedBytes)break;
                }
                sources.addAll(candidateSources);facts.add(candidateFact);
                offset++;
            }
            batches.add(values("operationIdentity",reportId(runId)+":"+start,"engineVersion",ConsistencyEngine.VERSION,
                    "sources",sources,"facts",facts));
        }
        return List.copyOf(batches);
    }

    public static Evaluation summary(Map<String,Object> report, Store store) {
        List<Map<String,Object>> groups = maps(report.get("groups"));
        long conflicts = groups.stream().filter(g->"CONFLICT".equals(string(g,"status"))).count();
        long gaps = groups.stream().filter(g->"EVIDENCE_INSUFFICIENT".equals(string(g,"status"))).count();
        String reason = groups.isEmpty() ? "已扫描的文本范围内未发现可比较字段的冲突；不代表全文所有信息均已核实。"
                : "发现 " + conflicts + " 组明确冲突、" + (groups.size()-conflicts-gaps) + " 组疑似差异、" + gaps + " 组比较证据不足；请逐组查看原文并复核。";
        List<Evidence> evidence = groups.stream().flatMap(g->evidences(store,g.get("evidences")).stream()).distinct().toList();
        return new Evaluation(groups.isEmpty()||gaps==groups.size()?"UNDETERMINED":"WARNING",reason,
                "查看全文一致性面板中的扫描范围及各组原文；正确值以确认的招标要求、有效补遗或人工复核为准。",
                evidence,groups.isEmpty()?0:0.8,"FULL_TEXT_CONSISTENCY");
    }

    public static List<Evidence> evidences(Store store,Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(v->evidence(store,v)).toList();
    }
    public static Evidence evidence(Store store,Object raw) {
        return raw instanceof Evidence e ? e : store.decode(store.encode(raw),Evidence.class);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>)value : Map.of();
    }
    public static List<Map<String,Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance).map(ConsistencyRunSupport::map).toList() : List.of();
    }
    public static String string(Map<String,Object> value,String key) { return Objects.toString(value.get(key),""); }
    public static Map<String,Object> values(Object... pairs) {
        Map<String,Object> out = new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
}
