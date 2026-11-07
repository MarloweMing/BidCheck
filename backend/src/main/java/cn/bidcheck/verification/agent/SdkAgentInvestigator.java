package cn.bidcheck.verification.agent;
import cn.bidcheck.compat.HexFormat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Java orchestration with a trusted host boundary. No model prose or credential is logged. */
@Component
public final class SdkAgentInvestigator implements AgentInvestigator {
    static final String PROTOCOL="bidcheck-agent-1",POLICY="java-agent-11-native-runtime-local-tool-audit";
    static final int MAX_LINE_BYTES=4*1024*1024,MAX_ARGUMENT_BYTES=64*1024;
    private static final Set<String> HOSTS=cn.bidcheck.compat.Java8Api.setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com");
    private static final Set<String> TOOLS=cn.bidcheck.compat.Java8Api.setOf("search_evidence", "read_section", "read_table_record", "inspect_page_region", "query_external", "submit_candidate_fact", "recompute_check");
    private static final Set<String> REVIEW_TOOLS=cn.bidcheck.compat.Java8Api.setOf("submit_review_plan", "submit_review_judgment");
    private static final Set<String> REVIEW_TARGET_READS=cn.bidcheck.compat.Java8Api.setOf("search_evidence", "read_section", "read_table_record");
    private static final Set<String> VALIDATION_REJECTIONS=cn.bidcheck.compat.Java8Api.setOf("QUERY_INVALID", "SOURCE_NOT_DISCLOSED", "SOURCE_NOT_CURRENT", "ROW_OFFSET_INVALID", "FIELD_INVALID", "FACT_PROTOCOL_INVALID", "FACT_OUTSIDE_TARGET", "INFERENCE_PROTOCOL_INVALID", "INFERENCE_CITATION_UNGROUNDED", "INFERENCE_TYPED_FACT_REQUIRED", "INFERENCE_PREVIOUSLY_REJECTED", "INFERENCE_CURRENT_SCOPE_CONFLICT");
    private static final Set<String> REVIEW_VALIDATION_REJECTIONS=cn.bidcheck.compat.Java8Api.setOf("PLAN_APPROVAL_REQUIRED", "REVIEW_INPUT_INVALID", "REQUIREMENT_SOURCE_INCOMPLETE", "PLAN_SCHEMA_INVALID", "PLAN_SIZE_INVALID", "PLAN_SOURCE_INVALID", "PLAN_METHOD_INVALID", "DETERMINISTIC_REVIEW_REQUIRED", "PREDICATE_NOT_SUPPORTED", "RULE_NOT_EXECUTABLE", "PRIOR_PLAN_REVIEW_MISSING", "PLAN_COVERAGE_REJECTED", "REVIEW_PLAN_REVISION_MISMATCH", "JUDGMENT_SCHEMA_INVALID", "SEMANTIC_EVIDENCE_INCOMPLETE", "SEMANTIC_SOURCE_INVALID", "SEMANTIC_SOURCE_SCOPE_INVALID", "TITLE_ONLY_EVIDENCE", "CONDITION_EVIDENCE_INVALID", "SEMANTIC_REVIEW_REJECTED");
    private static final Set<String> EVENTS=cn.bidcheck.compat.Java8Api.setOf("investigation_started", "model_started", "model_request_authorized", "model_finished", "tool_started", "tool_finished", "duplicate_tool_avoided", "investigation_error", "host_recomputed", "semantic_path_exhausted", "investigation_stalled", "context_compacted", "context_rebuilt", "target_tool_blocked", "source_body_replayed", "contract_originals_reprojected", "review_plan_approved", "review_stage_tool_blocked", "review_plan_rejection_reused", "review_plan_rejection_remembered", "tool_arguments_rejected", "no_evidence_progress", "evidence_pending", "target_navigation_progress", "target_proof_progress", "target_tool_failed", "investigation_failed");
    private static final int MAX_TARGET_DIAGNOSTICS=256;
    private static final Set<String> TARGET_STATES=cn.bidcheck.compat.Java8Api.setOf("UNVISITED", "RUNNING", "PROVEN", "NO_MORE_EVIDENCE", "NEEDS_CONFIRMATION", "FAILED");
    private static final Set<String> WORKER_ERRORS=cn.bidcheck.compat.Java8Api.setOf("MODEL_PERMISSION_TIMEOUT", "MODEL_PERMISSION_PROTOCOL_ERROR", "MODEL_REQUEST_DENIED", "MODEL_ENDPOINT_NOT_ALLOWED", "MODEL_CONFIGURATION_MISSING", "INVALID_TIMEOUT_CONFIGURATION", "PROTOCOL_MESSAGE_TOO_LARGE", "PROTOCOL_QUEUE_OVERFLOW", "HOST_DISCONNECTED", "PROTOCOL_READ_FAILED", "PROTOCOL_OBJECT_REQUIRED", "PROTOCOL_VERSION_MISMATCH", "INVALID_REQUEST_ID", "INVALID_CONTEXT", "NO_TOOLS_PROVIDED", "TOOL_NOT_ALLOWED", "DUPLICATE_TOOL", "INVALID_TOOL_DESCRIPTION", "INVALID_TOOL_SCHEMA", "REMOTE_SCHEMA_REFERENCE_NOT_ALLOWED", "TOOL_ARGUMENTS_TOO_LARGE", "TOOL_ARGUMENTS_OBJECT_REQUIRED", "TOOL_ARGUMENT_SCHEMA_MISMATCH", "HOST_CANCELLED", "TOOL_RESPONSE_ID_MISMATCH", "TOOL_RESPONSE_REQUEST_MISMATCH", "TOOL_RESULT_OBJECT_REQUIRED", "TOOL_RESULT_TOO_LARGE", "TOOL_RESPONSE_TIMEOUT", "TOOL_OPERATION_FAILED", "REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", "INVESTIGATION_TIMEOUT", "RUNAWAY_LOOP_PROTECTION", "REVIEW_PLAN_TOOL_MISSING", "MODEL_PROTOCOL_ERROR", "MODEL_OR_SDK_ERROR", "WORKER_INTERNAL_ERROR", "INVALID_JSON_NUMBER", "DUPLICATE_JSON_KEY", "INVALID_JSON");
    private static final Logger LOG=LoggerFactory.getLogger(SdkAgentInvestigator.class);
    private static final ThreadLocal<AtomicBoolean> CALL_FENCE=new ThreadLocal<>();
    private final boolean configuredEnabled;
    private final String baseUrl,apiKey,model;
    private final ProtocolFactory protocolFactory;
    private final JavaAgentRunner.ModelTransport modelTransport;
    private final int timeoutSeconds;
    private final Semaphore slots;
    private final ObjectMapper json;
    @Value("${bidcheck.agent.audit-directory:}") private String auditDirectory="";

