package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.verification.facts.FieldCoverage;
import cn.bidcheck.retrieval.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class RecoveryFieldCoverageTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String MODEL="recovery-read-test";
    private Rule rule(){return new Rule("R1","提交守法承诺书","DECLARATION","","material","EXISTS","守法承诺书","",Map.of(),"提交守法承诺书","",List.of());}
    private Checkpoint cp(){return new Checkpoint("cp","提交守法承诺书","组成检查","HIGH","SEMANTIC_RESPONSE","material","EXISTS","提交守法承诺书","",true,true,true,null);}
    private Document bid(){return new Document("bid","p","BID","投标.pdf",1,"frozen-hash","",List.of(new Page(1,"本项目投标文件\n守法承诺书相关材料索引\n工程概况：本项目拟进行室内装修，施工地点及实施范围按招标文件执行，保证按计划落实工作。","PAGE","PDF_TEXT","")),List.of());}
    private Map<String,Object> field(Rule rule,List<String> refs,String state,List<String> factIds){
        Map<String,Object> target=SharedFactReuse.observationTarget(rule);
        return linked("observationKey",FieldCoverage.key(target),"target",target,"status",state,"sourceIds",refs,"factIds",factIds,
            "sourceScopeComplete",Set.of("READ_NOT_FOUND","EXTRACTED").contains(state),"observationPolicy",FieldCoverage.POLICY,"modelIdentity",MODEL,"scope","SELECTED_SOURCES_ONLY","absenceProven",false);
    }
    private Map<String,Object> context(Document doc,Rule rule,String state,List<Fact> facts){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc));List<String> refs=corpus.all.stream().map(Source::id).toList();
        Map<String,Object> plan=linked("logic","ALL","rules",List.of(JSON.convertValue(rule,Map.class)),"issues",List.of());
        Map<String,Object> previous=linked("plan",plan,"metrics",Map.of("engineVersion",VERSION),"fieldCoverage",List.of(field(rule,refs,state,facts.stream().map(Fact::id).toList())),"businessDecision","PASS");
        RunSession session=session(doc);session.record("EVIDENCE_PLAN_0",Map.of(),Map.of("candidates",corpus.inputs(corpus.all)));
        Map<String,Object> binding=RecoveryFieldCoverage.readBinding("selection","packet",List.of(rule),refs);
        session.record("FACT_VALIDATION_0",binding,linked("facts",facts,"sourceIds",refs,"readBinding",binding));
        return linked("recoveryMode","RECOMPUTE_ONLY","recoveryModelAllowed",false,"recoveryTargetObligationIds",List.of(rule.id()),"allowModelRuleCompilation",true,"previousAtomicCheck",previous,
            "priorSteps",session.steps);
    }
    private RunSession session(Document doc){return new RunSession(new RecordingModel(),Map.of(),identity(cp().id()+doc.id()+":"+doc.version()+":"+doc.sha256()+":"+identity(doc.pages().toString())));}
    private RecoveryFieldCoverage.Replayed replay(Map<String,Object> context,Document doc,List<Rule> rules,List<Fact> facts){return RecoveryFieldCoverage.replay(context,rules,new EvidenceCorpus(List.of(doc)),facts,session(doc));}
    @SuppressWarnings("unchecked") private Map<String,Object> previous(Map<String,Object> context){return (Map<String,Object>)context.get("previousAtomicCheck");}
    private class RecordingModel implements ModelGateway {
        int compile,extract;boolean forbidExtract;final List<List<String>> requestedSources=new ArrayList<>();
        public boolean enabled(){return true;}public String identity(){return MODEL;}
        public JsonNode request(String operation,Map<String,Object> input){
            if("COMPILE".equals(operation)){compile++;return JSON.valueToTree(Map.of("logic","ALL","rules",List.of(rule())));}
            assertEquals("EXTRACT",operation);assertFalse(forbidExtract,"Already validated read must not trigger EXTRACT");extract++;
            requestedSources.add(ExtractionBatches.sourceIds(input));return JSON.valueToTree(Map.of("facts",List.of()));
        }
    }
    @Test void localEngineRetainsCompleteReadWhileNegativeReadDoesNotProveBusinessPass(){
        Document doc=bid();Map<String,Object> context=context(doc,rule(),"READ_NOT_FOUND",List.of());
        ModelGateway forbidden=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return MODEL;}public JsonNode request(String operation,Map<String,Object> input){fail(operation);return null;}};
        HybridRetriever retrieval=new HybridRetriever(){@Override public boolean enabled(){return true;}@Override public RetrievalResult search(Document d,String query,int limit){fail("Local recovery queried retrieval");return null;}};
        Map<String,Object> result=new VerificationEngine(forbidden,retrieval).evaluate(cp(),List.of(doc),null,context);
        assertEquals("UNDETERMINED",result.get("businessDecision"),"Old PASS and read-not-found cannot establish compliance");
        assertEquals(1,((Map<?,?>)result.get("metrics")).get("completedSourceFieldReads"));
        List<Map<String,Object>> receipts=RuleEvaluator.maps(result.get("fieldCoverage"));assertEquals(1,receipts.size());
        assertEquals(false,receipts.get(0).get("absenceProven"));assertEquals(true,receipts.get(0).get("recoveryRevalidated"));
        assertEquals(0,((Map<?,?>)result.get("metrics")).get("modelCalls"));
    }
    @Test void deterministicMaterialRuleDoesNotTreatAnIndexFollowedByAnotherSectionAsBody(){
        Map<String,Object> result=new VerificationEngine(ModelGateway.disabled(),null).evaluate(cp(),List.of(bid()),null,Map.of());
        assertEquals("UNDETERMINED",result.get("businessDecision"),JSON.valueToTree(result).toString());
        assertTrue(RuleEvaluator.maps(result.get("facts")).stream().noneMatch(f->"MATERIAL".equals(f.get("kind"))));
    }
    @Test void targetedEngineDoesNotExtractAnAlreadyReadUnresolvedFieldAgain(){
        Document doc=bid();RecordingModel first=new RecordingModel();Map<String,Object> initial=new VerificationEngine(first,null).evaluate(cp(),List.of(doc),null,Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true));
        assertEquals(1,first.extract,JSON.valueToTree(initial).toString());assertFalse(RuleEvaluator.maps(initial.get("fieldCoverage")).isEmpty());
        RecordingModel second=new RecordingModel();second.forbidExtract=true;
        Map<String,Object> context=linked("recoveryMode","TARGETED","recoveryStrategy","TARGETED_EVIDENCE","recoveryTargetObligationIds",List.of("R1"),"unlimitedModelCalls",true,"allowModelRuleCompilation",true,
            "previousAtomicCheck",JSON.convertValue(initial,Map.class),"priorSteps",initial.get("steps"));
        Map<String,Object> result=new VerificationEngine(second,null).evaluate(cp(),List.of(doc),null,context);
        assertEquals(0,second.extract);assertEquals("UNDETERMINED",result.get("businessDecision"),JSON.valueToTree(result).toString());
        assertEquals(1,((Map<?,?>)result.get("metrics")).get("completedSourceFieldReads"));
    }
    @Test void changedFrozenVersionCannotSuppressTargetedExtraction(){
        Document old=bid();Map<String,Object> context=context(old,rule(),"READ_NOT_FOUND",List.of());context.put("recoveryMode","TARGETED");context.remove("recoveryModelAllowed");context.put("unlimitedModelCalls",true);
        Document changed=new Document(old.id(),old.projectId(),old.role(),old.name(),2,"new-hash","",old.pages(),old.warnings());
        RecordingModel model=new RecordingModel();Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp(),List.of(changed),null,context);
        assertEquals(1,model.extract,JSON.valueToTree(result).toString());assertEquals(new EvidenceCorpus(List.of(changed)).all.stream().map(Source::id).toList(),model.requestedSources.get(0));
        assertEquals("UNDETERMINED",result.get("businessDecision"));
    }
    @Test void currentSourceMustMatchBothFingerprintAndOriginalQuote(){
        Document doc=bid();Map<String,Object> context=context(doc,rule(),"READ_NOT_FOUND",List.of());
        var steps=RuleEvaluator.maps(context.get("priorSteps"));Map<String,Object> evidence=new LinkedHashMap<>(steps.get(0));
        List<Map<String,Object>> supplied=RuleEvaluator.maps(((Map<?,?>)evidence.get("output")).get("candidates"));Map<String,Object> forged=new LinkedHashMap<>(supplied.get(0));forged.put("quote","另一份原文");
        evidence.put("output",Map.of("candidates",List.of(forged)));context.put("priorSteps",List.of(evidence,steps.get(1)));
        assertTrue(replay(context,doc,List.of(rule()),List.of()).receipts().isEmpty());
    }
    @Test void changedRuleModelEngineOrReceiptPolicyCannotReuseCompleteRead(){
        Document doc=bid();Rule other=new Rule("R1",rule().description(),rule().predicate(),rule().subject(),rule().field(),"OTHER",rule().expected(),rule().unit(),rule().parameters(),rule().sourceQuote(),"",List.of());
        assertTrue(replay(context(doc,rule(),"READ_NOT_FOUND",List.of()),doc,List.of(other),List.of()).receipts().isEmpty(),"Same observation target cannot authorize changed business-rule fingerprint");
        for(String change:List.of("modelIdentity","observationPolicy","engineVersion")){
            Map<String,Object> context=context(doc,rule(),"READ_NOT_FOUND",List.of());Map<String,Object> previous=previous(context);
            if(change.equals("engineVersion"))previous.put("metrics",Map.of("engineVersion","old"));
            else{Map<String,Object> receipt=new LinkedHashMap<>(RuleEvaluator.maps(previous.get("fieldCoverage")).get(0));receipt.put(change,"foreign");previous.put("fieldCoverage",List.of(receipt));}
            assertTrue(replay(context,doc,List.of(rule()),List.of()).receipts().isEmpty(),change);
        }
    }
    @Test void unreadFailedAndIncompletePositiveReceiptsNeverBecomeCompletedReads(){
        for(String state:List.of("UNREAD","FAILED"))assertTrue(replay(context(bid(),rule(),state,List.of()),bid(),List.of(rule()),List.of()).receipts().isEmpty(),state);
        Map<String,Object> context=context(bid(),rule(),"READ_NOT_FOUND",List.of());Map<String,Object> receipt=new LinkedHashMap<>(RuleEvaluator.maps(previous(context).get("fieldCoverage")).get(0));receipt.put("sourceScopeComplete",false);previous(context).put("fieldCoverage",List.of(receipt));
        assertTrue(replay(context,bid(),List.of(rule()),List.of()).receipts().isEmpty());
    }
    @Test void extractedReceiptNeedsRevalidatedMatchingFactsAndTheirExactSourceChain(){
        Document doc=bid();String source=new EvidenceCorpus(List.of(doc)).all.get(0).id();
        Fact fact=SharedFactReuse.canonical(new Fact("old","DECLARATION","","material","守法承诺书","守法承诺书","","","CURRENT_PROJECT",List.of(source),Map.of()));
        Map<String,Object> context=context(doc,rule(),"EXTRACTED",List.of(fact));
        assertEquals(1,replay(context,doc,List.of(rule()),List.of(fact)).receipts().size());
        assertTrue(replay(context,doc,List.of(rule()),List.of()).receipts().isEmpty(),"A cached accepted flag cannot replace current ProofVerifier output");
        Fact wrong=new Fact(fact.id(),"MATERIAL","","material",fact.rawValue(),fact.value(),"","","CURRENT_PROJECT",List.of(source),Map.of());
        assertTrue(replay(context,doc,List.of(rule()),List.of(wrong)).receipts().isEmpty());
        Fact outside=new Fact(fact.id(),fact.kind(),fact.subject(),fact.field(),fact.rawValue(),fact.value(),"","","CURRENT_PROJECT",List.of("outside"),Map.of());
        assertTrue(replay(context,doc,List.of(rule()),List.of(outside)).receipts().isEmpty());
    }
    @Test void fullEngineRejectsAnExtractedReceiptWhoseFactFailsCurrentSourceValidation(){
        Document doc=bid();String source=new EvidenceCorpus(List.of(doc)).all.get(0).id();
        Fact invented=SharedFactReuse.canonical(new Fact("old","DECLARATION","","material","未出现在原文中的虚构守法承诺书","未出现在原文中的虚构守法承诺书","","","CURRENT_PROJECT",List.of(source),Map.of()));
        RecordingModel model=new RecordingModel();model.forbidExtract=true;
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp(),List.of(doc),null,context(doc,rule(),"EXTRACTED",List.of(invented)));
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(RuleEvaluator.maps(result.get("facts")).isEmpty());
        assertEquals(0,((Map<?,?>)result.get("metrics")).get("completedSourceFieldReads"));
        assertTrue(RuleEvaluator.maps(result.get("fieldCoverage")).isEmpty());assertEquals(0,model.compile);assertEquals(0,model.extract);
    }
    @Test void fullEngineRetainsAnExtractedReceiptOnlyAfterRevalidatingItsOriginalFact(){
        Document old=bid();Document doc=new Document(old.id(),old.projectId(),old.role(),old.name(),old.version(),old.sha256(),"",
            List.of(new Page(1,"本项目投标文件\n守法承诺书\n本单位承诺遵守本项目相关法律法规，依法履行全部投标义务并对提交材料承担责任。","PAGE","PDF_TEXT","")),List.of());
        String source=new EvidenceCorpus(List.of(doc)).all.get(0).id();
        Fact fact=SharedFactReuse.canonical(new Fact("old","DECLARATION","","material","守法承诺书","守法承诺书","","","CURRENT_PROJECT",List.of(source),Map.of()));
        RecordingModel model=new RecordingModel();model.forbidExtract=true;
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp(),List.of(doc),null,context(doc,rule(),"EXTRACTED",List.of(fact)));
        assertEquals("PASS",result.get("businessDecision"));assertEquals(1,((Map<?,?>)result.get("metrics")).get("completedSourceFieldReads"));
        assertEquals(true,RuleEvaluator.maps(result.get("fieldCoverage")).get(0).get("recoveryRevalidated"));assertEquals(0,model.compile);assertEquals(0,model.extract);
    }
    @Test void aNewCandidateWindowStaysPendingWhileTheOldWindowRemainsRead(){
        Document old=bid();Document expanded=new Document(old.id(),old.projectId(),old.role(),old.name(),old.version(),old.sha256(),"",List.of(old.pages().get(0),new Page(2,"本项目补充说明：新增窗口仍需独立读取。","PAGE","PDF_TEXT","")),List.of());
        // The frozen corpus stays identical; only the candidate selection expands after recovery.
        Map<String,Object> context=context(expanded,rule(),"READ_NOT_FOUND",List.of());EvidenceCorpus corpus=new EvidenceCorpus(List.of(expanded));
        List<String> originalRefs=List.of(corpus.all.get(0).id());previous(context).put("fieldCoverage",List.of(field(rule(),originalRefs,"READ_NOT_FOUND",List.of())));
        RunSession previousSession=session(expanded);previousSession.record("EVIDENCE_PLAN_0",Map.of(),Map.of("candidates",corpus.inputs(List.of(corpus.all.get(0)))));
        Map<String,Object> binding=RecoveryFieldCoverage.readBinding("selection","packet",List.of(rule()),originalRefs);
        previousSession.record("FACT_VALIDATION_0",binding,linked("facts",List.of(),"sourceIds",originalRefs,"readBinding",binding));context.put("priorSteps",previousSession.steps);
        SharedFactReuse shared=new SharedFactReuse(corpus,context,MODEL,"engine");shared.restoreRecoveryCoverage(replay(context,expanded,List.of(rule()),List.of()));
        assertEquals(1,shared.completedReads().size());assertEquals(Set.of(corpus.all.get(1).id()),shared.pendingSources(rule(),corpus.all));
    }
    @Test void evidencePlanWithoutCompletedExtractionPacketDoesNotProveReadCoverage(){
        Map<String,Object> context=context(bid(),rule(),"READ_NOT_FOUND",List.of());context.put("priorSteps",List.of(RuleEvaluator.maps(context.get("priorSteps")).get(0)));
        assertTrue(replay(context,bid(),List.of(rule()),List.of()).receipts().isEmpty());
    }
    @Test void aSourceReadForAnotherRuleDoesNotAuthorizeThisFieldsNegativeReceipt(){
        Document doc=bid();Map<String,Object> context=context(doc,rule(),"READ_NOT_FOUND",List.of());
        Rule other=new Rule("R2","提交履约承诺书","DECLARATION","","performance","EXISTS","履约承诺书","",Map.of(),"提交履约承诺书","",List.of());
        previous(context).put("plan",Map.of("logic","ALL","rules",List.of(JSON.convertValue(rule(),Map.class),JSON.convertValue(other,Map.class))));
        List<String> refs=new EvidenceCorpus(List.of(doc)).all.stream().map(Source::id).toList();previous(context).put("fieldCoverage",List.of(field(other,refs,"READ_NOT_FOUND",List.of())));
        assertTrue(replay(context,doc,List.of(rule(),other),List.of()).receipts().isEmpty(),"R1 read cannot cover R2 even when R2 is in the prior plan and shares the source");
    }
    @Test void missingOrTamperedReadBindingAndFailedValidationPacketCannotRestoreCoverage(){
        for(String change:List.of("missing","tampered","failed")){
            Document doc=bid();Map<String,Object> context=context(doc,rule(),"READ_NOT_FOUND",List.of());List<Map<String,Object>> steps=RuleEvaluator.maps(context.get("priorSteps"));
            Map<String,Object> packet=new LinkedHashMap<>(steps.get(1)),output=new LinkedHashMap<>((Map<String,Object>)packet.get("output"));
            if(change.equals("missing"))output.remove("readBinding");
            if(change.equals("tampered")){Map<String,Object> binding=new LinkedHashMap<>((Map<String,Object>)output.get("readBinding"));binding.put("packetId","not-the-sent-packet");output.put("readBinding",binding);}
            if(change.equals("failed"))packet.put("status","FAILED");
            packet.put("output",output);context.put("priorSteps",List.of(steps.get(0),packet));
            assertTrue(replay(context,doc,List.of(rule()),List.of()).receipts().isEmpty(),change);
        }
    }
    @Test void relatedOrUnknownValidationFailureCannotBecomeACompletedReadButOtherFieldsRemainReusable(){
        for(String variant:List.of("related","unknownKind","unknownField","otherField","otherSource")){
            Document doc=bid();Map<String,Object> context=context(doc,rule(),"READ_NOT_FOUND",List.of());List<Map<String,Object>> steps=RuleEvaluator.maps(context.get("priorSteps"));
            Map<String,Object> packet=new LinkedHashMap<>(steps.get(1)),output=new LinkedHashMap<>((Map<String,Object>)packet.get("output"));
            String source=new EvidenceCorpus(List.of(doc)).all.get(0).id();
            Map<String,Object> rejected=linked("kind",variant.equals("unknownKind")?"":"DECLARATION","field",variant.equals("unknownField")?"":variant.equals("otherField")?"unrelated":"material","sourceIds",List.of(variant.equals("otherSource")?"outside":source),"code","SOURCE_VALUE_NOT_FOUND");
            output.put("rejected",List.of(rejected));packet.put("output",output);context.put("priorSteps",List.of(steps.get(0),packet));
            assertEquals(Set.of("otherField","otherSource").contains(variant)?1:0,replay(context,doc,List.of(rule()),List.of()).receipts().size(),variant);
        }
    }
}
