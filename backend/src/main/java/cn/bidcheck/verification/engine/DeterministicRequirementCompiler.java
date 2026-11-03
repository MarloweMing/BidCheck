package cn.bidcheck.verification.engine;

import cn.bidcheck.checktemplate.SourceRef;
import cn.bidcheck.checktemplate.mapping.ClauseNode;
import cn.bidcheck.checktemplate.mapping.CoverageItem;
import cn.bidcheck.checktemplate.mapping.RequirementDraft;
import cn.bidcheck.checktemplate.mapping.RequirementMappingResult;
import cn.bidcheck.checktemplate.mapping.RequirementMappingService;
import cn.bidcheck.checktemplate.mapping.TemplateCandidate;
import cn.bidcheck.domain.Domain.Checkpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.bidcheck.verification.engine.VerificationTypes.*;

/**
 * Turns the code-owned requirement-template inventory into the closed verification rule DSL.
 *
 * <p>The mapper remains an inventory component.  This adapter is the publication boundary used by
 * the 109-check execution path: every accepted parameter keeps its exact requirement fragment,
 * every rule gets a stable source-derived ID, and a fragment is considered covered only when all
 * of its deterministic candidates can be represented by the closed DSL.  Unknown text is never
 * discarded. It is retained as a source-bound pending condition for an explicit rule update.</p>
 */
final class DeterministicRequirementCompiler {
    static final String VERSION = "deterministic-requirement-compiler-14-typed-social-proof";

    /** All registered mapping templates have a server-owned conversion. */
    static final Set<String> SUPPORTED_TEMPLATE_IDS = cn.bidcheck.compat.Java8Api.setOf("MATERIAL_PRESENT", "COMMITMENT_WITH_SEAL", "MATERIAL_ALTERNATIVE", "CERTIFICATE_VALIDITY", "PRICE_COMPARE", "PERSONNEL_ROSTER", "SECTION_CHECKLIST", "ENTITY_FIELD_MATCH", "PERSONNEL_CREDENTIAL", "CONSORTIUM_RESPONSIBILITY", "PRICE_RECONCILE", "DURATION_COMPARE", "CROSS_DOCUMENT_CONSISTENCY", "FORM_FIELD_COMPLETENESS", "DATE_RELATION", "TEXT_ANOMALY", "OBJECTIVE_SCORING", "QUALITY_ADVISORY", "EXTERNAL_CHECK");

    private static final Pattern MONTHS = Pattern.compile(
            "(?:自|从)?\\s*((?:20)?[0-9]{2})年\\s*([01]?[0-9])月[^。；;]{0,80}?连续\\s*([0-9一二两三四五六七八九十]+)\\s*个?月");
    private static final Pattern MONTHS_WITHOUT_START = Pattern.compile(
            "连续\\s*([0-9一二两三四五六七八九十]+)\\s*个?月");
    private static final Pattern CONSORTIUM_COUNT = Pattern.compile(
            "联合体成员(?:单位)?(?:[（(]含牵头单位[）)])?\\s*(不得超过|不超过|至多|最多|不少于|至少|为|等于)\\s*([0-9一二两三四五六七八九十]+)\\s*家");
    private static final Pattern ROLE_COUNT = Pattern.compile(
            "(施工企业主要负责人|施工部分负责人|设计部分负责人|拟派项目负责人|项目负责人|项目经理|设计负责人|施工负责人|技术负责人|专职安全员|专职安全生产管理人员)\\s*([0-9一二两三四五六七八九十]+)\\s*(?:人|名)");
    private static final Pattern QUALIFICATION = Pattern.compile(
            "([^，,。；;]{1,70}?(?:营业执照|登记证书|资质证书|资质|许可证|合格证(?:书)?|执业资格证书|注册证书|体系认证(?:证书)?))");
    private static final Pattern EXPLICIT_ROLE = Pattern.compile("(" + RequirementContextBindings.ROLES + ")");
    private static final Pattern DATE = Pattern.compile("((?:20)?[0-9]{2})年\\s*([01]?[0-9])月\\s*([0-3]?[0-9])日");

    private final ObjectMapper json;

    DeterministicRequirementCompiler(ObjectMapper json) {
        this.json = json == null ? new ObjectMapper() : json;
    }

