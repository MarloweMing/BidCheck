package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.engine.ScoringSourceSegments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Source-closed performance awards. Contracts are counted only after all identity, amount,
 * time and common-document conditions have been proven. Unread contracts never mean zero. */
final class SourcePerformanceScore {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourcePerformanceScore.class);
    static final String MODE="PERFORMANCE_CONTRACTS";
    private static final Pattern AWARD=Pattern.compile("^(施工业绩情况|项目负责人业绩|设计负责人业绩)[（(]商务[）)]自(20[0-9]{2})年([0-9]{1,2})月([0-9]{1,2})日至今(?:作为(项目负责人|设计负责人)的)?(建安费)?金额在([0-9.]+)(万元|元)含以上装饰装修工程(施工|设计)项目合同或EPC合同(?:中)?担任(施工|设计)部分[。，,](?:每有|具有)1个得([0-9.]+)分[，,]最高得([0-9.]+)分[。]?$");
    static final String SOCIAL="需提交(20[0-9]{2})年([0-9]{1,2})月[（(]含[）)]至文件递交截止时间任意连续([一二三四五六七八九十0-9]+)个月在本单位缴纳社保证明材料，特殊情况提交说明文件[（(]格式自拟[）)]及有效的证明文件[（(]如单位设立时间不足、人员新入职、离退休、转业等[）)]";
    private static final String FRAMEWORK="如提交框架合同，按框架合同金额。如框架合同无签约金额，需提交汇总金额不低于上述限额的项下订单，并列表汇总（格式自拟）";
    private SourcePerformanceScore(){}
    static Rule compile(String original){
        String body=ScoringSourceSegments.body(original).replaceAll("\\s+","");String[] parts=body.split("证明材料[:：]",-1);if(parts.length!=2)return null;
        Matcher award=AWARD.matcher(parts[0]);if(!award.matches()||!award.group(9).equals(award.group(10)))return null;
        String role=Objects.toString(award.group(5),""),basis=Objects.toString(award.group(6),"");boolean person=!cn.bidcheck.compat.Java8Api.isBlank(role);
        if(person!=!award.group(1).equals("施工业绩情况")||person&&!award.group(1).equals(role+"业绩"))return null;
        String proof=parts[1];Map<String,Object> parameters=params(MODE);
        if(person){
            Matcher social=Pattern.compile("^"+SOCIAL+"，并").matcher(proof);if(!social.find())return null;putSocial(parameters,social);
            proof=proof.substring(social.end());
            String tail="需提交合同关键页扫描件，关键页包括合同名称、合同金额、签字盖章页和项目负责人证明材料（如合同项目负责人姓名页、中标通知书、具有"+role+"签字及甲方盖章的竣工验收单等）；"+FRAMEWORK+"。时间以合同签订日期为准。";
            if(!proof.equals(tail))return null;
        }else{
            Matcher form=Pattern.compile("^填写(附表[0-9]+-[0-9]+)。").matcher(proof);if(!form.find())return null;parameters.put("formReference",form.group(1));proof=proof.substring(form.end());
            String tail="需提交合同关键页扫描件，关键页包括合同名称、合同金额及签字盖章页等；"+FRAMEWORK+"，如提供EPC项目合同，则以施工部分金额为准。时间以合同签订日期为准。（如为联合体投标，则须为施工单位业绩）。";
            if(!proof.equals(tail))return null;
        }
        LocalDate since;try{since=LocalDate.of(Integer.parseInt(award.group(2)),Integer.parseInt(award.group(3)),Integer.parseInt(award.group(4)));}catch(Exception invalid){return null;}
        BigDecimal per=decimal(award.group(11)),max=decimal(award.group(12)),minimum=decimal(award.group(7));if(per==null||max==null||minimum==null||minimum.signum()<0||per.signum()<=0||max.signum()<=0)return null;
        parameters.putAll(linked("sinceDate",since.toString(),"amountMinimum",award.group(7),"amountUnit",award.group(8),"performanceAmountBasis",cn.bidcheck.compat.Java8Api.isBlank(basis)?"CONTRACT_OR_EPC_CONSTRUCTION":"CONSTRUCTION_INSTALLATION",
                "performanceType",award.group(9),"personRole",role,"pointsPerItem",award.group(11),"maxScore",award.group(12),"commonProofQuote",parts[1],
                "frameworkOrderAggregation",true,"decisionScope","PERFORMANCE_SCORE_WITH_COMMON_PROOF"));
        if(person)parameters.putAll(linked("requiresReferenceDate",true,"referenceEvent","BID_DEADLINE"));
        else parameters.put("performanceCutoffPolicy","VERIFIED_SUBMISSION_BOUNDARY");
        return new Rule("SOURCE-PERFORMANCE-"+identity(original).substring(0,12),award.group(1)+"逐合同核验计分","SCORE_COUNT",person?role:"施工单位","scoring","","","",parameters,original,"",cn.bidcheck.compat.Java8Api.listOf());
    }
    static Map<String,Object> params(String mode){return linked("sourceScoringVersion",SourceScoringRules.VERSION,"sourceScoringMode",mode,"deterministicFamily","SOURCE_SCORING","serverOwned",true);}
    static void putSocial(Map<String,Object> params,Matcher m){params.putAll(linked("earliestMonth",m.group(1)+"-"+String.format(Locale.ROOT,"%02d",Integer.parseInt(m.group(2))),"months",RequirementGrounding.number(m.group(3)).intValue(),"specialProofPermitted",true));}
    /** Rebuild navigation from the current frozen document, never a replayed/model row map.
     * These parameters select reads only; evaluate() independently rereads every business fact. */
    static Plan bindDeclaredTargets(Plan plan,EvidenceCorpus corpus){
        List<Rule> roots=new ArrayList<>();for(Rule root:plan.rules())roots.add(bindDeclaredTargets(root,corpus));
        return new Plan(plan.logic(),roots,plan.method(),plan.issues(),plan.policyNotes(),plan.requirementScope());
    }
    private static Rule bindDeclaredTargets(Rule rule,EvidenceCorpus corpus){
        Map<String,Object> p=new LinkedHashMap<>(rule.parameters());p.remove("declaredPerformanceReadRows");
        if(MODE.equals(str(p,"sourceScoringMode"))&&SourceScoringRules.issue(rule)==null){
            List<Map<String,Object>> rows=DeclaredPerformanceRows.read(rule,"",corpus,Collections.emptyList());
            if(!rows.isEmpty())p.put("declaredPerformanceReadRows",rows);
        }
        List<Rule> children=new ArrayList<>();for(Rule child:rule.children())children.add(bindDeclaredTargets(child,corpus));
        return new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),p,rule.sourceQuote(),rule.logic(),children);
    }
    static List<Rule> evidenceRules(Rule parent){
        Map<String,Object> p=readParams(parent);List<Rule> rules=new ArrayList<>();
        boolean personal=!cn.bidcheck.compat.Java8Api.isBlank(str(parent.parameters(),"personRole"));
        rules.add(read(parent,"SUBJECT",personal?"PERSON_ROLE":"RELATION",parent.subject(),personal?"当前任职人员及单位":"当前施工单位、投标人与联合体成员的明确角色关系",p));
        Map<String,Object> contracts=new LinkedHashMap<>(p);contracts.putAll(linked("requiredFields",cn.bidcheck.compat.Java8Api.listOf("contractName", "contractNumber", "party", "person", "personRole", "amount", "unit", "amountBasis", "signedDate"),"requiredAmountBasis",personal?"建安费、建筑安装工程费":"合同金额、签约金额；EPC仅施工部分金额", "amountBasisMustQuoteOriginalLabel",true,"performanceType",parent.parameters().get("performanceType")));
        List<Map<String,Object>> rows=RuleEvaluator.maps(parent.parameters().get("declaredPerformanceReadRows"));
        if(rows.isEmpty())rules.add(read(parent,"CONTRACTS","CONTRACT","","逐合同名称、合同号、签约主体、签订日期、同合同施工或建安费金额及人员任职",contracts));
        else for(Map<String,Object> row:rows){
            Map<String,Object> scoped=new LinkedHashMap<>(contracts);scoped.put("declaredPerformanceRow",row);
            scoped.put("readScopePolicy","EXACT_DECLARED_ROW_AND_CONTRACT_PACKET");
            scoped.put("navigationIsBusinessProof",false);
            String suffix="CONTRACTS-ROW-"+identity(str(row,"inventoryIdentity")+"|"+str(row,"documentHash")+"|"+row.get("documentVersion")).substring(0,16);
            rules.add(read(parent,suffix,"CONTRACT","","核验申报表第"+row.get("row")+"行："+str(row,"separatorTitle")+"；仅读取本行申报页及合同页段，分别取证合同总价、设计费、装修部分金额、建安费",scoped));
        }
        if(!cn.bidcheck.compat.Java8Api.isBlank(str(parent.parameters(),"personRole")))rules.add(socialRead(parent,parent.subject(),p));
        return rules;
    }
    static Map<String,Object> readParams(Rule parent){Map<String,Object> p=linked("scoringParentRuleId",parent.id(),"readPurpose","SCORE_FACT_DEPENDENCY","sourceScoringReadVersion",SourceScoringRules.VERSION);if(parent.parameters().containsKey("requirementSourceContext"))p.put("requirementSourceContext",parent.parameters().get("requirementSourceContext"));return p;}
    static Rule read(Rule parent,String suffix,String predicate,String subject,String description,Map<String,Object> p){return new Rule(parent.id()+"-READ-"+suffix,description,predicate,subject,"","","","",p,parent.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());}
    static Rule socialRead(Rule parent,String subject,Map<String,Object> base){Map<String,Object> p=new LinkedHashMap<>(base);p.putAll(linked("count",parent.parameters().get("months"),"earliestMonth",parent.parameters().get("earliestMonth"),"requiresReferenceDate",true,"referenceEvent","BID_DEADLINE","requiredFields",cn.bidcheck.compat.Java8Api.listOf("person", "employer", "months")));return read(parent,"SOCIAL","MONTHS",subject,"当前人员及本单位连续社保",p);}
    static Decision readDecision(Rule read, Decision parent, RuleEvaluator evaluator) {
        if (parent != null && "SCORE_FACT_DEPENDENCY".equals(str(read.parameters(), "readPurpose"))
                && parent.id().equals(str(read.parameters(), "scoringParentRuleId"))
                && "source-performance-score-1".equals(str(parent.proof(), "executor"))) {
            if (read.id().equals(parent.id() + "-READ-SUBJECT") && read.predicate().equals("PERSON_ROLE")) {
                // The parent's target string alone is not an identity proof. Re-run the actual
                // prerequisite so conflicting same-name identifiers remain unresolved.
                Decision subject = evaluator.evaluate(read);
                if (AgentInvestigationScope.closed(subject)) return closedRead(read, parent,
                    subject.status(), subject.reason(), subject.proof(), subject.evidences());
                return subject;
            }
            if (read.id().equals(parent.id() + "-READ-SUBJECT") && read.predicate().equals("RELATION")
                    && "施工单位".equals(read.subject())
                    && SourceScoringRules.VERSION.equals(str(read.parameters(), "sourceScoringReadVersion"))) {
                return companyReadDecision(read, parent, evaluator);
            }
            if (read.id().equals(parent.id() + "-READ-SOCIAL") && read.predicate().equals("MONTHS")) {
                Map<String,Object> social = parent.proof().get("socialProof") instanceof Map<?,?>
                    ? (Map<String,Object>) parent.proof().get("socialProof") : Collections.emptyMap();
                if (cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(str(social, "status"))
                        && social.get("gaps") instanceof Collection<?> && ((Collection<?>) social.get("gaps")).isEmpty())
                    return closedRead(read, parent, str(social, "status"), str(social, "reason"), social, parent.evidences());
            }
        }
        return declaredReadDecision(read, parent);
    }
    private static Decision companyReadDecision(Rule read, Decision parent, RuleEvaluator evaluator) {
        List<Fact> acceptedBindings = new ArrayList<>();
        for (Fact fact : DomainBindings.roleLinks(read.subject(), evaluator.facts, evaluator.corpus)) {
            if (fact.kind().equals("RELATION") && valid(fact, evaluator)) acceptedBindings.add(fact);
        }
        // Reparse every current agreement, including competing allocations not extracted by the Agent.
        // Membership alone and a company name in an old contract do not bind the construction role.
        List<Fact> sourceBindings = EnterpriseSubjectBindings.extract(
            Collections.singletonList(read), evaluator.corpus.all);
        Set<String> acceptedCompanies = new LinkedHashSet<>();
        Set<String> sourceCompanies = new LinkedHashSet<>();
        for (Fact fact : acceptedBindings) acceptedCompanies.add(DomainBindings.roleTarget(fact, read.subject()));
        for (Fact fact : sourceBindings) sourceCompanies.add(DomainBindings.roleTarget(fact, read.subject()));
        if (acceptedCompanies.size() != 1 || !acceptedCompanies.equals(sourceCompanies)) {
            return evaluator.unknown(read, "PERFORMANCE_SUBJECT_REQUIRED",
                "当前已采信关系与本次协议原文尚未共同证明唯一施工单位，保留其他成员及竞争职责来源", acceptedBindings);
        }
        List<Fact> allBindings = new ArrayList<>(acceptedBindings);
        allBindings.addAll(sourceBindings);
        Map<String,Object> binding = AgentSubjectBindings.resolve(read.subject(), allBindings,
            evaluator.corpus, RuleEvaluator.maps(evaluator.context.get("_validatedLayoutCells")));
        if (!"RESOLVED".equals(str(binding, "bindingStatus"))) {
            return evaluator.unknown(read, "PERFORMANCE_SUBJECT_REQUIRED",
                "施工单位的当前来源身份仍存在歧义或冲突", acceptedBindings, binding);
        }
        if (evaluator.incompleteSourceReview.contains(read.id())
                || evaluator.incompleteSourceReview.contains(parent.id())) {
            return evaluator.unknown(read, "SHARED_SOURCE_REVIEW_REQUIRED",
                "施工主体尚有未完成的来源反证复核，不能仅凭已采信关系闭合", acceptedBindings, binding);
        }
        Decision verified = evaluator.pass(read, "本次协议原文与已采信关系共同证明唯一施工单位", acceptedBindings,
            linked("binding", binding, "sourceAllocationFactIds",
                cn.bidcheck.compat.Java8Api.toList(sourceBindings.stream().map(Fact::id)),
                "proofScope", "CURRENT_CONSTRUCTION_PARTY_ONLY"));
        Decision scoped = closedRead(read, parent, verified.status(), verified.reason(), verified.proof(), verified.evidences());
        scoped = RecoveryCoverageDependencies.guard(read, scoped, evaluator);
        return InferenceClaims.pending(read, scoped, evaluator.context);
    }
    private static Decision closedRead(Rule read, Decision parent, String status, String reason,
            Map<String,Object> proof, List<cn.bidcheck.domain.Domain.Evidence> evidences) {
        return new Decision(read.id(), read.description(), status, reason, evidences, Collections.emptyList(),
            linked("decisionScope", "EVIDENCE_READ_ONLY", "businessProof", false, "scoreProduced", false,
                "parentBusinessStatus", parent.status(), "dependencyProof", proof));
    }
    static Decision declaredReadDecision(Rule read,Decision parent){
        if(parent==null||!(read.parameters().get("declaredPerformanceRow") instanceof Map<?,?>))return parent;
        Map<String,Object> owner=declaredOwnerScope(read,parent);
        if(!owner.isEmpty()&&!"CURRENT_PERSON".equals(str(owner,"state"))){
            boolean other="OTHER_PERSON".equals(str(owner,"state"));
            Map<String,Object> scoped=linked("decisionScope","EVIDENCE_READ_ONLY","businessProof",false,"scoreProduced",false,"parentBusinessStatus",parent.status(),"ownerScope",owner,"candidateNavigationOnly",!other,"requiredForCurrentSubject",false);
            List<Map<String, Object>> ownerReadActions = new ArrayList<>();
            if (!other) {
                for (Map<String, Object> action : RuleEvaluator.maps(parent.proof().get("requiredEvidenceActions"))) {
                    if (read.id().equals(str(action, "targetId"))) {
                        ownerReadActions.add(action);
                    }
                }
            }
            scoped.put("requiredEvidenceActions", ownerReadActions);
            return new Decision(read.id(),read.description(),other?"NOT_APPLICABLE":"UNDETERMINED",other?"原文明确本行申报人并非当前拟派人员，本行取证不再必需；不产生业务分值":"当前人员或本表属人尚未唯一核实，仅保留候选导航",parent.evidences(),other?Collections.emptyList():Collections.singletonList(gap("PERFORMANCE_DECLARATION_OWNER_REQUIRED","须先核实当前人员及申报表的明确属人关系")),scoped);
        }
        Map<?,?> row=(Map<?,?>)read.parameters().get("declaredPerformanceRow");List<Map<String,Object>> matching=new ArrayList<>();
        for(Map<String,Object> check:RuleEvaluator.maps(parent.proof().get("contractChecks"))){
            if(!(check.get("declaredRow") instanceof Map<?,?>))continue;Map<?,?> actual=(Map<?,?>)check.get("declaredRow");boolean same=true;
            for(String key:Arrays.asList("inventoryIdentity","documentId","documentHash","documentVersion","inventoryPage","row","rowQuote","proofPageStart","proofPageEnd"))if(!Objects.equals(row.get(key),actual.get(key)))same=false;
            if(same)matching.add(check);
        }
        boolean closed=matching.size()==1&&strings(matching.get(0).get("missing")).isEmpty()
            &&(Boolean.TRUE.equals(matching.get(0).get("counted"))||!strings(matching.get(0).get("excluded")).isEmpty())
            &&parent.gaps().stream().allMatch(g->"PERFORMANCE_PROOF_COVERAGE_REQUIRED".equals(g.code()));
        Map<String,Object> proof=new LinkedHashMap<>(parent.proof());proof.put("contractChecks",matching);
        proof.put("parentBusinessStatus",parent.status());proof.put("rowEvidenceClosed",closed);proof.put("businessProof",false);
        // Acquisition completion is not a new score and never replaces the parent's decision.
        proof.remove("score");proof.put("scoreProduced",false);
        List<Map<String,Object>> actions=new ArrayList<>();for(Map<String,Object> action:RuleEvaluator.maps(parent.proof().get("requiredEvidenceActions")))if(read.id().equals(str(action,"targetId")))actions.add(action);proof.put("requiredEvidenceActions",actions);
        return new Decision(read.id(),read.description(),closed?"PASS":"UNDETERMINED",closed?"本申报行的合同证据已逐项核验；其余申报行及总评分保持独立":"本申报行仍有未闭合的合同证据",parent.evidences(),closed?Collections.emptyList():parent.gaps().isEmpty()?Collections.singletonList(gap("PERFORMANCE_PROOF_COVERAGE_REQUIRED","本行尚未取得唯一完整合同回执")):parent.gaps(),proof);
    }
    static Map<String,Object> declaredOwnerScope(Rule read,Decision parent){
        if(parent==null||!(read.parameters().get("declaredPerformanceRow") instanceof Map<?,?>))return Collections.emptyMap();
        Map<?,?> row=(Map<?,?>)read.parameters().get("declaredPerformanceRow");
        for(Map<String,Object> scope:RuleEvaluator.maps(parent.proof().get("declaredOwnerScopes"))){
            if(!(scope.get("declaredRow") instanceof Map<?,?>))continue;Map<?,?> actual=(Map<?,?>)scope.get("declaredRow");boolean same=true;
            for(String key:Arrays.asList("inventoryIdentity","documentId","documentHash","documentVersion","inventoryPage","row","rowQuote","proofPageStart","proofPageEnd"))if(!Objects.equals(row.get(key),actual.get(key)))same=false;
            if(same)return scope;
        }
        return Collections.emptyMap();
    }
    static Decision evaluate(Rule rule,RuleEvaluator e){
        BigDecimal per=decimal(rule.parameters().get("pointsPerItem")),max=decimal(rule.parameters().get("maxScore")),minimum=RuleEvaluator.normalize(decimal(rule.parameters().get("amountMinimum")),str(rule.parameters(),"amountUnit"));
        Map<String,Object> proof=linked("executor","source-performance-score-1","scoreProduced",false,"pointsPerItem",per,"scoreMaximum",max,"contractChecks",new ArrayList<>());
        boolean personal=!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"personRole"));
        if(!personal&&!"BID_SUBMISSION".equals(str(e.context,"evaluationStage")))return e.unknown(rule,"PERFORMANCE_CUTOFF_STAGE_REQUIRED","原文“至今”未明确日期事件；当前并非已确认投标阶段，不能默认使用系统今天或其他阶段日期",cn.bidcheck.compat.Java8Api.listOf(),proof);
        Rule cutoff=new Rule(rule.id()+"-STATISTICS-CUTOFF","投标阶段业绩统计截止依据","REFERENCE_TIME","","","","","",cn.bidcheck.compat.Java8Api.mapOf("referenceEvent", "BID_DEADLINE"),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        List<String> scopedTargets=e.resolvedSubjects(rule);
        String currentPerson=scopedTargets.size()==1?scopedTargets.get(0):"";
        if(personal&&e.identities.issue(read(rule,"SUBJECT","PERSON_ROLE",rule.subject(),"当前人员",readParams(rule)),Collections.emptyList())!=null)currentPerson="";
        List<Map<String,Object>> candidates=DeclaredPerformanceRows.read(rule,"",e.corpus,RuleEvaluator.maps(e.context.get("_validatedLayoutCells")));
        List<Map<String,Object>> declared=new ArrayList<>();
        if(personal){
            List<Map<String,Object>> scopes=new ArrayList<>();
            for(Map<String,Object> row:candidates){
                String state=currentPerson.isEmpty()?"SUBJECT_UNRESOLVED":!"SOURCE_BOUND".equals(row.get("ownerBindingStatus"))?"OWNER_UNRESOLVED":currentPerson.equals(str(row,"declaredOwner"))?"CURRENT_PERSON":"OTHER_PERSON";
                scopes.add(linked("declaredRow",row,"state",state,"currentPerson",currentPerson,"ownerSourceIds",row.get("sourceIds"),"ownerSourceQuotes",row.get("ownerSourceQuotes"),"ownerBindingStatus",row.get("ownerBindingStatus")));
                if("CURRENT_PERSON".equals(state))declared.add(row);
            }
            proof.put("declaredOwnerScopes",scopes);proof.put("candidateNavigationRows",candidates);
        }else declared.addAll(candidates);
        if(!candidates.isEmpty()){
            proof.put("declaredPerformanceRows",declared);
            // The host authorizes reads on the generated CONTRACT dependency, not on its scoring parent.
            for(Rule read:evidenceRules(rule))if("CONTRACT".equals(read.predicate())&&!read.parameters().containsKey("declaredPerformanceRow"))
                proof.put("requiredEvidenceActions",DeclaredPerformanceRows.layoutActions(candidates,read.id(),e.corpus));
            Map<String,Object> navigation=new LinkedHashMap<>(rule.parameters());navigation.put("declaredPerformanceReadRows",DeclaredPerformanceRows.read(rule,"",e.corpus,Collections.emptyList()));
            Rule rowParent=new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),navigation,rule.sourceQuote(),rule.logic(),rule.children());
            List<Map<String,Object>> rowTargets=new ArrayList<>(),actions=new ArrayList<>(RuleEvaluator.maps(proof.get("requiredEvidenceActions")));
            for(Rule read:evidenceRules(rowParent))if("CONTRACT".equals(read.predicate())){
                rowTargets.add(linked("id",read.id(),"parentId",rule.id(),"description",read.description(),"declaredPerformanceRow",read.parameters().get("declaredPerformanceRow"),"businessProof",false));
                for(Map<String,Object> row:candidates)if(str(row,"inventoryIdentity").equals(str((Map<String,Object>)read.parameters().get("declaredPerformanceRow"),"inventoryIdentity")))actions.addAll(DeclaredPerformanceRows.layoutActions(Collections.singletonList(row),read.id(),e.corpus));
            }
            proof.put("declaredContractTargets",rowTargets);proof.put("requiredEvidenceActions",actions);
            proof.put("contractChecks",DeclaredPerformanceRows.unverifiedChecks(declared));
            // A server-derived range is a read constraint for contract acquisition only.
            e.declaredContractRows.put(rule.id(),declared);
        }
        LocalDate deadline=e.referenceDate(cutoff);Decision cutoffProof=e.reference(cutoff);
        if(deadline!=null&&!uniqueCutoff(cutoffProof,deadline)){deadline=null;cutoffProof=e.unknown(cutoff,"REFERENCE_TIME_UNCONFIRMED","截止日期的原文标签存在冲突或未唯一绑定，不能以已确认标记代替日期核对",cn.bidcheck.compat.Java8Api.listOf(),cutoffProof.proof());}
        proof.put("performanceCutoff",linked("policy",personal?"EXPLICIT_SOURCE_SUBMISSION_DEADLINE":"VERIFIED_SUBMISSION_BOUNDARY","sourceEventExplicit",personal,"evaluationStage",str(e.context,"evaluationStage"),"referenceProof",cutoffProof.map(),"interpretation",personal?"原文共同证明明确文件递交截止时点":"仅在投标阶段以已核招标截止日作为业绩统计边界；此为统计策略，不声称至今原文明确了日期事件"));
        if(deadline==null)return e.unknown(rule,"REFERENCE_TIME_UNCONFIRMED","业绩统计所需投标截止日期或唯一原文出处尚未核实，不使用系统今天补齐",cn.bidcheck.compat.Java8Api.listOf(),proof);
        List<String> targets=e.resolvedSubjects(rule);
        if(!personal&&targets.isEmpty()&&!EnterpriseSubjectBindings.consortiumDeclared(e.corpus.all))targets=DomainBindings.subjects("投标人",e.facts,e.corpus);
        if(targets.size()!=1)return e.unknown(rule,"PERFORMANCE_SUBJECT_REQUIRED","须先从本次投标材料确定唯一拟派人员或施工单位，不能混算其他人员、其他成员业绩",cn.bidcheck.compat.Java8Api.listOf(),proof);
        String target=targets.get(0);proof.put("target",target);List<Fact> roles=DomainBindings.roleLinks(rule.subject(),e.facts,e.corpus);
        List<Fact> validated=cn.bidcheck.compat.Java8Api.toList(e.facts.stream().filter(f->f.kind().equals("CONTRACT")&&valid(f,e)));Map<String,List<Fact>> groups=contractGroups(validated);Set<String> ambiguousNumbers=ambiguousNumberCandidates(groups);
        Inventory inventory=inventory(rule,target,e);proof.put("submittedInventory",inventory.proof());
        List<Fact> accepted=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();List<Map<String,Object>> checks=new ArrayList<>(),confirmationCandidates=new ArrayList<>();Set<String> counted=new LinkedHashSet<>();
        Decision social=null;if(personal){social=social(rule,target,roles,e);proof.put("socialProof",social.map());if(social.status().equals("PASS"))evidence.addAll(social.evidences());}
        String form=str(rule.parameters(),"formReference");PerformanceInventoryProof submittedForm=cn.bidcheck.compat.Java8Api.isBlank(form)?null:new PerformanceInventoryProof(form,e);
        Map<String,Long> contractNameCounts=new HashMap<>();for(List<Fact> group:groups.values())group.stream().map(f->clean(str(f.attributes(),"contractName"))).filter(n->!cn.bidcheck.compat.Java8Api.isBlank(n)).distinct().forEach(n->contractNameCounts.merge(n,1L,Long::sum));
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact>> group:groups.entrySet()){
            List<Fact> facts=group.getValue();List<String> missing=new ArrayList<>(),excluded=new ArrayList<>();Set<String> parties=new LinkedHashSet<>(),dates=new LinkedHashSet<>(),names=new LinkedHashSet<>();
            if(facts.stream().map(f->numberCandidate(str(f.attributes(),"contractNumber"))).anyMatch(ambiguousNumbers::contains))missing.add("合同编号去除分隔符后存在同号候选，名称或日期未能证明同一合同，暂不重复计分");
            Set<String> numbers=new LinkedHashSet<>();facts.forEach(f->{if(!cn.bidcheck.compat.Java8Api.isBlank(str(f.attributes(),"contractNumber")))numbers.add(str(f.attributes(),"contractNumber"));});
            if(inventory.complete()&&numbers.stream().noneMatch(inventory.numbers()::contains))continue;
            facts.forEach(f->{if(!cn.bidcheck.compat.Java8Api.isBlank(str(f.attributes(),"party")))parties.add(str(f.attributes(),"party"));if(!cn.bidcheck.compat.Java8Api.isBlank(str(f.attributes(),"signedDate")))dates.add(str(f.attributes(),"signedDate"));if(!cn.bidcheck.compat.Java8Api.isBlank(str(f.attributes(),"contractName")))names.add(str(f.attributes(),"contractName"));});
            Map<String,Object> packet=DeclaredPerformanceRows.packet(facts,declared,e.corpus);
            if(personal&&!candidates.isEmpty()){
                List<Map<String,Object>> ownerRows=new ArrayList<>();for(Map<String,Object> row:declared)if(!facts.isEmpty()&&facts.stream().flatMap(f->f.sourceIds().stream()).allMatch(id->DeclaredPerformanceRows.contains(row,e.corpus.byId.get(id))))ownerRows.add(row);
                if(ownerRows.size()!=1)missing.add("本合同对应当前人员的唯一原文申报表属人关系");
            }
            List<Source> sources=contractSources(facts,e);String text=sources.stream().map(s->s.evidence().quote()).reduce((a,b)->a+'\n'+b).orElse("");
            if(names.size()!=1)missing.add("同一合同名称");if(parties.isEmpty())missing.add("签约单位");else if(!personal&&!parties.contains(target))excluded.add("非本次施工单位业绩");
            if(parties.stream().anyMatch(party->!Pattern.compile("(?:承包人|承包方|乙方|施工单位|设计单位)[\\s:：|（(盖章）)]*"+Pattern.quote(party)+"(?=[\\s|，,。；;（）()]|$)").matcher(text).find()))missing.add("各签约单位与同一合同承包方的原文关系");
            if(dates.size()!=1)missing.add("同一合同签订日期");else {LocalDate date=date(dates.iterator().next());if(date==null)missing.add("签订日期格式");else if(date.isBefore(LocalDate.parse(str(rule.parameters(),"sinceDate")))||date.isAfter(deadline))excluded.add("签订日期不在招标窗口");}
            if(dates.size()==1&&!boundSigningDate(text,dates.iterator().next()))missing.add("签订日期标签与日期的对应关系");
            if(text.matches("(?s).*(?:示范文本|填写示例|空白合同|拟签订).*"))missing.add("非模板的已签合同原文");
            if(!text.contains("装饰装修")&&!text.contains("装修工程"))missing.add("装饰装修项目性质");
            boolean epc=Pattern.compile("(?i)(?:EPC|工程总承包)").matcher(text).find();String type=str(rule.parameters(),"performanceType");
            List<Map<String,Object>> workRoles=new ArrayList<>();
            if(!personal)workRoles.addAll(workRole(sources,target,type,epc,names,parties.size()));
            else {List<Map<String,Object>> membership=personalMembership(sources,target,str(rule.parameters(),"personRole"),parties);Set<String> members=new LinkedHashSet<>();membership.forEach(m->members.add(str(m,"party")));
                if(membership.isEmpty()||members.size()!=1||members.contains("")||!parties.contains(members.iterator().next()))missing.add("历史任职人员与同一合同成员单位的局部归属尚未唯一证明");
                else {String member=members.iterator().next();workRoles.addAll(workRole(sources,member,type,epc,names,parties.size()));workRoles.forEach(m->m.put("personMembershipProof",membership));}}
            if(workRoles.isEmpty())missing.add("同一合同中签约主体明确承担的"+type+"工作；其他成员分工不能借用");
            if(sources.stream().noneMatch(s->s.evidence().quote().matches("(?s).*(?:甲方|发包人|建设单位).*(?:签字|签章|盖章).*" )&&s.evidence().quote().matches("(?s).*(?:乙方|承包人|施工单位|设计单位).*(?:签字|签章|盖章).*")))missing.add("同一合同签字盖章页");
            if(personal){
                List<Fact> individual=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->target.equals(str(f.attributes(),"person"))&&PersonnelRoles.canonical(str(f.attributes(),"personRole")).equals(PersonnelRoles.canonical(str(rule.parameters(),"personRole")))));
                if(individual.isEmpty())missing.add("该合同中的同一负责人任职证明");
                else if(individual.stream().noneMatch(f->roles.stream().anyMatch(r->e.identities.samePerson(r,f))))missing.add("当前任职与历史合同人员身份关联");
                if(individual.stream().noneMatch(f->Pattern.compile(Pattern.quote(str(f.attributes(),"personRole"))+"[\\s:：|]*"+Pattern.quote(target)+"(?=[\\s|，,。；;（）()]|$)").matcher(text).find()))missing.add("合同任职岗位与该姓名的直接对应");
                if(social==null||!social.status().equals("PASS"))missing.add("同一人员在本单位连续社保或已核特殊证明");
            }
            List<Map<String,Object>> formRecords=cn.bidcheck.compat.Java8Api.listOf(),formCandidates=cn.bidcheck.compat.Java8Api.listOf();if(submittedForm!=null){
                String contractName=names.size()==1?names.iterator().next():"";Set<String> contractDocuments=new HashSet<>();sources.forEach(s->contractDocuments.add(s.evidence().documentId()));List<Fact> rowFacts=new ArrayList<>(facts);e.facts.stream().filter(InferenceClaims::humanConfirmed).forEach(rowFacts::add);PerformanceInventoryProof.Match rows=submittedForm.matchWithEvidence(contractName,numbers,contractNameCounts.getOrDefault(clean(contractName),0L)==1L,contractDocuments,sources,rowFacts);formCandidates=rows.candidates();
                if(!rows.present())missing.add(form+"中对应本合同的唯一实填企业业绩记录");else {evidence.addAll(rows.evidence());formRecords=rows.records();}
                confirmationCandidates.addAll(inventoryCandidates(rule,contractName,formCandidates,sources,e));
            }
            Amount amount=amount(rule,facts,sources,epc,minimum,e.facts,packet,e.corpus);missing.addAll(amount.missing());if(amount.value()!=null&&amount.value().compareTo(minimum)<0)excluded.add("正确金额口径低于限额");
            if(!amount.missing().isEmpty())confirmationCandidates.addAll(amountCandidates(rule,facts,sources,epc,e));
            for(Map<String,Object> row:formRecords){List<String> rowDates=strings(row.get("signedDates"));if(rowDates.size()!=1||dates.size()!=1)missing.add("业绩清单签订日期尚未与合同逐行核对");else if(!dates.contains(rowDates.get(0)))missing.add("业绩清单签订日期与合同签订日期矛盾");
                Set<BigDecimal> listed=new TreeSet<>();for(String value:strings(row.get("listedAmounts"))){BigDecimal n=decimal(value);if(n!=null)listed.add(n);}Set<BigDecimal> totals=new TreeSet<>();Set<String> confirmedAmounts=new HashSet<>("HUMAN_CONFIRMED_EXISTING_AMOUNT_OCCURRENCE".equals(amount.proof().get("method"))?strings(amount.proof().get("factIds")):cn.bidcheck.compat.Java8Api.listOf());for(Fact f:facts)if(str(f.attributes(),"amountBasis").matches(".*(?:合同金额|签约金额|合同总价|合同价款).*")&&(confirmedAmounts.isEmpty()||confirmedAmounts.contains(f.id()))){BigDecimal total=RuleEvaluator.normalize(decimal(f.attributes().get("amount")),str(f.attributes(),"unit"));if(total!=null)totals.add(total);}
                if(totals.isEmpty()&&"DEDUPLICATED_ORDER_LOWER_BOUND".equals(amount.proof().get("method"))&&amount.value()!=null)totals.add(amount.value());
                if(listed.size()!=1||totals.size()!=1)missing.add("业绩清单金额与合同同口径金额尚未逐行核对");else if(listed.iterator().next().compareTo(totals.iterator().next())!=0)missing.add("业绩清单金额与合同同口径金额矛盾");}
            if(missing.isEmpty()&&excluded.isEmpty()){counted.add(group.getKey());accepted.addAll(facts);sources.forEach(s->evidence.add(s.evidence()));}
            List<Map<String,Object>> amounts=new ArrayList<>();for(Fact fact:facts)if(fact.attributes().containsKey("amount"))amounts.add(linked("factId",fact.id(),"amount",fact.attributes().get("amount"),"unit",fact.attributes().get("unit"),"amountBasis",fact.attributes().get("amountBasis"),"taxBasis",factTaxBasis(fact),"sourceIds",fact.sourceIds()));
            checks.add(linked("contractIdentity",group.getKey(),"contractNumbers",cn.bidcheck.compat.Java8Api.listCopyOf(numbers),"factIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().map(Fact::id)),"sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)),"submittedFormRecords",formRecords,"submittedFormCandidates",formCandidates,"partyWorkRoleProof",workRoles,"amountBasis",amount.basis(),"amount",amount.value(),"amountObservations",amounts,"amountEvidence",amount.proof(),"missing",missing,"excluded",excluded,"counted",missing.isEmpty()&&excluded.isEmpty()));
        }
        if(!declared.isEmpty()){
            for(Map<String,Object> declaration:declared){
                Map<String,Object> matched=null;
                for(Map<String,Object> candidate:checks){
                    List<String> ids=strings(candidate.get("sourceIds"));
                    if(ids.isEmpty()||!ids.stream().map(e.corpus.byId::get).allMatch(source->DeclaredPerformanceRows.contains(declaration,source)))continue;
                    if(matched!=null){matched=null;break;}matched=candidate;
                }
                if(matched==null)checks.addAll(DeclaredPerformanceRows.unverifiedChecks(Collections.singletonList(declaration)));
                else matched.put("declaredRow",declaration);
            }
        }
        BigDecimal lower=per.multiply(BigDecimal.valueOf(counted.size())).min(max);proof.putAll(linked("contractChecks",checks,"scoreLower",lower,"scoreUpper",max,"deduplicatedContracts",counted.size(),"countedContractIdentities",counted,"signaturePageCheck","KEY_PAGE_SUBMISSION_ONLY","authenticityAssessed",false));
        if(lower.compareTo(max)>=0){proof.putAll(linked("score",max,"scoreProduced",true,"fullScore",true));return result(rule,"PASS","已逐合同核清主体、时间、金额口径及共同证明，按去重业绩达到原文评分上限",evidence,proof,cn.bidcheck.compat.Java8Api.listOf());}
        Set<String> checked=new HashSet<>();checks.forEach(c->checked.addAll(strings(c.get("contractNumbers"))));
        boolean ownersResolved=!personal||RuleEvaluator.maps(proof.get("declaredOwnerScopes")).stream().allMatch(s->Arrays.asList("CURRENT_PERSON","OTHER_PERSON").contains(str(s,"state")));
        if(ownersResolved&&inventory.complete()&&checked.equals(inventory.numbers())&&checks.stream().allMatch(c->strings(c.get("missing")).isEmpty())){
            proof.putAll(linked("score",lower,"scoreUpper",lower,"scoreProduced",true,"fullScore",false,"exactSubmittedInventoryScore",true));inventory.sources().forEach(s->evidence.add(s.evidence()));
            return result(rule,"PASS","原文明确列示的完整申报清单已逐合同核完，已按实际合格业绩计算确定分值；未达到满分不作为资格否决",evidence,proof,cn.bidcheck.compat.Java8Api.listOf());
        }
        // A cached/model boolean contractSetComplete is not proof of a complete submitted list.
        proof.put("confirmationCandidates",confirmationCandidates);
        proof.put("confirmationCoversAllGaps",inventory.complete()&&!confirmationCandidates.isEmpty()&&checks.stream().allMatch(c->strings(c.get("missing")).stream().allMatch(m->m.contains("唯一实填企业业绩记录")||m.equals("同一合同金额存在不同取值，需核清版本"))));
        return result(rule,"UNDETERMINED","已计算有完整证明的业绩分值下限；尚未证明全部申报合同及共同材料覆盖，不把未召回合同计为0分",evidence,proof,cn.bidcheck.compat.Java8Api.listOf(gap("PERFORMANCE_PROOF_COVERAGE_REQUIRED","逐合同缺口和已排除原因见 contractChecks；需闭合申报清单或继续核证至评分上限")));
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Amount{
        private final BigDecimal value;
        private final String basis;
        private final List<String> missing;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Amount(@com.fasterxml.jackson.annotation.JsonProperty("value") BigDecimal value, @com.fasterxml.jackson.annotation.JsonProperty("basis") String basis, @com.fasterxml.jackson.annotation.JsonProperty("missing") List<String> missing, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.value = value; this.basis = basis; this.missing = missing; this.proof = proof; }
        public BigDecimal value() { return value; }
        public String basis() { return basis; }
        public List<String> missing() { return missing; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Amount that = (Amount) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.basis, that.basis) && java.util.Objects.equals(this.missing, that.missing) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.basis); hash = 31 * hash + java.util.Objects.hashCode(this.missing); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "Amount[" + "value=" + value + ", basis=" + basis + ", missing=" + missing + ", proof=" + proof + "]"; }
}
    private static List<Fact> amountFacts(Rule rule,List<Fact> facts,boolean epc){String requested=str(rule.parameters(),"performanceAmountBasis"),requiredTax=taxBasis(str(rule.parameters(),"taxBasis"));return cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->{String basis=str(f.attributes(),"amountBasis");return requested.equals("CONSTRUCTION_INSTALLATION")?basis.matches(".*(?:建安费|建筑安装工程费).*" ):epc?basis.matches(".*(?:施工部分|施工费用|建安费).*" ):basis.matches(".*(?:合同金额|签约金额|合同价款|合同总价).*" );}).filter(f->cn.bidcheck.compat.Java8Api.isBlank(requiredTax)||requiredTax.equals(factTaxBasis(f))).filter(f->decimal(f.attributes().get("amount"))!=null));}
    private static String taxBasis(String value){if(value.matches(".*(?:不含税|未税|税前|EXCLUSIVE).*"))return "TAX_EXCLUSIVE";if(value.matches(".*(?:含税|税后|INCLUSIVE).*"))return "TAX_INCLUSIVE";return "";}
    private static String factTaxBasis(Fact fact){String tax=taxBasis(str(fact.attributes(),"taxBasis"));return cn.bidcheck.compat.Java8Api.isBlank(tax)?taxBasis(str(fact.attributes(),"amountBasis")):tax;}
    private static boolean conflictingTaxBases(List<Fact> facts){return facts.stream().map(SourcePerformanceScore::factTaxBasis).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().count()>1;}
    private static boolean confirmedAmount(Fact link,Fact original,String requested){return InferenceClaims.humanConfirmed(link)&&link.kind().equals("RELATION")&&link.field().equals("CONTRACT_AMOUNT_BASIS")&&link.subject().equals(str(original.attributes(),"contractName"))&&str(link.attributes(),"sourceContractFactId").equals(original.id())&&str(link.attributes(),"targetAmountBasis").equals(requested)&&str(link.attributes(),"observedBasis").equals(str(original.attributes(),"amountBasis"))&&str(link.attributes(),"originalAmount").equals(str(original.attributes(),"amount"))&&str(link.attributes(),"unit").equals(str(original.attributes(),"unit"))&&link.sourceIds().containsAll(original.sourceIds());}
    private static List<Map<String,Object>> inventoryCandidates(Rule rule,String name,List<Map<String,Object>> rows,List<Source> contractSources,RuleEvaluator evaluator){
        if(cn.bidcheck.compat.Java8Api.isBlank(name)||contractSources.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();
        List<Source> witnesses=cn.bidcheck.compat.Java8Api.toList(contractSources.stream().filter(s->clean(s.evidence().quote()).contains(clean(name))).limit(4));if(witnesses.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        for(java.util.Map<java.lang.String, java.lang.Object> row:rows){List<String> rowIds=strings(row.get("sourceIds"));if(rowIds.isEmpty())continue;Set<String> ids=new LinkedHashSet<>(rowIds);witnesses.forEach(s->ids.add(s.id()));List<Map<String,Object>> refs=claimEvidence(ids,evaluator);if(refs.isEmpty())continue;
            String rowName=str(row,"contractName");Map<String,Object> attributes=linked("object",rowName,"documentId",row.get("documentId"),"inventoryPage",row.get("page"),"inventoryRow",row.get("row"),"contractSourceIds",cn.bidcheck.compat.Java8Api.toList(witnesses.stream().map(Source::id)));
            Map<String,Object> proposed=linked("kind","RELATION","subject",name,"field","CONTRACT_RECORD_MATCH","rawValue",rowName,"value",rowName,"unit","","basis","申报表与合同原件对应","scope","HISTORICAL_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(ids),"attributes",attributes);
            out.add(linked("type","CONTRACT_RECORD_MATCH","claim","申报表第"+row.get("row")+"行“"+rowName+"”对应合同“"+name+"”","reasoning","合同名称相似，但日期、金额或证明页段尚不足以唯一自动关联；请结合两端原文确认。","confidence",row.getOrDefault("nameSimilarity",0.8),"risks",cn.bidcheck.compat.Java8Api.listOf("仅确认清单与合同对应，金额、签章、时间及业绩归属仍逐项核验"),"proposedFact",proposed,"evidence",refs,"targetId",rule.id(),"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.listOf("PERFORMANCE_PROOF_COVERAGE_REQUIRED")));
        }return out;
    }
    private static List<Map<String,Object>> amountCandidates(Rule rule,List<Fact> facts,List<Source> sources,boolean epc,RuleEvaluator evaluator){
        List<Fact> eligible=amountFacts(rule,facts,epc);if(conflictingTaxBases(eligible))return cn.bidcheck.compat.Java8Api.listOf();Set<BigDecimal> values=new TreeSet<>();eligible.forEach(f->{BigDecimal n=RuleEvaluator.normalize(decimal(f.attributes().get("amount")),str(f.attributes(),"unit"));if(n!=null)values.add(n);});if(values.size()<2)return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();
        for(Fact fact:eligible){String name=str(fact.attributes(),"contractName"),requested=str(rule.parameters(),"performanceAmountBasis");if(cn.bidcheck.compat.Java8Api.isBlank(name))continue;List<Map<String,Object>> refs=claimEvidence(new LinkedHashSet<>(fact.sourceIds()),evaluator);if(refs.isEmpty())continue;
            Map<String,Object> attrs=linked("object",requested,"targetAmountBasis",requested,"observedBasis",str(fact.attributes(),"amountBasis"),"sourceContractFactId",fact.id(),"originalAmount",str(fact.attributes(),"amount"),"unit",str(fact.attributes(),"unit"),"contractSourceIds",fact.sourceIds());
            Map<String,Object> proposed=linked("kind","RELATION","subject",name,"field","CONTRACT_AMOUNT_BASIS","rawValue",str(fact.attributes(),"amount"),"value",requested,"unit","","basis","选择既有同口径金额原文","scope","HISTORICAL_PROJECT","sourceIds",fact.sourceIds(),"attributes",attrs);
            out.add(linked("type","CONTRACT_AMOUNT_BASIS","claim","本项采用合同“"+name+"”原文的"+str(fact.attributes(),"amountBasis")+" "+str(fact.attributes(),"amount")+str(fact.attributes(),"unit"),"reasoning","同合同存在多个符合要求口径的不同金额；需要确认适用版本或发生位置，原金额及单位不改写。","confidence",0.8,"risks",cn.bidcheck.compat.Java8Api.listOf("不能将合同总价、设计费、施工部分金额或不同含税口径互相替代"),"proposedFact",proposed,"evidence",refs,"targetId",rule.id(),"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.listOf("PERFORMANCE_PROOF_COVERAGE_REQUIRED")));
        }return out;
    }
    private static List<Map<String,Object>> claimEvidence(Set<String> ids,RuleEvaluator evaluator){if(ids.isEmpty()||ids.size()>12)return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();for(String id:ids){Source source=evaluator.corpus.byId.get(id);if(source==null||source.index()||cn.bidcheck.compat.Java8Api.isBlank(source.evidence().quote()))return cn.bidcheck.compat.Java8Api.listOf();out.add(linked("sourceId",id,"quote",source.evidence().quote()));}return out;}
    private static boolean uniqueCutoff(Decision proof,LocalDate expected){
        if(!(proof.proof().get("referenceDateEvidence") instanceof Map<?,?>))return false;String quote=Objects.toString(((Map<?,?>) (proof.proof().get("referenceDateEvidence"))).get("quote"),"");
        Matcher labels=Pattern.compile("(?:投标截止|文件递交截止|递交截止|投标文件递交的截止|提交截止)(?:时间|日期)?").matcher(quote);Set<String> seen=new HashSet<>();Set<LocalDate> values=new HashSet<>();
        while(labels.find())if(seen.add(labels.group())){List<LocalDate> dates=cn.bidcheck.verification.source.DateFieldValues.read(quote,labels.group(),1);if(dates.size()!=1)return false;values.addAll(dates);}
        return values.equals(cn.bidcheck.compat.Java8Api.setOf(expected));
    }
    private static Amount amount(Rule rule,List<Fact> facts,List<Source> sources,boolean epc,BigDecimal minimum,List<Fact> shared,Map<String,Object> packet,EvidenceCorpus corpus){
        String requested=str(rule.parameters(),"performanceAmountBasis");List<Fact> suitable=amountFacts(rule,facts,epc);
        if(conflictingTaxBases(suitable))return new Amount(null,requested,cn.bidcheck.compat.Java8Api.listOf("同一合同存在不同含税口径，需明确适用金额口径"),linked("taxBases",cn.bidcheck.compat.Java8Api.toList(suitable.stream().map(SourcePerformanceScore::factTaxBasis).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct()),"factIds",cn.bidcheck.compat.Java8Api.toList(suitable.stream().map(Fact::id)),"amountsCombined",false));
        Set<BigDecimal> values=new TreeSet<>();for(Fact f:suitable){BigDecimal value=RuleEvaluator.normalize(decimal(f.attributes().get("amount")),str(f.attributes(),"unit"));if(value!=null)values.add(value);}
        if(values.size()==1)return new Amount(values.iterator().next(),requested,cn.bidcheck.compat.Java8Api.listOf(),linked("method","SOURCE_BOUND_CONTRACT_AMOUNT","factIds",cn.bidcheck.compat.Java8Api.toList(suitable.stream().map(Fact::id))));
        if(values.size()>1){List<Fact> confirmed=cn.bidcheck.compat.Java8Api.toList(suitable.stream().filter(f->shared.stream().anyMatch(link->confirmedAmount(link,f,requested))));Set<BigDecimal> selected=new TreeSet<>();confirmed.forEach(f->{BigDecimal n=RuleEvaluator.normalize(decimal(f.attributes().get("amount")),str(f.attributes(),"unit"));if(n!=null)selected.add(n);});if(selected.size()==1)return new Amount(selected.iterator().next(),requested,cn.bidcheck.compat.Java8Api.listOf(),linked("method","HUMAN_CONFIRMED_EXISTING_AMOUNT_OCCURRENCE","factIds",cn.bidcheck.compat.Java8Api.toList(confirmed.stream().map(Fact::id)),"doesNotChangeAmountBasis",true));return new Amount(null,requested,cn.bidcheck.compat.Java8Api.listOf("同一合同金额存在不同取值，需核清版本"),cn.bidcheck.compat.Java8Api.mapOf());}
        String all=sources.stream().map(s->s.evidence().quote()).reduce((a,b)->a+'\n'+b).orElse("");
        if(!all.contains("框架合同")||!all.matches("(?s).*(?:无签约金额|无固定金额|不约定合同金额|框架合同金额[:：]无).*"))return new Amount(null,requested,cn.bidcheck.compat.Java8Api.listOf("可核的"+(requested.equals("CONSTRUCTION_INSTALLATION")?"建安费":epc?"施工部分金额":"合同金额")),cn.bidcheck.compat.Java8Api.mapOf());
        String number=facts.stream().map(f->str(f.attributes(),"contractNumber")).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)).findFirst().orElse("");if(cn.bidcheck.compat.Java8Api.isBlank(number))return new Amount(null,requested,cn.bidcheck.compat.Java8Api.listOf("无金额框架合同的唯一合同编号"),cn.bidcheck.compat.Java8Api.mapOf());
        Map<String,BigDecimal> orders=new LinkedHashMap<>();List<Map<String,Object>> rows=new ArrayList<>();boolean conflict=false,summary=false;Set<String> orderTaxBases=new LinkedHashSet<>();
        List<Source> orderSources=new ArrayList<>(sources);
        if(!packet.isEmpty())for(Source source:corpus.all)if(DeclaredPerformanceRows.contains(packet,source)&&!orderSources.contains(source))orderSources.add(source);
        for(Source source:orderSources){String text=source.evidence().quote();boolean ranged=!packet.isEmpty()&&DeclaredPerformanceRows.contains(packet,source);
            if(!text.contains(number)&&!ranged||text.matches("(?s).*(?:订单作废|订单取消|已取消订单|负数冲销).*"))continue;
            Matcher parentNumber=Pattern.compile("(?:框架)?合同(?:编号|号)[:：\\s]*([A-Za-z0-9][A-Za-z0-9/-]{2,60})").matcher(text);boolean foreign=false;while(parentNumber.find())if(!numberCandidate(number).equals(numberCandidate(parentNumber.group(1))))foreign=true;if(foreign)continue;
            if(text.contains("订单汇总")||text.contains("订单列表"))summary=true;
            Matcher order=Pattern.compile("订单(?:编号|号)[:：\\s]*([A-Za-z0-9][A-Za-z0-9/-]{2,60})|\\b(PO[-：: ]*[A-Z0-9-]*[0-9][A-Z0-9-]*)",Pattern.CASE_INSENSITIVE).matcher(text);Set<String> identifiers=new LinkedHashSet<>();while(order.find())identifiers.add(order.group(1)==null?order.group(2):order.group(1));if(identifiers.size()!=1)continue;
            String label=requested.equals("CONSTRUCTION_INSTALLATION")?"(?:建安费|建筑安装工程费)":epc?"(?:施工部分金额|施工费用|建安费)":"(?:订单金额|订单价款|含税金额)";
            Matcher amount=Pattern.compile(label+"[:：\\s]*([0-9]+(?:[,.][0-9]+)*)(万元|元)").matcher(text);Set<BigDecimal> amounts=new TreeSet<>();while(amount.find()){BigDecimal value=RuleEvaluator.normalize(decimal(amount.group(1)),amount.group(2));if(value!=null)amounts.add(value);}
            if(amounts.size()!=1)continue;Set<String> taxes=new LinkedHashSet<>();if(Pattern.compile("不含税|未税|税前").matcher(text).find())taxes.add("TAX_EXCLUSIVE");if(Pattern.compile("(?<!不)含税|税后").matcher(text).find())taxes.add("TAX_INCLUSIVE");
            String requiredTax=taxBasis(str(rule.parameters(),"taxBasis"));if(!requiredTax.isEmpty()&&!taxes.equals(Collections.singleton(requiredTax)))continue;orderTaxBases.addAll(taxes);
            String id=identifiers.iterator().next();BigDecimal value=amounts.iterator().next(),old=orders.putIfAbsent(id,value);if(old!=null&&old.compareTo(value)!=0)conflict=true;
            rows.add(linked("orderId",id,"amount",value,"sourceId",source.id(),"taxBases",taxes,"parentContractNumber",number,"associationMethod",text.contains(number)?"EXPLICIT_CONTRACT_NUMBER":"EXACT_SUBMITTED_ROW_AND_SEPARATOR_PACKET","inventoryIdentity",packet.getOrDefault("inventoryIdentity",""),"proofPageStart",packet.get("proofPageStart"),"proofPageEnd",packet.get("proofPageEnd")));
        }
        if(orderTaxBases.size()>1)return new Amount(null,requested,Collections.singletonList("同一框架合同订单存在不同含税口径，不能直接汇总"),linked("orders",rows,"taxBases",orderTaxBases,"amountsCombined",false));
        if(conflict||!summary||orders.isEmpty())return new Amount(null,requested,cn.bidcheck.compat.Java8Api.listOf(conflict?"同一订单金额冲突":!summary?"同一框架合同的订单汇总清单":"框架合同项下逐订单金额与归属"),linked("orders",rows));
        BigDecimal sum=orders.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        // A proven lower bound suffices for a minimum threshold; missing other nonnegative orders
        // must not be represented as an exact final contract amount.
        return sum.compareTo(minimum)>=0?new Amount(sum,requested,cn.bidcheck.compat.Java8Api.listOf(),linked("method","DEDUPLICATED_ORDER_LOWER_BOUND","exactContractTotal",false,"orders",rows))
                :new Amount(null,requested,cn.bidcheck.compat.Java8Api.listOf("已核订单合计尚未达到限额且订单全集未证明"),linked("orders",rows,"amountLower",sum));
    }
    static boolean valid(Fact f,RuleEvaluator e){return new ProofVerifier().issue(f,e.corpus.byId)==null&&DomainBindings.issue(f,e.corpus.byId,RuleEvaluator.maps(e.context.get("_validatedLayoutCells")))==null;}
    private static boolean boundSigningDate(String text,String date){Matcher m=Pattern.compile("(?:合同签订日期|签订日期|合同签订时间|签订时间|签署日期)[\\s:：|]*([^\\n;；|]{1,70})").matcher(text);while(m.find())if(datePresent(m.group(1),date))return true;return false;}
    private static String numberExact(String value){return java.text.Normalizer.normalize(clean(value),java.text.Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[‐‑‒–—―−]","-");}
    private static String numberCandidate(String value){return numberExact(value).replaceAll("[^\\p{L}\\p{N}]","");}
    static String contractIdentity(Fact f){String number=numberExact(str(f.attributes(),"contractNumber")),name=clean(str(f.attributes(),"contractName"));return cn.bidcheck.compat.Java8Api.isBlank(number)&&cn.bidcheck.compat.Java8Api.isBlank(name)?"":identity(cn.bidcheck.compat.Java8Api.isBlank(number)?"name:"+name:"number:"+number);}
    private static Map<String,List<Fact>> contractGroups(List<Fact> facts){
        Map<String,List<Fact>> exact=new LinkedHashMap<>();for(Fact f:facts)if(!cn.bidcheck.compat.Java8Api.isBlank(str(f.attributes(),"contractNumber")))exact.computeIfAbsent(contractIdentity(f),k->new ArrayList<>()).add(f);
        Map<String,List<String>> candidates=new LinkedHashMap<>();for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact>> entry:exact.entrySet()){String candidate=numberCandidate(str(entry.getValue().get(0).attributes(),"contractNumber"));if(!cn.bidcheck.compat.Java8Api.isBlank(candidate))candidates.computeIfAbsent(candidate,k->new ArrayList<>()).add(entry.getKey());}
        Map<String,List<Fact>> result=new LinkedHashMap<>(exact);
        for(java.util.Map.Entry<java.lang.String, java.util.List<java.lang.String>> candidate:candidates.entrySet())if(candidate.getValue().size()>1){List<Fact> all=new ArrayList<>();candidate.getValue().forEach(key->all.addAll(exact.get(key)));Set<String> names=new HashSet<>(),dates=new HashSet<>();all.forEach(f->{names.add(clean(str(f.attributes(),"contractName")));dates.add(str(f.attributes(),"signedDate"));});
            // Punctuation removal identifies a duplicate candidate, not a proven contract.
            // A matching nonempty name AND signing date merge variants. Other collisions
            // stay separate and cannot score until their original identities are resolved.
            if(names.size()==1&&!names.contains("")&&dates.size()==1&&!dates.contains("")){candidate.getValue().forEach(result::remove);result.put(identity("number-variant:"+candidate.getKey()),all);}}
        Map<String,Set<String>> groupsByName=new HashMap<>();for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact>> entry:result.entrySet())entry.getValue().forEach(f->groupsByName.computeIfAbsent(clean(str(f.attributes(),"contractName")),k->new HashSet<>()).add(entry.getKey()));
        for(Fact f:facts)if(cn.bidcheck.compat.Java8Api.isBlank(str(f.attributes(),"contractNumber"))){String name=clean(str(f.attributes(),"contractName"));Set<String> keys=groupsByName.getOrDefault(name,cn.bidcheck.compat.Java8Api.setOf());if(keys.size()>1)continue;String key=keys.size()==1?keys.iterator().next():contractIdentity(f);if(!cn.bidcheck.compat.Java8Api.isBlank(key))result.computeIfAbsent(key,k->new ArrayList<>()).add(f);}return result;
    }
    private static Set<String> ambiguousNumberCandidates(Map<String,List<Fact>> groups){Map<String,Set<String>> keys=new HashMap<>();for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact>> entry:groups.entrySet())for(Fact f:entry.getValue()){String number=numberCandidate(str(f.attributes(),"contractNumber"));if(!cn.bidcheck.compat.Java8Api.isBlank(number))keys.computeIfAbsent(number,k->new HashSet<>()).add(entry.getKey());}Set<String> ambiguous=new HashSet<>();keys.forEach((number,identities)->{if(identities.size()>1)ambiguous.add(number);});return ambiguous;}
    private static final Pattern PARTY_LABEL=Pattern.compile("(?:乙方|承包人|承包方|施工单位|设计单位|成员单位|工作单位|任职单位)[（(]?(?:盖章)?[）)]?[:：\\s|]+([^\\s|，,。；;:：（）()]{2,100})");
    private static Set<String> labelledParties(String text){Set<String> result=new LinkedHashSet<>();Matcher matcher=PARTY_LABEL.matcher(text);while(matcher.find())result.add(matcher.group(1));return result;}
    private static List<Map<String,Object>> personalMembership(List<Source> sources,String person,String role,Set<String> contractParties){
        Pattern appointment=Pattern.compile(Pattern.quote(role)+"[:：\\s|]*"+Pattern.quote(person)+"(?=[\\s|，,。；;（）()]|$)");List<Map<String,Object>> proof=new ArrayList<>();
        for(Source source:sources){String text=source.evidence().quote();if(!appointment.matcher(text).find())continue;Set<String> local=labelledParties(text);List<String> lines=Arrays.asList(text.split("\\R",-1));
            for(int i=0;i<lines.size();i++){String line=lines.get(i);if(!appointment.matcher(line).find())continue;Set<String> bound=labelledParties(line);String quote=line;
                if(bound.isEmpty()&&i>0&&!cn.bidcheck.compat.Java8Api.isBlank(lines.get(i-1))){bound=labelledParties(lines.get(i-1));if(!bound.isEmpty())quote=lines.get(i-1)+"\n"+line;}
                if(bound.isEmpty()&&contractParties.size()==1&&local.equals(contractParties)){bound=local;quote=text;}
                String party=bound.size()==1?bound.iterator().next():"";proof.add(linked("sourceId",source.id(),"person",person,"personRole",role,"party",party,"quote",quote,"binding",cn.bidcheck.compat.Java8Api.isBlank(party)?"UNRESOLVED_PERSON_PARTY":"LOCAL_PERSON_PARTY"));}}
        return proof;
    }
    private static List<Map<String,Object>> workRole(List<Source> sources,String party,String type,boolean epc,Set<String> names,int partyCount){
        List<Map<String,Object>> proof=new ArrayList<>();String p=Pattern.quote(party),role=Pattern.quote(type);
        Pattern explicit=Pattern.compile("(?:^|[\\s|，,])(?:"+role+"(?:单位|方|承包人|部分)[:：\\s]*"+p+"(?=[\\s|，,。；;（）()]|$)|"+p+"\\s*(?:承担|负责)\\s*(?:本合同|本项目|本工程)?\\s*"+role+"(?:部分|工作|任务|工程)?(?:[\\s|，,。；;]|$)|(?:承包人|乙方)[:：\\s]*"+p+"[（(]"+role+"(?:单位|方|部分)[）)])");
        for(Source source:sources)for(String line:source.evidence().quote().split("[\\r\\n；;。]")){if(line.matches(".*(?:不负责|不承担|未承担|不得承担|不再承担|不是|并非)[^。；]{0,15}"+role+".*"))continue;Matcher found=explicit.matcher(line);if(found.find())proof.add(linked("sourceId",source.id(),"party",party,"workRole",type,"quote",line,"binding","EXPLICIT_LOCAL_PARTY_WORK_ROLE"));}
        if(!proof.isEmpty())return proof;
        // A single-party, non-EPC contract may bind its sole contractor to its explicit contract
        // type. EPC/multi-party contracts always need a local division-of-work statement.
        boolean opposite=sources.stream().anyMatch(s->Pattern.compile((type.equals("施工")?"设计":"施工")+"(?:单位|方|部分)[:：\\s]*"+p).matcher(s.evidence().quote()).find());
        if(!epc&&partyCount==1&&!opposite&&names.size()==1&&names.iterator().next().matches(".*"+role+"(?:项目|工程)?合同$"))for(Source source:sources){String text=source.evidence().quote();if(text.contains(names.iterator().next())&&Pattern.compile("(?:乙方|承包人)[:：\\s]*"+p+"(?=[\\s|，,。；;（）()]|$)").matcher(text).find())proof.add(linked("sourceId",source.id(),"party",party,"workRole",type,"quote",text,"binding","SOLE_CONTRACTOR_OF_EXPLICIT_SINGLE_WORK_CONTRACT"));}
        return proof;
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Inventory{
        private final boolean complete;
        private final Set<String> numbers;
        private final List<Source> sources;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Inventory(@com.fasterxml.jackson.annotation.JsonProperty("complete") boolean complete, @com.fasterxml.jackson.annotation.JsonProperty("numbers") Set<String> numbers, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.complete = complete; this.numbers = numbers; this.sources = sources; this.proof = proof; }
        public boolean complete() { return complete; }
        public Set<String> numbers() { return numbers; }
        public List<Source> sources() { return sources; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Inventory that = (Inventory) other; return java.util.Objects.equals(this.complete, that.complete) && java.util.Objects.equals(this.numbers, that.numbers) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.complete); hash = 31 * hash + java.util.Objects.hashCode(this.numbers); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "Inventory[" + "complete=" + complete + ", numbers=" + numbers + ", sources=" + sources + ", proof=" + proof + "]"; }
}
    private static Inventory inventory(Rule rule,String target,RuleEvaluator e){
        List<Source> sources=new ArrayList<>();Set<Set<String>> variants=new HashSet<>();
        for(Source source:e.corpus.all){String text=source.evidence().quote();if(source.index()||source.historical()||source.scopeContext().blocked()||!text.contains(target)||!text.contains("以上为本次申报全部业绩"))continue;
            String role=str(rule.parameters(),"personRole");if(!cn.bidcheck.compat.Java8Api.isBlank(role)&&!text.contains(role))continue;String form=str(rule.parameters(),"formReference");if(!cn.bidcheck.compat.Java8Api.isBlank(form)&&!text.contains(form))continue;
            Matcher total=Pattern.compile("本次申报(?:施工|项目负责人|设计负责人)?业绩共([0-9]{1,3})项").matcher(text);if(!total.find())continue;int declared=Integer.parseInt(total.group(1));if(total.find())continue;
            Matcher contract=Pattern.compile("合同(?:编号|号)[:：\\s]*([A-Za-z0-9][A-Za-z0-9/-]{2,60})").matcher(text);Set<String> numbers=new LinkedHashSet<>();while(contract.find())numbers.add(contract.group(1));if(numbers.size()!=declared)continue;
            variants.add(cn.bidcheck.compat.Java8Api.setCopyOf(numbers));sources.add(source);
        }
        boolean complete=variants.size()==1;Set<String> numbers=complete?variants.iterator().next():cn.bidcheck.compat.Java8Api.setOf();return new Inventory(complete,numbers,cn.bidcheck.compat.Java8Api.listCopyOf(sources),linked("complete",complete,"method","EXPLICIT_SOURCE_COMPLETE_DECLARATION","contractNumbers",numbers,"sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id))));
    }
    private static List<Source> contractSources(List<Fact> facts,RuleEvaluator e){Set<String> ids=new LinkedHashSet<>();facts.forEach(f->ids.addAll(f.sourceIds()));String number=facts.stream().map(f->str(f.attributes(),"contractNumber")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).findFirst().orElse("");Set<String> names=new HashSet<>(),parties=new HashSet<>();facts.forEach(f->{names.add(str(f.attributes(),"contractName"));parties.add(str(f.attributes(),"party"));});
        // Additional key/ordering pages must explicitly name the same contract and party. Page
        // adjacency, project similarity and a common person are not contract linkage evidence.
        for(Source source:e.corpus.all)if(!source.index()&&!cn.bidcheck.compat.Java8Api.isBlank(number)&&source.evidence().quote().contains(number)&&parties.stream().filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).anyMatch(source.evidence().quote()::contains))ids.add(source.id());
        return cn.bidcheck.compat.Java8Api.toList(ids.stream().map(e.corpus.byId::get).filter(Objects::nonNull).filter(s->!s.index()));}
    static Decision social(Rule rule,String person,List<Fact> appointments,RuleEvaluator e){
        Rule employerRule=new Rule(rule.id()+"-SOCIAL-EMPLOYER","人员评分本单位范围","MONTHS",person,"社保","","","",cn.bidcheck.compat.Java8Api.mapOf("employer", "本单位", "personnelRoleSelector", rule.subject()),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.PersonnelEmployerBindings.Binding binding=PersonnelEmployerBindings.resolve(employerRule,e.facts,e.corpus);
        if(!binding.resolved())return e.unknown(rule,"SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED","人员评分须按本次任职与联合体分工唯一确定本单位，不借另一成员的社保",cn.bidcheck.compat.Java8Api.listOf(),binding.audit());
        String employer=binding.employers().get(0);List<Fact> social=cn.bidcheck.compat.Java8Api.toList(e.facts.stream().filter(f->f.kind().equals("MONTHS")&&person.equals(str(f.attributes(),"person"))&&employer.equals(str(f.attributes(),"employer"))&&valid(f,e)&&appointments.stream().anyMatch(a->e.identities.samePerson(a,f))));
        Map<String,Object> params=linked("count",rule.parameters().get("months"),"earliestMonth",rule.parameters().get("earliestMonth"),"employer",employer,"requiresReferenceDate",true,"referenceEvent","BID_DEADLINE");
        Rule requirement=new Rule(rule.id()+"-SOCIAL-"+identity(person).substring(0,8),"同人同单位连续社保","MONTHS",person,"socialInsurance","","","月",params,rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        return e.months(requirement,social);
    }
    static Decision result(Rule r,String status,String reason,List<Evidence> evidence,Map<String,Object> proof,List<Gap> gaps){LOG.info("bidcheck.source_score rule={} mode={} status={} scoreProduced={} sourceCount={} gapCodes={}",r.id(),str(r.parameters(),"sourceScoringMode"),status,proof.getOrDefault("scoreProduced",false),evidence.size(),cn.bidcheck.compat.Java8Api.toList(gaps.stream().map(Gap::code)));return new Decision(r.id(),r.description(),status,reason,cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),gaps,proof);}
}
