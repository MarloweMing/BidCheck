package cn.bidcheck.verification.engine;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import static cn.bidcheck.compat.Java8Api.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic actual compilation/native-evidence entry; never calls models. */
class DesignArchitectNativeEntryTest {
    static final String QUOTE = "设计部分负责人资格要求：具有全国注册建筑师管理委员会颁发的壹级注册建筑师执业资格证书。";
    static Plan compile(String quote)throws Exception {
        Checkpoint cp = new Checkpoint("synthetic", "设计负责人", "资格", "HIGH", "SEMANTIC_RESPONSE", "qualification", "", quote,
            "", true, true, true, new Evidence("t", "合成招标", 1, "BLOCK", quote, "p1", 1));
        Document d = new Document("t", "p", "TENDER", "合成招标", 1, "hash", "", listOf(new Page(1, quote, "BLOCK", "NATIVE",
            "")), listOf());
        ModelGateway model = new ModelGateway() {
            public boolean enabled() {
                return false;
            }
            public String identity() {
                return "none";
            }
            public JsonNode request(String op, Map<String, Object> x) {
                throw new AssertionError("No model");
            }
        };
        return new RequirementCompiler().compile(cp, new RunSession(model, mapOf("originalRequirement", quote,
            "tenderDocuments", listOf(d), "requirementProjectId", "p"), "synthetic"));
    }
    @Test
    void sourceGradeIsPreservedAndCompiledLeavesConsumeExactNativeRecord()throws Exception {
        Plan p = compile(QUOTE);
        List<Rule> rules = toList(RequirementCompiler.leaves(p.rules()).stream().filter(r->r.predicate().equals("CERTIFICATE")));
        assertFalse(rules.isEmpty(), p.issues()+" "+p.rules());
        assertTrue(p.issues().isEmpty(), p.issues().toString());
        EvidenceCorpus c = Ticket03PersonnelAttachmentIdentityTest.fixture(Ticket03PersonnelAttachmentIdentityTest.id(), "李明",
            "", true, true);
        Fact role = Ticket03PersonnelAttachmentIdentityTest.role(c);
        Fact raw = Ticket03PersonnelAttachmentIdentityTest.certificate(c);
        Map<String, Object> attrs = new LinkedHashMap<>(raw.attributes());
        attrs.put("category", "注册建筑师");
        attrs.remove("grade");
        Fact broad = new Fact("actual-shaped", raw.kind(), raw.subject(), raw.field(), raw.rawValue(), raw.value(),
            raw.unit(), raw.basis(), raw.scope(), raw.sourceIds(), attrs);
        assertNull(CertificateRecordBindings.matching(broad, c.byId), "A broad fact must not itself become exact proof");
        for(Rule r:rules) {
            assertEquals("一级注册建筑师", r.parameters().get("category"), r.id());
            assertEquals("一级", r.parameters().get("grade"));
            List<Fact> facts = new ArrayList<>(listOf(role, broad));
            facts.addAll(c.nativeFacts(listOf(r), c.all));
            assertTrue(facts.stream().anyMatch(f->"一级注册建筑师".equals(f.attributes().get("category"))));
            Decision decision = new RuleEvaluator(facts, c, mapOf()).evaluate(r);
            assertEquals("PASS", decision.status(), r.id()+" "+decision.reason()+" "+decision.gaps());
        }
    }
    static EvidenceCorpus fixture(String certificate, String extra) {
        EvidenceCorpus original = Ticket03PersonnelAttachmentIdentityTest.fixture(Ticket03PersonnelAttachmentIdentityTest.id(), "李明", extra, true, true);
        Document d = original.documents.get(0);
        List<Page> pages = new ArrayList<>();
        for(Page p:d.pages())pages.add(p.number()==7?new Page(7, certificate+"\n7", "PAGE", "PDF_OCR", ""):p);
        return new EvidenceCorpus(listOf(new Document(d.id(), d.projectId(), d.role(), d.name(), d.version(), d.sha256(),
            d.createdAt(), pages, d.warnings())));
    }
    static Fact broad(EvidenceCorpus c, String category, String number, Map<String, Object> more) {
        List<String> ids = toList(c.all.stream().filter(s->s.evidence().page()==7).map(Source::id));
        Map<String, Object> attrs = new LinkedHashMap<>(mapOf("category", category, "number", number, "numberField", "注册编号"));
        attrs.putAll(more);
        return new Fact("broad", "CERTIFICATE", "李明", "证书", number, number, "", "", "UNRESOLVED", ids, attrs);
    }
    @Test
    void broadIdentityDoesNotSatisfyQualificationWithoutExactNativeFact()throws Exception {
        EvidenceCorpus c = fixture(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        Fact f = broad(c, "注册建筑师", "20991101234", mapOf());
        Fact role = Ticket03PersonnelAttachmentIdentityTest.role(c);
        assertFalse(PersonnelAttachmentIdentity.proof(role, f, c).isEmpty());
        for(Rule r:RequirementCompiler.leaves(compile(QUOTE).rules()))assertNotEquals("PASS", new RuleEvaluator(listOf(role,
            f), c, mapOf()).evaluate(r).status());
    }
    @Test
    void otherGradeOrCategoryCannotSatisfyTheFirstGradeRequirement()throws Exception {
        for(String certificate:listOf(Ticket03PersonnelAttachmentIdentityTest.certificate().replace("一级注册建筑师", "二级注册建筑师"),
            Ticket03PersonnelAttachmentIdentityTest.certificate().replace("一级注册建筑师", "一级注册建造师"))) {
            EvidenceCorpus c = fixture(certificate, "");
            Fact f = broad(c, "注册建筑师", "20991101234", mapOf());
            List<Rule> rules = RequirementCompiler.leaves(compile(QUOTE).rules());
            List<Fact> facts = new ArrayList<>(listOf(Ticket03PersonnelAttachmentIdentityTest.role(c), f));
            facts.addAll(c.nativeFacts(rules, c.all));
            for(Rule r:rules)assertNotEquals("PASS", new RuleEvaluator(facts, c, mapOf()).evaluate(r).status());
        }
    }
    @Test
    void conflictingFieldsOtherHolderMissingNumberAndCompetingCertificateRemainRejected() {
        String original = Ticket03PersonnelAttachmentIdentityTest.certificate();
        for(String page:listOf(original.replace("20991101234", ""), original.replace("姓名：李明", "姓名：王强"))) {
            EvidenceCorpus c = fixture(page, "");
            assertTrue(PersonnelAttachmentIdentity.proof(Ticket03PersonnelAttachmentIdentityTest.role(c), broad(c, "注册建筑师",
                "20991101234", mapOf()), c).isEmpty());
        }
        EvidenceCorpus c = fixture(original, "");
        Fact role = Ticket03PersonnelAttachmentIdentityTest.role(c);
        for(Map<String, Object> attrs:Arrays.<Map<String, Object>>asList(mapOf("grade", "二级"), mapOf("employer", "另一有限公司"),
            mapOf("numberField", "执业印章号")))assertTrue(PersonnelAttachmentIdentity.proof(role, broad(c, "注册建筑师", "20991101234",
            attrs), c).isEmpty());
        for(String category:listOf("注册建造师", "二级注册建筑师"))assertTrue(PersonnelAttachmentIdentity.proof(role, broad(c, category,
            "20991101234", mapOf()), c).isEmpty());
        EvidenceCorpus competing = fixture(original, original.replace("20991101234", "20991109999"));
        assertTrue(PersonnelAttachmentIdentity.proof(Ticket03PersonnelAttachmentIdentityTest.role(competing), broad(competing,
            "注册建筑师", "20991101234", mapOf()), competing).isEmpty());
        EvidenceCorpus chunks = fixture(original+"\n"+String.join("\n", Collections.nCopies(90, "本证信息文字占位，不包含其他姓名证号。")), "");
        Fact whole = broad(chunks, "注册建筑师", "20991101234", mapOf());
        assertTrue(whole.sourceIds().size()>1);
        Fact partial = new Fact(whole.id(), whole.kind(), whole.subject(), whole.field(), whole.rawValue(), whole.value(),
            whole.unit(), whole.basis(), whole.scope(), listOf(whole.sourceIds().get(0)), whole.attributes());
        assertTrue(PersonnelAttachmentIdentity.proof(Ticket03PersonnelAttachmentIdentityTest.role(chunks), partial,
            chunks).isEmpty());
    }
    @Test
    void gradeAliasesRequireTheExactOriginalSpanAndCannotChangeGrade()throws Exception {
        Plan p = compile(QUOTE);
        Rule rule = RequirementCompiler.leaves(p.rules()).get(0);
        assertNotNull(RequirementContextBindings.validatedReference(rule, "category", "一级注册建筑师", QUOTE));
        assertNull(RequirementContextBindings.validatedReference(rule, "category", "二级注册建筑师", QUOTE));
        assertNull(RequirementContextBindings.validatedReference(rule, "category", "一级注册建筑师", QUOTE.replace("壹级", "贰级")));
    }
    @Test
    void actualEnginePublishesNativeQualificationAndRetainsIncompleteReviewGuard()throws Exception {
        EvidenceCorpus c = fixture(Ticket03PersonnelAttachmentIdentityTest.certificate(), "");
        Document tender = new Document("t", "synthetic-project", "TENDER", "合成招标", 1, "t-hash", "", listOf(new Page(1, QUOTE,
            "BLOCK", "TEXT", "")), listOf());
        Checkpoint checkpoint = new Checkpoint("synthetic", "设计负责人资格", "资格", "HIGH", "SEMANTIC_RESPONSE", "qualification", "",
            QUOTE, "", true, true, true, new Evidence("t", "合成招标", 1, "BLOCK", QUOTE, "chars:0-"+QUOTE.length(), 1));
        VerificationEngine engine = new VerificationEngine(ModelGateway.disabled(), null);
        Map<String, Object> first = engine.evaluate(checkpoint, c.documents, tender, mapOf());
        Map<String, Object> second = engine.evaluate(checkpoint, c.documents, tender, mapOf("priorSteps", first.get("steps")));
        assertEquals("PASS", first.get("businessDecision"), first.toString());
        assertEquals("PASS", second.get("businessDecision"), second.toString());
        Rule rule = RequirementCompiler.leaves(compile(QUOTE).rules()).get(0);
        List<Fact> facts = new ArrayList<>(listOf(Ticket03PersonnelAttachmentIdentityTest.role(c), broad(c, "注册建筑师",
            "20991101234", mapOf())));
        facts.addAll(c.nativeFacts(listOf(rule), c.all));
        RuleEvaluator evaluator = new RuleEvaluator(facts, c, mapOf());
        evaluator.incompleteSourceReview.add(rule.id());
        Decision closed = evaluator.evaluate(rule);
        assertEquals("UNDETERMINED", closed.status(), closed.reason());
        assertTrue(closed.gaps().stream().anyMatch(g->g.code().equals("SHARED_SOURCE_REVIEW_REQUIRED")));
    }
}
