package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Cited hypotheses are separate from extracted facts. Only verified normalizations bypass human review. */
final class InferenceClaims {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(InferenceClaims.class);
    static final String POLICY="cited-inference-2-programmatic-candidates";
    static final Set<String> TYPES=cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION", "CERTIFICATE", "DATE_RANGE", "NUMBER", "CONTRACT", "MONTHS", "MATERIAL");
    /** Runtime-only capability: JSON/model attributes cannot manufacture this approval. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class HumanConfirmation {
        private final String claimId;
        private final String actor;
        private final String decidedAt;
        private final String policy;
        private final String targetId;
        private final String targetFingerprint;
        @com.fasterxml.jackson.annotation.JsonCreator
        HumanConfirmation(@com.fasterxml.jackson.annotation.JsonProperty("claimId") String claimId, @com.fasterxml.jackson.annotation.JsonProperty("actor") String actor, @com.fasterxml.jackson.annotation.JsonProperty("decidedAt") String decidedAt, @com.fasterxml.jackson.annotation.JsonProperty("policy") String policy, @com.fasterxml.jackson.annotation.JsonProperty("targetId") String targetId, @com.fasterxml.jackson.annotation.JsonProperty("targetFingerprint") String targetFingerprint) { this.claimId = claimId; this.actor = actor; this.decidedAt = decidedAt; this.policy = policy; this.targetId = targetId; this.targetFingerprint = targetFingerprint; }
        public String claimId() { return claimId; }
        public String actor() { return actor; }
        public String decidedAt() { return decidedAt; }
        public String policy() { return policy; }
        public String targetId() { return targetId; }
        public String targetFingerprint() { return targetFingerprint; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; HumanConfirmation that = (HumanConfirmation) other; return java.util.Objects.equals(this.claimId, that.claimId) && java.util.Objects.equals(this.actor, that.actor) && java.util.Objects.equals(this.decidedAt, that.decidedAt) && java.util.Objects.equals(this.policy, that.policy) && java.util.Objects.equals(this.targetId, that.targetId) && java.util.Objects.equals(this.targetFingerprint, that.targetFingerprint); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.claimId); hash = 31 * hash + java.util.Objects.hashCode(this.actor); hash = 31 * hash + java.util.Objects.hashCode(this.decidedAt); hash = 31 * hash + java.util.Objects.hashCode(this.policy); hash = 31 * hash + java.util.Objects.hashCode(this.targetId); hash = 31 * hash + java.util.Objects.hashCode(this.targetFingerprint); return hash; }
        @Override public String toString() { return "HumanConfirmation[" + "claimId=" + claimId + ", actor=" + actor + ", decidedAt=" + decidedAt + ", policy=" + policy + ", targetId=" + targetId + ", targetFingerprint=" + targetFingerprint + "]"; }
}
    static boolean humanConfirmed(Fact fact){return fact.attributes().get("humanConfirmation") instanceof HumanConfirmation;}
    private static Map<String,Object> businessAttributes(Object raw){Map<String,Object> result=new LinkedHashMap<>();if(raw instanceof Map<?,?>)for(String key:cn.bidcheck.compat.Java8Api.listOf("person", "employer", "object", "number", "numberField", "category", "grade", "profession", "validFrom", "validTo", "validityField", "field", "start", "end", "months", "month", "amount", "currency", "taxBasis", "amountBasis", "contractNumber", "contractName", "party", "personRole", "signedDate", "completedDate", "issueDate", "quantity", "days", "period", "taxRate", "project", "lot", "unit", "role", "value", "documentId", "inventoryPage", "inventoryRow", "contractSourceIds", "originalAmount", "sourceFactId", "sourceContractFactId", "observedBasis", "targetAmountBasis")){
        Object value=((Map<?,?>) (raw)).get(key);if(value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof List<?>&&((List<?>) (value)).stream().allMatch(v->v instanceof String||v instanceof Number))result.put(key,value);
    }return result;}
    private static double normalizationThreshold(Map<String,Object> context){try{double threshold=Double.parseDouble(Objects.toString(context.getOrDefault("inferenceNormalizationMinConfidence","0.9")));return Double.isFinite(threshold)&&threshold>=0.9&&threshold<=1?threshold:Double.POSITIVE_INFINITY;}catch(RuntimeException ex){return Double.POSITIVE_INFINITY;}}
    @SuppressWarnings("unchecked") static Map<String,Object> submit(Map<String,Object> supplied,Rule target,EvidenceCorpus corpus,
            Map<String,Source> disclosed,Map<String,Fact> facts,Map<String,Object> context,SharedFactReuse shared,RunSession session){
        return submit(supplied,target,corpus,disclosed,facts,context,shared,session,false);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> submit(Map<String,Object> supplied,Rule target,EvidenceCorpus corpus,
            Map<String,Source> disclosed,Map<String,Fact> facts,Map<String,Object> context,SharedFactReuse shared,RunSession session,boolean programmatic){
        Map<String,Object> attrs=supplied.get("attributes") instanceof Map<?,?>?JSON.convertValue(((Map<?,?>) (supplied.get("attributes"))),Map.class):cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> inference=attrs.get("inference") instanceof Map<?,?>?JSON.convertValue(((Map<?,?>) (attrs.get("inference"))),Map.class):cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> proposed=attrs.get("proposedFact") instanceof Map<?,?>?JSON.convertValue(((Map<?,?>) (attrs.get("proposedFact"))),Map.class):cn.bidcheck.compat.Java8Api.mapOf();
        String claim=str(inference,"claim"),reason=str(inference,"reasoning");
        List<Map<String,Object>> citations=RuleEvaluator.maps(inference.get("evidence"));
        double confidence=inference.get("confidence") instanceof Number?((Number) (inference.get("confidence"))).doubleValue():-1;
        if(cn.bidcheck.compat.Java8Api.isBlank(claim)||claim.length()>3000||cn.bidcheck.compat.Java8Api.isBlank(reason)||reason.length()>6000||citations.isEmpty()||citations.size()>12
                ||!Double.isFinite(confidence)||confidence<0||confidence>1||!TYPES.contains(str(proposed,"kind")))return reject("INFERENCE_PROTOCOL_INVALID");
        if(!AgentInvestigationScope.kinds(target).contains(str(proposed,"kind"))&&!cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION").contains(str(proposed,"kind")))return reject("FACT_OUTSIDE_TARGET");
        List<Map<String,Object>> evidence=new ArrayList<>();List<String> sourceIds=new ArrayList<>();
        for(Map<String,Object> citation:citations){String id=str(citation,"sourceId"),quote=str(citation,"quote");Source source=disclosed.get(id);
            if(source==null||!corpus.byId.containsKey(id)||cn.bidcheck.compat.Java8Api.isBlank(quote)||quote.length()>14000||!source.evidence().quote().contains(quote))return reject("INFERENCE_CITATION_UNGROUNDED");
            sourceIds.add(id);evidence.add(linked("sourceId",id,"quote",quote,"documentId",source.evidence().documentId(),"page",source.evidence().page(),
                "locationType",source.evidence().locationType(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion()));
        }
        Map<String,Object> candidate=new LinkedHashMap<>(proposed);candidate.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(sourceIds.stream().distinct()));
        for(String field:cn.bidcheck.compat.Java8Api.listOf("subject", "field", "rawValue", "value", "unit", "basis"))candidate.putIfAbsent(field,"");
        candidate.putIfAbsent("scope","UNRESOLVED");candidate.put("attributes",businessAttributes(candidate.get("attributes")));
        if(cn.bidcheck.compat.Java8Api.isBlank(str(candidate,"subject"))||cn.bidcheck.compat.Java8Api.isBlank(str(candidate,"field"))||cn.bidcheck.compat.Java8Api.isBlank(str(candidate,"value")))return reject("INFERENCE_TYPED_FACT_REQUIRED");
        if(!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").contains(str(candidate,"scope")))return reject("INFERENCE_TYPED_FACT_REQUIRED");
        if("CURRENT_PROJECT".equals(str(candidate,"scope"))&&sourceIds.stream().map(corpus.byId::get).anyMatch(s->s.historical()||s.index()||s.scopeContext().blocked()))return reject("INFERENCE_CURRENT_SCOPE_CONFLICT");
        boolean contractEdge=programmatic&&str(candidate,"kind").equals("RELATION")&&cn.bidcheck.compat.Java8Api.setOf("CONTRACT_RECORD_MATCH", "CONTRACT_AMOUNT_BASIS").contains(str(candidate,"field"))
            &&cn.bidcheck.compat.Java8Api.setOf("CONTRACT", "SCORE_COUNT", "SAME_FIELD").contains(target.predicate())
            &&!cn.bidcheck.compat.Java8Api.isBlank(str(businessAttributes(candidate.get("attributes")),"object"))
            &&(!str(candidate,"field").equals("CONTRACT_RECORD_MATCH")||sourceIds.stream().distinct().count()>1);
        if(!contractEdge&&!AgentInvestigationScope.candidateSubjectIssue(candidate,cn.bidcheck.compat.Java8Api.listOf(target)).isEmpty())return reject("FACT_OUTSIDE_TARGET");
        Fact proposedFact=new Fact("candidate",str(candidate,"kind"),str(candidate,"subject"),str(candidate,"field"),str(candidate,"rawValue"),str(candidate,"value"),str(candidate,"unit"),str(candidate,"basis"),str(candidate,"scope"),sourceIds,businessAttributes(candidate.get("attributes")));
        boolean identityEdge=proposedFact.kind().equals("RELATION")&&cn.bidcheck.compat.Java8Api.setOf("同一人", "SAME_PERSON", "身份对应").contains(proposedFact.field())
            &&!cn.bidcheck.compat.Java8Api.isBlank(SubjectIdentity.person(proposedFact))&&!DomainBindings.role(str(proposedFact.attributes(),"object"))&&!cn.bidcheck.compat.Java8Api.isBlank(str(proposedFact.attributes(),"object"))
            &&(target.subject().equals(proposedFact.subject())||AgentRuleSubjects.subjects(target,facts.values(),corpus).contains(proposedFact.subject()))
            &&citations.stream().anyMatch(c->str(c,"quote").contains(proposedFact.subject()))&&citations.stream().anyMatch(c->str(c,"quote").contains(str(proposedFact.attributes(),"object")));
        boolean employerEdge=target.predicate().equals("MONTHS")&&PersonnelEmployerBindings.deictic(str(target.parameters(),"employer"))&&proposedFact.kind().equals("RELATION")&&DomainBindings.employment(proposedFact.field())
            &&!cn.bidcheck.compat.Java8Api.isBlank(SubjectIdentity.person(proposedFact))&&str(proposedFact.attributes(),"object").matches(".*(?:公司|集团|事务所|合作社|合伙企业)$")&&proposedFact.scope().equals("CURRENT_PROJECT")
            &&(target.subject().equals(proposedFact.subject())||AgentRuleSubjects.subjects(target,facts.values(),corpus).contains(proposedFact.subject()))
            &&citations.stream().anyMatch(c->str(c,"quote").contains(proposedFact.subject()))&&citations.stream().anyMatch(c->str(c,"quote").contains(str(proposedFact.attributes(),"object")));
        if(!identityEdge&&!employerEdge&&!contractEdge&&!AgentInvestigationScope.relevant(proposedFact,cn.bidcheck.compat.Java8Api.listOf(target),facts.values(),corpus))return reject("FACT_OUTSIDE_TARGET");
        // Confidence is only descriptive. The allow-list is a deterministic normalization with a normal provenance check.
        boolean grade=confidence>=normalizationThreshold(context)&&"GRADE_NORMALIZATION".equals(str(inference,"type"))&&"CERTIFICATE".equals(str(candidate,"kind"))
            &&grade(str(candidate,"rawValue")).equals(grade(str(candidate,"value")))&&!str(candidate,"rawValue").equals(str(candidate,"value"));
        if(grade){
            cn.bidcheck.verification.engine.ProofVerifier.Verified verified=new ProofVerifier(corpus.byId).verify(JSON.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(candidate))),new ArrayList<>(disclosed.values()),RuleEvaluator.maps(context.get("_validatedLayoutCells")));
            List<Fact> normalized=cn.bidcheck.compat.Java8Api.toList(verified.facts().stream().filter(f->AgentInvestigationScope.relevant(f,cn.bidcheck.compat.Java8Api.listOf(target),facts.values(),corpus)).map(SharedFactReuse::canonical));
            if(!normalized.isEmpty()){normalized.forEach(f->facts.put(f.id(),f));if(shared!=null)shared.publish(normalized,new ArrayList<>(disclosed.values()),cn.bidcheck.compat.Java8Api.listOf(target),cn.bidcheck.compat.Java8Api.listOf(),false,"NORMALIZATION");
                Map<String,Object> receipt=linked("status","COMPLETED","acceptedFactIds",cn.bidcheck.compat.Java8Api.toList(normalized.stream().map(Fact::id)),"inferencePolicy",POLICY,"normalizationRule","GRADE_NORMALIZATION","businessProof",false);
                audit(context,session,"INFERENCE_NORMALIZATION",cn.bidcheck.compat.Java8Api.mapOf("targetId", target.id()),receipt);return receipt;}
        }
        String id=identity(POLICY+target.id()+JSON.valueToTree(candidate)+JSON.valueToTree(evidence));
        if(context.get("inferenceDecisionsLookup") instanceof Supplier<?>)for(Map<String,Object> previous:RuleEvaluator.maps(((Supplier<?>) (context.get("inferenceDecisionsLookup"))).get())){
            if(id.equals(str(previous,"engineClaimId"))&&"REJECTED".equals(str(previous,"status")))return linked("status","REJECTED","error","INFERENCE_PREVIOUSLY_REJECTED","acceptedFactIds",cn.bidcheck.compat.Java8Api.listOf(),"confirmationId",previous.get("id"),"reason","同一原文与推断已由用户否定；只有相关证据或事实内容改变后才可重新提出。","businessProof",false);
            if(id.equals(str(previous,"engineClaimId"))&&"CONFIRMED".equals(str(previous,"status")))return linked("status","COMPLETED","acceptedFactIds",cn.bidcheck.compat.Java8Api.listOf(),"confirmationId",previous.get("id"),"reason","该推断已在本轮冻结的确认快照中；复用已有确认事实，只处理剩余业务缺口。","businessProof",false);
        }
        Map<String,Object> record=linked("id",id,"targetId",target.id(),"targetFingerprint",identity(JSON.valueToTree(target).toString()),"claim",claim,"reasoning",reason,
            "confidence",confidence,"risks",ProofVerifier.strings(inference.get("risks")),"evidences",evidence,"proposedFact",candidate,"status","NEEDS_CONFIRMATION","revision",0,"policy",POLICY,
            "requirement",target.sourceQuote(),"targetDescription",target.description(),"origin",programmatic?ProgrammaticInferenceCandidates.VERSION:"AGENT_INFERRED","inferenceType",str(inference,"type"),"unresolvedGapCodes",ProofVerifier.strings(inference.get("unresolvedGapCodes")));
        List<Map<String,Object>> pending=(List<Map<String,Object>>)context.computeIfAbsent("inferredFacts",key->new ArrayList<Map<String,Object>>());
        if(pending.stream().noneMatch(r->id.equals(str(r,"id"))))pending.add(record);
        if(context.get("inferencePublisher") instanceof Consumer<?>)((Consumer<Map<String,Object>>)((Consumer<?>) (context.get("inferencePublisher")))).accept(record);
        audit(context,session,"INFERENCE_PENDING_CONFIRMATION",cn.bidcheck.compat.Java8Api.mapOf("targetId", target.id(), "claimId", id),record);
        return linked("status","NEEDS_CONFIRMATION","acceptedFactIds",cn.bidcheck.compat.Java8Api.listOf(),"confirmationId",id,"businessProof",false,"pendingConfirmation",record,
            "nextAction","已保存有原文依据的推断，等待用户确认；不得把它当作已采纳事实或重复改写提交。");
    }
    private static String grade(String s){return clean(s).replace("壹","一").replace("贰","二").replace("叁","三").replace("肆","四");}
    private static Map<String,Object> reject(String code){return linked("status","REJECTED","error",code,"correctable",true,"acceptedFactIds",cn.bidcheck.compat.Java8Api.listOf(),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf("READ_SOURCE_AND_SUBMIT_CITED_TYPED_INFERENCE"));}
    @SuppressWarnings("unchecked") private static void audit(Map<String,Object> context,RunSession session,String type,Map<String,Object> input,Map<String,Object> output){
        if(session!=null)session.record(type,input,output);
        else if(context.get("inferenceAudit") instanceof Consumer<?>)((Consumer<Map<String,Object>>)((Consumer<?>) (context.get("inferenceAudit")))).accept(linked("type",type,"input",input,"output",output));
        LOG.info("bidcheck.inference_candidate targetId={} origin={} event={} status={} errorCode={} claimId={} sourceCount={}",input.getOrDefault("targetId",""),output.getOrDefault("origin",""),type,output.getOrDefault("status",""),output.getOrDefault("error",""),output.getOrDefault("id",output.getOrDefault("confirmationId","")),RuleEvaluator.maps(output.get("evidences")).size());
    }
    /** Called after deterministic evaluation. No model turn is needed to expose a grounded ambiguity. */
    @SuppressWarnings("unchecked") static Decision discover(Rule rule,Decision decision,List<Fact> suppliedFacts,EvidenceCorpus corpus,Map<String,Object> context){
        if(!rule.children().isEmpty()||rule.predicate().equals("GROUP"))return pending(rule,decision,context);
        if(!(context.get("inferencePublisher") instanceof Consumer<?>)&&!Boolean.TRUE.equals(context.get("enableProgrammaticInferences")))return pending(rule,decision,context);
        List<Map<String,Object>> candidates=ProgrammaticInferenceCandidates.collect(rule,decision,suppliedFacts,corpus);
        Set<String> tried=(Set<String>)context.computeIfAbsent("_programmaticInferenceAttempts",key->new HashSet<String>());
        if(candidates.isEmpty()&&cn.bidcheck.compat.Java8Api.setOf("UNDETERMINED", "NEEDS_CONFIRMATION").contains(decision.status())&&tried.add("no-candidate:"+rule.id()+decision.gaps()))
            audit(context,null,"INFERENCE_PROGRAMMATIC_SKIPPED",linked("targetId",rule.id(),"gapCodes",cn.bidcheck.compat.Java8Api.toList(decision.gaps().stream().map(Gap::code)),"suppliedFactCount",suppliedFacts.size()),
                linked("status","NOT_CREATED","reason",ProgrammaticInferenceCandidates.technical(decision)?"TECHNICAL_FAILURE_IS_NOT_A_CONFIRMATION":"NO_UNIQUE_SOURCE_BACKED_CONFIRMATION_CANDIDATE","origin",ProgrammaticInferenceCandidates.VERSION));
        Map<String,Fact> facts=new LinkedHashMap<>();suppliedFacts.forEach(f->facts.put(f.id(),f));
        for(java.util.Map<java.lang.String, java.lang.Object> candidate:candidates){
            String signature=identity(rule.id()+JSON.valueToTree(candidate));if(!tried.add(signature))continue;
            Map<String,Object> inference=new LinkedHashMap<>(candidate);inference.remove("proposedFact");
            Map<String,Object> envelope=linked("kind","INFERRED","attributes",linked("inference",inference,"proposedFact",candidate.get("proposedFact")));
            Map<String,Source> disclosed=new LinkedHashMap<>();for(java.util.Map<java.lang.String, java.lang.Object> citation:RuleEvaluator.maps(candidate.get("evidence"))){Source source=corpus.byId.get(str(citation,"sourceId"));if(source!=null)disclosed.put(source.id(),source);}
            Map<String,Object> result=submit(envelope,rule,corpus,disclosed,facts,context,null,null,true);
            audit(context,null,"INFERENCE_PROGRAMMATIC_CANDIDATE",linked("targetId",rule.id(),"type",candidate.getOrDefault("type",""),"sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(disclosed.keySet()),"candidate",candidate),result);
        }
        return pending(rule,decision,context);
    }
    static Decision pending(Rule rule,Decision decision,Map<String,Object> context){
        if(!"UNDETERMINED".equals(decision.status()))return decision;
        List<Map<String,Object>> claims=cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(context.get("inferredFacts")).stream().filter(c->rule.id().equals(str(c,"targetId"))));
        if(claims.isEmpty())return decision;
        Map<String,Object> proof=new LinkedHashMap<>(decision.proof());proof.put("inferredFacts",claims);
        // A proposal cannot erase a failed request, an uncompiled obligation, or another missing field.
        boolean numeric=claims.stream().map(c->c.get("proposedFact")).filter(Map.class::isInstance).map(c->JSON.convertValue(c,Map.class)).anyMatch(c->"NUMBER".equals(str(c,"kind"))&&!cn.bidcheck.compat.Java8Api.isBlank(str(c,"basis"))&&!cn.bidcheck.compat.Java8Api.isBlank(str(c,"unit")));
        boolean semanticOnly=!decision.gaps().isEmpty()&&decision.gaps().stream().allMatch(g->numeric&&cn.bidcheck.compat.Java8Api.setOf("NUMERIC_BASIS_REQUIRED", "PRICE_DIMENSIONS_REQUIRED", "PRICE_SCOPE_AMBIGUOUS").contains(g.code())||cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE_REQUIRED", "PERSON_IDENTITY_REQUIRED", "PERSON_IDENTITY_UNPROVEN", "PERSON_IDENTITY_AMBIGUOUS", "SUBJECT_IDENTITY_UNPROVEN", "RELATION_REQUIRED", "RELATION_UNCONFIRMED", "CONFLICTING_EVIDENCE", "PERSON_APPOINTMENT_REQUIRED", "PERSON_APPOINTMENT_UNCONFIRMED", "SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED", "CERTIFICATE_SUBJECT_REQUIRED", "CERTIFICATE_IDENTITY_REQUIRED", "IDENTITY_REQUIRED", "PERSONNEL_BINDING_REQUIRED", "CURRENT_APPOINTMENT_REQUIRED", "APPOINTMENT_EMPLOYER_REQUIRED", "SUBJECT_RELATION_UNPROVEN", "RELATION_TARGET_UNPROVEN", "EMPLOYMENT_RELATION_REQUIRED", "ENTITY_IDENTITY_AMBIGUOUS", "SUBJECT_IDENTITY_CONFLICT").contains(g.code()));
        boolean coveredProgrammatic=!ProgrammaticInferenceCandidates.technical(decision)&&Boolean.TRUE.equals(decision.proof().get("confirmationCoversAllGaps"))
            &&decision.gaps().stream().allMatch(g->claims.stream().anyMatch(c->ProgrammaticInferenceCandidates.VERSION.equals(str(c,"origin"))&&ProofVerifier.strings(c.get("unresolvedGapCodes")).contains(g.code())));
        if(!semanticOnly&&!coveredProgrammatic)return new Decision(decision.id(),decision.description(),decision.status(),decision.reason(),decision.evidences(),decision.gaps(),proof);
        return new Decision(decision.id(),decision.description(),"NEEDS_CONFIRMATION","已找到相关原文，存在需要确认的推断关系。",decision.evidences(),cn.bidcheck.compat.Java8Api.listOf(new Gap("INFERENCE_CONFIRMATION_REQUIRED","请确认有原文依据的推断后重新计算。","确认或否定推断关系","USER_CONFIRMATION")),proof);
    }
    /** A confirmed scope fills only unknown dimensions on the exact same numerical occurrence.
     * Original facts stay in the run ledger; conflicting values/dimensions are never hidden. */
    static boolean numericSuperseded(Fact original,List<Fact> candidates){
        if(humanConfirmed(original)||!original.kind().equals("NUMBER"))return false;
        Map<String,String> originalDimensions=DomainBindings.priceDimensions(original);
        for(Fact confirmed:candidates){if(!humanConfirmed(confirmed)||!confirmed.kind().equals("NUMBER")||!original.subject().equals(confirmed.subject())||!original.field().equals(confirmed.field())
            ||!original.rawValue().equals(confirmed.rawValue())||!original.value().equals(confirmed.value())||!original.unit().equals(confirmed.unit())
            ||!new HashSet<>(original.sourceIds()).equals(new HashSet<>(confirmed.sourceIds())))continue;
            Map<String,String> filled=DomainBindings.priceDimensions(confirmed);boolean gain=false,conflict=false;
            for(String key:originalDimensions.keySet()){String before=originalDimensions.get(key),after=filled.get(key);if(DomainBindings.known(before)&&!before.equals(after)){conflict=true;break;}if(!DomainBindings.known(before)&&DomainBindings.known(after))gain=true;}
            if(!conflict&&gain)return true;
        }return false;
    }
    @SuppressWarnings("unchecked") static List<Fact> confirmed(Map<String,Object> context,EvidenceCorpus corpus){
        if(!(context.get("confirmedInferenceLookup") instanceof Supplier<?>))return cn.bidcheck.compat.Java8Api.listOf();
        List<Fact> result=new ArrayList<>();
        for(Map<String,Object> record:RuleEvaluator.maps(((Supplier<?>) (context.get("confirmedInferenceLookup"))).get())){
            List<Map<String,Object>> evidence=RuleEvaluator.maps(record.get("evidences"));
            if(!"CONFIRMED".equals(str(record,"status"))||evidence.isEmpty())continue;
            boolean valid=evidence.stream().allMatch(e->{Source s=corpus.byId.get(str(e,"sourceId"));return s!=null&&s.documentHash().equals(str(e,"documentHash"))&&s.documentVersion()==RunSession.number(e.get("documentVersion"),-1)&&!cn.bidcheck.compat.Java8Api.isBlank(str(e,"quote"))&&s.evidence().quote().contains(str(e,"quote"));});
            if(!valid)continue;
            Map<String,Object> candidate=JSON.convertValue(record.get("proposedFact"),Map.class);if(!TYPES.contains(str(candidate,"kind")))continue;
            if("CURRENT_PROJECT".equals(str(candidate,"scope"))&&evidence.stream().map(e->corpus.byId.get(str(e,"sourceId"))).anyMatch(s->s.historical()||s.index()||s.scopeContext().blocked()))continue;
            Map<String,Object> attrs=businessAttributes(candidate.get("attributes"));
            attrs.put("humanConfirmation",new HumanConfirmation(str(record,"id"),str(record,"actor"),str(record,"decidedAt"),POLICY,str(record,"targetId"),str(record,"targetFingerprint")));
            result.add(new Fact("FC"+str(record,"id"),str(candidate,"kind"),str(candidate,"subject"),str(candidate,"field"),str(candidate,"rawValue"),str(candidate,"value"),str(candidate,"unit"),str(candidate,"basis"),str(candidate,"scope"),cn.bidcheck.compat.Java8Api.toList(evidence.stream().map(e->str(e,"sourceId")).distinct()),attrs));
        }return result;
    }
}
