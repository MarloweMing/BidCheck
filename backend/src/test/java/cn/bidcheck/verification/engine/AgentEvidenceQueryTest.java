package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class AgentEvidenceQueryTest {
    @Test void publicNavigationDoesNotAppendAllCheckpointRolesAndPeople(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=EvidenceInvestigationTargetContractTest.host();host.start();
        cn.bidcheck.verification.engine.AgentEvidenceQuery.Selection selection=AgentEvidenceQuery.select("测试甲 社保缴费月份","",host.rules,new ArrayList<>(host.facts.values()),host.corpus);
        assertEquals("测试甲 社保缴费月份",selection.query());assertTrue(selection.anchors().isEmpty());
    }
    @Test void resolvedPersonSearchPreservesEvidenceQuestionAndDoesNotPinAppointment(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=EvidenceInvestigationTargetContractTest.host();host.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));host.start();
        Rule months=new Rule("M","社保证明","MONTHS","项目负责人","社保","GE","3","月",cn.bidcheck.compat.Java8Api.mapOf(),"项目负责人社保证明","",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.AgentEvidenceQuery.Selection selection=AgentEvidenceQuery.select("社保缴费月份","M",cn.bidcheck.compat.Java8Api.listOf(months),new ArrayList<>(host.facts.values()),host.corpus);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("测试甲"),selection.expandedSubjects());assertEquals("社保缴费月份 测试甲",selection.query());assertTrue(selection.anchors().isEmpty());
        assertEquals("测试甲 社保",AgentEvidenceQuery.select("测试甲 社保","M",cn.bidcheck.compat.Java8Api.listOf(months),new ArrayList<>(host.facts.values()),host.corpus).query());
    }
    @Test void explicitRoleSearchKeepsItsOwnAnchorsOnly(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=EvidenceInvestigationTargetContractTest.host();host.start();
        cn.bidcheck.verification.engine.AgentEvidenceQuery.Selection selection=AgentEvidenceQuery.select("拟在本合同任职","C",host.rules,new ArrayList<>(host.facts.values()),host.corpus);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("C"),cn.bidcheck.compat.Java8Api.toList(selection.targets().stream().map(Rule::id)));assertEquals(cn.bidcheck.compat.Java8Api.listOf("C"),cn.bidcheck.compat.Java8Api.toList(selection.anchors().stream().map(Rule::id)));
        assertFalse(selection.query().contains("测试甲"));
    }
}
