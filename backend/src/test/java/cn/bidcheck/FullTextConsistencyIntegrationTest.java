package cn.bidcheck;

import cn.bidcheck.consistency.ConsistencyRunSupport;
import cn.bidcheck.consistency.ConsistencyModelScanner;
import cn.bidcheck.document.ExtractionPlan;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"bidcheck.check-mode=FULL_MODEL","spring.datasource.url=jdbc:h2:mem:full-consistency;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000",
        "bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false"})
@AutoConfigureMockMvc
class FullTextConsistencyIntegrationTest {
    private static final Path DATA;
    static {try{DATA=Files.createTempDirectory("bidcheck-consistency-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;
    @Autowired ReportService reports;
    @Autowired Store store;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired cn.bidcheck.verification.facts.FactLedgerService ledger;

    String parsed(String project,String role,String text)throws Exception {
        TaskRow task=service.upload(project,role,new MockMultipartFile("file","全文一致性样本.txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));
        service.execute(task);return store.map(task.inputJson).get("documentId").toString();
    }
    String project()throws Exception {
        String id=service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "一致性回归项目")).get("id").toString();
        String tender=parsed(id,"TENDER","项目名称：一致性回归项目\n项目编号：ZB-2026\n开标时间：2026年11月5日\n投标文件的企业名称、金额、开标时间与关键岗位人员应全文一致。");
        service.execute(service.extract(id,tender));
        for(Checkpoint cp:(List<Checkpoint>)service.detail(id).get("checkpoints"))
            service.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,"fullTextConsistency".equals(cp.field()),cp.source()));
        return id;
    }
    Map<String,Object> run(String project,String bid) throws Exception {
        Map<String,Object> created=service.startRun(project,bid);
        service.execute(service.task(created.get("taskId").toString()));
        return service.run(created.get("id").toString(),false);
    }

    @Test void publishesAllConflictGroupsWithLedgerSourcesReviewsAndExport()throws Exception {
        String project=project();String bid=parsed(project,"BID",
                "本项目投标函\n投标人名称：北京甲科技有限公司\n投标总报价：100万元\n开标时间：2026年11月5日09:00\n项目经理：张三\n"+
                "本项目响应表\n投标人名称：北京乙科技有限公司\n投标总报价：120万元\n开标时间：2026年11月6日09:00\n项目经理：李四");
        Map<String,Object> payload=run(project,bid),report=ConsistencyRunSupport.map(payload.get("consistency"));
        assertThat(ConsistencyRunSupport.map(payload.get("run"))).containsEntry("status","COMPLETED");
        List<Map<String,Object>> groups=ConsistencyRunSupport.maps(report.get("groups"));
        assertThat(groups).hasSizeGreaterThanOrEqualTo(4);
        assertThat(groups).extracting(g->g.get("field")).contains("companyName","totalPrice","openingTime","personName");
        assertThat(ConsistencyRunSupport.summary(report,store).evidences()).hasSizeGreaterThanOrEqualTo(8);
        List<Finding> findings=(List<Finding>)payload.get("results");
        for(Map<String,Object> group:groups) {
            Finding finding=findings.stream().filter(f->f.id().equals(group.get("findingId"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
            assertThat(finding.evaluation().evidences()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(finding.evaluation().evidences()).allSatisfy(e->{assertThat(e.documentId()).isEqualTo(bid);assertThat(service.document(bid).pages().stream().anyMatch(p->p.text().contains(e.quote()))).isTrue();});
        }
        assertThat(report).containsEntry("documentId",bid).containsEntry("documentHash",service.document(bid).sha256());
        assertThat(ConsistencyRunSupport.map(report.get("ledgerSummary")).get("occurrences")).isEqualTo(ConsistencyRunSupport.maps(report.get("occurrences")).size());
        String findingId=groups.get(0).get("findingId").toString(),runId=report.get("runId").toString();
        mvc.perform(post("/api/results/"+findingId+"/review").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WARNING\",\"reviewer\":\"回归测试\",\"note\":\"保留原文差异，待核定\"}")).andExpect(status().isOk());
        assertThat(ConsistencyRunSupport.maps(service.run(runId,false).get("reviews"))).hasSize(1);
        com.fasterxml.jackson.databind.JsonNode exported=json.readTree(reports.generate(runId,"json").bytes());
        assertThat(exported.path("consistency").path("groups").size()).isEqualTo(groups.size());
        assertThat(new String(reports.generate(runId,"html").bytes(),StandardCharsets.UTF_8)).contains("全文一致性扫描范围","保留原文差异，待核定");
        String frozen=store.encode(report);
        String replacement=parsed(project,"BID","本项目投标人名称：北京甲科技有限公司\n投标人名称：北京甲科技有限公司");
        Map<String,Object> second=run(project,replacement);
        assertThat(ConsistencyRunSupport.map(second.get("run")).get("id")).isNotEqualTo(runId);
        assertThat(store.encode(service.run(runId,false).get("consistency"))).isEqualTo(frozen);
    }

    @Test void exposesDisabledModelAndIncompletePageWithoutGlobalPass()throws Exception {
        String project=project(),bid=parsed(project,"BID","投标人名称：甲有限公司");
        Document original=service.document(bid);
        store.save("document",bid,project,"",new Document(bid,project,"BID",original.name(),original.version(),original.sha256(),original.createdAt(),
                cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标人名称：甲有限公司","PAGE","PDFBOX",null), new Page(2,"","PAGE","PDFBOX","[OCR未完成] 扫描件无法读取")),cn.bidcheck.compat.Java8Api.listOf("[OCR未完成] 扫描件无法读取")));
        Map<String,Object> payload=run(project,bid),report=ConsistencyRunSupport.map(payload.get("consistency")),coverage=ConsistencyRunSupport.map(report.get("coverage"));
        assertThat(((Number)coverage.get("pagesUnparsed")).intValue()).isGreaterThan(0);
        assertThat(ConsistencyRunSupport.map(coverage.get("model"))).containsEntry("enabled",false);
        assertThat((List<Finding>)payload.get("results")).noneMatch(f->"PASS".equals(f.evaluation().status()));
    }

    @Test void fullTextCheckpointSelectsUnlabelledImagesWithinBudget() {
        Checkpoint cp=new Checkpoint("c","全文一致性","全文信息检查","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","","",true,true,true,null);
        assertThat(ExtractionPlan.fromCheckpoints(cn.bidcheck.compat.Java8Api.listOf(cp)).mode()).isEqualTo(ExtractionPlan.Mode.FULL);
        assertThat(ExtractionPlan.fromCheckpoints(cn.bidcheck.compat.Java8Api.listOf(cp)).maxSelectedImages()).isEqualTo(ExtractionPlan.DEFAULT_SELECTED_IMAGES);
        Checkpoint disabled=new Checkpoint("c",cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),"","",true,true,false,null);
        assertThat(ExtractionPlan.fromCheckpoints(cn.bidcheck.compat.Java8Api.listOf(disabled)).mode()).isEqualTo(ExtractionPlan.Mode.NATIVE_ONLY);
    }

    @Test void keepsUnlimitedAttemptAuditAndSuccessfulBatchesAcrossRetryCacheInstances()throws Exception {
        String project=project(),bid=parsed(project,"BID","本项目投标人名称：甲公司");
        Map<String,Object> run=service.startRun(project,bid);String runId=run.get("id").toString();
        TaskRow task=service.task(run.get("taskId").toString());
        ConsistencyModelScanner.BatchCache first=ReflectionTestUtils.invokeMethod(service,"consistencyCache",task,runId);
        assertThat(first.reserve("success",2)).isTrue();
        first.save("success",json.readTree("{\"facts\":[]}"));
        ConsistencyModelScanner.BatchCache retry=ReflectionTestUtils.invokeMethod(service,"consistencyCache",task,runId);
        assertThat(retry.get("success").path("facts").isArray()).isTrue();
        assertThat(retry.reserve("failed",2)).isTrue();
        assertThat(retry.reserve("another-attempt",2)).isTrue();
        assertThat(retry.get("failed")).isNull();
        assertThat(store.maps("consistency_budget",project,runId)).singleElement().satisfies(counter->{
            assertThat(counter.get("calls")).isEqualTo(3);
            assertThat(counter.get("maxCalls")).isEqualTo(-1);
            assertThat(counter.get("unlimited")).isEqualTo(true);
        });
        String anotherBid=parsed(project,"BID","本项目投标人名称：乙公司");
        Map<String,Object> anotherRun=service.startRun(project,anotherBid);
        ConsistencyModelScanner.BatchCache independent=ReflectionTestUtils.invokeMethod(service,"consistencyCache",service.task(anotherRun.get("taskId").toString()),anotherRun.get("id").toString());
        assertThat(independent.get("success")).isNull();
        assertThat(independent.reserve("fresh",2)).isTrue();
    }

    @Test void preservesTableHeadersAlongsideValuesInSharedLedger()throws Exception {
        String project=project(),bid=parsed(project,"BID","投标人名称\t投标总报价（万元）\n甲有限公司\t100\n乙有限公司\t120");
        Map<String,Object> report=ConsistencyRunSupport.map(run(project,bid).get("consistency"));
        assertThat(ConsistencyRunSupport.maps(report.get("groups"))).hasSizeGreaterThanOrEqualTo(2);
        Map<String,Object> snapshot=ledger.view(project,report.get("runId").toString(),report.get("engineIdentity").toString(),cn.bidcheck.compat.Java8Api.listOf(service.document(bid)));
        assertThat(ConsistencyRunSupport.maps(snapshot.get("occurrences"))).allSatisfy(occurrence->{
            assertThat(ConsistencyRunSupport.maps(occurrence.get("sourceRefs"))).anySatisfy(source->assertThat(source.get("quote").toString()).contains("投标总报价（万元）"));
        });
    }

    @Test void legacyIdentityCardsUseSameScopeInsteadOfReintroducingHistoricalConflicts()throws Exception {
        String project=project();
        for(Checkpoint cp:(List<Checkpoint>)service.detail(project).get("checkpoints"))
            if("ENTITY_FIELD_CONSISTENCY".equals(cp.type())&&cn.bidcheck.compat.Java8Api.setOf("companyName", "personName").contains(cp.field())&&(cp.expected()==null||cn.bidcheck.compat.Java8Api.isBlank(cp.expected())))
                service.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,true,cp.source()));
        String bid=parsed(project,"BID","投标函\n投标人名称：甲公司\n项目经理：张三\n历史合同业绩\n投标人名称：乙公司\n项目经理：李四");
        Map<String,Object> payload=run(project,bid);
        assertThat(ConsistencyRunSupport.maps(ConsistencyRunSupport.map(payload.get("consistency")).get("groups"))).isEmpty();
        assertThat(cn.bidcheck.compat.Java8Api.toList(((List<Finding>)payload.get("results")).stream().filter(f->"ENTITY_FIELD_CONSISTENCY".equals(f.checkpoint().type()))))
                .hasSize(2).allSatisfy(f->{assertThat(f.evaluation().method()).contains("FULL_TEXT_SCOPED_IDENTITY");assertThat(f.evaluation().status()).isEqualTo("UNDETERMINED");});
    }

    @Test void expandedRelationsReachApiLedgerReviewAndAllExports()throws Exception {
        String project=project(),bid=parsed(project,"BID","本项目投标函\n"+
                "品目：服务器A；产品型号：X100\n品目：服务器A；产品型号：X200\n"+
                "本项目交付计划\n起算条件：合同签订后；交货期：30日历天；计划完成周期：45日历天\n"+
                "本项目报价表\n品目\t数量（台）\t单价（元）\t行金额（元）\n服务器B\t3\t100\t250");
        Map<String,Object> payload=run(project,bid),report=ConsistencyRunSupport.map(payload.get("consistency"));
        assertThat(ConsistencyRunSupport.map(payload.get("run"))).containsEntry("status","COMPLETED");
        assertThat(report).containsEntry("version",cn.bidcheck.consistency.ConsistencyEngine.VERSION);
        List<Map<String,Object>> groups=ConsistencyRunSupport.maps(report.get("groups"));
        assertThat(groups).extracting(g->g.get("relationType")).contains("VALUE","ARITHMETIC","TEMPORAL");
        assertThat(ConsistencyRunSupport.maps(ConsistencyRunSupport.map(report.get("coverage")).get("categories"))).hasSize(18);
        Set<String> ids=new HashSet<>();ConsistencyRunSupport.maps(report.get("occurrences")).forEach(o->ids.add(o.get("id").toString()));
        assertThat(groups).allSatisfy(g->{
            assertThat(g.get("categoryId")).isNotNull();assertThat(ConsistencyRunSupport.map(g.get("comparisonContext"))).isNotEmpty();
            assertThat((List<String>)g.get("occurrenceIds")).allMatch(ids::contains);
            assertThat(ConsistencyRunSupport.evidences(store,g.get("evidences"))).allSatisfy(e->assertThat(service.document(bid).pages().stream().anyMatch(p->p.text().contains(e.quote()))).isTrue());
        });
        Map<String,Object> snapshot=ledger.view(project,report.get("runId").toString(),report.get("engineIdentity").toString(),cn.bidcheck.compat.Java8Api.listOf(service.document(bid)));
        assertThat(ConsistencyRunSupport.maps(snapshot.get("occurrences"))).anySatisfy(o->{
            assertThat(o.get("field")).isEqualTo("productModel");
            assertThat(ConsistencyRunSupport.map(o.get("attributes"))).containsEntry("categoryId","product");
            assertThat(ConsistencyRunSupport.map(ConsistencyRunSupport.map(o.get("attributes")).get("dimensions"))).containsEntry("object","服务器A");
        });
        String runId=report.get("runId").toString(),findingId=groups.stream().filter(g->"ARITHMETIC".equals(g.get("relationType"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("findingId").toString();
        mvc.perform(post("/api/results/"+findingId+"/review").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WARNING\",\"reviewer\":\"验收\",\"note\":\"核对同行乘算原文\"}")).andExpect(status().isOk());
        String html=new String(reports.generate(runId,"html").bytes(),StandardCharsets.UTF_8),csv=new String(reports.generate(runId,"csv").bytes(),StandardCharsets.UTF_8);
        assertThat(html).contains("检查目录与实际识别范围（18 类）","计算关系","时间关系","比较前提","核对同行乘算原文");
        assertThat(csv).contains("一致性类别,检查关系,比较前提","计算关系","核对同行乘算原文");
        assertThat(json.readTree(reports.generate(runId,"json").bytes()).path("consistency").path("catalogVersion").asText()).isNotBlank();
    }

    @Test void unresolvedComparisonIsReviewableInsufficientEvidenceRatherThanConflict()throws Exception {
        String project=project(),bid=parsed(project,"BID","本项目投标文件\n产品型号：X100\n产品型号：X200");
        Map<String,Object> payload=run(project,bid),report=ConsistencyRunSupport.map(payload.get("consistency"));
        List<Map<String,Object>> groups=ConsistencyRunSupport.maps(report.get("groups"));
        assertThat(groups).anySatisfy(g->{assertThat(g).containsEntry("status","EVIDENCE_INSUFFICIENT");assertThat(g.get("findingId")).isNotNull();});
        assertThat(groups).noneMatch(g->"CONFLICT".equals(g.get("status")));
        List<Finding> findings=(List<Finding>)payload.get("results");
        for(Map<String,Object> group:groups)if("EVIDENCE_INSUFFICIENT".equals(group.get("status")))
            assertThat(findings.stream().filter(f->f.id().equals(group.get("findingId"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).evaluation().status()).isEqualTo("UNDETERMINED");
        assertThat(ConsistencyRunSupport.summary(report,store).reason()).contains("比较证据不足");
        assertThat(new String(reports.generate(report.get("runId").toString(),"html").bytes(),StandardCharsets.UTF_8)).contains("不能据此认定事实冲突或材料不存在");
    }

    @Test void projectHintsAndCustomSourceBoundFactsSurviveTheFullPipeline()throws Exception {
        String project=project(),first="本项目业务数据库；备份副本数：3份",second="本项目业务数据库；备份副本数：2份";
        String bid=parsed(project,"BID",first+"\n"+second);
        List<Map<String,Object>> inputs=new ArrayList<>();
        cn.bidcheck.verification.engine.ModelGateway gateway=new cn.bidcheck.verification.engine.ModelGateway(){
            public boolean enabled(){return true;}
            public String identity(){return "offline-custom-consistency-integration";}
            public com.fasterxml.jackson.databind.JsonNode request(String operation,Map<String,Object> input){
                inputs.add(input);String source=ConsistencyRunSupport.maps(input.get("sources")).get(0).get("id").toString();
                List<Map<String,Object>> facts=new ArrayList<>();
                for(String quote:cn.bidcheck.compat.Java8Api.listOf(first, second))facts.add(BidService.fields("kind","FIELD","field","custom","fieldLabel","备份副本数","categoryId","solution",
                        "subject","业务数据库","scope","CURRENT_PROJECT","rawValue",quote.contains("3份")?"3份":"2份","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),
                        "attributes",BidService.fields("quote",quote,"contextQuote",quote,"dimensions",cn.bidcheck.compat.Java8Api.mapOf("object", "业务数据库"))));
                facts.add(BidService.fields("kind","FIELD","field","custom","fieldLabel","不存在的字段标签","categoryId","solution","scope","CURRENT_PROJECT",
                        "rawValue","3份","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf("quote", first, "contextQuote", first)));
                return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", facts));
            }
        };
        Object original=ReflectionTestUtils.getField(service,"consistencyScanner");
        try {
            ReflectionTestUtils.setField(service,"consistencyScanner",new ConsistencyModelScanner(gateway));
            Map<String,Object> payload=run(project,bid),report=ConsistencyRunSupport.map(payload.get("consistency"));
            assertThat(ConsistencyRunSupport.map(payload.get("run"))).containsEntry("status","COMPLETED");
            assertThat(inputs).isNotEmpty();
            assertThat(ConsistencyRunSupport.maps(inputs.get(0).get("requirementHints"))).extracting(h->h.get("field")).containsExactly("fullTextConsistency");
            List<Map<String,Object>> facts=ConsistencyRunSupport.maps(report.get("occurrences"));
            assertThat(facts).filteredOn(f->f.get("field").toString().startsWith("dynamic.")).hasSize(2).allSatisfy(f->{
                assertThat(f).containsEntry("categoryId","solution").containsEntry("fieldLabel","备份副本数").containsEntry("comparability","WEAK");
            });
            assertThat(ConsistencyRunSupport.maps(report.get("groups"))).anySatisfy(g->{assertThat(g).containsEntry("categoryId","solution").containsEntry("status","SUSPECTED");});
            assertThat(((Number)ConsistencyRunSupport.map(ConsistencyRunSupport.map(report.get("coverage")).get("model")).get("rejectedFacts")).intValue()).isGreaterThan(0);
            String historical=store.encode(report);
            assertThat(json.readTree(reports.generate(report.get("runId").toString(),"json").bytes()).path("consistency").toString()).contains("备份副本数");
            assertThat(store.encode(service.run(report.get("runId").toString(),false).get("consistency"))).isEqualTo(historical);
        } finally {ReflectionTestUtils.setField(service,"consistencyScanner",original);}
    }
}
