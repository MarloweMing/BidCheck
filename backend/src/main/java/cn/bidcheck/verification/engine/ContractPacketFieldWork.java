package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Work left on actually submitted fields of one declared packet. No proposal is business proof. */
final class ContractPacketFieldWork {
    static final String VERSION = "bounded-contract-field-work-1";
    private final Map<String,Map<String,Attempt>> byTarget = new LinkedHashMap<>();
    private final Map<String,Set<String>> rejectedKeys = new LinkedHashMap<>();
    private final Set<String> unmapped = new HashSet<>();

    private static final class Attempt {
        final Map<String,Object> atom, result, outer;
        final String recordId;
        Attempt(Map<String,Object> atom, Map<String,Object> result, Map<String,Object> outer, String recordId) {
            this.atom=atom; this.result=result; this.outer=outer; this.recordId=recordId;
        }
    }

    void observe(String target, Map<String,Object> args, Map<String,Object> result) {
        List<Map<String,Object>> fields=RuleEvaluator.maps(args.get("contractFields"));
        List<Map<String,Object>> receipts=RuleEvaluator.maps(result.get("fieldResults"));
        Map<String,Object> outer=map(args.get("fact")),protocol=map(result.get("protocolFieldRepair"));
        boolean single=!args.containsKey("contractFields") && "host-single-contract-field-protocol-1".equals(str(protocol,"version"))
            && target.equals(str(protocol,"targetObligationId"));
        if(single){
            fields=Collections.singletonList(map(protocol.get("atom")));
            receipts=Collections.singletonList(linked("status","REJECTED","fieldPath","/fact/rawValue","rejections",result.get("rejections")));
            outer=map(protocol.get("identity"));
        }
        if (fields.isEmpty() || fields.size()!=receipts.size()
                || !single && RuleEvaluator.maps(result.get("rejections")).stream()
                    .anyMatch(issue -> !str(issue,"fieldPath").startsWith("/contractFields/"))) {
            unmapped.add(target);
            return;
        }
        Map<String,Attempt> attempts=byTarget.computeIfAbsent(target,k->new LinkedHashMap<>());
        String issued=str(map(result.get("contractRecord")),"recordId");
        for (int index=0; index<fields.size(); index++) {
            Map<String,Object> atom=fields.get(index), attrs=map(atom.get("attributes")), receipt=receipts.get(index);
            String field=str(atom,"field");
            String key=Arrays.asList(field, str(attrs,field), str(attrs,"person"), str(attrs,"amountBasis"),
                str(attrs,"taxBasis"), str(attrs,"unit")).toString();
            if(!RuleEvaluator.maps(receipt.get("rejections")).isEmpty())
                rejectedKeys.computeIfAbsent(target,k->new HashSet<>()).add(key);
            attempts.put(key,new Attempt(atom,receipt,outer,
                atom.containsKey("recordId")?str(atom,"recordId"):issued));
        }
    }