    @Autowired public SdkAgentInvestigator(
            @Value("${bidcheck.agent.enabled:false}") boolean enabled,
            @Value("${bidcheck.agent.base-url:${bidcheck.semantic.extraction-url:${bidcheck.semantic.url:}}}") String baseUrl,
            @Value("${bidcheck.agent.api-key:${bidcheck.semantic.key:}}") String apiKey,
            @Value("${bidcheck.agent.model:${bidcheck.semantic.extraction-model:${bidcheck.semantic.model:}}}") String model,
            @Value("${bidcheck.agent.run-timeout-seconds:600}") int timeoutSeconds,
            @Value("${bidcheck.agent.max-concurrency:2}") int concurrency){
        this(enabled,baseUrl,apiKey,model,timeoutSeconds,concurrency,null,null);
    }
    interface ProtocolConnection extends AutoCloseable {InputStream input();OutputStream output();void close();}
    interface ProtocolFactory {ProtocolConnection open(int timeout,String endpoint,String key,String model,String audit)throws Exception;String identity();}
    SdkAgentInvestigator(boolean enabled,String baseUrl,String apiKey,String model,int timeoutSeconds,int concurrency,ProtocolFactory protocolFactory){this(enabled,baseUrl,apiKey,model,timeoutSeconds,concurrency,protocolFactory,null);}
    SdkAgentInvestigator(boolean enabled,String baseUrl,String apiKey,String model,int timeoutSeconds,int concurrency,ProtocolFactory protocolFactory,JavaAgentRunner.ModelTransport modelTransport){
        this.configuredEnabled=enabled;this.baseUrl=trim(baseUrl);this.apiKey=trim(apiKey);this.model=trim(model);this.protocolFactory=protocolFactory;this.modelTransport=modelTransport;
        this.timeoutSeconds=timeoutSeconds;this.slots=new Semaphore(Math.max(1,Math.min(16,concurrency)),true);
        this.json=new ObjectMapper();this.json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_LINE_BYTES).maxNumberLength(32).build());
    }
    @Override public boolean enabled(){return configuredEnabled;}
    /** Host mutations must additionally check this immediately before adopting/publishing,
     * alongside the existing database lease fence. Interrupt-ignoring tools cannot keep an
     * expired investigation alive. Non-SDK callers retain their own cancellation policy. */
    public static boolean currentCallActive(){AtomicBoolean fence=CALL_FENCE.get();return (fence==null||fence.get())&&!Thread.currentThread().isInterrupted();}
    @Override public String identity(){
        String endpoint;try{endpoint=normalizedEndpoint(baseUrl);}catch(BridgeFailure e){endpoint="INVALID_ENDPOINT";}
        String runtime=protocolFactory==null?JavaAgentRunner.POLICY+":"+JavaAgentRunner.VERSION:protocolFactory.identity();
        // The credential and its hash are deliberately excluded from exported identity.
        return POLICY+":"+sha((runtime+"\n"+endpoint+"\n"+model+"\ntracing-disabled").getBytes(StandardCharsets.UTF_8));
    }

    @Override public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){
        if(protocolFactory==null)return investigateJava(start,handler,event);
        long began=System.nanoTime();String requestId=safeId(start==null?null:start.get("requestId"));
        ProtocolConnection connection=null;ExecutorService executor=null;boolean acquired=false;
        HostReceipts receipts=new HostReceipts();receipts.requestId=requestId;AtomicBoolean callActive=new AtomicBoolean(true);
        try{
            if(!configuredEnabled)throw new BridgeFailure("AGENT_DISABLED");
            if(timeoutSeconds<=0||timeoutSeconds>86400)throw new BridgeFailure("INVALID_TIMEOUT_CONFIGURATION");
            String endpoint=normalizedEndpoint(baseUrl);
            if(cn.bidcheck.compat.Java8Api.isBlank(apiKey)||!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))throw new BridgeFailure("MODEL_CONFIGURATION_MISSING");
            if(handler==null)throw new BridgeFailure("TOOL_HANDLER_MISSING");
            Set<String> offered=validateStart(start,requestId);
            receipts.initialize(object(start.get("context"),"INVALID_CONTEXT"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
            if(!slots.tryAcquire(remaining(deadline),TimeUnit.NANOSECONDS))throw new BridgeFailure("AGENT_CONCURRENCY_TIMEOUT");
            acquired=true;
            connection=protocolFactory.open(timeoutSeconds,endpoint,apiKey,model,auditDirectory);
            executor=Executors.newCachedThreadPool(r->{Thread thread=new Thread(r,"bidcheck-agent-protocol");thread.setDaemon(true);return thread;});
            BlockingQueue<Object> messages=new ArrayBlockingQueue<>(16);ProtocolConnection running=connection;
            executor.submit(()->readMessages(running.input(),messages));
            BufferedOutputStream output=new BufferedOutputStream(connection.output());
            // Pipe writes can block too when a crashed or malicious child stops reading.
            invoke(executor,()->{send(output,start);return null;},deadline,"PROTOCOL_WRITE_FAILED",callActive);
            Set<String> callIds=new HashSet<>();long actualToolCalls=0,toolAuditIndex=0;ToolPayloadAudit toolAudit=new ToolPayloadAudit(auditDirectory,apiKey);
            while(true){
                Object message=messages.poll(remaining(deadline),TimeUnit.NANOSECONDS);
                if(message==null)throw new BridgeFailure("INVESTIGATION_TIMEOUT");
                if(message instanceof BridgeFailure)throw ((BridgeFailure) (message));
                Map<String,Object> frame=object(message,"PROTOCOL_OBJECT_REQUIRED");
                if(!PROTOCOL.equals(frame.get("protocol")))throw new BridgeFailure("PROTOCOL_VERSION_MISMATCH");
                if(!requestId.equals(frame.get("requestId")))throw new BridgeFailure("PROTOCOL_REQUEST_ID_MISMATCH");
                String type=Objects.toString(frame.get("type"),"");
                if(type.equals("event")){
                    Map<String,Object> safe=safeEvent(frame,requestId,receipts);
                    boolean permit="model_started".equals(frame.get("event"));String permitId=permit?safeId(frame.get("id")):"";
                    if(permit&&(cn.bidcheck.compat.Java8Api.isBlank(permitId)||!callIds.add("permit:"+permitId)))throw new BridgeFailure("MODEL_PERMISSION_PROTOCOL_ERROR");
                    if(permit&&event==null)throw new BridgeFailure("MODEL_REQUEST_DENIED");
                    // SDK waits for this acknowledgement before issuing the provider HTTP
                    // request. The upper layer acquires/validates its lease in the callback.
                    if(event!=null)invoke(executor,()->{event.accept(safe);return null;},deadline,permit?"MODEL_REQUEST_DENIED":"EVENT_CALLBACK_FAILED",callActive);
                    if(permit)invoke(executor,()->{send(output,map("type","event_ack","protocol",PROTOCOL,"requestId",requestId,"id",permitId,"allowed",true));return null;},deadline,"PROTOCOL_WRITE_FAILED",callActive);
                }else if(type.equals("tool_call")){
                    String name=Objects.toString(frame.get("name"),""),callId=safeId(frame.get("id"));
                    if(cn.bidcheck.compat.Java8Api.isBlank(callId)||!callIds.add(callId))throw new BridgeFailure("TOOL_CALL_ID_INVALID");
                    if(!offered.contains(name)||!toolAllowed(name,receipts.review))throw new BridgeFailure("TOOL_NOT_ALLOWED");
                    Map<String,Object> arguments=object(frame.get("arguments"),"TOOL_ARGUMENTS_OBJECT_REQUIRED");
                    if(json.writeValueAsBytes(arguments).length>MAX_ARGUMENT_BYTES)throw new BridgeFailure("TOOL_ARGUMENTS_TOO_LARGE");
                    long auditIndex=++toolAuditIndex;
                    toolAuditEvent(toolAudit,start,event,requestId,auditIndex,callId,name,"input",arguments);
                    try{
                        receipts.validateCall(name,arguments);
                        Map<String,Object> result=invoke(executor,()->handler.call(name,arguments),deadline,"TOOL_HANDLER_FAILED",callActive);
                        if(result==null)throw new BridgeFailure("TOOL_RESULT_OBJECT_REQUIRED");
                        toolAuditEvent(toolAudit,start,event,requestId,auditIndex,callId,name,"output",result);
                        receipts.accept(name,arguments,result);actualToolCalls++;
                        invoke(executor,()->{send(output,map("type","tool_result","requestId",requestId,"id",callId,"result",result));return null;},deadline,"PROTOCOL_WRITE_FAILED",callActive);
                    }catch(Exception failed){
                        toolAuditEvent(toolAudit,start,event,requestId,auditIndex,callId,name,"error",map("errorCode",failed instanceof BridgeFailure?((BridgeFailure) (failed)).code:failed instanceof InterruptedException?"HOST_CANCELLED":"TOOL_BOUNDARY_FAILED","errorType",failed.getClass().getSimpleName()));
                        throw failed;
                    }
                }else if(type.equals("result")){
                    Map<String,Object> result=safeResult(frame,receipts,actualToolCalls,requestId,began);
                    LOG.info("bidcheck.agent_bridge requestId={} policy={} status={} code={} toolCalls={} targetCompleted={} businessCompleted={} unresolvedToolFailures={} recoveredTools={} validationRejections={} elapsedMs={}",
                        requestId,POLICY,result.get("status"),result.get("code"),actualToolCalls,receipts.targetCompleted,receipts.completed,receipts.unresolved.size(),receipts.recovered,receipts.rejections,result.get("elapsedMs"));
                    return result;
                }else throw new BridgeFailure("PROTOCOL_MESSAGE_TYPE_INVALID");
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return failure("HOST_CANCELLED",requestId,receipts,began);}
        catch(BridgeFailure failure){return failure(failure.code,requestId,receipts,began);}
        catch(Exception unexpected){return failure("AGENT_BRIDGE_FAILURE",requestId,receipts,began);}
        finally{
            callActive.set(false);
            if(connection!=null)connection.close();
            if(executor!=null){
                executor.shutdownNow();
                // Cancellation is a protocol boundary: an interruptible tool callback
                // should observe cancellation before investigate() returns. Without a
                // short join the callback can remain scheduled briefly under load even
                // though its Future has already been cancelled.
                try{executor.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            if(acquired)slots.release();
        }
    }

    private Map<String,Object> investigateJava(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){
        long began=System.nanoTime();String requestId=safeId(start==null?null:start.get("requestId"));
        HostReceipts receipts=new HostReceipts();receipts.requestId=requestId;AtomicBoolean active=new AtomicBoolean(true);ExecutorService executor=null;boolean acquired=false;
        try{
            if(!configuredEnabled)throw new BridgeFailure("AGENT_DISABLED");
            if(timeoutSeconds<=0||timeoutSeconds>86400)throw new BridgeFailure("INVALID_TIMEOUT_CONFIGURATION");
            String endpoint=normalizedEndpoint(baseUrl);
            if(cn.bidcheck.compat.Java8Api.isBlank(apiKey)||!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))throw new BridgeFailure("MODEL_CONFIGURATION_MISSING");
            if(handler==null)throw new BridgeFailure("TOOL_HANDLER_MISSING");
            Set<String> offered=validateStart(start,requestId);receipts.initialize(object(start.get("context"),"INVALID_CONTEXT"));
            long available=TimeUnit.SECONDS.toMillis(timeoutSeconds);
            if(start.get("recoveryDeadlineEpochMillis") instanceof Number)available=Math.min(available,((Number)start.get("recoveryDeadlineEpochMillis")).longValue()-System.currentTimeMillis());
            if(available<=0)throw new BridgeFailure("INVESTIGATION_TIMEOUT");
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(available);
            if(!slots.tryAcquire(remaining(deadline),TimeUnit.NANOSECONDS))throw new BridgeFailure("AGENT_CONCURRENCY_TIMEOUT");acquired=true;
            executor=Executors.newCachedThreadPool(r->{Thread thread=new Thread(r,"bidcheck-java-agent");thread.setDaemon(true);return thread;});
            final ExecutorService runtime=executor;final long[] toolCalls={0};ToolPayloadAudit toolAudit=new ToolPayloadAudit(auditDirectory,apiKey);
            JavaAgentRunner runner=new JavaAgentRunner(endpoint,apiKey,model,timeoutSeconds,auditDirectory,modelTransport);
            JavaAgentRunner.Host host=new JavaAgentRunner.Host(){
                @Override public Map<String,Object> call(String name,Map<String,Object> arguments)throws Exception{
                    if(!offered.contains(name)||!toolAllowed(name,receipts.review))throw new BridgeFailure("TOOL_NOT_ALLOWED");
                    if(json.writeValueAsBytes(arguments).length>MAX_ARGUMENT_BYTES)throw new BridgeFailure("TOOL_ARGUMENTS_TOO_LARGE");
                    receipts.validateCall(name,arguments);long index=toolCalls[0]+1;String callId="tool-"+index;
                    toolAuditEvent(toolAudit,start,event,requestId,index,callId,name,"input",arguments);
                    try{
                        Map<String,Object> result=invoke(runtime,()->handler.call(name,arguments),deadline,"TOOL_HANDLER_FAILED",active);
                        if(result==null)throw new BridgeFailure("TOOL_RESULT_OBJECT_REQUIRED");
                        if(json.writeValueAsBytes(result).length>MAX_LINE_BYTES)throw new BridgeFailure("TOOL_RESULT_TOO_LARGE");
                        toolAuditEvent(toolAudit,start,event,requestId,index,callId,name,"output",result);
                        receipts.accept(name,arguments,result);toolCalls[0]++;return result;
                    }catch(Exception failed){toolAuditEvent(toolAudit,start,event,requestId,index,callId,name,"error",map("errorCode",failed instanceof BridgeFailure?((BridgeFailure)failed).code:"TOOL_BOUNDARY_FAILED","errorType",failed.getClass().getSimpleName()));throw failed;}
                }
                @Override public Map<String,Object> state(){return receipts.snapshot();}
                @Override public void event(Map<String,Object> frame)throws Exception{
                    boolean permit="model_started".equals(frame.get("event"));if(permit&&event==null)throw new BridgeFailure("MODEL_REQUEST_DENIED");
                    Map<String,Object> safe=safeEvent(frame,requestId,receipts);
                    if(event!=null)invoke(runtime,()->{event.accept(safe);return null;},deadline,permit?"MODEL_REQUEST_DENIED":"EVENT_CALLBACK_FAILED",active);
                }
            };
            Map<String,Object> frame=invoke(runtime,()->runner.run(start,host),deadline,"AGENT_BRIDGE_FAILURE",active);
            return safeResult(frame,receipts,toolCalls[0],requestId,began);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return failure("HOST_CANCELLED",requestId,receipts,began);}
        catch(BridgeFailure failure){return failure(failure.code,requestId,receipts,began);}
        catch(Exception unexpected){return failure("AGENT_BRIDGE_FAILURE",requestId,receipts,began);}
        finally{active.set(false);if(executor!=null){executor.shutdownNow();try{executor.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}if(acquired)slots.release();}
    }

    /** Audit failures are visible but cannot turn successful business work into a failed tool. */
    private void toolAuditEvent(ToolPayloadAudit audit,Map<String,Object> start,Consumer<Map<String,Object>> event,String requestId,long index,String callId,String tool,String direction,Object payload){
        if(!audit.enabled())return;
        Map<String,Object> stored=audit.record(start,requestId,index,callId,tool,direction,payload);
        Map<?,?> context=start!=null&&start.get("auditContext") instanceof Map<?,?>?((Map<?,?>) (start.get("auditContext"))):cn.bidcheck.compat.Java8Api.mapOf();
        String runId=Objects.toString(context.get("runId"),""),checkpointId=Objects.toString(context.get("checkpointId"),"");
        if(!runId.matches("[a-fA-F0-9-]{36}"))runId="";if(!checkpointId.matches("[a-fA-F0-9-]{36}"))checkpointId="";
        LOG.info("bidcheck.agent_tool_audit runId={} checkpointId={} requestId={} toolIndex={} callId={} tool={} direction={} status={} file={} bytes={} sha256={}",runId,checkpointId,requestId,index,callId,tool,direction,stored.get("status"),stored.getOrDefault("file",""),stored.getOrDefault("bytes",0),stored.getOrDefault("sha256",""));
        if(event!=null)try{event.accept(map("type","event","protocol",PROTOCOL,"requestId",requestId,"event","tool_payload_audit","toolIndex",index,"callId",callId,"tool",tool,"direction",direction,"toolAudit",stored));}
        catch(RuntimeException failure){LOG.warn("bidcheck.agent_tool_audit_event requestId={} toolIndex={} direction={} status=EVENT_PUBLISH_FAILED errorType={}",requestId,index,direction,failure.getClass().getSimpleName());}
    }

    private Set<String> validateStart(Map<String,Object> start,String requestId)throws BridgeFailure,IOException{
        if(start==null||!"start".equals(start.get("type"))||!PROTOCOL.equals(start.get("protocol")))throw new BridgeFailure("PROTOCOL_VERSION_MISMATCH");
        if(cn.bidcheck.compat.Java8Api.isBlank(requestId))throw new BridgeFailure("INVALID_REQUEST_ID");
        Map<String,Object> context=object(start.get("context"),"INVALID_CONTEXT");
        boolean review="AGENT_LED_REVIEW_V1".equals(context.get("workflowPolicy"));
        if("TARGET_FACTS_V1".equals(context.get("workflowPolicy"))||review){
            Object rawTargets=context.getOrDefault("targetObligationIds",cn.bidcheck.compat.Java8Api.listOf());
            if(!(rawTargets instanceof List<?>)||(!review&&((List<?>) (rawTargets)).isEmpty())||((List<?>) (rawTargets)).stream().anyMatch(t->cn.bidcheck.compat.Java8Api.isBlank(safeId(t))))throw new BridgeFailure("TARGET_CONTRACT_MISSING");
        }
        if(!(start.get("tools") instanceof List<?>)||((List<?>) (start.get("tools"))).isEmpty())throw new BridgeFailure("NO_TOOLS_PROVIDED");
        Set<String> offered=new LinkedHashSet<>();
        for(Object value:((List<?>) (start.get("tools")))){Map<String,Object> tool=object(value,"INVALID_TOOL_SCHEMA");String name=Objects.toString(tool.get("name"),"");if(!toolAllowed(name,review))throw new BridgeFailure("TOOL_NOT_ALLOWED");if(!offered.add(name))throw new BridgeFailure("DUPLICATE_TOOL");object(tool.get("parameters"),"INVALID_TOOL_SCHEMA");}
        if(json.writeValueAsBytes(start).length>MAX_LINE_BYTES)throw new BridgeFailure("PROTOCOL_MESSAGE_TOO_LARGE");
        return offered;
    }
    private void send(OutputStream output,Map<String,Object> frame)throws IOException,BridgeFailure{
        byte[] bytes=json.writeValueAsBytes(frame);if(bytes.length>MAX_LINE_BYTES)throw new BridgeFailure("PROTOCOL_MESSAGE_TOO_LARGE");output.write(bytes);output.write('\n');output.flush();
    }
    private void readMessages(InputStream input,BlockingQueue<Object> queue){
        try{
            ByteArrayOutputStream line=new ByteArrayOutputStream(4096);int value;
            while((value=input.read())!=-1){
                if(value=='\n'){
                    byte[] bytes=line.toByteArray();line.reset();
                    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    Object frame=json.readValue(text,Object.class);queue.put(frame);
                }else{if(line.size()>=MAX_LINE_BYTES)throw new BridgeFailure("PROTOCOL_MESSAGE_TOO_LARGE");line.write(value);}
            }
            queue.put(new BridgeFailure("WORKER_DISCONNECTED"));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception error){try{queue.put(error instanceof BridgeFailure?error:new BridgeFailure("PROTOCOL_READ_FAILED"));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
    private Map<String,Object> safeEvent(Map<String,Object> frame,String requestId,HostReceipts receipts)throws BridgeFailure{
        String name=Objects.toString(frame.get("event"),"");if(!EVENTS.contains(name))throw new BridgeFailure("PROTOCOL_EVENT_INVALID");
        if("model_started".equals(name))receipts.observedModels.accumulateAndGet(nonnegative(frame.getOrDefault("modelIndex",0)),Math::max);
        if("tool_started".equals(name))receipts.observedTools.accumulateAndGet(nonnegative(frame.getOrDefault("toolIndex",0)),Math::max);
        Map<String,Object> safe=map("type","event","protocol",PROTOCOL,"requestId",requestId,"event",name);
        String observedEvent=safeEventId(frame.get("eventId"),requestId);if(!cn.bidcheck.compat.Java8Api.isBlank(observedEvent)&&receipts.observedEventIds.size()<4096)receipts.observedEventIds.add(observedEvent);
        if(!cn.bidcheck.compat.Java8Api.isBlank(observedEvent)&&cn.bidcheck.compat.Java8Api.setOf("target_tool_failed", "investigation_failed", "tool_arguments_rejected", "investigation_error").contains(name)&&receipts.observedFailureEventIds.size()<4096)receipts.observedFailureEventIds.add(observedEvent);
        for(String key:cn.bidcheck.compat.Java8Api.listOf("eventId", "firstFailureEventId")){String id=key.equals("eventId")?safeEventId(frame.get(key),requestId):receipts.failureEventId(frame.get(key),requestId);if(!cn.bidcheck.compat.Java8Api.isBlank(id))safe.put(key,id);}
        if(name.equals("model_started"))safe.put("id",safeId(frame.get("id")));
        if(frame.get("modelAudit") instanceof Map<?,?>){
            String status=Objects.toString(((Map<?,?>) (frame.get("modelAudit"))).get("status"),"");
            if(cn.bidcheck.compat.Java8Api.setOf("DISABLED", "SAVED", "PAYLOAD_OMITTED", "WRITE_FAILED", "INVALID_METADATA").contains(status)){
                Map<String,Object> metadata=map("status",status);
                String file=Objects.toString(((Map<?,?>) (frame.get("modelAudit"))).get("file"),""),hash=Objects.toString(((Map<?,?>) (frame.get("modelAudit"))).get("sha256"),"");
                if(file.matches("[a-f0-9]{32}-[0-9]{5,10}-(input|output|error)\\.json")&&hash.matches("[a-f0-9]{64}")){
                    metadata.put("file",file);metadata.put("sha256",hash);metadata.put("bytes",nonnegative(((Map<?,?>) (frame.get("modelAudit"))).get("bytes")));
                }
                safe.put("modelAudit",metadata);
            }
        }
        for(String key:cn.bidcheck.compat.Java8Api.listOf("elapsedMs", "modelIndex", "toolIndex", "durationMs", "acceptedFactCount", "duplicateCalls", "inputChars", "removedChars", "retainedToolBatches", "retainedFunctionCalls", "receiptInputChars", "receiptOutputChars", "receiptRemovedChars", "disclosedSourceCount", "sourceCount", "targetCount", "attemptForTargetGap", "maxAttemptsForTargetGap", "inputTokens", "outputTokens", "newSourceCount", "acceptedFactDelta", "closedTargetDelta", "noEvidenceProgressRounds"))if(frame.containsKey(key))safe.put(key,nonnegative(frame.get(key)));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("businessCheckCompleted", "targetCheckCompleted", "toolFailed", "correctable"))if(frame.get(key) instanceof Boolean)safe.put(key,frame.get(key));
        if(safe.containsKey("businessCheckCompleted"))safe.put("businessCheckCompleted",receipts.completed);
        if(safe.containsKey("targetCheckCompleted"))safe.put("targetCheckCompleted",receipts.targetCompleted);
        if(safe.containsKey("acceptedFactCount"))safe.put("acceptedFactCount",receipts.accepted.size());
        for(String key:cn.bidcheck.compat.Java8Api.listOf("path", "evidenceRevision", "planRevision"))if(!cn.bidcheck.compat.Java8Api.isBlank(safeId(frame.get(key))))safe.put(key,safeId(frame.get(key)));
        if(receipts.targets.contains(safeId(frame.get("targetObligationId"))))safe.put("targetObligationId",safeId(frame.get("targetObligationId")));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("targetId", "targetObligationId")){String target=safeId(frame.get(key));if(receipts.diagnosticTarget(target))safe.put(key,target);}
        if(cn.bidcheck.compat.Java8Api.setOf("BLOCKED", "COMPLETED", "NO_MORE_EVIDENCE").contains(Objects.toString(frame.get("targetState"),"")))safe.put("targetState",frame.get("targetState"));
        if(TARGET_STATES.contains(Objects.toString(frame.get("stateAfter"),""))){String target=safeId(frame.getOrDefault("targetObligationId",frame.get("targetId")));safe.put("stateAfter","PROVEN".equals(frame.get("stateAfter"))&&!receipts.closedTargets.contains(target)?"RUNNING":frame.get("stateAfter"));}
        for(String key:cn.bidcheck.compat.Java8Api.listOf("operationKey", "progressScope")){String value=Objects.toString(frame.get(key),"");if(value.matches("[a-f0-9]{64}"))safe.put(key,value);}
        for(String key:cn.bidcheck.compat.Java8Api.listOf("ruleVersion", "relevantEvidenceRevision")){String value=safeId(frame.get(key));if(!cn.bidcheck.compat.Java8Api.isBlank(value))safe.put(key,value);}
        if(frame.get("newTableRecord") instanceof Boolean)safe.put("newTableRecord",frame.get("newTableRecord"));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("rejectionCode", "stage")){String value=Objects.toString(frame.get(key),"");if(value.matches("[A-Z][A-Z0-9_]{0,95}"))safe.put(key,value);}
        if(Boolean.FALSE.equals(frame.get("hostStateReplayed")))safe.put("hostStateReplayed",false);
        if(frame.get("failureFamily") instanceof String&&((String) (frame.get("failureFamily"))).matches("[A-Z][A-Z0-9_]{0,63}"))safe.put("failureFamily",((String) (frame.get("failureFamily"))));
        if(frame.get("validatorKeyword") instanceof String&&((String) (frame.get("validatorKeyword"))).matches("[A-Za-z][A-Za-z0-9_]{0,63}"))safe.put("validatorKeyword",((String) (frame.get("validatorKeyword"))));
        if(frame.get("fieldPath") instanceof String&&((String) (frame.get("fieldPath"))).matches("/[A-Za-z0-9_~./@\\[\\]-]{0,255}"))safe.put("fieldPath",((String) (frame.get("fieldPath"))));
        if(frame.get("reason") instanceof String&&((String) (frame.get("reason"))).matches("[A-Z][A-Z0-9_]{0,95}"))safe.put("reason",((String) (frame.get("reason"))));
        if(frame.get("remainingRepairActions") instanceof Collection<?>)safe.put("remainingRepairActions",cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (frame.get("remainingRepairActions"))).stream().filter(String.class::isInstance).map(String.class::cast).filter(action->action.matches("[A-Z][A-Z0-9_]{0,63}")).distinct().limit(12)));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("affectedTargetObligationIds", "blockedTargetObligationIds", "remainingTargetObligationIds", "exhaustedTargetObligationIds", "businessUnclosedTargetObligationIds", "pendingConfirmationTargetObligationIds"))if(frame.get(key) instanceof Collection<?>)safe.put(key,knownTargets(((Collection<?>) (frame.get(key))),receipts));
        if(cn.bidcheck.compat.Java8Api.setOf("target_navigation_progress", "target_proof_progress", "target_tool_failed", "investigation_failed", "no_evidence_progress", "evidence_pending").contains(name)){safe.put("diagnosticOnly",true);safe.put("businessProof",false);}
        if(frame.containsKey("tool")){String tool=Objects.toString(frame.get("tool"),"");if(!toolAllowed(tool,receipts.review))throw new BridgeFailure("TOOL_NOT_ALLOWED");safe.put("tool",tool);}
        if(name.equals("investigation_error"))safe.put("errorCategory",(java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "AuthenticationError") || java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "PermissionDeniedError") ? "PROVIDER_AUTHENTICATION" : (java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "RateLimitError") ? "PROVIDER_RATE_LIMIT" : (java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "APITimeoutError") || java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "TimeoutError") ? "PROVIDER_TIMEOUT" : (java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "APIConnectionError") || java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "ConnectError") ? "PROVIDER_CONNECTION" : (java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "BadRequestError") || java.util.Objects.equals((Objects.toString(frame.get("errorType"),"")), "UnprocessableEntityError") ? "PROVIDER_REQUEST_FORMAT" : "MODEL_OR_SDK_ERROR"))))));
        if(cn.bidcheck.compat.Java8Api.isBlank(receipts.firstFailureEventId)&&cn.bidcheck.compat.Java8Api.setOf("target_tool_failed", "investigation_failed", "tool_arguments_rejected", "investigation_error").contains(name)){
            String failure=receipts.failureEventId(frame.get("firstFailureEventId"),requestId);
            if(cn.bidcheck.compat.Java8Api.isBlank(failure))failure=observedEvent;
            if(!cn.bidcheck.compat.Java8Api.isBlank(failure))receipts.firstFailureEventId=failure;
        }
        // Arbitrary exception names and model/event metadata are deliberately omitted.
        return Collections.unmodifiableMap(safe);
    }
    private Map<String,Object> safeResult(Map<String,Object> frame,HostReceipts receipts,long toolCalls,String requestId,long began)throws BridgeFailure{
        String status=Objects.toString(frame.get("status"),"");if(!cn.bidcheck.compat.Java8Api.setOf("COMPLETED", "EVIDENCE_UPDATED", "NO_PROGRESS", "FAILED").contains(status))throw new BridgeFailure("WORKER_RESULT_STATUS_INVALID");
        if(!Boolean.FALSE.equals(frame.get("tracingExportEnabled")))throw new BridgeFailure("WORKER_TRACING_POLICY_VIOLATION");
        if(status.equals("COMPLETED")&&!receipts.targetCompleted&&!receipts.completed||status.equals("EVIDENCE_UPDATED")&&receipts.accepted.isEmpty())throw new BridgeFailure("WORKER_RESULT_STATE_MISMATCH");
        String code=Objects.toString(frame.get("code"),"");if(!cn.bidcheck.compat.Java8Api.isBlank(code)&&!WORKER_ERRORS.contains(code)&&!Arrays.asList("PROVIDER_CONNECTION","PROVIDER_TIMEOUT","PROVIDER_SERVER","PROVIDER_TLS_ERROR","PROVIDER_AUTHENTICATION","PROVIDER_RATE_LIMIT").contains(code)&&!cn.bidcheck.compat.Java8Api.setOf("MODEL_PERMISSION_TIMEOUT", "MODEL_PERMISSION_PROTOCOL_ERROR", "MODEL_REQUEST_DENIED", "SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", "EVIDENCE_PENDING", "AGENT_STALLED_WITH_REMAINING_TARGETS", "TARGET_CONTRACT_MISSING", "TARGET_WORK_RECEIPT_MISSING", "TARGET_WORK_RECEIPT_INVALID", "INVALID_TARGET_RECEIPT", "CANDIDATE_TARGET_REQUIRED", "REVIEW_PLAN_REQUIRED", "REVIEW_PLAN_RECEIPT_INVALID", "REVIEW_PLAN_REVISION_MISMATCH", "TARGET_STATE_UNCHANGED", "HOST_TARGET_RECEIPT_INVALID", "WAIT_CONFIRMATION", "HOST_CONFIRMATION_RECEIPT_INVALID","INVESTIGATION_CALL_LIMIT").contains(code))code="WORKER_REPORTED_FAILURE";
        if(status.equals("FAILED")&&cn.bidcheck.compat.Java8Api.isBlank(code))code="WORKER_REPORTED_FAILURE";
        // A worker's historical tool error cannot override the host's current
        // operation receipts. Transport/model failures remain fail-closed.
        if(!status.equals("FAILED")||code.equals("TOOL_OPERATION_FAILED")){
            status=receipts.targetCompleted||receipts.completed?"COMPLETED":!receipts.unresolved.isEmpty()?"FAILED":receipts.accepted.isEmpty()?"NO_PROGRESS":"EVIDENCE_UPDATED";
            if(status.equals("FAILED"))code="TOOL_OPERATION_FAILED";
            else if(code.equals("TOOL_OPERATION_FAILED"))code="";
        }
        Map<String,Object> safe=map("status",status,"code",code,"errorCode",status.equals("FAILED")?code:"","requestId",requestId,"protocol",PROTOCOL,"policy",POLICY,
            "acceptedFactIds",cn.bidcheck.compat.Java8Api.listCopyOf(receipts.accepted),"businessCheckCompleted",receipts.completed,"targetCheckCompleted",receipts.targetCompleted,
            "failedToolCount",receipts.unresolved.size(),"recoveredToolCount",receipts.recovered,"validationRejectionCount",receipts.rejections,
            "toolCalls",toolCalls,"elapsedMs",elapsed(began),"sdk",protocolFactory==null?"bidcheck-java":"protocol-test","tracingExportEnabled",false);
        for(String key:cn.bidcheck.compat.Java8Api.listOf("modelCalls", "modelPermissionRequests", "duplicateCallsAvoided", "inputTokens", "outputTokens", "contextCompactions", "contextRebuilds", "contextRetainedToolBatches", "contextReceiptInputChars", "contextReceiptOutputChars", "contextReceiptRemovedChars", "contextInputChars", "contextRemovedChars", "targetGateBlocks", "correctableToolRejectionCount", "maxNoEvidenceProgressRounds"))safe.put(key,nonnegative(frame.getOrDefault(key,0)));
        safe.put("workflowPolicy",receipts.workflowPolicy);safe.put("approvedPlanRevision",receipts.planRevision);
        for(String key:cn.bidcheck.compat.Java8Api.listOf("blockedTargetObligationIds", "remainingTargetObligationIds", "exhaustedTargetObligationIds", "pendingConfirmationTargetObligationIds"))if(frame.get(key) instanceof Collection<?>)safe.put(key,cn.bidcheck.compat.Java8Api.toList(knownTargets(((Collection<?>) (frame.get(key))),receipts).stream().filter(id->!receipts.closedTargets.contains(id))));
        safe.put("businessUnclosedTargetObligationIds",cn.bidcheck.compat.Java8Api.toList(receipts.targets.stream().filter(id->!receipts.closedTargets.contains(id))));
        String firstFailure=cn.bidcheck.compat.Java8Api.isBlank(receipts.firstFailureEventId)?receipts.failureEventId(frame.get("firstFailureEventId"),requestId):receipts.firstFailureEventId;if(!cn.bidcheck.compat.Java8Api.isBlank(firstFailure))safe.put("firstFailureEventId",firstFailure);
        if(frame.containsKey("targetProgressLedger")){
            safe.put("targetProgressLedger",safeLedger(frame.get("targetProgressLedger"),receipts,requestId));
            safe.put("targetProgressLedgerTruncated",frame.get("targetProgressLedger") instanceof Collection<?>&&((Collection<?>) (frame.get("targetProgressLedger"))).size()>MAX_TARGET_DIAGNOSTICS);
        }
        safe.put("completedTargetObligationIds",cn.bidcheck.compat.Java8Api.listCopyOf(receipts.closedTargets));safe.put("automaticRecomputations",receipts.automaticRecomputations);
        safe.put("repeatedRejectionCount",nonnegative(frame.getOrDefault("repeatedRejectionCount",0)));
        if("AGENT_STALLED_WITH_REMAINING_TARGETS".equals(code)){safe.put("investigationIncomplete",true);safe.put("stopScope","CURRENT_INVESTIGATION_ONLY");safe.put("resumeRequiresChangedTargetOrEvidence",true);}
        String version=Objects.toString(frame.get("sdkVersion"),"");if(version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[A-Za-z0-9.-]{0,24})?"))safe.put("sdkVersion",version);
        return Collections.unmodifiableMap(safe);
    }
    private Map<String,Object> failure(String code,String requestId,HostReceipts receipts,long began){
        LOG.info("bidcheck.agent_bridge requestId={} policy={} status=FAILED code={} elapsedMs={}",requestId,POLICY,code,elapsed(began));
        return map("status","FAILED","code",code,"errorCode",code,"requestId",requestId,"protocol",PROTOCOL,"policy",POLICY,"acceptedFactIds",cn.bidcheck.compat.Java8Api.listCopyOf(receipts.accepted),"businessCheckCompleted",false,"targetCheckCompleted",false,
            "firstFailureEventId",receipts.firstFailureEventId,"completedTargetObligationIds",cn.bidcheck.compat.Java8Api.listCopyOf(receipts.closedTargets),"businessUnclosedTargetObligationIds",cn.bidcheck.compat.Java8Api.toList(receipts.targets.stream().filter(id->!receipts.closedTargets.contains(id))),
            "modelCalls",receipts.observedModels.get(),"toolCalls",receipts.observedTools.get(),"countBasis","OBSERVED_STARTED_EVENTS","failedToolCount",receipts.unresolved.size(),"recoveredToolCount",receipts.recovered,"validationRejectionCount",receipts.rejections,"elapsedMs",elapsed(began),"tracingExportEnabled",false);
    }
    private static <T>T invoke(ExecutorService executor,Callable<T> action,long deadline,String code,AtomicBoolean active)throws InterruptedException,BridgeFailure{
        Future<T> future=executor.submit(()->{CALL_FENCE.set(active);try{if(!currentCallActive())throw new InterruptedException();return action.call();}finally{CALL_FENCE.remove();}});
        try{return future.get(remaining(deadline),TimeUnit.NANOSECONDS);}
        catch(TimeoutException timeout){active.set(false);future.cancel(true);throw new BridgeFailure("INVESTIGATION_TIMEOUT");}
        catch(InterruptedException cancelled){active.set(false);future.cancel(true);throw cancelled;}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof InterruptedException){Thread.currentThread().interrupt();throw new InterruptedException();}if(cause instanceof BridgeFailure)throw ((BridgeFailure) (cause));throw new BridgeFailure(code);}
        finally{if(!future.isDone())future.cancel(true);}
    }
    static String normalizedEndpoint(String raw)throws BridgeFailure{
        try{
            if(raw==null||cn.bidcheck.compat.Java8Api.isBlank(raw)||raw.contains("\\"))throw new BridgeFailure("MODEL_ENDPOINT_NOT_ALLOWED");
            URI uri=URI.create(cn.bidcheck.compat.Java8Api.strip(raw));String path=Objects.toString(uri.getPath(),"").replaceFirst("/+$","");if(path.endsWith("/chat/completions"))path=path.substring(0,path.length()-"/chat/completions".length());
            if(!"https".equalsIgnoreCase(uri.getScheme())||!HOSTS.contains(Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT))||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||!path.equals("/compatible-mode/v1")||uri.getRawPath().contains("%"))throw new BridgeFailure("MODEL_ENDPOINT_NOT_ALLOWED");
            return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+"/compatible-mode/v1";
        }catch(IllegalArgumentException invalid){throw new BridgeFailure("MODEL_ENDPOINT_NOT_ALLOWED");}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value,String code)throws BridgeFailure{if(!(value instanceof Map<?,?>)||((Map<?,?>) (value)).keySet().stream().anyMatch(k->!(k instanceof String)))throw new BridgeFailure(code);return (Map<String,Object>)((Map<?,?>) (value));}
    private static long nonnegative(Object value)throws BridgeFailure{if(!(value instanceof Number))throw new BridgeFailure("PROTOCOL_COUNTER_INVALID");long integer=((Number) (value)).longValue();if(integer<0||integer>1_000_000_000_000L||((Number) (value)).doubleValue()!=integer)throw new BridgeFailure("PROTOCOL_COUNTER_INVALID");return integer;}
    private static long remaining(long deadline)throws BridgeFailure{long remaining=deadline-System.nanoTime();if(remaining<=0)throw new BridgeFailure("INVESTIGATION_TIMEOUT");return remaining;}
    private static long elapsed(long began){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);}
    private static String trim(String value){return cn.bidcheck.compat.Java8Api.strip(Objects.toString(value,""));}
    private static boolean toolAllowed(String name,boolean review){return TOOLS.contains(name)||review&&REVIEW_TOOLS.contains(name);}
    private static String safeId(Object value){return value instanceof String&&((String) (value)).matches("[A-Za-z0-9_.:-]{1,160}")?((String) (value)):"";}
    private static String safeEventId(Object value,String requestId){return value instanceof String&&((String) (value)).length()<=200&&((String) (value)).matches(java.util.regex.Pattern.quote(requestId)+":[1-9][0-9]{0,9}")?((String) (value)):"";}
    private static List<String> knownTargets(Collection<?> values,HostReceipts receipts){return cn.bidcheck.compat.Java8Api.toList(values.stream().map(SdkAgentInvestigator::safeId).filter(receipts.targets::contains).distinct().limit(MAX_TARGET_DIAGNOSTICS));}
    private List<Map<String,Object>> safeLedger(Object raw,HostReceipts receipts,String requestId)throws BridgeFailure{
        if(!(raw instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();
        List<Map<String,Object>> safe=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object value:((Collection<?>) (raw))){
            if(safe.size()>=MAX_TARGET_DIAGNOSTICS)break;
            if(!(value instanceof Map<?,?>))continue;
            String target=safeId(((Map<?,?>) (value)).get("targetObligationId")),scope=Objects.toString(((Map<?,?>) (value)).get("progressScope"),"");
            String state=Objects.toString(((Map<?,?>) (value)).get("state"),""),ruleVersion=safeId(((Map<?,?>) (value)).get("ruleVersion")),revision=safeId(((Map<?,?>) (value)).get("relevantEvidenceRevision"));
            boolean discovery=target.equals("DOCUMENT_DISCOVERY");
            if(!receipts.diagnosticTarget(target)||!scope.matches("[a-f0-9]{64}")||!TARGET_STATES.contains(state)||seen.contains(target))continue;
            if(!receipts.ruleVersion().equals(ruleVersion)||(!discovery&&!revision.equals(receipts.targetRevisions.get(target)))||(discovery&&!revision.matches("[a-f0-9]{64}")))continue;
            seen.add(target);
            // Worker diagnostics never certify a completed target. Host recomputation owns that status.
            String current=receipts.closedTargets.contains(target)?"PROVEN":state.equals("PROVEN")?"RUNNING":state;
            Map<String,Object> entry=map("targetObligationId",target,"progressScope",scope,"ruleVersion",ruleVersion,
                "relevantEvidenceRevision",revision,"state",current,"diagnosticOnly",true,"businessProof",false);
            if(!state.equals(current))entry.put("workerState",state);
            if(((Map<?,?>) (value)).get("visited") instanceof Boolean)entry.put("visited",((Map<?,?>) (value)).get("visited"));
            for(String key:cn.bidcheck.compat.Java8Api.listOf("noEvidenceProgressRounds", "maxNoEvidenceProgressRounds", "acceptedFactCount", "closedTargetCount", "navigationCount"))if(((Map<?,?>) (value)).containsKey(key))entry.put(key,nonnegative(((Map<?,?>) (value)).get(key)));
            // Only the current request's Runner counter survives this diagnostic boundary.
            Map<?,?> diagnostic = (Map<?,?>) value;
            // Presence is sticky: malformed new diagnostics must never become legacy authority.
            for (String field : cn.bidcheck.compat.Java8Api.listOf("noNewFactCounterVersion", "noNewFactRequestId",
                    "noNewFactModelRounds", "noNewFactStopReason")) {
                if (diagnostic.containsKey(field)) entry.put("noNewFactCounterVersion", "INVALID");
            }
            if ("java-agent-target-rounds-1".equals(diagnostic.get("noNewFactCounterVersion"))
                    && requestId.equals(diagnostic.get("noNewFactRequestId"))
                    && diagnostic.get("noNewFactModelRounds") instanceof Number
                    && diagnostic.get("noNewFactStopReason") instanceof String) {
                Number rawRounds = (Number) diagnostic.get("noNewFactModelRounds");
                long rounds = rawRounds.longValue();
                String reason = Objects.toString(diagnostic.get("noNewFactStopReason"), "");
                if (rounds >= 0 && rawRounds.doubleValue() == (double) rounds
                        && rounds <= receipts.observedModels.get()
                        && (reason.isEmpty() && rounds < 3 || "THREE_ROUNDS_NO_NEW_FACT".equals(reason) && rounds >= 3)) {
                    entry.put("noNewFactCounterVersion", "java-agent-target-rounds-1");
                    entry.put("noNewFactRequestId", requestId);
                    entry.put("noNewFactModelRounds", rounds);
                    entry.put("noNewFactStopReason", reason);
                }
            }
            if(!receipts.closedTargets.contains(target))entry.put("closedTargetCount",0L);
            String failure=receipts.failureEventId(((Map<?,?>) (value)).get("firstFailureEventId"),requestId);if(!cn.bidcheck.compat.Java8Api.isBlank(failure))entry.put("firstFailureEventId",failure);
            String operation=Objects.toString(((Map<?,?>) (value)).get("lastOperationKey"),"");if(operation.matches("[a-f0-9]{64}"))entry.put("lastOperationKey",operation);
            String code=Objects.toString(((Map<?,?>) (value)).get("rejectionCode"),"");if(code.matches("[A-Z][A-Z0-9_]{0,95}"))entry.put("rejectionCode",code);
            if("RELEVANT_RULE_OR_EVIDENCE_CHANGED".equals(((Map<?,?>) (value)).get("resumeTrigger"))&&!receipts.closedTargets.contains(target))entry.put("resumeTrigger",((Map<?,?>) (value)).get("resumeTrigger"));
            safe.add(entry);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(safe);
    }
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException("SHA256_UNAVAILABLE");}}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(values[i].toString(),values[i+1]);return out;}
    static final class BridgeFailure extends Exception{final String code;BridgeFailure(String code){super(code);this.code=code;}}
    private final class HostReceipts{
        String requestId = "";
        final java.util.concurrent.atomic.AtomicLong observedModels=new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong observedTools=new java.util.concurrent.atomic.AtomicLong();
        final Set<String> accepted=new LinkedHashSet<>(),unresolved=new LinkedHashSet<>(),targets=new LinkedHashSet<>(),closedTargets=new LinkedHashSet<>();
        final Set<String> historicalFailureIds=new HashSet<>(),observedEventIds=new HashSet<>(),observedFailureEventIds=new HashSet<>();
        final Map<String,String> targetRevisions=new LinkedHashMap<>(),blockedTargets=new LinkedHashMap<>();
        boolean completed,targetCompleted,typed,review,canonicalTargetPartition;int recovered,rejections,automaticRecomputations;String evidenceRevision="",planRevision="",serverPlanRevision="",serverPlanFingerprint="",firstFailureEventId="",workflowPolicy="LEGACY_EVIDENCE";
        Map<String,Object> snapshot(){return map("targetObligationIds",new ArrayList<>(targets),"acceptedFactIds",new ArrayList<>(accepted),"completedTargetObligationIds",new ArrayList<>(closedTargets),"businessCheckCompleted",completed,"targetCheckCompleted",targetCompleted,"targetEvidenceRevision",evidenceRevision,"targetEvidenceRevisions",new LinkedHashMap<>(targetRevisions),"approvedPlanRevision",planRevision,"failedToolCount",unresolved.size(),"canonicalTargetPartition",canonicalTargetPartition);}
        void initialize(Map<String,Object> context)throws BridgeFailure{
            String policy=Objects.toString(context.get("workflowPolicy"),"");review="AGENT_LED_REVIEW_V1".equals(policy);typed=review||"TARGET_FACTS_V1".equals(policy);if(typed)workflowPolicy=policy;
            if(context.get("targetObligationIds") instanceof Collection<?>)for(Object value:((Collection<?>) (context.get("targetObligationIds"))))if(!cn.bidcheck.compat.Java8Api.isBlank(safeId(value)))targets.add(safeId(value));
            evidenceRevision=safeId(context.get("targetEvidenceRevision"));serverPlanRevision=safeId(context.get("serverPlanRevision"));serverPlanFingerprint=safeId(context.get("serverPlanFingerprint"));
            if(context.get("targetProgressLedger") instanceof Collection<?>)for(Object raw:((Collection<?>) (context.get("targetProgressLedger"))))if(raw instanceof Map<?,?>&&targets.contains(safeId(((Map<?,?>) (raw)).get("targetObligationId")))){
                String id=Objects.toString(((Map<?,?>) (raw)).get("firstFailureEventId"),"");if(id.matches("[A-Za-z0-9_.:-]{1,160}:[1-9][0-9]{0,9}")&&historicalFailureIds.size()<MAX_TARGET_DIAGNOSTICS)historicalFailureIds.add(id);
            }
            refreshTargets(context);observeWork(context);
            if(typed&&(context.containsKey("targetReceiptAuthority")||context.containsKey("completedTargetObligationIds")||context.containsKey("remainingTargetObligationIds"))){
                // start is issued by the host; still validate its complete canonical partition and versions.
                completed=Boolean.TRUE.equals(context.get("businessCheckCompleted"));
                targetCompleted=completed||Boolean.TRUE.equals(context.get("targetCheckCompleted"));
                observeTargets(context);
            }
        }
        boolean diagnosticTarget(String target){return targets.contains(target)||typed&&targets.size()>1&&target.equals("DOCUMENT_DISCOVERY");}
        String ruleVersion(){return review?planRevision:cn.bidcheck.compat.Java8Api.isBlank(serverPlanRevision)?"SESSION_FROZEN":serverPlanRevision;}
        String failureEventId(Object value,String requestId){String id=safeEventId(value,requestId);return !cn.bidcheck.compat.Java8Api.isBlank(id)&&observedFailureEventIds.contains(id)?id:value instanceof String&&historicalFailureIds.contains(((String) (value)))?((String) (value)):"";}
        void validateCall(String name,Map<String,Object> arguments)throws BridgeFailure{
            if(review&&(cn.bidcheck.compat.Java8Api.setOf("submit_candidate_fact", "submit_review_judgment", "recompute_check").contains(name)||REVIEW_TARGET_READS.contains(name))){
                if(cn.bidcheck.compat.Java8Api.isBlank(planRevision))throw new BridgeFailure("REVIEW_PLAN_REQUIRED");
                if(name.equals("submit_review_judgment")&&!planRevision.equals(arguments.get("planRevision")))throw new BridgeFailure("REVIEW_PLAN_REVISION_MISMATCH");
            }
            if(typed&&arguments.containsKey("targetObligationId")&&!targets.contains(safeId(arguments.get("targetObligationId"))))throw new BridgeFailure("CANDIDATE_TARGET_REQUIRED");
            if(typed&&(cn.bidcheck.compat.Java8Api.setOf("submit_candidate_fact", "submit_review_judgment").contains(name)||review&&REVIEW_TARGET_READS.contains(name)||REVIEW_TARGET_READS.contains(name)&&arguments.containsKey("targetObligationId"))){
                String target=safeId(arguments.get("targetObligationId"));if(!targets.contains(target))throw new BridgeFailure("CANDIDATE_TARGET_REQUIRED");
                if(closedTargets.contains(target)||blockedTargets.containsKey(target))throw new BridgeFailure("TARGET_STATE_UNCHANGED");
            }
        }
        void refreshTargets(Map<?,?> result){
            if(!typed||!(result.get("targetEvidenceRevisions") instanceof Map<?,?>))return;
            for(String target:targets){String revision=safeId(((Map<?,?>) (result.get("targetEvidenceRevisions"))).get(target));if(cn.bidcheck.compat.Java8Api.isBlank(revision))continue;String previous=targetRevisions.put(target,revision);
                if(previous!=null&&!previous.equals(revision)){closedTargets.remove(target);blockedTargets.remove(target);completed=false;targetCompleted=false;}
            }
        }
        void observeWork(Map<?,?> result){
            if(!typed||!(result.get("semanticWorkItems") instanceof Collection<?>))return;
            for(Object raw:((Collection<?>) (result.get("semanticWorkItems"))))if(raw instanceof Map<?,?>){String target=safeId(((Map<?,?>) (raw)).get("targetObligationId")),revision=safeId(((Map<?,?>) (raw)).get("evidenceRevision"));
                if(!targets.contains(target)||cn.bidcheck.compat.Java8Api.isBlank(revision))continue;
                targetRevisions.putIfAbsent(target,revision);
                if(Boolean.TRUE.equals(((Map<?,?>) (raw)).get("terminalForRevision"))&&Boolean.TRUE.equals(((Map<?,?>) (raw)).get("targetExhaustedForRevision"))&&revision.equals(targetRevisions.get(target)))blockedTargets.put(target,revision);
            }
        }
        void acceptPlan(Map<String,Object> result)throws BridgeFailure{
            if(!(result.get("planReceipt") instanceof Map<?,?>)||!Boolean.TRUE.equals(((Map<?,?>) (result.get("planReceipt"))).get("approved")))return;
            String revision=safeId(((Map<?,?>) (result.get("planReceipt"))).get("planRevision"));Object raw=((Map<?,?>) (result.get("planReceipt"))).containsKey("targetObligationIds")?((Map<?,?>) (result.get("planReceipt"))).get("targetObligationIds"):result.get("targetObligationIds");
            if(cn.bidcheck.compat.Java8Api.isBlank(revision)||!(raw instanceof List<?>)||((List<?>) (raw)).isEmpty()||((List<?>) (raw)).stream().anyMatch(v->cn.bidcheck.compat.Java8Api.isBlank(safeId(v)))||new HashSet<>(((List<?>) (raw))).size()!=((List<?>) (raw)).size())throw new BridgeFailure("REVIEW_PLAN_RECEIPT_INVALID");
            if(revision.equals(planRevision)&&!targets.equals(new LinkedHashSet<>(((List<?>) (raw)))))throw new BridgeFailure("REVIEW_PLAN_RECEIPT_INVALID");
            if(!revision.equals(planRevision)){completed=false;targetCompleted=false;closedTargets.clear();blockedTargets.clear();targetRevisions.clear();}
            planRevision=revision;targets.clear();((List<?>) (raw)).forEach(v->targets.add(safeId(v)));
        }
        boolean currentPlan(Map<?,?> receipt){return !review||!cn.bidcheck.compat.Java8Api.isBlank(planRevision)&&planRevision.equals(receipt.get("planRevision"));}
        void accept(String name,Map<String,Object> arguments,Map<String,Object> result)throws IOException,BridgeFailure{
            String operation=name+":"+sha(json.writeValueAsBytes(name.equals("recompute_check")?cn.bidcheck.compat.Java8Api.mapOf():normalizeOperation(arguments,"")));
            String status=Objects.toString(result.get("status"),"");Object error=result.get("error");
            boolean hasError=error!=null&&!error.equals("")&&!error.equals(cn.bidcheck.compat.Java8Api.mapOf())&&!error.equals(cn.bidcheck.compat.Java8Api.listOf());
            boolean rejected=error instanceof String&&VALIDATION_REJECTIONS.contains(((String) (error)))||status.equals("REJECTED")&&(!hasError||review&&error instanceof String&&REVIEW_VALIDATION_REJECTIONS.contains(((String) (error))));
            boolean bad=!rejected&&(hasError||cn.bidcheck.compat.Java8Api.setOf("FAILED", "ERROR", "UNAVAILABLE").contains(status));
            if(review&&name.equals("submit_review_plan")&&!bad&&!rejected)acceptPlan(result);
            if(!bad)refreshTargets(result);
            String revision=safeId(result.get("targetEvidenceRevision"));if(!bad&&!cn.bidcheck.compat.Java8Api.isBlank(revision)&&!revision.equals(evidenceRevision)){evidenceRevision=revision;completed=false;if(!typed){targetCompleted=false;closedTargets.clear();}}
            if(bad)unresolved.add(operation);
            else if(rejected)rejections++;
            else if((cn.bidcheck.compat.Java8Api.isBlank(status)||cn.bidcheck.compat.Java8Api.setOf("COMPLETED", "NO_RECORD").contains(status))&&unresolved.remove(operation))recovered++;
            if(cn.bidcheck.compat.Java8Api.setOf("search_evidence", "read_section", "read_table_record", "submit_candidate_fact").contains(name)&&!bad&&!cn.bidcheck.compat.Java8Api.setOf("FAILED", "ERROR", "UNAVAILABLE").contains(status)&&result.get("acceptedFactIds") instanceof List<?>){int before=accepted.size();for(Object id:((List<?>) (result.get("acceptedFactIds"))))if(id instanceof String&&((String) (id)).length()<=256&&!cn.bidcheck.compat.Java8Api.isBlank(((String) (id))))accepted.add(((String) (id)));if(before!=accepted.size()){completed=false;if(!typed){targetCompleted=false;closedTargets.clear();}}}
            if(name.equals("recompute_check")){
                completed=!bad&&!rejected&&currentPlan(result)&&Boolean.TRUE.equals(result.get("businessCheckCompleted"));
                targetCompleted=!bad&&!rejected&&currentPlan(result)&&(completed||Boolean.TRUE.equals(result.get("targetCheckCompleted")));
                if(!bad&&!rejected&&currentPlan(result))observeTargets(result);
            }
            if((cn.bidcheck.compat.Java8Api.setOf("search_evidence", "read_section", "read_table_record", "submit_candidate_fact").contains(name)||review&&name.equals("submit_review_judgment"))&&!bad&&!result.containsKey("evaluatedTargetReceipt")&&result.get("computationReceipt") instanceof Map<?,?>
                    &&"HOST_RULE_RECOMPUTE".equals(((Map<?,?>) (result.get("computationReceipt"))).get("kind"))&&"COMPLETED".equals(((Map<?,?>) (result.get("computationReceipt"))).get("status"))&&!cn.bidcheck.compat.Java8Api.isBlank(revision)&&revision.equals(((Map<?,?>) (result.get("computationReceipt"))).get("targetEvidenceRevision"))
                    &&((Map<?,?>) (result.get("computationReceipt"))).get("businessCheckCompleted") instanceof Boolean&&((Map<?,?>) (result.get("computationReceipt"))).get("targetCheckCompleted") instanceof Boolean&&currentPlan(((Map<?,?>) (result.get("computationReceipt"))))){
                completed=Boolean.TRUE.equals(((Map<?,?>) (result.get("computationReceipt"))).get("businessCheckCompleted"));targetCompleted=completed||Boolean.TRUE.equals(((Map<?,?>) (result.get("computationReceipt"))).get("targetCheckCompleted"));automaticRecomputations++;observeTargets(((Map<?,?>) (result.get("computationReceipt"))));
            }
            if (!bad && !rejected && typed && result.containsKey("evaluatedTargetReceipt")) {
                observeEvaluatedRead(name, arguments, result);
            }
            if(!bad)observeWork(result);
        }
        void observeEvaluatedRead(String name, Map<String,Object> arguments, Map<String,Object> result)
                throws BridgeFailure {
            if (!(result.get("evaluatedTargetReceipt") instanceof Map<?,?>))
                throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
            Map<?,?> receipt = (Map<?,?>) result.get("evaluatedTargetReceipt");
            Object receiptTargets = receipt.get("targetObligationIds");
            boolean targetSetMatches = receiptTargets instanceof List<?>
                && ((List<?>) receiptTargets).size() == targets.size()
                && new LinkedHashSet<>((List<?>) receiptTargets).equals(targets);
            boolean boundToCall = REVIEW_TARGET_READS.contains(name)
                && "HOST_TARGET_RECOMPUTE".equals(receipt.get("kind"))
                && "COMPLETED".equals(receipt.get("status"))
                && !requestId.isEmpty() && requestId.equals(receipt.get("requestId"))
                && name.equals(receipt.get("tool"))
                && Objects.equals(arguments.getOrDefault("targetObligationId", ""), receipt.get("targetObligationId"));
            if (!boundToCall || !targetSetMatches
                    || !(receipt.get("businessCheckCompleted") instanceof Boolean)
                    || !(receipt.get("targetCheckCompleted") instanceof Boolean))
                throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
            for (String field : Arrays.asList("serverPlanRevision", "serverPlanFingerprint",
                    "targetEvidenceRevision", "targetEvidenceRevisions", "targetReceiptAuthority",
                    "completedTargetObligationIds", "remainingTargetObligationIds")) {
                if (!result.containsKey(field) || !Objects.equals(result.get(field), receipt.get(field)))
                    throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
                if (result.get("computationReceipt") instanceof Map<?,?>
                        && !Objects.equals(((Map<?,?>) result.get("computationReceipt")).get(field), receipt.get(field)))
                    throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
            }
            if (result.get("computationReceipt") instanceof Map<?,?>) {
                Map<?,?> nested = (Map<?,?>) result.get("computationReceipt");
                for (String field : Arrays.asList("businessCheckCompleted", "targetCheckCompleted"))
                    if (!Objects.equals(nested.get(field), receipt.get(field)))
                        throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
            }
            completed = Boolean.TRUE.equals(receipt.get("businessCheckCompleted"));
            targetCompleted = completed || Boolean.TRUE.equals(receipt.get("targetCheckCompleted"));
            observeTargets(receipt);
            automaticRecomputations++;
        }
        void observeTargets(Map<?,?> receipt)throws BridgeFailure{
            if(typed&&(receipt.containsKey("targetReceiptAuthority")||receipt.containsKey("completedTargetObligationIds")||receipt.containsKey("remainingTargetObligationIds"))){
                Object rawDone=receipt.get("completedTargetObligationIds"),rawRemaining=receipt.get("remainingTargetObligationIds");
                if(!(rawDone instanceof List<?>)||!(rawRemaining instanceof List<?>))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
                Set<String> doneIds=new LinkedHashSet<>(),remainingIds=new LinkedHashSet<>();
                for(Object id:((List<?>) (rawDone))){String value=safeId(id);if(!targets.contains(value)||!doneIds.add(value))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");}
                for(Object id:((List<?>) (rawRemaining))){String value=safeId(id);if(!targets.contains(value)||!remainingIds.add(value)||doneIds.contains(value))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");}
                Set<String> partition=new HashSet<>(doneIds);partition.addAll(remainingIds);
                boolean plan=review?currentPlan(receipt):!cn.bidcheck.compat.Java8Api.isBlank(serverPlanRevision)&&serverPlanRevision.equals(receipt.get("serverPlanRevision"))&&(cn.bidcheck.compat.Java8Api.isBlank(serverPlanFingerprint)||serverPlanFingerprint.equals(receipt.get("serverPlanFingerprint")));
                boolean revisions=receipt.get("targetEvidenceRevisions") instanceof Map<?,?>&&targets.stream().allMatch(id->!cn.bidcheck.compat.Java8Api.isBlank(targetRevisions.getOrDefault(id,""))&&targetRevisions.get(id).equals(((Map<?,?>) (receipt.get("targetEvidenceRevisions"))).get(id)));
                if(!"CURRENT_SERVER_PROOF".equals(receipt.get("targetReceiptAuthority"))||!partition.equals(targets)||!plan||!revisions||cn.bidcheck.compat.Java8Api.isBlank(evidenceRevision)||!evidenceRevision.equals(receipt.get("targetEvidenceRevision"))||!remainingIds.isEmpty()&&(completed||targetCompleted))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
                canonicalTargetPartition=true;closedTargets.clear();closedTargets.addAll(doneIds);doneIds.forEach(blockedTargets::remove);return;
            }
            if(completed||targetCompleted){closedTargets.addAll(targets);return;}
            if(receipt.get("obligations") instanceof Collection<?>)for(Object raw:((Collection<?>) (receipt.get("obligations"))))if(raw instanceof Map<?,?>){String id=safeId(((Map<?,?>) (raw)).get("id"));if(!targets.contains(id))continue;
                if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(Objects.toString(((Map<?,?>) (raw)).get("status"),""))&&cn.bidcheck.compat.Java8Api.listOf().equals(((Map<?,?>) (raw)).get("gaps")))closedTargets.add(id);else closedTargets.remove(id);
            }
        }
    }
    private static Object normalizeOperation(Object value,String field){
        if(value instanceof Map<?,?>){Map<String,Object> sorted=new TreeMap<>();((Map<?,?>) (value)).forEach((key,item)->sorted.put(key.toString(),normalizeOperation(item,key.toString())));return sorted;}
        if(value instanceof List<?>){List<Object> items=cn.bidcheck.compat.Java8Api.toList(((List<?>) (value)).stream().map(item->normalizeOperation(item,"")));if(field.equals("sourceIds")&&items.stream().allMatch(String.class::isInstance))return cn.bidcheck.compat.Java8Api.toList(items.stream().map(String.class::cast).distinct().sorted());return items;}
        if(value instanceof String&&!cn.bidcheck.compat.Java8Api.setOf("sourceId", "sourceIds").contains(field))return cn.bidcheck.compat.Java8Api.strip(Normalizer.normalize(((String) (value)),Normalizer.Form.NFKC)).replaceAll("\\s+"," ");
        return value;
    }
}
