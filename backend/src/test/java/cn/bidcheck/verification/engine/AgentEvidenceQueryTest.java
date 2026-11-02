package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class AgentEvidenceQueryTest {
    @Test void publicNavigationDoesNotAppendAllCheckpointRolesAndPeople(){
        var host=EvidenceInvestigationTargetContractTest.host();host.start();
        var selection=AgentEvidenceQuery.select("测试甲 社保缴费月份","",host.rules,new ArrayList<>(host.facts.values()),host.corpus);
        assertEquals("测试甲 社保缴费月份",selection.query());assertTrue(selection.anchors().isEmpty());
    }
    @Test void resolvedPersonSearchPreservesEvidenceQuestionAndDoesNotPinAppointment(){
        var host=EvidenceInvestigationTargetContractTest.host();host.call("search_evidence",Map.of("query","项目负责人"));host.start();
        Rule months=new Rule("M","社保证明","MONTHS","项目负责人","社保","GE","3","月",Map.of(),"项目负责人社保证明","",List.of());
        var selection=AgentEvidenceQuery.select("社保缴费月份","M",List.of(months),new ArrayList<>(host.facts.values()),host.corpus);
        assertEquals(List.of("测试甲"),selection.expandedSubjects());assertEquals("社保缴费月份 测试甲",selection.query());assertTrue(selection.anchors().isEmpty());
        assertEquals("测试甲 社保",AgentEvidenceQuery.select("测试甲 社保","M",List.of(months),new ArrayList<>(host.facts.values()),host.corpus).query());
    }
    @Test void explicitRoleSearchKeepsItsOwnAnchorsOnly(){
        var host=EvidenceInvestigationTargetContractTest.host();host.start();
        var selection=AgentEvidenceQuery.select("拟在本合同任职","C",host.rules,new ArrayList<>(host.facts.values()),host.corpus);
        assertEquals(List.of("C"),selection.targets().stream().map(Rule::id).toList());assertEquals(List.of("C"),selection.anchors().stream().map(Rule::id).toList());
        assertFalse(selection.query().contains("测试甲"));
    }
}
