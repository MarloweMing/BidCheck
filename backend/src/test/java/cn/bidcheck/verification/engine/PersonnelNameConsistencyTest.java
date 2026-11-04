package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Invented source-bound appointments; no private test helpers or real documents. */
class PersonnelNameConsistencyTest {
    private static final String ID_A = "110105199001010010";
    private static final String ID_B = "110105199002020026";

    private static final class Fixture {
        final EvidenceCorpus corpus;
        final List<Fact> facts = new ArrayList<>();
        Fixture(String... records) {
            List<Page> pages = new ArrayList<>();
            for (int i = 0; i < records.length; i++) pages.add(new Page(i + 1,
                "本项目投标文件\n拟派人员任职表\n" + records[i], "PAGE", "TEXT", ""));
            corpus = new EvidenceCorpus(Collections.singletonList(new Document("bid", "p", "BID", "synthetic.pdf", 1,
                "synthetic-hash", "", pages, Collections.emptyList())));
        }
        Fixture add(int page, String role, String name, String scope, String lot) {
            Source source = corpus.all.stream().filter(s -> s.evidence().page() == page).findFirst().get();
            Map<String,Object> attrs = linked("person", name);
            if (!lot.isEmpty()) attrs.put("lot", lot);
            facts.add(new Fact("f" + facts.size(), "PERSON_ROLE", role, "姓名", name, name, "", "", scope,
                Collections.singletonList(source.id()), attrs));
            return this;
        }
        Decision evaluate() { return evaluate(""); }
        Decision evaluate(String requirement) {
            return new RuleEvaluator(facts, corpus, Collections.emptyMap()).evaluate(rule(requirement));
        }
    }

    private static Rule rule(String quote) {
        return new Rule("names", "人员角色与姓名一致性", "SAME_FIELD", "", "personName", "=", "", "",
            linked("intentAuthority", "CONFIRMED_CHECKPOINT_METADATA", "intentType", "ENTITY_FIELD_CONSISTENCY",
                "comparisonScope", "CURRENT_PROJECT_OCCURRENCES"), quote, "", Collections.emptyList());
    }
    private static String text(String role, String person, String id, String lot) {
        return lot + "\n" + role + "：" + person + " 身份证号：" + id;
    }

