package cn.bidcheck.web;

import cn.bidcheck.retrieval.HybridRetriever;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.graph.EvidenceGraph;
import cn.bidcheck.verification.graph.EvidenceGraphReadService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiEvidenceGraphControllerTest {
    @Test void exposesRunScopedGraphAndProofWithoutChangingLegacyConstructor() throws Exception {
        EvidenceGraphReadService reads=mock(EvidenceGraphReadService.class);
        EvidenceGraph.Revision revision=new EvidenceGraph.Revision("revision-a","project-a","engine","manifest","occurrences",
                "evidence-graph-1","content",List.of(),"2026-10-19T00:00:00Z");
        when(reads.graph(eq("run-a"),any())).thenReturn(new EvidenceGraphReadService.GraphView(revision,List.of(),List.of()));
        when(reads.proof(eq("run-a"),eq("person:a"),eq("company:a"),any(),eq(2)))
                .thenReturn(new EvidenceGraph.ProofPath(false,"revision-a","person:a","company:a",List.of(),List.of(),"NO_SUPPORTED_PATH"));
        ApiController controller=new ApiController(mock(BidService.class),null,null,mock(HybridRetriever.class));
        controller.configureEvidenceGraphReads(reads);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/runs/run-a/evidence-graph").param("predicate","EMPLOYED_BY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision.id").value("revision-a"))
                .andExpect(jsonPath("$.entities").isArray()).andExpect(jsonPath("$.relations").isArray());
        mvc.perform(get("/api/runs/run-a/evidence-graph/proof").param("from","person:a").param("to","company:a")
                        .param("predicates","EMPLOYED_BY").param("maxDepth","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.reason").value("NO_SUPPORTED_PATH"));
        verify(reads).graph(eq("run-a"),argThat(values->values!=null&&values.contains("EMPLOYED_BY")));
        verify(reads).proof(eq("run-a"),eq("person:a"),eq("company:a"),argThat(values->values!=null&&values.contains("EMPLOYED_BY")),eq(2));
    }

    @Test void returnsServiceUnavailableWhenOptionalGraphReaderIsAbsent() throws Exception {
        ApiController controller=new ApiController(mock(BidService.class),null,null,mock(HybridRetriever.class));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/runs/run-a/evidence-graph"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void delegatesVerificationRunIdsToTheSharedGraphReader() throws Exception {
        EvidenceGraphReadService reads=mock(EvidenceGraphReadService.class);
        EvidenceGraph.Revision revision=new EvidenceGraph.Revision("revision-v","project-v","engine","manifest","occurrences",
                "evidence-graph-1","content",List.of(),"2026-10-20T00:00:00Z");
        when(reads.graph(eq("verification-v"),any())).thenReturn(new EvidenceGraphReadService.GraphView(revision,List.of(),List.of()));
        ApiController controller=new ApiController(mock(BidService.class),null,null,mock(HybridRetriever.class));controller.configureEvidenceGraphReads(reads);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/runs/verification-v/evidence-graph"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision.id").value("revision-v"));
        verify(reads).graph(eq("verification-v"),any());
    }
}
