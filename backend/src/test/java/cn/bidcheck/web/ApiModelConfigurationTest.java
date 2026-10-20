package cn.bidcheck.web;

import cn.bidcheck.retrieval.HybridRetriever;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.engine.VerificationModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiModelConfigurationTest {
    private ApiController controller() {
        BidService service=mock(BidService.class);
        when(service.semanticModel()).thenReturn("legacy-semantic-model");
        return new ApiController(service,null,null,mock(HybridRetriever.class));
    }

    private MockMvc mvc(ApiController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void displaysActualRoutingModelsWithoutPublishingCredentialsOrEndpoint() throws Exception {
        ApiController controller=controller();
        controller.configureVerificationModels(new VerificationModelClient(
            "https://private-model-endpoint.invalid/private-generation-path","never-publish-api-test-key",
            "complex-verification-model","ordinary-fact-extraction-model"));
        mvc(controller).perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.semanticModel").value("legacy-semantic-model"))
            .andExpect(jsonPath("$.verificationEnabled").value(true))
            .andExpect(jsonPath("$.semanticExtractionModel").value("ordinary-fact-extraction-model"))
            .andExpect(jsonPath("$.semanticVerificationModel").value("complex-verification-model"))
            .andExpect(jsonPath("$.semanticRoutingPolicy").value("verification-model-routing-1-consistency-fields-only"))
            .andExpect(content().string(not(containsString("never-publish-api-test-key"))))
            .andExpect(content().string(not(containsString("private-model-endpoint.invalid"))))
            .andExpect(content().string(not(containsString("private-generation-path"))));
    }

    @Test void reflectsEffectiveExtractionFallbackAndDisabledClient() throws Exception {
        ApiController controller=controller();
        controller.configureVerificationModels(new VerificationModelClient("","","configured-primary","  "));
        mvc(controller).perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verificationEnabled").value(false))
            .andExpect(jsonPath("$.semanticExtractionModel").value("configured-primary"))
            .andExpect(jsonPath("$.semanticVerificationModel").value("configured-primary"));
    }

    @Test void originalConstructorWorksWithoutInventingVerificationConfiguration() throws Exception {
        mvc(controller()).perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.semanticModel").value("legacy-semantic-model"))
            .andExpect(jsonPath("$.verificationEnabled").value(false))
            .andExpect(jsonPath("$.semanticExtractionModel").value(""))
            .andExpect(jsonPath("$.semanticVerificationModel").value(""))
            .andExpect(jsonPath("$.semanticRoutingPolicy").value(""));
    }
}
