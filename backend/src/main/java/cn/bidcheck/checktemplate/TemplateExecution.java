package cn.bidcheck.checktemplate;

import java.util.List;

public record TemplateExecution(
        CheckSpec spec,
        List<EvidenceNeed> evidenceNeeds,
        TemplateResult result,
        ResultProjection projection,
        String executionRoute) {
    public TemplateExecution {
        evidenceNeeds = evidenceNeeds == null ? List.of() : List.copyOf(evidenceNeeds);
        executionRoute = executionRoute == null ? "TEMPLATE" : executionRoute;
    }
}
