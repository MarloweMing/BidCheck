package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.agent.AgentInvestigator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static cn.bidcheck.compat.Java8Api.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic documents and a local no-op investigator; no model, database or real bid. */
class AgentCertificateOriginalReadTest {
    private static final String QUOTE = DesignArchitectNativeEntryTest.QUOTE;

    private EvidenceCorpus corpus(String original, String extra) {
        return DesignArchitectNativeEntryTest.fixture(original + "\n全国注册建筑师管理委员会", extra);
    }

    private Fact broad(EvidenceCorpus corpus, String number) {
        return DesignArchitectNativeEntryTest.broad(corpus, "注册建筑师", number, mapOf());
    }

    private Map<String, Object> context(EvidenceCorpus corpus, Fact certificate) {
        List<Map<String, Object>> entries = listOf(
            SharedFactReuseTest.entry(corpus, SharedFactReuse.value(Ticket03PersonnelAttachmentIdentityTest.role(corpus))),
            SharedFactReuseTest.entry(corpus, SharedFactReuse.value(certificate)));
        Map<String, Object> context = linked("originalRequirement", QUOTE);
        context.put("sharedFactLookup", (Function<Map<String, Object>, Object>) request -> entries);
        context.put("enableProgrammaticInferences", true);
        return context;
    }

    private Checkpoint checkpoint() {
        return new Checkpoint("synthetic", "设计负责人资格", "资格", "HIGH", "SEMANTIC_RESPONSE",
            "qualification", "", QUOTE, "", true, true, true,
            new Evidence("t", "合成招标", 1, "BLOCK", QUOTE, "chars:0-" + QUOTE.length(), 1));
    }

    private Document tender() {
        return new Document("t", "synthetic-project", "TENDER", "合成招标", 1, "t-hash", "",
            listOf(new Page(1, QUOTE, "BLOCK", "TEXT", "")), listOf());
    }

    private EvidenceInvestigation.Host host(EvidenceCorpus corpus, Fact certificate) throws Exception {
        return host(corpus, certificate, false);
    }

    private EvidenceInvestigation.Host host(EvidenceCorpus corpus, Fact certificate, boolean incomplete) throws Exception {
        Plan plan = DesignArchitectNativeEntryTest.compile(QUOTE);
        Map<String, Object> context = context(corpus, certificate);
        return new EvidenceInvestigation.Host(checkpoint(), plan, VerificationEngine.evidenceRules(plan.rules()),
            corpus, new LinkedHashMap<>(), context, new SharedFactReuse(corpus, context, "synthetic", "synthetic"),
            new RunSession(ModelGateway.disabled(), context, "synthetic"), null, new ArrayList<>(),
            incomplete ? Collections.singleton(plan.rules().get(0).id()) : Collections.emptySet());
    }

    @Test
    void initialSubjectPreloadReadsTheStronglyBoundOriginalBeforeProposingConfirmation() throws Exception {
        EvidenceCorpus corpus = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        EvidenceInvestigation.Host host = host(corpus, broad(corpus, "20991101234"));
        assertTrue(host.disclosed.isEmpty());
        host.start();
        assertEquals("PASS", host.decision.status(), host.decision.map().toString());
        assertTrue(host.facts.values().stream().anyMatch(f -> "一级注册建筑师".equals(f.attributes().get("category"))));
        assertTrue(host.facts.values().stream().anyMatch(f -> "注册建筑师".equals(f.attributes().get("category"))),
            "The original coarse fact must remain unchanged");
        assertTrue(RuleEvaluator.maps(host.ctx.get("inferredFacts")).isEmpty(), "No needless identity proposal");
        assertTrue(host.disclosed.values().stream().anyMatch(s -> s.evidence().page() == 7));
    }

    @Test
    void agentLedAndOrdinaryEngineBothPublishTheIndependentlyParsedQualification() {
        for (String mode : listOf("AGENT_LED", "ORDINARY")) {
            EvidenceCorpus corpus = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
            VerificationEngine engine = new VerificationEngine(ModelGateway.disabled(), null);
            engine.setInvestigator(new AgentInvestigator() {
                public boolean enabled() { return true; }
                public String identity() { return "synthetic-no-model"; }
                public Map<String, Object> investigate(Map<String, Object> start, ToolHandler tools,
                                                       Consumer<Map<String, Object>> events) {
                    return linked("status", "COMPLETED", "modelCalls", 0, "toolCalls", 0);
                }
            });
            Map<String, Object> context = "AGENT_LED".equals(mode)
                ? context(corpus, broad(corpus, "20991101234")) : new LinkedHashMap<>();
            context.put("reviewMode", mode);
            Map<String, Object> result = engine.evaluate(checkpoint(), corpus.documents, tender(), context);
            assertEquals("PASS", result.get("businessDecision"), mode + ": " + result);
        }
    }

