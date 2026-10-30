package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticOnDemandTest {
    HttpServer server;AtomicInteger calls=new AtomicInteger();String url;
    @BeforeEach void setup()throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/services/aigc/text-generation/generation",e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();
            byte[] bytes="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{bad json}\"}}]}}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/services/aigc/text-generation/generation";}
    @AfterEach void stop(){server.stop(0);}
    Document doc(String text){return new Document("d","p","BID","bid.txt",1,"hash","now",List.of(new Page(1,text,"PAGE","TEXT","")),List.of());}
    Checkpoint cp(){return OnDemandPolicyTest.check("设备配置与技术方案参数应一致");}
    @Test void noRelevantEvidenceUsesRetrievalWithoutCallingProvider(){var result=new SemanticChecker(url,"test-key","test-model").assessOnDemand(cp(),doc("项目地址位于海南"),null,()->{fail("No reservation allowed");return false;});assertEquals("UNDETERMINED",result.evaluation().status());assertEquals(0,calls.get());assertEquals(false,((Map<?,?>)result.audit().get("model")).get("requested"));}
    @Test void oneReservationAllowsOneAttemptButNoRepairOverBudget(){AtomicInteger reserved=new AtomicInteger();var result=new SemanticChecker(url,"test-key","test-model").assessOnDemand(cp(),doc("设备配置与技术方案：处理器参数采用四核配置。"),null,()->reserved.incrementAndGet()==1);assertEquals(1,calls.get());assertEquals("UNDETERMINED",result.evaluation().status());Map<?,?> m=(Map<?,?>)result.audit().get("model");assertEquals("BUDGET_DEFERRED",m.get("decision"));assertEquals(1,((List<?>)m.get("attempts")).size());}
    @Test void protocolRepairConsumesItsOwnReservation(){AtomicInteger reserved=new AtomicInteger();var result=new SemanticChecker(url,"test-key","test-model").assessOnDemand(cp(),doc("设备配置与技术方案：处理器参数采用四核配置。"),null,()->reserved.incrementAndGet()<=2);assertEquals(2,calls.get());assertEquals(2,reserved.get());assertEquals("UNDETERMINED",result.evaluation().status());}
    @Test void expiredLeasePropagatesWithoutDispatch(){assertThrows(TaskLeaseLostException.class,()->new SemanticChecker(url,"test-key","test-model").assessOnDemand(cp(),doc("设备配置与技术方案：处理器参数采用四核配置。"),null,()->{throw new TaskLeaseLostException("t");}));assertEquals(0,calls.get());}
    @Test void zeroBudgetKeepsEvidenceAndNoHttp(){var result=new SemanticChecker(url,"test-key","test-model").assessOnDemand(cp(),doc("设备配置与技术方案：处理器参数采用四核配置。"),null,()->false);assertEquals(0,calls.get());assertEquals("UNDETERMINED",result.evaluation().status());assertFalse(((List<?>)result.audit().get("candidates")).isEmpty());}
    @Test void failureBeforeEvidenceAdmissionIsUnknownNotAClaimOfMissingEvidence(){
        var result=new SemanticChecker(url,"test-key","test-model").assessOnDemand(cp(),null,null,()->{fail("Invalid input cannot reserve");return false;});
        Map<?,?> model=(Map<?,?>)result.audit().get("model");
        assertEquals("UNKNOWN",model.get("decision"));assertEquals(0,calls.get());assertEquals("UNDETERMINED",result.evaluation().status());
        var finding=new Finding("f","run",cp(),result.evaluation(),result.audit());
        var summary=(Map<?,?>)OnDemandSummary.summarize(Map.of("checkMode","RULES_RETRIEVAL","onDemandAttemptsReserved",0,"onDemandAttemptBudget",24),List.of(finding),1).get("onDemandModel");
        assertEquals(1,summary.get("unknownCheckpoints"));assertEquals(0,summary.get("evidenceInsufficientCheckpoints"));
    }
    @Test void deterministicFullTextDelegationIsCountedAsModelNotNeededInsteadOfUnknown(){
        Checkpoint check=new Checkpoint("full-text","错别字检查","全文信息检查","MEDIUM","FULL_TEXT_REVIEW","typos","REVIEW","检查疑似错别字","",true,true,true,null);
        Finding finding=new Finding("f","run",check,new Evaluation("PASS","程序扫描完成","",List.of(),1,"DETERMINISTIC/FULL_TEXT_REVIEW"),
            Map.of("agentDelegation",Map.of("executor","DETERMINISTIC_FULL_TEXT_REVIEW","modelCalls",0)));
        Map<?,?> summary=(Map<?,?>)OnDemandSummary.summarize(Map.of("checkMode","RULES_RETRIEVAL"),List.of(finding),1).get("onDemandModel");
        assertEquals(1,summary.get("modelNotNeededCheckpoints"));assertEquals(0,summary.get("unknownCheckpoints"));assertEquals(0,summary.get("modelRequestedCheckpoints"));
    }
    @Test void fixedPacketNeverReplacesRequiredSidesAndRepairSharesBudget(){
        Document source=doc("设备配置与技术方案：处理器参数采用四核配置。\n设备配置与技术方案：处理器参数采用八核配置。");
        List<Evidence> packet=Arrays.stream(source.pages().get(0).text().split("\\n")).map(q->new Evidence("d","bid.txt",1,"PAGE",q,"p1",1)).toList();
        AtomicInteger reserves=new AtomicInteger();var result=new SemanticChecker(url,"test-key","test-model").assessConsistencyPacket(cp(),source,packet,()->reserves.incrementAndGet()==1);
        assertEquals(1,calls.get());assertEquals("BUDGET_DEFERRED",((Map<?,?>)result.audit().get("model")).get("decision"));
        List<?> candidates=(List<?>)result.audit().get("candidates");assertEquals(2,candidates.size());
        assertTrue(candidates.stream().allMatch(c->Boolean.TRUE.equals(((Map<?,?>)c).get("required"))));
    }
    @Test void invalidPacketCannotDispatch(){
        var result=new SemanticChecker(url,"test-key","test-model").assessConsistencyPacket(cp(),doc("设备配置与技术方案：四核配置"),List.of(new Evidence("foreign","f",1,"PAGE","四核配置","p1",1)),()->{fail();return false;});
        assertEquals(0,calls.get());assertEquals("UNDETERMINED",result.evaluation().status());
    }
    @Test void repeatedQuoteDoesNotBorrowFirstOccurrenceContext(){
        Document source=doc("历史合同\n设备配置与技术方案：四核配置\n本次投标\n设备配置与技术方案：四核配置");
        var result=new SemanticChecker(url,"test-key","test-model").assessConsistencyPacket(cp(),source,List.of(new Evidence("d","bid.txt",1,"PAGE","设备配置与技术方案：四核配置","p1-l4",1)),()->false);
        assertTrue(((List<?>)result.audit().get("guards")).stream().anyMatch(g->"SOURCE_POSITION_AMBIGUOUS".equals(((Map<?,?>)g).get("code"))));
        assertEquals(0,calls.get());
    }
}
