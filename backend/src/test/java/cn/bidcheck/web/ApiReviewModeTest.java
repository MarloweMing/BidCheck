package cn.bidcheck.web;

import cn.bidcheck.service.BidService;
import cn.bidcheck.retrieval.HybridRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiReviewModeTest {
    @Test void forwardsExplicitModeWithoutChangingCheckManifestArguments()throws Exception{
        BidService service=mock(BidService.class);when(service.startRun("p","b",true,"set",2,"key","AGENT_LED")).thenReturn(cn.bidcheck.compat.Java8Api.mapOf("id", "run", "reviewMode", "AGENT_LED"));
        org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new ApiController(service,null,null,mock(HybridRetriever.class))).build();
        mvc.perform(post("/api/projects/p/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"b\",\"force\":true,\"checkSetId\":\"set\",\"manifestRevision\":2,\"idempotencyKey\":\"key\",\"reviewMode\":\"AGENT_LED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewMode").value("AGENT_LED"));
        verify(service).startRun("p","b",true,"set",2,"key","AGENT_LED");
    }
    @Test void omittedModeIsPassedAsDefaultAndAvailabilityIsExplicit()throws Exception{
        BidService service=mock(BidService.class);when(service.startRun("p","b",false,"",null,"","")).thenReturn(cn.bidcheck.compat.Java8Api.mapOf("id", "run", "reviewMode", "FIXED_WORKFLOW"));
        org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new ApiController(service,null,null,mock(HybridRetriever.class))).build();
        mvc.perform(post("/api/projects/p/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"b\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewMode").value("FIXED_WORKFLOW"));
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.agentReviewEnabled").value(false)).andExpect(jsonPath("$.defaultReviewMode").value("FIXED_WORKFLOW"));
    }
}
