package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.consistency.ConsistencyRunSupport;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.TaskRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:template-route;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=",
    "bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false"})
class TemplateRouteIntegrationTest {
    static final Path DATA=temp();
    static Path temp(){try{return Files.createTempDirectory("template-route-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;

    @Test void ordinaryCheckEntryFreezesAndExecutesTheFixedTemplateWithoutCompilation() throws Exception {
        String project=service.createProject(Map.of("name","固定模板入口测试")).get("id").toString();
        String tender=parsed(project,"TENDER","投标文件应提供法定代表人身份证明正文。");
        service.execute(service.extract(project,tender));
        Checkpoint material=configureSourceBoundMaterialCheckpoint(project,tender);
        String bid=parsed(project,"BID","法定代表人身份证明\n投标人：海南示例建设有限公司\n法定代表人姓名：张三\n特此证明。\n日期：2026年8月1日");

        Map<String,Object> created=service.startRun(project,bid);
        assertNotEquals("UNAVAILABLE",created.get("templateCheckIdentity"));
        assertNotNull(created.get("templateSnapshotId"));assertNotNull(created.get("templateSnapshotDigest"));
        List<Checkpoint> frozen=store.list("snapshot",project,created.get("id").toString(),Checkpoint.class);
        assertFalse(service.allCheckpointsUseFrozenTemplates(project,created.get("id").toString(),frozen),
            "a compatibility route without an immutable CheckSpec must retain the legacy pipeline");
        Checkpoint sample=frozen.get(0),notRouted=new Checkpoint("legacy-only",sample.title(),sample.category(),sample.severity(),sample.type(),sample.field(),sample.operator(),sample.expected(),sample.unit(),sample.required(),sample.confirmed(),sample.enabled(),sample.source());
        List<Checkpoint> mixed=new java.util.ArrayList<>(frozen);mixed.add(notRouted);
        assertFalse(service.allCheckpointsUseFrozenTemplates(project,created.get("id").toString(),mixed),
            "a mixed run must retain the legacy full-text pipeline");
        service.execute(service.task(created.get("taskId").toString()));

        Finding finding=((List<Finding>)service.run(created.get("id").toString(),false).get("results")).stream()
            .filter(value->material.id().equals(value.checkpoint().id())).findFirst().orElseThrow();
        assertTrue(finding.evaluation().method().startsWith("TEMPLATE_FIXED"));
        Map<?,?> trace=(Map<?,?>)finding.assessment().get("templateTrace");assertEquals(0,trace.get("compileRequests"));
        Map<?,?> projection=(Map<?,?>)finding.assessment().get("resultProjection");
        assertEquals(true,projection.get("finalReconciled"));assertEquals(finding.evaluation().status(),finding.assessment().get("businessPresentation") instanceof Map<?,?> p?p.get("finalStatus"):null);
        Map<String,Object> graph=service.templateDependencyGraph(created.get("id").toString());
        assertEquals("template-dependency-snapshot-v1",graph.get("schemaVersion"));
        assertTrue(((Map<?,?>)graph.get("reverseNeedIndex")).values().stream().anyMatch(value->value instanceof List<?> ids&&ids.contains(material.id())), graph::toString);
        assertNotNull(store.mapper().get(ConsistencyRunSupport.reportId(created.get("id").toString())),
            "兼容模板没有冻结 CheckSpec，必须保留旧全文流程");
    }

    @Test void rolloutSwitchOnlyChangesNewRunsAndDoesNotInvalidateFrozenWork() throws Exception {
        ReflectionTestUtils.setField(service,"templateRouteEnabled",true);
        try {
            String frozenProject=projectWithConfirmedMaterialCheck("模板开关冻结测试");
            String frozenBid=parsed(frozenProject,"BID","法定代表人身份证明\n投标人：海南示例建设有限公司\n特此证明。");
            Map<String,Object> frozenRun=service.startRun(frozenProject,frozenBid);
            assertEquals(true,frozenRun.get("templateRouteEnabled"));

            ReflectionTestUtils.setField(service,"templateRouteEnabled",false);
            service.execute(service.task(frozenRun.get("taskId").toString()));
            Finding frozenFinding=firstFinding(frozenRun);
            assertTrue(frozenFinding.evaluation().method().startsWith("TEMPLATE_FIXED"));

            String legacyProject=projectWithConfirmedMaterialCheck("模板开关新任务测试");
            String legacyBid=parsed(legacyProject,"BID","法定代表人身份证明\n投标人：海南示例建设有限公司\n特此证明。");
            Map<String,Object> legacyRun=service.startRun(legacyProject,legacyBid);
            assertEquals(false,legacyRun.get("templateRouteEnabled"));
            service.execute(service.task(legacyRun.get("taskId").toString()));
            assertFalse(firstFinding(legacyRun).evaluation().method().startsWith("TEMPLATE_FIXED"));
        } finally {
            ReflectionTestUtils.setField(service,"templateRouteEnabled",true);
        }
    }

    @Test void sourceLessManualTypedCheckpointDoesNotEnterTheFixedTemplateRoute() throws Exception {
        String project=service.createProject(Map.of("name","无来源手工项路由测试")).get("id").toString();
        String tender=parsed(project,"TENDER","投标文件应提供法定代表人身份证明正文。");
        service.execute(service.extract(project,tender));
        for(Checkpoint checkpoint:checkpoints(project))service.updateCheckpoint(checkpoint.id(),new Checkpoint(checkpoint.id(),checkpoint.title(),checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),true,false,checkpoint.source()));
        Checkpoint manual=service.createCheckpoint(project,Map.of("title","法定代表人身份证明","category","形式检查","severity","HIGH",
            "type","MATERIAL_EXISTENCE","field","material","operator","EXISTS","expected","法定代表人身份证明","unit","","confirmed",true,"enabled",true));
        String bid=parsed(project,"BID","法定代表人身份证明\n投标人：海南示例建设有限公司\n特此证明。");

        Map<String,Object> created=service.startRun(project,bid);service.execute(service.task(created.get("taskId").toString()));
        Finding finding=((List<Finding>)service.run(created.get("id").toString(),false).get("results")).stream()
            .filter(value->manual.id().equals(value.checkpoint().id())).findFirst().orElseThrow();
        assertFalse(finding.evaluation().method().startsWith("TEMPLATE_FIXED"));
        assertFalse(((Map<?,?>)service.templateDependencyGraph(created.get("id").toString()).get("reverseNeedIndex")).values().stream()
            .anyMatch(value->value instanceof List<?> ids&&ids.contains(manual.id())));
    }

    private String projectWithConfirmedMaterialCheck(String name) throws Exception {
        String project=service.createProject(Map.of("name",name)).get("id").toString();
        String tender=parsed(project,"TENDER","投标文件应提供法定代表人身份证明正文。");
        service.execute(service.extract(project,tender));
        configureSourceBoundMaterialCheckpoint(project,tender);
        return project;
    }

    private Checkpoint configureSourceBoundMaterialCheckpoint(String project,String tender) {
        List<Checkpoint> extracted=checkpoints(project);
        for(Checkpoint checkpoint:extracted)service.updateCheckpoint(checkpoint.id(),new Checkpoint(checkpoint.id(),checkpoint.title(),checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),true,false,checkpoint.source()));
        Checkpoint created=service.createCheckpoint(project,Map.of("title","法定代表人身份证明","category","形式检查","severity","HIGH",
            "type","MATERIAL_EXISTENCE","field","material","operator","EXISTS","expected","法定代表人身份证明","unit","","confirmed",true,"enabled",true));
        Evidence source=new Evidence(tender,"tender.txt",1,"PAGE","投标文件应提供法定代表人身份证明正文。","",1);
        Checkpoint sourceBound=new Checkpoint(created.id(),created.title(),created.category(),created.severity(),created.type(),created.field(),created.operator(),created.expected(),created.unit(),created.required(),created.confirmed(),created.enabled(),source);
        store.save("checkpoint",sourceBound.id(),project,tender,sourceBound);
        return sourceBound;
    }

    @SuppressWarnings("unchecked") private Finding firstFinding(Map<String,Object> run){return ((List<Finding>)service.run(run.get("id").toString(),false).get("results")).stream().findFirst().orElseThrow();}

    @SuppressWarnings("unchecked") private List<Checkpoint> checkpoints(String project){return (List<Checkpoint>)service.detail(project).get("checkpoints");}
    private String parsed(String project,String role,String text) throws Exception {
        TaskRow task=service.upload(project,role,new MockMultipartFile("file",role.toLowerCase()+".txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));
        service.execute(task);return store.map(task.inputJson).get("documentId").toString();
    }
}
