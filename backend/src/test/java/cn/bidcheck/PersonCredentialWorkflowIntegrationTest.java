package cn.bidcheck;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.*;
import cn.bidcheck.verification.*;
import cn.bidcheck.consistency.ConsistencyRunSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Source fixtures and real persistence/report publication; all remote services disabled. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:person-credential-workflow;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.verification.input-poll-ms=600000","bidcheck.semantic.url=",
    "bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false","bidcheck.check-concurrency=1"})
class PersonCredentialWorkflowIntegrationTest {
    static final Path DATA;
    static {try {DATA=Files.createTempDirectory("person-credential-workflow-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService bids; @Autowired Store store; @Autowired ReportService reports;
    @Autowired VerificationService verification; @Autowired VerificationReportService verificationReports;

    String upload(String project,String role,String content)throws Exception {
        TaskRow task=bids.upload(project,role,new MockMultipartFile("file","合成人员证件测试.txt","text/plain",content.getBytes(StandardCharsets.UTF_8)));
        bids.execute(task);return store.map(task.inputJson).get("documentId").toString();
    }
    String[] setup()throws Exception {
        String project=bids.createProject(Map.of("name","人员证件合成回归")).get("id").toString();
        String tender=upload(project,"TENDER","项目名称：人员证件合成回归\n投标有效期不得少于90天。");
        bids.execute(bids.extract(project,tender));
        for(Checkpoint cp:store.list("checkpoint",project,tender,Checkpoint.class))
            bids.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,"bidValidity".equals(cp.field()),cp.source()));
        assertThat(store.list("checkpoint",project,tender,Checkpoint.class)).anyMatch(c->c.enabled()&&"bidValidity".equals(c.field()));
        return new String[]{project,tender};
    }
    String distantCredentials(String project,boolean incomplete)throws Exception {
        String first="中华人民共和国机动车驾驶证\n姓名：测试甲\n证号：110101199001010011\n档案编号：123456789012\n准驾车型：C1";
        String last="中华人民共和国机动车驾驶证\n姓名：测试甲\n证号：110101199001010029\n档案编号：123456789012\n准驾车型：C1";
        String id=upload(project,"BID",first+"\n投标有效期：90天\n"+last);
        Document original=bids.document(id);List<Page> pages=new ArrayList<>();
        for(int i=1;i<=50;i++)pages.add(new Page(i,i==1?first:i==50?last:"投标有效期：90天","PAGE","PDFBOX",null));
        if(incomplete)pages.add(new Page(51,"","PAGE","PDFBOX","[OCR未完成] 合成扫描页未识别"));
        // Controlled source fixture: record exact page text as a document snapshot, separate from all user documents.
        store.save("document",id,project,"",new Document(id,project,"BID",original.name(),original.version(),original.sha256(),original.createdAt(),pages,
            incomplete?List.of("[OCR未完成] 合成扫描页未识别"):List.of()));
        return id;
    }
    @Test void baselineCheckWithoutFullTextCheckpointPublishesBothDistantSourcesAndExport()throws Exception {
        String[] p=setup();String bid=distantCredentials(p[0],false);
        Map<String,Object> run=bids.startRun(p[0],bid);bids.execute(bids.task(run.get("taskId").toString()));
        Map<String,Object> payload=bids.run(run.get("id").toString(),false);
        Map<String,Object> report=ConsistencyRunSupport.map(payload.get("consistency"));
        Map<String,Object> group=ConsistencyRunSupport.maps(report.get("groups")).stream().filter(g->"drivingLicenseNumber".equals(g.get("field"))).findFirst().orElseThrow();
        assertThat(ConsistencyRunSupport.evidences(store,group.get("evidences"))).extracting(Evidence::page).contains(1,50);
        assertThat(ConsistencyRunSupport.evidences(store,group.get("evidences"))).allSatisfy(e->assertThat(bids.document(bid).pages().stream().filter(page->page.number()==e.page()).findFirst().orElseThrow().text()).contains(e.quote()));
        assertThat(report.get("ledgerSummary")).isNotNull();
        assertThat(new String(reports.generate(run.get("id").toString(),"html").bytes(),StandardCharsets.UTF_8)).contains("驾驶证","110101199001010011","110101199001010029");
        assertThat((List<Finding>)payload.get("results")).anyMatch(f->f.id().equals(group.get("findingId"))&&!"PASS".equals(f.evaluation().status()));
    }
    @Test void automaticVerificationAlsoPublishesCredentialFactsAndSourceLinks()throws Exception {
        String[] p=setup();String bid=distantCredentials(p[0],false);
        Map<String,Object> created=verification.start(p[0],Map.of("tenderDocumentId",p[1],"documentIds",List.of(bid),"evaluationStage","PRE_SUBMISSION"));
        assertThat(store.mapper().claimTask(created.get("taskId").toString(),BidService.now(),"person-credential-test",System.currentTimeMillis()+120000)).isEqualTo(1);
        bids.execute(bids.task(created.get("taskId").toString()));
        String runId=created.get("id").toString();Map<String,Object> detail=verification.detail(runId);
        assertThat(ConsistencyRunSupport.map(detail.get("run"))).containsEntry("status","COMPLETED");
        assertThat(ConsistencyRunSupport.maps(detail.get("results"))).anySatisfy(result->{
            assertThat(result.get("systemCheck")).isEqualTo(true);
            assertThat(result.get("businessDecision")).isEqualTo("UNDETERMINED");
            assertThat(ConsistencyRunSupport.maps(result.get("evidences"))).extracting(e->((Number)e.get("page")).intValue()).contains(1,50);
        });
        Map<String,Object> ledger=verification.factsForReport(runId);
        assertThat(ConsistencyRunSupport.maps(ledger.get("occurrences"))).anyMatch(o->"drivingLicenseNumber".equals(o.get("field")));
        assertThat(ConsistencyRunSupport.maps(ledger.get("conflicts"))).anySatisfy(conflict->assertThat((List<?>)conflict.get("occurrenceIds")).hasSizeGreaterThanOrEqualTo(2));
        assertThat(new String(verificationReports.generate(runId,"json").bytes(),StandardCharsets.UTF_8)).contains("personCredentialConsistency","drivingLicenseNumber");
    }
    @Test void incompleteLocationProducesExplicitCoverageFindingInsteadOfCleanBill()throws Exception {
        String[] p=setup();String bid=distantCredentials(p[0],true);
        Map<String,Object> run=bids.startRun(p[0],bid);bids.execute(bids.task(run.get("taskId").toString()));
        Map<String,Object> payload=bids.run(run.get("id").toString(),false);
        assertThat(ConsistencyRunSupport.map(ConsistencyRunSupport.map(payload.get("consistency")).get("coverage"))).containsEntry("status","PARTIAL");
        assertThat(ConsistencyRunSupport.map(payload.get("run"))).containsEntry("checkMode","RULES_RETRIEVAL");
        assertThat((List<Finding>)payload.get("results")).anySatisfy(f->{
            assertThat(f.evaluation().method()).isEqualTo("PERSON_CREDENTIAL_COVERAGE");
            assertThat(f.evaluation().status()).isEqualTo("UNDETERMINED");
            assertThat(ConsistencyRunSupport.map(f.assessment().get("fullTextRuleCoverage"))).containsEntry("status","PARTIAL");
        });
        assertThat(new String(reports.generate(run.get("id").toString(),"html").bytes(),StandardCharsets.UTF_8)).contains("尚未读取");
    }
    String extractionInformationOnly(String project)throws Exception {
        List<String> texts=List.of("投标有效期：90天",
            "居民身份证\n姓名：测试甲\n身份证号：110101199001010011\n性别：",
            "居民身份证\n姓名：张三 李四\n身份证号：110101199001010029",
            "居民身份证\n姓名：测试乙\n身份证号：110101********1234",
            "姓名：测试丙\n编号：ABC123");
        String id=upload(project,"BID",String.join("\n",texts));Document original=bids.document(id);
        List<Page> pages=new ArrayList<>();for(int i=0;i<texts.size();i++)pages.add(new Page(i+1,texts.get(i),"PAGE","PDFBOX",null));
        store.save("document",id,project,"",new Document(id,project,"BID",original.name(),original.version(),original.sha256(),original.createdAt(),pages,List.of()));
        return id;
    }
    @Test void extractionUncertaintyRemainsAvailableWithoutCreatingBaselineReviewFindings()throws Exception {
        String[] p=setup();String bid=extractionInformationOnly(p[0]);
        Map<String,Object> run=bids.startRun(p[0],bid);bids.execute(bids.task(run.get("taskId").toString()));
        Map<String,Object> payload=bids.run(run.get("id").toString(),false);
        Map<String,Object> report=ConsistencyRunSupport.map(payload.get("consistency"));
        Map<String,Object> coverage=ConsistencyRunSupport.map(report.get("coverage"));
        Map<String,Object> personnel=ConsistencyRunSupport.map(coverage.get("personCredentials"));
        assertThat(coverage).containsEntry("checkMode","RULES_RETRIEVAL");
        assertThat(personnel).containsEntry("status","PARTIAL").containsEntry("requiresReview",false);
        assertThat(ConsistencyRunSupport.maps(personnel.get("informationalIssues"))).isNotEmpty();
        assertThat(ConsistencyRunSupport.maps(report.get("personCredentialInformationalGroups"))).isNotEmpty();
        assertThat(ConsistencyRunSupport.maps(report.get("groups"))).noneMatch(g->String.valueOf(g.get("ruleId")).startsWith("person-credential-consistency-"));
        assertThat(ConsistencyRunSupport.maps(report.get("occurrences"))).anyMatch(o->"110101********1234".equals(o.get("rawValue")));
        assertThat((List<Finding>)payload.get("results")).noneMatch(f->"PERSON_CREDENTIAL_COVERAGE".equals(f.evaluation().method())
            ||String.valueOf(f.assessment().get("ruleId")).startsWith("person-credential-consistency-"));
    }
    @Test void extractionUncertaintyDoesNotCreateAutomaticVerificationSystemChecks()throws Exception {
        String[] p=setup();String bid=extractionInformationOnly(p[0]);
        Map<String,Object> created=verification.start(p[0],Map.of("tenderDocumentId",p[1],"documentIds",List.of(bid),"evaluationStage","PRE_SUBMISSION"));
        assertThat(store.mapper().claimTask(created.get("taskId").toString(),BidService.now(),"person-info-test",System.currentTimeMillis()+120000)).isEqualTo(1);
        bids.execute(bids.task(created.get("taskId").toString()));
        String runId=created.get("id").toString();Map<String,Object> detail=verification.detail(runId);
        assertThat(ConsistencyRunSupport.map(detail.get("run"))).containsEntry("status","COMPLETED").containsEntry("systemCheckCount",0);
        assertThat(ConsistencyRunSupport.maps(detail.get("results"))).noneMatch(r->Boolean.TRUE.equals(r.get("systemCheck")));
        Map<String,Object> ledger=verification.factsForReport(runId);
        assertThat(ConsistencyRunSupport.map(ledger.get("personCredentialCoverage"))).containsEntry("status","PARTIAL").containsEntry("requiresReview",false);
        assertThat(ConsistencyRunSupport.maps(ledger.get("personCredentialGroups"))).isEmpty();
        assertThat(ConsistencyRunSupport.maps(ledger.get("personCredentialInformationalGroups"))).isNotEmpty();
        assertThat(ConsistencyRunSupport.maps(ledger.get("conflicts"))).noneMatch(c->c.containsKey("personCredentialStatus"));
    }
}