    Map<String,Object> plan(String target,String request,String planRevision,String revision,
            Map<String,Object> coverage,List<Map<String,Object>> pending,List<Source> packet,
            Map<String,ContractEvidenceRecord> records,ProofVerifier verifier,Collection<Fact> known,Decision targetProof) {
        Map<String,Attempt> attempts=byTarget.get(target);
        if (attempts==null || attempts.isEmpty() || coverage.isEmpty()) return Collections.emptyMap();
        List<Map<String,Object>> tasks=new ArrayList<>(), actions=new ArrayList<>();
        boolean understood=!unmapped.contains(target);
        Set<String> read=new HashSet<>(ProofVerifier.strings(coverage.get("readSourceIds")));
        Map<String,Source> current=new LinkedHashMap<>();
        for(Source source:packet) if(read.contains(source.id())) current.put(source.id(),source);
        for(Attempt attempt:attempts.values()) {
            String field=str(attempt.atom,"field");
            List<Map<String,Object>> issues=RuleEvaluator.maps(attempt.result.get("rejections"));
            String path="/contractFields/"+attempt.result.get("fieldIndex");
            List<String> sources=ProofVerifier.strings(attempt.atom.get("sourceIds"));
            Map<String,Object> task=linked("field",field,"sourceIds",sources,"fieldPath",path,"code","","businessProof",false);
            tasks.add(task);
            if(issues.isEmpty()) {
                boolean accepted=!ProofVerifier.strings(attempt.result.get("acceptedFactIds")).isEmpty()
                    || !ProofVerifier.strings(attempt.result.get("alreadyKnownFactIds")).isEmpty();
                task.put("status",accepted?"ACCEPTED_OR_KNOWN":"UNRESOLVED");
                understood &= accepted;
                continue;
            }
            Map<String,Object> issue=issues.get(0);
            task.put("code",str(issue,"code")); task.put("fieldPath",issue.getOrDefault("submittedValuePath",issue.get("fieldPath")));
            if(issue.containsKey("submittedValuePath"))task.put("protocolFieldPath",issue.get("fieldPath"));
            ContractEvidenceRecord record=records.get(attempt.recordId);
            if(issues.size()==1 && record!=null && current.keySet().containsAll(record.anchors)
                    && monthOnlyOriginal(attempt,issue,current)) {
                SigningDateBlock day=SigningDateBlock.read(current.values());
                if(day.status==SigningDateBlock.Status.NOT_FOUND) {
                    task.put("status","NO_GROUNDED_CORRECTION");
                    task.put("diagnostic","ORIGINAL_SIGNING_MONTH_WITHOUT_DAY");
                    List<Map<String,Object>> originals=new ArrayList<>();
                    for(Source source:current.values()) {
                        String line=ContractSigningOriginal.damagedDateQuote(source);
                        if(!line.isEmpty())originals.add(linked("sourceId",source.id(),"page",source.evidence().page(),
                            "field","合同签订日期","originalText",line,"route","VERIFY_SOURCE_PAGES","businessProof",false));
                    }
                    if(!originals.isEmpty()) {
                        task.put("diagnostic","ORIGINAL_SIGNING_DATE_IMAGE_REQUIRED");
                        task.put("originalReview",originals);
                    }
                } else {
                    Map<String,Object> repair=issue.containsKey("submittedValuePath")
                        ?fullSigningDate(target,attempt,record,current,verifier,known,day):Collections.emptyMap();
                    if(Boolean.TRUE.equals(repair.get("alreadyKnown"))) {
                        task.put("status","NO_GROUNDED_CORRECTION");task.put("diagnostic","COMPLETE_SIGNING_DAY_SEPARATELY_VERIFIED");
                    } else {
                        if(repair.isEmpty()) for(Source source:day.sources)
                            actions.add(reviewSource(target,"signedDate",source,"CHECK_COMPLETE_SIGNING_DATE"));
                        else {repair.put("fieldPath",task.get("fieldPath")); actions.add(repair);}
                        task.put("status","ACTION_REQUIRED");
                    }
                }
                continue;
            }
            if(issues.size()!=1 || !knownIssue(str(issue,"code")) || !current.keySet().containsAll(sources)
                    || sources.isEmpty() || !canInspectField(attempt.atom)) {
                task.put("status","UNRESOLVED"); understood=false; continue;
            }
            if(record==null || !current.keySet().containsAll(record.anchors)) {
                task.put("status","UNRESOLVED"); understood=false; continue;
            }
            Map<String,Object> action=correction(target,attempt,record,current,verifier,known);
            if(action.isEmpty()) action=differentFieldValue(target,attempt,record,current.values());
            if(action.isEmpty()) task.put("status","NO_GROUNDED_CORRECTION");
            else if(Boolean.TRUE.equals(action.get("alreadyKnown"))) task.put("status","ACCEPTED_OR_KNOWN");
            else {
                task.put("status","ACTION_REQUIRED"); action.put("fieldPath",task.get("fieldPath"));
                actions.add(action);
            }
        }
        boolean complete=Boolean.TRUE.equals(coverage.get("complete")) && pending.isEmpty();
        Set<String> fields=new HashSet<>();
        attempts.values().forEach(a->fields.add(str(a.atom,"field")));
        fields.retainAll(Arrays.asList("amount","signedDate","party","person","personRole"));
        if(fields.remove("person")) fields.add("personRole");
        int rejectedCount=rejectedKeys.getOrDefault(target,Collections.emptySet()).size();
        List<Map<String,Object>> checks=targetProof==null?Collections.emptyList():RuleEvaluator.maps(targetProof.proof().get("contractChecks"));
        boolean independentWork=fields.size()>=2 && !Collections.disjoint(fields,Arrays.asList("amount","signedDate","personRole"));
        boolean reviewed=checks.size()==1 && checks.get(0).get("missing") instanceof List<?>;
        Set<String> anchors=new HashSet<>();
        for (Attempt attempt:attempts.values()) {
            ContractEvidenceRecord record=records.get(attempt.recordId);
            if(record!=null) anchors.addAll(record.anchors);
        }
        if(reviewed) addOutstandingFields(target,fields,checks.get(0),cn.bidcheck.compat.Java8Api.toList(
            current.values().stream().filter(source->!anchors.contains(source.id()))),actions);
        // A fully checked packet need not manufacture a rejected submission to finish its work.
        // This consumes only this packet revision, never the business requirement or its gaps.
        boolean workComplete=complete && understood && independentWork && reviewed && actions.isEmpty();
        String status=workComplete?"NO_EXECUTABLE_CORRECTION":!understood||!complete||actions.isEmpty()?"INCOMPLETE":"ACTION_REQUIRED";
        return linked("version",VERSION,"authority","CURRENT_SERVER_PROOF","targetObligationId",target,
            "requestId",request,"serverPlanRevision",planRevision,"evidenceRevision",revision,
            "documentId",coverage.get("documentId"),"documentHash",coverage.get("documentHash"),
            "documentVersion",coverage.get("documentVersion"),"status",status,"attemptedFieldCount",tasks.size(),
            "rejectedFieldCount",rejectedCount,"targetWorkComplete",workComplete,
            "tasks",tasks,"remainingActions",actions,"businessProof",false);
    }

