package cn.bidcheck.verification.agent;

import java.util.Map;

/** In-memory model substitute, while using the real SDK, runner and Host callback. */
public final class SyntheticContractModelBridge {
    private SyntheticContractModelBridge() {}
    public interface Model { Map<String,Object> reply(Map<String,Object> request) throws Exception; }
    public static Map<String,Object> run(Map<String,Object> start, Model model, AgentInvestigator.ToolHandler host) {
        return new SdkAgentInvestigator(true, "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "synthetic-not-a-credential", "synthetic", 20, 1, null,
            (request, timeout) -> model.reply(request)).investigate(start, host, event -> {});
    }
}
