package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.source.VisualObservationPolicy;

import cn.bidcheck.domain.Domain.Evidence;
import java.math.*;
import java.time.*;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Business decisions are computed from validated facts, not from a model's item-level status. */
final class RuleEvaluator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RuleEvaluator.class);
    final List<Fact> facts;final EvidenceCorpus corpus;final Map<String,Object> context;
    final List<Map<String,Object>> visualRequests=new ArrayList<>();final Map<String,Decision> decisions=new LinkedHashMap<>();
    final Map<String,Rule> sourceQualificationChoices=new LinkedHashMap<>();
    final Map<String,String> sourceQualificationProofs=new HashMap<>();
    final Map<String,QualificationProofAssociation.Bundle> qualificationProofBundles=new HashMap<>();
    final Set<String> incompleteSourceReview=new HashSet<>();
    private final Map<Rule,Decision> sourceReviewReceipts=new IdentityHashMap<>();
    private final Map<Decision,PersonnelCredentialProof.Selection> personnelSelections=new IdentityHashMap<>();
    private final Map<Decision,List<Fact>> registrationSelections=new IdentityHashMap<>();
    private final Map<Rule,PersonnelCredentialProof.Selection> personnelReviewSelections=new IdentityHashMap<>();
    private List<Source> personnelRecordSources=cn.bidcheck.compat.Java8Api.listOf();
    private Map<String,cn.bidcheck.domain.Domain.Page> personnelRecordPages=cn.bidcheck.compat.Java8Api.mapOf();
    private List<CertificateSourceComparison.Record> personnelRecords;
    final SubjectIdentity identities;
    RuleEvaluator(List<Fact> facts,EvidenceCorpus corpus,Map<String,Object> context){this.facts=facts;this.corpus=corpus;this.context=context;identities=new SubjectIdentity(facts,corpus,maps(context.get("_validatedLayoutCells")));}
    Decision evaluate(Rule r){Decision out;
        sourceReviewReceipts.remove(r);
        personnelReviewSelections.remove(r);
        QualificationProofAssociation.register(r,this);
        if(!r.children().isEmpty()&&!cn.bidcheck.compat.Java8Api.setOf("SCORE_COUNT", "UNSUPPORTED").contains(r.predicate())){
            if(r.logic().equals("CONDITIONAL")){
                Decision condition=evaluate(r.children().get(0));if(!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(condition.status()))out=unknown(r,"REQUIREMENT_APPLICABILITY_UNKNOWN","适用条件尚未证明，不能排除条件分支",cn.bidcheck.compat.Java8Api.listOf());
                else {Decision branch=evaluate(r.children().get(condition.status().equals("PASS")?1:2));out=new Decision(r.id(),r.description(),branch.status(),branch.reason(),union(cn.bidcheck.compat.Java8Api.listOf(condition, branch)),branch.gaps(),linked("operator","CONDITIONAL","condition",condition.map(),"selectedBranch",branch.map()));}
            }else if(r.logic().equals("FOR_EACH")&&!Boolean.TRUE.equals(r.parameters().get("subjectSetComplete")))out=unknown(r,"SUBJECT_SET_INCOMPLETE","逐主体规则尚未证明主体集合完整，不能按已找到的部分人员或成员宣称全部通过",cn.bidcheck.compat.Java8Api.listOf());
            else out=combine(r.id(),r.description(),r.logic(),cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(this::evaluate)));
            if(RuleObligations.hasOwnPredicate(r)){
                Decision own=evaluate(RuleObligations.own(r));
                Decision supported=combine(r.id(),r.description(),"ALL",cn.bidcheck.compat.Java8Api.listOf(own, out));
                Map<String,Object> proof=new LinkedHashMap<>(supported.proof());proof.put("ownPredicate",r.predicate());proof.put("supportingLogic",r.logic());proof.put("supportingConditions",out.proof());
                out=new Decision(supported.id(),supported.description(),supported.status(),supported.reason(),supported.evidences(),supported.gaps(),proof);
            }
        }else out=leaf(r);
        PersonnelCredentialProof.Selection selection=personnelSelections.remove(out);
        List<Fact> registrationSelection=registrationSelections.remove(out);
        if(r.children().isEmpty()&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(out.status())){
            boolean selected=selection!=null&&selection.authorizes(r,this,out);
            List<Fact> broad=match(r),matched=selected?selection.identityFacts():registrationSelection!=null?registrationSelection:broad;
            SubjectIdentity.Problem identityIssue=PersonnelCredentialProof.applies(r)&&!selected
                ?new SubjectIdentity.Problem("TYPED_SELECTION_RECEIPT_REQUIRED","人员证照终态缺少本次原文选择凭据，不能依赖缓存或模型声明") :identities.issue(r,matched);
            Map<String,Object> proof=new LinkedHashMap<>(out.proof());List<Map<String,Object>> bindings=identities.bindings(matched);if(!bindings.isEmpty())proof.put("identityBindings",bindings);
            Map<String,Object> review=selected&&identityIssue==null?SourceLocalPersonnelCredentialClosure.review(r,selection,this):cn.bidcheck.compat.Java8Api.mapOf();
            if(selected){proof.put("selectedEvidenceAudit",linked("authority","SERVER_MEMORY_RECEIPT","selectedRecordIds",cn.bidcheck.compat.Java8Api.toList(selection.records().stream().map(CertificateSourceComparison.Record::id)),"selectedSourceIds",cn.bidcheck.compat.Java8Api.toList(matched.stream().flatMap(f->f.sourceIds().stream()).distinct()),"broadCandidateCount",broad.size(),"selectedCount",matched.size(),"sameCredentialReview",review));
                if(identityIssue==null&&!Boolean.TRUE.equals(review.get("complete")))identityIssue=new SubjectIdentity.Problem(str(review,"reasonCode"),"所选证件存在同编号冲突或尚未完成的原文反证复核");}
            else if(registrationSelection!=null)proof.put("selectedEvidenceAudit",linked("authority","SERVER_REGISTRATION_MEMORY_RECEIPT","selectedFactIds",cn.bidcheck.compat.Java8Api.toList(matched.stream().map(Fact::id)),"selectedSourceIds",cn.bidcheck.compat.Java8Api.toList(matched.stream().flatMap(f->f.sourceIds().stream()).distinct()),"broadCandidateCount",broad.size(),"selectedCount",matched.size(),"proofScope","REGISTRATION_UNIT_ONLY"));
            if(identityIssue!=null){
                List<Map<String,Object>> proposals=identities.confirmationCandidates(r,matched);
                if(!proposals.isEmpty())proof.put("confirmationCandidates",proposals);
                out=new Decision(out.id(),out.description(),"UNDETERMINED",identityIssue.reason(),out.evidences(),cn.bidcheck.compat.Java8Api.listOf(gap(identityIssue.code(),identityIssue.reason())),proof);
            }
            else{
                if(selected&&out.status().equals("PASS"))proof.put("sourceReviewClosure",SourceLocalPersonnelCredentialClosure.closure(r,review,this));
                out=new Decision(out.id(),out.description(),out.status(),out.reason(),out.evidences(),out.gaps(),proof);
                if(selected&&out.status().equals("PASS")){sourceReviewReceipts.put(r,out);personnelReviewSelections.put(r,selection);}
            }
            if(selected||registrationSelection!=null||PersonnelCredentialProof.applies(r))LOG.info("bidcheck.identity_gate ruleId={} authority={} selectedCount={} broadCandidateCount={} decisionAfter={} reasonCode={}",r.id(),selected?"SERVER_MEMORY_RECEIPT":registrationSelection!=null?"SERVER_REGISTRATION_MEMORY_RECEIPT":"MISSING_TYPED_RECEIPT",matched.size(),broad.size(),out.status(),identityIssue==null?"":identityIssue.code());
        }
        if(incompleteSourceReview.contains(r.id())&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(out.status())){
            Decision closed=closeSourceReview(r,out);
            if(closed!=null)out=closed;
            else out=new Decision(out.id(),out.description(),"UNDETERMINED","已复用有原文的事实，但本轮新增或未覆盖来源的反证检查尚未完成，不能沿用已有结论",out.evidences(),cn.bidcheck.compat.Java8Api.listOf(gap("SHARED_SOURCE_REVIEW_REQUIRED","需要完成本轮候选原文的冲突及缺口检查")),out.proof());
        }
        out=plannedCoverage(r,out);
        out=RecoveryCoverageDependencies.guard(r,out,this);
        out=InferenceClaims.discover(r,out,facts,corpus,context);
        out=InferenceClaims.pending(r,out,context);
        decisions.put(r.id(),out);return out;
    }
    /** Reuse parsing only while every immutable source is unchanged in this evaluator. */
    List<CertificateSourceComparison.Record> personnelCredentialRecords(){
        if(personnelRecords==null||!personnelRecordSources.equals(corpus.all)||!personnelRecordPages.equals(corpus.pages)){personnelRecordSources=cn.bidcheck.compat.Java8Api.listCopyOf(corpus.all);personnelRecordPages=cn.bidcheck.compat.Java8Api.mapCopyOf(corpus.pages);personnelRecords=CertificateSourceComparison.records(corpus);}
        return personnelRecords;
    }
    /** Only a fresh in-memory validation receipt can discharge source-review work. An old
     * proof map is not an authorization to skip current-source counterevidence checks. */
    Decision closeSourceReview(Rule rule,Decision decision){
        if(sourceReviewReceipts.get(rule)==decision){
            if(PersonnelCredentialProof.applies(rule)){cn.bidcheck.verification.engine.PersonnelCredentialProof.Selection selection=personnelReviewSelections.get(rule);return selection!=null&&selection.current(rule,this)?decision:null;}
            return decision;
        }
        Decision commitmentReceipt=sourceReviewReceipts.get(rule);
        if(PersonnelWorkloadCommitmentProof.applies(rule)&&commitmentReceipt!=null&&decision.status().equals("PASS")
            &&commitmentReceipt.status().equals("PASS")&&commitmentReceipt.evidences().equals(decision.evidences())
            &&Objects.equals(commitmentReceipt.proof().get("factIds"),decision.proof().get("factIds"))
            &&Objects.equals(commitmentReceipt.proof().get("sourceProofs"),decision.proof().get("sourceProofs")))return decision;
        Map<String,Object> closure=SourceLocalCertificateClosure.proof(rule,decision,this);
        if(closure.isEmpty()&&RegistrationEmployerProof.applies(rule))closure=RegistrationEmployerProof.sourceReviewClosure(rule,decision,this);
        if(closure.isEmpty()&&PersonnelCertificateDocumentProof.proven(rule,decision,this))
            closure=linked("basis","REVERIFIED_PERSONNEL_CERTIFICATE_RECORD","scope","CERTIFICATE_RECORD_SUBMISSION_ONLY","exhaustiveCandidateReview",false,"fullPageVisualVerification",false);
        if(closure.isEmpty()&&SourceLocalExistenceClosure.proven(rule,decision,this)){
            String basis=ConditionalMaterialEvidence.applies(rule)?"REVERIFIED_CURRENT_MATERIAL_BODY":BidLetterResponse.applies(rule)?"REVERIFIED_CURRENT_BID_LETTER_FIELD":"REVERIFIED_CURRENT_SINGLE_APPOINTMENT";
            closure=linked("basis",basis,"exhaustiveCandidateReview",false,"scope",ConditionalMaterialEvidence.applies(rule)?"MATERIAL_SUBMISSION_ONLY":BidLetterResponse.applies(rule)?"WRITTEN_FIELD_RESPONSE_ONLY":"EXISTENCE_ONLY");
        }
        if(closure.isEmpty())return null;
        Map<String,Object> proof=new LinkedHashMap<>(decision.proof());proof.put("sourceReviewClosure",closure);
        Decision closed=new Decision(decision.id(),decision.description(),decision.status(),decision.reason(),decision.evidences(),decision.gaps(),proof);
        if(!RegistrationEmployerProof.applies(rule))sourceReviewReceipts.put(rule,closed);return closed;
    }
    private Decision plannedCoverage(Rule rule,Decision decision) {
        if(!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(decision.status()))return decision;
        List<Map<String,Object>> deferred=new ArrayList<>();
        for(cn.bidcheck.domain.Domain.Document document:corpus.documents)for(cn.bidcheck.domain.Domain.Page page:document.pages())if(page.warning()!=null&&page.warning().contains("[OCR_DEFERRED]"))
            deferred.add(linked("documentId",document.id(),"page",page.number(),"locationType",page.locationType()));
        if(deferred.isEmpty())return decision;
        boolean exhaustive="FOR_EACH".equals(rule.logic())||Boolean.TRUE.equals(rule.parameters().get("requiresFullDocument"))
            ||Boolean.TRUE.equals(rule.parameters().get("fullDocumentRead"))||("PASS".equals(decision.status())&&"SAME_FIELD".equals(rule.predicate()));
        boolean filledConfiguration="PASS".equals(decision.status())&&PersonCountRequirement.roleConfiguration(rule)&&"ROLE_CONFIGURATION_ONLY".equals(decision.proof().get("proofScope"));
        if("PERSON_COUNT".equals(rule.predicate())&&!("PASS".equals(decision.status())&&(cn.bidcheck.compat.Java8Api.setOf(">=", ">", "GE", "GT").contains(rule.operator())||filledConfiguration)
            ||"FAIL".equals(decision.status())&&cn.bidcheck.compat.Java8Api.setOf("<=", "<", "LE", "LT", "=", "==", "EQ").contains(rule.operator())))exhaustive=true;
        boolean negativeAlternative="FAIL".equals(decision.status())&&cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "CERTIFICATE", "VALIDITY").contains(rule.predicate());
        if(!exhaustive&&!negativeAlternative)return decision;
        Map<String,Object> proof=new LinkedHashMap<>(decision.proof());proof.put("decisionBeforeCoverageGuard",decision.status());proof.put("deferredLocations",deferred);
        String reason="检查计划仍有未识别的图像位置，尚未证明全范围或替代材料覆盖，不能由已读取的部分内容确定本条件";
        return new Decision(decision.id(),decision.description(),"UNDETERMINED",reason,decision.evidences(),cn.bidcheck.compat.Java8Api.listOf(gap("OCR_PLAN_COVERAGE_REQUIRED",reason)),proof);
    }
    static Decision combine(String id,String description,String logic,List<Decision> children){
        if(children.isEmpty())return new Decision(id,description,"UNDETERMINED","空条件集合不能构成通过证明",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("EMPTY_RULE_SET","需要可执行条件及适用主体")),cn.bidcheck.compat.Java8Api.mapOf("operator", logic));
        boolean any=logic.equals("ANY"),pass=any?children.stream().anyMatch(d->d.status().equals("PASS")):children.stream().allMatch(d->cn.bidcheck.compat.Java8Api.setOf("PASS", "NOT_APPLICABLE").contains(d.status()));
        boolean fail=any?children.stream().allMatch(d->d.status().equals("FAIL")):children.stream().anyMatch(d->d.status().equals("FAIL"));
        boolean inactive=children.stream().allMatch(d->"NOT_APPLICABLE".equals(d.status()));
        boolean confirmation=children.stream().anyMatch(d->"NEEDS_CONFIRMATION".equals(d.status()))&&children.stream().noneMatch(d->"UNDETERMINED".equals(d.status()));
        String status=inactive?"NOT_APPLICABLE":pass?"PASS":fail?"FAIL":confirmation?"NEEDS_CONFIRMATION":"UNDETERMINED";
        List<Gap> gaps=pass&&any?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(children.stream().flatMap(d->d.gaps().stream()).distinct());
        List<Decision> used=pass&&any?cn.bidcheck.compat.Java8Api.toList(children.stream().filter(d->d.status().equals("PASS")).limit(1)):children;
        return new Decision(id,description,status,inactive?"全部子条件已核实不适用，本项无适用义务":pass?(any?"至少一条完整替代路径已得到证明":"全部适用原子条件已得到证明"):fail?"存在有来源的明确偏差；其他未完成条件单独列出":"尚有影响结论的条件未完成核验",union(used),gaps,linked("operator",logic,"children",cn.bidcheck.compat.Java8Api.toList(children.stream().map(Decision::map))));
    }
    Decision leaf(Rule r){
        if(PaymentRequirementProof.applies(r))return PaymentRequirementProof.evaluate(r,this);
        if(CompoundBusinessRequirements.applies(r))return CompoundBusinessRequirements.evaluate(r,this);
        if(LegalRegistrationProof.applies(r))return LegalRegistrationProof.evaluate(r,this);
        if(RequirementBusinessRouting.applies(r))return RequirementBusinessRouting.evaluate(r,this);
        if(SourceRequirementInventory.applies(r))return SourceRequirementInventory.evaluate(r,this);
        if(SourceSignatureRequirements.applies(r))return SourceSignatureRequirements.evaluate(r,this);
        if(PersonnelCredentialProof.applies(r)){cn.bidcheck.verification.engine.PersonnelCredentialProof.SelectedEvaluation selected=PersonnelCredentialProof.evaluateSelected(r,this);if(selected.selection()!=null)personnelSelections.put(selected.decision(),selected.selection());return selected.decision();}
        if(BidLetterResponse.applies(r))return BidLetterResponse.evaluate(r,this);
        if(ConditionalMaterialEvidence.applies(r))return ConditionalMaterialEvidence.evaluate(r,this);
        if(SourceScoringRules.applies(r))return SourceScoringRules.evaluate(r,this);
        List<String> subjectSet=RequirementContextBindings.validatedSubjectSet(r);
        if(!subjectSet.isEmpty()){
            List<Decision> children=new ArrayList<>();
            for(String subject:subjectSet){Map<String,Object> parameters=new LinkedHashMap<>(r.parameters());parameters.remove("requirementSubjectSet");
                Rule member=new Rule(r.id()+":subject:"+identity(subject).substring(0,10),r.description(),r.predicate(),subject,r.field(),r.operator(),r.expected(),r.unit(),parameters,r.sourceQuote(),r.logic(),r.children());
                children.add(evaluate(member));}
            Decision result=combine(r.id(),r.description(),"ALL",children);Map<String,Object> proof=new LinkedHashMap<>(result.proof());proof.put("requiredSubjectRoles",subjectSet);proof.put("subjectSetBasis","EXPLICIT_PERSONNEL_SET");
            return new Decision(result.id(),result.description(),result.status(),result.reason(),result.evidences(),result.gaps(),proof);
        }
        if(r.predicate().equals("NOT_APPLICABLE"))return ConditionalRequirement.verified(r)&&inactiveConditionProven(r)
            ?new Decision(r.id(),r.description(),"NOT_APPLICABLE","原文条件已证明不成立，本分支无额外提交义务",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),linked("inactiveBranchProof",r.parameters().get("inactiveBranchProof")))
            :unknown(r,"REQUIREMENT_APPLICABILITY_UNKNOWN","不适用分支缺少核源证明",cn.bidcheck.compat.Java8Api.listOf());
        if(r.predicate().equals("ENTITY_COUNT"))return EntityCountRequirement.evaluate(r,this);
        if(QualificationProofAssociation.applies(r))return QualificationProofAssociation.evaluate(r,this);
        if(PersonnelCertificateDocumentProof.applies(r))return PersonnelCertificateDocumentProof.evaluate(r,this);
        if(r.predicate().equals("UNSUPPORTED"))return unknown(r,"UNSUPPORTED_RULE","该条件尚未编译为已验证原语："+r.description(),cn.bidcheck.compat.Java8Api.listOf());
        if(r.predicate().equals("PERSON_COUNT"))return PersonnelCount.evaluate(r,this);
        if(!cn.bidcheck.compat.Java8Api.isBlank(SameFieldComparisonTarget.reference(r))&&!EnterpriseNameComparison.applies(r))return CertificateSourceComparison.evaluate(r,this);
        if(RegistrationEmployerProof.applies(r)){cn.bidcheck.verification.engine.RegistrationEmployerProof.SelectedEvaluation evaluated=RegistrationEmployerProof.evaluateSelected(r,this);if(!evaluated.identityFacts().isEmpty())registrationSelections.put(evaluated.decision(),evaluated.identityFacts());return evaluated.decision();}
        if(cn.bidcheck.compat.Java8Api.isBlank(r.subject())&&cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "CERTIFICATE", "VALIDITY", "MONTHS", "RELATION").contains(r.predicate())
                &&!(r.predicate().equals("VALIDITY")&&Boolean.TRUE.equals(r.parameters().get("allApplicableCertificates"))))
            return unknown(r,"SUBJECT_REQUIRED","本条件适用的具体主体或岗位尚未明确，不能把所有人员和单位材料混在一起比较",cn.bidcheck.compat.Java8Api.listOf());
        if(DomainBindings.role(r.subject())&&!r.predicate().equals("PERSON_ROLE"))return roleLeaf(r);
        List<Fact> matched=match(r);
        switch (r.predicate()) {case "MATERIAL":case "DECLARATION":case "SECTION":return presence(r,matched);case "PERSON_ROLE":return person(r,matched);case "CERTIFICATE":return certificate(r,matched);case "VALIDITY":return validity(r,matched);case "MONTHS":return months(r,matched);case "RELATION":return relation(r,matched);case "NUMBER":return number(r,matched);case "SAME_FIELD":return sameField(r,matched);case "PRICE_RECONCILE":return priceReconcile(r,matched);case "DATE_RELATION":return dateRelation(r,matched);case "CONTRACT":return contract(r,matched);case "SCORE_COUNT":return score(r);case "TEXT_ANOMALY":return textAnomaly(r);case "QUALITY_ADVISORY":return qualityAdvisory(r);case "QUALITY":return quality(r,matched);case "EXTERNAL":return external(r);case "VISUAL":return visual(r);case "REFERENCE_TIME":return reference(r);case "INPUT":return unknown(r,"MISSING_REQUIRED_INPUT",cn.bidcheck.compat.Java8Api.isBlank(r.expected())?r.description():r.expected(),cn.bidcheck.compat.Java8Api.listOf());default:return unknown(r,"UNSUPPORTED_RULE","该条件尚未编译为已验证原语："+r.description(),cn.bidcheck.compat.Java8Api.listOf());}
    }
    private boolean inactiveConditionProven(Rule rule){
        Map<?,?> proof=(Map<?,?>)rule.parameters().get("inactiveBranchProof");
        Decision condition=decisions.get(Objects.toString(proof.get("conditionId"),""));
        return condition!=null&&condition.status().equals("FAIL")&&!condition.evidences().isEmpty();
    }
    private Decision roleLeaf(Rule r){List<String> subjects=resolvedSubjects(r);List<Fact> links=DomainBindings.roleLinks(r.subject(),facts,corpus);
        if(subjects.isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","尚未由当前项目原文把角色对应到具体公司或人员，不能使用角色字面值替代主体",links);
        boolean allMembers=DomainBindings.memberSelector(r.subject());
        List<Map<String,Object>> memberSet=allMembers?DomainBindings.completeMemberSet(r.subject(),links,subjects,corpus):cn.bidcheck.compat.Java8Api.listOf();
        if(allMembers&&memberSet.isEmpty())return unknown(r,"SUBJECT_SET_INCOMPLETE","该条件要求覆盖全部成员，须有当前协议中逐名完整列举及逐成员原文关系；已召回的部分角色关系不能代表成员全集",links);
        if(EnterpriseSubjectBindings.bidder(r.subject())&&(subjects.size()>1||EnterpriseSubjectBindings.consortiumDeclared(corpus.all))){
            memberSet=EnterpriseSubjectBindings.completeSet(subjects,corpus);
            if(memberSet.isEmpty())return unknown(r,"SUBJECT_SET_INCOMPLETE","出现多个投标主体，尚未由本次投标函或协议证明完整联合体组成，不能合并不同公司的证照",links);
        }
        List<Decision> children=new ArrayList<>();for(String subject:subjects){Map<String,Object> concreteParameters=new LinkedHashMap<>(r.parameters());if(PersonnelRoles.personRole(r.subject()))concreteParameters.put("personnelRoleSelector",r.subject());Rule concrete=new Rule(r.id()+":"+identity(subject).substring(0,12),r.description(),r.predicate(),subject,r.field(),r.operator(),r.expected(),r.unit(),concreteParameters,r.sourceQuote(),r.logic(),cn.bidcheck.compat.Java8Api.listOf());Decision child=r.predicate().equals("VISUAL")?visual(concrete,r.id()):leaf(concrete);
            if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(child.status())){List<Fact> selected=match(concrete);SubjectIdentity.Problem issue=identities.issue(r,selected);Map<String,Object> childProof=new LinkedHashMap<>(child.proof());childProof.put("identityBindings",identities.bindings(selected));
                child=new Decision(child.id(),child.description(),issue==null?child.status():"UNDETERMINED",issue==null?child.reason():issue.reason(),child.evidences(),issue==null?child.gaps():cn.bidcheck.compat.Java8Api.listOf(gap(issue.code(),issue.reason())),childProof);}
            children.add(child);}
        Decision combined=combine(r.id(),r.description(),"ALL",children);Map<String,Object> proof=new LinkedHashMap<>(combined.proof());
        proof.put("role",r.subject());proof.put("normalizedRole",PersonnelRoles.canonical(r.subject()));proof.put("subjects",subjects);proof.put("roleBindings",cn.bidcheck.compat.Java8Api.toList(links.stream().map(f->linked("factId",f.id(),"subject",DomainBindings.roleTarget(f,r.subject()),"originalRole",DomainBindings.sourceRole(f),"normalizedRole",PersonnelRoles.canonical(DomainBindings.sourceRole(f)),"sourceIds",f.sourceIds()))));
        if(!memberSet.isEmpty())proof.put("completeMemberSet",memberSet);
        List<Evidence> evidence=new ArrayList<>(combined.evidences());links.stream().flatMap(f->f.sourceIds().stream()).map(corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(evidence::add);
        return new Decision(r.id(),r.description(),combined.status(),combined.reason(),cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),combined.gaps(),proof);
    }
    List<Fact> match(Rule r){Set<String> kinds;
switch (r.predicate()) {case "VALIDITY":kinds = cn.bidcheck.compat.Java8Api.setOf("DATE_RANGE", "CERTIFICATE"); break;case "SAME_FIELD":kinds = cn.bidcheck.compat.Java8Api.setOf("FIELD", "NUMBER", "CERTIFICATE"); break;case "PRICE_RECONCILE":kinds = cn.bidcheck.compat.Java8Api.setOf("NUMBER", "FIELD"); break;case "DATE_RELATION":kinds = cn.bidcheck.compat.Java8Api.setOf("FIELD"); break;default:kinds = cn.bidcheck.compat.Java8Api.setOf(r.predicate()); break;}
        Set<String> subjects=new LinkedHashSet<>(resolvedSubjects(r));if(r.predicate().equals("PERSON_ROLE")&&!cn.bidcheck.compat.Java8Api.isBlank(r.subject()))subjects.add(r.subject());
        if(!cn.bidcheck.compat.Java8Api.isBlank(r.subject())&&subjects.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        Set<String> fields=new LinkedHashSet<>();fields.add(r.field());
        if(r.predicate().equals("PRICE_RECONCILE"))fields.addAll(strings(r.parameters().get("componentFields")));
        if(r.predicate().equals("DATE_RELATION")){fields.add(str(r.parameters(),"leftRole"));fields.add(str(r.parameters(),"rightRole"));}
        fields.remove("");
        // PERSON_ROLE already binds role -> person. Native readers label its value 姓名,
        // while compiled/legacy rules may say role; that label is not an extra business constraint.
        return cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->kinds.contains(f.kind())).filter(f->r.predicate().equals("PERSON_ROLE")&&DomainBindings.roleMatches(r.subject(),f.subject(),f,corpus)||subjects.isEmpty()||subjects.contains(f.subject())||subjects.contains(str(f.attributes(),"person"))||subjects.stream().anyMatch(person->ConfirmedSubjectBindings.matches(person,f,facts,corpus))).filter(f->fields.isEmpty()||fields.contains(f.field())||cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "CERTIFICATE", "MONTHS", "CONTRACT", "SECTION", "MATERIAL", "DECLARATION", "VALIDITY", "RELATION", "QUALITY").contains(r.predicate())));
    }
    List<String> resolvedSubjects(Rule r){return DomainBindings.subjects(r.subject(),facts,corpus);}
    Decision presence(Rule r,List<Fact> matched){
        if(PersonnelWorkloadCommitmentProof.applies(r)){
            Decision response=PersonnelWorkloadCommitmentProof.evaluate(r,matched,this);
            // Only this invocation's revalidated source/issuer/obligation proof can close a shared review.
            // SubjectIdentity adds its own proof after leaf evaluation, so match the private receipt's
            // evidence and fact set rather than accepting an arbitrary persisted proof map.
            if(response.status().equals("PASS")){
                Map<String,Object> proof=new LinkedHashMap<>(response.proof());
                proof.put("sourceReviewClosure",linked("basis","REVERIFIED_CURRENT_PERSONNEL_COMMITMENT","scope","WRITTEN_WORKLOAD_UNDERTAKING_ONLY","exhaustiveCandidateReview",false));
                response=new Decision(response.id(),response.description(),response.status(),response.reason(),response.evidences(),response.gaps(),proof);
                sourceReviewReceipts.put(r,response);
            }
            return response;
        }
        if(CurrentTaxpayerProof.applies(r))return CurrentTaxpayerProof.evaluate(r,matched,this);if(CertificateQueryProof.applies(r))return CertificateQueryProof.evaluate(r,matched,this);if(Boolean.TRUE.equals(r.parameters().get("templateFormAudit")))return TenderFormAudit.evaluate(r,matched,this);if(DurationResponseProof.applies(r)){Decision response=DurationResponseProof.evaluate(r,this);if(response!=null)return response;}List<Fact> accepted=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")).filter(f->f.sourceIds().stream().anyMatch(id->!corpus.byId.get(id).index())).filter(f->cn.bidcheck.compat.Java8Api.isBlank(r.expected())||contains(f.rawValue(),r.expected())||contains(f.value(),r.expected())||r.expected().equals(f.field())));
        if(accepted.isEmpty())return unknown(r,"CONTENT_EVIDENCE_REQUIRED","当前证据尚未建立该项正文/承诺的覆盖，检索未命中不等于原文件缺失",matched);
        if(r.predicate().equals("DECLARATION")&&!cn.bidcheck.compat.Java8Api.isBlank(r.subject())&&accepted.stream().noneMatch(f->f.sourceIds().stream().anyMatch(id->contains(corpus.byId.get(id).evidence().quote(),r.subject()))))return unknown(r,"SUBJECT_RELATION_UNPROVEN","声明文本已发现，但要求适用主体未建立",accepted);
        return pass(r,r.predicate().equals("SECTION")?"已发现该章节的实质正文，目录未作为正文证明":"已发现与本条件对应的材料正文/声明；本结论仅覆盖提交或文本义务",accepted,cn.bidcheck.compat.Java8Api.mapOf("proofLevel", "TEXT_CONTENT"));
    }
    Decision person(Rule r,List<Fact> matched){List<Fact> current=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")&&DomainBindings.currentRoleSource(f,corpus.byId)));if(current.isEmpty())return unknown(r,"CURRENT_APPOINTMENT_REQUIRED","尚未建立本次拟派岗位与人员的对应关系",matched);
        String employer=str(r.parameters(),"employer");if(!cn.bidcheck.compat.Java8Api.isBlank(employer)){List<String> employers=DomainBindings.subjects(employer,facts,corpus);current=cn.bidcheck.compat.Java8Api.toList(current.stream().filter(f->employers.contains(str(f.attributes(),"employer"))));if(current.isEmpty())return unknown(r,"APPOINTMENT_EMPLOYER_REQUIRED","岗位人员尚未绑定要求的具体成员单位；联合体职责不能替代聘用关系",matched);}
        Map<String,Set<String>> assignments=new LinkedHashMap<>();for(Fact f:current)assignments.computeIfAbsent(str(f.attributes(),"employer")+"|"+str(f.attributes(),"lot"),key->new LinkedHashSet<>()).add(f.value());
        if(assignments.values().stream().anyMatch(names->names.size()>1)&&!Boolean.TRUE.equals(r.parameters().get("multipleAllowed")))return unknown(r,"CONFLICTING_EVIDENCE","同成员、同标段岗位仍出现不同人员，不能仅按姓名或检索排序选择",current);
        List<Fact> employmentProof=new ArrayList<>();if(DomainBindings.employment(r.description()))for(Fact appointment:current){String company=str(appointment.attributes(),"employer");
            List<Fact> links=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.kind().equals("RELATION")&&f.scope().equals("CURRENT_PROJECT")&&DomainBindings.employment(f.field())&&company.equals(str(f.attributes(),"object"))&&identities.samePerson(appointment,f)));
            if(cn.bidcheck.compat.Java8Api.isBlank(company)||links.isEmpty())return unknown(r,"EMPLOYMENT_RELATION_REQUIRED","岗位任职或联合体成员职责尚不能证明该人员与要求单位的聘用关系",current);employmentProof.addAll(links);}
        List<Fact> used=new ArrayList<>(current);used.addAll(employmentProof);return pass(r,"本次岗位与人员已由任职原文建立对应；不同成员单位分别保留",used,linked("personsByMemberAndLot",assignments,"requestedRole",r.subject(),"normalizedRole",PersonnelRoles.canonical(r.subject()),"roleBindings",cn.bidcheck.compat.Java8Api.toList(current.stream().map(f->linked("factId",f.id(),"originalRole",f.subject(),"normalizedRole",PersonnelRoles.canonical(f.subject()),"sourceIds",f.sourceIds())))));}
    Decision certificate(Rule r,List<Fact> matched){if(matched.isEmpty())return unknown(r,"CERTIFICATE_EVIDENCE_REQUIRED","尚未建立适用主体的证照事实",cn.bidcheck.compat.Java8Api.listOf());
        String employer=str(r.parameters(),"employer");if(!cn.bidcheck.compat.Java8Api.isBlank(employer)&&DomainBindings.subjects(employer,facts,corpus).isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","证照要求的所属单位角色尚未解析，不能据此判断聘用单位不符",matched);
        if(cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"category"))&&matched.stream().noneMatch(f->certificateSelected(r,f)))return unknown(r,"CERTIFICATE_CATEGORY_REQUIRED","尚未将候选证照类别对应到本条款原文，不能因只召回一类证件便认定适用",matched);
        List<Fact> identified=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(DomainBindings::certificateIdentity).filter(f->certificateSelected(r,f)));
        if(identified.isEmpty())return unknown(r,"CERTIFICATE_IDENTITY_REQUIRED","尚未同时核到具体持有人、证书类别及对应证号，不能借另一证件字段补齐",matched);
        if(cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"category"))&&identified.stream().map(f->str(f.attributes(),"category")).distinct().count()>1)return unknown(r,"CERTIFICATE_CATEGORY_REQUIRED","该主体有多类证件，须明确要求适用的证书类别",identified);
        List<Fact> complete=new ArrayList<>(),mismatch=new ArrayList<>();for(Fact f:identified){boolean absent=false,bad=false;for(String key:cn.bidcheck.compat.Java8Api.listOf("category", "grade", "profession", "employer")){String needed=str(r.parameters(),key),value=str(f.attributes(),key);if(cn.bidcheck.compat.Java8Api.isBlank(needed))continue;if(cn.bidcheck.compat.Java8Api.isBlank(value))absent=true;else if(key.equals("grade")){Boolean gradeMeets=CredentialGrades.meets(str(r.parameters(),"category"),value,needed,str(r.parameters(),"gradeOperator"),r.sourceQuote());if(gradeMeets==null)absent=true;else if(!gradeMeets)bad=true;}else if(key.equals("employer")?!DomainBindings.subjects(needed,facts,corpus).contains(value):key.equals("category")?!DomainBindings.sameCertificateCategory(value,needed):!value.equals(needed))bad=true;}if(!absent&&!bad)complete.add(f);else if(!absent) mismatch.add(f);}
        Set<String> conflicting=SourceLocalCertificateClosure.conflictingFactIds(identified);
        complete.removeIf(f->conflicting.contains(f.id()));
        if(!complete.isEmpty())return pass(r,"证照类别/等级/专业与该条件相符；有效时点另行核验",complete,cn.bidcheck.compat.Java8Api.mapOf("verifiedFields", r.parameters().keySet()));
        if(!conflicting.isEmpty())return unknown(r,"CERTIFICATE_VERSION_REQUIRED","同一主体、类别和证号的等级、专业或单位存在不同记录，适用版本尚未证明，不能择优通过",identified,cn.bidcheck.compat.Java8Api.mapOf("conflictingFactIds", conflicting));
        if(!mismatch.isEmpty()&&identified.size()==mismatch.size()&&identified.size()==matched.size())return fail(r,"所引适用证号的明确字段与要求不符",mismatch,cn.bidcheck.compat.Java8Api.mapOf("required", r.parameters()));
        return unknown(r,"CERTIFICATE_FIELD_REQUIRED","证照主体已定位，所需类别、等级或专业字段尚未全部可核",matched);}
    private boolean certificateSelected(Rule r,Fact f){String category=str(f.attributes(),"category");if(cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"category"))&&(!DomainBindings.known(category)||!contains(r.sourceQuote(),category)))return false;for(String key:cn.bidcheck.compat.Java8Api.listOf("category", "number")){String expected=str(r.parameters(),key);if(!cn.bidcheck.compat.Java8Api.isBlank(expected)&&!(key.equals("category")?DomainBindings.sameCertificateCategory(str(f.attributes(),key),expected):expected.equals(str(f.attributes(),key))))return false;}return true;}
    Decision validity(Rule r,List<Fact> matched){if(Boolean.TRUE.equals(r.parameters().get("allApplicableCertificates")))return validityCoverage(r,matched);LocalDate ref=referenceDate(r);if(ref==null)return unknown(r,"REFERENCE_TIME_UNCONFIRMED","尚未取得与条款要求事件对应且已核对招标原文的参考日期，不用当前日期或另一事件日期代替",matched);
        if(cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"category"))&&matched.stream().noneMatch(f->certificateSelected(r,f)))return unknown(r,"CERTIFICATE_CATEGORY_REQUIRED","有效期所属的证照类别尚未对应到本条款原文，不能使用另一类证件的日期",matched);
        String employer=str(r.parameters(),"employer");List<String> employers=DomainBindings.subjects(employer,facts,corpus);
        if(!cn.bidcheck.compat.Java8Api.isBlank(employer)){if(employers.isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","有效期要求的证照单位角色尚未解析为具体单位",matched);matched=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->employers.contains(str(f.attributes(),"employer"))));if(matched.isEmpty())return unknown(r,"CERTIFICATE_EMPLOYER_REQUIRED","尚未取得归属于要求单位的同一证照，不用其他成员单位的证件有效期替代",cn.bidcheck.compat.Java8Api.listOf());}
        String requiredField=str(r.parameters(),"validityField");boolean inferredField=false;
        if(cn.bidcheck.compat.Java8Api.isBlank(requiredField)&&DomainBindings.validityField(r.field())&&r.sourceQuote().contains(r.field()))requiredField=r.field();
        if(cn.bidcheck.compat.Java8Api.isBlank(requiredField)){
            List<Fact> candidates=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(DomainBindings::certificateIdentity).filter(f->certificateSelected(r,f)));
            Set<String> certificates=new LinkedHashSet<>(),labels=new LinkedHashSet<>(),declared=new LinkedHashSet<>();
            for(Fact f:candidates){certificates.add(f.subject()+"|"+str(f.attributes(),"category")+"|"+str(f.attributes(),"number"));String field=str(f.attributes(),f.kind().equals("CERTIFICATE")?"validityField":"field");if(DomainBindings.validityField(field))declared.add(field);labels.addAll(DomainBindings.validityFields(f,corpus.byId,maps(context.get("_validatedLayoutCells"))));}
            if(certificates.size()==1&&labels.size()==1&&declared.equals(labels)){requiredField=labels.iterator().next();inferredField=true;}
        }
        if(cn.bidcheck.compat.Java8Api.isBlank(requiredField))return unknown(r,"VALIDITY_FIELD_REQUIRED","适用的有效期字段未明确，不能混用注册期、使用期或其他证件日期",matched);
        if(matched.isEmpty())return unknown(r,"CERTIFICATE_EVIDENCE_REQUIRED","有效期字段尚未定位",cn.bidcheck.compat.Java8Api.listOf());List<Fact> complete=new ArrayList<>(),invalid=new ArrayList<>();
        List<Map<String,Object>> dateProofs=new ArrayList<>();List<CertificateValidityDates.Result> dateGaps=new ArrayList<>();
        Set<String> keys=new HashSet<>(),ranges=new HashSet<>();for(Fact f:matched){String field=str(f.attributes(),f.kind().equals("CERTIFICATE")?"validityField":"field");if(!field.equals(requiredField)||!DomainBindings.certificateIdentity(f)||!certificateSelected(r,f))continue;boolean compatible=true;for(String key:cn.bidcheck.compat.Java8Api.listOf("category", "profession", "number")){String expected=str(r.parameters(),key);if(!cn.bidcheck.compat.Java8Api.isBlank(expected)&&!(key.equals("category")?DomainBindings.sameCertificateCategory(str(f.attributes(),key),expected):expected.equals(str(f.attributes(),key))))compatible=false;}if(!compatible)continue;
            CertificateValidityDates.Result dates=CertificateValidityDates.fact(f,corpus,field,ref,false,maps(context.get("_validatedLayoutCells")));dateProofs.add(linked("factId",f.id(),"dateSemantics",dates.proof()));
            if(!dates.complete()){dateGaps.add(dates);continue;}complete.add(f);keys.add(DomainBindings.certificateKey(f));ranges.add(normalizedDateRange(dates));if(!Boolean.TRUE.equals(dates.valid()))invalid.add(f);}
        if(complete.isEmpty()){CertificateValidityDates.Result gap=dateGaps.isEmpty()?null:dateGaps.get(0);return unknown(r,gap==null?"VALIDITY_RANGE_REQUIRED":gap.code(),gap==null?"该有效期字段尚未形成可核验的日期记录":gap.reason(),matched,cn.bidcheck.compat.Java8Api.mapOf("dateSemantics", dateProofs));}
        if(keys.size()>1)return unknown(r,"CERTIFICATE_SELECTION_REQUIRED","已定位多个持有人、类别或证号，须明确本条件适用的具体证件，不能混合判断有效或过期",complete);
        if(ranges.size()>1)return unknown(r,"CERTIFICATE_VERSION_REQUIRED","同一证号的同一有效期字段存在不同范围，适用版本或延期关系尚未证明",complete,linked("normalizedDateRanges",ranges,"dateSemantics",dateProofs));
        if(!invalid.isEmpty()&&invalid.size()<complete.size())return unknown(r,"CONFLICTING_EVIDENCE","同主体同字段存在有效与过期的不同证书/版本；替代关系或适用版本未证明，不能由任一过期证书否决",complete);
        Map<String,Object> proof=linked("referenceDate",ref.toString(),"referenceEvent",referenceEvent(r),"referenceDateEvidence",referenceEvidence(r),"certificateKey",keys.iterator().next(),"validityField",requiredField,"validityFieldResolution",inferredField?"UNIQUE_CERTIFICATE_SOURCE_FIELD":"REQUIREMENT_EXPLICIT","validityFieldReason",inferredField?"由唯一证件的唯一明确有效期字段确定":"招标条件已明确有效期字段","dateSemantics",dateProofs);
        if(!invalid.isEmpty())return fail(r,"已确认的招标事件日期不在该持有人、类别、证号的适用有效期内",invalid,proof);
        return pass(r,"该持有人、类别、证号的有效期字段覆盖已确认招标事件日期",complete,proof);}
    /** Compare the already validated date contract, never OCR/model spelling.
     * Expiry-only dates remain expiry-only; issuance must not become a validity start. */
    private static String normalizedDateRange(CertificateValidityDates.Result dates){
        return str(dates.proof(),"semantics")+"|"+str(dates.proof(),"start")+"|"+str(dates.proof(),"end");
    }
    /** Built-in certificate-expiry checkpoint: inspect each source-identified certificate
     * independently.  It may prove an observed expiry, but it passes only with a separately
     * established complete certificate set; a partial retrieval can never establish coverage. */
    private Decision validityCoverage(Rule r,List<Fact> matched){LocalDate ref=referenceDate(r);if(ref==null)return unknown(r,"REFERENCE_TIME_UNCONFIRMED","尚未取得与检查项参考事件对应且已核对招标原文的日期",matched);
        List<Fact> complete=new ArrayList<>(),invalid=new ArrayList<>();Map<String,Set<String>> ranges=new LinkedHashMap<>();
        List<Map<String,Object>> dateProofs=new ArrayList<>();List<CertificateValidityDates.Result> dateGaps=new ArrayList<>();
        for(Fact fact:matched){if(!"CURRENT_PROJECT".equals(fact.scope())||!DomainBindings.certificateIdentity(fact))continue;String field=str(fact.attributes(),fact.kind().equals("CERTIFICATE")?"validityField":"field");
            if(!DomainBindings.validityField(field))continue;
            CertificateValidityDates.Result dates=CertificateValidityDates.fact(fact,corpus,field,ref,false,maps(context.get("_validatedLayoutCells")));dateProofs.add(linked("factId",fact.id(),"dateSemantics",dates.proof()));
            if(!dates.complete()){dateGaps.add(dates);continue;}complete.add(fact);String key=DomainBindings.certificateKey(fact)+"|"+field;
            ranges.computeIfAbsent(key,ignored->new LinkedHashSet<>()).add(normalizedDateRange(dates));if(!Boolean.TRUE.equals(dates.valid()))invalid.add(fact);}
        Map<String,Object> proof=linked("referenceDate",ref.toString(),"referenceEvent",referenceEvent(r),"referenceDateEvidence",referenceEvidence(r),
                "observedCertificateCount",ranges.size(),"certificateSetComplete",Boolean.TRUE.equals(context.get("certificateSetComplete")),"decisionBasis","CHECKPOINT_METADATA_AND_SOURCE_GROUNDED_CERTIFICATE_RANGES","dateSemantics",dateProofs);
        if(complete.isEmpty())return unknown(r,"CERTIFICATE_VALIDITY_EVIDENCE_REQUIRED","尚未取得可绑定持有人、类别、证号和完整有效期范围的证照记录",matched,proof);
        List<String> conflicts=cn.bidcheck.compat.Java8Api.toList(ranges.entrySet().stream().filter(entry->entry.getValue().size()>1).map(Map.Entry::getKey));
        if(!conflicts.isEmpty()){proof.put("conflictingCertificateKeys",conflicts);proof.put("normalizedDateRanges",ranges);return unknown(r,"CERTIFICATE_VERSION_REQUIRED","同一证号的同一有效期字段存在不同范围，须先确认适用版本",complete,proof);}
        if(!invalid.isEmpty())return fail(r,"发现已明确归属的证照在核验日期已过期或尚未生效",invalid,proof);
        if(!dateGaps.isEmpty())return unknown(r,dateGaps.get(0).code(),dateGaps.get(0).reason(),matched,proof);
        if(!Boolean.TRUE.equals(context.get("certificateSetComplete")))return unknown(r,"CERTIFICATE_SET_COVERAGE_REQUIRED","已识别证照的有效期均覆盖核验日期，但尚未证明当前投标适用证照清单已经完整闭合",complete,proof);
        return pass(r,"当前投标适用证照清单已闭合，逐证有效期均覆盖核验日期",complete,proof);}
    Decision months(Rule r,List<Fact> matched){int required=RunSession.number(r.parameters().get("count"),0);if(required<1||required>120)return unknown(r,"REQUIREMENT_AMBIGUOUS","连续月份数量尚未明确",matched);
        String employer=str(r.parameters(),"employer");PersonnelEmployerBindings.Binding binding=PersonnelEmployerBindings.resolve(r,facts,corpus,maps(context.get("_validatedLayoutCells")));List<String> employers=binding.employers();
        if(PersonnelEmployerBindings.deictic(employer)&&!binding.resolved())return unknown(r,"SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED","本单位尚未由本人员任职或联合体分工唯一确定，需确认适用成员单位",matched,binding.audit());
        if(!cn.bidcheck.compat.Java8Api.isBlank(employer)&&employers.isEmpty())return unknown(r,"SOCIAL_EMPLOYER_REQUIRED","社保要求的单位角色尚未解析为具体单位",matched,binding.audit());
        List<Fact> applicable=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")).filter(f->cn.bidcheck.compat.Java8Api.isBlank(employer)||employers.contains(str(f.attributes(),"employer"))));
        if(applicable.isEmpty()){Map<String,Object> proof=new LinkedHashMap<>(binding.audit());proof.put("matchedMonthFactIds",cn.bidcheck.compat.Java8Api.toList(matched.stream().map(Fact::id)));proof.put("observedEmployers",cn.bidcheck.compat.Java8Api.toList(matched.stream().map(f->linked("factId",f.id(),"subject",f.subject(),"employer",str(f.attributes(),"employer"),"scope",f.scope(),"sourceIds",f.sourceIds()))));proof.put("requiredEvidenceSources",cn.bidcheck.compat.Java8Api.toList(SocialInsuranceEvidenceRecords.targetedSources(cn.bidcheck.compat.Java8Api.listOf(r),facts,corpus).stream().map(s->linked("sourceId",s.id(),"page",s.evidence().page(),"documentId",s.evidence().documentId()))));return unknown(r,"SOCIAL_EMPLOYER_REQUIRED","尚未取得适用当前人员和要求缴费单位的月份证明，其他成员单位或历史社保不能替代",matched,proof);}
        LocalDate ref=referenceDate(r);if(Boolean.TRUE.equals(r.parameters().get("requiresReferenceDate"))&&ref==null)return unknown(r,"REFERENCE_TIME_UNCONFIRMED","社保适用时间窗的截止时点尚未确认",matched);
        YearMonth earliest=null;try{if(!cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"earliestMonth")))earliest=YearMonth.parse(str(r.parameters(),"earliestMonth"));}catch(Exception ignored){return unknown(r,"REQUIREMENT_AMBIGUOUS","社保窗口起点无法解释",matched);}
        for(Fact f:applicable){TreeSet<YearMonth> months=new TreeSet<>();for(String value:strings(f.attributes().get("months")))try{YearMonth m=YearMonth.parse(value);if((earliest==null||!m.isBefore(earliest))&&(ref==null||!m.isAfter(YearMonth.from(ref))))months.add(m);}catch(Exception ignored){}
            int count=0,best=0;YearMonth previous=null;for(YearMonth m:months){count=previous!=null&&previous.plusMonths(1).equals(m)?count+1:1;best=Math.max(best,count);previous=m;}if(best>=required){Map<String,Object> proof=new LinkedHashMap<>(binding.audit());proof.putAll(cn.bidcheck.compat.Java8Api.mapOf("requiredMonths", required, "longestConsecutive", best, "months", cn.bidcheck.compat.Java8Api.toList(months.stream().map(YearMonth::toString))));List<Fact> used=new ArrayList<>(binding.witnesses());used.add(f);return pass(r,"已按本人员适用单位核到允许窗口内的连续月份",used,proof);}}
        return unknown(r,"MONTH_COVERAGE_REQUIRED","现有逐月证据尚未证明要求的连续月份，起止月或抽页表头不证明中间月连续",matched);}
    Decision relation(Rule r,List<Fact> matched){String object=!cn.bidcheck.compat.Java8Api.isBlank(r.expected())?r.expected():str(r.parameters(),"object");List<String> targets=DomainBindings.subjects(object,facts,corpus);boolean employment=DomainBindings.employment(r.field())||DomainBindings.employment(r.description());List<String> path=strings(r.parameters().get("path"));
        if(DomainBindings.role(object)&&targets.isEmpty())return unknown(r,"RELATION_TARGET_UNPROVEN","关系要求的成员角色尚未绑定具体单位，不能用联合体职责替代人员聘用",matched);
        if(!path.isEmpty()){
        if(path.size()>4||cn.bidcheck.compat.Java8Api.isBlank(r.subject())||cn.bidcheck.compat.Java8Api.isBlank(object))return unknown(r,"RELATION_PATH_REQUIRED","关系路径缺少明确起点、终点或超过推导预算",matched);
        if(employment&&path.stream().noneMatch(DomainBindings::employment))return unknown(r,"EMPLOYMENT_RELATION_REQUIRED","要求的是人员聘用关系，当前路径仅有成员或职责关系，不能据此推导聘用",matched);
        Set<String> starts=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf(r.subject()));facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.subject().equals(r.subject())&&f.scope().equals("CURRENT_PROJECT")).map(Fact::value).forEach(starts::add);
        List<List<Fact>> paths=new ArrayList<>();for(String start:starts)for(String target:targets)walk(start,target,path,0,new ArrayList<>(),paths);if(paths.isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","要求的每条原子关系尚未组成同范围完整路径",matched);
        List<Fact> chain=paths.get(0);return pass(r,"从主体到单位及职责的每条关系均有独立原文支持",chain,cn.bidcheck.compat.Java8Api.mapOf("derivation", "ORDERED_RELATION_PATH", "path", path, "edges", cn.bidcheck.compat.Java8Api.toList(chain.stream().map(f->linked("subject",f.subject(),"relation",f.field(),"object",str(f.attributes(),"object"),"sourceIds",f.sourceIds())))));}
        List<Fact> edges=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")).filter(f->cn.bidcheck.compat.Java8Api.isBlank(r.field())||f.field().equals(r.field())).filter(f->!employment||DomainBindings.employment(f.field())).filter(f->cn.bidcheck.compat.Java8Api.isBlank(object)||targets.contains(str(f.attributes(),"object"))));if(edges.isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","尚未建立要求的主体关系及相应时点/项目范围，联合体职责不能替代历史或外部关系",matched);
        return pass(r,"关系的两端及原子关系由同一可追溯原文支持",edges,cn.bidcheck.compat.Java8Api.mapOf("edges", cn.bidcheck.compat.Java8Api.toList(edges.stream().map(f->linked("subject",f.subject(),"relation",f.field(),"object",str(f.attributes(),"object"),"sourceIds",f.sourceIds())))));}
    private void walk(String subject,String target,List<String> path,int at,List<Fact> chain,List<List<Fact>> found){if(found.size()>8)return;if(at==path.size()){if(subject.equals(target))found.add(cn.bidcheck.compat.Java8Api.listCopyOf(chain));return;}for(Fact f:facts)if(f.kind().equals("RELATION")&&f.scope().equals("CURRENT_PROJECT")&&f.subject().equals(subject)&&f.field().equals(path.get(at))&&!chain.contains(f)){chain.add(f);walk(str(f.attributes(),"object"),target,path,at+1,chain,found);chain.remove(chain.size()-1);}}
    Decision number(Rule r,List<Fact> matched){if(DomainBindings.money(r,matched))return price(r,matched,false);if(DurationResponseProof.applies(r)){Decision response=DurationResponseProof.evaluate(r,this);if(response!=null)return response;}BigDecimal expected=decimal(r.expected());if(expected==null)return unknown(r,"REQUIREMENT_AMBIGUOUS","比较门槛未规范为数值",matched);List<Fact> scope=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")).filter(f->cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"amountBasis"))||f.basis().equals(str(r.parameters(),"amountBasis"))).filter(f->cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"taxBasis"))||str(f.attributes(),"taxBasis").equals(str(r.parameters(),"taxBasis"))));
        if(scope.isEmpty())return unknown(r,"NUMERIC_BASIS_REQUIRED","尚未取得同一主体、金额/期限口径及税基的数值",matched);
        BigDecimal threshold=normalize(expected,r.unit());if(threshold==null)return unknown(r,"UNIT_UNSUPPORTED","比较单位尚不支持",scope);
        List<Fact> usable=cn.bidcheck.compat.Java8Api.toList(scope.stream().filter(f->sameUnitFamily(f.unit(),r.unit())&&normalize(decimal(f.value()),f.unit())!=null));if(usable.isEmpty())return unknown(r,"UNIT_MISMATCH","原数值与比较条件单位无法可靠对齐",scope);
        Set<BigDecimal> values=new HashSet<>();usable.forEach(f->values.add(normalize(decimal(f.value()),f.unit()).stripTrailingZeros()));if(values.size()>1)return unknown(r,"CONFLICTING_EVIDENCE","同一业务字段存在不同值，应先处理来源或适用范围冲突",usable);
        BigDecimal value=values.iterator().next();Boolean meets=compare(value,threshold,r.operator());if(meets==null)return unknown(r,"OPERATOR_UNSUPPORTED","数值比较符未规范",usable);
        return meets?pass(r,"同口径数值满足明确比较条件",usable,cn.bidcheck.compat.Java8Api.mapOf("actual", value, "expected", threshold, "operator", r.operator())):fail(r,"同口径数值与明确比较条件存在偏差",usable,cn.bidcheck.compat.Java8Api.mapOf("actual", value, "expected", threshold, "operator", r.operator()));}
    Decision sameField(Rule r,List<Fact> matched){return PriceUniquenessScope.guard(r,sameFieldComparison(r,matched));}
    private Decision sameFieldComparison(Rule r,List<Fact> matched){
        if("creditCode".equals(r.field())&&!Boolean.TRUE.equals(r.parameters().get("companyScopedComparison"))){
            List<Fact> current=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")));
            if(current.isEmpty())return unknown(r,"FIELD_EVIDENCE_REQUIRED","尚未取得本项目单位的统一社会信用代码",matched);
            if(current.stream().anyMatch(f->cn.bidcheck.compat.Java8Api.isBlank(clean(f.subject()))||DomainBindings.role(f.subject())))return unknown(r,"COMPANY_IDENTITY_REQUIRED","统一社会信用代码尚未绑定到具体公司，不能跨联合体成员合并比较",current);
            Map<String,List<Fact>> companies=current.stream().collect(java.util.stream.Collectors.groupingBy(f->clean(f.subject()),LinkedHashMap::new,java.util.stream.Collectors.toList()));
            if(!cn.bidcheck.compat.Java8Api.isBlank(r.expected())&&companies.size()>1)return unknown(r,"COMPANY_IDENTITY_REQUIRED","比较基准需指明适用公司，不能将一个单位代码应用于多个联合体成员",current);
            List<Decision> decisions=new ArrayList<>();
            for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact>> company:companies.entrySet()){
                Map<String,Object> parameters=new LinkedHashMap<>(r.parameters());parameters.put("companyScopedComparison",true);
                Rule child=new Rule(r.id()+"-"+identity(company.getKey()).substring(0,12),r.description()+"："+company.getKey(),r.predicate(),company.getKey(),r.field(),r.operator(),r.expected(),r.unit(),parameters,r.sourceQuote(),r.logic(),r.children());
                decisions.add(sameFieldComparison(child,company.getValue()));
            }
            return combine(r.id(),r.description(),"ALL",decisions);
        }
        if(EnterpriseNameComparison.applies(r))return EnterpriseNameComparison.evaluate(r,this);
        if(!cn.bidcheck.compat.Java8Api.isBlank(SameFieldComparisonTarget.reference(r)))return CertificateSourceComparison.evaluate(r,this);
        if(DomainBindings.money(r,matched))return price(r,matched,true);
        List<Fact> current=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")));
        if(current.isEmpty())return unknown(r,"FIELD_EVIDENCE_REQUIRED","本项目同一字段尚未建立",matched);
        Set<String> values=new LinkedHashSet<>();current.forEach(f->values.add(clean(f.value())));if(!cn.bidcheck.compat.Java8Api.isBlank(r.expected()))values.add(clean(r.expected()));
        SameFieldSourceOccurrences.Result occurrences=SameFieldSourceOccurrences.inspect(current,corpus);
        Map<String,Object> proof=linked("field",r.field(),"sourceOccurrenceAudit",occurrences.audit());
        LOG.info("bidcheck.same_field_occurrences ruleId={} field={} facts={} distinctOccurrences={} unlocatedFacts={} expectedReference={}",
                r.id(),r.field(),current.size(),occurrences.distinctCount(),strings(occurrences.audit().get("unlocatedFactIds")).size(),!cn.bidcheck.compat.Java8Api.isBlank(r.expected()));
        // Distinct values retain the existing conflict decision; duplicate extraction records
        // must neither erase counterevidence nor count as another original occurrence.
        if(values.size()>1){proof.put("values",values);return fail(r,"明确属于本项目的同一字段值不一致",current,proof);}
        if(occurrences.distinctCount()<2&&cn.bidcheck.compat.Java8Api.isBlank(r.expected()))return unknown(r,"COMPARISON_SOURCE_REQUIRED","仅定位到不足两处独立原文字段值；重复提取记录不构成第二处比较来源",current,proof);
        return pass(r,"本项目同一字段的可比来源一致",current,proof);
    }
    private Decision price(Rule r,List<Fact> matched,boolean consistency){
        if(cn.bidcheck.compat.Java8Api.isBlank(r.subject()))return unknown(r,"PRICE_SUBJECT_REQUIRED","报价适用主体未明确，不能把不同公司的报价当成同一报价比较",matched);
        List<Fact> current=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")));if(current.isEmpty())return unknown(r,"PRICE_EVIDENCE_REQUIRED","尚未取得当前项目可比较的报价事实",matched);
        Map<String,String> required=new LinkedHashMap<>();for(String key:cn.bidcheck.compat.Java8Api.listOf("lot", "amountBasis", "taxBasis", "currency")){String value=str(r.parameters(),key);required.put(key,(java.util.Objects.equals((key), "lot") ? DomainBindings.lot(value) : (java.util.Objects.equals((key), "amountBasis") ? DomainBindings.amountBasis(value) : (java.util.Objects.equals((key), "taxBasis") ? DomainBindings.tax(value) : (java.util.Objects.equals((key), "currency") ? DomainBindings.currency(value) : value)))));}
        if(!consistency&&required.values().stream().anyMatch(v->!DomainBindings.known(v)))return unknown(r,"PRICE_REQUIREMENT_DIMENSIONS_REQUIRED","比较门槛尚缺明确标段、金额类别、税口径或币种，不能将不同价格定义混用",current);
        List<Fact> selected=new ArrayList<>();Set<Map<String,String>> dimensions=new LinkedHashSet<>();List<Map<String,Object>> gaps=new ArrayList<>();
        for(Fact f:current){if(InferenceClaims.numericSuperseded(f,current))continue;Map<String,String> actual=DomainBindings.priceDimensions(f);boolean other=required.entrySet().stream().anyMatch(e->DomainBindings.known(e.getValue())&&DomainBindings.known(actual.get(e.getKey()))&&!e.getValue().equals(actual.get(e.getKey())));if(other)continue;
            List<String> missing=cn.bidcheck.compat.Java8Api.toList(actual.entrySet().stream().filter(e->!DomainBindings.known(e.getValue())||e.getKey().equals("subject")&&DomainBindings.role(e.getValue())).map(Map.Entry::getKey));
            if(!missing.isEmpty()){gaps.add(linked("factId",f.id(),"missing",missing));continue;}selected.add(f);dimensions.add(actual);}
        if(!gaps.isEmpty())return unknown(r,"PRICE_DIMENSIONS_REQUIRED","报价的主体、标段、金额类别、税口径或币种尚未完整对齐，保留待核",current,cn.bidcheck.compat.Java8Api.mapOf("dimensionGaps", gaps));
        if(selected.isEmpty())return unknown(r,"PRICE_SCOPE_REQUIRED","未取得符合要求标段、金额类别、税口径及币种的报价",current);
        if(dimensions.size()!=1)return unknown(r,"PRICE_SCOPE_AMBIGUOUS","来源涉及不同主体、标段或报价口径，应分别核对，不能把不同口径价格判为冲突",selected,cn.bidcheck.compat.Java8Api.mapOf("comparisonContexts", dimensions));
        Set<BigDecimal> values=new LinkedHashSet<>();for(Fact f:selected){BigDecimal value=normalize(decimal(f.value()),f.unit());if(value==null||!sameUnitFamily(f.unit(),"元")||!DomainBindings.unitCurrencyMatches(f.unit(),dimensions.iterator().next().get("currency")))return unknown(r,"PRICE_UNIT_REQUIRED","金额原值、单位或币种尚不能可靠归一，不能直接比较字符串",selected);values.add(value.stripTrailingZeros());}
        Map<String,Object> proof=linked("comparisonContext",dimensions.iterator().next(),"normalizedUnit","BASE_CURRENCY_UNIT","normalizedValues",values);
        if(consistency){if(!cn.bidcheck.compat.Java8Api.isBlank(r.expected())){BigDecimal expected=normalize(decimal(r.expected()),r.unit());if(expected==null||!DomainBindings.unitCurrencyMatches(r.unit(),required.get("currency"))||required.values().stream().anyMatch(v->!DomainBindings.known(v)))return unknown(r,"PRICE_REQUIREMENT_DIMENSIONS_REQUIRED","外部比较值须同时明确单位及完整报价口径",selected,proof);values.add(expected.stripTrailingZeros());}
            if(selected.stream().flatMap(f->f.sourceIds().stream()).distinct().count()<2&&cn.bidcheck.compat.Java8Api.isBlank(r.expected()))return unknown(r,"COMPARISON_SOURCE_REQUIRED","当前仅一处报价来源，尚无第二处同口径值可比较",selected,proof);
            return values.size()==1?pass(r,"同主体、同标段、同金额类别、同税口径及币种的报价归一后一致",selected,proof):fail(r,"完整比较口径一致，单位归一后的报价仍存在差异",selected,proof);}
        if(values.size()!=1)return unknown(r,"CONFLICTING_EVIDENCE","同口径报价存在不同原值，先核对适用版本后再比较门槛",selected,proof);
        BigDecimal threshold=normalize(decimal(r.expected()),r.unit());if(threshold==null||!sameUnitFamily(r.unit(),"元")||!DomainBindings.unitCurrencyMatches(r.unit(),required.get("currency")))return unknown(r,"PRICE_UNIT_REQUIRED","报价门槛单位无法可靠归一",selected,proof);Boolean meets=compare(values.iterator().next(),threshold,r.operator());if(meets==null)return unknown(r,"OPERATOR_UNSUPPORTED","报价比较符未明确",selected,proof);
        proof.put("expected",threshold);proof.put("operator",r.operator());return meets?pass(r,"完整口径一致的报价满足比较门槛",selected,proof):fail(r,"完整口径一致的报价与门槛存在偏差",selected,proof);
    }
    Decision priceReconcile(Rule r,List<Fact> matched){
        String total=str(r.parameters(),"totalField");if(cn.bidcheck.compat.Java8Api.isBlank(total))total=r.field();
        List<String> components=strings(r.parameters().get("componentFields"));
        String calculation=str(r.parameters(),"calculation");if(cn.bidcheck.compat.Java8Api.isBlank(calculation))calculation="TOTAL_EQUALS_COMPONENT_SUM";
        if(cn.bidcheck.compat.Java8Api.isBlank(total)||components.isEmpty()||components.contains(total)||components.stream().distinct().count()!=components.size()
                ||!cn.bidcheck.compat.Java8Api.setOf("TOTAL_EQUALS_COMPONENT_SUM", "AMOUNT_REPRESENTATIONS_EQUAL").contains(calculation)
                ||calculation.equals("AMOUNT_REPRESENTATIONS_EQUAL")&&components.size()!=1)
            return unknown(r,"PRICE_RECONCILE_REQUIREMENT_INVALID","报价勾稽缺少唯一字段、明确计算方式或非空分项字段集",matched);
        Set<String> requiredFields=new LinkedHashSet<>();requiredFields.add(total);requiredFields.addAll(components);
        List<Fact> current=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")&&requiredFields.contains(f.field())));
        if(current.isEmpty())return unknown(r,"PRICE_RECONCILE_EVIDENCE_REQUIRED","尚未取得当前项目的报价勾稽金额",matched);

        String requiredLot=DomainBindings.lot(str(r.parameters(),"lot"));
        String requiredTax=DomainBindings.tax(str(r.parameters(),"taxBasis"));
        String requiredCurrency=DomainBindings.currency(str(r.parameters(),"currency"));
        String requiredTotalLayer=str(r.parameters(),"totalPriceLayer");
        String requiredPriceSheet=str(r.parameters(),"priceSheetIdentity");
        List<Fact> applicable=new ArrayList<>();List<Map<String,Object>> dimensionGaps=new ArrayList<>();
        Set<Map<String,String>> contexts=new LinkedHashSet<>();
        for(Fact f:current){String currency=DomainBindings.currency(str(f.attributes(),"currency"));if(cn.bidcheck.compat.Java8Api.isBlank(currency))currency=currencyFromUnit(f.unit());
            String lot=DomainBindings.lot(str(f.attributes(),"lot")),tax=DomainBindings.tax(str(f.attributes(),"taxBasis"));
            String layer=str(f.attributes(),"priceLayer");if(cn.bidcheck.compat.Java8Api.isBlank(layer)&&f.field().equals(total))layer="TOTAL";
            String priceSheet=priceSheetIdentity(f);
            Map<String,String> actual=linkedString("subject",f.subject(),"lot",lot,"taxBasis",tax,"currency",currency,
                    "priceSheetIdentity",priceSheet);
            boolean other=knownMismatch(requiredLot,lot)||knownMismatch(requiredTax,tax)||knownMismatch(requiredCurrency,currency)
                    ||knownMismatch(requiredPriceSheet,priceSheet)||f.field().equals(total)&&knownMismatch(requiredTotalLayer,layer);
            if(other)continue;
            List<String> missing=new ArrayList<>();if(!DomainBindings.known(f.subject())||DomainBindings.role(f.subject()))missing.add("subject");
            if(!DomainBindings.known(currency))missing.add("currency");if(DomainBindings.known(requiredLot)&&!DomainBindings.known(lot))missing.add("lot");
            if(DomainBindings.known(requiredTax)&&!DomainBindings.known(tax))missing.add("taxBasis");
            if(!DomainBindings.known(priceSheet))missing.add("priceSheetIdentity");
            if(f.field().equals(total)&&DomainBindings.known(requiredTotalLayer)&&!DomainBindings.known(layer))missing.add("totalPriceLayer");
            BigDecimal amount=moneyValue(f);if(amount==null||f.kind().equals("NUMBER")&&(!sameUnitFamily(f.unit(),"元")||!DomainBindings.unitCurrencyMatches(f.unit(),currency)))missing.add("unit");
            if(!missing.isEmpty()){dimensionGaps.add(linked("factId",f.id(),"field",f.field(),"missing",missing,"actual",actual));continue;}
            applicable.add(f);contexts.add(actual);
        }
        if(!dimensionGaps.isEmpty())return unknown(r,"PRICE_RECONCILE_DIMENSIONS_REQUIRED","报价的主体、标段、币种、税口径、可追溯价格表身份或原单位未完整对齐",current,cn.bidcheck.compat.Java8Api.mapOf("dimensionGaps", dimensionGaps));
        if(applicable.isEmpty())return unknown(r,"PRICE_RECONCILE_SCOPE_REQUIRED","未取得符合要求口径的报价勾稽事实",current);
        if(contexts.size()!=1)return unknown(r,"PRICE_RECONCILE_SCOPE_AMBIGUOUS","报价字段属于不同主体、标段、价格表或金额口径，不能混合计算",applicable,cn.bidcheck.compat.Java8Api.mapOf("comparisonContexts", contexts));

        Map<String,List<Fact>> byField=new LinkedHashMap<>();for(Fact f:applicable)byField.computeIfAbsent(f.field(),key->new ArrayList<>()).add(f);
        List<String> missing=cn.bidcheck.compat.Java8Api.toList(requiredFields.stream().filter(field->!byField.containsKey(field)));
        if(!missing.isEmpty())return unknown(r,"PRICE_RECONCILE_FIELDS_REQUIRED","报价勾稽所需字段尚未齐全",applicable,cn.bidcheck.compat.Java8Api.mapOf("missingFields", missing, "requiredFields", requiredFields));
        BigDecimal tolerance=decimal(r.parameters().getOrDefault("tolerance","0"));if(tolerance==null||tolerance.signum()<0)return unknown(r,"PRICE_RECONCILE_TOLERANCE_INVALID","报价勾稽容差不可解释",applicable);
        if(!cn.bidcheck.compat.Java8Api.isBlank(r.unit())){BigDecimal normalizedTolerance=normalize(tolerance,r.unit());if(normalizedTolerance==null)return unknown(r,"PRICE_RECONCILE_TOLERANCE_INVALID","报价勾稽容差单位无法归一",applicable);tolerance=normalizedTolerance;}

        FieldAmount totalAmount=singleAmount(total,byField.get(total));
        if(!totalAmount.complete())return unknown(r,totalAmount.code(),totalAmount.reason(),applicable,totalAmount.proof());
        if(calculation.equals("AMOUNT_REPRESENTATIONS_EQUAL")){
            String otherField=components.get(0);FieldAmount other=singleAmount(otherField,byField.get(otherField));
            if(!other.complete())return unknown(r,other.code(),other.reason(),applicable,other.proof());
            BigDecimal difference=totalAmount.value().subtract(other.value()).abs();Map<String,Object> proof=linked(
                    "comparisonContext",contexts.iterator().next(),"leftField",total,"rightField",otherField,
                    "normalizedUnit","BASE_CURRENCY_UNIT","leftAmount",totalAmount.value(),"rightAmount",other.value(),
                    "difference",difference,"tolerance",tolerance,"calculation",calculation);
            return difference.compareTo(tolerance)<=0
                    ?pass(r,"同一主体、标段和金额口径下，大写与小写金额归一后一致",applicable,proof)
                    :fail(r,"同一主体、标段和金额口径下，大写与小写金额存在差异",applicable,proof);
        }

        Map<String,BigDecimal> componentValues=new LinkedHashMap<>();Map<String,Object> setProof=new LinkedHashMap<>();
        for(String field:components){FieldAmount component=componentAmount(field,byField.get(field));
            if(!component.complete())return unknown(r,component.code(),component.reason(),applicable,component.proof());
            componentValues.put(field,component.value());if(!component.proof().isEmpty())setProof.put(field,component.proof());}
        BigDecimal componentSum=componentValues.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal difference=totalAmount.value().subtract(componentSum).abs();Map<String,Object> proof=linked(
                "comparisonContext",contexts.iterator().next(),"totalField",total,"componentFields",components,
                "normalizedUnit","BASE_CURRENCY_UNIT","total",totalAmount.value(),"componentValues",componentValues,
                "componentSets",setProof,"componentSum",componentSum,"difference",difference,"tolerance",tolerance,
                "calculation",calculation);
        return difference.compareTo(tolerance)<=0
                ?pass(r,"同一主体、标段和金额口径下，总计与闭合分项集合的程序求和结果一致",applicable,proof)
                :fail(r,"同一主体、标段和金额口径下，总计与闭合分项集合的程序求和结果存在偏差",applicable,proof);
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class FieldAmount{
        private final BigDecimal value;
        private final boolean complete;
        private final String code;
        private final String reason;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        private FieldAmount(@com.fasterxml.jackson.annotation.JsonProperty("value") BigDecimal value, @com.fasterxml.jackson.annotation.JsonProperty("complete") boolean complete, @com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.value = value; this.complete = complete; this.code = code; this.reason = reason; this.proof = proof; }
        public BigDecimal value() { return value; }
        public boolean complete() { return complete; }
        public String code() { return code; }
        public String reason() { return reason; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; FieldAmount that = (FieldAmount) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.complete, that.complete) && java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.complete); hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "FieldAmount[" + "value=" + value + ", complete=" + complete + ", code=" + code + ", reason=" + reason + ", proof=" + proof + "]"; }
}
    private FieldAmount singleAmount(String field,List<Fact> facts){
        Set<BigDecimal> values=facts.stream().map(RuleEvaluator::moneyValue).filter(Objects::nonNull)
                .map(BigDecimal::stripTrailingZeros).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if(values.size()!=1)return amountGap("PRICE_RECONCILE_FIELD_CONFLICT","同一口径的报价字段存在多个不同数值，须先确认适用版本",
                linked("field",field,"normalizedValues",values,"factIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().map(Fact::id))));
        return amount(values.iterator().next(),linked("field",field,"factIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().map(Fact::id))));
    }
    private FieldAmount componentAmount(String field,List<Fact> facts){
        // An explicitly printed aggregate is already one source-backed component value.  Raw item
        // rows are different: every row must have a stable identity and the declared set cardinality
        // must close before the program may sum them.
        Set<String> layers=facts.stream().map(f->str(f.attributes(),"priceLayer")).filter(DomainBindings::known)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if(layers.size()>1||field.equals("bid.itemPrices")&&facts.size()>1&&layers.stream().anyMatch(layer->!layer.equals("ITEM")))
            return amountGap("PRICE_RECONCILE_COMPONENT_BASIS_AMBIGUOUS","同一分项字段混入了不同价格层级，不能作为一个集合求和",linked("field",field,"priceLayers",layers));
        if(facts.size()==1){Fact only=facts.get(0);String identity=rowIdentity(only);
            if(cn.bidcheck.compat.Java8Api.isBlank(identity)&&(contains(only.basis(),"合计")||contains(only.rawValue(),"合计")||contains(only.rawValue(),"之和")))
                return amount(moneyValue(only),linked("field",field,"aggregation","SOURCE_AGGREGATE","factIds",cn.bidcheck.compat.Java8Api.listOf(only.id())));
            if(cn.bidcheck.compat.Java8Api.isBlank(identity)&&!field.equals("bid.itemPrices"))return singleAmount(field,facts);
        }
        Map<String,Set<BigDecimal>> rows=new LinkedHashMap<>();Set<String> sets=new LinkedHashSet<>();Set<Integer> declaredSizes=new LinkedHashSet<>();int declaredCount=0;
        for(Fact fact:facts){String row=rowIdentity(fact),set=setIdentity(fact),sheet=priceSheetIdentity(fact);Integer size=declaredSetSize(fact,set);
            if(cn.bidcheck.compat.Java8Api.isBlank(row)||cn.bidcheck.compat.Java8Api.isBlank(set))return amountGap("PRICE_RECONCILE_ROW_IDENTITY_REQUIRED","多条分项金额缺少可去重的项目/表格行身份，不能直接相加",linked("field",field,"factIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().map(Fact::id))));
            if(cn.bidcheck.compat.Java8Api.isBlank(sheet)||!set.equals(sheet)&&!setBoundToPriceSheet(set,sheet))return amountGap("PRICE_RECONCILE_SET_SCOPE_UNPROVEN","分项集合尚未证明属于当前总价所在的同一报价表，不能相加",linked("field",field,"factId",fact.id(),"setIdentity",set,"priceSheetIdentity",sheet));
            BigDecimal value=moneyValue(fact);if(value==null)return amountGap("PRICE_RECONCILE_FIELD_CONFLICT","分项金额无法归一",cn.bidcheck.compat.Java8Api.mapOf("field", field, "factId", fact.id()));
            rows.computeIfAbsent(row,key->new LinkedHashSet<>()).add(value.stripTrailingZeros());sets.add(set);if(size!=null){declaredSizes.add(size);declaredCount++;}
        }
        List<String> conflicts=cn.bidcheck.compat.Java8Api.toList(rows.entrySet().stream().filter(entry->entry.getValue().size()!=1).map(Map.Entry::getKey));
        if(!conflicts.isEmpty())return amountGap("PRICE_RECONCILE_ROW_CONFLICT","同一分项行身份存在不同金额，不能选择其中一个计算",linked("field",field,"conflictingRows",conflicts));
        if(sets.size()!=1)return amountGap("PRICE_RECONCILE_SET_AMBIGUOUS","分项来自不同表格或报价集合，不能混合求和",linked("field",field,"setIdentities",sets));
        int closedSize=closedSetSize(sets.iterator().next(),rows.keySet());
        if(closedSize<0&&declaredCount==facts.size()&&declaredSizes.size()==1)closedSize=declaredSizes.iterator().next();
        if(declaredSizes.size()>1||closedSize!=rows.size())return amountGap("PRICE_RECONCILE_SET_OPEN","已定位分项行，但尚未证明同一报价集合已完整闭合",linked("field",field,"setIdentity",sets.iterator().next(),"observedRows",rows.keySet(),"observedSize",rows.size(),"declaredSizes",declaredSizes,"declaredFactCount",declaredCount,"closedSize",closedSize));
        BigDecimal sum=rows.values().stream().map(Set::iterator).map(Iterator::next).reduce(BigDecimal.ZERO,BigDecimal::add);
        return amount(sum,linked("field",field,"aggregation","CLOSED_ROW_SET_SUM","setIdentity",sets.iterator().next(),"rowIdentities",rows.keySet(),"rowCount",rows.size()));
    }
    private int closedSetSize(String setIdentity,Set<String> rows){for(Map<String,Object> closure:maps(context.get("_priceComponentSetClosures"))){
        if(!setIdentity.equals(str(closure,"setIdentity"))||!"SOURCE_BOUND".equals(str(closure,"status")))continue;
        Set<String> closed=new LinkedHashSet<>(strings(closure.get("rowIdentities")));if(closed.equals(rows))return closed.size();}
        return -1;}
    private boolean setBoundToPriceSheet(String setIdentity,String priceSheetIdentity){for(Map<String,Object> closure:maps(context.get("_priceComponentSetClosures")))
        if("SOURCE_BOUND".equals(str(closure,"status"))&&setIdentity.equals(str(closure,"setIdentity"))
                &&priceSheetIdentity.equals(str(closure,"priceSheetIdentity")))return true;
        return false;}
    private static FieldAmount amount(BigDecimal value,Map<String,Object> proof){return value==null
            ?amountGap("PRICE_RECONCILE_FIELD_CONFLICT","报价金额无法可靠归一",proof)
            :new FieldAmount(value.stripTrailingZeros(),true,"","",proof);}
    private static FieldAmount amountGap(String code,String reason,Map<String,Object> proof){return new FieldAmount(null,false,code,reason,proof);}
    private static String first(Map<String,Object> values,String... keys){for(String key:keys){String value=str(values,key);if(!cn.bidcheck.compat.Java8Api.isBlank(value))return value;}return "";}
    private String priceSheetIdentity(Fact fact){String value=first(fact.attributes(),"priceSheetIdentity","reconciliationScope","quoteVersion");if(!cn.bidcheck.compat.Java8Api.isBlank(value)&&sourceContains(fact,value))return value;
        for(Map<String,Object> record:structureRecords(fact)){String table=str(record,"tableId");if(!cn.bidcheck.compat.Java8Api.isBlank(table))return String.join("|",str(record,"documentId"),str(record,"documentHash"),Objects.toString(record.get("documentVersion"),""),table,str(record,"frameId"));}
        return "";}
    private String rowIdentity(Fact fact){String value=first(fact.attributes(),"itemIdentity","rowIdentity","rowId","itemId");if(!cn.bidcheck.compat.Java8Api.isBlank(value)&&sourceBindsRow(fact,value))return value;
        for(Map<String,Object> record:structureRecords(fact)){String key=str(record,"recordKey");if(!cn.bidcheck.compat.Java8Api.isBlank(key))return key;Object row=record.get("row");if(row!=null&&!cn.bidcheck.compat.Java8Api.isBlank(str(record,"tableId")))return str(record,"tableId")+":row:"+row;}
        return "";}
    private String setIdentity(Fact fact){String value=first(fact.attributes(),"setIdentity","componentSetId","tableId");if(!cn.bidcheck.compat.Java8Api.isBlank(value)&&sourceContains(fact,value))return value;
        for(Map<String,Object> record:structureRecords(fact)){String table=str(record,"tableId");if(!cn.bidcheck.compat.Java8Api.isBlank(table))return String.join("|",str(record,"documentId"),str(record,"documentHash"),Objects.toString(record.get("documentVersion"),""),table,str(record,"frameId"));}
        String row=rowIdentity(fact);java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("^(.+?)(?::row:|#row-?)[0-9]+$").matcher(row);return matcher.matches()?matcher.group(1):"";}
    private boolean sourceContains(Fact fact,String value){return !cn.bidcheck.compat.Java8Api.isBlank(value)&&fact.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull)
            .anyMatch(source->contains(source.evidence().quote(),value));}
    private boolean sourceBindsRow(Fact fact,String row){return fact.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull).anyMatch(source->{String text=source.evidence().quote();
        if(!contains(text,row))return false;String raw=!cn.bidcheck.compat.Java8Api.isBlank(fact.rawValue())?fact.rawValue():fact.value();return near(text,row,raw)||near(text,row,fact.value());});}
    private Integer declaredSetSize(Fact fact,String setIdentity){Integer size=positiveInteger(first(fact.attributes(),"setSize","rowCount","itemCount"));if(size==null||cn.bidcheck.compat.Java8Api.isBlank(setIdentity))return null;
        java.util.regex.Pattern count=java.util.regex.Pattern.compile("(?:共|合计|总计|总共)?\\s*"+size+"\\s*(?:项|行|个)");
        return fact.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull).map(source->source.evidence().quote())
                .filter(text->contains(text,setIdentity)).anyMatch(text->count.matcher(text).find())?size:null;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> structureRecords(Fact fact){Object proof=fact.attributes().get("structureProof");if(!(proof instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.listOf();return maps(((Map<?,?>) (proof)).get("records"));}
    private static Integer positiveInteger(String raw){try{int value=Integer.parseInt(raw);return value>0&&value<=10000?value:null;}catch(Exception ignored){return null;}}
    private static BigDecimal moneyValue(Fact fact){BigDecimal numeric=decimal(fact.value());if(numeric!=null){BigDecimal normalized=normalize(numeric,fact.unit());if(normalized!=null)return normalized;}
        for(String text:cn.bidcheck.compat.Java8Api.listOf(fact.rawValue(), fact.value())){BigDecimal parsed=moneyText(text);if(parsed!=null)return parsed;}return null;}
    private static BigDecimal moneyText(String raw){String text=Objects.toString(raw,"").replaceAll("[\\s,，]","");if(cn.bidcheck.compat.Java8Api.isBlank(text))return null;
        java.util.regex.Matcher arabic=java.util.regex.Pattern.compile("(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)(亿元|万元|元|圆)?").matcher(text);
        if(arabic.find()){BigDecimal value=decimal(arabic.group(1));String unit=Objects.toString(arabic.group(2),"");return normalize(value,unit);}
        return chineseMoney(text);}
    private static BigDecimal chineseMoney(String raw){String text=raw.replace("人民币","").replace("￥","").replace("¥","").replace("整","").replace("正","");
        if(!text.matches(".*[零〇一壹二贰两三叁四肆五伍六陆七柒八捌九玖拾佰仟万亿].*"))return null;
        int boundary=Math.max(text.indexOf('元'),text.indexOf('圆'));String integerText=boundary>=0?text.substring(0,boundary):text.replaceAll("[角分].*$","");
        BigDecimal integer=chineseInteger(integerText);if(integer==null)return null;BigDecimal result=integer;
        String fraction=boundary>=0?text.substring(boundary+1):text.substring(Math.min(text.length(),integerText.length()));
        java.util.regex.Matcher jiao=java.util.regex.Pattern.compile("([零〇一壹二贰两三叁四肆五伍六陆七柒八捌九玖])角").matcher(fraction);if(jiao.find())result=result.add(BigDecimal.valueOf(chineseDigit(jiao.group().charAt(0)),1));
        java.util.regex.Matcher fen=java.util.regex.Pattern.compile("([零〇一壹二贰两三叁四肆五伍六陆七柒八捌九玖])分").matcher(fraction);if(fen.find())result=result.add(BigDecimal.valueOf(chineseDigit(fen.group().charAt(0)),2));
        return result;}
    private static BigDecimal chineseInteger(String value){BigDecimal total=BigDecimal.ZERO,section=BigDecimal.ZERO,number=BigDecimal.ZERO;boolean seen=false;
        for(int index=0;index<value.length();index++){char ch=value.charAt(index);int digit=chineseDigit(ch);if(digit>=0){number=BigDecimal.valueOf(digit);seen=true;continue;}
            long small;
switch (ch) {case '拾':small = 10; break;case '佰':small = 100; break;case '仟':small = 1000; break;default:small = 0; break;}if(small>0){if(number.signum()==0)number=BigDecimal.ONE;section=section.add(number.multiply(BigDecimal.valueOf(small)));number=BigDecimal.ZERO;seen=true;continue;}
            long large;
switch (ch) {case '万':large = 10000; break;case '亿':large = 100000000; break;default:large = 0; break;}if(large>0){section=section.add(number);total=total.add(section.multiply(BigDecimal.valueOf(large)));section=BigDecimal.ZERO;number=BigDecimal.ZERO;seen=true;continue;}
            if("元圆".indexOf(ch)>=0)break;if("金额价款总计大写小写：:()（）".indexOf(ch)>=0)continue;return null;}
        return seen?total.add(section).add(number):null;}
    private static int chineseDigit(char value){switch (value) {case '零':case '〇':return 0;case '一':case '壹':return 1;case '二':case '贰':case '两':return 2;case '三':case '叁':return 3;case '四':case '肆':return 4;case '五':case '伍':return 5;case '六':case '陆':return 6;case '七':case '柒':return 7;case '八':case '捌':return 8;case '九':case '玖':return 9;default:return -1;}}

    Decision dateRelation(Rule r,List<Fact> matched){
        String left=str(r.parameters(),"leftRole"),right=str(r.parameters(),"rightRole");if(cn.bidcheck.compat.Java8Api.isBlank(left))left=r.field();if(cn.bidcheck.compat.Java8Api.isBlank(right))right=r.expected();
        if(cn.bidcheck.compat.Java8Api.isBlank(left)||cn.bidcheck.compat.Java8Api.isBlank(right)||left.equals(right)||compare(BigDecimal.ZERO,BigDecimal.ZERO,r.operator())==null)
            return unknown(r,"DATE_RELATION_REQUIREMENT_INVALID","日期关系缺少两个不同的日期角色或可执行比较符",matched);
        DateSide leftSide=dateSide(r,left,str(r.parameters(),"leftDocumentRole"),matched),rightSide=dateSide(r,right,str(r.parameters(),"rightDocumentRole"),matched);
        if(leftSide.conflict()||rightSide.conflict())return unknown(r,"DATE_RELATION_CONFLICTING","同一日期角色存在多个不同日期，须先确认适用文书或版本",unionFacts(leftSide.facts(),rightSide.facts()),linked("leftDates",leftSide.values(),"rightDates",rightSide.values()));
        if(leftSide.values().size()!=1||rightSide.values().size()!=1)return unknown(r,"DATE_RELATION_EVIDENCE_REQUIRED","尚未取得两个明确日期角色的唯一可比日期",unionFacts(leftSide.facts(),rightSide.facts()),linked("leftRole",left,"leftDates",leftSide.values(),"rightRole",right,"rightDates",rightSide.values(),"leftSource",leftSide.sourceProof(),"rightSource",rightSide.sourceProof()));
        LocalDate leftDate=leftSide.values().iterator().next(),rightDate=rightSide.values().iterator().next();Boolean meets=compare(BigDecimal.valueOf(leftDate.toEpochDay()),BigDecimal.valueOf(rightDate.toEpochDay()),r.operator());
        if(meets==null)return unknown(r,"DATE_RELATION_OPERATOR_UNSUPPORTED","日期比较符未规范",unionFacts(leftSide.facts(),rightSide.facts()));
        long difference=Math.abs(java.time.temporal.ChronoUnit.DAYS.between(leftDate,rightDate));Integer maximum=r.parameters().get("maxDifferenceDays")==null?null:RunSession.number(r.parameters().get("maxDifferenceDays"),-1);
        if(maximum!=null){if(maximum<0)return unknown(r,"DATE_RELATION_MAX_DIFFERENCE_INVALID","允许日期差不可为负数",unionFacts(leftSide.facts(),rightSide.facts()));meets=meets&&difference<=maximum;}
        List<Fact> used=unionFacts(leftSide.facts(),rightSide.facts());Map<String,Object> proof=linked("leftRole",left,"leftDate",leftDate.toString(),"leftDocumentRole",leftSide.documentRole(),"leftSource",leftSide.sourceProof(),"rightRole",right,"rightDate",rightDate.toString(),"rightDocumentRole",rightSide.documentRole(),"rightSource",rightSide.sourceProof(),"operator",r.operator(),"differenceDays",difference,"relationKey",str(r.parameters(),"relationKey"),"calculation","COMPARE_NAMED_DATES");
        return meets?pass(r,"两个明确日期角色的日期关系满足要求",used,proof):fail(r,"两个明确日期角色的日期关系与要求存在偏差",used,proof);
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class DateSide{
        private final String role;
        private final String documentRole;
        private final Set<LocalDate> values;
        private final List<Fact> facts;
        private final Object sourceProof;
        private final boolean conflict;
        @com.fasterxml.jackson.annotation.JsonCreator
        private DateSide(@com.fasterxml.jackson.annotation.JsonProperty("role") String role, @com.fasterxml.jackson.annotation.JsonProperty("documentRole") String documentRole, @com.fasterxml.jackson.annotation.JsonProperty("values") Set<LocalDate> values, @com.fasterxml.jackson.annotation.JsonProperty("facts") List<Fact> facts, @com.fasterxml.jackson.annotation.JsonProperty("sourceProof") Object sourceProof, @com.fasterxml.jackson.annotation.JsonProperty("conflict") boolean conflict) { this.role = role; this.documentRole = documentRole; this.values = values; this.facts = facts; this.sourceProof = sourceProof; this.conflict = conflict; }
        public String role() { return role; }
        public String documentRole() { return documentRole; }
        public Set<LocalDate> values() { return values; }
        public List<Fact> facts() { return facts; }
        public Object sourceProof() { return sourceProof; }
        public boolean conflict() { return conflict; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DateSide that = (DateSide) other; return java.util.Objects.equals(this.role, that.role) && java.util.Objects.equals(this.documentRole, that.documentRole) && java.util.Objects.equals(this.values, that.values) && java.util.Objects.equals(this.facts, that.facts) && java.util.Objects.equals(this.sourceProof, that.sourceProof) && java.util.Objects.equals(this.conflict, that.conflict); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.role); hash = 31 * hash + java.util.Objects.hashCode(this.documentRole); hash = 31 * hash + java.util.Objects.hashCode(this.values); hash = 31 * hash + java.util.Objects.hashCode(this.facts); hash = 31 * hash + java.util.Objects.hashCode(this.sourceProof); hash = 31 * hash + java.util.Objects.hashCode(this.conflict); return hash; }
        @Override public String toString() { return "DateSide[" + "role=" + role + ", documentRole=" + documentRole + ", values=" + values + ", facts=" + facts + ", sourceProof=" + sourceProof + ", conflict=" + conflict + "]"; }
}
    private DateSide dateSide(Rule rule,String role,String requestedDocumentRole,List<Fact> matched){String documentRole=cn.bidcheck.compat.Java8Api.isBlank(requestedDocumentRole)?dateDocumentRole(role):requestedDocumentRole;
        if("TENDER".equals(documentRole)){String event=role.contains("开标")?"OPENING_DATE":role.contains("截止")?"BID_DEADLINE":"";if(cn.bidcheck.compat.Java8Api.isBlank(event))return new DateSide(role,documentRole,cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),false);
            Rule referenceRule=new Rule(rule.id()+":reference:"+event,role,"REFERENCE_TIME","",role,"","","",cn.bidcheck.compat.Java8Api.mapOf("referenceEvent", event),role,"",cn.bidcheck.compat.Java8Api.listOf());LocalDate value=referenceDate(referenceRule);Object proof=referenceEvidence(referenceRule);return new DateSide(role,documentRole,value==null?cn.bidcheck.compat.Java8Api.setOf():cn.bidcheck.compat.Java8Api.setOf(value),cn.bidcheck.compat.Java8Api.listOf(),proof,false);}
        List<Fact> facts=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.scope().equals("CURRENT_PROJECT")&&f.field().equals(role)&&sourceRole(f,"BID")));Set<LocalDate> values=new LinkedHashSet<>();for(Fact fact:facts){LocalDate parsed=dateFact(fact);if(parsed!=null)values.add(parsed);}return new DateSide(role,documentRole,values,facts,cn.bidcheck.compat.Java8Api.toList(facts.stream().map(f->linked("factId",f.id(),"sourceIds",f.sourceIds(),"rawValue",f.rawValue()))),values.size()>1);
    }
    private boolean sourceRole(Fact fact,String role){return fact.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull).allMatch(source->corpus.documents.stream().anyMatch(document->document.id().equals(source.evidence().documentId())&&role.equals(document.role())));}
    private static String dateDocumentRole(String role){return role.contains("开标")||role.contains("投标截止")?"TENDER":"BID";}
    private static LocalDate dateFact(Fact fact){for(Object value:cn.bidcheck.compat.Java8Api.listOf(fact.attributes().get("date"), fact.value(), fact.rawValue())){LocalDate parsed=dateValue(value);if(parsed!=null)return parsed;}return null;}
    private static LocalDate dateValue(Object raw){String value=cn.bidcheck.compat.Java8Api.strip(Objects.toString(raw,""));LocalDate direct=date(value);if(direct!=null)return direct;java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("((?:20)?[0-9]{2})[\u5e74./-]([01]?[0-9])[\u6708./-]([0-3]?[0-9])\u65e5?").matcher(value.replaceAll("\\s+",""));if(!matcher.find())return null;try{int year=Integer.parseInt(matcher.group(1));if(year<100)year+=2000;return LocalDate.of(year,Integer.parseInt(matcher.group(2)),Integer.parseInt(matcher.group(3)));}catch(Exception ignored){return null;}}
    private static List<Fact> unionFacts(List<Fact> left,List<Fact> right){LinkedHashMap<String,Fact> values=new LinkedHashMap<>();left.forEach(f->values.put(f.id(),f));right.forEach(f->values.put(f.id(),f));return cn.bidcheck.compat.Java8Api.listCopyOf(values.values());}
    private static boolean knownMismatch(String required,String actual){return DomainBindings.known(required)&&DomainBindings.known(actual)&&!required.equals(actual);}
    private static String currencyFromUnit(String unit){switch (unit) {case "元":case "万元":case "亿元":case "CNY":case "RMB":return "CNY";case "美元":case "USD":return "USD";case "欧元":case "EUR":return "EUR";case "港元":case "HKD":return "HKD";default:return "";}}
    private static Map<String,String> linkedString(String k1,String v1,String k2,String v2,String k3,String v3,String k4,String v4,String k5,String v5){Map<String,String> result=new LinkedHashMap<>();result.put(k1,v1);result.put(k2,v2);result.put(k3,v3);result.put(k4,v4);result.put(k5,v5);return result;}
    Decision contract(Rule r,List<Fact> matched){List<Fact> eligible=new ArrayList<>();List<Map<String,Object>> checks=new ArrayList<>();for(Fact f:matched){List<String> missing=new ArrayList<>(),deviations=new ArrayList<>();Map<String,Object> a=f.attributes();
        for(String key:cn.bidcheck.compat.Java8Api.listOf("personRole", "amountBasis", "taxBasis")){String required=str(r.parameters(),key);if(!cn.bidcheck.compat.Java8Api.isBlank(required)){String actual=str(a,key);if(cn.bidcheck.compat.Java8Api.isBlank(actual))missing.add(key);else if(!required.equals(actual))deviations.add(key);}}
        BigDecimal threshold=decimal(r.parameters().get("amountMinimum"));if(threshold!=null){BigDecimal amount=normalize(decimal(a.get("amount")),str(a,"unit")),minimum=normalize(threshold,r.unit());if(amount==null||minimum==null||cn.bidcheck.compat.Java8Api.isBlank(str(a,"amountBasis")))missing.add("amount");else if(amount.compareTo(minimum)<0)deviations.add("amount");}
        String since=str(r.parameters(),"sinceDate");if(!cn.bidcheck.compat.Java8Api.isBlank(since)){LocalDate signed=date(a.get("signedDate")),min=date(since);if(signed==null||min==null)missing.add("signedDate");else if(signed.isBefore(min))deviations.add("signedDate");}
        if(!cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"person"))&&!str(a,"person").equals(str(r.parameters(),"person")))missing.add("person");
        if(missing.isEmpty()&&deviations.isEmpty())eligible.add(f);checks.add(linked("factId",f.id(),"missing",missing,"deviations",deviations));}
        if(!eligible.isEmpty())return pass(r,"合同身份及所要求的金额口径、时间或人员条件已逐项对应",eligible,cn.bidcheck.compat.Java8Api.mapOf("contractChecks", checks));
        return unknown(r,"CONTRACT_PROOF_REQUIRED","当前合同候选尚未形成完整条件链；不把总工程造价替代建安费，也不把未召回关键页认定原文件缺失",matched,cn.bidcheck.compat.Java8Api.mapOf("contractChecks", checks));}
    Decision score(Rule r){BigDecimal per=decimal(r.parameters().get("pointsPerItem")),max=decimal(r.parameters().get("maxScore"));if(per==null||max==null||per.signum()<0||max.signum()<0)return unknown(r,"SCORING_FORMULA_REQUIRED","计分单价或上限尚未明确",cn.bidcheck.compat.Java8Api.listOf());
        Set<String> identities=new HashSet<>();List<Fact> counted=new ArrayList<>();List<Gap> unresolved=new ArrayList<>();for(Fact f:facts){if(!f.kind().equals("CONTRACT"))continue;RuleEvaluator local=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(f),corpus,context);List<Decision> checks=cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(local::evaluate));Decision aggregate=combine("criteria","合同计分条件","ALL",checks);if(aggregate.status().equals("PASS")){String key=clean(str(f.attributes(),"contractNumber"));if(cn.bidcheck.compat.Java8Api.isBlank(key))key=clean(str(f.attributes(),"contractName"))+":"+clean(str(f.attributes(),"party"));if(identities.add(key))counted.add(f);}else unresolved.addAll(aggregate.gaps());}
        BigDecimal lower=per.multiply(BigDecimal.valueOf(identities.size())).min(max);boolean reached=lower.compareTo(max)>=0;
        if(!reached&&(!Boolean.TRUE.equals(context.get("contractSetComplete"))||!unresolved.isEmpty()))return unknown(r,"SCORE_COVERAGE_REQUIRED","已计算已证实且去重合同的下限；其余合同及资料集合未核全，不宣称确定总分",counted,cn.bidcheck.compat.Java8Api.mapOf("scoreLower", lower, "scoreUpper", max, "deduplicatedContracts", identities.size(), "decisionBasis", "RULE_CALCULATION"));
        return pass(r,"按已核合同身份去重并应用积分上限",counted,cn.bidcheck.compat.Java8Api.mapOf("score", lower, "deduplicatedContracts", identities.size(), "decisionBasis", "RULE_CALCULATION"));}
    Decision textAnomaly(Rule r){EvidenceCorpus.ProgramScan scan=corpus.textAnomalyScan(r);List<Fact> anomalies=cn.bidcheck.compat.Java8Api.toList(scan.facts().stream().filter(f->!f.field().equals("SCAN_RECEIPT")));Map<String,Object> proof=linked("decisionBasis","PROGRAM_TEXT_SCAN","textScope",str(r.parameters(),"textScope"),"anomalyTypes",scan.covered(),"scannedSourceIds",scan.sourceIds(),"coverageComplete",scan.coverageComplete());
        if(!anomalies.isEmpty())return fail(r,"在当前项目正文中发现已配置规则可确定的文字异常；处理流水线失败未作为投标材料缺陷",anomalies,proof);
        if(!scan.scopeLocated())return unknown(r,"TEXT_SCAN_SCOPE_REQUIRED",scan.reason(),scan.facts(),proof);
        if(!scan.missing().isEmpty())return unknown(r,"TEXT_ANOMALY_DETECTOR_REQUIRED","以下异常类型尚未配置确定性检测能力："+String.join("、",scan.missing()),scan.facts(),proof);
        if(!scan.coverageComplete())return unknown(r,"TEXT_SCAN_COVERAGE_REQUIRED",scan.reason(),scan.facts(),proof);
        return pass(r,"当前项目非目录正文已完成所配置异常类型扫描，未发现确定性文字异常",scan.facts(),proof);}
    Decision qualityAdvisory(Rule r){EvidenceCorpus.ProgramScan scan=corpus.qualityAdvisoryScan(r);List<String> dimensions=strings(r.parameters().get("dimensions"));Map<String,Object> proof=linked("decisionBasis","PROGRAM_STRUCTURAL_COVERAGE","materialType",str(r.parameters(),"materialType"),"requiredDimensions",dimensions,"coveredDimensions",scan.covered(),"missingDimensions",scan.missing(),"scannedSourceIds",scan.sourceIds(),"coverageComplete",scan.coverageComplete(),"scoreProduced",false);
        if(dimensions.isEmpty())return unknown(r,"QUALITY_DIMENSIONS_REQUIRED","质量建议须先从招标条款取得明确评价维度；程序不会自行生成评分量表",scan.facts(),proof);
        if(!scan.scopeLocated())return unknown(r,"QUALITY_MATERIAL_SCOPE_REQUIRED",scan.reason(),scan.facts(),proof);
        if(!scan.coverageComplete())return unknown(r,"QUALITY_BODY_COVERAGE_REQUIRED",scan.reason(),scan.facts(),proof);
        if(!scan.missing().isEmpty())return fail(r,"材料正文范围已闭合，但以下要求维度未形成实质正文覆盖："+String.join("、",scan.missing()),scan.facts(),proof);
        return pass(r,"材料正文及全部要求维度均有实质内容覆盖；本结论不生成或接受模型质量评分",scan.facts(),proof);}
    Decision quality(Rule r,List<Fact> matched){return unknown(r,"QUALITY_PROGRAM_RULE_REQUIRED","旧版模型质量评分谓词已停用；须重新映射为程序化材料正文与维度覆盖检查",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("modelQualityFactsIgnored", matched.size()));}
    Decision external(Rule r){List<String> subjects=resolvedSubjects(r);if(subjects.isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","尚未把核验角色关联到具体主体，不以角色字面值发起外部查询",cn.bidcheck.compat.Java8Api.listOf());List<Decision> results=new ArrayList<>();for(String subject:subjects)results.add(externalSubject(new Rule(r.id()+":"+identity(subject).substring(0,8),r.description(),r.predicate(),subject,r.field(),r.operator(),r.expected(),r.unit(),r.parameters(),r.sourceQuote(),r.logic(),r.children())));if(results.size()==1){Decision result=results.get(0);return new Decision(r.id(),r.description(),result.status(),result.reason(),result.evidences(),result.gaps(),result.proof());}return combine(r.id(),r.description(),"ALL",results);}
    Decision externalSubject(Rule r){String requiredStage=str(r.parameters(),"evaluationStage");if(!cn.bidcheck.compat.Java8Api.isBlank(requiredStage)&&!requiredStage.equals(str(context,"evaluationStage")))return unknown(r,"WAIT_STAGE","该核查须在指定业务阶段进行："+requiredStage,cn.bidcheck.compat.Java8Api.listOf());
        String requiredType=cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"externalType"))?r.field():str(r.parameters(),"externalType");
        List<Map<String,Object>> records=maps(context.get("externalRecords"));for(Map<String,Object> record:records){if(!"TRUSTED_CONNECTOR".equals(str(record,"provenanceStatus")))continue;if(!cn.bidcheck.compat.Java8Api.isBlank(r.subject())&&!r.subject().equals(str(record,"subject")))continue;if(!requiredType.equals(str(record,"type")))continue;if(cn.bidcheck.compat.Java8Api.isBlank(str(record,"rawSnapshotHash"))||cn.bidcheck.compat.Java8Api.isBlank(str(record,"source"))||cn.bidcheck.compat.Java8Api.isBlank(str(record,"queriedAt")))continue;if(!cn.bidcheck.compat.Java8Api.isBlank(requiredStage)&&!requiredStage.equals(str(record,"evaluationStage")))continue;
            if(Boolean.TRUE.equals(r.parameters().get("requiresReferenceDate"))){LocalDate ref=referenceDate();if(ref==null||!ref.toString().equals(str(record,"effectiveAt")))continue;}
            if(!Boolean.TRUE.equals(record.get("coverageComplete"))||!(record.get("result") instanceof Map<?,?>)||!"VERIFIED".equals(((Map<?,?>) (record.get("result"))).get("status")))continue;
            for(Map<String,Object> value:maps(((Map<?,?>) (record.get("result"))).get("facts"))){if(!r.field().equals(str(value,"field"))||cn.bidcheck.compat.Java8Api.isBlank(str(value,"sourceQuote"))||!contains(str(value,"sourceQuote"),str(value,"rawValue")))continue;String actual=str(value,"value");Boolean meets;if(cn.bidcheck.compat.Java8Api.setOf("=", "==", "EQ", "").contains(r.operator()))meets=actual.equals(r.expected());else {BigDecimal a=decimal(actual),b=decimal(r.expected());meets=a==null||b==null?null:compare(a,b,r.operator());}if(meets==null)continue;
                return new Decision(r.id(),r.description(),meets?"PASS":"FAIL",meets?"指定来源、主体、阶段与范围的原始查询字段满足条件":"指定来源及范围的实际字段与要求存在偏差",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),linked("externalRecordId",record.get("id"),"rawSnapshotHash",record.get("rawSnapshotHash"),"source",record.get("source"),"queriedAt",record.get("queriedAt"),"actual",actual,"expected",r.expected(),"field",r.field(),"decisionBasis","EXTERNAL_VERIFICATION"));}}
        return unknown(r,"EXTERNAL_RECORD_REQUIRED","声明/历史截图或未认证导入不能替代指定来源、主体、阶段及覆盖范围的外部记录",cn.bidcheck.compat.Java8Api.listOf());}
    Decision visual(Rule r){return visual(r,r.id());}
    private Decision visual(Rule r,String observationObligationId){String documentType=str(r.parameters(),"documentType");if(cn.bidcheck.compat.Java8Api.isBlank(documentType))return unknown(r,"VISUAL_SCOPE_REQUIRED","所需文书及签署位置尚未明确，不能借其他材料上的同主体章/签名满足本条件",cn.bidcheck.compat.Java8Api.listOf());List<String> subjects=resolvedSubjects(r);if(subjects.isEmpty())return unknown(r,"SUBJECT_RELATION_UNPROVEN","签署角色尚未关联到具体人员或单位，不能拿模板角色名当作可见姓名",cn.bidcheck.compat.Java8Api.listOf());Set<String> observedSubjects=new LinkedHashSet<>();List<Evidence> observedEvidence=new ArrayList<>();List<Map<String,Object>> observationsUsed=new ArrayList<>();Map<String,DocumentScopeProof.Resolver> visualScopeResolvers=new HashMap<>();List<Source> relevant=cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->!s.index()&&(!s.historical()||TenderFormAudit.visualSourceCurrent(r,s,corpus,visualScopeResolvers))&&(contains(s.opening(),documentType)||contains(s.evidence().quote(),documentType))).filter(s->!r.parameters().containsKey("visualSourceIds")||strings(r.parameters().get("visualSourceIds")).contains(s.id())));
        Map<String,Set<String>> reviewedAt=new LinkedHashMap<>(),absentAt=new LinkedHashMap<>(),inconclusiveAt=new LinkedHashMap<>();List<Map<String,Object>> completedReviews=new ArrayList<>();List<Evidence> reviewedEvidence=new ArrayList<>();
        List<Source> negativeScope=cn.bidcheck.compat.Java8Api.toList(relevant.stream().filter(s->s.scopeContext()!=null&&cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION").contains(s.scopeContext().status())||TenderFormAudit.visualSourceCurrent(r,s,corpus,visualScopeResolvers)));
        String requiredType=str(r.parameters(),"visualType");if(cn.bidcheck.compat.Java8Api.isBlank(requiredType))requiredType=r.field().toLowerCase().contains("seal")||r.description().contains("盖章")?"SEAL":"SIGNATURE";
        for(Map<String,Object> observation:maps(context.get("visualObservations"))){if(!observationObligationId.equals(str(observation,"obligationId")))continue;
            String id=str(observation,"documentId");int location=RunSession.number(observation.get("location"),RunSession.number(observation.get("page"),-1));String type=str(observation,"locationType");if(cn.bidcheck.compat.Java8Api.isBlank(type))type="PAGE";
            PageIdentity found=pageIdentity(id,location,type);
            if(relevant.stream().noneMatch(s->s.evidence().documentId().equals(id)&&s.evidence().page()==location))continue;
            if(found==null||cn.bidcheck.compat.Java8Api.isBlank(str(observation,"imageHash")))continue;
            if(!(observation.get("source") instanceof Map<?,?>)||!found.hash.equals(String.valueOf(((Map<?,?>) (observation.get("source"))).get("documentHash")))||!str(observation,"imageHash").equals(String.valueOf(((Map<?,?>) (observation.get("source"))).get("renderedSha256"))))continue;
            if(((Map<?,?>) (observation.get("source"))).containsKey("documentVersion")&&RunSession.number(((Map<?,?>) (observation.get("source"))).get("documentVersion"),-1)!=found.version||((Map<?,?>) (observation.get("source"))).containsKey("documentId")&&!id.equals(((Map<?,?>) (observation.get("source"))).get("documentId"))||((Map<?,?>) (observation.get("source"))).containsKey("page")&&RunSession.number(((Map<?,?>) (observation.get("source"))).get("page"),-1)!=location)continue;
            List<String> reviewed=((Map<?,?>) (observation.get("source"))).containsKey("documentVersion")&&((Map<?,?>) (observation.get("source"))).containsKey("documentId")&&((Map<?,?>) (observation.get("source"))).containsKey("page")
                    ?VisualMarkReview.reviewedSubjects(observation,requiredType,subjects,negativeScope):cn.bidcheck.compat.Java8Api.listOf();
            if(!reviewed.isEmpty()&&!"NOT_OBSERVED".equals(VisualMarkReview.outcome(observation)))inconclusiveAt.computeIfAbsent(EvidenceCorpus.pageKey(id,location,type),k->new LinkedHashSet<>()).addAll(reviewed);
            if("CLEAR".equals(str(observation,"readability")))reviewed=cn.bidcheck.compat.Java8Api.listOf();
            if(!reviewed.isEmpty()){
                String key=EvidenceCorpus.pageKey(id,location,type),readability=VisualMarkReview.outcome(observation);reviewedAt.computeIfAbsent(key,k->new LinkedHashSet<>()).addAll(reviewed);
                if("NOT_OBSERVED".equals(readability))absentAt.computeIfAbsent(key,k->new LinkedHashSet<>()).addAll(reviewed);
                reviewedEvidence.add(new Evidence(id,found.name,location,type,"[原图已核验："+r.description()+"；"+("NOT_OBSERVED".equals(readability)?"未观察到要求签章":"无法辨清要求签章")+"]","image:"+str(observation,"imageHash"),0));
                completedReviews.add(linked("observationId",str(observation,"id"),"obligationId",observationObligationId,"sourceBindingVerified",true,"imageHash",str(observation,"imageHash"),"documentHash",found.hash,"documentVersion",found.version,"documentId",id,"location",location,"type",requiredType,"subjects",reviewed,"outcome",readability,"coverage","CURRENT_FULL_PAGE","requestedSourceIds",strings(observation.get("requestedSourceIds"))));continue;
            }
            if(!VisualObservationPolicy.assess(observation).valueUsable()||!"CLEAR".equals(str(observation,"readability"))||!VisualObservationPolicy.validSourceBounds(observation))continue;
            Object value=observation.get("observedValue");String observed=value==null?"":value.toString();
            if(!requiredType.equals(str(observation,"type"))||cn.bidcheck.compat.Java8Api.isBlank(observed))continue;List<String> present=cn.bidcheck.compat.Java8Api.toList(subjects.stream().filter(subject->contains(observed,subject)));if(present.isEmpty())continue;
            Evidence ev=new Evidence(id,found.name,location,type,"[原图观察："+r.description()+"]","image:"+str(observation,"imageHash"),0);
            observedSubjects.addAll(present);observedEvidence.add(ev);observationsUsed.add(cn.bidcheck.compat.Java8Api.mapOf("observationId", observation.get("id"), "imageHash", observation.get("imageHash")));}
        boolean conflict=absentAt.entrySet().stream().anyMatch(entry->entry.getValue().stream().anyMatch(subject->observedSubjects.contains(subject)||inconclusiveAt.getOrDefault(entry.getKey(),cn.bidcheck.compat.Java8Api.setOf()).contains(subject)));
        if(observedSubjects.containsAll(subjects)&&!conflict)return new Decision(r.id(),r.description(),"PASS",r.field().toLowerCase().contains("seal")||r.description().contains("盖章")?"要求位置已盖章，满足盖章要求":"要求位置已签字，满足签字要求",observedEvidence,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("observations", observationsUsed, "subjects", subjects, "decisionBasis", "VISUAL_OBSERVATION"));
        int newRequests=0;for(Source s:relevant){String key=EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType());List<String> pending=cn.bidcheck.compat.Java8Api.toList(subjects.stream().filter(subject->!observedSubjects.contains(subject)&&!reviewedAt.getOrDefault(key,cn.bidcheck.compat.Java8Api.setOf()).contains(subject)));if(pending.isEmpty())continue;if(newRequests++>=8)break;
            visualRequests.add(linked("obligationId",observationObligationId,"documentId",s.evidence().documentId(),"locationType",s.evidence().locationType(),"location",s.evidence().page(),"task",requiredType+"_VISIBILITY","fields",pending,"expectedSubjects",subjects,"sourceIds",cn.bidcheck.compat.Java8Api.listOf(s.id()),"question",r.description()));}
        if(!completedReviews.isEmpty()){
            Map<String,Object> proof=linked("decisionBasis","VISUAL_OBSERVATION","observations",completedReviews,"positiveObservations",observationsUsed,"subjects",subjects,"automaticRejection",false,"authenticityAssessed",false);
            org.slf4j.LoggerFactory.getLogger(RuleEvaluator.class).info("bidcheck.visual_consumption runId={} checkpointId={} obligationId={} type={} reviewed={} visibleSubjects={} notObservedLocations={} conflict={}",str(context,"sourceRunId"),str(context,"checkpointId"),observationObligationId,requiredType,completedReviews.size(),observedSubjects.size(),absentAt.size(),conflict);
            if(conflict)return new Decision(r.id(),r.description(),"UNDETERMINED","同一要求签章存在相互矛盾的原图观察，需对照原图确认",reviewedEvidence,cn.bidcheck.compat.Java8Api.listOf(gap("VISUAL_MARK_OBSERVATION_CONFLICT","原图已核验但观察结论不一致，请核对指定签署位置")),proof);
            List<String> absent=cn.bidcheck.compat.Java8Api.toList(subjects.stream().filter(subject->!observedSubjects.contains(subject)&&!relevant.isEmpty()&&relevant.stream().allMatch(s->absentAt.getOrDefault(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),cn.bidcheck.compat.Java8Api.setOf()).contains(subject))));
            if(!absent.isEmpty()){proof.put("observationOutcome","NOT_OBSERVED");proof.put("missingVisibleSubjects",absent);return new Decision(r.id(),r.description(),"FAIL","指定位置未观察到要求签章，存在签署风险；请对照原图确认",reviewedEvidence,cn.bidcheck.compat.Java8Api.listOf(),proof);}
            proof.put("observationOutcome","UNREADABLE_OR_PARTIAL_REVIEW");return new Decision(r.id(),r.description(),"UNDETERMINED","原图已核验，但部分要求签章无法辨清或仍有位置待核对",reviewedEvidence,cn.bidcheck.compat.Java8Api.listOf(gap("VISUAL_MARK_UNREADABLE","已检查的签章未能清楚识别；请核对所列原图，不表示原文件缺失")),proof);
        }
        if(relevant.isEmpty())return unknown(r,"REQUIREMENT_VISUAL_SCOPE_UNRESOLVED","要求文书尚未对应到投标文件中的具体签署位置，原图任务尚未生成",cn.bidcheck.compat.Java8Api.listOf());
        return unknown(r,"VISUAL_OBSERVATION_REQUIRED","要求位置的签字盖章尚待原图核验，已生成有位置的原图任务",cn.bidcheck.compat.Java8Api.listOf());}
    private boolean keywordsMatch(Source s,Rule r){return EvidenceCorpus.keywords(r.description()+" "+r.expected()).stream().anyMatch(k->s.evidence().quote().contains(k));}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class PageIdentity{
        private final String name;
        private final String hash;
        private final int version;
        @com.fasterxml.jackson.annotation.JsonCreator
        PageIdentity(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("hash") String hash, @com.fasterxml.jackson.annotation.JsonProperty("version") int version) { this.name = name; this.hash = hash; this.version = version; }
        public String name() { return name; }
        public String hash() { return hash; }
        public int version() { return version; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PageIdentity that = (PageIdentity) other; return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.hash, that.hash) && java.util.Objects.equals(this.version, that.version); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.name); hash = 31 * hash + java.util.Objects.hashCode(this.hash); hash = 31 * hash + java.util.Objects.hashCode(this.version); return hash; }
        @Override public String toString() { return "PageIdentity[" + "name=" + name + ", hash=" + hash + ", version=" + version + "]"; }
} private PageIdentity pageIdentity(String id,int location,String type){return corpus.documents.stream().filter(d->d.id().equals(id)&&d.pages().stream().anyMatch(p->p.number()==location&&p.locationType().equals(type))).map(d->new PageIdentity(d.name(),d.sha256(),d.version())).findFirst().orElse(null);}
    Decision reference(Rule r){LocalDate date=referenceDate(r);return date==null?unknown(r,"REFERENCE_TIME_UNCONFIRMED","最终参考时点及其依据尚未确认",cn.bidcheck.compat.Java8Api.listOf()):pass(r,"参考时点已关联明确来源；该条件不证明证书有效或材料齐全",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("referenceDate", date.toString(), "referenceDateEvidence", referenceEvidence(r)));}
    LocalDate referenceDate(){return referenceDate(null);}
    private String referenceEvent(Rule rule){String event=rule==null?"":str(rule.parameters(),"referenceEvent");String requirement=rule==null?"":rule.sourceQuote()+" "+rule.description();
        if(cn.bidcheck.compat.Java8Api.isBlank(event))return requirement.contains("开标")?"":"BID_DEADLINE";
        if(!cn.bidcheck.compat.Java8Api.setOf("BID_DEADLINE", "OPENING_DATE").contains(event))return "";
        if(event.equals("OPENING_DATE")&&!requirement.contains("开标"))return "";
        if(event.equals("BID_DEADLINE")&&requirement.contains("开标")&&!requirement.matches("(?s).*(?:投标截止|递交截止|递交的截止|提交截止).*[\\s\\S]*"))return "";
        return event;}
    private Map<String,Object> referenceEntry(Rule rule){String event=referenceEvent(rule);if(cn.bidcheck.compat.Java8Api.isBlank(event))return cn.bidcheck.compat.Java8Api.mapOf();Object entries=context.get("referenceDates");
        if(entries instanceof Map<?,?>&&((Map<?,?>) (entries)).get(event) instanceof Map<?,?>){Map<String,Object> result=new LinkedHashMap<>();((Map<?,?>) (((Map<?,?>) (entries)).get(event))).forEach((key,value)->result.put(String.valueOf(key),value));return result;}
        return event.equals("BID_DEADLINE")?context:cn.bidcheck.compat.Java8Api.mapOf();}
    private Object referenceEvidence(Rule rule){return referenceEntry(rule).getOrDefault("referenceDateEvidence",cn.bidcheck.compat.Java8Api.mapOf());}
    LocalDate referenceDate(Rule rule){Map<String,Object> entry=referenceEntry(rule);LocalDate value=date(entry.get("referenceDate"));Object evidence=entry.get("referenceDateEvidence");if(value==null||!(evidence instanceof Map<?,?>)||!Boolean.TRUE.equals(entry.get("referenceDateSourceVerified")))return null;
        String quote=Objects.toString(((Map<?,?>) (evidence)).get("quote"),""),confirmation=Objects.toString(((Map<?,?>) (evidence)).get("confirmation"),"");
        boolean confirmed="USER_SELECTED_VERSION".equals(confirmation)||"SOURCE_UNAMBIGUOUS".equals(confirmation)&&Boolean.TRUE.equals(((Map<?,?>) (evidence)).get("confirmed"));
        if(!confirmed||cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(((Map<?,?>) (evidence)).get("documentId"),""))||!datePresent(quote,value.toString()))return null;
        String source=str(entry,"referenceDateSource");if(!cn.bidcheck.compat.Java8Api.isBlank(source)&&!source.equals(((Map<?,?>) (evidence)).get("documentId")))return null;
        String label=referenceEvent(rule).equals("OPENING_DATE")?"开标(?:时间|日期)?":"(?:投标截止|文件递交截止|递交截止|投标文件递交的截止|提交截止)";
        return quote.matches("(?s).*"+label+".*")?value:null;}
    static List<Map<String,Object>> maps(Object value){List<Map<String,Object>> out=new ArrayList<>();if(value instanceof List<?>)for(Object item:((List<?>) (value)))if(item instanceof Map<?,?>){Map<String,Object> copy=new LinkedHashMap<>();((Map<?,?>) (item)).forEach((k,v)->copy.put(String.valueOf(k),v));out.add(copy);}return out;}
    static Boolean compare(BigDecimal a,BigDecimal b,String op){int c=a.compareTo(b);switch (op) {case ">=":case "GE":return c>=0;case "<=":case "LE":return c<=0;case "=":case "==":case "EQ":return c==0;case ">":case "GT":return c>0;case "<":case "LT":return c<0;default:return null;}}
    static BigDecimal normalize(BigDecimal n,String unit){if(n==null)return null;switch (unit) {case "元":case "CNY":case "RMB":case "美元":case "USD":case "欧元":case "EUR":case "港元":case "HKD":case "天":case "日":case "DAY":case "个月":case "月":case "MONTH":case "":case "个":case "项":return n;case "万元":return n.multiply(BigDecimal.valueOf(10000));case "亿元":return n.multiply(BigDecimal.valueOf(100000000));case "年":case "YEAR":return n.multiply(BigDecimal.valueOf(12));default:return null;}}
    static boolean sameUnitFamily(String a,String b){return family(a).equals(family(b));}private static String family(String u){switch (u) {case "元":case "万元":case "亿元":case "CNY":case "RMB":case "美元":case "USD":case "欧元":case "EUR":case "港元":case "HKD":return "MONEY";case "年":case "YEAR":case "月":case "个月":case "MONTH":return "MONTH";case "天":case "日":case "DAY":return "DAY";default:return u;}}
    Decision pass(Rule r,String reason,List<Fact> facts,Map<String,Object> proof){return result(r,"PASS",reason,facts,cn.bidcheck.compat.Java8Api.listOf(),proof);}Decision fail(Rule r,String reason,List<Fact> facts,Map<String,Object> proof){return result(r,"FAIL",reason,facts,cn.bidcheck.compat.Java8Api.listOf(),proof);}
    Decision unknown(Rule r,String code,String reason,List<Fact> facts){return unknown(r,code,reason,facts,cn.bidcheck.compat.Java8Api.mapOf());}Decision unknown(Rule r,String code,String reason,List<Fact> facts,Map<String,Object> proof){Gap missing="UNSUPPORTED_RULE".equals(code)?new Gap(code,reason,"修正规则映射并重新计算本条件；补充投标证据不能修复未知规则","REQUIREMENT_MAPPING_CHANGED"):gap(code,reason);return result(r,"UNDETERMINED",reason,facts,cn.bidcheck.compat.Java8Api.listOf(missing),proof);}
    Decision result(Rule r,String status,String reason,List<Fact> used,List<Gap> gaps,Map<String,Object> proof){Map<String,Object> complete=new LinkedHashMap<>(proof);complete.put("ruleVersion",VERSION);complete.put("predicate",r.predicate());complete.put("factIds",cn.bidcheck.compat.Java8Api.toList(used.stream().map(Fact::id)));complete.put("requirementQuote",r.sourceQuote());return new Decision(r.id(),r.description(),status,reason,cn.bidcheck.compat.Java8Api.toList(used.stream().flatMap(f->f.sourceIds().stream()).distinct().map(corpus.byId::get).filter(Objects::nonNull).map(Source::evidence)),gaps,complete);}
    static List<Evidence> union(List<Decision> decisions){return cn.bidcheck.compat.Java8Api.toList(decisions.stream().flatMap(d->d.evidences().stream()).distinct());}
}
