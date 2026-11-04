package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Entirely invented originals, no saved bid or provider. */
class CompoundAppointmentRoutingTest {
    private static final String PROJECT = "合成中心综合楼二次装修工程";
    private static final String COMPANY = "合成建设有限公司";
    private static final String DESIGN = "合成设计有限公司";
    private static final String REQUIREMENT = "本项目接受联合体投标。联合体投标时须提交联合体文件（见第六章中《联合体文件》）。由施工单位作为联合体牵头人，且项目负责人应由牵头单位委派。联合体成员（含牵头单位）不得超过2家。";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void passingMaterialLeavesOnlyTypedAppointmentForAgent() {
        Fixture f = new Fixture(true, false);
        assertEquals("UNDETERMINED", f.parentDecision().status());
        assertTrue(f.parentDecision().gaps().stream().anyMatch(g -> g.code().equals("SUBJECT_RELATION_UNPROVEN")), f.parentDecision().map().toString());
        List<Rule> pending = f.pending(Collections.emptyMap());
        assertEquals(Collections.singletonList(f.parent.id() + "-APPOINT"), ids(pending));
        EvidenceInvestigation.Host host = f.host(pending);
        Map<String,Object> start = host.start();
        Map<String,Object> context = (Map<String,Object>) start.get("context");
        List<Map<String,Object>> contracts = RuleEvaluator.maps(context.get("allowedFactContracts"));
        assertTrue(contracts.stream().anyMatch(c -> (f.parent.id() + "-APPOINT").equals(c.get("obligationId"))
            && ((Collection<?>) c.get("factKinds")).contains("RELATION") && ((Collection<?>) c.get("relationFields")).contains("委派")));
        assertNotEquals("PASS", host.decision.status());
    }

    @Test void originalDelegationCanBeSubmittedAndLocallyRecomputed() {
        Fixture f = new Fixture(true, false);
        EvidenceInvestigation.Host host = f.host(f.pending(Collections.emptyMap()));
        host.start();
        String target = f.parent.id() + "-APPOINT";
        Source source = f.corpus.all.stream().filter(s -> s.evidence().page() == 3).findFirst().get();
        host.navigation.put(source.id(), source);
        host.call("read_section", linked("targetObligationId", target, "sourceId", source.id()));
        Map<String,Object> result = host.call("submit_candidate_fact", linked("targetObligationId", target,
            "fact", relation(source, "委派", "CURRENT_PROJECT")));
        assertFalse(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty(), result.toString());
        Map<String,Object> receipt = host.recompute();
        assertEquals(true, receipt.get("businessCheckCompleted"), receipt.toString());
        assertEquals("PASS", receipt.get("businessDecision"));
        assertTrue(ProofVerifier.strings(receipt.get("completedTargetObligationIds")).contains(target));
    }

    @Test void employmentRegistrationAndHistoricalDelegationCannotReplaceCurrentDelegation() {
        for (String field : Arrays.asList("聘用", "注册", "委派")) {
            Fixture f = new Fixture(true, false);
            EvidenceInvestigation.Host host = f.host(f.pending(Collections.emptyMap())); host.start();
            Source source = f.corpus.all.stream().filter(s -> s.evidence().page() == 3).findFirst().get();
            host.navigation.put(source.id(), source);
            host.call("read_section", linked("targetObligationId", f.parent.id()+"-APPOINT", "sourceId", source.id()));
            host.call("submit_candidate_fact", linked("targetObligationId", f.parent.id()+"-APPOINT",
                "fact", relation(source, field, field.equals("委派") ? "HISTORICAL_PROJECT" : "CURRENT_PROJECT")));
            assertNotEquals("PASS", host.recompute().get("businessDecision"));
        }
    }