    private static boolean monthOnlyOriginal(Attempt attempt, Map<String,Object> issue,Map<String,Source> sources) {
        if(!"signedDate".equals(str(attempt.atom,"field")) || !"CONTRACT_ATOMIC_FIELDS_INVALID".equals(str(issue,"code"))
                || !Arrays.asList("field","rawValue","sourceIds","attributes","recordId").containsAll(attempt.atom.keySet())) return false;
        Map<String,Object> attrs=map(attempt.atom.get("attributes"));
        String value=str(attrs,"signedDate");
        if(!Arrays.asList("signedDate","contractName","contractNumber").containsAll(attrs.keySet())
                || !value.equals(str(attempt.atom,"rawValue")) || !value.matches("20[0-9]{2}年(?:0?[1-9]|1[0-2])月")) return false;
        List<String> ids=ProofVerifier.strings(attempt.atom.get("sourceIds"));
        if(ids.size()!=1 || !sources.containsKey(ids.get(0)))return false;
        Pattern printed=Pattern.compile("(?m)^[ \\t#|>*]*(?:合同)?(?:签署|签约|签订)(?:日期|时间)[ \\t]*[:：][ \\t]*"
            +Pattern.quote(value)+"[ \\t|。]*$");
        String quote=sources.get(ids.get(0)).evidence().quote();
        Matcher month=printed.matcher(quote);
        return month.find() && !quote.substring(month.end()).matches("(?s)^\\s*[0-9]{1,2}\\s*日.*");
    }

    private static Map<String,Object> fullSigningDate(String target,Attempt partial,ContractEvidenceRecord record,
            Map<String,Source> sources,ProofVerifier verifier,Collection<Fact> known,SigningDateBlock day) {
        if(day.status!=SigningDateBlock.Status.CONFIRMED)return Collections.emptyMap();
        java.time.LocalDate complete=ProofVerifier.date(day.first);
        String month=str(partial.atom,"rawValue");
        if(complete==null || !month.matches(String.format("%04d年0?%d月",complete.getYear(),complete.getMonthValue())))
            return Collections.emptyMap();
        Pattern dates=Pattern.compile("20[0-9]{2}[年./-][0-9]{1,2}[月./-][0-9]{1,2}日?");
        for(Source source:day.sources) {
            if(record.anchors.contains(source.id()))continue;
            Matcher m=dates.matcher(source.evidence().quote());
            while(m.find())if(complete.equals(ProofVerifier.date(m.group()))) {
                Map<String,Object> atom=linked("field","signedDate","rawValue",m.group(),"attributes",linked("signedDate",m.group()),
                    "sourceIds",Collections.singletonList(source.id()),"recordId",record.id);
                Attempt separate=new Attempt(atom,Collections.emptyMap(),partial.outer,record.id);
                return correction(target,separate,record,sources,verifier,known);
            }
        }
        return Collections.emptyMap();
    }

