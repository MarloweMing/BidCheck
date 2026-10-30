package cn.bidcheck.verification.agent;

import java.util.Map;
import java.util.function.Consumer;

/** Evidence investigation may propose facts; only server-owned tools can accept or judge them. */
public interface AgentInvestigator {
    boolean enabled();
    String identity();
    Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event);

    interface ToolHandler {
        Map<String,Object> call(String name,Map<String,Object> arguments);
    }
}
