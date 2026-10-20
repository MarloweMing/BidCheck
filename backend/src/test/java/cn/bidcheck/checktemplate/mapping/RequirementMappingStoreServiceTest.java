package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import cn.bidcheck.service.BidService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:requirement-mapping-store;DB_CLOSE_DELAY=-1",
        "bidcheck.ocr.enabled=false",
        "bidcheck.tasks.poll-ms=600000"
})
@AutoConfigureMockMvc
class RequirementMappingStoreServiceTest {
    private static final Path DATA = temp();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bidcheck.data", DATA::toString);
    }

    @Autowired Store store;
    @Autowired RequirementMappingStoreService mappings;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BidService bids;
    String project;
    String tender;

    @BeforeEach
    void fixture() {
        project = UUID.randomUUID().toString();
        tender = UUID.randomUUID().toString();
        store.save("project", project, project, "", Map.of(
                "id", project, "name", "条款覆盖测试", "activeTenderId", tender));
        store.save("document", tender, project, "", document(1, "a".repeat(64)));
    }

    @Test
    void persistsDraftOnlyCoverageWithoutCreatingExecutableChecks() {
        Map<String, Object> response = mappings.refresh(project, tender, true);

        assertThat(response).containsEntry("status", "READY")
                .containsEntry("executionState", "DRAFT_ONLY")
                .containsEntry("affectsBusinessConclusion", false);
        Map<String, Object> summary = cast(response.get("summary"));
        assertThat(number(summary, "mapped")).isGreaterThan(0);
        assertThat(number(summary, "externalRequired")).isGreaterThan(0);
        assertThat(number(summary, "unsupported")).isGreaterThan(0);
        assertThat(response.get("drafts")).asList().isNotEmpty();
        assertThat(response.get("coverage")).asList().isNotEmpty();
        assertThat(store.mapper().count("checkpoint", project, null)).isZero();
        assertThat(store.mapper().count("check_spec", project, null)).isZero();

        Map<String, Object> compact = mappings.currentSummary(project);
        assertThat(compact).containsKeys("summary", "reviewItemCount");
        assertThat(compact).doesNotContainKeys("drafts", "coverage", "reviewItems", "diagnostics");
    }

    @Test
    void apiReadsAndRefreshesCoverageButNeverPublishesIt() throws Exception {
        mvc.perform(post("/api/projects/{id}/requirement-mapping/refresh", project)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("documentId", tender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.affectsBusinessConclusion").value(false))
                .andExpect(jsonPath("$.drafts").doesNotExist())
                .andExpect(jsonPath("$.coverage").doesNotExist());

        mvc.perform(get("/api/projects/{id}/requirement-mapping", project)
                        .param("includeDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionState").value("DRAFT_ONLY"))
                .andExpect(jsonPath("$.drafts").isArray())
                .andExpect(jsonPath("$.coverage").isArray());
        assertThat(store.mapper().count("checkpoint", project, null)).isZero();
    }

    @Test
    void refusesToPresentAChangedDocumentUnderAnOldSnapshotAsReady() {
        mappings.refresh(project, tender, false);
        store.save("document", tender, project, "", document(2, "b".repeat(64)));

        assertThat(mappings.current(project, false))
                .containsEntry("status", "STALE")
                .containsEntry("affectsBusinessConclusion", false);
    }

    @Test
    void refusesToPresentASnapshotBuiltByAnOlderTreeVersionAsReady() {
        RequirementMappingSnapshot current = mappings.analyze(document(1, "a".repeat(64)));
        RequirementMappingSnapshot old = new RequirementMappingSnapshot(current.schema(),
                "document-clause-tree-1|requirement-mapper-1", current.projectId(),
                current.tenderDocumentId(), current.tenderDocumentVersion(), current.tenderDocumentHash(),
                current.generatedAt(), current.executionState(), current.affectsBusinessConclusion(),
                current.summary(), current.drafts(), current.coverage(), current.diagnostics());
        store.save("requirement_mapping", "requirement-mapping:" + tender, project, tender, old);

        assertThat(mappings.current(project, false))
                .containsEntry("status", "STALE")
                .containsEntry("affectsBusinessConclusion", false);
    }

    @Test
    void projectDetailCarriesOnlyTheCompactMappingSummary() {
        mappings.refresh(project, tender, true);
        Map<String, Object> detail = bids.detail(project, false);
        Map<String, Object> mapping = cast(detail.get("requirementMapping"));

        assertThat(mapping).containsEntry("status", "READY").containsKeys("summary", "reviewItemCount");
        assertThat(mapping).doesNotContainKeys("drafts", "coverage", "reviewItems", "diagnostics");
    }

    @Test
    void clauseSourcesRetainPageVersionHashAndExactOffsets() {
        RequirementMappingSnapshot snapshot = mappings.analyze(document(1, "a".repeat(64)));
        CoverageItem item = snapshot.coverage().stream()
                .filter(value -> !value.text().isBlank() && value.sourceRef().startOffset() != null)
                .findFirst().orElseThrow();
        String pageText = document(1, "a".repeat(64)).pages().get(item.sourceRef().location() - 1).text();

        assertThat(item.sourceRef().documentVersion()).isEqualTo(1);
        assertThat(item.sourceRef().documentHash()).isEqualTo("a".repeat(64));
        assertThat(pageText.substring(item.sourceRef().startOffset(), item.sourceRef().endOffset()))
                .isEqualTo(item.sourceRef().quote());
    }

    @Test
    void realBuilderNestsListItemsAndProducesASectionChecklistCandidate() {
        Document source = document(List.of(new Page(1, "第三章 技术要求\n"
                + "施工组织设计应包括以下内容：\n"
                + "1. 施工方案\n"
                + "2. 质量保证措施", "PAGE", "TEXT", "")));

        RequirementMappingSnapshot snapshot = mappings.analyze(source);
        RequirementDraft checklist = snapshot.drafts().stream()
                .filter(draft -> draft.templateCandidates().stream()
                        .anyMatch(candidate -> "SECTION_CHECKLIST".equals(candidate.templateId())))
                .findFirst().orElseThrow();
        TemplateCandidate candidate = checklist.templateCandidates().stream()
                .filter(value -> "SECTION_CHECKLIST".equals(value.templateId())).findFirst().orElseThrow();

        assertThat(candidate.parameters().get("requiredItems"))
                .isEqualTo(List.of("施工方案", "质量保证措施"));
        assertThat(checklist.sourceContext().sectionPath()).containsExactly("第三章 技术要求");
        assertExactSources(source, candidate.requirementRefs());
    }

    @Test
    void numberedChecklistLeadOwnsOnlyItsDeeperNumberedChildren() {
        Document source = document(List.of(new Page(1, "第三章 响应文件格式\n"
                + "1. 投标文件应包括以下内容：\n"
                + "1.1 投标函\n"
                + "1.2 报价表\n"
                + "2. 其他说明", "PAGE", "TEXT", "")));

        RequirementMappingSnapshot snapshot = mappings.analyze(source);
        RequirementDraft checklist = snapshot.drafts().stream()
                .filter(draft -> draft.text().contains("投标文件应包括以下内容"))
                .filter(draft -> draft.templateCandidates().stream()
                        .anyMatch(candidate -> "SECTION_CHECKLIST".equals(candidate.templateId())))
                .findFirst().orElseThrow();
        TemplateCandidate candidate = checklist.templateCandidates().stream()
                .filter(value -> "SECTION_CHECKLIST".equals(value.templateId())).findFirst().orElseThrow();

        assertThat(candidate.parameters().get("requiredItems"))
                .isEqualTo(List.of("投标函", "报价表"));
        assertThat(candidate.requirementRefs()).extracting(ref -> ref.quote())
                .doesNotContain("其他说明");
        assertThat(checklist.sourceContext().sectionPath()).containsExactly("第三章 响应文件格式");
        assertExactSources(source, candidate.requirementRefs());
    }

    @Test
    void checklistAndSectionContextContinueAcrossAdjacentPagesWithoutLosingSources() {
        Document source = document(List.of(
                new Page(1, "第三章 技术要求\n施工组织设计应包括以下内容：\n1. 施工方案", "PAGE", "TEXT", ""),
                new Page(2, "2. 质量保证措施\n3. 安全文明施工措施", "PAGE", "TEXT", "")));

        RequirementMappingSnapshot snapshot = mappings.analyze(source);
        RequirementDraft checklist = snapshot.drafts().stream()
                .filter(draft -> draft.templateCandidates().stream()
                        .anyMatch(candidate -> "SECTION_CHECKLIST".equals(candidate.templateId())))
                .findFirst().orElseThrow();
        TemplateCandidate candidate = checklist.templateCandidates().stream()
                .filter(value -> "SECTION_CHECKLIST".equals(value.templateId())).findFirst().orElseThrow();

        assertThat(candidate.parameters().get("requiredItems"))
                .isEqualTo(List.of("施工方案", "质量保证措施", "安全文明施工措施"));
        assertThat(candidate.requirementRefs()).extracting(ref -> ref.location())
                .containsExactly(1, 1, 2, 2);
        assertThat(checklist.sourceContext().sectionPath()).containsExactly("第三章 技术要求");
        assertExactSources(source, candidate.requirementRefs());
    }

    @Test
    void tableContinuationInheritsHeadersTableIdentityAndSectionAcrossPages() {
        Document source = document(List.of(
                new Page(1, "第三章 人员资格要求\n"
                        + "| 岗位 | 资格条件 |\n| --- | --- |\n| 技术负责人 | 应具备高级职称证书 |",
                        "PAGE", "TEXT", ""),
                new Page(2, "| 项目经理 | 应具备一级建造师证书 |", "PAGE", "TEXT", "")));

        RequirementMappingSnapshot snapshot = mappings.analyze(source);
        RequirementDraft pageTwo = snapshot.drafts().stream()
                .filter(draft -> draft.requirementRefs().stream().anyMatch(ref -> ref.location() == 2))
                .filter(draft -> draft.templateCandidates().stream()
                        .anyMatch(candidate -> "PERSONNEL_CREDENTIAL".equals(candidate.templateId())))
                .findFirst().orElseThrow();

        assertThat(pageTwo.sourceContext().sectionPath()).containsExactly("第三章 人员资格要求");
        assertThat(pageTwo.sourceContext().tableHeaders()).containsExactly("岗位", "资格条件");
        assertThat(pageTwo.sourceContext().tableId()).contains(":page:1:table:");
        assertExactSources(source, pageTwo.requirementRefs());
    }

    private Document document(int version, String hash) {
        String pageOne = "第三章 资格和响应要求\n"
                + "1. 投标人必须提供营业执照。\n"
                + "2. 项目经理不少于2人，并须具备一级注册建造师证书。\n"
                + "3. 投标人应通过信用中国网站查询失信记录。";
        String pageTwo = "第四章 其他要求\n投标人应以全息视频演示施工路径。";
        return new Document(tender, project, "TENDER", "招标文件.txt", version, hash,
                "2026-10-19T00:00:00Z", List.of(
                new Page(1, pageOne, "PAGE", "TEXT", ""),
                new Page(2, pageTwo, "PAGE", "TEXT", "")), List.of());
    }

    private Document document(List<Page> pages) {
        return new Document(tender, project, "TENDER", "招标文件.txt", 1, "a".repeat(64),
                "2026-10-19T00:00:00Z", pages, List.of());
    }

    private static void assertExactSources(Document document, List<cn.bidcheck.checktemplate.SourceRef> refs) {
        for (cn.bidcheck.checktemplate.SourceRef ref : refs) {
            Page page = document.pages().stream().filter(item -> item.number() == ref.location())
                    .findFirst().orElseThrow();
            assertThat(ref.startOffset()).isNotNull();
            assertThat(ref.endOffset()).isNotNull();
            assertThat(page.text().substring(ref.startOffset(), ref.endOffset())).isEqualTo(ref.quote());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
    private static long number(Map<String, Object> value, String key) { return ((Number) value.get(key)).longValue(); }
    private static Path temp() {
        try { return Files.createTempDirectory("bidcheck-requirement-mapping-"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