    @Test void currentIndependentStrongRoleRecordsAreComparable() {
        Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "王甲", ID_A, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "")
            .add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "");
        assertTrue(AgentInvestigationScope.kinds(rule("")).contains("PERSON_ROLE"));
        assertTrue(AgentInvestigationScope.relevant(fixture.facts.get(0), Collections.singletonList(rule("")), fixture.facts, fixture.corpus));
        assertEquals("PASS", fixture.evaluate().status());
    }

    @Test void differentRolesAreNotComparedAgainstEachOther() {
        Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "王甲", ID_A, ""),
            text("设计负责人", "李乙", ID_B, ""), text("设计负责人", "李乙", ID_B, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "").add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "")
            .add(3, "设计负责人", "李乙", "CURRENT_PROJECT", "").add(4, "设计负责人", "李乙", "CURRENT_PROJECT", "");
        assertEquals("PASS", fixture.evaluate().status());
    }

    @Test void explicitDifferentLotsRemainSeparateComparableGroups() {
        Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, "一标段"), text("项目负责人", "王甲", ID_A, "一标段"),
            text("项目负责人", "李乙", ID_B, "二标段"), text("项目负责人", "李乙", ID_B, "二标段"))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段").add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段")
            .add(3, "项目负责人", "李乙", "CURRENT_PROJECT", "二标段").add(4, "项目负责人", "李乙", "CURRENT_PROJECT", "二标段");
        assertEquals("PASS", fixture.evaluate().status());
    }

    @Test void differentPeopleWithoutUniqueStaffingRequirementRemainUncertain() {
        Fixture fixture = new Fixture(text("安全员", "王甲", ID_A, ""), text("安全员", "李乙", ID_B, ""))
            .add(1, "安全员", "王甲", "CURRENT_PROJECT", "").add(2, "安全员", "李乙", "CURRENT_PROJECT", "");
        assertEquals("UNDETERMINED", fixture.evaluate().status());
    }

    @Test void explicitUniqueRoleWithDifferentStrongPersonsIsConflict() {
        Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "李乙", ID_B, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "").add(2, "项目负责人", "李乙", "CURRENT_PROJECT", "");
        assertEquals("FAIL", fixture.evaluate("本项目项目负责人仅配备1人，任职姓名必须一致。").status());
    }

    @Test void historicalRecordsAndDuplicateExtractionNeverSupplySecondOccurrence() {
        Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "王甲", ID_A, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "").add(2, "项目负责人", "王甲", "HISTORICAL_PROJECT", "");
        assertEquals("UNDETERMINED", fixture.evaluate().status());
        fixture.add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "");
        assertEquals("UNDETERMINED", fixture.evaluate().status());
    }

    @Test void sameNameConflictingIdentityAndUnconfirmedMaskNeverPass() {
        Fixture conflicting = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "王甲", ID_B, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "").add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "");
        assertEquals("UNDETERMINED", conflicting.evaluate().status());
        Fixture masked = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "王*", ID_A, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "").add(2, "项目负责人", "王*", "CURRENT_PROJECT", "");
        assertEquals("UNDETERMINED", masked.evaluate().status());
    }

    @Test void unknownLotOrInventedLotAttributeCannotForceComparison() {
        Fixture unknown = new Fixture(text("项目负责人", "王甲", ID_A, "一标段"), text("项目负责人", "王甲", ID_A, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段").add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "");
        assertEquals("UNDETERMINED", unknown.evaluate().status());
        Fixture forged = new Fixture(text("项目负责人", "王甲", ID_A, ""), text("项目负责人", "王甲", ID_A, ""))
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段").add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段");
        assertEquals("UNDETERMINED", forged.evaluate().status());
    }

    private static String form(String name, String id, String role) {
        return "姓名：" + name + "\n身份证号：" + id + "\n拟在本合同任职 " + role + "\n";
    }

    private static EvidenceInvestigation.Host host(Fixture fixture) {
        Rule rule = rule("");
        List<Rule> rules = Collections.singletonList(rule);
        Map<String,Object> context = new LinkedHashMap<>();
        cn.bidcheck.domain.Domain.Checkpoint checkpoint = new cn.bidcheck.domain.Domain.Checkpoint(
            "names", "人员姓名一致性", "一致性", "MEDIUM", "SEMANTIC_RESPONSE", "personName", "=", "", "",
            true, true, true, null);
        return new EvidenceInvestigation.Host(checkpoint, new Plan("ALL", rules, "SYNTHETIC", Collections.emptyList()),
            rules, fixture.corpus, new LinkedHashMap<>(), context,
            new SharedFactReuse(fixture.corpus, context, "synthetic", "synthetic"),
            new RunSession(ModelGateway.disabled(), context, "synthetic"), null, new ArrayList<>(), Collections.emptySet());
    }

    private static Map<String,Object> read(EvidenceInvestigation.Host host, int page) {
        Source source = host.corpus.all.stream().filter(s -> s.evidence().page() == page).findFirst().get();
        return host.call("read_section", linked("targetObligationId", "names", "sourceId", source.id()));
    }

    @Test void hostReadExtractsOnlyDisclosedExplicitAppointmentAndRecomputesTheActualRule() {
        Fixture fixture = new Fixture(form("王甲", ID_A, "项目负责人"), form("王甲", ID_A, "项目负责人"),
            form("李乙", ID_B, "项目负责人"));
        EvidenceInvestigation.Host host = host(fixture);
        host.start();
        fixture.corpus.all.forEach(source -> host.disclosed.put(source.id(), source));
        assertTrue(host.facts.isEmpty(), "Navigation is not disclosure");
        Map<String,Object> first = read(host, 1);
        assertEquals("COMPLETED", first.get("status"));
        assertFalse(ProofVerifier.strings(first.get("acceptedFactIds")).isEmpty(), first.toString());
        assertEquals("UNDETERMINED", host.evaluator.decisions.get("names").status());
        Map<String,Object> second = read(host, 2);
        assertEquals("COMPLETED", second.get("status"));
        assertEquals("PASS", host.evaluator.decisions.get("names").status(), host.evaluator.decisions.get("names").map().toString());
        assertTrue(host.facts.values().stream().noneMatch(f -> "李乙".equals(f.value())), "Unread source must remain undisclosed");
    }

    @Test void hostReadDoesNotPromoteHistoricalOrUnknownAppointmentRoles() {
        Fixture fixture = new Fixture("类似工程业绩\n历史项目任职\n" + form("王甲", ID_A, "项目负责人"),
            form("王甲", ID_A, "未定义岗位"));
        EvidenceInvestigation.Host host = host(fixture);
        host.start();
        fixture.corpus.all.forEach(source -> host.disclosed.put(source.id(), source));
        assertEquals("COMPLETED", read(host, 1).get("status"));
        assertEquals("COMPLETED", read(host, 2).get("status"));
        assertTrue(host.facts.values().stream().noneMatch(f -> "PERSON_ROLE".equals(f.kind())), host.facts.toString());
        assertEquals("UNDETERMINED", host.evaluator.decisions.get("names").status());
    }

    @Test void ambiguousMultipleLotLabelsCannotValidateAChosenLotAttribute() {
        String record = text("项目负责人", "王甲", ID_A, "一标段\n二标段");
        Fixture fixture = new Fixture(record, record)
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段")
            .add(2, "项目负责人", "王甲", "CURRENT_PROJECT", "一标段");
        assertEquals("UNDETERMINED", fixture.evaluate().status());
    }

    @Test void repeatedNameWithinOneAppointmentIsNotAnIndependentComparisonRecord() {
        Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, "") + "\n王甲负责本记录签收。")
            .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "");
        assertEquals("UNDETERMINED", fixture.evaluate().status());
    }

    @Test void unrelatedAuxiliaryCitationCannotTurnOneAppointmentIntoAComparisonPair() {
        for (String auxiliary : Arrays.asList("其他正文未记载人员岗位。", "王甲为其他段落的联系人。")) {
            Fixture fixture = new Fixture(text("项目负责人", "王甲", ID_A, "") + "\n王甲负责本记录签收。", auxiliary)
                .add(1, "项目负责人", "王甲", "CURRENT_PROJECT", "");
            Fact original = fixture.facts.get(0);
            List<String> sources = new ArrayList<>(original.sourceIds());
            sources.add(fixture.corpus.all.stream().filter(source -> source.evidence().page() == 2).findFirst().get().id());
            Fact enlarged = new Fact(original.id(), original.kind(), original.subject(), original.field(), original.rawValue(),
                original.value(), original.unit(), original.basis(), original.scope(), sources, original.attributes());
            fixture.facts.set(0, enlarged);
            assertNull(new ProofVerifier().issue(enlarged, fixture.corpus.byId), "The aggregate fact itself is valid");
            assertEquals("UNDETERMINED", fixture.evaluate().status(), auxiliary);
            fixture.facts.add(new Fact("duplicate", enlarged.kind(), enlarged.subject(), enlarged.field(), enlarged.rawValue(),
                enlarged.value(), enlarged.unit(), enlarged.basis(), enlarged.scope(), enlarged.sourceIds(), enlarged.attributes()));
            assertEquals("UNDETERMINED", fixture.evaluate().status(), "Duplicating the aggregate fact cannot help");
        }
    }
}