    private static final String NUMBER="(?:[0-9]{1,3}(?:[,，][0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?";
    private static final String UNITS="亿元|万元|美元|欧元|港元|元|CNY|RMB|USD|EUR|HKD";

    /** New values are review tasks. They cannot silently replace the submitted amount, date or person. */
    private static Map<String,Object> differentFieldValue(String target,Attempt attempt,ContractEvidenceRecord record,
            Collection<Source> sources) {
        String field=str(attempt.atom,"field");
        Map<String,Object> attrs=map(attempt.atom.get("attributes"));
        for(Source source:sources) {
            if(record.anchors.contains(source.id())) continue;
            String quote=source.evidence().quote(), other="";
            if("amount".equals(field)) {
                String basis=str(attrs,"amountBasis"), unit=str(attrs,"unit");
                Matcher amounts=labelledAmount(basis,quote);
                while(amounts.find()) {
                    String amount=amounts.group(1);
                    if(ProofVerifier.decimal(str(attrs,"amount"))==null
                            || ProofVerifier.decimal(amount).compareTo(ProofVerifier.decimal(str(attrs,"amount")))==0
                            || !ProofVerifier.unitBound(amounts.group(),amount,unit)) continue;
                    String tax=str(attrs,"taxBasis");
                    if(!tax.isEmpty() && !ProofVerifier.taxBound(quote,basis,amount,tax)) continue;
                    other=amount+" "+unit;break;
                }
            } else if("signedDate".equals(field)) {
                SigningDateBlock date=SigningDateBlock.read(Collections.singletonList(source));
                if(date.status==SigningDateBlock.Status.CONFIRMED && !date.confirms(str(attrs,"signedDate"))) other=date.first;
            } else if("person".equals(field)||"personRole".equals(field)) {
                Matcher named=Pattern.compile(Pattern.quote(str(attrs,"personRole"))+"[ \\t]*[:：][ \\t]*([\\p{IsHan}·]{2,12})(?=[\\s。；;|]|$)").matcher(quote);
                while(named.find()) if(!named.group(1).equals(str(attrs,"person"))) {other=named.group(1);break;}
            }
            if(!other.isEmpty()) {
                Map<String,Object> task=reviewSource(target,field,source,"REVIEW_ALTERNATIVE_FIELD_VALUE");
                task.put("submittedValue",attrs.get(field));task.put("alternativeValue",other);
                task.put("amountBasis",attrs.get("amountBasis"));return task;
            }
        }
        return Collections.emptyMap();
    }

    private static Matcher labelledAmount(String label,String text) {
        return Pattern.compile("(?<![\\p{IsHan}A-Za-z0-9])"+Pattern.quote(label)
            +"[ \\t]*[:：为]?[ \\t]*(?:人民币[ \\t]*)?("+NUMBER+")[ \\t]*("+UNITS+")").matcher(text);
    }

