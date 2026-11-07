package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** The investigation observes only unfinished dependencies; the host still evaluates the whole plan. */
final class AgentInvestigationScope {
    private AgentInvestigationScope() {}
    static boolean closed(Decision d){return d!=null&&d.gaps().isEmpty()&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(d.status());}
    /** Explicit acquisition prerequisites are separate from the business plan. Binding a person
     * never proves their credential, social insurance or performance. */
    static List<Rule> withIdentityPrerequisites(List<Rule> rules){
        Map<String,List<Rule>> dependent=new LinkedHashMap<>();
        for(Rule rule:rules)if(!rule.predicate().equals("PERSON_ROLE"))for(String selector:AgentRuleSubjects.selectors(rule))
            if(PersonnelRoles.personRole(selector))dependent.computeIfAbsent(PersonnelRoles.canonical(selector),k->new ArrayList<>()).add(rule);
        List<Rule> result=new ArrayList<>();Set<String> existing=new HashSet<>();rules.stream().filter(r->r.predicate().equals("PERSON_ROLE")).forEach(r->existing.add(PersonnelRoles.canonical(r.subject())));
        dependent.forEach((role,parents)->{if(!existing.add(PersonnelRoles.canonical(role)))return;
            result.add(new Rule("BINDING-"+identity(PersonnelRoles.canonical(role)).substring(0,16),"先确认当前岗位人员："+role,"PERSON_ROLE",role,"姓名","EXISTS","","",
                linked("identityPrerequisite",true,"prerequisiteFor",cn.bidcheck.compat.Java8Api.toList(parents.stream().map(Rule::id)),"doesNotProveDependentObligations",true),parents.get(0).sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf()));});
        result.addAll(rules);return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }
    private static Decision currentDecision(Rule rule,String id,RuleEvaluator evaluator){
        Decision decision=Boolean.TRUE.equals(rule.parameters().get("identityPrerequisite"))?evaluator.evaluate(rule):evaluator.decisions.get(id);
        if(CompoundBusinessRequirements.isRead(rule))return CompoundBusinessRequirements.readDecision(rule,decision);
        return SourceScoringRules.bodyReadDecision(rule,
            SourcePerformanceScore.readDecision(rule,decision,evaluator),evaluator);
    }
    private static boolean prerequisiteCovered(Rule rule,Set<String> covered){
        List<String> parents=ProofVerifier.strings(rule.parameters().get("prerequisiteFor"));
        return Boolean.TRUE.equals(rule.parameters().get("identityPrerequisite"))&&!parents.isEmpty()&&covered.containsAll(parents);
    }

