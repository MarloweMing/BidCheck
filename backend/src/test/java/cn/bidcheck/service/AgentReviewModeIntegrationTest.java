package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.agent.AgentInvestigator;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:agent-review-mode;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000",
    "bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false","bidcheck.visual.enabled=false","bidcheck.queue.enabled=false","bidcheck.agent.enabled=false"})
class AgentReviewModeIntegrationTest {
    private static final Path DATA=temp();
    private static Path temp(){try{return Files.createTempDirectory("agent-review-mode-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",DATA::toString);}
    @Autowired BidService service;@Autowired Store store;@Autowired FocusedCheckContext contexts;
    private Object previous;
    @BeforeEach void configure(){previous=ReflectionTestUtils.getField(service,"reviewInvestigator");AgentInvestigator investigator=mock(AgentInvestigator.class);when(investigator.enabled()).thenReturn(true);when(investigator.identity()).thenReturn("test-agent-no-network");ReflectionTestUtils.setField(service,"reviewInvestigator",investigator);}
    @AfterEach void restore(){ReflectionTestUtils.setField(service,"reviewInvestigator",previous);}
    private record Fixture(String project,String tender,String bid){}
    @SuppressWarnings("unchecked") private Fixture fixture()throws Exception{
        String p=BidService.text(service.createProject(Map.of("name","Agent模式入口测试")),"id");String tender=parsed(p,"TENDER","投标人应提交授权委托书及报价清单。");service.execute(service.extract(p,tender));
        for(Checkpoint cp:(List<Checkpoint>)service.detail(p).get("checkpoints"))service.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,false,cp.source()));
        for(String name:List.of("授权委托书","报价清单"))service.createCheckpoint(p,BidService.fields("title",name,"category","形式检查","type","MATERIAL_EXISTENCE","field","material","operator","EXISTS","expected",name,"confirmed",true));
        String bid=parsed(p,"BID","投标文件\n授权委托书\n本单位授权王甲办理本项目投标事项。\n报价清单\n本项目投标报价合计为人民币100万元。");return new Fixture(p,tender,bid);
    }
    private String parsed(String p,String role,String text)throws Exception{TaskRow task=service.upload(p,role,new MockMultipartFile("file",role+".txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));service.execute(task);return BidService.text(store.map(task.inputJson),"documentId");}
    private Map<String,Object> start(Fixture f,String mode,String key){return service.startRun(f.project(),f.bid(),false,"",null,key,mode);}
    @Test void newModeFreezesSeparateFingerprintWithoutChangingOriginalCheckpointIds()throws Exception{
        Fixture f=fixture();Map<String,Object> fixed=start(f,"FIXED_WORKFLOW","fixed"),agent=start(f,"AGENT_LED","agent");
        assertEquals("RULES_RETRIEVAL",agent.get("checkMode"));assertEquals("AGENT_LED",agent.get("reviewMode"));assertEquals(CheckReviewMode.VERSION,agent.get("reviewModeVersion"));assertEquals(false,agent.get("templateRouteEnabled"));
        assertEquals("RULES_RETRIEVAL",agent.get("consistencyReviewMode"));assertNotEquals(fixed.get("fingerprint"),agent.get("fingerprint"));assertNotEquals(fixed.get("id"),agent.get("id"));
        List<String> a=store.list("snapshot",f.project(),BidService.text(agent,"id"),Checkpoint.class).stream().map(Checkpoint::id).sorted().toList();
        List<String> b=store.list("snapshot",f.project(),BidService.text(fixed,"id"),Checkpoint.class).stream().map(Checkpoint::id).sorted().toList();assertEquals(b,a);assertEquals(2,a.size());
        assertEquals(agent.get("id"),start(f,"AGENT_LED","agent").get("id"));assertThrows(Exception.class,()->start(f,"FIXED_WORKFLOW","agent"));
    }
    @Test void unavailableSdkAndUnknownModesRejectBeforeAnyNewRunIsCreated()throws Exception{
        Fixture f=fixture();ReflectionTestUtils.setField(service,"reviewInvestigator",null);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->start(f,"AGENT_LED","unavailable"));assertEquals(503,error.getStatusCode().value());assertTrue(error.getReason().contains("未降级"));
        assertThrows(ResponseStatusException.class,()->start(f,"UNKNOWN","unknown"));assertTrue(store.maps("run",f.project(),null).isEmpty());
        assertEquals("FIXED_WORKFLOW",service.startRun(f.project(),f.bid()).get("reviewMode"));
    }
    @Test void recoveryInheritsReviewModeAndNeverMutatesTheFinishedParent()throws Exception{
        Fixture f=fixture();Map<String,Object> parent=start(f,"AGENT_LED","parent");String id=BidService.text(parent,"id");
        parent.put("status","COMPLETED");parent.put("ocrPreparationComplete",true);store.save("run",id,f.project(),f.bid(),parent);
        TaskRow task=service.task(BidService.text(parent,"taskId"));task.status="SUCCEEDED";store.mapper().updateTask(task);
        String before=store.require("run",id).body;Map<String,Object> child=service.recoverRun(f.project(),id);
        assertEquals("AGENT_LED",child.get("reviewMode"));assertEquals(false,child.get("templateRouteEnabled"));assertEquals(id,child.get("parentRunId"));assertEquals(before,store.require("run",id).body);
        assertEquals(child.get("id"),service.recoverRun(f.project(),id).get("id"));
    }
    @Test void frozenModeReachesFocusedContextAndExplicitAgentStallWaitsForChangedInput()throws Exception{
        Fixture f=fixture();Map<String,Object> run=start(f,"AGENT_LED","context");String id=BidService.text(run,"id");run.put("status","RUNNING");store.save("run",id,f.project(),f.bid(),run);
        TaskRow task=service.task(BidService.text(run,"taskId"));task.status="RUNNING";store.mapper().updateTask(task);
        Checkpoint cp=store.list("snapshot",f.project(),id,Checkpoint.class).get(0);Document bid=service.document(f.bid()),tender=service.document(f.tender());
        Map<String,Object> context=contexts.prepare(task,id,bid,tender,"mode-test",List.of(),()->true).apply(cp);assertEquals("AGENT_LED",context.get("reviewMode"));
        Map<String,Object> atomic=Map.of("complete",false,"workflowState","NEEDS_REVIEW","agentReview",Map.of("mode","AGENT_LED"),
            "agentInvestigation",Map.of("status","NO_PROGRESS","errorCode","AGENT_STALLED_WITH_REMAINING_TARGETS"),
            "gaps",List.of(Map.of("code","AGENT_INVESTIGATION_STALLED","description","本轮没有新增证据")));
        Finding finding=new Finding(BidService.stableId(id+":"+cp.id()),id,cp,new Evaluation("UNDETERMINED","本轮未闭合","",List.of(),0,"AGENT_LED_REVIEW"),Map.of("atomicCheck",atomic));
        ReflectionTestUtils.invokeMethod(service,"recordResolution",task,id,finding);Map<String,Object> job=store.getMap("check_resolution",CheckResolution.jobId(id,cp.id()));
        assertEquals("FAILED",job.get("status"));assertEquals("WAIT_AGENT_INPUT_CHANGE",job.get("strategy"));assertEquals("RELEVANT_INPUT_OR_EVIDENCE_CHANGED",job.get("resumeTrigger"));
    }
    @Test void agentOwnedPendingVisualWorkRemainsExecutableInsteadOfBeingTurnedIntoAWait()throws Exception{
        Fixture f=fixture();Map<String,Object> run=start(f,"AGENT_LED","visual-action");String id=BidService.text(run,"id");
        run.put("status","RUNNING");store.save("run",id,f.project(),f.bid(),run);
        TaskRow task=service.task(BidService.text(run,"taskId"));task.status="RUNNING";store.mapper().updateTask(task);
        Checkpoint cp=store.list("snapshot",f.project(),id,Checkpoint.class).get(0);
        Map<String,Object> atomic=Map.of("complete",false,"workflowState","NEEDS_REVIEW",
            "gaps",List.of(Map.of("code","VISUAL_OBSERVATION_REQUIRED","description","需要核验原图")),
            "visualRequests",List.of(Map.of("documentId",f.bid(),"page",1,"task","SEAL")));
        Finding finding=new Finding(BidService.stableId(id+":"+cp.id()),id,cp,new Evaluation("UNDETERMINED","等待原图核验","",List.of(),0,"AGENT_LED_REVIEW"),Map.of("atomicCheck",atomic));
        ReflectionTestUtils.invokeMethod(service,"recordResolution",task,id,finding);Map<String,Object> job=store.getMap("check_resolution",CheckResolution.jobId(id,cp.id()));
        assertEquals("QUEUED",job.get("status"));assertEquals("VERIFY_SOURCE_PAGES",job.get("strategy"));assertNull(job.get("requiresInputChange"));
    }
    @Test void agentModeDelegatesKnownFullTextChecksToTheExistingProgramScanner()throws Exception{
        Fixture f=fixture();Map<String,Object> run=start(f,"AGENT_LED","full-text-delegation");String runId=BidService.text(run,"id");
        Checkpoint check=new Checkpoint("full-text-typo","错别字检查","全文信息检查","MEDIUM","FULL_TEXT_REVIEW","typos","REVIEW","检查疑似错别字","",true,true,true,null);
        Finding finding=ReflectionTestUtils.invokeMethod(service,"evaluateFinding",runId,check,service.document(f.bid()),service.document(f.tender()),List.<Fact>of(),Map.<String,Object>of(),(BooleanSupplier)()->true,new LinkedHashMap<>(Map.of("reviewMode","AGENT_LED")));
        assertNotNull(finding);assertEquals("DETERMINISTIC/FULL_TEXT_REVIEW",finding.evaluation().method());
        assertEquals("DETERMINISTIC_FULL_TEXT_REVIEW",BidService.text(CheckResolution.map(finding.assessment().get("agentDelegation")),"executor"));
    }
}