    Result compile(Checkpoint checkpoint, String requirement, RequirementSourceContext.Context sourceContext,
                   Map<String, Object> runContext) {
        String source = cn.bidcheck.compat.Java8Api.strip(Objects.toString(requirement, ""));
        if (cn.bidcheck.compat.Java8Api.isBlank(source)) return Result.empty(json, source);

        Rule confirmedDuration=confirmedDuration(checkpoint,source);
        if(confirmedDuration!=null)return single(source,confirmedDuration);

        Rule registration=LegalRegistrationProof.compile(source);
        if(registration!=null)return single(source,registration);

        Rule bidLetterResponse=BidLetterResponse.compile(source);
        if(bidLetterResponse!=null)return single(source,bidLetterResponse);

        Rule conditionalMaterial=ConditionalMaterialEvidence.compile(checkpoint,source,sourceContext,runContext);
        if(conditionalMaterial!=null)return single(source,conditionalMaterial);
        Rule compound=CompoundBusinessRequirements.compile(checkpoint,source);
        if(compound!=null)return single(source,compound);
        Rule payment=PaymentRequirementProof.compile(checkpoint,source);
        if(payment!=null)return single(source,payment);
        List<Rule> signatures=SourceSignatureRequirements.compile(checkpoint,source,sourceContext,runContext);
        if(!signatures.isEmpty())return selected(source,signatures);

        List<Rule> routed=RequirementBusinessRouting.compile(checkpoint,source,sourceContext,runContext);
        if(!routed.isEmpty())return selected(source,routed);
        List<Rule> inventory=SourceRequirementInventory.compile(checkpoint,source,sourceContext,runContext);
        if(!inventory.isEmpty())return selected(source,inventory);

        List<Rule> restoredScoring=SourceScoringRules.compile(checkpoint,source,sourceContext,runContext);
        if(!restoredScoring.isEmpty())return selected(source,restoredScoring);

        Rule qualificationTree=qualificationTree(source);
        if(qualificationTree!=null)return single(source,qualificationTree);
        Rule personnelTree=personnelQualificationTree(source);
        if(personnelTree!=null)return single(source,personnelTree);

        // Some table rows are one source-bound obligation. Splitting the response from
        // its referenced form loses the response mode and creates unrelated conditions.
        Rule response = formResponse(source);
        if (response != null) return single(source, response);
        Rule invoice = invoiceAlternatives(checkpoint, source, runContext);
        if (invoice != null) return single(source, invoice);
        if (nameComparisonOnly(source)) {
            Map<String,Object> p=nativeParams("BIDDER_NAME");
            p.put("comparisonTargetReference", clean(source).split("与",2)[1].split("一致",2)[0]);
            p.put("comparisonOperandKind", "SOURCE_REFERENCE");
            p.put("nameChangeEvidenceRequiredWhenDifferent", source.contains("变更"));
            return single(source,rule("NAME-CONSISTENCY","投标人名称与对应证照一致","SAME_FIELD","投标人","投标人名称","=","","",p,source));
        }

        SourceRef rootSource = sourceRef(checkpoint, source, sourceContext);
        ClauseNode.LocalContext local = new ClauseNode.LocalContext("", "", null, "",
                lot(source), inheritedSubject(sourceContext), "TENDER_REQUIREMENT",
                ClauseNode.Boundary.CURRENT_TENDER, cn.bidcheck.compat.Java8Api.listOf());
        String stableSourceId = "requirement:" + identity(source).substring(0, 24);
        ClauseNode root = new ClauseNode(stableSourceId, ClauseNode.Kind.PARAGRAPH,
                source, rootSource, local, cn.bidcheck.compat.Java8Api.listOf());
        RequirementMappingService mapper = new RequirementMappingService(externalAdapters(runContext));
        RequirementMappingResult mapped = mapper.map(mappingRoots(root,sourceContext));
        Map<String, RequirementDraft> drafts = new LinkedHashMap<>();
        for (RequirementDraft draft : mapped.drafts()) drafts.put(draft.fragmentId(), draft);

        List<Rule> rules = new ArrayList<>();
        List<FragmentCoverage> ledger = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        Set<String> templates = new LinkedHashSet<>();

        for (CoverageItem item : mapped.coverage()) {
            RequirementDraft draft = drafts.get(item.fragmentId());
            List<Rule> converted = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            if (draft != null && !draft.templateCandidates().isEmpty()) {
                for (TemplateCandidate candidate : draft.templateCandidates()) {
                    templates.add(candidate.templateId());
                    if(submissionException(draft.text()))continue;
                    if("MATERIAL_PRESENT".equals(candidate.templateId())&&!certificateProofRules("probe",draft.text(),"",source).isEmpty())continue;
                    // This material phrase names the evidence for the same closed MONTHS
                    // predicate; it is not an additional verbatim document-content duty.
                    if("MATERIAL_PRESENT".equals(candidate.templateId())&&socialMonthProofOnly(draft.text()))continue;
                    if("MATERIAL_PRESENT".equals(candidate.templateId())
                            &&!cn.bidcheck.compat.Java8Api.isBlank(text(candidate.parameters(),"materialType"))
                            &&namedMaterialRules("probe",draft.text(),"").stream()
                                .anyMatch(named->named.expected().contains(text(candidate.parameters(),"materialType"))))continue;
                    if (candidate.origin() != TemplateCandidate.Origin.DETERMINISTIC_RULE) {
                        reasons.add("CLASSIFIER_CANDIDATE_NOT_EXECUTABLE:" + candidate.templateId());
                        continue;
                    }
                    if (!candidate.missingParameters().isEmpty()) {
                        if ("CERTIFICATE_VALIDITY".equals(candidate.templateId())
                                && candidate.missingParameters().stream().allMatch("comparisonDate"::equals)
                                && draft.text().contains("有效")) {
                            if (cn.bidcheck.compat.Java8Api.setOf("证书", "证照", "材料").contains(text(candidate.parameters(), "certificateType"))
                                    && draft.text().matches("(?s).*(?:或|或者).*")) {
                                reasons.add("MISSING_PARAMETERS:" + candidate.templateId() + ":specificAlternative");
                                continue;
                            }
                            converted.addAll(convert(candidate, draft, checkpoint, source));
                            continue;
                        }
                        reasons.add("MISSING_PARAMETERS:" + candidate.templateId() + ":"
                                + String.join(",", candidate.missingParameters()));
                        continue;
                    }
                    // Narrow leader/count/appointment clauses must not acquire an invented
                    // consortium agreement or responsibility schedule from the broad mapper.
                    if ("CONSORTIUM_RESPONSIBILITY".equals(candidate.templateId())
                            && !draft.text().matches("(?s).*(?:协议|职责|分工|责任).*") ) continue;
                    List<Rule> candidateRules = convert(candidate, draft, checkpoint, source);
                    if (candidateRules.isEmpty()) reasons.add("TEMPLATE_BRIDGE_UNAVAILABLE:" + candidate.templateId());
                    else converted.addAll(candidateRules);
                }
            }

            if (item.status() != CoverageItem.Status.NON_CHECKING) {
                List<Rule> nativeRules = nativeRules(item, checkpoint, source);
                for(Rule nativeRule:nativeRules){List<Rule> nativeLeaves=new ArrayList<>();flatten(nativeRule,nativeLeaves);
                    for(Rule leaf:nativeLeaves)if("UNSUPPORTED".equals(leaf.predicate()))
                        reasons.add("NATIVE_RULE_UNSUPPORTED:"+text(leaf.parameters(),"deterministicFamily"));}
                for (Rule nativeRule : nativeRules) if (converted.stream().noneMatch(existing -> sameObligation(existing, nativeRule)))
                    converted.add(nativeRule);
                reasons.removeIf(reason -> represented(reasonTemplate(reason), converted));
            }

            // NON_CHECKING is accepted only when the source mapper itself proved it structural.
            boolean nonChecking = item.status() == CoverageItem.Status.NON_CHECKING;
            boolean covered = nonChecking || (!converted.isEmpty() && reasons.isEmpty()
                    && converted.stream().noneMatch(DeterministicRequirementCompiler::containsUnsupported));
            if (covered) {
                rules.addAll(converted);
                ledger.add(new FragmentCoverage(item.fragmentId(), item.text(), item.sourceRef(),
                        nonChecking ? "NON_CHECKING" : "RULE", cn.bidcheck.compat.Java8Api.toList(converted.stream().map(Rule::id)),
                        item.templateIds(), "", true));
            } else {
                // One source fragment may legitimately match more than one template. A missing
                // parameter on one candidate must not throw away another candidate that already
                // compiled into a closed, source-grounded rule. Keep those rules immutable and ask
                // the constrained compiler only for the unresolved part of this same fragment.
                List<Rule> partial = cn.bidcheck.compat.Java8Api.toList(converted.stream()
                        .filter(rule -> !containsUnsupported(rule)));
                rules.addAll(partial);
                String reason = reasons.isEmpty() ? item.reasonCode() : String.join(";", reasons);
                gaps.add(new Gap(item.fragmentId(), item.text(), item.sourceRef(),
                        cn.bidcheck.compat.Java8Api.isBlank(reason) ? "NO_DETERMINISTIC_RULE" : reason));
                ledger.add(new FragmentCoverage(item.fragmentId(), item.text(), item.sourceRef(),
                        partial.isEmpty() ? "GAP" : "PARTIAL",
                        cn.bidcheck.compat.Java8Api.toList(partial.stream().map(Rule::id)), item.templateIds(), reason, false));
            }
        }

        rules = new ArrayList<>(cn.bidcheck.compat.Java8Api.toList(deduplicate(rules).stream().map(r->secureInvoiceRule(r,checkpoint,source,runContext))));
        // Presence is a separate obligation. A reference to a prescribed form must
        // retain a content/filled-field audit even when its material rule is satisfied.
        rules.addAll(TenderFormAudit.compile(checkpoint, source, runContext));
        Rule summary=SourceRequirementInventory.dependencyRule(checkpoint,source,sourceContext,runContext);
        if(summary!=null){
            rules.add(summary);
            // Replace only the exact reference fragment represented by this validated
            // dependency. Any extra obligation in a mapper gap remains unresolved.
            gaps.removeIf(g->SourceRequirementInventory.dependencyCovers(g.text(),summary,sourceContext));
            for(int i=0;i<ledger.size();i++){
                FragmentCoverage row=ledger.get(i);
                if(SourceRequirementInventory.dependencyCovers(row.text(),summary,sourceContext))
                    ledger.set(i,new FragmentCoverage(row.fragmentId(),row.text(),row.source(),"RULE",cn.bidcheck.compat.Java8Api.listOf(summary.id()),row.templateIds(),"SOURCE_DEPENDENCY_EXECUTION",true));
            }
        }
        if(source.matches("(?s).*投标保证金.*(?:按|按照).{0,20}规定缴纳.*")
                &&rules.stream().allMatch(r->"MATERIAL".equals(r.predicate())))
            rules.add(rule("PAYMENT-COVERAGE","保证金实际缴纳要求尚未展开","UNSUPPORTED","","payment","","","",
                    cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "PAYMENT_OBLIGATION_REFERENCE_REQUIRED", "compilationIssue", "提交凭证不证明缴纳金额、方式和时点满足引用要求"),source));
        if(source.matches("(?s).*(?:清单(?:详见|见)|等(?:必须|必需|必备)的(?:承诺)?(?:文件|材料)).*")
                &&rules.stream().anyMatch(r->"MATERIAL".equals(r.predicate())))
            rules.add(rule("INVENTORY-COVERAGE","必备材料全集尚未展开核验","UNSUPPORTED","","materialInventory","","","",
                    cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "REQUIREMENT_INVENTORY_UNRESOLVED", "compilationIssue", "定位到一份材料不能证明引用清单中的全部必备文件齐全"),source));
        String logic = topLogic(source, rules);
        ObjectNode rootJson = json.createObjectNode();
        rootJson.put("logic", logic);
        ArrayNode rows = rootJson.putArray("rules");
        rules.forEach(rule -> rows.add(ruleNode(rule)));
        rootJson.putArray("policyNotes");
        String digest = identity(VERSION + "|" + source + "|" + cn.bidcheck.compat.Java8Api.toList(rules.stream().map(SharedFactReuse::signature))
                + "|" + cn.bidcheck.compat.Java8Api.toList(gaps.stream().map(Gap::identity)));
        return new Result(rootJson, cn.bidcheck.compat.Java8Api.listCopyOf(rules), cn.bidcheck.compat.Java8Api.listCopyOf(ledger), cn.bidcheck.compat.Java8Api.listCopyOf(gaps),
                cn.bidcheck.compat.Java8Api.setCopyOf(templates), digest, source, json);
    }

    private Result single(String source, Rule rule) {
        return selected(source,cn.bidcheck.compat.Java8Api.listOf(rule));
    }
    /** Preserve reviewed numeric intent only when its field/value/unit are visible in this
     * exact obligation. An omitted comparison in a labelled tender table does not erase the
     * confirmed comparator; an explicit contradictory comparator always prevents preservation. */
    Rule confirmedDuration(Checkpoint checkpoint,String source) {
        if(checkpoint==null||!checkpoint.confirmed()||!"VALUE_COMPARE".equals(checkpoint.type()))return null;
        String label;
switch (Objects.toString(checkpoint.field(),"")) {case "bidValidity":label = "投标有效期"; break;case "designDuration":label = "设计工期"; break;case "constructionDuration":label = "施工工期"; break;case "duration":label = "(?:总工期|项目工期|工期)"; break;default:label = ""; break;}
        if(cn.bidcheck.compat.Java8Api.isBlank(label)||!cn.bidcheck.compat.Java8Api.setOf(">=", "<=", "=", ">", "<").contains(Objects.toString(checkpoint.operator(),"")))return null;
        Matcher m=Pattern.compile("(?:"+label+")[\\s:：]*(不少于|不低于|至少|不得少于|不超过|不得超过|至多|不得高于|不高于|大于|超过|小于|少于|等于|为|≥|≤|>=|<=|=)?[\\s:：]*([0-9]+(?:\\.[0-9]+)?)[\\s]*(日历日|天|日|个月|月|年)").matcher(source);
        if(!m.find())return null;
        String comparator=m.group(1),value=m.group(2),unit=m.group(3),fragment=m.group();int from=m.start(),to=m.end();
        if(m.find())return null;
        try{if(new BigDecimal(value).compareTo(new BigDecimal(checkpoint.expected()))!=0)return null;}catch(RuntimeException invalid){return null;}
        String normalizedUnit;
switch (unit) {case "日历日":case "天":case "日":normalizedUnit = "DAY"; break;case "个月":case "月":normalizedUnit = "MONTH"; break;case "年":normalizedUnit = "YEAR"; break;default:normalizedUnit = ""; break;}
        String reviewedUnit;
switch (Objects.toString(checkpoint.unit(),"")) {case "日历日":case "天":case "日":case "DAY":reviewedUnit = "DAY"; break;case "个月":case "月":case "MONTH":reviewedUnit = "MONTH"; break;case "年":case "YEAR":reviewedUnit = "YEAR"; break;default:reviewedUnit = ""; break;}
        if(!normalizedUnit.equals(reviewedUnit))return null;
        if(comparator!=null){String op;
switch (comparator) {case "不少于":case "不低于":case "至少":case "不得少于":case "≥":case ">=":op = ">="; break;case "不超过":case "不得超过":case "至多":case "不得高于":case "不高于":case "≤":case "<=":op = "<="; break;case "大于":case "超过":op = ">"; break;case "小于":case "少于":op = "<"; break;default:op = "="; break;}if(!op.equals(checkpoint.operator()))return null;}
        // Only a table label/number may accompany this obligation; never discard other duties.
        String remainder=(source.substring(0,from)+source.substring(to)).replaceAll("[\\s:：|。.;；0-9]","");
        if(!cn.bidcheck.compat.Java8Api.isBlank(remainder)&&!remainder.equals("投标有效期"))return null;
        return rule("CONFIRMED-DURATION",checkpoint.title(),"NUMBER","",checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),
                cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "SOURCE_VERIFIED_CONFIRMED_DURATION", "comparisonBasis", comparator==null?"CONFIRMED_CHECKPOINT_WITH_SOURCE_VALUE":"EXPLICIT_SOURCE_COMPARATOR", "sourceValue", value, "sourceUnit", unit),fragment);
    }
    private Result selected(String source,List<Rule> rules) {
        ObjectNode root=json.createObjectNode();root.put("logic","ALL");ArrayNode nodes=root.putArray("rules");rules.forEach(r->nodes.add(ruleNode(r)));root.putArray("policyNotes");
        String digest=identity(VERSION+"|"+source+"|"+cn.bidcheck.compat.Java8Api.toList(rules.stream().map(SharedFactReuse::signature)));
        return new Result(root,cn.bidcheck.compat.Java8Api.listCopyOf(rules),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.setOf(),digest,source,json);
    }
    private static boolean nameComparisonOnly(String source) {
        String certificate="(?:营业执照|登记证书|资质证书|其他组织证明)";
        return clean(source).matches("^(?:(?:[0-9]+(?:\\.[0-9]+)*)?形式评审)?投标人名称[:：]?与"+certificate+"(?:[、或及和]"+certificate+")*一致[，,](?:招标文件规定必须提交的文件中)?(?:投标人)?名称如有变更须附有效证明文件[。]?$" );
    }
    private Rule formResponse(String source) {
        String compact=clean(source);
        if(!compact.matches("工期[：:]?满足[。.]?[（(]见投标函[）)][。.]?"))return null;
        String field="durationCommitment";
        return rule("FORM-RESPONSE-"+field,"投标函承诺响应","DECLARATION","",field,"",compact.startsWith("质量")?"合格":"满足","",
                cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "TENDER_FORM_RESPONSE", "documentType", "投标函", "responseField", field),source);
    }

    /** The tender permits two complete alternatives, not a generic promise document. */
    private Rule invoiceAlternatives(Checkpoint checkpoint,String source,Map<String,Object> context){
        if(!clean(source).matches("(?:其他要求)?(?:(?:本项目须)?提供增值税专用发票[。；;])?提供一般纳税人证明文件或可开具增值税专用发票承诺书[（(]格式可参考第[一二三四五六七八九十0-9]+章中《增值税专用发票承诺书》[）)][。；;]?"))return null;
        String base="INVOICE-"+identity(source).substring(0,12);
        Rule taxpayer=rule(base+"-TAXPAYER","当前一般纳税人资格证明","MATERIAL","投标人","material","","一般纳税人证明文件","",
                cn.bidcheck.compat.Java8Api.mapOf("currentTaxpayerProof", true, "deterministicFamily", "CURRENT_TAXPAYER_PROOF"),source);
        Rule promise=TenderFormAudit.namedRule(checkpoint,source,"增值税专用发票承诺书",context,true,base+"-PROMISE");
        return group(base,source,"ANY",cn.bidcheck.compat.Java8Api.listOf(taxpayer, promise));
    }
    private Rule secureInvoiceRule(Rule r,Checkpoint cp,String source,Map<String,Object> context){
        List<Rule> children=cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(c->secureInvoiceRule(c,cp,source,context)));
        if(r.predicate().equals("MATERIAL")&&r.expected().contains("一般纳税人")){
            Map<String,Object> p=new LinkedHashMap<>(r.parameters());p.put("currentTaxpayerProof",true);
            return new Rule(r.id(),r.description(),r.predicate(),cn.bidcheck.compat.Java8Api.isBlank(r.subject())?"投标人":r.subject(),r.field(),r.operator(),r.expected(),r.unit(),p,r.sourceQuote(),r.logic(),children);
        }
        if(cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "DECLARATION").contains(r.predicate())&&r.expected().matches(".*(?:承诺书|开具增值税专用发票).*")
                &&r.sourceQuote().contains("增值税")
                &&clean(source).contains("一般纳税人证明文件或可开具增值税专用发票承诺书"))
            return TenderFormAudit.namedRule(cp,source,"增值税专用发票承诺书",context,true,r.id());
        return new Rule(r.id(),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),r.parameters(),r.sourceQuote(),r.logic(),children);
    }

    private List<Rule> convert(TemplateCandidate candidate, RequirementDraft draft, Checkpoint checkpoint,
                               String requirementSource) {
        Map<String, Object> p = new LinkedHashMap<>(candidate.parameters());
        p.put("templateId", candidate.templateId());
        p.put("templateVersion", candidate.templateVersion());
        p.put("templateCompilerVersion", VERSION);
        p.put("templateParameterSources", parameterSourceAudit(candidate));
        String quote = exactQuote(requirementSource, draft.text());
        String subject = subject(candidate, draft, quote);
        String base = stableId(candidate.templateId(), draft.fragmentId(), quote, "0");
        switch (candidate.templateId()) {case "MATERIAL_PRESENT":return cn.bidcheck.compat.Java8Api.listOf(rule(base, quote, "MATERIAL", subject, "material", "", text(p, "materialType"), "", p, quote));case "COMMITMENT_WITH_SEAL":return cn.bidcheck.compat.Java8Api.listOf(commitment(base, quote, subject, p));case "MATERIAL_ALTERNATIVE":return cn.bidcheck.compat.Java8Api.listOf(materialAlternatives(base, quote, subject, p));case "CERTIFICATE_VALIDITY":return cn.bidcheck.compat.Java8Api.listOf(validity(base, quote, subject, p));case "PRICE_COMPARE":return cn.bidcheck.compat.Java8Api.listOf(price(base, quote, subject, p));case "PERSONNEL_ROSTER":return personnelRoster(base, quote, p);case "SECTION_CHECKLIST":return cn.bidcheck.compat.Java8Api.listOf(checklist(base, quote, subject, p));case "ENTITY_FIELD_MATCH":return cn.bidcheck.compat.Java8Api.listOf(entityMatch(base, quote, subject, p));case "PERSONNEL_CREDENTIAL":return cn.bidcheck.compat.Java8Api.listOf(personnelCredential(base, quote, p));case "CONSORTIUM_RESPONSIBILITY":return cn.bidcheck.compat.Java8Api.listOf(consortium(base, quote, p));case "PRICE_RECONCILE":return cn.bidcheck.compat.Java8Api.listOf(priceReconcile(base, quote, subject, p));case "DURATION_COMPARE":return cn.bidcheck.compat.Java8Api.listOf(number(base, quote, subject, text(p, "field"),
                    text(p, "operator"), text(p, "expected"), text(p, "unit"), p));case "CROSS_DOCUMENT_CONSISTENCY":return cn.bidcheck.compat.Java8Api.listOf(consistency(base, quote, subject, p));case "FORM_FIELD_COMPLETENESS":return cn.bidcheck.compat.Java8Api.listOf(formCompleteness(base, quote, subject, p));case "DATE_RELATION":return cn.bidcheck.compat.Java8Api.listOf(dateRelation(base, quote, subject, p));case "TEXT_ANOMALY":return cn.bidcheck.compat.Java8Api.listOf(textAnomaly(base, quote, subject, p));case "OBJECTIVE_SCORING":return cn.bidcheck.compat.Java8Api.listOf(objectiveScore(base, quote, subject, p));case "QUALITY_ADVISORY":return cn.bidcheck.compat.Java8Api.listOf(quality(base, quote, subject, p));case "EXTERNAL_CHECK":return external(base, quote, subject, p);default:return cn.bidcheck.compat.Java8Api.listOf();}
    }

    private Rule commitment(String id, String quote, String subject, Map<String, Object> p) {
        String material = text(p, "materialType");
        List<Rule> children = new ArrayList<>();
        children.add(rule(id + "-BODY", "提交" + material, "MATERIAL", subject, "material", "", material, "", p, quote));
        int index = 0;
        for (String point : strings(p.get("requiredCommitmentPoints"))) {
            Map<String, Object> params = new LinkedHashMap<>(p);params.put("commitmentPoint", point);
            children.add(rule(id + "-POINT-" + (++index), "承诺内容", "DECLARATION", subject,
                    "commitment", "", point, "", params, quote));
        }
        Map<String, Object> visual = new LinkedHashMap<>(p);
        visual.put("documentType", material);visual.put("visualType", "SEAL");
        children.add(rule(id + "-SEAL", "要求位置盖章", "VISUAL", subject, "seal", "", "", "", visual, quote));
        return group(id, quote, "ALL", children);
    }

    private Rule materialAlternatives(String id, String quote, String subject, Map<String, Object> p) {
        List<Rule> children = new ArrayList<>();int at = 0;
        for (String material : strings(p.get("alternatives"))) {
            Map<String, Object> params = new LinkedHashMap<>(p);params.put("materialType", material);
            children.add(rule(id + "-ALT-" + (++at), "替代材料：" + material, "MATERIAL", subject,
                    "material", "", material, "", params, quote));
        }
        return group(id, quote, "ANY", children);
    }

    private Rule validity(String id, String quote, String subject, Map<String, Object> p) {
        Map<String, Object> params = new LinkedHashMap<>(p);
        params.put("category", text(p, "certificateType"));
        String validityField = explicitValidityField(quote);
        if (!cn.bidcheck.compat.Java8Api.isBlank(validityField)) params.put("validityField", validityField);
        String event = referenceEvent(quote);
        if (!cn.bidcheck.compat.Java8Api.isBlank(event)) { params.put("referenceEvent", event);params.put("requiresReferenceDate", true); }
        return rule(id, quote, "VALIDITY", subject, "有效期", "", text(p, "comparisonDate"), "", params, quote);
    }

    private Rule price(String id, String quote, String subject, Map<String, Object> p) {
        Map<String, Object> params = new LinkedHashMap<>(p);
        params.put("amountBasis", amountBasis(quote, text(p, "field")));
        params.put("taxBasis", taxBasis(text(p, "taxBasis")));
        params.put("currency", currency(text(p, "currency")));
        return number(id, quote, subject, "amount", text(p, "operator"), text(p, "expected"), text(p, "unit"), params);
    }

    private List<Rule> personnelRoster(String id, String quote, Map<String, Object> p) {
        String role = text(p, "role");List<Rule> result = new ArrayList<>();
        Integer min = integer(p.get("minCount")), max = integer(p.get("maxCount"));
        if (min != null) result.add(rule(id + "-MIN", quote, "PERSON_COUNT", role, "personnelCount",
                comparatorInQuote(quote, role, min, ">="), Integer.toString(min), "人", p, quote));
        if (max != null) result.add(rule(id + "-MAX", quote, "PERSON_COUNT", role, "personnelCount",
                "<=", Integer.toString(max), "人", p, quote));
        return result;
    }

    private Rule checklist(String id, String quote, String subject, Map<String, Object> p) {
        List<Rule> children = new ArrayList<>();int at = 0;
        for (String item : strings(p.get("requiredItems"))) {
            Map<String, Object> params = new LinkedHashMap<>(p);params.put("materialType", item);
            children.add(rule(id + "-ITEM-" + (++at), "清单项：" + item, "MATERIAL", subject,
                    "material", "", item, "", params, quote));
        }
        return group(id, quote, "ALL", children);
    }

    private Rule entityMatch(String id, String quote, String subject, Map<String, Object> p) {
        String field = displayField(quote, text(p, "leftField"));
        Map<String, Object> params = new LinkedHashMap<>(p);params.put("comparisonField", text(p, "rightField"));
        return rule(id, quote, "SAME_FIELD", subject, field, "=", "", "", params, quote);
    }

    private Rule personnelCredential(String id, String quote, Map<String, Object> p) {
        Map<String, Object> params = new LinkedHashMap<>(p);
        params.put("category", text(p, "certificateType"));
        if (!cn.bidcheck.compat.Java8Api.isBlank(text(p, "requiredLevel"))) params.put("grade", text(p, "requiredLevel"));
        if (Boolean.TRUE.equals(p.get("requireEmployerMatch"))) params.put("employer", "投标人");
        return rule(id, quote, "CERTIFICATE", text(p, "role"), "certificate", "", "", "", params, quote);
    }

    private Rule consortium(String id, String quote, Map<String, Object> p) {
        List<Rule> children = new ArrayList<>();
        String material = text(p, "materialType");
        children.add(rule(id + "-AGREEMENT", "联合体协议", "MATERIAL", "联合体成员", "material", "", material, "", p, quote));
        int at = 0;
        for (String responsibility : strings(p.get("requiredResponsibilities"))) {
            Map<String, Object> params = new LinkedHashMap<>(p);params.put("object", responsibility);
            children.add(rule(id + "-RESP-" + (++at), "联合体责任分工", "RELATION", "联合体成员",
                    "负责", "", responsibility, "", params, quote));
        }
        if (!cn.bidcheck.compat.Java8Api.isBlank(text(p, "leadRole"))) {
            children.add(rule(id + "-LEAD", "联合体牵头关系", "RELATION", "联合体牵头人",
                    "牵头", "", "联合体成员", "", p, quote));
        }
        return group(id, quote, "ALL", children);
    }

    private Rule priceReconcile(String id, String quote, String subject, Map<String, Object> p) {
        Map<String, Object> params = new LinkedHashMap<>(p);
        // The mapper identifies the commercial fields, while this boundary keeps only dimensions
        // which are actually stated by the source.  An absent currency/unit/tax basis remains
        // absent and is resolved from mutually compatible bid facts, never invented by the model.
        params.put("currency", explicitCurrency(quote, text(p, "currency")));
        params.put("taxBasis", explicitTaxBasis(quote, text(p, "taxBasis")));
        params.put("totalPriceLayer", cn.bidcheck.compat.Java8Api.isBlank(text(p, "totalPriceLayer"))
                ? text(p, "priceLayer") : text(p, "totalPriceLayer"));
        params.remove("priceLayer");
        params.putIfAbsent("tolerance", "0");
        String calculation = text(p, "calculation");
        if (!cn.bidcheck.compat.Java8Api.setOf("TOTAL_EQUALS_COMPONENT_SUM", "AMOUNT_REPRESENTATIONS_EQUAL").contains(calculation)) {
            calculation = "TOTAL_EQUALS_COMPONENT_SUM";
        }
        params.put("calculation", calculation);
        return rule(id, quote, "PRICE_RECONCILE", subject, text(p, "totalField"), "=", "",
                explicitMoneyUnit(quote, text(p, "unit")), params, quote);
    }

    private Rule consistency(String id, String quote, String subject, Map<String, Object> p) {
        String field = displayField(quote, text(p, "field"));
        return rule(id, quote, "SAME_FIELD", subject, field, "=", "", "", p, quote);
    }

    private Rule formCompleteness(String id, String quote, String subject, Map<String, Object> p) {
        List<Rule> children = new ArrayList<>();int at = 0;
        for (String field : strings(p.get("requiredFields"))) {
            Map<String, Object> params = new LinkedHashMap<>(p);params.put("requiredField", field);
            children.add(rule(id + "-FIELD-" + (++at), "表单必填字段：" + field, "MATERIAL", subject,
                    field, "", field, "", params, quote));
        }
        return group(id, quote, "ALL", children);
    }

    private Rule dateRelation(String id, String quote, String subject, Map<String, Object> p) {
        Map<String, Object> params = new LinkedHashMap<>(p);
        params.put("leftDocumentRole", dateDocumentRole(text(p, "leftRole")));
        params.put("rightDocumentRole", dateDocumentRole(text(p, "rightRole")));
        params.put("calculation", "COMPARE_NAMED_DATES");
        return rule(id, quote, "DATE_RELATION", subject, text(p, "leftRole"), text(p, "operator"),
                text(p, "rightRole"), "日", params, quote);
    }

    private Rule textAnomaly(String id, String quote, String subject, Map<String, Object> p) {
        return rule(id, quote, "TEXT_ANOMALY", subject, "textAnomaly", "", "", "", p, quote);
    }

    private Rule objectiveScore(String id, String quote, String subject, Map<String, Object> p) {
        String category=certificateCategory(quote);
        if(!cn.bidcheck.compat.Java8Api.isBlank(category)&&quote.matches("(?s).*(?:具备|具有)"+Pattern.quote(category)+"(?:证书)?[，,]得[0-9]+分[；;。]?.*")){
            Map<String,Object> parameters=new LinkedHashMap<>(p);parameters.put("category",category);parameters.put("score",text(p,"maxScore"));parameters.put("deterministicFamily","CERTIFICATE_SCORING_CRITERION");
            // The certificate is the scored fact. Query/validity/common proof remains separate;
            // the scoring sentence itself is never an additional material to find in the bid.
            return rule(id,quote,"CERTIFICATE",subject,"certificate","","","",parameters,quote);
        }
        // The mapper only marks a single binary material award complete.  It is therefore safe to
        // check its source-bound criterion as a material condition; no model score is accepted.
        Map<String, Object> params = new LinkedHashMap<>(p);params.put("score", text(p, "maxScore"));
        return rule(id, quote, "MATERIAL", subject, "scoringCriterion", "", text(p, "criterion"), "", params, quote);
    }

    private Rule quality(String id, String quote, String subject, Map<String, Object> p) {
        // This template checks only source-body and required-dimension coverage. It deliberately
        // produces no model score, grade or qualitative PASS/FAIL assertion.
        return rule(id, quote, "QUALITY_ADVISORY", subject, "quality", "", "", "", p, quote);
    }

    private List<Rule> external(String id, String quote, String subject, Map<String, Object> p) {
        List<Rule> result = new ArrayList<>();int at = 0;
        for (String field : strings(p.get("requiredFields"))) {
            Map<String, Object> params = new LinkedHashMap<>(p);
            params.put("externalType", text(p, "adapterId"));
            params.put("evaluationStage", text(p, "evaluationStage"));
            params.put("requiresReferenceDate", !cn.bidcheck.compat.Java8Api.isBlank(text(p, "asOfDate")));
            result.add(rule(id + "-FIELD-" + (++at), quote, "EXTERNAL", subject, field,
                    "=", externalExpected(field, quote), "", params, quote));
        }
        return result;
    }

    /** Only a closed payment-evidence sentence can be subsumed. Other requested documents,
     * signatures, insurance types or alternative duties remain independent obligations. */
    static boolean socialMonthProofOnly(String text){
        String q=text.replaceAll("\\s+","").replace('（','(').replace('）',')');
        if(!MONTHS.matcher(q).find()&&!MONTHS_WITHOUT_START.matcher(q).find())return false;
        String time="(?:(?:20)?[0-9]{2}年[0-9]{1,2}月(?:\\(含\\))?(?:至|起至)(?:投标|应答|文件递交|投标文件递交|应答文件递交)?截止(?:时间|日|日期)?)?";
        String count="[0-9一二三四五六七八九十两]+";
        String ordinary="(?:须|应|必须|需)?(?:提交|提供)(?:有效(?:的)?证明文件及)?"+time+"(?:任意)?连续"+count+"个月在本单位缴纳社保(?:证明材料|证明)";
        String exception="(?:[，,]特殊情况(?:提交|提供)说明文件(?:\\(格式自拟\\))?及有效的证明文件\\((?:如)?(?:单位设立时间不足|人员新入职|离退休|转业)(?:[、，,](?:单位设立时间不足|人员新入职|离退休|转业))*(?:等)?\\))?";
        return q.matches(ordinary+exception+"[。；;]?");
    }

    /** Additional closed primitives for the recurrent tender clauses that are not one-template rows. */
    private List<Rule> nativeRules(CoverageItem item, Checkpoint checkpoint, String requirementSource) {
        String quote = exactQuote(requirementSource, item.text());String subject = explicitSubject(quote);
        String base = stableId("NATIVE", item.fragmentId(), quote, "0");
        if(submissionException(quote))return cn.bidcheck.compat.Java8Api.listOf(rule(base+"-APPLICABILITY","需先确定免交材料的适用条件","UNSUPPORTED",subject,"material","","","",
                cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "CONDITIONAL_SUBMISSION_APPLICABILITY", "compilationIssue", "原文明确免交条件，尚未绑定该条件；不得生成无条件材料或签字义务"),quote));
        List<Rule> rules = new ArrayList<>();

        Matcher months = MONTHS.matcher(quote);
        if (months.find()) {
            Map<String, Object> p = nativeParams("SOCIAL_MONTHS");
            p.put("count", chineseNumber(months.group(3)));
            p.put("earliestMonth", fourDigitYear(months.group(1)) + "-" + String.format(Locale.ROOT, "%02d", Integer.parseInt(months.group(2))));
            p.put("requiresReferenceDate", quote.matches("(?s).*(?:截止|截至).*"));
            if (quote.contains("本单位")) p.put("employer", "本单位");
            if(socialMonthProofOnly(quote)&&quote.contains("特殊情况")){p.put("exceptionProofPermitted",true);p.put("exceptionSourceQuote",quote);}
            rules.add(rule(base + "-MONTHS", "连续社保", "MONTHS", cn.bidcheck.compat.Java8Api.isBlank(subject) ? "上述人员" : subject,
                    "socialInsurance", "", "", "月", p, quote));
        } else {
            Matcher plainMonths = MONTHS_WITHOUT_START.matcher(quote);
            if (plainMonths.find() && quote.contains("社保")) {
                Map<String, Object> p = nativeParams("SOCIAL_MONTHS");p.put("count", chineseNumber(plainMonths.group(1)));
                p.put("requiresReferenceDate", quote.matches("(?s).*(?:截止|截至).*"));
                if(quote.contains("本单位"))p.put("employer","本单位");
                rules.add(rule(base + "-MONTHS", "连续社保", "MONTHS", cn.bidcheck.compat.Java8Api.isBlank(subject) ? "上述人员" : subject,
                        "socialInsurance", "", "", "月", p, quote));
            }
        }

        Matcher entityCount = CONSORTIUM_COUNT.matcher(quote);
        if (entityCount.find()) {
            rules.add(rule(base + "-ENTITY-COUNT", "联合体成员数量", "ENTITY_COUNT", "联合体成员",
                    "memberCount", RequirementGrounding.operator(entityCount.group(1)),
                    Integer.toString(chineseNumber(entityCount.group(2))), "家", nativeParams("CONSORTIUM_COUNT"), quote));
        }

        // The mapping vocabulary intentionally stays conservative.  These aliases are common in
        // tender tables and are parsed here with the same closed PERSON_COUNT primitive.
        Matcher roleCount = ROLE_COUNT.matcher(quote);
        int countAt = 0;
        while (roleCount.find()) {
            rules.add(rule(base + "-ROLE-" + (++countAt), "岗位人数", "PERSON_COUNT", roleCount.group(1),
                    "personnelCount", "", Integer.toString(chineseNumber(roleCount.group(2))), "人",
                    nativeParams("PERSONNEL_COUNT"), roleCount.group()));
        }

        if (quote.matches("(?s)(?=.*(?:投标报价|总报价|投标总价))(?=.*(?:唯一|只能.{0,6}(?:一|1)(?:个|份|项)?)).*")) {
            rules.add(rule(base + "-PRICE-UNIQUE", "投标报价唯一", "SAME_FIELD", "投标人",
                    quote.contains("投标总价") ? "投标总价" : quote.contains("总报价") ? "总报价" : "投标报价",
                    "", "", "", nativeParams("PRICE_UNIQUENESS"), quote));
        }

        if (quote.contains("投标人名称") && quote.matches("(?s).*(?:营业执照|资质证书|组织证明).*(?:一致|相同|相符).*")) {
            rules.add(rule(base + "-NAME", "投标人名称一致", "SAME_FIELD", "投标人",
                    "投标人名称", "=", "", "", nativeParams("BIDDER_NAME"), quote));
        }

        if (quote.matches("(?s).*采用电子投标.*不接收纸质投标文件.*")) {
            rules.add(rule(base + "-ELECTRONIC", "电子投标文件", "MATERIAL", "",
                    "material", "", "电子投标", "", nativeParams("ELECTRONIC_SUBMISSION"), quote));
        }

        rules.addAll(namedMaterialRules(base,quote,subject));
        rules.addAll(certificateProofRules(base,quote,subject,requirementSource));
        // A clause that explicitly accepts a named commitment can be executed as a
        // written-response obligation. It does not become a factual/online status check.
        if(quote.matches("(?s).*经营状况[：:\\t].*未处于.*")&&requirementSource.contains("《企业经营状况承诺书》")){
            String point=quote.replaceFirst("^.*?经营状况[：:\\t]","");
            rules.add(rule(base+"-OPERATING-RESPONSE","经营状况书面承诺内容","DECLARATION",subject,"commitment","",point,"",
                    cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "SOURCE_AUTHORIZED_COMMITMENT", "documentType", "企业经营状况承诺书", "proofScope", "WRITTEN_RESPONSE"),quote));
        }
        if(quote.matches("(?s).*上述人员不得超出法律规定的同时承接项目的最大数量承接本项目.*")&&requirementSource.contains("《人员承诺书》"))
            rules.add(rule(base+"-WORKLOAD-RESPONSE","人员在建项目数量书面承诺","DECLARATION","","commitment","",quote.replaceAll("[。；;]$",""),"",
                    cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "SOURCE_AUTHORIZED_COMMITMENT", "documentType", "人员承诺书", "proofScope", "WRITTEN_RESPONSE"),quote));
        if (quote.matches("(?s).*(?:盖章|单位公章|签字|签名章|签署).*")) rules.addAll(signatureRules(base, quote));
        if (quote.matches("(?s).*(?:法定代表人身份证明|授权委托书).*")) rules.addAll(signingAuthority(base, quote));
        if (quote.matches("(?s).*(?:独立法人|其他组织|登记注册|依法设立|合法成立|经营范围未受限).*")
                &&!quote.matches("(?s).*(?:利害关系|不得投标|禁止投标|不得参加|禁止参加|控股|管理关系).*")) {
            Map<String, Object> p = nativeParams("LEGAL_REGISTRATION");
            rules.add(rule(base + "-LEGAL", "依法登记成立", "CERTIFICATE", "投标人",
                    "registrationStatus", "", "", "", p, quote));
        }
        rules.addAll(explicitValidityRules(base, quote, subject));
        rules.addAll(qualificationRules(base, quote, subject));

        if (quote.contains("联合体") && quote.contains("牵头人")) {
            String expected = quote.contains("施工单位作为联合体牵头人") ? "施工单位" : "联合体成员";
            Map<String, Object> p = nativeParams("CONSORTIUM_LEADER");p.put("object", expected);
            rules.add(rule(base + "-LEADER", "联合体牵头关系", "RELATION", "联合体牵头人",
                    "牵头", "", expected, "", p, quote));
        }
        if (quote.matches("(?s).*项目负责人应由(?:联合体)?牵头单位委派.*")) {
            Map<String, Object> p = nativeParams("LEADER_APPOINTMENT");p.put("object", "牵头单位");
            rules.add(rule(base + "-APPOINT", "项目负责人由牵头单位委派", "RELATION", "项目负责人",
                    "委派", "", "牵头单位", "", p, quote));
        }
        return deduplicate(rules);
    }

    /** Named tender forms keep their complete name; “合同条款偏离表” must not become “合同”. */
    private List<Rule> namedMaterialRules(String base,String quote,String subject){
        if(!quote.matches("(?s).*(?:提交|提供|出具|附具|附上).*"))return cn.bidcheck.compat.Java8Api.listOf();
        if(quote.matches("(?s).*(?:任选|或者|或|如为|如有|若|联合体投标时|无需|无须|不需|免于).*"))return cn.bidcheck.compat.Java8Api.listOf();
        Matcher named=Pattern.compile("《([^》]{0,60}(?:承诺书|承诺函|偏离表|委托书|投标函|证明书|协议书))》").matcher(quote);
        Set<String> names=new LinkedHashSet<>();while(named.find())names.add(named.group(1));
        List<Rule> result=new ArrayList<>();int at=0;
        for(String name:names)result.add(rule(base+"-NAMED-"+(++at),"提交"+name,"MATERIAL",subject,"material","",name,"",nativeParams("NAMED_MATERIAL"),quote));
        return result;
    }

    /** Closed ancillary proof clause. Consistency remains a source-reference comparison;
     * merely locating the screenshot can never establish agreement or certificate registration. */
    private List<Rule> certificateProofRules(String base,String quote,String subject,String requirementSource){
        String normalized=quote.replaceFirst("^[ /\\t]+","");
        if(!normalized.equals(quote))return certificateProofRules(base,normalized,subject,requirementSource);
        String effectiveSubject=cn.bidcheck.compat.Java8Api.isBlank(subject)?explicitSubject(requirementSource):subject;
        if(quote.matches("^(?:证明材料|证明文件要求)[:：](?:须|需|应|必须)(?:提交|提供)有效证明文件，并提供查询网址及网址查询截图[。；;]?$")){
            Map<String,Object> p=nativeParams("CERTIFICATE_PROOF_DOCUMENTS");
            String category=certificateCategory(requirementSource);
            if(!cn.bidcheck.compat.Java8Api.isBlank(category)){
                String certificateSource=Arrays.stream(requirementSource.split("\\n")).filter(line->line.contains(category)).findFirst().orElse("");
                Map<String,Object> certificateParams=new LinkedHashMap<>(p);certificateParams.put("category",category);certificateParams.put("sharedProofSourceQuote",quote);
                Map<String,Object> queryParams=new LinkedHashMap<>(p);queryParams.put("certificateProofCategory",category);queryParams.put("certificateRequirementQuote",certificateSource);
                Map<String,Object> chainParams=new LinkedHashMap<>(queryParams);chainParams.put("certificateProofRequireValidity",true);
                return cn.bidcheck.compat.Java8Api.listOf(rule(base+"-PROOF","提供对应证书证明文件","CERTIFICATE",effectiveSubject,"certificate","","","",certificateParams,certificateSource), rule(base+"-URL","提供对应认证证书查询网址","MATERIAL",effectiveSubject,"material","","查询网址","",queryParams,quote), rule(base+"-SCREENSHOT","提供对应认证证书查询截图","MATERIAL",effectiveSubject,"material","","查询截图","",queryParams,quote), rule(base+"-CHAIN","同一证书的查询与有效期证据完整对应","MATERIAL",effectiveSubject,"material","","同证书查询及有效期","",chainParams,quote), rule(base+"-VALIDITY","对应证书有效性","VALIDITY",effectiveSubject,"有效期","","","",certificateParams,certificateSource));
            }
            return cn.bidcheck.compat.Java8Api.listOf(rule(base+"-PROOF","提供证书证明文件","MATERIAL",subject,"material","","证明文件","",p,quote), rule(base+"-URL","提供查询网址","MATERIAL",subject,"material","","查询网址","",p,quote), rule(base+"-SCREENSHOT","提供查询截图","MATERIAL",subject,"material","","查询截图","",p,quote), rule(base+"-ASSOCIATION","共用证明材料需对应当前证书并核实有效性","UNSUPPORTED",subject,"certificate","","","",nativeParams("SHARED_CERTIFICATE_PROOF_ASSOCIATION"),quote));
        }
        if(!quote.matches("^(?:须|需|应|必须)(?:提交|提供)有效证明文件[（(]并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致[）)](?:[（(]注册类资质须已在本单位完成注册[）)])?[。；;]?$"))return cn.bidcheck.compat.Java8Api.listOf();
        List<Rule> result=new ArrayList<>();Map<String,Object> p=nativeParams("CERTIFICATE_PROOF_DOCUMENTS");
        p.put("certificateRequirementQuote",requirementSource);
        result.add(rule(base+"-PROOF","提供证书证明文件","MATERIAL",subject,"material","","证明文件","",p,quote));
        result.add(rule(base+"-URL","提供查询网址","MATERIAL",subject,"material","","查询网址","",p,quote));
        result.add(rule(base+"-SCREENSHOT","提供查询截图","MATERIAL",subject,"material","","查询截图","",p,quote));
        result.add(rule(base+"-MATCH","查询截图与证明文件信息一致","SAME_FIELD",subject,"证书信息","","","",p,quote));
        if(quote.contains("注册类资质")){
            Map<String,Object> registration=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "CERTIFICATE_REGISTRATION", "registrationConditional", true, "credentialRequirement", requirementSource));
            if(cn.bidcheck.compat.Java8Api.setOf("设计负责人", "设计部分负责人").contains(effectiveSubject)&&requirementSource.contains(effectiveSubject))registration.put("registrationEmployerRole","设计单位");
            if(cn.bidcheck.compat.Java8Api.setOf("施工负责人", "施工部分负责人").contains(effectiveSubject)&&requirementSource.contains(effectiveSubject))registration.put("registrationEmployerRole","施工单位");
            result.add(rule(base+"-REGISTRATION","注册类资质在本单位完成注册","RELATION",effectiveSubject,"注册单位","","本单位","",registration,quote));
        }
        return result;
    }

    private static String certificateCategory(String text){
        for(String category:cn.bidcheck.compat.Java8Api.listOf("职业健康安全管理体系认证", "质量管理体系认证", "环境管理体系认证", "安全生产许可证", "营业执照", "登记证书"))
            if(text.contains(category))return category;
        return "";
    }
    private static boolean submissionException(String quote){return quote.matches("(?s).*(?:无需|无须|不需|免于)(?:提供|提交|出具).*" );}

    private List<Rule> explicitValidityRules(String base, String quote, String inheritedSubject) {
        int marker = quote.indexOf("有效");
        if (marker < 0 || !quote.substring(marker).matches("(?s).*(?:执照|证书|许可证|合格证).*")) return cn.bidcheck.compat.Java8Api.listOf();
        String tail = quote.substring(marker + 2).replaceFirst("^的", "");
        int end = tail.length();
        for (String stop : cn.bidcheck.compat.Java8Api.listOf("，", ",", "。", "；", ";")) {
            int at = tail.indexOf(stop);if (at >= 0) end = Math.min(end, at);
        }
        tail = tail.substring(0, end);
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (String part : tail.split("或|或者|及|和|、")) {
            String category = normalizeCategory(cn.bidcheck.compat.Java8Api.strip(part.replaceFirst("^(?:具有|具备|取得|持有|提供|提交|的)+", "")));
            if (category.matches("(?s).*(?:营业执照|登记证书|法人证书|资质证书|许可证|合格证(?:书)?|注册证书|认证证书)$"))
                categories.add(category);
        }
        if (categories.isEmpty()) return cn.bidcheck.compat.Java8Api.listOf();
        String subject = cn.bidcheck.compat.Java8Api.isBlank(inheritedSubject) ? explicitSubject(quote) : inheritedSubject;
        List<Rule> leaves = new ArrayList<>();int at = 0;
        for (String category : categories) {
            Map<String,Object> p = nativeParams("CERTIFICATE_VALIDITY");p.put("category", category);
            leaves.add(rule(base + "-VALID-" + (++at), "证照有效期：" + category, "VALIDITY", subject,
                    "有效期", "", "", "", p, quote));
        }
        return leaves.size() == 1 ? leaves : cn.bidcheck.compat.Java8Api.listOf(group(base + "-VALID-CHOICE", quote, "ANY", leaves));
    }

    private List<Rule> qualificationRules(String base, String quote, String inheritedSubject) {
        if (!quote.matches("(?s).*(?:具有|具备|取得|持有|认证).*(?:资质|证书|许可证|合格证|执照).*")) return cn.bidcheck.compat.Java8Api.listOf();
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        Matcher matcher = QUALIFICATION.matcher(quote);
        while (matcher.find()) {
            // Names of laws/policies containing “资质” are references, never certificate categories.
            int inside=quote.lastIndexOf('《',matcher.start(1)),closed=quote.lastIndexOf('》',matcher.start(1));
            if(inside>closed||matcher.group(1).matches("(?s).*(?:《|通知|改革方案|根据|规定已换发).*") )continue;
            String value = normalizeCategory(cn.bidcheck.compat.Java8Api.strip(matcher.group(1).replaceFirst("^(?:具有|具备|取得|持有|认证|及|且|或)+", "")));
            if (value.length() > 80) value = value.substring(Math.max(0, value.length() - 80));
            if (!cn.bidcheck.compat.Java8Api.isBlank(value)) categories.add(value);
        }
        if (categories.isEmpty()) return cn.bidcheck.compat.Java8Api.listOf();
        String subject = cn.bidcheck.compat.Java8Api.isBlank(inheritedSubject) ? explicitSubject(quote) : inheritedSubject;
        List<Rule> leaves = new ArrayList<>();int at = 0;
        for (String category : categories) {
            Map<String, Object> p = nativeParams("QUALIFICATION_CERTIFICATE");p.put("category", category);
            String grade = grade(category);if (!cn.bidcheck.compat.Java8Api.isBlank(grade)) p.put("grade", grade);
            String profession = profession(category);if (!cn.bidcheck.compat.Java8Api.isBlank(profession)) p.put("profession", profession);
            if (quote.contains("本单位完成注册")) p.put("employer", "本单位");
            // A grade in a sibling certificate must not become this certificate's
            // constraint. The three ungraded certification branches keep their exact
            // numbered actor sentence; other categories retain their bounded span.
            String categoryQuote=cn.bidcheck.compat.Java8Api.setOf("质量管理体系认证", "职业健康安全管理体系认证", "环境管理体系认证").contains(category)
                    &&!quote.matches("(?s).*(?:一级|二级|三级|甲级|乙级|丙级|壹级|贰级|叁级).*")?quote:category;
            leaves.add(rule(base + "-CERT-" + (++at), "证照资格：" + category, "CERTIFICATE", subject,
                    "certificate", "", "", "", p, categoryQuote));
        }
        // OCR may omit the closing book-title mark; an unfinished policy reference still
        // cannot erase the alternative qualification route.
        Matcher replacement=Pattern.compile("或根据《[^。；;]*?(?:换发|换证)[^。；;]*").matcher(quote);
        if(replacement.find())leaves.add(rule(base+"-REPLACEMENT","换发新资质对应关系尚未确定","UNSUPPORTED",subject,
                "certificate","","","",nativeParams("QUALIFICATION_REPLACEMENT_MAPPING_REQUIRED"),replacement.group()));
        if (leaves.size() == 1) return leaves;
        return cn.bidcheck.compat.Java8Api.listOf(group(base + "-CERT-CHOICE", quote, quote.contains("或") ? "ANY" : "ALL", leaves));
    }

    /** Closed personnel clauses use one source-bound executor instead of overlapping broad templates. */
    private Rule personnelQualificationTree(String source){
        Matcher header=Pattern.compile("^(施工企业主要负责人|项目负责人|施工部分负责人|施工负责人|专职安全员)(?:[（(]可兼任施工部分负责人[）)])?(?:资格要求)?[:：]具有").matcher(source);
        if(!header.find())return null;String subject=header.group(1);int proofAt=source.indexOf("须提交有效证明文件",header.end());
        String credential=cn.bidcheck.compat.Java8Api.strip(source.substring(header.end(),proofAt<0?source.length():proofAt)).replaceFirst("[。；;]$","");
        List<Rule> rules=new ArrayList<>();String base="PERSONNEL-"+identity(source).substring(0,16);int at=0;
        String issuer="(?:住建主管部门颁发的)?";
        Matcher builder=Pattern.compile("^"+issuer+"([壹贰一二])级((?:含以上|及以上|[（(]含[）)]以上)?)(注册建造师执业资格证书)[（(](房屋建筑工程)专业[）)]").matcher(credential);
        if(builder.find()){
            Map<String,Object> p=nativeParams("TYPED_PERSONNEL_CREDENTIAL");p.put("personnelCredentialKind","REGISTERED_BUILDER");p.put("category",builder.group(3));p.put("requiredGrade",builder.group(1)+"级");p.put("gradeInclusive",!cn.bidcheck.compat.Java8Api.isBlank(builder.group(2)));p.put("requiredProfession",builder.group(4));
            String quote=source.substring(0,header.end())+builder.group();rules.add(rule(base+"-BUILDER","注册建造师等级及专业","CERTIFICATE",subject,"certificate","","","",p,quote));
            if(proofAt>=0)rules.add(rule(base+"-BUILDER-VALID","同一注册建造师证书在要求时点有效","VALIDITY",subject,"有效期","","","",p,source));
            at=builder.end();if(at<credential.length()){if(credential.charAt(at)!='及')return null;at++;}
        }
        if(at<credential.length()){
            String remaining=credential.substring(at);Matcher safety=Pattern.compile("^"+issuer+"(?:有效的|有效期内的)?安全生产考核合格证[（(]([AB]|C[123]?)(?:类|证)[）)]或((?:建筑)?施工企业(?:主要负责人|项目负责人|专职安全生产管理人员)安全生产考核合格证书)(?:[（(](C[123]?)(?:类|证)[）)])?$").matcher(remaining);
            if(!safety.matches())return null;
            List<String> classes=new ArrayList<>();classes.add(safety.group(1));if(safety.group(3)!=null)classes.add(safety.group(3));
            if(safety.group(2).contains("主要负责人")&&!classes.contains("A")||safety.group(2).contains("项目负责人")&&!classes.contains("B"))return null;
            Map<String,Object> p=nativeParams("TYPED_PERSONNEL_CREDENTIAL");p.put("personnelCredentialKind","SAFETY");p.put("category","安全生产考核合格证");p.put("allowedSafetyClasses",cn.bidcheck.compat.Java8Api.listCopyOf(classes));
            rules.add(rule(base+"-SAFETY","安全生产考核证类别","CERTIFICATE",subject,"certificate","","","",p,cn.bidcheck.compat.Java8Api.strip(source.substring(0,proofAt<0?source.length():proofAt))));
            if(remaining.contains("有效")||proofAt>=0)rules.add(rule(base+"-SAFETY-VALID","同一安全生产考核证在要求时点有效","VALIDITY",subject,"有效期","","","",p,source));
        }
        if(rules.isEmpty())return null;
        if(proofAt>=0){List<Rule> proof=certificateProofRules(base+"-COMMON",cn.bidcheck.compat.Java8Api.strip(source.substring(proofAt)),subject,source);if(proof.isEmpty())return null;rules.addAll(proof);}
        return new Rule(base,"人员证照、有效期及共同证明","GROUP",subject,"","","","",nativeParams("TYPED_PERSONNEL_REQUIREMENT"),source,"ALL",cn.bidcheck.compat.Java8Api.listCopyOf(rules));
    }

    private static String normalizeCategory(String value){
        String specific=certificateCategory(value);
        if(!cn.bidcheck.compat.Java8Api.isBlank(specific)&&value.matches("(?s).*(?:安全生产许可证|营业执照|登记证书|管理体系认证)(?:证书)?$"))return specific;
        return value;
    }

    private Rule qualificationTree(String source){
        Optional<QualificationRequirementTree.Tree> parsed=QualificationRequirementTree.parse(source);
        if((!parsed.isPresent()))return null;
        cn.bidcheck.verification.engine.QualificationRequirementTree.Tree tree=parsed.get();String base="QUALIFICATION-TREE-"+identity(source).substring(0,16);
        List<Rule> alternatives=new ArrayList<>();int index=0;
        for(cn.bidcheck.verification.engine.QualificationRequirementTree.Branch branch:tree.branches()){
            String id=base+"-B"+(++index);
            if(!cn.bidcheck.compat.Java8Api.isBlank(branch.unresolved())){
                alternatives.add(rule(id,"资质替代分支待解析","UNSUPPORTED",tree.subject(),"certificate","","","",
                        nativeParams(branch.unresolved()),branch.quote()));continue;
            }
            Map<String,Object> parameters=nativeParams("QUALIFICATION_CERTIFICATE");
            parameters.put("category",branch.category());parameters.put("grade",branch.grade());
            parameters.put("gradeOperator",branch.gradeOperator());parameters.put("inheritedLogic","ANY");
            alternatives.add(rule(id,"资质要求："+branch.category(),"CERTIFICATE",tree.subject(),"certificate","","","",parameters,branch.quote()));
        }
        Rule choice=new Rule(base+"-CHOICE","满足任一资质路线","GROUP",tree.subject(),"","","","",
                nativeParams("SOURCE_ALTERNATIVE_TREE"),tree.choiceQuote(),"ANY",cn.bidcheck.compat.Java8Api.listCopyOf(alternatives));
        List<Rule> mandatory=new ArrayList<>();int mandatoryIndex=0;
        for(String quote:tree.mandatoryQuotes()){
            String id=base+"-MANDATORY-"+(++mandatoryIndex);
            if(quote.contains("安全生产许可证")){
                Map<String,Object> p=nativeParams("QUALIFICATION_CERTIFICATE");p.put("category","安全生产许可证");
                mandatory.add(rule(id+"-CERT","施工单位安全生产许可","CERTIFICATE",tree.subject(),"certificate","","","",p,"安全生产许可证"));
                if(quote.contains("有效"))mandatory.add(rule(id+"-VALID","安全生产许可有效性","VALIDITY",tree.subject(),"有效期","","","",p,quote));
                if(!cn.bidcheck.compat.Java8Api.isBlank(tree.proofQuote())){
                    Map<String,Object> proof=nativeParams("CERTIFICATE_PROOF_DOCUMENTS");proof.put("certificateProofCategory","安全生产许可证");proof.put("certificateRequirementQuote",quote);proof.put("certificateProofRequireValidity",true);
                    mandatory.add(rule(id+"-QUERY","安全生产许可证的查询及有效期对应","MATERIAL",tree.subject(),"material","","同证书查询及有效期","",proof,tree.proofQuote()));
                }
            }else mandatory.add(rule(id,"独立共同义务待解析","UNSUPPORTED",tree.subject(),"","","","",nativeParams("QUALIFICATION_MANDATORY_NOT_COMPILED"),quote));
        }
        if(cn.bidcheck.compat.Java8Api.isBlank(tree.proofQuote())&&mandatory.isEmpty())return choice;
        List<Rule> proofRules=certificateProofRules(base+"-PROOF",tree.proofQuote(),tree.subject(),source);
        proofRules=cn.bidcheck.compat.Java8Api.toList(proofRules.stream().map(proof->{
            if(!cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "SAME_FIELD").contains(proof.predicate()))return proof;
            Map<String,Object> parameters=new LinkedHashMap<>(proof.parameters());
            parameters.put("qualificationChoiceFingerprint",QualificationProofAssociation.choiceFingerprint(choice));
            return new Rule(proof.id(),proof.description(),proof.predicate(),proof.subject(),proof.field(),proof.operator(),proof.expected(),proof.unit(),parameters,proof.sourceQuote(),proof.logic(),proof.children());
        }));
        if(proofRules.isEmpty()&&!cn.bidcheck.compat.Java8Api.isBlank(tree.proofQuote()))proofRules=cn.bidcheck.compat.Java8Api.listOf(rule(base+"-PROOF-GAP","共同证明义务待解析","UNSUPPORTED",tree.subject(),"certificate","","","",
                nativeParams("QUALIFICATION_COMMON_PROOF_NOT_COMPILED"),tree.proofQuote()));
        List<Rule> all=new ArrayList<>();all.add(choice);all.addAll(mandatory);all.addAll(proofRules);
        return new Rule(base,"资质可选条件与共同证明要求","GROUP",tree.subject(),"","","","",
                nativeParams("SOURCE_REQUIREMENT_TREE"),source,"ALL",cn.bidcheck.compat.Java8Api.listCopyOf(all));
    }

    private List<Rule> signatureRules(String base, String quote) {
        List<Rule> rules = new ArrayList<>();
        if (quote.matches("(?s).*(?:盖章|单位公章).*")) {
            Map<String, Object> p = nativeParams("SEAL_VISIBILITY");p.put("documentType", documentType(quote));p.put("visualType", "SEAL");
            rules.add(rule(base + "-SEAL", "要求位置盖章", "VISUAL", sealSubject(quote), "seal", "", "", "", p, quote));
        }
        if (quote.matches("(?s).*(?:签字|签名章|签署).*")) {
            Map<String, Object> p = nativeParams("SIGNATURE_VISIBILITY");p.put("documentType", documentType(quote));p.put("visualType", "SIGNATURE");
            rules.add(rule(base + "-SIGN", "要求位置签字", "VISUAL", signerSubject(quote), "signature", "", "", "", p, quote));
        }
        return rules;
    }

    private List<Rule> signingAuthority(String base, String quote) {
        List<Rule> rules = new ArrayList<>();
        if (quote.contains("法定代表人身份证明")) {
            rules.add(rule(base + "-LEGAL-ID", "法定代表人身份证明", "MATERIAL", "法定代表人",
                    "material", "", "法定代表人身份证明", "", nativeParams("SIGNING_AUTHORITY"), quote));
        }
        if (quote.contains("授权委托书")) {
            Map<String, Object> p = nativeParams("SIGNING_AUTHORITY");
            if (quote.matches("(?s).*(?:直接签署|本人签署).*(?:无需|不需)提交.*")) {
                // Applicability is a required external input; this prevents treating an absent
                // authorization as failure when the legal representative signed directly.
                rules.add(rule(base + "-SIGN-MODE", "签署方式", "INPUT", "投标人", "signingMode", "",
                        "确认由法定代表人直接签署或由委托代理人签署", "", p, quote));
            }
            rules.add(rule(base + "-AUTH", "法定代表人授权委托书", "MATERIAL", "委托代理人",
                    "material", "", "法定代表人授权委托书", "", p, quote));
        }
        return rules;
    }

    private static Map<String, Object> nativeParams(String family) {
        Map<String, Object> p = new LinkedHashMap<>();p.put("deterministicFamily", family);p.put("templateCompilerVersion", VERSION);return p;
    }

    private static boolean sameObligation(Rule left, Rule right) {
        if (!left.children().isEmpty() || !right.children().isEmpty())
            return SharedFactReuse.signature(left).equals(SharedFactReuse.signature(right));
        return left.predicate().equals(right.predicate()) && left.subject().equals(right.subject())
                && left.field().equals(right.field()) && left.operator().equals(right.operator())
                && left.expected().equals(right.expected()) && left.unit().equals(right.unit())
                && semanticParameter(left, "category").equals(semanticParameter(right, "category"))
                && semanticParameter(left, "grade").equals(semanticParameter(right, "grade"))
                && semanticParameter(left, "profession").equals(semanticParameter(right, "profession"));
    }

    private static String semanticParameter(Rule rule, String key) {
        return string(rule.parameters().get(key));
    }

    private static String reasonTemplate(String reason) {
        String[] parts = Objects.toString(reason, "").split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private static boolean represented(String templateId, List<Rule> rules) {
        if (cn.bidcheck.compat.Java8Api.isBlank(templateId)) return false;
        List<Rule> leaves = new ArrayList<>();rules.forEach(rule -> flatten(rule, leaves));
        Set<String> predicates = leaves.stream().map(Rule::predicate).collect(java.util.stream.Collectors.toSet());
        switch (templateId) {case "MATERIAL_PRESENT":case "MATERIAL_ALTERNATIVE":case "SECTION_CHECKLIST":case "FORM_FIELD_COMPLETENESS":case "OBJECTIVE_SCORING":return predicates.contains("MATERIAL");case "COMMITMENT_WITH_SEAL":return predicates.containsAll(cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "DECLARATION", "VISUAL"));case "CERTIFICATE_VALIDITY":return predicates.contains("VALIDITY");case "PRICE_COMPARE":case "DURATION_COMPARE":return predicates.contains("NUMBER");case "PERSONNEL_ROSTER":return predicates.contains("PERSON_COUNT");case "ENTITY_FIELD_MATCH":case "CROSS_DOCUMENT_CONSISTENCY":return predicates.contains("SAME_FIELD");case "PERSONNEL_CREDENTIAL":return predicates.contains("CERTIFICATE");case "CONSORTIUM_RESPONSIBILITY":return predicates.contains("RELATION") || predicates.contains("ENTITY_COUNT");case "PRICE_RECONCILE":return predicates.contains("PRICE_RECONCILE");case "DATE_RELATION":return predicates.contains("DATE_RELATION");case "TEXT_ANOMALY":return predicates.contains("TEXT_ANOMALY");case "QUALITY_ADVISORY":return predicates.contains("QUALITY_ADVISORY");case "EXTERNAL_CHECK":return predicates.contains("EXTERNAL");default:return false;}
    }

    private static void flatten(Rule rule, List<Rule> out) {
        if (rule.children().isEmpty()) out.add(rule);else rule.children().forEach(child -> flatten(child, out));
    }

    /** Repaired requirements may select disjoint source spans. Do not assign their joined
     * text an invented offset on the first page; the mapper sees each exact original span. */
    private List<ClauseNode> mappingRoots(ClauseNode fallback,RequirementSourceContext.Context context){
        if(context==null)return cn.bidcheck.compat.Java8Api.listOf(fallback);
        // Reference expansion and legacy repair both join disjoint original spans. Keep each
        // original location rather than pretending the joined requirement is one page range.
        List<Map<String,Object>> selected=cn.bidcheck.compat.Java8Api.toList(context.sources().stream().filter(s->cn.bidcheck.compat.Java8Api.setOf("OBLIGATION", "SCOPED_OBLIGATION", "SCOPED_PROOF", "SCOPED_BOOK_CONDITION", "SCOPED_SCORING_CONTINUATION", "REFERENCE_CLAUSE").contains(s.get("kind"))));
        String joined=selected.stream().map(s->str(s,"sourceQuote")).distinct().collect(java.util.stream.Collectors.joining("\n"));
        if(selected.isEmpty()||!joined.equals(fallback.text()))return cn.bidcheck.compat.Java8Api.listOf(fallback);
        List<ClauseNode> roots=new ArrayList<>();int index=0;
        for(Map<String,Object> source:selected){String quote=str(source,"sourceQuote");
            SourceRef ref=new SourceRef(str(source,"documentId"),number(source.get("documentVersion"),0),str(source,"documentHash"),VERSION,identity(quote),str(source,"locationType"),number(source.get("location"),0),quote,integer(source.get("start")),integer(source.get("end")));
            roots.add(new ClauseNode(fallback.id()+":s"+(index++),ClauseNode.Kind.PARAGRAPH,quote,ref,fallback.localContext(),cn.bidcheck.compat.Java8Api.listOf()));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(roots);
    }

    private SourceRef sourceRef(Checkpoint checkpoint, String requirement, RequirementSourceContext.Context sourceContext) {
        Map<String, Object> identity = sourceContext == null ? cn.bidcheck.compat.Java8Api.mapOf() : sourceContext.identity();
        String documentId = string(identity.get("documentId"));
        int documentVersion = number(identity.get("documentVersion"), 0);
        String documentHash = string(identity.get("documentHash"));
        String locationType = string(identity.get("locationType"));
        int location = number(identity.get("location"), checkpoint.source() == null ? 0 : checkpoint.source().page());
        Integer start = integer(identity.get("start"));Integer end = integer(identity.get("end"));
        if (cn.bidcheck.compat.Java8Api.isBlank(documentId) && checkpoint.source() != null) documentId = checkpoint.source().documentId();
        if (cn.bidcheck.compat.Java8Api.isBlank(documentId)) documentId = "requirement:" + identity(requirement).substring(0, 24);
        if (cn.bidcheck.compat.Java8Api.isBlank(documentHash)) documentHash = identity(requirement);
        if (cn.bidcheck.compat.Java8Api.isBlank(locationType)) locationType = checkpoint.source() == null ? "REQUIREMENT" : checkpoint.source().locationType();
        if (start == null) start = 0;if (end == null) end = start + requirement.length();
        return new SourceRef(documentId, documentVersion, documentHash, VERSION, identity(requirement),
                locationType, Math.max(0, location), requirement, start, end);
    }

    private static Map<String, String> externalAdapters(Map<String, Object> context) {
        Object raw = context == null ? null : context.get("externalAdapterBySource");
        if (!(raw instanceof Map<?, ?>)) return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<?, ?>) (raw)).forEach((key, value) -> { if (key != null && value != null) result.put(key.toString(), value.toString()); });
        return result;
    }

    private static String inheritedSubject(RequirementSourceContext.Context context) {
        if (context == null) return "";
        Set<String> subjects = new LinkedHashSet<>();
        for (Map<String, Object> source : context.sources()) {
            String kind = string(source.get("kind"));String subject = string(source.get("subject"));
            if (cn.bidcheck.compat.Java8Api.setOf("SECTION_SUBJECT", "TABLE_ROW_SUBJECT").contains(kind) && !cn.bidcheck.compat.Java8Api.isBlank(subject)) subjects.add(subject);
        }
        return subjects.size() == 1 ? subjects.iterator().next() : "";
    }

    private static String subject(TemplateCandidate candidate, RequirementDraft draft, String quote) {
        for (String key : cn.bidcheck.compat.Java8Api.listOf("role", "issuerRole", "queryObject")) {
            String value = text(candidate.parameters(), key);if (!cn.bidcheck.compat.Java8Api.isBlank(value)) return value;
        }
        if (!cn.bidcheck.compat.Java8Api.isBlank(draft.sourceContext().subjectRole())) return draft.sourceContext().subjectRole();
        return explicitSubject(quote);
    }

    private static String explicitSubject(String quote) {
        Matcher role = EXPLICIT_ROLE.matcher(quote);if (role.find()) return role.group(1);
        for (String value : cn.bidcheck.compat.Java8Api.listOf("联合体成员", "投标人", "施工单位", "设计单位", "供应商", "响应人"))
            if (quote.contains(value)) return value;
        Matcher company = Pattern.compile("[\\p{IsHan}A-Za-z0-9（）()·]{1,100}?(?:有限责任公司|股份有限公司|有限公司|公司)")
                .matcher(quote);
        if (company.find()) return company.group();
        return "";
    }

    /** Restores terminal punctuation removed by the clause splitter while keeping an exact,
     * contiguous quote from the reviewed requirement. */
    private static String exactQuote(String source, String fragment) {
        if (source == null || fragment == null || cn.bidcheck.compat.Java8Api.isBlank(fragment)) return Objects.toString(fragment, "");
        int start = source.indexOf(fragment);
        if (start < 0) return fragment;
        int end = start + fragment.length();
        while (end < source.length() && "。；;！？!?".indexOf(source.charAt(end)) >= 0) end++;
        return source.substring(start, end);
    }

    private static String lot(String source) {
        Matcher matcher = Pattern.compile("(?:第?[一二三四五六七八九十0-9A-Za-z]+标段|不划分标段)").matcher(source);
        Set<String> values = new LinkedHashSet<>();while (matcher.find()) values.add(matcher.group());
        return values.size() == 1 ? values.iterator().next() : "";
    }

    private static String referenceEvent(String quote) {
        if (quote.contains("开标")) return "OPENING_DATE";
        if (quote.matches("(?s).*(?:投标|文件递交|提交).{0,12}截止.*")) return "BID_DEADLINE";
        return "";
    }

    private static String explicitValidityField(String quote) {
        for (String field : cn.bidcheck.compat.Java8Api.listOf("注册有效期", "证书有效期", "有效期限", "有效期")) if (quote.contains(field)) return field;
        return "";
    }

    private static String amountBasis(String quote, String mappedField) {
        for (String value : cn.bidcheck.compat.Java8Api.listOf("投标总报价", "投标总价", "投标报价合计", "投标报价", "单价", "分项报价", "投标保证金"))
            if (quote.contains(value)) return value;
        return mappedField;
    }

    private static String taxBasis(String value) {
        switch (value) {case "TAX_INCLUDED":return "含税";case "TAX_EXCLUDED":return "不含税";default:return value;}
    }

    private static String currency(String value) { return "CNY".equals(value) ? "人民币" : value; }

    private static String explicitCurrency(String quote, String mapped) {
        for (String token : cn.bidcheck.compat.Java8Api.listOf("人民币", "CNY", "RMB", "￥", "¥", "美元", "USD", "欧元", "EUR", "港元", "港币", "HKD"))
            if (quote.toUpperCase(Locale.ROOT).contains(token.toUpperCase(Locale.ROOT))) return token;
        // RequirementMappingService uses CNY as a safe template default.  A default is not source
        // evidence, so do not turn it into a required currency when the clause never names one.
        return "CNY".equalsIgnoreCase(mapped) ? "" : mapped;
    }

    private static String explicitTaxBasis(String quote, String mapped) {
        if (quote.contains("不含税") || quote.contains("未税") || quote.contains("税前")) return "不含税";
        if (quote.contains("含税") || quote.contains("税后")) return "含税";
        return cn.bidcheck.compat.Java8Api.isBlank(mapped) ? "" : taxBasis(mapped);
    }

    private static String explicitMoneyUnit(String quote, String mapped) {
        Matcher matcher = Pattern.compile("(亿元|万元|美元|欧元|港元|CNY|RMB|USD|EUR|HKD|元)").matcher(quote);
        return matcher.find() ? matcher.group(1) : mapped;
    }

    private static String dateDocumentRole(String role) {
        if (role.contains("开标") || role.contains("投标截止")) return "TENDER";
        return "BID";
    }

    private static String displayField(String quote, String field) {
        List<String> labels;
switch (field) {case "organization.name":case "companyName":labels = cn.bidcheck.compat.Java8Api.listOf("投标人名称", "企业名称", "单位名称"); break;case "project.name":case "projectName":labels = cn.bidcheck.compat.Java8Api.listOf("项目名称", "工程名称"); break;case "project.code":case "projectNumber":labels = cn.bidcheck.compat.Java8Api.listOf("项目编号", "招标编号", "采购编号"); break;case "certificate.number":labels = cn.bidcheck.compat.Java8Api.listOf("证书编号", "注册编号", "证号"); break;case "certificate.registeredOrganization":labels = cn.bidcheck.compat.Java8Api.listOf("注册单位", "聘用单位"); break;case "person.name":labels = cn.bidcheck.compat.Java8Api.listOf("姓名"); break;case "totalPrice":case "price.amount":labels = cn.bidcheck.compat.Java8Api.listOf("投标总报价", "总报价", "投标报价"); break;case "documentDate":labels = cn.bidcheck.compat.Java8Api.listOf("投标日期", "签署日期", "落款日期"); break;default:labels = cn.bidcheck.compat.Java8Api.listOf(); break;}
        for (String label : labels) if (quote.contains(label)) return label;
        return field;
    }

    private static String externalExpected(String field, String quote) {
        switch (field) {case "dishonestJudgmentDebtor":case "majorTaxViolation":case "administrativePenalty":case "abnormalOperation":return "false";default:return quote.matches("(?s).*(?:未处于|不得|无).*") ? "COMPLIANT" : "VERIFIED";}
    }

    private static String comparatorInQuote(String quote, String role, int count, String fallback) {
        String countText = Integer.toString(count);
        Matcher matcher = Pattern.compile(Pattern.quote(role) + "[^。；;]{0,20}?(不少于|不低于|至少|不超过|至多|最多|为|等于)?\\s*(?:" + countText + "|[一二两三四五六七八九十]+)\\s*(?:人|名)").matcher(quote);
        if (!matcher.find() || matcher.group(1) == null) return "";
        return RequirementGrounding.operator(matcher.group(1));
    }

    private static String grade(String quote) {
        Matcher m = Pattern.compile("(甲级|乙级|丙级|特级|[一二壹贰三叁]级|高级|中级|初级)").matcher(quote);
        return m.find() ? m.group(1) : "";
    }

    private static String profession(String quote) {
        Matcher m = Pattern.compile("([^，,。；;（）()]{2,30}(?:工程|建筑|装饰装修)专业)").matcher(quote);
        return m.find() ? cn.bidcheck.compat.Java8Api.strip(m.group(1)) : "";
    }

    private static String documentType(String quote) {
        for (String value : cn.bidcheck.compat.Java8Api.listOf("联合体协议书", "法定代表人身份证明", "法定代表人授权委托书", "投标函", "承诺书", "投标文件"))
            if (quote.contains(value)) return value;
        return "投标文件";
    }

    private static String sealSubject(String quote) {
        if (quote.contains("联合体各方")) return "联合体成员";
        if (quote.contains("牵头单位")) return "联合体牵头人";
        if (quote.contains("投标人")) return "投标人";
        // A document-level instruction identifies the required location, not a legal entity.
        // Keeping the subject empty prevents the compiler from inventing a signer/owner while
        // documentType still routes the exact image check.
        return "";
    }

    private static String signerSubject(String quote) {
        if (quote.contains("委托代理人")) return "委托代理人";
        if (quote.contains("法定代表人")) return "法定代表人";
        if (quote.contains("投标人")) return "投标人";
        return "";
    }

    private static String topLogic(String source, List<Rule> rules) {
        if (rules.size() <= 1) return "ALL";
        // Alternatives handled inside their own candidate group.  Separate sentence fragments are
        // conjunctive unless the frozen source explicitly makes the whole row an either/or choice.
        return source.matches("(?s)^\\s*(?:任选|满足下列条件之一).*" ) && rules.stream().allMatch(r -> "ANY".equals(r.logic())) ? "ANY" : "ALL";
    }

    private Rule number(String id, String description, String subject, String field, String operator,
                        String expected, String unit, Map<String, Object> parameters) {
        return rule(id, description, "NUMBER", subject, field, operator, expected, unit, parameters, description);
    }

    private Rule rule(String id, String description, String predicate, String subject, String field,
                      String operator, String expected, String unit, Map<String, Object> parameters,
                      String sourceQuote) {
        return new Rule(id, description, predicate, subject, field, operator, expected, unit,
                cn.bidcheck.compat.Java8Api.mapCopyOf(parameters), sourceQuote, "", cn.bidcheck.compat.Java8Api.listOf());
    }

    private Rule group(String id, String quote, String logic, List<Rule> children) {
        return new Rule(id, quote, "GROUP", "", "", "", "", "", nativeParams("GROUP"), quote, logic, children);
    }

    private ObjectNode ruleNode(Rule rule) {
        ObjectNode node = json.createObjectNode();
        node.put("id", rule.id());node.put("description", rule.description());node.put("predicate", rule.predicate());
        node.put("subject", rule.subject());node.put("field", rule.field());node.put("operator", rule.operator());
        node.put("expected", rule.expected());node.put("unit", rule.unit());node.set("parameters", json.valueToTree(rule.parameters()));
        node.put("sourceQuote", rule.sourceQuote());node.put("logic", rule.logic());
        ArrayNode children = node.putArray("children");rule.children().forEach(child -> children.add(ruleNode(child)));
        return node;
    }

    private static Map<String, Object> parameterSourceAudit(TemplateCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        candidate.parameterSources().forEach((key, values) -> result.put(key, cn.bidcheck.compat.Java8Api.toList(values.stream().map(source -> cn.bidcheck.compat.Java8Api.mapOf("documentId", source.documentId(), "documentVersion", source.documentVersion(), "documentHash", source.documentHash(), "locationType", source.locationType(), "location", source.location(), "sourceHash", source.sourceHash(), "sourceQuote", source.quote(), "start", source.startOffset() == null ? -1 : source.startOffset(), "end", source.endOffset() == null ? -1 : source.endOffset())))));
        return result;
    }

    private static boolean containsUnsupported(Rule rule) {
        return "UNSUPPORTED".equals(rule.predicate()) || rule.children().stream().anyMatch(DeterministicRequirementCompiler::containsUnsupported);
    }

    private static List<Rule> deduplicate(Collection<Rule> values) {
        Map<String, Rule> unique = new LinkedHashMap<>();
        for (Rule rule : values) unique.putIfAbsent(SharedFactReuse.signature(rule), rule);
        return cn.bidcheck.compat.Java8Api.listCopyOf(unique.values());
    }

    private static String stableId(String template, String fragmentId, String quote, String suffix) {
        return "D" + identity(template + "|" + fragmentId + "|" + quote + "|" + suffix).substring(0, 20);
    }

    private static String text(Map<String, ?> map, String key) { return string(map.get(key)); }
    private static String string(Object value) { return value == null ? "" : cn.bidcheck.compat.Java8Api.strip(value.toString()); }
    private static Integer integer(Object value) {
        if (value instanceof Number) return ((Number) (value)).intValue();
        try { return value == null || cn.bidcheck.compat.Java8Api.isBlank(value.toString()) ? null : Integer.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static int number(Object value, int fallback) { Integer parsed = integer(value);return parsed == null ? fallback : parsed; }
    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?>)) return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(DeterministicRequirementCompiler::string).filter(v -> !cn.bidcheck.compat.Java8Api.isBlank(v)));
    }
    private static int chineseNumber(String value) {
        BigDecimal result = RequirementGrounding.number(value);return result == null ? 0 : result.intValueExact();
    }
    private static String fourDigitYear(String value) { int year = Integer.parseInt(value);return Integer.toString(year < 100 ? year + 2000 : year); }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class FragmentCoverage {
        private final String fragmentId;
        private final String text;
        private final SourceRef source;
        private final String disposition;
        private final List<String> ruleIds;
        private final List<String> templateIds;
        private final String reasonCode;
        private final boolean covered;
        @com.fasterxml.jackson.annotation.JsonCreator
        FragmentCoverage(@com.fasterxml.jackson.annotation.JsonProperty("fragmentId") String fragmentId, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("source") SourceRef source, @com.fasterxml.jackson.annotation.JsonProperty("disposition") String disposition, @com.fasterxml.jackson.annotation.JsonProperty("ruleIds") List<String> ruleIds, @com.fasterxml.jackson.annotation.JsonProperty("templateIds") List<String> templateIds, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode, @com.fasterxml.jackson.annotation.JsonProperty("covered") boolean covered) { this.fragmentId = fragmentId; this.text = text; this.source = source; this.disposition = disposition; this.ruleIds = ruleIds; this.templateIds = templateIds; this.reasonCode = reasonCode; this.covered = covered; }
        public String fragmentId() { return fragmentId; }
        public String text() { return text; }
        public SourceRef source() { return source; }
        public String disposition() { return disposition; }
        public List<String> ruleIds() { return ruleIds; }
        public List<String> templateIds() { return templateIds; }
        public String reasonCode() { return reasonCode; }
        public boolean covered() { return covered; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; FragmentCoverage that = (FragmentCoverage) other; return java.util.Objects.equals(this.fragmentId, that.fragmentId) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.disposition, that.disposition) && java.util.Objects.equals(this.ruleIds, that.ruleIds) && java.util.Objects.equals(this.templateIds, that.templateIds) && java.util.Objects.equals(this.reasonCode, that.reasonCode) && java.util.Objects.equals(this.covered, that.covered); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.fragmentId); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.disposition); hash = 31 * hash + java.util.Objects.hashCode(this.ruleIds); hash = 31 * hash + java.util.Objects.hashCode(this.templateIds); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); hash = 31 * hash + java.util.Objects.hashCode(this.covered); return hash; }
        @Override public String toString() { return "FragmentCoverage[" + "fragmentId=" + fragmentId + ", text=" + text + ", source=" + source + ", disposition=" + disposition + ", ruleIds=" + ruleIds + ", templateIds=" + templateIds + ", reasonCode=" + reasonCode + ", covered=" + covered + "]"; }

        Map<String, Object> map() { return linked("fragmentId", fragmentId, "sourceQuote", text,
                "sourceHash", source.sourceHash(), "documentId", source.documentId(),
                "documentVersion", source.documentVersion(), "locationType", source.locationType(),
                "location", source.location(), "start", source.startOffset(), "end", source.endOffset(),
                "disposition", disposition, "ruleIds", ruleIds, "templateIds", templateIds,
                "reasonCode", reasonCode, "covered", covered); }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Gap {
        private final String fragmentId;
        private final String text;
        private final SourceRef source;
        private final String reasonCode;
        @com.fasterxml.jackson.annotation.JsonCreator
        Gap(@com.fasterxml.jackson.annotation.JsonProperty("fragmentId") String fragmentId, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("source") SourceRef source, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode) { this.fragmentId = fragmentId; this.text = text; this.source = source; this.reasonCode = reasonCode; }
        public String fragmentId() { return fragmentId; }
        public String text() { return text; }
        public SourceRef source() { return source; }
        public String reasonCode() { return reasonCode; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Gap that = (Gap) other; return java.util.Objects.equals(this.fragmentId, that.fragmentId) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.reasonCode, that.reasonCode); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.fragmentId); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); return hash; }
        @Override public String toString() { return "Gap[" + "fragmentId=" + fragmentId + ", text=" + text + ", source=" + source + ", reasonCode=" + reasonCode + "]"; }

        String identity() { return fragmentId + "|" + source.sourceHash() + "|" + reasonCode; }
        Map<String, Object> map() { return linked("fragmentId", fragmentId, "sourceQuote", text,
                "sourceHash", source.sourceHash(), "start", source.startOffset(), "end", source.endOffset(),
                "reasonCode", reasonCode); }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Result {
        private final ObjectNode root;
        private final List<Rule> rules;
        private final List<FragmentCoverage> ledger;
        private final List<Gap> gaps;
        private final Set<String> templateIds;
        private final String digest;
        private final String requirement;
        private final ObjectMapper json;
        @com.fasterxml.jackson.annotation.JsonCreator
        Result(@com.fasterxml.jackson.annotation.JsonProperty("root") ObjectNode root, @com.fasterxml.jackson.annotation.JsonProperty("rules") List<Rule> rules, @com.fasterxml.jackson.annotation.JsonProperty("ledger") List<FragmentCoverage> ledger, @com.fasterxml.jackson.annotation.JsonProperty("gaps") List<Gap> gaps, @com.fasterxml.jackson.annotation.JsonProperty("templateIds") Set<String> templateIds, @com.fasterxml.jackson.annotation.JsonProperty("digest") String digest, @com.fasterxml.jackson.annotation.JsonProperty("requirement") String requirement, @com.fasterxml.jackson.annotation.JsonProperty("json") ObjectMapper json) { this.root = root; this.rules = rules; this.ledger = ledger; this.gaps = gaps; this.templateIds = templateIds; this.digest = digest; this.requirement = requirement; this.json = json; }
        public ObjectNode root() { return root; }
        public List<Rule> rules() { return rules; }
        public List<FragmentCoverage> ledger() { return ledger; }
        public List<Gap> gaps() { return gaps; }
        public Set<String> templateIds() { return templateIds; }
        public String digest() { return digest; }
        public String requirement() { return requirement; }
        public ObjectMapper json() { return json; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.root, that.root) && java.util.Objects.equals(this.rules, that.rules) && java.util.Objects.equals(this.ledger, that.ledger) && java.util.Objects.equals(this.gaps, that.gaps) && java.util.Objects.equals(this.templateIds, that.templateIds) && java.util.Objects.equals(this.digest, that.digest) && java.util.Objects.equals(this.requirement, that.requirement) && java.util.Objects.equals(this.json, that.json); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.root); hash = 31 * hash + java.util.Objects.hashCode(this.rules); hash = 31 * hash + java.util.Objects.hashCode(this.ledger); hash = 31 * hash + java.util.Objects.hashCode(this.gaps); hash = 31 * hash + java.util.Objects.hashCode(this.templateIds); hash = 31 * hash + java.util.Objects.hashCode(this.digest); hash = 31 * hash + java.util.Objects.hashCode(this.requirement); hash = 31 * hash + java.util.Objects.hashCode(this.json); return hash; }
        @Override public String toString() { return "Result[" + "root=" + root + ", rules=" + rules + ", ledger=" + ledger + ", gaps=" + gaps + ", templateIds=" + templateIds + ", digest=" + digest + ", requirement=" + requirement + ", json=" + json + "]"; }

        static Result empty(ObjectMapper json, String requirement) {
            ObjectNode root = json.createObjectNode();root.put("logic", "ALL");root.putArray("rules");root.putArray("policyNotes");
            return new Result(root, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.setOf(), identity(VERSION + requirement), requirement, json);
        }
        boolean complete() { return !rules.isEmpty() && gaps.isEmpty() && ledger.stream().allMatch(FragmentCoverage::covered) && rules.stream().noneMatch(DeterministicRequirementCompiler::containsUnsupported); }
        Map<String, Object> audit() { return linked("version", VERSION, "digest", digest,
                "complete", complete(), "fragmentCount", ledger.size(),
                "coveredFragments", ledger.stream().filter(FragmentCoverage::covered).count(),
                "gapCount", gaps.size(), "templates", cn.bidcheck.compat.Java8Api.toList(templateIds.stream().sorted()),
                "supportedTemplateCount", SUPPORTED_TEMPLATE_IDS.size(),
                "coverage", cn.bidcheck.compat.Java8Api.toList(ledger.stream().map(FragmentCoverage::map)),
                "gaps", cn.bidcheck.compat.Java8Api.toList(gaps.stream().map(Gap::map))); }
        List<Map<String, Object>> lockedRules() { return cn.bidcheck.compat.Java8Api.toList(rules.stream().map(Result::ruleMap)); }

        /**
         * Keeps every locally compiled rule and materializes every uncovered source fragment as a
         * source-bound UNSUPPORTED node.  Production uses this closed tree instead of asking a model
         * to invent rule structure.  The ordinary evaluator will therefore preserve the gap as an
         * explicit pending condition while it can still extract facts for the supported rules.
         */
        JsonNode rootWithUnsupportedGaps() {
            ObjectNode safe = root.deepCopy();
            ArrayNode out = safe.withArray("rules");
            for (Gap gap : gaps) {
                ObjectNode node = out.addObject();
                node.put("id", "GAP-" + identity(VERSION + "|" + gap.identity()).substring(0, 24));
                node.put("description", gap.text());
                node.put("predicate", "UNSUPPORTED");
                node.put("subject", "");node.put("field", "");node.put("operator", "");
                node.put("expected", "");node.put("unit", "");
                ObjectNode parameters = node.putObject("parameters");
                parameters.put("deterministicGapReason", gap.reasonCode());
                parameters.put("fragmentId", gap.fragmentId());
                parameters.put("sourceHash", gap.source().sourceHash());
                parameters.set("sourceRef", json.valueToTree(gap.map()));
                node.put("sourceQuote", gap.text());node.put("logic", "");node.putArray("children");
            }
            return safe;
        }

        /** Re-inserts immutable local nodes and accepts model nodes only from uncovered spans. */
        JsonNode merge(JsonNode modelRoot) {
            JsonNode normalized = CompilationRecovery.normalize(modelRoot);
            ObjectNode merged = json.createObjectNode();merged.put("logic", root.path("logic").asText("ALL"));
            ArrayNode out = merged.putArray("rules");root.path("rules").forEach(out::add);
            Set<String> ids = new LinkedHashSet<>();root.path("rules").forEach(row -> collectIds(row, ids));
            if (normalized.path("rules").isArray()) for (JsonNode row : normalized.path("rules")) {
                if (!modelRuleWithinGap(row) || collides(row, ids)) continue;
                out.add(row);collectIds(row, ids);
            }
            ArrayNode notes = merged.putArray("policyNotes");
            if (normalized.path("policyNotes").isArray()) normalized.path("policyNotes").forEach(notes::add);
            return merged;
        }

        Plan identify(Plan plan, String method) {
            Map<String, Object> scope = new LinkedHashMap<>(plan.requirementScope());scope.put("deterministicCompilation", audit());
            return new Plan(plan.logic(), plan.rules(), method, plan.issues(), plan.policyNotes(), scope);
        }

        private boolean modelRuleWithinGap(JsonNode row) {
            List<String> quotes = new ArrayList<>();collectQuotes(row, quotes);
            return !quotes.isEmpty() && quotes.stream().allMatch(quote -> gaps.stream().anyMatch(gap ->
                    gap.text().contains(quote) || quote.contains(gap.text()) && gap.text().length() >= 6));
        }
        private static boolean collides(JsonNode row, Set<String> ids) {
            List<String> incoming = new ArrayList<>();collectIdValues(row, incoming);
            return incoming.stream().anyMatch(id -> cn.bidcheck.compat.Java8Api.isBlank(id) || ids.contains(id) || id.endsWith("::own"))
                    || incoming.size() != new LinkedHashSet<>(incoming).size();
        }
        private static void collectQuotes(JsonNode row, List<String> quotes) {
            String quote = row.path("sourceQuote").asText();if (!cn.bidcheck.compat.Java8Api.isBlank(quote)) quotes.add(quote);
            if (row.path("children").isArray()) row.path("children").forEach(child -> collectQuotes(child, quotes));
        }
        private static void collectIds(JsonNode row, Set<String> ids) { List<String> values = new ArrayList<>();collectIdValues(row, values);ids.addAll(values); }
        private static void collectIdValues(JsonNode row, List<String> ids) {
            ids.add(row.path("id").asText());if (row.path("children").isArray()) row.path("children").forEach(child -> collectIdValues(child, ids));
        }
        private static Map<String, Object> ruleMap(Rule rule) { return linked("id", rule.id(), "description", rule.description(),
                "predicate", rule.predicate(), "subject", rule.subject(), "field", rule.field(), "operator", rule.operator(),
                "expected", rule.expected(), "unit", rule.unit(), "parameters", rule.parameters(), "sourceQuote", rule.sourceQuote(),
                "logic", rule.logic(), "children", cn.bidcheck.compat.Java8Api.toList(rule.children().stream().map(Result::ruleMap))); }
    }
}
