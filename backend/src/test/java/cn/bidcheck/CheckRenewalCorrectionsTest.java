package cn.bidcheck;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.TaskRow;
import cn.bidcheck.service.BidService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:check-renewal-corrections;DB_CLOSE_DELAY=-1", "bidcheck.tasks.poll-ms=600000"})
class CheckRenewalCorrectionsTest {
    private static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("bidcheck-renewal-corrections-"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("bidcheck.data", () -> DATA.toString());
    }
    @Autowired BidService service;
    @Autowired Store store;

    @Test void existingAutomaticRulesRequireRefreshAndCurrentVersionIsBoundToSelectedTender() throws Exception {
        String project=project();
        selectTender(project,"项目名称：升级验证\n项目编号：UPGRADE\n资格要求\n提供营业执照复印件");
        String first=store.getMap("project",project).get("activeTenderId").toString();
        String bid=parse(project,"BID","营业执照复印件\n企业名称：测试公司");
        Map<String,Object> old=store.getMap("project",project);
        old.remove("checkpointRuleVersions");old.put("checkpointRuleVersion","old-rules");store.save("project",project,project,"",old);
        assertThat(((Map<?,?>)service.detail(project).get("project")).get("checkpointRulesOutdated")).isEqualTo(true);
        assertThatThrownBy(()->service.startRun(project,bid)).hasMessageContaining("提取规则已更新");
        service.execute(service.extract(project,first));
        assertThat(((Map<?,?>)service.detail(project).get("project")).get("checkpointRulesOutdated")).isEqualTo(false);
        String second=parse(project,"TENDER","项目名称：第二版本\n资格要求\n提供营业执照复印件");
        service.execute(service.extract(project,second));
        Map<String,Object> selected=store.getMap("project",project);
        selected.put("checkpointRuleVersions",cn.bidcheck.compat.Java8Api.mapOf(second, BidService.RULE_VERSION));
        selected.put("activeTenderId",first);selected.put("informationTenderId",first);
        store.save("project",project,project,"",selected);
        assertThatThrownBy(()->service.startRun(project,bid)).hasMessageContaining("提取规则已更新");
    }

    @Test void laterParseCannotOverwriteAnExplicitlyReselectedTender() throws Exception {
        String project = project();
        String first = parse(project, "TENDER", "项目名称：第一项目\n项目编号：FIRST\n开标时间：2026-11-03");
        service.execute(service.extract(project, first));
        TaskRow second = upload(project, "TENDER", "项目名称：第二项目\n项目编号：SECOND\n开标时间：2026-12-01");
        TaskRow selection = service.extract(project, first);
        service.execute(selection);
        service.execute(second);
        assertThat(store.getMap("project", project))
                .containsEntry("activeTenderId", first)
                .containsEntry("informationTenderId", first)
                .containsEntry("requestedInformationTenderId", first)
                .containsEntry("name", "第一项目")
                .containsEntry("projectNumber", "FIRST")
                .containsEntry("openingTime", "2026-11-03");
        assertThat(service.document(documentId(second)).name()).isEqualTo("修正样例.txt");
    }

    @Test void nameAndNumberCorrectionsCreateNewRunsWhileOldInformationStaysFrozen() throws Exception {
        String project = project();
        selectTender(project, "项目名称：原项目\n项目编号：OLD-01\n开标时间：2026-11-03");
        String bid = parse(project, "BID", "项目名称：原项目\n项目编号：OLD-01");
        Map<String,Object> first = service.startRun(project, bid);
        service.updateProject(project, cn.bidcheck.compat.Java8Api.mapOf("name", "人工核定项目"));
        Map<String,Object> second = service.startRun(project, bid);
        service.updateProject(project, cn.bidcheck.compat.Java8Api.mapOf("projectNumber", "CORRECTED-01"));
        Map<String,Object> third = service.startRun(project, bid);
        assertThat(second.get("id")).isNotEqualTo(first.get("id"));
        assertThat(third.get("id")).isNotEqualTo(second.get("id"));
        assertThat(service.startRun(project, bid).get("id")).isEqualTo(third.get("id"));
        assertThat(information(store.getMap("run", first.get("id").toString())))
                .containsEntry("name", "原项目").containsEntry("projectNumber", "OLD-01");
        assertThat(information(second)).containsEntry("name", "人工核定项目").containsEntry("projectNumber", "OLD-01");
        assertThat(information(third)).containsEntry("name", "人工核定项目").containsEntry("projectNumber", "CORRECTED-01");
    }

    @Test void manuallyClearingOpeningDateDoesNotReuseExtractedDates() throws Exception {
        String project = project();
        selectTender(project, "项目名称：清空日期核验\n开标时间：2026-11-03");
        String bid = parse(project, "BID", "签署日期：2026-11-04\n证书有效期至：2026-11-04");
        String previous = service.startRun(project, bid).get("id").toString();
        service.updateProject(project, cn.bidcheck.compat.Java8Api.mapOf("openingTime", ""));
        Map<String,Object> current = service.startRun(project, bid);
        assertThat(current.get("id")).isNotEqualTo(previous);
        assertThat(information(current)).containsEntry("openingTime", "");
        List<Checkpoint> snapshot = snapshot(project, current);
        assertThat(rule(snapshot, "signingAfterOpening").expected()).isEmpty();
        assertThat(rule(snapshot, "certificateCoverage").expected()).isEmpty();
        assertThat(snapshot).noneMatch(checkpoint -> "certificateExpiry".equals(checkpoint.field()));
        assertThat(rule(store.list("snapshot", project, previous, Checkpoint.class), "certificateCoverage").expected())
                .startsWith("2026-11-03");
    }