    /** Compare the current row's unresolved proof with ORIGINAL bodies. Even a complete prior
     * batch cannot consume an independent, still actionable read or a different required price basis. */
    private static void addOutstandingFields(String target,Set<String> attempted,Map<String,Object> row,
            Collection<Source> pages,List<Map<String,Object>> actions) {
        String missing=String.join(" ",ProofVerifier.strings(row.get("missing")));
        for(Source source:pages) {
            String quote=source.evidence().quote();
            if(!attempted.contains("signedDate") && missing.contains("签订日期")
                    && SigningDateBlock.read(Collections.singletonList(source)).status!=SigningDateBlock.Status.NOT_FOUND)
                actions.add(reviewSource(target,"signedDate",source,"CHECK_UNSUBMITTED_SIGNING_DATE"));
            if(!attempted.contains("party") && missing.contains("签约单位") && quote.matches("(?s).*(?:发包人|承包人|甲方|乙方)[（(]?(?:全称)?[）)]?[ \\t]*[:：][ \\t]*[\\p{IsHan}]{2,}.*"))
                actions.add(reviewSource(target,"party",source,"CHECK_UNSUBMITTED_PARTY"));
            if(!attempted.contains("personRole") && missing.matches("(?s).*(?:负责人任职|任职岗位|历史任职人员).*")
                    && quote.matches("(?s).*(?:设计负责人|设计人项目负责人|项目经理|项目负责人)[ \\t]*[:：][ \\t]*[\\p{IsHan}]{2,}.*"))
                actions.add(reviewSource(target,"personRole",source,"CHECK_UNSUBMITTED_HISTORICAL_ROLE"));
            for(String basis:Arrays.asList("建安费","建筑安装工程费","施工部分金额","施工费用","合同金额")) {
                boolean required=missing.contains("可核的"+basis)
                    || missing.contains("可核的建安费") && basis.equals("建筑安装工程费")
                    || missing.contains("可核的施工部分金额") && basis.equals("施工费用");
                boolean evaluated=RuleEvaluator.maps(row.get("amountObservations")).stream().anyMatch(observation->
                    basis.equals(str(observation,"amountBasis")) && ProofVerifier.strings(observation.get("sourceIds")).contains(source.id()));
                if(required && !evaluated && labelledAmount(basis,quote).find())
                    actions.add(reviewSource(target,"amount",source,"CHECK_REQUIRED_AMOUNT_BASIS"));
            }
        }
    }

    private static Map<String,Object> reviewSource(String target,String field,Source source,String action) {
        return linked("action",action,"targetObligationId",target,"field",field,"sourceIds",Collections.singletonList(source.id()),
            "page",source.evidence().page(),"fieldPath","/contract/fields/"+field,"businessProof",false);
    }

    /** Reuse selectors only; an earlier record ID never authorizes a submission in a new host. */
    static List<Map<String,Object>> previousReviewSources(Object previous,String planRevision,
            Map<String,String> currentRevisions,EvidenceCorpus corpus) {
        Map<String,Object> audit=map(map(previous).get("agentInvestigation")), receipt=map(audit.get("serverTargetInputConsumption"));
        if(!"CURRENT_SERVER_PROOF".equals(str(receipt,"authority"))
                || !planRevision.equals(str(receipt,"serverPlanRevision"))
                || !str(audit,"requestId").equals(str(receipt,"requestId"))) return Collections.emptyList();
        List<Map<String,Object>> hints=new ArrayList<>();
        for(Map<String,Object> target:RuleEvaluator.maps(receipt.get("targets"))) {
            Map<String,Object> plan=map(target.get("contractFieldWorkPlan"));
            String id=str(plan,"targetObligationId");
            if(!VERSION.equals(str(plan,"version")) || !"CURRENT_SERVER_PROOF".equals(str(plan,"authority"))
                    || !Boolean.FALSE.equals(plan.get("businessProof")) || !id.equals(str(target,"targetObligationId"))
                    || !Objects.equals(currentRevisions.get(id),plan.get("evidenceRevision"))
                    || !planRevision.equals(str(plan,"serverPlanRevision"))
                    || !str(receipt,"requestId").equals(str(plan,"requestId"))) continue;
            for(Map<String,Object> action:RuleEvaluator.maps(plan.get("remainingActions"))) {
                List<String> ids=ProofVerifier.strings(action.get("sourceIds"));
                if(!id.equals(str(action,"targetObligationId")) || ids.isEmpty() || ids.stream().anyMatch(ref->{
                    Source source=corpus.byId.get(ref);
                    return source==null || !source.evidence().documentId().equals(str(plan,"documentId"))
                        || !source.documentHash().equals(str(plan,"documentHash"))
                        || source.documentVersion()!=RunSession.number(plan.get("documentVersion"),-1);
                })) continue;
                Map<String,Object> hint=new LinkedHashMap<>(action);
                hint.remove("toolArguments"); hint.put("requiresOriginalRead",true);
                hints.add(hint);
            }
        }
        return hints;
    }

