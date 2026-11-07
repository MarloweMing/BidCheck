package cn.bidcheck.verification.engine;

import java.util.*;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A PO's reference may request a cover read. It cannot itself identify the original. */
final class FrameworkNumberEvidence {
    private FrameworkNumberEvidence(){}

    /** Only identify the inventory row where this separately verified PO was filed.
     * Preserve its independent identity and all outstanding original-agreement checks. */
    static boolean attachOrderNavigation(Map<String,Object> check,Map<String,Object> row,List<Map<String,Object>> declarations,
            Map<String,Object> header,EvidenceCorpus corpus) {
        String number=str(header,"expectedParentReference");
        List<String> ids=ProofVerifier.strings(check.get("sourceIds"));
        if(number.isEmpty()||ids.isEmpty()||str(row,"documentHash").isEmpty()||!(row.get("documentVersion") instanceof Number)
                ||ProofVerifier.strings(check.get("factIds")).isEmpty()||Boolean.TRUE.equals(check.get("counted"))||
                !Collections.singletonList(number).equals(ProofVerifier.strings(check.get("contractNumbers"))))return false;
        if(!ids.stream().allMatch(id->{Source s=corpus.byId.get(id);return DeclaredPerformanceRows.contains(row,s)
                && ContractRecordBindings.isOrder(s.evidence().quote())
                && Collections.singleton(number).equals(ContractRecordBindings.printedParentAgreements(s));}))return false;
        long matches=declarations.stream().filter(r->ids.stream().allMatch(id->DeclaredPerformanceRows.contains(r,corpus.byId.get(id)))).count();
        if(matches!=1)return false;
        check.put("declaredNavigationRow",row);
        check.put("orderNavigationEvidence",linked("method","ORDER_REFERENCE_NAVIGATION_ONLY",
            "documentId",row.get("documentId"),"documentVersion",row.get("documentVersion"),"documentSha256",row.get("documentHash"),
            "sourceIds",ids,"factIds",check.get("factIds"),"uniqueRangeMatches",1,
            "sourcePages",cn.bidcheck.compat.Java8Api.toList(ids.stream().map(id->corpus.byId.get(id).evidence().page()).distinct()),
            "originalNumberConfirmed",false,"businessProof",false));
        return true;
    }