    @Test void unresolvedMembersDoNotAuthorizeAppointmentOrImplyNotApplicable() {
        Fixture f = new Fixture(false, false);
        assertNotEquals("NOT_APPLICABLE", f.parentDecision().status());
        List<Rule> pending = f.pending(Collections.emptyMap());
        assertFalse(pending.isEmpty());
        assertFalse(ids(pending).contains(f.parent.id()+"-APPOINT"));
        assertTrue(pending.stream().anyMatch(r -> "RELATION".equals(r.predicate()) && "联合体成员".equals(r.subject())));
    }

    @Test void verifiedNonConsortiumDoesNotActivateAppointment() {
        Fixture f = new Fixture(false, true);
        assertEquals("NOT_APPLICABLE", f.parentDecision().status(), f.parentDecision().map().toString());
        assertTrue(f.pending(Collections.emptyMap()).isEmpty());
    }

    @Test void serviceAdmissionWaitsForRealMembershipPrerequisite() {
        Fixture f = new Fixture(false, false);
        String target = f.parent.id()+"-APPOINT";
        Map<String,String> unavailable = RecoveryTargets.unavailableEvidenceTargets(JSON.convertValue(f.plan.rules(), List.class),
            Collections.singletonList(f.parentDecision().map()), Collections.singletonList(target));
        assertTrue(unavailable.containsKey(target), unavailable.toString());
        assertTrue(f.pending(linked("recoveryStrategy", "TARGETED_EVIDENCE", "recoveryTargetObligationIds", Collections.singletonList(target))).isEmpty());
        Fixture ready = new Fixture(true, false);
        assertFalse(RecoveryTargets.unavailableEvidenceTargets(JSON.convertValue(ready.plan.rules(), List.class),
            Collections.singletonList(ready.parentDecision().map()), Collections.singletonList(target)).containsKey(target));
    }

    @Test void recoveryResolvesExactChildAndRejectsForgedParentLink() {
        Fixture f = new Fixture(true, false);
        List<Rule> executable = VerificationEngine.evidenceRules(f.plan.rules());
        Map<String,Object> context = linked("recoveryStrategy", "TARGETED_EVIDENCE",
            "recoveryTargetObligationIds", Collections.singletonList(f.parent.id()+"-APPOINT"));
        assertEquals(Collections.singletonList(f.parent.id()+"-APPOINT"), ids(f.pending(context)));
        Map<String,Map<String,Object>> deps = RecoveryTargets.readDependencies(JSON.convertValue(f.plan.rules(), List.class));
        assertEquals(f.parent.id(), deps.get(f.parent.id()+"-APPOINT").get("parentId"));
        Rule original = executable.stream().filter(r -> r.id().endsWith("-APPOINT")).findFirst().get();
        Rule forged = new Rule(original.id(), original.description(), "MATERIAL", original.subject(), original.field(),
            original.operator(), original.expected(), original.unit(), original.parameters(), original.sourceQuote(), original.logic(), original.children());
        assertFalse(SourceScoringRules.readParents(f.plan.rules(), Collections.singletonList(forged)).containsKey(original.id()));
    }

    private Map<String,Object> relation(Source source, String field, String scope) {
        return linked("kind", "RELATION", "subject", "李明", "field", field, "rawValue", source.evidence().quote(),
            "value", COMPANY, "scope", scope, "sourceIds", Collections.singletonList(source.id()), "attributes", linked("object", COMPANY));
    }
    private List<String> ids(List<Rule> rules) { List<String> ids = new ArrayList<>(); for (Rule r : rules) ids.add(r.id()); return ids; }