    private static boolean knownIssue(String code) {
        return Arrays.asList("RAW_VALUE_UNGROUNDED","CONTRACT_ATTRIBUTE_UNGROUNDED",
            "CONTRACT_ATOMIC_FIELDS_INVALID","CONTRACT_AMOUNT_BASIS_UNPROVEN","CONTRACT_AMOUNT_UNIT_UNPROVEN",
            "CONTRACT_TAX_BASIS_UNPROVEN").contains(code);
    }

    private static boolean canInspectField(Map<String,Object> atom) {
        String field=str(atom,"field");
        Map<String,Object> attrs=map(atom.get("attributes"));
        if (!Arrays.asList("contractName","contractNumber","party","person","personRole","signedDate","amount").contains(field)
                || str(attrs,field).isEmpty()) return false;
        if ("amount".equals(field) && (str(attrs,"amountBasis").isEmpty() || str(attrs,"unit").isEmpty())) return false;
        if (("person".equals(field)||"personRole".equals(field)) && (str(attrs,"person").isEmpty() || str(attrs,"personRole").isEmpty())) return false;
        Map<String,Object> shaped=new LinkedHashMap<>(atom);
        shaped.put("rawValue",attrs.get(field));
        return ContractCandidateFields.atomicIssue(shaped)==null;
    }

    /** Keep the submitted value, scope and price basis; try only original returned text in this row. */
    private static Map<String,Object> correction(String target,Attempt attempt,ContractEvidenceRecord record,
            Map<String,Source> current,ProofVerifier verifier,Collection<Fact> known) {
        String field=str(attempt.atom,"field");
        Map<String,Object> attrs=map(attempt.atom.get("attributes"));
        String value=str(attrs,field);
        if(value.isEmpty()) return Collections.emptyMap();
        for(Source source:current.values()) {
            if(record.anchors.contains(source.id()) || !ProofVerifier.contains(source.evidence().quote(),value)) continue;
            if (("person".equals(field)||"personRole".equals(field))
                    && !personAndRoleOnOneLine(source,attrs)) continue;
            Map<String,Object> atom=linked("field",field,"rawValue",value,"attributes",attrs,
                "sourceIds",Collections.singletonList(source.id()),"recordId",record.id);
            if(ContractCandidateFields.atomicIssue(atom)!=null) continue;
            Map<String,Object> bound=new LinkedHashMap<>(atom);
            if(record.bind(bound,current)!=null) continue;
            List<String> ids=ProofVerifier.strings(bound.get("sourceIds"));
            Fact fact=new Fact("","CONTRACT",str(attempt.outer,"subject"),field,value,value,"","",
                Objects.toString(attempt.outer.getOrDefault("scope","UNRESOLVED")),
                ids,map(bound.get("attributes")));
            if(verifier.issue(fact,current)!=null) continue;
            boolean exists=known.stream().anyMatch(f->"CONTRACT".equals(f.kind()) && field.equals(f.field())
                && f.attributes().equals(fact.attributes()) && new HashSet<>(ids).equals(new HashSet<>(f.sourceIds())));
            if(exists) return linked("alreadyKnown",true);
            return linked("action","SUBMIT_VERIFIED_CONTRACT_FIELD","targetObligationId",target,"field",field,
                "sourceIds",Collections.singletonList(source.id()),"page",source.evidence().page(),
                "toolArguments",linked("targetObligationId",target,"submissionMode","CONTRACT_FIELDS",
                    "fact",attempt.outer,"contractFields",Collections.singletonList(atom)),"businessProof",false);
        }
        return Collections.emptyMap();
    }

    private static boolean personAndRoleOnOneLine(Source source,Map<String,Object> attrs) {
        String name=str(attrs,"person"),role=str(attrs,"personRole");
        return !name.isEmpty() && !role.isEmpty() && Arrays.stream(source.evidence().quote().split("[\\r\\n；;。]"))
            .anyMatch(line->ProofVerifier.contains(line,name)&&ProofVerifier.contains(line,role));
    }

    private static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?>?(Map<String,Object>)value:Collections.emptyMap();
    }
}