    @Test
    void ordinarySharedReuseStillRequiresItsIndependentSourceReview() {
        EvidenceCorpus corpus = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        VerificationEngine engine = new VerificationEngine(ModelGateway.disabled(), null);
        Map<String, Object> result = engine.evaluate(checkpoint(), corpus.documents, tender(),
            context(corpus, broad(corpus, "20991101234")));
        assertEquals("UNDETERMINED", result.get("businessDecision"));
        assertTrue(result.toString().contains("SHARED_SOURCE_REVIEW_REQUIRED"));
    }

    @Test
    void originalReadCannotOverrideAnIncompleteSourceReview() throws Exception {
        EvidenceCorpus corpus = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        EvidenceInvestigation.Host host = host(corpus, broad(corpus, "20991101234"), true);
        host.start();
        assertTrue(host.disclosed.values().stream().anyMatch(s -> s.evidence().page() == 7));
        assertEquals("UNDETERMINED", host.decision.status());
        assertTrue(host.decision.gaps().stream().anyMatch(g -> "SHARED_SOURCE_REVIEW_REQUIRED".equals(g.code())));
    }

    @Test
    void oneWrongIdentityDigitCannotAuthorizeOriginalPreload() throws Exception {
        EvidenceCorpus valid = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        String identity = Ticket03PersonnelAttachmentIdentityTest.id();
        String wrong = identity.substring(0, 17) + (identity.endsWith("0") ? "1" : "0");
        Document original = valid.documents.get(0);
        List<Page> pages = new ArrayList<>();
        for (Page page : original.pages()) {
            pages.add(new Page(page.number(), page.text().replace(identity, wrong), "PAGE", "PDF_OCR", ""));
        }
        EvidenceCorpus invalid = new EvidenceCorpus(listOf(new Document(original.id(), original.projectId(),
            original.role(), original.name(), original.version(), original.sha256(), "", pages, listOf())));
        EvidenceInvestigation.Host host = host(invalid, broad(invalid, "20991101234"));
        host.start();
        assertNotEquals("PASS", host.decision.status());
        assertFalse(host.disclosed.values().stream().anyMatch(s -> s.evidence().page() == 7));
    }

    @Test
    void wrongGradeCategoryHolderNumberAndCompetingOriginalDoNotPass() throws Exception {
        String original = Ticket03PersonnelAttachmentIdentityTest.certificate();
        for (String text : listOf(original.replace("一级注册建筑师", "二级注册建筑师"),
            original.replace("一级注册建筑师", "一级注册建造师"), original.replace("姓名：李明", "姓名：王强"),
            original.replace("20991101234", ""))) {
            EvidenceCorpus corpus = corpus(text, "");
            EvidenceInvestigation.Host host = host(corpus, broad(corpus, "20991101234"));
            host.start();
            assertNotEquals("PASS", host.decision.status(), text);
        }
        EvidenceCorpus corpus = corpus(original, original.replace("20991101234", "20991109999"));
        EvidenceInvestigation.Host host = host(corpus, broad(corpus, "20991101234"));
        host.start();
        assertNotEquals("PASS", host.decision.status());
        assertFalse(host.disclosed.values().stream().anyMatch(s -> s.evidence().page() == 7));
    }

    @Test
    void aDifferentNumberOrIncompleteOriginalReferenceCannotAuthorizeTheRead() throws Exception {
        EvidenceCorpus corpus = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        EvidenceInvestigation.Host wrong = host(corpus, broad(corpus, "20991101235"));
        wrong.start();
        assertNotEquals("PASS", wrong.decision.status());
        assertFalse(wrong.disclosed.values().stream().anyMatch(s -> s.evidence().page() == 7));

        EvidenceCorpus split = corpus(Ticket03PersonnelAttachmentIdentityTest.certificate() + "\n" +
            String.join("\n", Collections.nCopies(90, "本证信息文字占位，不包含其他姓名证号。")), "");
        Fact complete = broad(split, "20991101234");
        assertTrue(complete.sourceIds().size() > 1);
        Fact partial = new Fact(complete.id(), complete.kind(), complete.subject(), complete.field(), complete.rawValue(),
            complete.value(), complete.unit(), complete.basis(), complete.scope(), listOf(complete.sourceIds().get(0)),
            complete.attributes());
        EvidenceInvestigation.Host incomplete = host(split, partial);
        incomplete.start();
        assertNotEquals("PASS", incomplete.decision.status());
        assertFalse(incomplete.disclosed.values().stream().anyMatch(s -> s.evidence().page() == 7));
    }
}