    private static class Fixture {
        final EvidenceCorpus corpus;
        final Rule parent;
        final Plan plan;
        final Checkpoint cp;
        final Map<String,Object> context = new LinkedHashMap<>();
        final Map<String,Fact> facts = new LinkedHashMap<>();
        Fixture(boolean members, boolean solo) {
            Document tender = new Document("t", "p", "TENDER", "合成招标", 1, "th", "",
                Arrays.asList(new Page(1, PROJECT, "PAGE", "TEXT", ""), new Page(2, REQUIREMENT, "PAGE", "TEXT", "")), Collections.emptyList());
            cp = new Checkpoint("cp", "联合体", "材料", "HIGH", "SEMANTIC_RESPONSE", "", "", REQUIREMENT, "", true, true, true,
                new Evidence("t", "合成招标", 2, "PAGE", REQUIREMENT, "p2", 1));
            context.put("tenderDocuments", Collections.singletonList(tender));
            context.put("requirementProjectId", "p");
            Rule raw = CompoundBusinessRequirements.compile(cp, REQUIREMENT);
            Map<String,Object> params = new LinkedHashMap<>(raw.parameters());
            params.put("requirementSourceContext", RequirementSourceContext.resolve(cp, REQUIREMENT, context).map());
            parent = new Rule(raw.id(), raw.description(), raw.predicate(), raw.subject(), raw.field(), raw.operator(), raw.expected(), raw.unit(), params, raw.sourceQuote(), raw.logic(), raw.children());
            plan = new Plan("ALL", Collections.singletonList(parent), "SYNTHETIC", Collections.emptyList());
            String agreement = members ? "6.联合体协议书\n" + COMPANY + "、" + DESIGN + "[所有成员单位名称]自愿组成：联合体，共同投标"+PROJECT+"/标段。现就联合体应答事宜订立如下协议：\n共同承担合同责任，并对招标人承担连带责任。\n联合体牵头人："+COMPANY+"。\n牵头单位："+COMPANY+"。\n"+COMPANY+"负责施工部分。" : solo ? "投标函\n（"+PROJECT+"）投标文件\n投标人："+COMPANY+"。\n本次投标不采用联合体形式。" : "联合体协议书\n拟组成联合体，成员尚未确定。";
            Document bid = new Document("b", "p", "BID", "合成投标", 1, "bh", "", Arrays.asList(
                new Page(1, "投标文件\n（"+PROJECT+"）\n投标人："+COMPANY, "PAGE", "TEXT", ""),
                new Page(2, agreement, "PAGE", "TEXT", ""),
                new Page(3, "本项目拟派人员\n姓名：李明\n拟在本合同任职：项目负责人\n李明由"+COMPANY+"委派。\n李明与"+COMPANY+"建立聘用关系。\n李明注册单位："+COMPANY+"。", "PAGE", "TEXT", "")), Collections.emptyList());
            corpus = new EvidenceCorpus(Collections.singletonList(bid));
            List<Rule> roles = new ArrayList<>();
            for (String role : Arrays.asList("投标人", "联合体成员", "联合体牵头人", "施工单位", "牵头单位"))
                roles.add(new Rule("role-"+role, role, "RELATION", role, "名称", "", "", "", linked(), "", "", Collections.emptyList()));
            roles.add(new Rule("person", "项目负责人", "PERSON_ROLE", "项目负责人", "姓名", "", "", "", linked(), "", "", Collections.emptyList()));
            for (Fact fact : corpus.nativeFacts(roles, corpus.all)) facts.put(fact.id(), fact);
        }
        RuleEvaluator evaluator() { RuleEvaluator e = new RuleEvaluator(new ArrayList<>(facts.values()), corpus, context); e.evaluate(parent); return e; }
        Decision parentDecision() { return evaluator().decisions.get(parent.id()); }
        List<Rule> pending(Map<String,Object> ctx) { return AgentInvestigationScope.pending(plan, VerificationEngine.evidenceRules(plan.rules()), ctx, evaluator()); }
        EvidenceInvestigation.Host host(List<Rule> selected) { return new EvidenceInvestigation.Host(cp, plan, selected, corpus, new LinkedHashMap<>(facts), new LinkedHashMap<>(context),
            new SharedFactReuse(corpus, context, "synthetic", "synthetic"), new RunSession(ModelGateway.disabled(), context, "synthetic"), null, new ArrayList<>(), Collections.emptySet()); }
    }
}