    static boolean requiresEvidence(Decision decision){
        if(closed(decision))return false;
        if(decision!=null&&"COMPOUND_EVIDENCE_READ".equals(str(decision.proof(),"decisionScope"))
                &&Boolean.TRUE.equals(decision.proof().get("awaitingReadPrerequisite")))return false;
        if(decision!=null&&awaitingRequirementSource(decision.proof()))return false;
        if(SourceScoringRules.professionReviewRequired(decision))return false;
        if(LegalRegistrationProof.hostTextExamined(decision))return false;
        if(decision!=null&&"CAPABILITY_STANDARD_NOT_SPECIFIED".equals(str(decision.proof(),"proofScope"))
                &&Boolean.TRUE.equals(decision.proof().get("requiresRequirementConfirmation"))
                &&!decision.gaps().isEmpty()&&decision.gaps().stream().allMatch(g->"CAPABILITY_CRITERIA_REQUIRED".equals(g.code())))return false;
        if(decision==null||decision.gaps().isEmpty())return true;
        for(Gap gap:decision.gaps())if(!Arrays.asList("CURRENT_TAXPAYER_PROOF_REQUIRED","VISUAL_MARK_UNREADABLE",
                "VISUAL_OBSERVATION_REQUIRED","BID_VOLUME_NOT_UPLOADED","EXTERNAL_OWNER_DATA_REQUIRED","EXTERNAL_RECORD_REQUIRED","EVIDENCE_LOOKUP_INCOMPLETE").contains(gap.code()))return true;
        return false;
    }
    /** An unresolved tender baseline needs requirement clarification, not another bid-person search. */
    static boolean awaitingRequirementSource(Map<String,Object> proof) {
        if (!"source-extra-personnel-score-1".equals(str(proof, "executor"))) return false;
        Object raw = proof.get("baseConfiguration");
        if (!(raw instanceof Map<?,?>)) return false;
        Map<String,Object> base = (Map<String,Object>) raw;
        return "UNDETERMINED".equals(str(base, "status"))
            && RuleEvaluator.maps(base.get("gaps")).stream().anyMatch(gap ->
                "SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED".equals(str(gap, "code")));
    }
    static List<Rule> pending(Plan plan,List<Rule> executable,Map<String,Object> context,RuleEvaluator evaluator){
        Map<String,String> parents=SourceScoringRules.readParents(plan.rules(),executable);
        Set<String> primary=new LinkedHashSet<>();
        if(context.containsKey("recoveryTargetObligationIds")){
            Set<String> requested=new LinkedHashSet<>(ProofVerifier.strings(context.get("recoveryTargetObligationIds")));
            plan.rules().forEach(r->select(r,requested,primary,false));
            parents.forEach((read,parent)->{if(primary.contains(parent)||requested.contains(read))primary.add(read);});
        }else executable.forEach(r->primary.add(r.id()));
        // A selected conditional branch first depends on its applicability condition. The
        // unselected alternative and already-satisfied ANY groups are not outstanding work.
        plan.rules().forEach(r->conditionalDependencies(r,primary));
        Set<String> applicable=new LinkedHashSet<>();plan.rules().forEach(r->applicable(r,evaluator,applicable));
        parents.forEach((read,parent)->{if(applicable.contains(parent))applicable.add(read);});
        List<String> unclosed=cn.bidcheck.compat.Java8Api.toList(executable.stream().filter(r->primary.contains(r.id())&&applicable.contains(r.id()))
            .filter(r->!kinds(r).isEmpty())
            .filter(r->requiresEvidence(currentDecision(r,parents.getOrDefault(r.id(),r.id()),evaluator))).map(Rule::id));
        if(unclosed.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        // Identity facts remain admissible prerequisites through relevant(). Do not re-expand
        // a selected ::own predicate into its sibling obligations just because they share a role.
        Set<String> selected=new LinkedHashSet<>(unclosed);
        return cn.bidcheck.compat.Java8Api.toList(executable.stream().filter(r->selected.contains(r.id())&&!kinds(r).isEmpty())
            .filter(r->requiresEvidence(currentDecision(r,parents.getOrDefault(r.id(),r.id()),evaluator))));
    }
    private static void select(Rule r,Set<String> requested,Set<String> selected,boolean parent){
        boolean direct=parent||requested.contains(r.id())||requested.stream().anyMatch(id->id.startsWith(r.id()+":subject:"));
        if(requested.contains(r.id()+"::own")&&RuleObligations.hasOwnPredicate(r))selected.add(r.id()+"::own");
        if(direct){selected.add(r.id());if(RuleObligations.hasOwnPredicate(r))selected.add(r.id()+"::own");}
        r.children().forEach(child->select(child,requested,selected,direct));
    }
    private static boolean conditionalDependencies(Rule r,Set<String> selected){
        boolean matched=selected.contains(r.id())||selected.contains(r.id()+"::own");
        for(Rule child:r.children())matched|=conditionalDependencies(child,selected);
        if(matched&&r.logic().equals("CONDITIONAL")&&!r.children().isEmpty())RequirementCompiler.leaves(cn.bidcheck.compat.Java8Api.listOf(r.children().get(0))).forEach(gate->selected.add(gate.id()));
        return matched;
    }
    private static void applicable(Rule r,RuleEvaluator evaluator,Set<String> selected){
        if(closed(evaluator.decisions.get(r.id())))return;
        if(r.children().isEmpty()||r.predicate().equals("SCORE_COUNT")){selected.add(r.id());return;}
        if(RuleObligations.hasOwnPredicate(r))selected.add(r.id()+"::own");
        if(r.logic().equals("CONDITIONAL")&&r.children().size()>=3){
            Rule gate=r.children().get(0);applicable(gate,evaluator,selected);Decision condition=evaluator.decisions.get(gate.id());
            if(condition!=null&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(condition.status()))applicable(r.children().get(condition.status().equals("PASS")?1:2),evaluator,selected);
        }else if(!r.logic().equals("FOR_EACH")||Boolean.TRUE.equals(r.parameters().get("subjectSetComplete")))r.children().forEach(child->applicable(child,evaluator,selected));
    }
    static List<Decision> decisions(List<Rule> rules,Plan plan,RuleEvaluator evaluator){
        Map<String,String> parents=SourceScoringRules.readParents(plan.rules(),rules);
        Set<String> covered=coveredByCurrentTree(plan,evaluator);
        return cn.bidcheck.compat.Java8Api.toList(rules.stream().filter(r->!covered.contains(parents.getOrDefault(r.id(),r.id()))&&!prerequisiteCovered(r,covered)).map(r->{
            Decision value=currentDecision(r,parents.getOrDefault(r.id(),r.id()),evaluator);
            return value==null?new Decision(r.id(),r.description(),"UNDETERMINED","本目标的适用前提尚未闭合",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("TARGET_DEPENDENCY_UNRESOLVED","须先证明本目标的适用前提，不能按缺少分支计算回执认定目标完成")),cn.bidcheck.compat.Java8Api.mapOf()):value;
        }).distinct());
    }
    static boolean completed(List<Rule> rules,Plan plan,RuleEvaluator evaluator){
        Map<String,String> parents=SourceScoringRules.readParents(plan.rules(),rules);
        Set<String> covered=coveredByCurrentTree(plan,evaluator);
        return !rules.isEmpty()&&rules.stream().allMatch(r->covered.contains(parents.getOrDefault(r.id(),r.id()))||prerequisiteCovered(r,covered)||closed(currentDecision(r,parents.getOrDefault(r.id(),r.id()),evaluator)));
    }
    static Map<String,Object> targetReceipt(List<Rule> rules,Plan plan,RuleEvaluator evaluator){
        Map<String,String> parents=SourceScoringRules.readParents(plan.rules(),rules);
        Set<String> covered=coveredByCurrentTree(plan,evaluator);
        List<String> done=new ArrayList<>(),remaining=new ArrayList<>(),confirmation=new ArrayList<>();Map<String,Object> confirmationProofs=new LinkedHashMap<>();
        for(Rule rule:rules){String id=parents.getOrDefault(rule.id(),rule.id());
            Decision decision=currentDecision(rule,id,evaluator);
            if(covered.contains(id)||prerequisiteCovered(rule,covered)||closed(decision)){done.add(rule.id());continue;}
            remaining.add(rule.id());Map<String,Object> waiting=AgentConfirmationTargets.proof(rule,decision,evaluator);
            if(!waiting.isEmpty()){confirmation.add(rule.id());confirmationProofs.put(rule.id(),waiting);}
        }
        return linked("completedTargetObligationIds",done,"remainingTargetObligationIds",remaining,
            "targetReceiptAuthority","CURRENT_SERVER_PROOF","pendingConfirmationTargetObligationIds",confirmation,
            "confirmationTargetProofs",confirmationProofs,"waitingForConfirmation",!remaining.isEmpty()&&new HashSet<>(confirmation).equals(new HashSet<>(remaining)));
    }
    static Set<String> coveredByCurrentTree(Plan plan,RuleEvaluator evaluator){
        Set<String> covered=new LinkedHashSet<>();
        List<Decision> roots=cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(r->evaluator.decisions.get(r.id())).filter(Objects::nonNull));
        if(roots.size()==plan.rules().size()&&!roots.isEmpty()&&closed(RuleEvaluator.combine("scope","scope",plan.logic(),roots)))plan.rules().forEach(r->subtree(r,covered));
        else plan.rules().forEach(r->covered(r,evaluator,covered));
        return covered;
    }
    private static void covered(Rule r,RuleEvaluator evaluator,Set<String> covered){
        if(!r.children().isEmpty()&&closed(evaluator.decisions.get(r.id()))){subtree(r,covered);return;}
        if(r.logic().equals("CONDITIONAL")&&r.children().size()>=3){
            Decision gate=evaluator.decisions.get(r.children().get(0).id());
            if(gate!=null&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(gate.status()))subtree(r.children().get(gate.status().equals("PASS")?2:1),covered);
        }
        r.children().forEach(child->covered(child,evaluator,covered));
    }
    private static void subtree(Rule r,Set<String> ids){ids.add(r.id());if(RuleObligations.hasOwnPredicate(r))ids.add(r.id()+"::own");r.children().forEach(child->subtree(child,ids));}
    static List<Map<String,Object>> contracts(List<Rule> rules){return contracts(rules,cn.bidcheck.compat.Java8Api.listOf(),null,cn.bidcheck.compat.Java8Api.listOf());}
    /** A typed protocol error is not an OCR failure. The existing source verifier remains authoritative. */
    static Map<String,Object> candidateSubjectIssue(Map<String,Object> candidate,List<Rule> rules){
        String kind=str(candidate,"kind"),subject=str(candidate,"subject");
        if(!cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE", "DATE_RANGE", "MONTHS").contains(kind))return cn.bidcheck.compat.Java8Api.mapOf();
        List<String> targets=cn.bidcheck.compat.Java8Api.toList(rules.stream().filter(r->kinds(r).contains(kind)).map(Rule::id));
        if(targets.isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        String person=candidate.get("attributes") instanceof Map<?,?>?Objects.toString(((Map<?,?>) (candidate.get("attributes"))).get("person"),""):"";
        String invalid=DomainBindings.role(subject)||AgentRuleSubjects.collective(subject)?subject
            :kind.equals("MONTHS")&&(DomainBindings.role(person)||AgentRuleSubjects.collective(person))?person:"";
        if(cn.bidcheck.compat.Java8Api.isBlank(invalid))return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("code","SUBJECT_BINDING_REQUIRED","kind",kind,"subject",subject,"subjectSelector",invalid,
            "field",str(candidate,"field"),"sourceIds",ProofVerifier.strings(candidate.get("sourceIds")),"targetObligationIds",targets,
            "nextAction","RESOLVE_SUBJECT_FIRST","reason","岗位或单位角色不能作为具体持证主体。先用已读原文建立任职/单位身份关系，再提交具体姓名或单位全称；重新读图不能替代主体绑定。");
    }
    static List<Map<String,Object>> contracts(List<Rule> rules,Collection<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        return cn.bidcheck.compat.Java8Api.toList(rules.stream().map(r->{
            Map<String,Object> binding=AgentRuleSubjects.resolve(r,facts,corpus,layout);
            List<String> names=cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(binding.get("resolvedSubjects")).stream().map(s->str(s,"subject")).distinct());
            // A rule's subject is a selector, while a certificate's subject is its actual holder.
            // Do not advertise the selector as a ready-to-submit certificate/person subject.
            boolean selector=DomainBindings.role(r.subject())||AgentRuleSubjects.collective(r.subject());
            String subject=r.predicate().equals("PERSON_ROLE")?r.subject():!selector?r.subject():names.size()==1&&"RESOLVED".equals(binding.get("bindingStatus"))?names.get(0):"";
            Map<String,Object> contract=linked("obligationId",r.id(),"predicate",r.predicate(),"subject",subject,
                "field",r.field(),"factKinds",kinds(r),"relationFields",relationFields(r),
                "requiredProofRoles",RegistrationEmployerProof.applies(r)?cn.bidcheck.compat.Java8Api.listOf("CURRENT_APPOINTMENT", "INDEPENDENT_EMPLOYMENT_RELATION", "CERTIFICATE_REGISTERED_EMPLOYER"):cn.bidcheck.compat.Java8Api.listOf(),
                "bindingPolicy","subjectSelector 是岗位/单位查询条件，不是证书持有人。PERSON_ROLE.subject 填岗位、value 填姓名；CERTIFICATE/DATE_RANGE.subject 填原文持有人或单位名称；MONTHS.subject 填具体姓名。未解析先建立来源支持的任职关系；聘用、注册、委派分别取证。",
                "factSubjectContracts",linked("PERSON_ROLE",linked("subject",r.subject(),"value","原文明确任职的具体姓名"),
                    "CONCRETE_SUBJECT_FIELDS",linked("kinds",cn.bidcheck.compat.Java8Api.listOf("CERTIFICATE", "DATE_RANGE", "MONTHS"),"allowedSubjectNames",names,"roleSelectorAllowed",false),
                    "RELATION",linked("subject","原文关系的起点主体","object","原文关系的终点主体","identityPrerequisiteMayUseRole",true)),
                "identityPrerequisiteKinds",selector?AgentRuleSubjects.collective(r.subject())||PersonnelRoles.personRole(r.subject())?cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE", "RELATION"):cn.bidcheck.compat.Java8Api.listOf("RELATION"):cn.bidcheck.compat.Java8Api.listOf());
            contract.putAll(binding);
            if (kinds(r).contains("CONTRACT")) contract.put("contractFactFields", ContractCandidateFields.contract());
            return contract;
        }));
    }
    static List<Rule> requestRules(List<Rule> rules){return cn.bidcheck.compat.Java8Api.toList(rules.stream().map(r->{
        Map<String,Object> parameters=new LinkedHashMap<>(r.parameters());
        for(String name:cn.bidcheck.compat.Java8Api.listOf("requirementSourceContext", "requirementBindings", "requirementSubjectSet", "obligationIdentity", "sourceContext"))parameters.remove(name);
        Map<String,Object> context=requestContext(r);if(!context.isEmpty())parameters.put("requirementContext",context);
        return new Rule(r.id(),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),parameters,r.sourceQuote(),r.logic(),cn.bidcheck.compat.Java8Api.listOf());
    }));}
    /** Source excerpts carry their exact offsets and truncation flags. Never serialize arbitrary
     * nested proof receipts into a prompt or silently present a shortened clause as complete. */
    private static Map<String,Object> requestContext(Rule rule){
        List<Map<String,Object>> excerpts=new ArrayList<>();Set<String> seen=new HashSet<>();int[] remaining={6000};
        List<String> selectors=RequirementContextBindings.validatedSubjectSet(rule);
        if(!selectors.isEmpty()&&rule.parameters().get("requirementSubjectSet") instanceof Map<?,?>){
            addContext(excerpts,seen,remaining,"PERSONNEL_ENUMERATION",Objects.toString(((Map<?,?>) (rule.parameters().get("requirementSubjectSet"))).get("enumerationQuote"),""),rule.sourceQuote());
            addContext(excerpts,seen,remaining,"COLLECTIVE_OBLIGATION_CONTEXT",Objects.toString(((Map<?,?>) (rule.parameters().get("requirementSubjectSet"))).get("sourceQuote"),""),rule.sourceQuote());
        }
        if(rule.parameters().get("requirementBindings") instanceof Map<?,?>)
            for(Object value:((Map<?,?>) (rule.parameters().get("requirementBindings"))).values())if(value instanceof Map<?,?>)addContext(excerpts,seen,remaining,"BOUND_REQUIREMENT_CONTEXT",Objects.toString(((Map<?,?>) (value)).get("sourceQuote"),""),rule.sourceQuote());
        if(rule.parameters().get("requirementSourceContext") instanceof Map<?,?>&&((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("sources") instanceof List<?>)
            for(Object value:((List<?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("sources"))))if(value instanceof Map<?,?>)addContext(excerpts,seen,remaining,Objects.toString(((Map<?,?>) (value)).get("kind"),"SOURCE_CONTEXT"),Objects.toString(((Map<?,?>) (value)).get("sourceQuote"),Objects.toString(((Map<?,?>) (value)).get("quote"),Objects.toString(((Map<?,?>) (value)).get("text"),""))),rule.sourceQuote());
        if(excerpts.isEmpty()&&selectors.isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("version","agent-requirement-context-1","validatedSubjectSelectors",selectors,"excerpts",excerpts,
            "contextIsAdditionalObligation",false,"instructions","上下文用于解释主体、定义和适用分支；只核验本项原始义务。truncated=true 时须继续读取原文，不得把截取片段视为完整要求。");
    }
    private static void addContext(List<Map<String,Object>> out,Set<String> seen,int[] remaining,String kind,String text,String quote){
        if(cn.bidcheck.compat.Java8Api.isBlank(text)||remaining[0]<=0||!seen.add(identity(text))||out.size()>=8)return;
        int limit=Math.min(remaining[0],4000),start=0;
        if(text.length()>limit){int at=text.indexOf(quote);if(at>=0)start=Math.max(0,Math.min(at-600,text.length()-limit));}
        int end=Math.min(text.length(),start+limit);String excerpt=text.substring(start,end);remaining[0]-=excerpt.length();
        out.add(linked("kind",kind,"sourceHash",identity(text),"text",excerpt,"start",start,"end",end,"sourceLength",text.length(),"truncated",start>0||end<text.length(),"offsetBasis","CONTEXT_TEXT_UTF16"));
    }
    static Map<String,Object> requestFact(Fact fact){
        Map<String,Object> attrs=new LinkedHashMap<>();
        // Keep business values, not recursive server receipts or duplicated page transcripts.
        for(String key:cn.bidcheck.compat.Java8Api.listOf("category", "number", "numberField", "grade", "profession", "employer", "person", "months", "object", "start", "end", "field", "validityField", "contractName", "contractNumber", "personRole", "amount", "amountBasis", "taxBasis", "signedDate", "recordId"))
            if(fact.attributes().containsKey(key))attrs.put(key,fact.attributes().get(key));
        // Server proof receipts are retained for full-tree recomputation, but are not new
        // source text and should not be repeated inside every model-visible fact.
        for(String key:cn.bidcheck.compat.Java8Api.listOf("structureProof", "documentScopeEvidence", "documentScope"))attrs.remove(key);
        Map<String,Object> out=SharedFactReuse.value(fact);out.put("id",fact.id());out.put("attributes",attrs);return out;
    }
    static Set<String> kinds(Rule r){
        if(Boolean.TRUE.equals(r.parameters().get("sourceInventory"))&&!SourceRequirementInventory.applies(r))return Collections.emptySet();
        if(LegalRegistrationProof.applies(r))return cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE","FIELD","DECLARATION");
        if(SourceScoringRules.applies(r)){
            if(SourceScoringRules.issue(r)!=null)return Collections.emptySet();
            if(SourceScoringRules.programOnly(r))return cn.bidcheck.compat.Java8Api.setOf("SECTION");
            Set<String> reads=new LinkedHashSet<>();
            SourceScoringRules.evidenceRules(r).forEach(read->reads.addAll(kinds(read)));
            return reads;
        }
        if (PersonnelNameConsistency.applies(r)) return cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "FIELD");
        if(RegistrationEmployerProof.applies(r))return cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION", "CERTIFICATE");switch (r.predicate()) {case "PERSON_COUNT":case "ENTITY_COUNT":return cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION");case "VALIDITY":return cn.bidcheck.compat.Java8Api.setOf("DATE_RANGE", "CERTIFICATE");case "SAME_FIELD":return cn.bidcheck.compat.Java8Api.setOf("FIELD", "NUMBER", "CERTIFICATE");case "PRICE_RECONCILE":return cn.bidcheck.compat.Java8Api.setOf("NUMBER", "FIELD");case "DATE_RELATION":case "VISUAL":return cn.bidcheck.compat.Java8Api.setOf("FIELD");case "SCORE_COUNT":return cn.bidcheck.compat.Java8Api.setOf("CONTRACT");default:return ProofVerifier.KINDS.contains(r.predicate())?cn.bidcheck.compat.Java8Api.setOf(r.predicate()):cn.bidcheck.compat.Java8Api.setOf();}}
    private static Set<String> relationFields(Rule r){Set<String> fields=new LinkedHashSet<>();
        if(RegistrationEmployerProof.applies(r))return cn.bidcheck.compat.Java8Api.setOf("聘用", "受聘", "任职于", "就职于", "供职于", "劳动关系", "用人单位", "雇佣", "所属单位");
        if(r.predicate().equals("RELATION")){if(!cn.bidcheck.compat.Java8Api.isBlank(r.field()))fields.add(r.field());fields.addAll(ProofVerifier.strings(r.parameters().get("path")));}
        return fields;
    }
    static boolean relevant(Fact f,List<Rule> rules,Collection<Fact> facts,EvidenceCorpus corpus){
        for(Rule r:rules){
            if (PersonnelNameConsistency.applies(r)) {
                if (PersonnelNameConsistency.relevant(f)) return true;
                continue;
            }
            List<Map<String,Object>> sourceIdentities = new ArrayList<>();
            for (String id : f.sourceIds()) {
                Source source = corpus.byId.get(id);
                if (source != null) sourceIdentities.add(linked("id", id, "documentId", source.evidence().documentId(),
                    "documentHash", source.documentHash(), "documentVersion", source.documentVersion(), "location", source.evidence().page()));
            }
            if (!DeclaredContractRelevance.possiblyRelevant(linked("predicate", r.predicate(), "parameters", r.parameters()),
                    SharedFactReuse.value(f), sourceIdentities)) continue;
            // Identity links are prerequisites, never substitutes for the requested certificate or relationship.
            Set<String> selectors=new LinkedHashSet<>(AgentRuleSubjects.selectors(r));selectors.add(str(r.parameters(),"employer"));
            if(r.predicate().equals("RELATION"))selectors.add(cn.bidcheck.compat.Java8Api.isBlank(r.expected())?str(r.parameters(),"object"):r.expected());
            if(binding(f,selectors,corpus))return true;
            if(RegistrationEmployerProof.applies(r)){
                // This executor compares independent employment with the certificate's registered
                // employer. A registered-unit/delegation edge cannot establish employment.
                Set<String> persons=new LinkedHashSet<>(AgentRuleSubjects.subjects(r,facts,corpus));
                if(persons.contains(f.subject())&&!DomainBindings.role(f.subject())&&(f.kind().equals("CERTIFICATE")
                    ||f.kind().equals("RELATION")&&f.scope().equals("CURRENT_PROJECT")&&DomainBindings.employment(f.field())))return true;
                continue;
            }
            if(cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "PERSON_COUNT").contains(r.predicate())&&DomainBindings.employment(r.description()+r.sourceQuote())
                &&f.kind().equals("RELATION")&&DomainBindings.employment(f.field())&&f.scope().equals("CURRENT_PROJECT")
                &&AgentRuleSubjects.subjects(r,facts,corpus).contains(f.subject())
                &&DomainBindings.subjects(str(r.parameters(),"employer"),new ArrayList<>(facts),corpus).contains(str(f.attributes(),"object")))return true;
            if(!kinds(r).contains(f.kind()))continue;
            if(cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "PERSON_COUNT", "ENTITY_COUNT").contains(r.predicate()))continue;
            if(f.kind().equals("RELATION")&&!ProofVerifier.strings(r.parameters().get("path")).isEmpty()){
                if(pathEdge(r,f,facts,corpus))return true;continue;
            }
            if(!cn.bidcheck.compat.Java8Api.isBlank(r.subject())){
                Set<String> subjects=new LinkedHashSet<>(AgentRuleSubjects.subjects(r,facts,corpus));
                if(!AgentRuleSubjects.collective(r.subject()))subjects.add(r.subject());
                if(!subjects.contains(f.subject())&&!subjects.contains(str(f.attributes(),"person")))continue;
            }
            if(f.kind().equals("RELATION")&&!relationFields(r).contains(f.field()))continue;
            if(cn.bidcheck.compat.Java8Api.setOf("FIELD", "NUMBER").contains(f.kind())&&!cn.bidcheck.compat.Java8Api.isBlank(r.field())){
                Set<String> fields=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf(r.field()));fields.addAll(ProofVerifier.strings(r.parameters().get("componentFields")));
                fields.add(str(r.parameters(),"leftRole"));fields.add(str(r.parameters(),"rightRole"));
                if(!fields.contains(f.field()))continue;
            }
            return true;
        }
        return false;
    }
    private static boolean pathEdge(Rule r,Fact candidate,Collection<Fact> facts,EvidenceCorpus corpus){
        if(!candidate.scope().equals("CURRENT_PROJECT"))return false;
        List<String> path=ProofVerifier.strings(r.parameters().get("path"));Set<String> reached=new LinkedHashSet<>(AgentRuleSubjects.selectors(r));
        reached.addAll(AgentRuleSubjects.subjects(r,facts,corpus));
        for(String relation:path){
            if(relation.equals(candidate.field())&&reached.contains(candidate.subject()))return true;
            Set<String> next=new LinkedHashSet<>();for(Fact known:facts)if(known.kind().equals("RELATION")&&known.scope().equals("CURRENT_PROJECT")&&known.field().equals(relation)&&reached.contains(known.subject()))next.add(str(known.attributes(),"object"));
            reached=next;if(reached.isEmpty())return false;
        }
        return false;
    }
    private static boolean binding(Fact f,Set<String> selectors,EvidenceCorpus corpus){
        if(!f.scope().equals("CURRENT_PROJECT"))return false;
        for(String selector:selectors){if(!DomainBindings.role(selector))continue;
            if(f.kind().equals("PERSON_ROLE")&&DomainBindings.roleMatches(selector,f.subject(),f,corpus))return true;
            if(f.kind().equals("RELATION")&&DomainBindings.roleMatches(selector,f.subject(),f,corpus)
                &&cn.bidcheck.compat.Java8Api.setOf("为", "是", "名称", "对应主体", "成员名称", "：", ":").contains(f.field()))return true;
            if(f.kind().equals("RELATION")&&DomainBindings.roleMatches(selector,str(f.attributes(),"object"),f,corpus)
                &&cn.bidcheck.compat.Java8Api.setOf("身份", "角色", "成员角色", "作为", "担任", "为", "是", "拟在本合同任职", "在本项目中担任职务", "在本项目担任职务").contains(f.field()))return true;
        }
        return false;
    }
}