    static Map<String,Object> reconcile(Rule rule,Map<String,Object> row,Map<String,List<Fact>> groups,RuleEvaluator evaluator) {
        if(groups.size()!=2 || !str(row,"separatorTitle").contains("框架合同"))return Collections.emptyMap();
        String parentKey="",nameKey="",number="";
        for(Map.Entry<String,List<Fact>> entry:groups.entrySet()) {
            Set<String> numbers=new LinkedHashSet<>();
            for(Fact f:entry.getValue())if(!str(f.attributes(),"contractNumber").isEmpty())numbers.add(str(f.attributes(),"contractNumber"));
            if(numbers.isEmpty()){nameKey=entry.getKey();continue;}
            if(numbers.size()!=1)return Collections.emptyMap();
            String n=numbers.iterator().next();
            if(entry.getValue().stream().anyMatch(f->!"contractNumber".equals(ContractCandidateFields.identityAttribute(f.field()))
                    || !n.equals(f.rawValue()) || f.sourceIds().isEmpty() || !f.sourceIds().stream().allMatch(id->{
                        Source source=evaluator.corpus.byId.get(id);
                        return DeclaredPerformanceRows.contains(row,source)&&ContractRecordBindings.isOrder(source.evidence().quote())
                            && Collections.singleton(n).equals(ContractRecordBindings.printedParentAgreements(source));
                    })))return Collections.emptyMap();
            parentKey=entry.getKey();number=n;
        }
        if(nameKey.isEmpty()||parentKey.isEmpty())return Collections.emptyMap();
        String base=str(row,"contractName");if(base.isEmpty())base=str(row,"separatorTitle");
        Set<String> anchors=new LinkedHashSet<>();
        for(Fact fact:groups.get(nameKey)) {
            if(str(fact.attributes(),"contractRecordId").isEmpty())continue;
            for(String id:ProofVerifier.strings(fact.attributes().get("recordAnchorSourceIds"))){
                Source source=evaluator.corpus.byId.get(id);
                if(DeclaredPerformanceRows.contains(row,source)&&ProofVerifier.strings(row.get("separatorSourceIds")).contains(id)
                        &&ContractCandidateFields.sameTitle(FrameworkOriginalFields.coverTitle(source,base),str(fact.attributes(),"recordAnchorName")))anchors.add(id);
            }
        }
        if(anchors.size()!=1)return Collections.emptyMap();
        Source cover=evaluator.corpus.byId.get(anchors.iterator().next());
        Set<String> values=new LinkedHashSet<>();List<Map<String,Object>> readings=new ArrayList<>();boolean inspected=false;
        for(Map<String,Object> o:RuleEvaluator.maps(evaluator.context.get("visualObservations"))){
            if(!rule.id().equals(str(o,"obligationId")) || !"FIELD".equals(str(o,"type")) || !"FIELD".equals(str(o,"requestedTask"))
                    || !cover.evidence().documentId().equals(str(o,"documentId")) || !cover.evidence().locationType().equals(str(o,"locationType"))
                    || cover.evidence().page()!=RunSession.number(o.get("location"),-1)
                    || !Collections.singletonList(cover.id()).equals(ProofVerifier.strings(o.get("requestedSourceIds")))
                    || !ProofVerifier.strings(o.get("requestedFields")).contains("合同编号") || !(o.get("source") instanceof Map<?,?>))continue;
            Map<String,Object> meta=(Map<String,Object>)o.get("source");
            if(!cover.evidence().documentId().equals(str(meta,"documentId"))||!cover.documentHash().equals(str(meta,"documentHash"))
                    || cover.documentVersion()!=RunSession.number(meta.get("documentVersion"),-1)
                    || cover.evidence().page()!=RunSession.number(meta.get("page"),-1) || str(meta,"renderedSha256").isEmpty()
                    || !str(meta,"renderedSha256").equals(str(o,"imageHash")))continue;
            inspected=true;
            Source image=evaluator.corpus.visualFieldSource(o);
            String n=str(o,"value").trim();
            if(image==null||image.evidence().page()!=cover.evidence().page()
                    ||!image.evidence().documentId().equals(cover.evidence().documentId())||!"合同编号".equals(str(o,"label"))
                    || !VisualObservationPolicy.assess(o).valueUsable() || !n.matches("[\\p{L}0-9][-A-Za-z0-9._/()（）\\p{IsHan}]{2,100}"))continue;
            values.add(n);readings.add(linked("sourceId",image.id(),"observationId",o.get("id"),"number",n,
                "imageHash",o.get("imageHash"),"sourceBbox",o.get("sourceBbox")));
        }
        boolean confirmed=values.equals(Collections.singleton(number));
        if(confirmed) {
            List<Fact> merged=new ArrayList<>(groups.get(parentKey));merged.addAll(groups.remove(nameKey));
            groups.put(parentKey,merged);
        } else if(!inspected) {
            Map<String,Object> request=linked("documentId",cover.evidence().documentId(),"locationType",cover.evidence().locationType(),
                "location",cover.evidence().page(),"task","FIELD","fields",Collections.singletonList("合同编号"),
                "reason","FRAMEWORK_ORIGINAL_HEADER_NUMBER_REQUIRED","obligationId",rule.id(),"sourceIds",Collections.singletonList(cover.id()));
            if(!evaluator.visualRequests.contains(request))evaluator.visualRequests.add(request);
        }
        return linked("status",confirmed?"ORIGINAL_NUMBER_MATCHED":!inspected?"ORIGINAL_IMAGE_REQUIRED":"ORIGINAL_NUMBER_REQUIRES_REVIEW",
            "expectedParentReference",number,"originalSourceId",cover.id(),"page",cover.evidence().page(),"readings",readings,"businessProof",false);
    }
}
