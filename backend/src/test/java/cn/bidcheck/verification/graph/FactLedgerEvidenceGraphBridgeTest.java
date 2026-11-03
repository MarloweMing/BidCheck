package cn.bidcheck.verification.graph;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.verification.facts.FactLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactLedgerEvidenceGraphBridgeTest {
    @Test void onlySupportedCurrentProjectOccurrencesBecomeRevalidatedCandidates(){
        FactLedgerService ledger=mock(FactLedgerService.class);
        EvidenceGraphService graphs=new EvidenceGraphService(new EvidenceGraphServiceTest.MemoryRepository(),new ObjectMapper());
        FactLedgerEvidenceGraphBridge bridge=new FactLedgerEvidenceGraphBridge(ledger,graphs);
        Document document=EvidenceGraphServiceTest.document(1,EvidenceGraphServiceTest.HASH);
        Map<String,Object> current=EvidenceGraphServiceTest.personRole(document,"current","项目经理","张三","CURRENT_PROJECT","拟在本合同任职：项目经理 张三");
        Map<String,Object> candidate=EvidenceGraphServiceTest.personRole(document,"candidate","项目经理","王五","CURRENT_PROJECT","王五个人简历");
        Map<String,Object> historical=EvidenceGraphServiceTest.personRole(document,"history","项目经理","李四","HISTORICAL_PROJECT","历史业绩：项目经理 李四");
        cn.bidcheck.verification.graph.EvidenceGraph.Snapshot graph=graphs.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.mapOf("occurrences", cn.bidcheck.compat.Java8Api.listOf(current, candidate, historical))).snapshot();
        Map<String,Object> reopened=new LinkedHashMap<>(current);reopened.put("projectId","project");
        when(ledger.revalidateOccurrences(eq("project"),anyCollection(),eq(cn.bidcheck.compat.Java8Api.listOf(document)))).thenReturn(cn.bidcheck.compat.Java8Api.listOf(reopened));

        cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse result=bridge.reusableCurrentProjectOccurrences("project",graph.revision().id(),cn.bidcheck.compat.Java8Api.listOf(document));

        assertEquals(1,result.occurrences().size());assertEquals("REVALIDATED",result.audit().get("status"));
        assertEquals(0,result.audit().get("candidateEdgesAccepted"));assertEquals(0,result.audit().get("historicalEdgesAccepted"));
        assertEquals(false,result.audit().get("businessDecisionReused"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<String>> ids=(ArgumentCaptor<Collection<String>>)(ArgumentCaptor<?>)ArgumentCaptor.forClass(Collection.class);
        verify(ledger).revalidateOccurrences(eq("project"),ids.capture(),eq(cn.bidcheck.compat.Java8Api.listOf(document)));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("occurrence:current"),cn.bidcheck.compat.Java8Api.toList(ids.getValue().stream().sorted()));
    }

    @Test void changedDocumentManifestRejectsTheWholeRevisionBeforeLedgerRead(){
        FactLedgerService ledger=mock(FactLedgerService.class);
        EvidenceGraphService graphs=new EvidenceGraphService(new EvidenceGraphServiceTest.MemoryRepository(),new ObjectMapper());
        FactLedgerEvidenceGraphBridge bridge=new FactLedgerEvidenceGraphBridge(ledger,graphs);
        Document original=EvidenceGraphServiceTest.document(1,EvidenceGraphServiceTest.HASH);
        cn.bidcheck.verification.graph.EvidenceGraph.Snapshot graph=graphs.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(original),cn.bidcheck.compat.Java8Api.mapOf("occurrences", cn.bidcheck.compat.Java8Api.listOf(EvidenceGraphServiceTest.personRole(original,"current","项目经理","张三","CURRENT_PROJECT","拟在本合同任职：项目经理 张三")))).snapshot();
        Document newer=EvidenceGraphServiceTest.document(2,EvidenceGraphServiceTest.HASH);

        cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse result=bridge.reusableCurrentProjectOccurrences("project",graph.revision().id(),cn.bidcheck.compat.Java8Api.listOf(newer));

        assertTrue(result.occurrences().isEmpty());assertEquals("GRAPH_MANIFEST_CHANGED",result.audit().get("reason"));
        verifyNoInteractions(ledger);
    }
}