    @Test void conflictingOpeningDatesRemainUnresolvedInTheRunSnapshot() throws Exception {
        String project = project();
        selectTender(project, "项目名称：日期冲突项目\n开标时间：2026-11-03\n开标时间：2026-11-05");
        String bid = parse(project, "BID", "签署日期：2026-11-04");
        Map<String,Object> run = service.startRun(project, bid);
        assertThat(information(run)).containsEntry("openingTime", "");
        assertThat(rule(snapshot(project, run), "signingAfterOpening").expected()).isEmpty();
        assertThat(rule(snapshot(project, run), "certificateCoverage").expected()).isEmpty();
    }

    @Test void correctingOpeningDateUpdatesTheSharedCertificateCoverageRule() throws Exception {
        String project = project();
        selectTender(project, "项目名称：证照基准项目\n开标时间：2026-11-03 09:30");
        String bid = parse(project, "BID", "证书有效期至：2026-11-04");
        Map<String,Object> original = service.startRun(project, bid);
        service.updateProject(project, cn.bidcheck.compat.Java8Api.mapOf("openingTime", "2026-11-05T10:00"));
        Map<String,Object> corrected = service.startRun(project, bid);
        List<Checkpoint> snapshot = snapshot(project, corrected);
        assertThat(rule(snapshot, "signingAfterOpening").expected()).isEqualTo("2026-11-05T10:00");
        assertThat(rule(snapshot, "certificateCoverage").expected()).startsWith("2026-11-05");
        assertThat(snapshot).noneMatch(checkpoint -> "certificateExpiry".equals(checkpoint.field()));
        assertThat(rule(snapshot(project, original), "certificateCoverage").expected()).startsWith("2026-11-03");
    }

    @Test void independentBidDeadlineIsNotReplacedByTheCorrectedOpeningDate() throws Exception {
        String project = project();
        selectTender(project, "项目名称：独立截止日期\n开标时间：2026-11-03\n投标截止时间：2026-11-02");
        String bid = parse(project, "BID", "证书有效期至：2026-11-04");
        service.updateProject(project, cn.bidcheck.compat.Java8Api.mapOf("openingTime", "2026-11-05T10:00"));
        List<Checkpoint> snapshot = snapshot(project, service.startRun(project, bid));
        assertThat(rule(snapshot, "signingAfterOpening").expected()).isEqualTo("2026-11-05T10:00");
        assertThat(rule(snapshot, "certificateCoverage").expected()).isEqualTo("2026-11-02");
        assertThat(snapshot).noneMatch(checkpoint -> "certificateExpiry".equals(checkpoint.field()));
    }

    @Test void explicitlyEnteredCreationFieldsSurviveMissingAndConflictingExtraction() throws Exception {
        String project = service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "人工创建项目", "projectNumber", "MANUAL-01", "openingTime", "2026-11-05 10:00"))
                .get("id").toString();
        parse(project, "TENDER", "投标有效期：90天");
        assertThat(store.getMap("project", project))
                .containsEntry("projectNumber", "MANUAL-01").containsEntry("openingTime", "2026-11-05T10:00");
        String replacement = parse(project, "TENDER", "项目编号：EXTRACTED-02\n开标时间：2026-11-07");
        service.execute(service.extract(project, replacement));
        Map<String,Object> information = store.getMap("project", project);
        assertThat(information).containsEntry("projectNumber", "MANUAL-01").containsEntry("code", "MANUAL-01")
                .containsEntry("openingTime", "2026-11-05T10:00");
        assertThat(information.get("manualFields")).asList().contains("projectNumber", "openingTime");
    }

    private String project() { return service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "待解析项目")).get("id").toString(); }
    private TaskRow upload(String project, String role, String content) throws Exception {
        return service.upload(project, role, new MockMultipartFile("file", "修正样例.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8)));
    }
    private String parse(String project, String role, String content) throws Exception {
        TaskRow task = upload(project, role, content);
        service.execute(task);
        return documentId(task);
    }
    private String documentId(TaskRow task) { return store.map(task.inputJson).get("documentId").toString(); }
    @SuppressWarnings("unchecked")
    private void selectTender(String project, String content) throws Exception {
        service.execute(service.extract(project, parse(project, "TENDER", content)));
        for (Checkpoint c : (List<Checkpoint>) service.detail(project).get("checkpoints")) {
            service.updateCheckpoint(c.id(), new Checkpoint(c.id(), c.title(), c.category(), c.severity(), c.type(), c.field(),
                    c.operator(), c.expected(), c.unit(), c.required(), true, c.enabled(), c.source()));
        }
    }
    private List<Checkpoint> snapshot(String project, Map<String,Object> run) {
        return store.list("snapshot", project, run.get("id").toString(), Checkpoint.class);
    }
    private Checkpoint rule(List<Checkpoint> rules, String field) {
        return rules.stream().filter(rule -> field.equals(rule.field())).findFirst().orElseThrow(java.util.NoSuchElementException::new);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> information(Map<String,Object> run) { return (Map<String,Object>) run.get("projectInformation"); }
}
