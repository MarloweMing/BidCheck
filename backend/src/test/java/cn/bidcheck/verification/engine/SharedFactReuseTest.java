package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Checkpoint;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedFactReuseTest {
    final ObjectMapper json=new ObjectMapper();
    static Document doc(String id,String project,int version,String... texts){List<Page> pages=new ArrayList<>();for(int i=0;i<texts.length;i++)pages.add(new Page(i+1,texts[i],"PAGE","PDF",""));return new Document(id,project,"BID",id+".pdf",version,"hash-"+id,"",pages,List.of());}
    static Checkpoint cp(String id){return new Checkpoint(id,"报价","商务","HIGH","VALUE_COMPARE","amount","<=","100","元",true,true,true,null);}
    static Rule rule(){return new Rule("O1","报价","NUMBER","","amount","<=","100","元",Map.of(),"100","",List.of());}
    static Map<String,Object> number(Source source,String value){return linked("id","cached-number","kind","NUMBER","subject","","field","amount","rawValue",value,"value",value,"unit","元","basis","报价","scope","CURRENT_PROJECT","sourceIds",List.of(source.id()),"attributes",Map.of());}
    static Map<String,Object> source(EvidenceCorpus corpus,Source source){Document d=corpus.documents.stream().filter(x->x.id().equals(source.evidence().documentId())).findFirst().orElseThrow();Page p=corpus.pages.get(EvidenceCorpus.pageKey(d.id(),source.evidence().page(),source.evidence().locationType()));return linked("id",source.id(),"evidence",source.evidence(),"projectId",d.projectId(),"documentHash",d.sha256(),"documentVersion",d.version(),"parserFingerprint",SharedFactReuse.parserFingerprint(p));}
    static Map<String,Object> entry(EvidenceCorpus corpus,Map<String,Object> fact){return linked("fact",fact,"sources",corpus.all.stream().map(s->source(corpus,s)).toList(),"engineVersion",VERSION,"operationIdentity","operation");}
    SharedFactReuse reuse(EvidenceCorpus c,List<Map<String,Object>> entries){return new SharedFactReuse(c,Map.of("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)(q)->entries),"model","engine");}
    @Test void revalidatesTypedValuesAgainstCurrentOriginal(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));Map<String,Object> f=number(c.all.get(0),"80");f.put("value","90");SharedFactReuse cache=reuse(c,List.of(entry(c,f)));assertTrue(cache.lookup(List.of(rule()),c.all,List.of()).isEmpty());assertEquals(1,cache.rejectedFacts);}
    @Test void rejectsOldVersionEvenWhenFileHashUnchanged(){EvidenceCorpus old=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元"))),current=new EvidenceCorpus(List.of(doc("d","p",2,"报价：80元")));assertTrue(reuse(current,List.of(entry(old,number(old.all.get(0),"80")))).lookup(List.of(rule()),current.all,List.of()).isEmpty());}
    @Test void rejectsOtherProjectEvenWithCollidingDocumentId(){EvidenceCorpus old=new EvidenceCorpus(List.of(doc("d","foreign",1,"报价：80元"))),current=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));assertEquals(old.all.get(0).id(),current.all.get(0).id());assertTrue(reuse(current,List.of(entry(old,number(old.all.get(0),"80")))).lookup(List.of(rule()),current.all,List.of()).isEmpty());}
    @Test void reparseFingerprintPreventsReuseWithoutChangingDocumentId(){Document d=doc("d","p",1,"报价：80元");EvidenceCorpus old=new EvidenceCorpus(List.of(d));Document reparsed=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),List.of(new Page(1,"报价：80元","PAGE","OCR-v2","")),List.of());EvidenceCorpus current=new EvidenceCorpus(List.of(reparsed));assertTrue(reuse(current,List.of(entry(old,number(old.all.get(0),"80")))).lookup(List.of(rule()),current.all,List.of()).isEmpty());}
    @Test void sameNamedPersonCannotBorrowAnotherEmployer(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"姓名：同名甲\n注册证书\n单位名称：乙公司\n姓名：另一人\n单位名称：甲公司")));Map<String,Object> f=linked("id","certificate","kind","CERTIFICATE","subject","同名甲","field","certificate","rawValue","注册证书","value","注册证书","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(c.all.get(0).id()),"attributes",Map.of("employer","甲公司"));Rule r=new Rule("r","证书","CERTIFICATE","同名甲","","","","",Map.of("employer","甲公司"),"证书","",List.of());assertTrue(reuse(c,List.of(entry(c,f))).lookup(List.of(r),c.all,List.of()).isEmpty());}
    @Test void sameNameInUnselectedDocumentDoesNotCreateCrossSourceIdentity(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("a","p",1,"本项目项目负责人：同名甲"),doc("b","p",1,"本项目项目负责人：同名甲")));Source a=c.all.get(0),b=c.all.get(1);Map<String,Object> f=linked("id","role","kind","PERSON_ROLE","subject","项目负责人","field","role","rawValue","同名甲","value","同名甲","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(a.id()),"attributes",Map.of());Rule r=new Rule("r","本项目负责人","PERSON_ROLE","项目负责人","role","","","",Map.of(),"本项目负责人","",List.of());assertTrue(reuse(c,List.of(entry(c,f))).lookup(List.of(r),List.of(b),List.of()).isEmpty());}
    @Test void ignoresSectionAndQualityAndVisualFacts(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"技术方案\n"+"实质技术正文。".repeat(20))));for(String kind:List.of("SECTION","QUALITY")){Map<String,Object> f=number(c.all.get(0),"80");f.put("kind",kind);assertTrue(reuse(c,List.of(entry(c,f))).lookup(List.of(rule()),c.all,List.of()).isEmpty());}Map<String,Object> f=number(c.all.get(0),"80");f.put("sourceIds",List.of("Vimage"));assertTrue(reuse(c,List.of(entry(c,f))).lookup(List.of(rule()),c.all,List.of()).isEmpty());}
    @Test void emptyLookupCannotProveMissingMaterial(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"本文件未涉及相应材料")));Rule r=new Rule("r","提交证明","MATERIAL","","","","许可证","",Map.of(),"许可证","",List.of());SharedFactReuse cache=reuse(c,List.of());List<Fact> facts=cache.lookup(List.of(r),c.all,List.of());assertEquals("UNDETERMINED",new RuleEvaluator(facts,c,Map.of()).evaluate(r).status());assertEquals(1,cache.extractionRules(List.of(r),c.all,new RuleEvaluator(facts,c,Map.of())).size());}

    final class MemoryLedger {
        final List<Map<String,Object>> batches=new ArrayList<>();
        Map<String,Object> context(){return Map.of("sharedFactPublisher",(Consumer<Map<String,Object>>)batches::add,"sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)this::lookup);}
        List<Map<String,Object>> lookup(Map<String,Object> request){List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> batch:batches){List<Map<String,Object>> facts=RuleEvaluator.maps(batch.get("facts"));for(Map<String,Object> fact:facts){Map<String,Object> item=linked("fact",fact,"sources",batch.get("sources"),"engineVersion",batch.get("engineVersion"),"observationPolicy",batch.getOrDefault("observationPolicy",""),"operationIdentity",batch.get("operationIdentity"),"batchId",batch.get("operationIdentity"),"coverageComplete",true,"fieldCoverage",batch.getOrDefault("fieldCoverage",List.of()),"fieldCoverageSources",batch.get("sources"));if(batch.containsKey("extractionCoverage"))item.put("extractionCoverage",batch.get("extractionCoverage"));out.add(item);}if(facts.isEmpty()&&batch.containsKey("fieldCoverage"))out.add(linked("recordType","FIELD_COVERAGE","engineVersion",batch.get("engineVersion"),"observationPolicy",batch.getOrDefault("observationPolicy",""),"batchId",batch.get("operationIdentity"),"fieldCoverage",batch.get("fieldCoverage"),"fieldCoverageSources",batch.get("sources")));}return out;}
    }
    ModelGateway numericModel(AtomicInteger extracts,boolean fail){return new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "model";}public JsonNode request(String op,Map<String,Object> input)throws IOException{assertEquals("EXTRACT",op);extracts.incrementAndGet();if(fail)throw new IOException("unavailable");List<Map<String,Object>> facts=new ArrayList<>();for(Map<String,Object> row:RuleEvaluator.maps(input.get("sources"))){String quote=str(row,"quote");java.util.regex.Matcher m=java.util.regex.Pattern.compile("报价[：:]([0-9]+)元").matcher(quote);while(m.find())facts.add(linked("kind","NUMBER","subject","","field","amount","rawValue",m.group(1),"value",m.group(1),"unit","元","basis","报价","scope","CURRENT_PROJECT","sourceIds",List.of(str(row,"id")),"attributes",Map.of()));}return json.valueToTree(Map.of("facts",facts));}};}
    @Test void repeatedCheckpointRecomputesProofAndSkipsDuplicateExtraction(){MemoryLedger ledger=new MemoryLedger();AtomicInteger calls=new AtomicInteger();VerificationEngine e=new VerificationEngine(groundedPriceModel(calls,false),null);Document d=priceDoc("d","80");Map<String,Object> first=e.evaluate(priceCheckpoint("first"),List.of(d),null,ledger.context());assertEquals("PASS",first.get("businessDecision"),first.toString());Map<String,Object> context=new HashMap<>(ledger.context());context.put("priorSteps",first.get("steps"));Map<String,Object> second=e.evaluate(priceCheckpoint("second"),List.of(d),null,context);assertEquals("PASS",second.get("businessDecision"),second.toString());assertEquals(1,calls.get());Map<?,?> metrics=(Map<?,?>)second.get("metrics");assertEquals(1,metrics.get("reusedFacts"));assertEquals(1,metrics.get("extractionSkipped"));assertEquals(0,metrics.get("modelCalls"));assertTrue((Integer)metrics.get("sharedPublishes")>0,"adoption registers current checkpoint usage");}
    @Test void durableOperationResultIsActuallyReadByTheTextExtractionPath(){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));Rule rule=pricedRule("100");List<Map<String,Object>> published=new ArrayList<>();
        new SharedFactReuse(corpus,Map.of("sharedFactPublisher",(Consumer<Map<String,Object>>)published::add),"model","engine")
            .publish(List.of(pricedFact(corpus)),corpus.all,List.of(rule),List.of(),true,"EXTRACTED");
        Map<String,Object> batch=published.stream().filter(row->row.containsKey("extractionCoverage")).findFirst().orElseThrow();AtomicInteger lookups=new AtomicInteger();
        Function<Map<String,Object>,Map<String,Object>> durable=request->{lookups.incrementAndGet();assertEquals(batch.get("evidenceOperationKey"),request.get("operationKey"));return linked(
            "facts",batch.get("facts"),"sources",batch.get("sources"),"fieldCoverage",batch.get("fieldCoverage"),"persistentCacheHit",true);};
        SharedFactReuse reused=new SharedFactReuse(corpus,Map.of("evidenceOperationLookup",durable),"model","engine");List<Fact> facts=reused.lookup(List.of(rule),corpus.all,List.of());
        assertEquals(1,lookups.get());assertEquals(1,facts.size());RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of());
        assertEquals("PASS",evaluator.evaluate(rule).status());assertTrue(reused.extractionRules(List.of(rule),corpus.all,evaluator).isEmpty());
    }
    @Test void additionalSourceStillGetsExtractedAndConflictingPriceIsNotPassed(){MemoryLedger ledger=new MemoryLedger();AtomicInteger calls=new AtomicInteger();VerificationEngine e=new VerificationEngine(numericModel(calls,false),null);Document original=doc("d","p",1,"报价：80元");e.evaluate(cp("first"),List.of(original),null,ledger.context());Map<String,Object> next=e.evaluate(cp("second"),List.of(original,doc("supplement","p",1,"报价：120元")),null,ledger.context());assertTrue(calls.get()>1);assertNotEquals("PASS",next.get("businessDecision"));assertTrue(((Integer)((Map<?,?>)next.get("metrics")).get("reusedFacts"))>0);}
    @Test void newSourceFailureCannotFallBackToCachedPass(){MemoryLedger ledger=new MemoryLedger();Document d=priceDoc("d","80");Map<String,Object> first=new VerificationEngine(groundedPriceModel(new AtomicInteger(),false),null).evaluate(priceCheckpoint("first"),List.of(d),null,ledger.context());assertEquals("PASS",first.get("businessDecision"),first.toString());Map<String,Object> next=new VerificationEngine(groundedPriceModel(new AtomicInteger(),true),null).evaluate(priceCheckpoint("second"),List.of(d,priceDoc("new","120")),null,ledger.context());assertEquals("UNDETERMINED",next.get("businessDecision"));assertTrue(RuleEvaluator.maps(next.get("gaps")).stream().anyMatch(g->str(g,"code").equals("SHARED_SOURCE_REVIEW_REQUIRED")));}

    // Cache PASS fixtures must satisfy the same explicit price dimensions as production comparisons.
    private static final String PRICE_REQUIREMENT="甲公司本项目不划分标段，人民币含税投标总报价不得超过100元。";
    static Checkpoint priceCheckpoint(String id){return new Checkpoint(id,"报价","商务","HIGH","SEMANTIC_RESPONSE","amount","<=",PRICE_REQUIREMENT,"元",true,true,true,null);}
    static Document priceDoc(String id,String amount){return doc(id,"p",1,"本项目甲公司，不划分标段，人民币含税投标总报价："+amount+"元。");}
    ModelGateway groundedPriceModel(AtomicInteger extracts,boolean fail){return new ModelGateway(){
        public boolean enabled(){return true;}public String identity(){return "grounded-price-cache-fixture";}
        public JsonNode request(String op,Map<String,Object> input)throws IOException{
            if(op.equals("COMPILE"))return json.valueToTree(Map.of("logic","ALL","rules",List.of(linked("id","O1","description","报价","predicate","NUMBER","subject","甲公司","field","amount","operator","<=","expected","100","unit","元","sourceQuote",PRICE_REQUIREMENT,"parameters",Map.of("lot","不划分标段","currency","人民币","amountBasis","投标总报价","taxBasis","含税")))));
            assertEquals("EXTRACT",op);extracts.incrementAndGet();if(fail)throw new IOException("unavailable");List<Map<String,Object>> facts=new ArrayList<>();
            for(Map<String,Object> row:RuleEvaluator.maps(input.get("sources"))){var m=java.util.regex.Pattern.compile("投标总报价[：:]([0-9]+)元").matcher(str(row,"quote"));while(m.find())facts.add(linked("kind","NUMBER","subject","甲公司","field","amount","rawValue",m.group(1),"value",m.group(1),"unit","元","basis","投标总报价","scope","CURRENT_PROJECT","sourceIds",List.of(str(row,"id")),"attributes",Map.of("lot","不划分标段","currency","人民币","taxBasis","含税")));}
            return json.valueToTree(Map.of("facts",facts));
        }
    };}
    @Test void partialBatchCannotAuthorizeExtractionSkip(){MemoryLedger ledger=new MemoryLedger();AtomicInteger calls=new AtomicInteger();VerificationEngine e=new VerificationEngine(numericModel(calls,false),null);Document d=doc("d","p",1,"报价：80元\n报价：80元");e.evaluate(cp("first"),List.of(d),null,ledger.context());Map<String,Object> batch=ledger.batches.stream().filter(b->b.containsKey("extractionCoverage")).findFirst().orElseThrow();Map<String,Object> coverage=(Map<String,Object>)batch.get("extractionCoverage");coverage.put("factIds",List.of("unreturned-fact"));List<Map<String,Object>> fields=RuleEvaluator.maps(batch.get("fieldCoverage"));fields.forEach(row->row.put("factIds",List.of("unreturned-fact")));batch.put("fieldCoverage",fields);e.evaluate(cp("second"),List.of(d),null,ledger.context());assertEquals(2,calls.get());}
    @Test void publisherLeaseLossPropagatesInsteadOfFinishingWithCachedOrNewFact(){AtomicInteger calls=new AtomicInteger();Map<String,Object> ctx=Map.of("sharedFactPublisher",(Consumer<Map<String,Object>>)(batch)->{throw new IllegalStateException("lease lost");});IllegalStateException ex=assertThrows(IllegalStateException.class,()->new VerificationEngine(numericModel(calls,false),null).evaluate(cp("c"),List.of(doc("d","p",1,"报价：80元")),null,ctx));assertEquals("lease lost",ex.getMessage());assertEquals(0,calls.get(),"lost lease prevents even the pending-observation write and paid extraction");}

    Rule pricedRule(String threshold){return new Rule("price","报价","NUMBER","甲公司","amount","<=",threshold,"元",Map.of("lot","不划分标段","currency","人民币","amountBasis","投标总报价","taxBasis","含税"),PRICE_REQUIREMENT,"",List.of());}
    Fact pricedFact(EvidenceCorpus corpus){return new Fact("price","NUMBER","甲公司","amount","80","80","元","投标总报价","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of("lot","不划分标段","currency","人民币","taxBasis","含税"));}
    Rule identifiedPricedRule(String threshold,String lot){
        Rule base=pricedRule(threshold);String quote=PRICE_REQUIREMENT.replace("100",threshold).replace("不划分标段",lot);Map<String,Object> parameters=new LinkedHashMap<>(base.parameters());parameters.put("lot",lot);
        Rule bound=new RequirementContextBindings(quote,"ORIGINAL_REQUIREMENT").bind(new Rule(base.id(),base.description(),base.predicate(),base.subject(),base.field(),base.operator(),threshold,base.unit(),parameters,quote,base.logic(),base.children()),List.of());
        var sourceContext=new RequirementSourceContext.Context(quote,linked("projectId","p","documentId","tender","documentHash",identity(quote),"documentVersion",1,"locationType","PAGE","location",1),List.of(Map.of("kind","SECTION_SUBJECT","sourceQuote",quote,"subject","甲公司")));
        Map<String,Object> contextual=new LinkedHashMap<>(bound.parameters());contextual.put("requirementSourceContext",sourceContext.map());contextual.put("priceConstraintSources",Map.of("definition",Map.of("value",threshold,"sourceQuote",quote,"start",0,"end",quote.length())));
        bound=new Rule(bound.id(),bound.description(),bound.predicate(),bound.subject(),bound.field(),bound.operator(),bound.expected(),bound.unit(),contextual,bound.sourceQuote(),bound.logic(),bound.children());
        RequirementArtifact artifact=new RequirementArtifact(new RunSession(ModelGateway.disabled(),Map.of(),"artifact-fixture"),Map.of("expected",threshold),sourceContext);
        return artifact.identify(new Plan("ALL",List.of(bound),"FIXTURE",List.of())).rules().get(0);
    }
    @Test void realRequirementIdentityAndSourceBindingsDoNotInvalidateSameBidObservationOnThresholdChange(){
        Rule first=identifiedPricedRule("100","不划分标段"),changed=identifiedPricedRule("50","不划分标段");
        assertNotEquals(first.parameters().get("obligationIdentity"),changed.parameters().get("obligationIdentity"));assertNotEquals(first.parameters().get("requirementBindings"),changed.parameters().get("requirementBindings"));assertNotEquals(first.parameters().get("requirementSourceContext"),changed.parameters().get("requirementSourceContext"));
        assertEquals(SharedFactReuse.observationTarget(first),SharedFactReuse.observationTarget(changed));assertNotEquals(SharedFactReuse.observationTarget(first),SharedFactReuse.observationTarget(identifiedPricedRule("50","第一标段")));
        MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));new SharedFactReuse(corpus,ledger.context(),"model","v1").publish(List.of(pricedFact(corpus)),corpus.all,List.of(first),List.of(),true,"EXTRACTED");
        SharedFactReuse current=new SharedFactReuse(corpus,ledger.context(),"model","v2");List<Fact> facts=current.lookup(List.of(changed),corpus.all,List.of());RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of("originalRequirement",changed.sourceQuote()));assertEquals("FAIL",evaluator.evaluate(changed).status());assertTrue(current.extractionRules(List.of(changed),corpus.all,evaluator).isEmpty());
    }
    @Test void observationIdentityPreservesBusinessQualifiersDespiteRemovingProvenance(){Rule original=identifiedPricedRule("100","不划分标段");for(String field:List.of("lot","currency","taxBasis","amountBasis","evaluationStage","employer")){Map<String,Object> changed=new LinkedHashMap<>(original.parameters());changed.put(field,"另一业务口径");Rule other=new Rule(original.id(),original.description(),original.predicate(),original.subject(),original.field(),original.operator(),original.expected(),original.unit(),changed,original.sourceQuote(),original.logic(),original.children());assertNotEquals(SharedFactReuse.observationTarget(original),SharedFactReuse.observationTarget(other),field);}}
    @Test void changedNumericThresholdRecomputesFailWithoutReextractingVerifiedValue(){
        MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));
        new SharedFactReuse(corpus,ledger.context(),"model","rule-version-1").publish(List.of(pricedFact(corpus)),corpus.all,List.of(pricedRule("100")),List.of(),true,"EXTRACTED");
        SharedFactReuse current=new SharedFactReuse(corpus,ledger.context(),"model","rule-version-2");Rule changed=pricedRule("50");List<Fact> facts=current.lookup(List.of(changed),corpus.all,List.of());RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of());
        assertEquals("FAIL",evaluator.evaluate(changed).status());assertTrue(current.extractionRules(List.of(changed),corpus.all,evaluator).isEmpty());assertEquals(1,current.extractionSkipped);
    }
    @Test void oneRejectedFieldDoesNotEraseOtherFieldsCompleteReceipt(){
        MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));Rule price=pricedRule("100"),cert=new Rule("cert","证书","CERTIFICATE","甲公司","license","","许可证","",Map.of(),"许可证","",List.of());
        SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"model","engine");first.publish(List.of(pricedFact(corpus)),corpus.all,List.of(price,cert),List.of(),false,"EXTRACTED",List.of(Map.of("kind","CERTIFICATE","field","license","code","RAW_VALUE_UNGROUNDED")));
        assertEquals(List.of("EXTRACTED","FAILED"),first.coverage().stream().map(row->row.get("status")).toList());assertFalse(ledger.batches.get(0).containsKey("extractionCoverage"));
        SharedFactReuse next=new SharedFactReuse(corpus,ledger.context(),"model","engine");List<Fact> facts=next.lookup(List.of(price,cert),corpus.all,List.of());assertTrue(next.extractionRules(List.of(price),corpus.all,new RuleEvaluator(facts,corpus,Map.of())).isEmpty());
    }
    @Test void rejectedValueOfSameFieldPreventsUsingSingleSurvivorAsCompleteCoverage(){
        MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));Rule price=pricedRule("100");
        new SharedFactReuse(corpus,ledger.context(),"model","engine").publish(List.of(pricedFact(corpus)),corpus.all,List.of(price),List.of(),false,"EXTRACTED",List.of(Map.of("kind","NUMBER","field","amount","code","RAW_VALUE_UNGROUNDED")));
        SharedFactReuse next=new SharedFactReuse(corpus,ledger.context(),"model","engine");List<Fact> facts=next.lookup(List.of(price),corpus.all,List.of());assertEquals(1,facts.size());assertEquals(1,next.extractionRules(List.of(price),corpus.all,new RuleEvaluator(facts,corpus,Map.of())).size());
    }
    @Test void noFactResponseIsRecordedAsReadNotFoundButNeverProvesMissingMaterial(){
        MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"本页为说明正文")));Rule material=new Rule("r","许可证","MATERIAL","","","","许可证","",Map.of(),"许可证","",List.of());
        SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"model","engine");first.publish(List.of(),corpus.all,List.of(material),List.of(),true,"EXTRACTED");assertEquals("READ_NOT_FOUND",first.coverage().get(0).get("status"));
        SharedFactReuse next=new SharedFactReuse(corpus,ledger.context(),"model","engine");List<Fact> facts=next.lookup(List.of(material),corpus.all,List.of());assertTrue(facts.isEmpty());assertEquals("UNDETERMINED",new RuleEvaluator(facts,corpus,Map.of()).evaluate(material).status());assertTrue(next.extractionRules(List.of(material),corpus.all,new RuleEvaluator(facts,corpus,Map.of())).isEmpty(),"Already read windows are reusable while business absence remains unproved");
        assertEquals(false,next.coverage().get(0).get("absenceProven"));
    }
    @Test void protocolFailureCannotBeRecordedAsReadNotFound(){MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));SharedFactReuse shared=new SharedFactReuse(corpus,ledger.context(),"model","engine");shared.publish(List.of(),corpus.all,List.of(pricedRule("100")),List.of(),false,"EXTRACTED",List.of(Map.of("code","FACT_PROTOCOL_INVALID")));assertEquals("FAILED",shared.coverage().get(0).get("status"));}
    @Test void oldPendingReceiptsCannotOverwriteCompletedCachedObservation(){MemoryLedger ledger=new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(priceDoc("d","80")));Rule rule=pricedRule("100");SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"model","engine");first.publishState(List.of(rule),corpus.all,"UNREAD","pending");first.publish(List.of(pricedFact(corpus)),corpus.all,List.of(rule),List.of(),true,"EXTRACTED");Collections.reverse(ledger.batches);SharedFactReuse next=new SharedFactReuse(corpus,ledger.context(),"model","engine");next.lookup(List.of(rule),corpus.all,List.of());assertEquals("EXTRACTED",next.coverage().get(0).get("status"));next.publishState(List.of(rule),corpus.all,"FAILED","CURRENT_FAILURE");assertEquals("FAILED",next.coverage().get(0).get("status"));}
    @Test void transportFailureLeavesFailedFieldReceiptWithoutInventingFacts(){MemoryLedger ledger=new MemoryLedger();Map<String,Object> result=new VerificationEngine(numericModel(new AtomicInteger(),true),null).evaluate(cp("c"),List.of(doc("d","p",1,"报价：80元")),null,ledger.context());assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(RuleEvaluator.maps(result.get("fieldCoverage")).stream().allMatch(row->"FAILED".equals(row.get("status"))));assertTrue(ledger.batches.stream().anyMatch(b->"UNREAD".equals(b.get("origin"))));assertTrue(ledger.batches.stream().anyMatch(b->"FAILED".equals(b.get("origin"))));}
    @Test void reusedFactPublisherFailureAlsoPropagates(){EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));Map<String,Object> ctx=Map.of("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)(q)->List.of(entry(c,number(c.all.get(0),"80"))),"sharedFactPublisher",(Consumer<Map<String,Object>>)(batch)->{throw new IllegalStateException("lease lost");});assertThrows(IllegalStateException.class,()->new SharedFactReuse(c,ctx,"model","engine").lookup(List.of(rule()),c.all,List.of()));}

    Map<String,Object> processingOccurrence(EvidenceCorpus corpus,Source original,String quote){
        Map<String,Object> stored=new LinkedHashMap<>(source(corpus,original));stored.put("id","P1");
        var evidence=original.evidence();stored.put("evidence",new cn.bidcheck.domain.Domain.Evidence(evidence.documentId(),evidence.documentName(),evidence.page(),evidence.locationType(),quote,"native-field:amount",0));
        Map<String,Object> fact=number(original,"80");fact.put("sourceIds",List.of("P1"));
        return linked("occurrenceId","page-occurrence","projectId",stored.get("projectId"),"fact",fact,"sources",List.of(stored),"engineVersion","page-facts-1");
    }
    @Test void processingFactRemapsExactNarrowQuoteToCurrentSourceWithoutClaimingExtractionCoverage(){
        EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价说明\n报价：80元\n其他正文")));
        Map<String,Object> occurrence=processingOccurrence(c,c.all.get(0),"报价：80元");List<Map<String,Object>> publications=new ArrayList<>();
        Map<String,Object> context=Map.of("processingOccurrences",List.of(occurrence),"sharedFactPublisher",(Consumer<Map<String,Object>>)publications::add);
        SharedFactReuse shared=new SharedFactReuse(c,context,"model","engine");List<Fact> facts=shared.lookup(List.of(rule()),c.all,List.of());
        assertEquals(1,facts.size());assertEquals(List.of(c.all.get(0).id()),facts.get(0).sourceIds());assertEquals(1,shared.reusedFacts());
        assertEquals(1,shared.extractionRules(List.of(rule()),c.all,new RuleEvaluator(facts,c,Map.of())).size());assertEquals(0,shared.extractionSkipped);
        assertEquals(1,publications.size());assertFalse(publications.get(0).containsKey("extractionCoverage"));
    }
    @Test void processingFactCannotMoveToAnotherPageWithIdenticalText(){
        EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元","报价：80元")));
        Map<String,Object> occurrence=processingOccurrence(c,c.all.get(0),"报价：80元");
        SharedFactReuse shared=new SharedFactReuse(c,Map.of("processingOccurrences",List.of(occurrence)),"model","engine");
        assertTrue(shared.lookup(List.of(rule()),List.of(c.all.get(1)),List.of()).isEmpty());assertEquals(1,shared.rejectedFacts);
    }
    @Test void processingFactRequiresSameHashVersionProjectAndParserFingerprint(){
        EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));
        for(String key:List.of("documentHash","documentVersion","projectId","parserFingerprint")){
            Map<String,Object> occurrence=processingOccurrence(c,c.all.get(0),"报价：80元");Map<String,Object> stored=RuleEvaluator.maps(occurrence.get("sources")).get(0);
            stored.put(key,key.equals("documentVersion")?2:"wrong");occurrence.put("sources",List.of(stored));
            SharedFactReuse shared=new SharedFactReuse(c,Map.of("processingOccurrences",List.of(occurrence)),"model","engine");
            assertTrue(shared.lookup(List.of(rule()),c.all,List.of()).isEmpty(),key);assertEquals(1,shared.rejectedFacts,key);
        }
    }
    @Test void widerCurrentSourceCannotRepairWrongNumberBindingInNarrowProcessingEvidence(){
        EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元\n折扣：80元")));
        Map<String,Object> occurrence=processingOccurrence(c,c.all.get(0),"折扣：80元");
        SharedFactReuse shared=new SharedFactReuse(c,Map.of("processingOccurrences",List.of(occurrence)),"model","engine");
        assertTrue(shared.lookup(List.of(rule()),c.all,List.of()).isEmpty());assertEquals(1,shared.rejectedFacts);
    }
    @Test void processingCoverageFlagsAndPassValuesHaveNoAuthority(){
        EvidenceCorpus c=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));
        Map<String,Object> occurrence=processingOccurrence(c,c.all.get(0),"报价：80元");occurrence.put("coverageComplete",true);
        occurrence.put("businessDecision","PASS");occurrence.put("extractionCoverage",Map.of("status","VALIDATED","modelIdentity","model","ruleSignatures",List.of(SharedFactReuse.signature(rule())),"sourceIds",List.of(c.all.get(0).id()),"factIds",List.of("cached-number")));
        SharedFactReuse shared=new SharedFactReuse(c,Map.of("processingOccurrences",List.of(occurrence)),"model","engine");List<Fact> facts=shared.lookup(List.of(rule()),c.all,List.of());
        assertEquals(1,facts.size());assertEquals(1,shared.extractionRules(List.of(rule()),c.all,new RuleEvaluator(facts,c,Map.of())).size());
    }
}
