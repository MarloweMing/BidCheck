package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;

/** A constrained extraction adapter; callers never execute model-generated code. */
public interface ModelGateway {
    boolean enabled();
    String identity();
    JsonNode request(String operation,Map<String,Object> input) throws IOException;
    /** Retry the same evidence window with server-owned format feedback, never with a generated answer. */
    default JsonNode requestWithFeedback(String operation,Map<String,Object> input,Map<String,Object> feedback) throws IOException {
        return request(operation,input);
    }
    static ModelGateway disabled(){return new ModelGateway(){public boolean enabled(){return false;}public String identity(){return "DISABLED";}public JsonNode request(String operation,Map<String,Object> input)throws IOException{throw new IOException("未配置结构化抽取服务");}};}
}
