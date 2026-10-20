package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.SharedFactReuseTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationReuseProgressTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void successfulFieldReadIsReusableDespiteUnresolvedBusinessScope(){
        var fixture=new SharedFactReuseTest();var ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"model","engine");
        Fact fact=new Fact("v","NUMBER","","amount","80","80","元","报价","UNRESOLVED",List.of(corpus.all.get(0).id()),Map.of());first.publish(List.of(fact),corpus.all,List.of(rule()),List.of(),true,"EXTRACTED");
        SharedFactReuse second=new SharedFactReuse(corpus,ledger.context(),"model","engine");List<Fact> facts=second.lookup(List.of(rule()),corpus.all,List.of());RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of());assertEquals("UNDETERMINED",evaluator.evaluate(rule()).status());assertTrue(second.extractionRules(List.of(rule()),corpus.all,evaluator).isEmpty());assertEquals(1,facts.size());
    }
    @Test void sourceWindowUnionSkipsOverlapButNeverUnseenWindow(){
        var fixture=new SharedFactReuseTest();var ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"说明甲","说明乙","说明丙")));List<Source> prior=corpus.all.subList(0,2),next=corpus.all.subList(1,3);
        SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"model","engine");first.publish(List.of(),prior,List.of(rule()),List.of(),true,"EXTRACTED");SharedFactReuse second=new SharedFactReuse(corpus,ledger.context(),"model","engine");assertTrue(second.lookup(List.of(rule()),next,List.of()).isEmpty());assertEquals(Set.of(corpus.all.get(2).id()),second.pendingSources(rule(),next));assertEquals("UNDETERMINED",new RuleEvaluator(List.of(),corpus,Map.of()).evaluate(rule()).status());
    }
    @Test void allReadWindowsSurviveTheSameBatchAndTargetKey(){
        var fixture=new SharedFactReuseTest();var ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"说明甲","说明乙","说明丙")));new SharedFactReuse(corpus,ledger.context(),"model","engine").publish(List.of(),corpus.all,List.of(rule()),List.of(),true,"EXTRACTED");
        SharedFactReuse second=new SharedFactReuse(corpus,ledger.context(),"model","engine");second.lookup(List.of(rule()),corpus.all,List.of());assertTrue(second.pendingSources(rule(),corpus.all).isEmpty(),"Same target receipts from a batch are keyed by source window too");assertEquals(3,second.completedReads().size());
    }
    @Test void failedObservationAndOtherFieldCannotSuppressRetry(){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"正文")));SharedFactReuse shared=new SharedFactReuse(corpus,Map.of(),"model","engine");shared.publishState(List.of(rule()),corpus.all,"FAILED","REQUEST_TIMEOUT");assertEquals(Set.of(corpus.all.get(0).id()),shared.pendingSources(rule(),corpus.all));
        Rule other=new Rule("other","数量","NUMBER","","quantity",">=","1","个",Map.of(),"数量","",List.of());shared.publish(List.of(),corpus.all,List.of(other),List.of(),true,"EXTRACTED");assertEquals(Set.of(corpus.all.get(0).id()),shared.pendingSources(rule(),corpus.all));assertTrue(shared.pendingSources(other,corpus.all).isEmpty());
    }
    @Test void localReadsAreTrackedWithoutPersistentPublisher(){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"说明")));SharedFactReuse shared=new SharedFactReuse(corpus,Map.of(),"model","engine");shared.publish(List.of(),corpus.all,List.of(rule()),List.of(),true,"EXTRACTED");assertTrue(shared.pendingSources(rule(),corpus.all).isEmpty());assertEquals(1,shared.completedReads().size());assertEquals(false,shared.coverage().get(0).get("absenceProven"));
    }
    @Test void rejectedFactIdsAndChangingErrorWordsDoNotKeepInnerLoopRunning(){
        AtomicInteger calls=new AtomicInteger();ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "invalid-facts";}public JsonNode request(String operation,Map<String,Object> input){calls.incrementAndGet();return json.valueToTree(Map.of("facts",List.of(Map.of("id","renamed-"+calls.get(),"kind","NUMBER","field","amount","value","999","rawValue","invented-"+calls.get(),"sourceIds",ExtractionBatches.sourceIds(input)))));}};
        var result=new VerificationEngine(model,null).evaluate(cp("c"),List.of(doc("d","p",1,"报价：80元","报价：90元")),null,Map.of());assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals(1,calls.get());assertEquals("NO_NEW_EVIDENCE_OR_CLOSED_GAP",((Map<?,?>)result.get("metrics")).get("stopReason"));assertEquals(1,((Map<?,?>)result.get("metrics")).get("rounds"));
    }
    @Test void concurrentSameTargetRechecksLedgerUnderSingleflight()throws Exception{
        List<Map<String,Object>> batches=new CopyOnWriteArrayList<>();Map<String,Object> context=Map.of("sharedFactPublisher",(Consumer<Map<String,Object>>)batches::add,"sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->{List<Map<String,Object>> rows=new ArrayList<>();for(var batch:batches)if("EXTRACTED".equals(batch.get("origin")))rows.add(linked("recordType","FIELD_COVERAGE","engineVersion",VERSION,"observationPolicy",batch.get("observationPolicy"),"batchId",batch.get("operationIdentity"),"fieldCoverage",batch.get("fieldCoverage"),"fieldCoverageSources",batch.get("sources")));return rows;});
        AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "same-model";}public JsonNode request(String op,Map<String,Object> in)throws java.io.IOException{calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("test wait timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}return json.valueToTree(Map.of("facts",List.of()));}};
        ExecutorService workers=Executors.newFixedThreadPool(2);try{var engine=new VerificationEngine(model,null);var documents=List.of(doc("d","p",1,"报价说明正文"));Future<Map<String,Object>> first=workers.submit(()->engine.evaluate(cp("first"),documents,null,context));assertTrue(entered.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> second=workers.submit(()->engine.evaluate(cp("second"),documents,null,context));release.countDown();assertEquals("UNDETERMINED",first.get(10,TimeUnit.SECONDS).get("businessDecision"));assertEquals("UNDETERMINED",second.get(10,TimeUnit.SECONDS).get("businessDecision"));assertEquals(1,calls.get(),"Negative window read is shared without inventing a PASS");}finally{release.countDown();workers.shutdownNow();}
    }
    @Test void packetFactsMergeLocallyAndCanNeverCiteAnUnsentPage(){
        var document=doc("d","p",1,"报价：80元","报价：120元");AtomicInteger calls=new AtomicInteger();List<Set<String>> sent=new ArrayList<>();ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "packet-merge";}public JsonNode request(String op,Map<String,Object> input){calls.incrementAndGet();assertTrue(ExtractionBatches.size(input)<=70_000);Set<String> refs=new HashSet<>(ExtractionBatches.sourceIds(input));sent.add(refs);List<Map<String,Object>> facts=new ArrayList<>();for(var row:RuleEvaluator.maps(input.get("sources"))){String amount=str(row,"quote").contains("120")?"120":"80";facts.add(linked("kind","NUMBER","subject","","field","amount","rawValue",amount,"value",amount,"unit","元","basis","报价","scope","UNRESOLVED","sourceIds",List.of(str(row,"id"))));}return json.valueToTree(Map.of("facts",facts));}};
        Map<String,Object> context=Map.of("unlimitedModelCalls",true,"layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(linked("documentId","d","locationType","PAGE","location",request.get("location"),"spanId","row-"+request.get("location"),"description","表格定位说明".repeat(7000))));
        var result=new VerificationEngine(model,null).evaluate(cp("split"),List.of(document),null,context);assertEquals(2,calls.get());assertEquals(2,sent.size());assertTrue(Collections.disjoint(sent.get(0),sent.get(1)));assertEquals(Set.of("80","120"),((List<Fact>)result.get("facts")).stream().map(Fact::value).collect(java.util.stream.Collectors.toSet()));assertNotEquals("PASS",result.get("businessDecision"));
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(document));Map<String,Object> forged=number(corpus.all.get(1),"120");assertTrue(new ProofVerifier().verify(json.valueToTree(Map.of("facts",List.of(forged))),List.of(corpus.all.get(0))).facts().isEmpty());
    }
    @Test void negativeReceiptCannotCarryFactsOrClaimAnotherFieldWasExtracted(){
        var fixture=new SharedFactReuseTest();var ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元")));Fact number=new Fact("v","NUMBER","","amount","80","80","元","报价","UNRESOLVED",List.of(corpus.all.get(0).id()),Map.of());new SharedFactReuse(corpus,ledger.context(),"model","engine").publish(List.of(number),corpus.all,List.of(rule()),List.of(),true,"EXTRACTED");
        var batch=ledger.batches.get(0);batch.remove("extractionCoverage");List<Map<String,Object>> fields=RuleEvaluator.maps(batch.get("fieldCoverage"));fields.get(0).put("status","READ_NOT_FOUND");batch.put("fieldCoverage",fields);var shared=new SharedFactReuse(corpus,ledger.context(),"model","engine");assertEquals(1,shared.lookup(List.of(rule()),corpus.all,List.of()).size());assertFalse(shared.pendingSources(rule(),corpus.all).isEmpty(),"A malformed negative receipt is not a completed field observation");
    }
    @Test void partiallyOverlappingRequestsShareTheirWindowLock()throws Exception{
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("d","p",1,"一","二","三")));SharedFactReuse first=new SharedFactReuse(corpus,Map.of(),"model","engine"),second=new SharedFactReuse(corpus,Map.of(),"model","engine");ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch trying=new CountDownLatch(1);Future<Boolean> future;
        try{try(var held=first.coordinate(List.of(rule()),corpus.all.subList(0,2))){future=worker.submit(()->{trying.countDown();try(var overlap=second.coordinate(List.of(rule()),corpus.all.subList(1,3))){return true;}});assertTrue(trying.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(100,TimeUnit.MILLISECONDS));}assertTrue(future.get(3,TimeUnit.SECONDS));}finally{worker.shutdownNow();}
    }
}
