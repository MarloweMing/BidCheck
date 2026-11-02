package cn.bidcheck.verification.agent;

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
import org.springframework.stereotype.Component;

/** Local UTF-8 JSONL process boundary. No model prose, source payload or credential is logged. */
@Component
public final class SdkAgentInvestigator implements AgentInvestigator {
    static final String PROTOCOL="bidcheck-agent-1",POLICY="java-sdk-bridge-10-local-tool-audit";
    static final int MAX_LINE_BYTES=4*1024*1024,MAX_ARGUMENT_BYTES=64*1024;
    private static final Set<String> HOSTS=Set.of("dashscope.aliyuncs.com","dashscope-intl.aliyuncs.com","dashscope-us.aliyuncs.com");
    private static final Set<String> TOOLS=Set.of("search_evidence","read_section","read_table_record","inspect_page_region","query_external","submit_candidate_fact","recompute_check");
    private static final Set<String> REVIEW_TOOLS=Set.of("submit_review_plan","submit_review_judgment");
    private static final Set<String> REVIEW_TARGET_READS=Set.of("search_evidence","read_section","read_table_record");
    private static final Set<String> VALIDATION_REJECTIONS=Set.of("QUERY_INVALID","SOURCE_NOT_DISCLOSED","SOURCE_NOT_CURRENT","ROW_OFFSET_INVALID","FIELD_INVALID","FACT_PROTOCOL_INVALID","FACT_OUTSIDE_TARGET",
        "INFERENCE_PROTOCOL_INVALID","INFERENCE_CITATION_UNGROUNDED","INFERENCE_TYPED_FACT_REQUIRED","INFERENCE_PREVIOUSLY_REJECTED","INFERENCE_CURRENT_SCOPE_CONFLICT");
    private static final Set<String> REVIEW_VALIDATION_REJECTIONS=Set.of("PLAN_APPROVAL_REQUIRED","REVIEW_INPUT_INVALID","REQUIREMENT_SOURCE_INCOMPLETE","PLAN_SCHEMA_INVALID","PLAN_SIZE_INVALID","PLAN_SOURCE_INVALID","PLAN_METHOD_INVALID","DETERMINISTIC_REVIEW_REQUIRED","PREDICATE_NOT_SUPPORTED","RULE_NOT_EXECUTABLE","PRIOR_PLAN_REVIEW_MISSING","PLAN_COVERAGE_REJECTED","REVIEW_PLAN_REVISION_MISMATCH","JUDGMENT_SCHEMA_INVALID","SEMANTIC_EVIDENCE_INCOMPLETE","SEMANTIC_SOURCE_INVALID","SEMANTIC_SOURCE_SCOPE_INVALID","TITLE_ONLY_EVIDENCE","CONDITION_EVIDENCE_INVALID","SEMANTIC_REVIEW_REJECTED");
    private static final Set<String> EVENTS=Set.of("investigation_started","model_started","model_request_authorized","model_finished","tool_started","tool_finished","duplicate_tool_avoided","investigation_error","host_recomputed","semantic_path_exhausted","investigation_stalled","context_compacted","context_rebuilt","target_tool_blocked","source_body_replayed","review_plan_approved","review_stage_tool_blocked","review_plan_rejection_reused","review_plan_rejection_remembered","tool_arguments_rejected","no_evidence_progress","evidence_pending","target_navigation_progress","target_proof_progress","target_tool_failed","investigation_failed");
    private static final int MAX_TARGET_DIAGNOSTICS=256;
    private static final Set<String> TARGET_STATES=Set.of("UNVISITED","RUNNING","PROVEN","NO_MORE_EVIDENCE","NEEDS_CONFIRMATION","FAILED");
    private static final Set<String> WORKER_ERRORS=Set.of("MODEL_PERMISSION_TIMEOUT","MODEL_PERMISSION_PROTOCOL_ERROR","MODEL_REQUEST_DENIED","MODEL_ENDPOINT_NOT_ALLOWED","MODEL_CONFIGURATION_MISSING","INVALID_TIMEOUT_CONFIGURATION","PROTOCOL_MESSAGE_TOO_LARGE","PROTOCOL_QUEUE_OVERFLOW","HOST_DISCONNECTED","PROTOCOL_READ_FAILED","PROTOCOL_OBJECT_REQUIRED","PROTOCOL_VERSION_MISMATCH","INVALID_REQUEST_ID","INVALID_CONTEXT","NO_TOOLS_PROVIDED","TOOL_NOT_ALLOWED","DUPLICATE_TOOL","INVALID_TOOL_DESCRIPTION","INVALID_TOOL_SCHEMA","REMOTE_SCHEMA_REFERENCE_NOT_ALLOWED","TOOL_ARGUMENTS_TOO_LARGE","TOOL_ARGUMENTS_OBJECT_REQUIRED","TOOL_ARGUMENT_SCHEMA_MISMATCH","HOST_CANCELLED","TOOL_RESPONSE_ID_MISMATCH","TOOL_RESPONSE_REQUEST_MISMATCH","TOOL_RESULT_OBJECT_REQUIRED","TOOL_RESULT_TOO_LARGE","TOOL_RESPONSE_TIMEOUT","TOOL_OPERATION_FAILED","REPEATED_ACTION_WITHOUT_NEW_EVIDENCE","INVESTIGATION_TIMEOUT","RUNAWAY_LOOP_PROTECTION","REVIEW_PLAN_TOOL_MISSING","MODEL_PROTOCOL_ERROR","MODEL_OR_SDK_ERROR","WORKER_INTERNAL_ERROR","INVALID_JSON_NUMBER","DUPLICATE_JSON_KEY","INVALID_JSON");
    private static final Logger LOG=LoggerFactory.getLogger(SdkAgentInvestigator.class);
    private static final ThreadLocal<AtomicBoolean> CALL_FENCE=new ThreadLocal<>();
    private final boolean configuredEnabled;
    private final String python,worker,baseUrl,apiKey,model;
    private final int timeoutSeconds;
    private final Semaphore slots;
    private final ObjectMapper json;
    @Value("${bidcheck.agent.audit-directory:}") private String auditDirectory="";

    public SdkAgentInvestigator(
            @Value("${bidcheck.agent.enabled:false}") boolean enabled,
            @Value("${bidcheck.agent.python:}") String python,
            @Value("${bidcheck.agent.worker:agent-worker/worker.py}") String worker,
            @Value("${bidcheck.agent.base-url:${bidcheck.semantic.extraction-url:${bidcheck.semantic.url:}}}") String baseUrl,
            @Value("${bidcheck.agent.api-key:${bidcheck.semantic.key:}}") String apiKey,
            @Value("${bidcheck.agent.model:${bidcheck.semantic.extraction-model:${bidcheck.semantic.model:}}}") String model,
            @Value("${bidcheck.agent.run-timeout-seconds:600}") int timeoutSeconds,
            @Value("${bidcheck.agent.max-concurrency:2}") int concurrency){
        this.configuredEnabled=enabled;this.python=trim(python);this.worker=trim(worker);this.baseUrl=trim(baseUrl);this.apiKey=trim(apiKey);this.model=trim(model);
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
        String workerHash="UNAVAILABLE",lockHash="UNAVAILABLE",auditHash="UNAVAILABLE";try{
            Path path=Path.of(worker).toAbsolutePath().normalize();
            if(Files.isRegularFile(path)&&Files.size(path)<=MAX_LINE_BYTES)workerHash=sha(Files.readAllBytes(path));
            Path lock=path.getParent()==null?Path.of("requirements.lock"):path.getParent().resolve("requirements.lock");
            if(Files.isRegularFile(lock)&&Files.size(lock)<=MAX_LINE_BYTES)lockHash=sha(Files.readAllBytes(lock));
            Path audit=path.resolveSibling("model_audit.py");
            if(Files.isRegularFile(audit)&&Files.size(audit)<=MAX_LINE_BYTES)auditHash=sha(Files.readAllBytes(audit));
        }catch(Exception ignored){}
        // The credential and its hash are deliberately excluded from exported identity.
        return POLICY+":"+sha((workerHash+"\n"+lockHash+"\n"+auditHash+"\n"+endpoint+"\n"+model+"\nopenai-agents:tracing-disabled").getBytes(StandardCharsets.UTF_8));
    }

    @Override public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){
        long began=System.nanoTime();String requestId=safeId(start==null?null:start.get("requestId"));
        Process process=null;ExecutorService executor=null;boolean acquired=false;
        HostReceipts receipts=new HostReceipts();AtomicBoolean callActive=new AtomicBoolean(true);
        try{
            if(!configuredEnabled)throw new BridgeFailure("AGENT_DISABLED");
            if(timeoutSeconds<=0||timeoutSeconds>86400)throw new BridgeFailure("INVALID_TIMEOUT_CONFIGURATION");
            String endpoint=normalizedEndpoint(baseUrl);
            if(apiKey.isBlank()||!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))throw new BridgeFailure("MODEL_CONFIGURATION_MISSING");
            Path interpreter=regularPath(python,"PYTHON_EXECUTABLE_MISSING"),script=regularPath(worker,"WORKER_FILE_MISSING");
            if(Files.size(script)>MAX_LINE_BYTES)throw new BridgeFailure("WORKER_FILE_TOO_LARGE");
            if(handler==null)throw new BridgeFailure("TOOL_HANDLER_MISSING");
            Set<String> offered=validateStart(start,requestId);
            receipts.initialize(object(start.get("context"),"INVALID_CONTEXT"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
            if(!slots.tryAcquire(remaining(deadline),TimeUnit.NANOSECONDS))throw new BridgeFailure("AGENT_CONCURRENCY_TIMEOUT");
            acquired=true;
            ProcessBuilder builder=new ProcessBuilder(interpreter.toString(),"-u",script.toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String,String> environment=builder.environment();
            // Only runtime/OS paths are inherited; unrelated provider, database and storage
            // credentials and telemetry/proxy switches never enter this child process.
            Set<String> runtimeNames=Set.of("SYSTEMROOT","WINDIR","SYSTEMDRIVE","COMSPEC","PATH","PATHEXT","TEMP","TMP","TMPDIR","HOME","USERPROFILE","APPDATA","LOCALAPPDATA","LANG","LC_ALL","LD_LIBRARY_PATH");
            environment.keySet().removeIf(k->!runtimeNames.contains(k.toUpperCase(Locale.ROOT)));
            environment.put("PYTHONIOENCODING","utf-8");environment.put("PYTHONUTF8","1");
            environment.put("BIDCHECK_AGENT_API_KEY",apiKey);environment.put("BIDCHECK_AGENT_BASE_URL",endpoint);environment.put("BIDCHECK_AGENT_MODEL",model);
            environment.put("BIDCHECK_AGENT_RUN_TIMEOUT_SECONDS",Integer.toString(timeoutSeconds));
            if(!trim(auditDirectory).isBlank())environment.put("BIDCHECK_AGENT_AUDIT_DIRECTORY",Path.of(auditDirectory).toAbsolutePath().normalize().toString());
            // Java owns the absolute deadline and invalidates host callbacks first. Avoid
            // the worker's shorter default expiring while a host mutation is still active.
            environment.put("BIDCHECK_AGENT_TOOL_TIMEOUT_SECONDS",Integer.toString(timeoutSeconds));
            environment.put("OPENAI_AGENTS_DISABLE_TRACING","1");environment.put("OPENAI_AGENTS_DONT_LOG_MODEL_DATA","1");environment.put("OPENAI_AGENTS_DONT_LOG_TOOL_DATA","1");
            process=builder.start();
            executor=Executors.newCachedThreadPool(r->{Thread thread=new Thread(r,"bidcheck-agent-protocol");thread.setDaemon(true);return thread;});
            BlockingQueue<Object> messages=new ArrayBlockingQueue<>(16);Process running=process;
            executor.submit(()->readMessages(running.getInputStream(),messages));
            BufferedOutputStream output=new BufferedOutputStream(process.getOutputStream());
            // Pipe writes can block too when a crashed or malicious child stops reading.
            invoke(executor,()->{send(output,start);return null;},deadline,"PROTOCOL_WRITE_FAILED",callActive);
            Set<String> callIds=new HashSet<>();long actualToolCalls=0,toolAuditIndex=0;ToolPayloadAudit toolAudit=new ToolPayloadAudit(auditDirectory,apiKey);
            while(true){
                Object message=messages.poll(remaining(deadline),TimeUnit.NANOSECONDS);
                if(message==null)throw new BridgeFailure("INVESTIGATION_TIMEOUT");
                if(message instanceof BridgeFailure problem)throw problem;
                Map<String,Object> frame=object(message,"PROTOCOL_OBJECT_REQUIRED");
                if(!PROTOCOL.equals(frame.get("protocol")))throw new BridgeFailure("PROTOCOL_VERSION_MISMATCH");
                if(!requestId.equals(frame.get("requestId")))throw new BridgeFailure("PROTOCOL_REQUEST_ID_MISMATCH");
                String type=Objects.toString(frame.get("type"),"");
                if(type.equals("event")){
                    Map<String,Object> safe=safeEvent(frame,requestId,receipts);
                    boolean permit="model_started".equals(frame.get("event"));String permitId=permit?safeId(frame.get("id")):"";
                    if(permit&&(permitId.isBlank()||!callIds.add("permit:"+permitId)))throw new BridgeFailure("MODEL_PERMISSION_PROTOCOL_ERROR");
                    if(permit&&event==null)throw new BridgeFailure("MODEL_REQUEST_DENIED");
                    // SDK waits for this acknowledgement before issuing the provider HTTP
                    // request. The upper layer acquires/validates its lease in the callback.
                    if(event!=null)invoke(executor,()->{event.accept(safe);return null;},deadline,permit?"MODEL_REQUEST_DENIED":"EVENT_CALLBACK_FAILED",callActive);
                    if(permit)invoke(executor,()->{send(output,map("type","event_ack","protocol",PROTOCOL,"requestId",requestId,"id",permitId,"allowed",true));return null;},deadline,"PROTOCOL_WRITE_FAILED",callActive);
                }else if(type.equals("tool_call")){
                    String name=Objects.toString(frame.get("name"),""),callId=safeId(frame.get("id"));
                    if(callId.isBlank()||!callIds.add(callId))throw new BridgeFailure("TOOL_CALL_ID_INVALID");
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
                        toolAuditEvent(toolAudit,start,event,requestId,auditIndex,callId,name,"error",map("errorCode",failed instanceof BridgeFailure known?known.code:failed instanceof InterruptedException?"HOST_CANCELLED":"TOOL_BOUNDARY_FAILED","errorType",failed.getClass().getSimpleName()));
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
            if(process!=null)terminate(process);
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

    /** Audit failures are visible but cannot turn successful business work into a failed tool. */
    private void toolAuditEvent(ToolPayloadAudit audit,Map<String,Object> start,Consumer<Map<String,Object>> event,String requestId,long index,String callId,String tool,String direction,Object payload){
        if(!audit.enabled())return;
        Map<String,Object> stored=audit.record(start,requestId,index,callId,tool,direction,payload);
        Map<?,?> context=start!=null&&start.get("auditContext") instanceof Map<?,?> c?c:Map.of();
        String runId=Objects.toString(context.get("runId"),""),checkpointId=Objects.toString(context.get("checkpointId"),"");
        if(!runId.matches("[a-fA-F0-9-]{36}"))runId="";if(!checkpointId.matches("[a-fA-F0-9-]{36}"))checkpointId="";
        LOG.info("bidcheck.agent_tool_audit runId={} checkpointId={} requestId={} toolIndex={} callId={} tool={} direction={} status={} file={} bytes={} sha256={}",runId,checkpointId,requestId,index,callId,tool,direction,stored.get("status"),stored.getOrDefault("file",""),stored.getOrDefault("bytes",0),stored.getOrDefault("sha256",""));
        if(event!=null)try{event.accept(map("type","event","protocol",PROTOCOL,"requestId",requestId,"event","tool_payload_audit","toolIndex",index,"callId",callId,"tool",tool,"direction",direction,"toolAudit",stored));}
        catch(RuntimeException failure){LOG.warn("bidcheck.agent_tool_audit_event requestId={} toolIndex={} direction={} status=EVENT_PUBLISH_FAILED errorType={}",requestId,index,direction,failure.getClass().getSimpleName());}
    }

    private Set<String> validateStart(Map<String,Object> start,String requestId)throws BridgeFailure,IOException{
        if(start==null||!"start".equals(start.get("type"))||!PROTOCOL.equals(start.get("protocol")))throw new BridgeFailure("PROTOCOL_VERSION_MISMATCH");
        if(requestId.isBlank())throw new BridgeFailure("INVALID_REQUEST_ID");
        Map<String,Object> context=object(start.get("context"),"INVALID_CONTEXT");
        boolean review="AGENT_LED_REVIEW_V1".equals(context.get("workflowPolicy"));
        if("TARGET_FACTS_V1".equals(context.get("workflowPolicy"))||review){
            Object rawTargets=context.getOrDefault("targetObligationIds",List.of());
            if(!(rawTargets instanceof List<?> targets)||(!review&&targets.isEmpty())||targets.stream().anyMatch(t->safeId(t).isBlank()))throw new BridgeFailure("TARGET_CONTRACT_MISSING");
        }
        if(!(start.get("tools") instanceof List<?> definitions)||definitions.isEmpty())throw new BridgeFailure("NO_TOOLS_PROVIDED");
        Set<String> offered=new LinkedHashSet<>();
        for(Object value:definitions){Map<String,Object> tool=object(value,"INVALID_TOOL_SCHEMA");String name=Objects.toString(tool.get("name"),"");if(!toolAllowed(name,review))throw new BridgeFailure("TOOL_NOT_ALLOWED");if(!offered.add(name))throw new BridgeFailure("DUPLICATE_TOOL");object(tool.get("parameters"),"INVALID_TOOL_SCHEMA");}
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
        Map<String,Object> safe=map("type","event","protocol",PROTOCOL,"requestId",requestId,"event",name);
        String observedEvent=safeEventId(frame.get("eventId"),requestId);if(!observedEvent.isBlank()&&receipts.observedEventIds.size()<4096)receipts.observedEventIds.add(observedEvent);
        if(!observedEvent.isBlank()&&Set.of("target_tool_failed","investigation_failed","tool_arguments_rejected","investigation_error").contains(name)&&receipts.observedFailureEventIds.size()<4096)receipts.observedFailureEventIds.add(observedEvent);
        for(String key:List.of("eventId","firstFailureEventId")){String id=key.equals("eventId")?safeEventId(frame.get(key),requestId):receipts.failureEventId(frame.get(key),requestId);if(!id.isBlank())safe.put(key,id);}
        if(name.equals("model_started"))safe.put("id",safeId(frame.get("id")));
        if(frame.get("modelAudit") instanceof Map<?,?> audit){
            String status=Objects.toString(audit.get("status"),"");
            if(Set.of("DISABLED","SAVED","PAYLOAD_OMITTED","WRITE_FAILED","INVALID_METADATA").contains(status)){
                Map<String,Object> metadata=map("status",status);
                String file=Objects.toString(audit.get("file"),""),hash=Objects.toString(audit.get("sha256"),"");
                if(file.matches("[a-f0-9]{32}-[0-9]{5,10}-(input|output|error)\\.json")&&hash.matches("[a-f0-9]{64}")){
                    metadata.put("file",file);metadata.put("sha256",hash);metadata.put("bytes",nonnegative(audit.get("bytes")));
                }
                safe.put("modelAudit",metadata);
            }
        }
        for(String key:List.of("elapsedMs","modelIndex","toolIndex","durationMs","acceptedFactCount","duplicateCalls","inputChars","removedChars","retainedToolBatches","retainedFunctionCalls","receiptInputChars","receiptOutputChars","receiptRemovedChars","disclosedSourceCount","sourceCount","targetCount","attemptForTargetGap","maxAttemptsForTargetGap","inputTokens","outputTokens","newSourceCount","acceptedFactDelta","closedTargetDelta","noEvidenceProgressRounds"))if(frame.containsKey(key))safe.put(key,nonnegative(frame.get(key)));
        for(String key:List.of("businessCheckCompleted","targetCheckCompleted","toolFailed","correctable"))if(frame.get(key) instanceof Boolean)safe.put(key,frame.get(key));
        if(safe.containsKey("businessCheckCompleted"))safe.put("businessCheckCompleted",receipts.completed);
        if(safe.containsKey("targetCheckCompleted"))safe.put("targetCheckCompleted",receipts.targetCompleted);
        if(safe.containsKey("acceptedFactCount"))safe.put("acceptedFactCount",receipts.accepted.size());
        for(String key:List.of("path","evidenceRevision","planRevision"))if(!safeId(frame.get(key)).isBlank())safe.put(key,safeId(frame.get(key)));
        if(receipts.targets.contains(safeId(frame.get("targetObligationId"))))safe.put("targetObligationId",safeId(frame.get("targetObligationId")));
        for(String key:List.of("targetId","targetObligationId")){String target=safeId(frame.get(key));if(receipts.diagnosticTarget(target))safe.put(key,target);}
        if(Set.of("BLOCKED","COMPLETED","NO_MORE_EVIDENCE").contains(Objects.toString(frame.get("targetState"),"")))safe.put("targetState",frame.get("targetState"));
        if(TARGET_STATES.contains(Objects.toString(frame.get("stateAfter"),""))){String target=safeId(frame.getOrDefault("targetObligationId",frame.get("targetId")));safe.put("stateAfter","PROVEN".equals(frame.get("stateAfter"))&&!receipts.closedTargets.contains(target)?"RUNNING":frame.get("stateAfter"));}
        for(String key:List.of("operationKey","progressScope")){String value=Objects.toString(frame.get(key),"");if(value.matches("[a-f0-9]{64}"))safe.put(key,value);}
        for(String key:List.of("ruleVersion","relevantEvidenceRevision")){String value=safeId(frame.get(key));if(!value.isBlank())safe.put(key,value);}
        if(frame.get("newTableRecord") instanceof Boolean)safe.put("newTableRecord",frame.get("newTableRecord"));
        for(String key:List.of("rejectionCode","stage")){String value=Objects.toString(frame.get(key),"");if(value.matches("[A-Z][A-Z0-9_]{0,95}"))safe.put(key,value);}
        if(Boolean.FALSE.equals(frame.get("hostStateReplayed")))safe.put("hostStateReplayed",false);
        if(frame.get("failureFamily") instanceof String family&&family.matches("[A-Z][A-Z0-9_]{0,63}"))safe.put("failureFamily",family);
        if(frame.get("validatorKeyword") instanceof String keyword&&keyword.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))safe.put("validatorKeyword",keyword);
        if(frame.get("fieldPath") instanceof String path&&path.matches("/[A-Za-z0-9_~./@\\[\\]-]{0,255}"))safe.put("fieldPath",path);
        if(frame.get("reason") instanceof String reason&&reason.matches("[A-Z][A-Z0-9_]{0,95}"))safe.put("reason",reason);
        if(frame.get("remainingRepairActions") instanceof Collection<?> actions)safe.put("remainingRepairActions",actions.stream().filter(String.class::isInstance).map(String.class::cast).filter(action->action.matches("[A-Z][A-Z0-9_]{0,63}")).distinct().limit(12).toList());
        for(String key:List.of("affectedTargetObligationIds","blockedTargetObligationIds","remainingTargetObligationIds","exhaustedTargetObligationIds","businessUnclosedTargetObligationIds","pendingConfirmationTargetObligationIds"))if(frame.get(key) instanceof Collection<?> values)safe.put(key,knownTargets(values,receipts));
        if(Set.of("target_navigation_progress","target_proof_progress","target_tool_failed","investigation_failed","no_evidence_progress","evidence_pending").contains(name)){safe.put("diagnosticOnly",true);safe.put("businessProof",false);}
        if(frame.containsKey("tool")){String tool=Objects.toString(frame.get("tool"),"");if(!toolAllowed(tool,receipts.review))throw new BridgeFailure("TOOL_NOT_ALLOWED");safe.put("tool",tool);}
        if(name.equals("investigation_error"))safe.put("errorCategory",switch(Objects.toString(frame.get("errorType"),"")){
            case "AuthenticationError","PermissionDeniedError"->"PROVIDER_AUTHENTICATION";
            case "RateLimitError"->"PROVIDER_RATE_LIMIT";
            case "APITimeoutError","TimeoutError"->"PROVIDER_TIMEOUT";
            case "APIConnectionError","ConnectError"->"PROVIDER_CONNECTION";
            case "BadRequestError","UnprocessableEntityError"->"PROVIDER_REQUEST_FORMAT";
            default->"MODEL_OR_SDK_ERROR";
        });
        if(receipts.firstFailureEventId.isBlank()&&Set.of("target_tool_failed","investigation_failed","tool_arguments_rejected","investigation_error").contains(name)){
            String failure=receipts.failureEventId(frame.get("firstFailureEventId"),requestId);
            if(failure.isBlank())failure=observedEvent;
            if(!failure.isBlank())receipts.firstFailureEventId=failure;
        }
        // Arbitrary exception names and model/event metadata are deliberately omitted.
        return Collections.unmodifiableMap(safe);
    }
    private Map<String,Object> safeResult(Map<String,Object> frame,HostReceipts receipts,long toolCalls,String requestId,long began)throws BridgeFailure{
        String status=Objects.toString(frame.get("status"),"");if(!Set.of("COMPLETED","EVIDENCE_UPDATED","NO_PROGRESS","FAILED").contains(status))throw new BridgeFailure("WORKER_RESULT_STATUS_INVALID");
        if(!Boolean.FALSE.equals(frame.get("tracingExportEnabled")))throw new BridgeFailure("WORKER_TRACING_POLICY_VIOLATION");
        if(status.equals("COMPLETED")&&!receipts.targetCompleted&&!receipts.completed||status.equals("EVIDENCE_UPDATED")&&receipts.accepted.isEmpty())throw new BridgeFailure("WORKER_RESULT_STATE_MISMATCH");
        String code=Objects.toString(frame.get("code"),"");if(!code.isBlank()&&!WORKER_ERRORS.contains(code)&&!Set.of("MODEL_PERMISSION_TIMEOUT","MODEL_PERMISSION_PROTOCOL_ERROR","MODEL_REQUEST_DENIED","SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE","EVIDENCE_PENDING","AGENT_STALLED_WITH_REMAINING_TARGETS","TARGET_CONTRACT_MISSING","TARGET_WORK_RECEIPT_MISSING","TARGET_WORK_RECEIPT_INVALID","INVALID_TARGET_RECEIPT","CANDIDATE_TARGET_REQUIRED","REVIEW_PLAN_REQUIRED","REVIEW_PLAN_RECEIPT_INVALID","REVIEW_PLAN_REVISION_MISMATCH","TARGET_STATE_UNCHANGED","HOST_TARGET_RECEIPT_INVALID","WAIT_CONFIRMATION","HOST_CONFIRMATION_RECEIPT_INVALID").contains(code))code="WORKER_REPORTED_FAILURE";
        if(status.equals("FAILED")&&code.isBlank())code="WORKER_REPORTED_FAILURE";
        // A worker's historical tool error cannot override the host's current
        // operation receipts. Transport/model failures remain fail-closed.
        if(!status.equals("FAILED")||code.equals("TOOL_OPERATION_FAILED")){
            status=receipts.targetCompleted||receipts.completed?"COMPLETED":!receipts.unresolved.isEmpty()?"FAILED":receipts.accepted.isEmpty()?"NO_PROGRESS":"EVIDENCE_UPDATED";
            if(status.equals("FAILED"))code="TOOL_OPERATION_FAILED";
            else if(code.equals("TOOL_OPERATION_FAILED"))code="";
        }
        Map<String,Object> safe=map("status",status,"code",code,"errorCode",status.equals("FAILED")?code:"","requestId",requestId,"protocol",PROTOCOL,"policy",POLICY,
            "acceptedFactIds",List.copyOf(receipts.accepted),"businessCheckCompleted",receipts.completed,"targetCheckCompleted",receipts.targetCompleted,
            "failedToolCount",receipts.unresolved.size(),"recoveredToolCount",receipts.recovered,"validationRejectionCount",receipts.rejections,
            "toolCalls",toolCalls,"elapsedMs",elapsed(began),"sdk","openai-agents","tracingExportEnabled",false);
        for(String key:List.of("modelCalls","modelPermissionRequests","duplicateCallsAvoided","inputTokens","outputTokens","contextCompactions","contextRebuilds","contextRetainedToolBatches","contextReceiptInputChars","contextReceiptOutputChars","contextReceiptRemovedChars","contextInputChars","contextRemovedChars","targetGateBlocks","correctableToolRejectionCount","maxNoEvidenceProgressRounds"))safe.put(key,nonnegative(frame.getOrDefault(key,0)));
        safe.put("workflowPolicy",receipts.workflowPolicy);safe.put("approvedPlanRevision",receipts.planRevision);
        for(String key:List.of("blockedTargetObligationIds","remainingTargetObligationIds","exhaustedTargetObligationIds","pendingConfirmationTargetObligationIds"))if(frame.get(key) instanceof Collection<?> values)safe.put(key,knownTargets(values,receipts).stream().filter(id->!receipts.closedTargets.contains(id)).toList());
        safe.put("businessUnclosedTargetObligationIds",receipts.targets.stream().filter(id->!receipts.closedTargets.contains(id)).toList());
        String firstFailure=receipts.firstFailureEventId.isBlank()?receipts.failureEventId(frame.get("firstFailureEventId"),requestId):receipts.firstFailureEventId;if(!firstFailure.isBlank())safe.put("firstFailureEventId",firstFailure);
        if(frame.containsKey("targetProgressLedger")){
            safe.put("targetProgressLedger",safeLedger(frame.get("targetProgressLedger"),receipts,requestId));
            safe.put("targetProgressLedgerTruncated",frame.get("targetProgressLedger") instanceof Collection<?> rows&&rows.size()>MAX_TARGET_DIAGNOSTICS);
        }
        safe.put("completedTargetObligationIds",List.copyOf(receipts.closedTargets));safe.put("automaticRecomputations",receipts.automaticRecomputations);
        safe.put("repeatedRejectionCount",nonnegative(frame.getOrDefault("repeatedRejectionCount",0)));
        if("AGENT_STALLED_WITH_REMAINING_TARGETS".equals(code)){safe.put("investigationIncomplete",true);safe.put("stopScope","CURRENT_INVESTIGATION_ONLY");safe.put("resumeRequiresChangedTargetOrEvidence",true);}
        String version=Objects.toString(frame.get("sdkVersion"),"");if(version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[A-Za-z0-9.-]{0,24})?"))safe.put("sdkVersion",version);
        return Collections.unmodifiableMap(safe);
    }
    private Map<String,Object> failure(String code,String requestId,HostReceipts receipts,long began){
        LOG.info("bidcheck.agent_bridge requestId={} policy={} status=FAILED code={} elapsedMs={}",requestId,POLICY,code,elapsed(began));
        return map("status","FAILED","code",code,"errorCode",code,"requestId",requestId,"protocol",PROTOCOL,"policy",POLICY,"acceptedFactIds",List.copyOf(receipts.accepted),"businessCheckCompleted",false,"targetCheckCompleted",false,
            "firstFailureEventId",receipts.firstFailureEventId,"completedTargetObligationIds",List.copyOf(receipts.closedTargets),"businessUnclosedTargetObligationIds",receipts.targets.stream().filter(id->!receipts.closedTargets.contains(id)).toList(),
            "failedToolCount",receipts.unresolved.size(),"recoveredToolCount",receipts.recovered,"validationRejectionCount",receipts.rejections,"elapsedMs",elapsed(began),"tracingExportEnabled",false);
    }
    private static <T>T invoke(ExecutorService executor,Callable<T> action,long deadline,String code,AtomicBoolean active)throws InterruptedException,BridgeFailure{
        Future<T> future=executor.submit(()->{CALL_FENCE.set(active);try{if(!currentCallActive())throw new InterruptedException();return action.call();}finally{CALL_FENCE.remove();}});
        try{return future.get(remaining(deadline),TimeUnit.NANOSECONDS);}
        catch(TimeoutException timeout){active.set(false);future.cancel(true);throw new BridgeFailure("INVESTIGATION_TIMEOUT");}
        catch(InterruptedException cancelled){active.set(false);future.cancel(true);throw cancelled;}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof InterruptedException){Thread.currentThread().interrupt();throw new InterruptedException();}if(cause instanceof BridgeFailure safe)throw safe;throw new BridgeFailure(code);}
        finally{if(!future.isDone())future.cancel(true);}
    }
    private static void terminate(Process process){
        try{process.descendants().forEach(child->{try{child.destroyForcibly();}catch(RuntimeException ignored){}});}catch(RuntimeException ignored){}
        try{process.destroyForcibly();}catch(RuntimeException ignored){}
        try{process.getOutputStream().close();}catch(IOException ignored){}try{process.getInputStream().close();}catch(IOException ignored){}
    }
    static String normalizedEndpoint(String raw)throws BridgeFailure{
        try{
            if(raw==null||raw.isBlank()||raw.contains("\\"))throw new BridgeFailure("MODEL_ENDPOINT_NOT_ALLOWED");
            URI uri=URI.create(raw.strip());String path=Objects.toString(uri.getPath(),"").replaceFirst("/+$","");if(path.endsWith("/chat/completions"))path=path.substring(0,path.length()-"/chat/completions".length());
            if(!"https".equalsIgnoreCase(uri.getScheme())||!HOSTS.contains(Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT))||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||!path.equals("/compatible-mode/v1")||uri.getRawPath().contains("%"))throw new BridgeFailure("MODEL_ENDPOINT_NOT_ALLOWED");
            return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+"/compatible-mode/v1";
        }catch(IllegalArgumentException invalid){throw new BridgeFailure("MODEL_ENDPOINT_NOT_ALLOWED");}
    }
    private static Path regularPath(String raw,String code)throws BridgeFailure{try{Path path=Path.of(raw).toAbsolutePath().normalize();if(raw.isBlank()||!Files.isRegularFile(path))throw new BridgeFailure(code);return path;}catch(InvalidPathException invalid){throw new BridgeFailure(code);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value,String code)throws BridgeFailure{if(!(value instanceof Map<?,?> map)||map.keySet().stream().anyMatch(k->!(k instanceof String)))throw new BridgeFailure(code);return (Map<String,Object>)map;}
    private static long nonnegative(Object value)throws BridgeFailure{if(!(value instanceof Number number))throw new BridgeFailure("PROTOCOL_COUNTER_INVALID");long integer=number.longValue();if(integer<0||integer>1_000_000_000_000L||number.doubleValue()!=integer)throw new BridgeFailure("PROTOCOL_COUNTER_INVALID");return integer;}
    private static long remaining(long deadline)throws BridgeFailure{long remaining=deadline-System.nanoTime();if(remaining<=0)throw new BridgeFailure("INVESTIGATION_TIMEOUT");return remaining;}
    private static long elapsed(long began){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);}
    private static String trim(String value){return Objects.toString(value,"").strip();}
    private static boolean toolAllowed(String name,boolean review){return TOOLS.contains(name)||review&&REVIEW_TOOLS.contains(name);}
    private static String safeId(Object value){return value instanceof String id&&id.matches("[A-Za-z0-9_.:-]{1,160}")?id:"";}
    private static String safeEventId(Object value,String requestId){return value instanceof String id&&id.length()<=200&&id.matches(java.util.regex.Pattern.quote(requestId)+":[1-9][0-9]{0,9}")?id:"";}
    private static List<String> knownTargets(Collection<?> values,HostReceipts receipts){return values.stream().map(SdkAgentInvestigator::safeId).filter(receipts.targets::contains).distinct().limit(MAX_TARGET_DIAGNOSTICS).toList();}
    private List<Map<String,Object>> safeLedger(Object raw,HostReceipts receipts,String requestId)throws BridgeFailure{
        if(!(raw instanceof Collection<?> rows))return List.of();
        List<Map<String,Object>> safe=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object value:rows){
            if(safe.size()>=MAX_TARGET_DIAGNOSTICS)break;
            if(!(value instanceof Map<?,?> row))continue;
            String target=safeId(row.get("targetObligationId")),scope=Objects.toString(row.get("progressScope"),"");
            String state=Objects.toString(row.get("state"),""),ruleVersion=safeId(row.get("ruleVersion")),revision=safeId(row.get("relevantEvidenceRevision"));
            boolean discovery=target.equals("DOCUMENT_DISCOVERY");
            if(!receipts.diagnosticTarget(target)||!scope.matches("[a-f0-9]{64}")||!TARGET_STATES.contains(state)||seen.contains(target))continue;
            if(!receipts.ruleVersion().equals(ruleVersion)||(!discovery&&!revision.equals(receipts.targetRevisions.get(target)))||(discovery&&!revision.matches("[a-f0-9]{64}")))continue;
            seen.add(target);
            // Worker diagnostics never certify a completed target. Host recomputation owns that status.
            String current=receipts.closedTargets.contains(target)?"PROVEN":state.equals("PROVEN")?"RUNNING":state;
            Map<String,Object> entry=map("targetObligationId",target,"progressScope",scope,"ruleVersion",ruleVersion,
                "relevantEvidenceRevision",revision,"state",current,"diagnosticOnly",true,"businessProof",false);
            if(!state.equals(current))entry.put("workerState",state);
            if(row.get("visited") instanceof Boolean)entry.put("visited",row.get("visited"));
            for(String key:List.of("noEvidenceProgressRounds","maxNoEvidenceProgressRounds","acceptedFactCount","closedTargetCount","navigationCount"))if(row.containsKey(key))entry.put(key,nonnegative(row.get(key)));
            if(!receipts.closedTargets.contains(target))entry.put("closedTargetCount",0L);
            String failure=receipts.failureEventId(row.get("firstFailureEventId"),requestId);if(!failure.isBlank())entry.put("firstFailureEventId",failure);
            String operation=Objects.toString(row.get("lastOperationKey"),"");if(operation.matches("[a-f0-9]{64}"))entry.put("lastOperationKey",operation);
            String code=Objects.toString(row.get("rejectionCode"),"");if(code.matches("[A-Z][A-Z0-9_]{0,95}"))entry.put("rejectionCode",code);
            if("RELEVANT_RULE_OR_EVIDENCE_CHANGED".equals(row.get("resumeTrigger"))&&!receipts.closedTargets.contains(target))entry.put("resumeTrigger",row.get("resumeTrigger"));
            safe.add(entry);
        }
        return List.copyOf(safe);
    }
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException("SHA256_UNAVAILABLE");}}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(values[i].toString(),values[i+1]);return out;}
    static final class BridgeFailure extends Exception{final String code;BridgeFailure(String code){super(code);this.code=code;}}
    private final class HostReceipts{
        final Set<String> accepted=new LinkedHashSet<>(),unresolved=new LinkedHashSet<>(),targets=new LinkedHashSet<>(),closedTargets=new LinkedHashSet<>();
        final Set<String> historicalFailureIds=new HashSet<>(),observedEventIds=new HashSet<>(),observedFailureEventIds=new HashSet<>();
        final Map<String,String> targetRevisions=new LinkedHashMap<>(),blockedTargets=new LinkedHashMap<>();
        boolean completed,targetCompleted,typed,review;int recovered,rejections,automaticRecomputations;String evidenceRevision="",planRevision="",serverPlanRevision="",serverPlanFingerprint="",firstFailureEventId="",workflowPolicy="LEGACY_EVIDENCE";
        void initialize(Map<String,Object> context)throws BridgeFailure{
            String policy=Objects.toString(context.get("workflowPolicy"),"");review="AGENT_LED_REVIEW_V1".equals(policy);typed=review||"TARGET_FACTS_V1".equals(policy);if(typed)workflowPolicy=policy;
            if(context.get("targetObligationIds") instanceof Collection<?> values)for(Object value:values)if(!safeId(value).isBlank())targets.add(safeId(value));
            evidenceRevision=safeId(context.get("targetEvidenceRevision"));serverPlanRevision=safeId(context.get("serverPlanRevision"));serverPlanFingerprint=safeId(context.get("serverPlanFingerprint"));
            if(context.get("targetProgressLedger") instanceof Collection<?> rows)for(Object raw:rows)if(raw instanceof Map<?,?> row&&targets.contains(safeId(row.get("targetObligationId")))){
                String id=Objects.toString(row.get("firstFailureEventId"),"");if(id.matches("[A-Za-z0-9_.:-]{1,160}:[1-9][0-9]{0,9}")&&historicalFailureIds.size()<MAX_TARGET_DIAGNOSTICS)historicalFailureIds.add(id);
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
        String ruleVersion(){return review?planRevision:serverPlanRevision.isBlank()?"SESSION_FROZEN":serverPlanRevision;}
        String failureEventId(Object value,String requestId){String id=safeEventId(value,requestId);return !id.isBlank()&&observedFailureEventIds.contains(id)?id:value instanceof String old&&historicalFailureIds.contains(old)?old:"";}
        void validateCall(String name,Map<String,Object> arguments)throws BridgeFailure{
            if(review&&(Set.of("submit_candidate_fact","submit_review_judgment","recompute_check").contains(name)||REVIEW_TARGET_READS.contains(name))){
                if(planRevision.isBlank())throw new BridgeFailure("REVIEW_PLAN_REQUIRED");
                if(name.equals("submit_review_judgment")&&!planRevision.equals(arguments.get("planRevision")))throw new BridgeFailure("REVIEW_PLAN_REVISION_MISMATCH");
            }
            if(typed&&arguments.containsKey("targetObligationId")&&!targets.contains(safeId(arguments.get("targetObligationId"))))throw new BridgeFailure("CANDIDATE_TARGET_REQUIRED");
            if(typed&&(Set.of("submit_candidate_fact","submit_review_judgment").contains(name)||review&&REVIEW_TARGET_READS.contains(name)||REVIEW_TARGET_READS.contains(name)&&arguments.containsKey("targetObligationId"))){
                String target=safeId(arguments.get("targetObligationId"));if(!targets.contains(target))throw new BridgeFailure("CANDIDATE_TARGET_REQUIRED");
                if(closedTargets.contains(target)||blockedTargets.containsKey(target))throw new BridgeFailure("TARGET_STATE_UNCHANGED");
            }
        }
        void refreshTargets(Map<?,?> result){
            if(!typed||!(result.get("targetEvidenceRevisions") instanceof Map<?,?> revisions))return;
            for(String target:targets){String revision=safeId(revisions.get(target));if(revision.isBlank())continue;String previous=targetRevisions.put(target,revision);
                if(previous!=null&&!previous.equals(revision)){closedTargets.remove(target);blockedTargets.remove(target);completed=false;targetCompleted=false;}
            }
        }
        void observeWork(Map<?,?> result){
            if(!typed||!(result.get("semanticWorkItems") instanceof Collection<?> items))return;
            for(Object raw:items)if(raw instanceof Map<?,?> item){String target=safeId(item.get("targetObligationId")),revision=safeId(item.get("evidenceRevision"));
                if(!targets.contains(target)||revision.isBlank())continue;
                targetRevisions.putIfAbsent(target,revision);
                if(Boolean.TRUE.equals(item.get("terminalForRevision"))&&Boolean.TRUE.equals(item.get("targetExhaustedForRevision"))&&revision.equals(targetRevisions.get(target)))blockedTargets.put(target,revision);
            }
        }
        void acceptPlan(Map<String,Object> result)throws BridgeFailure{
            if(!(result.get("planReceipt") instanceof Map<?,?> receipt)||!Boolean.TRUE.equals(receipt.get("approved")))return;
            String revision=safeId(receipt.get("planRevision"));Object raw=receipt.containsKey("targetObligationIds")?receipt.get("targetObligationIds"):result.get("targetObligationIds");
            if(revision.isBlank()||!(raw instanceof List<?> values)||values.isEmpty()||values.stream().anyMatch(v->safeId(v).isBlank())||new HashSet<>(values).size()!=values.size())throw new BridgeFailure("REVIEW_PLAN_RECEIPT_INVALID");
            if(revision.equals(planRevision)&&!targets.equals(new LinkedHashSet<>(values)))throw new BridgeFailure("REVIEW_PLAN_RECEIPT_INVALID");
            if(!revision.equals(planRevision)){completed=false;targetCompleted=false;closedTargets.clear();blockedTargets.clear();targetRevisions.clear();}
            planRevision=revision;targets.clear();values.forEach(v->targets.add(safeId(v)));
        }
        boolean currentPlan(Map<?,?> receipt){return !review||!planRevision.isBlank()&&planRevision.equals(receipt.get("planRevision"));}
        void accept(String name,Map<String,Object> arguments,Map<String,Object> result)throws IOException,BridgeFailure{
            String operation=name+":"+sha(json.writeValueAsBytes(name.equals("recompute_check")?Map.of():normalizeOperation(arguments,"")));
            String status=Objects.toString(result.get("status"),"");Object error=result.get("error");
            boolean hasError=error!=null&&!error.equals("")&&!error.equals(Map.of())&&!error.equals(List.of());
            boolean rejected=error instanceof String value&&VALIDATION_REJECTIONS.contains(value)||status.equals("REJECTED")&&(!hasError||review&&error instanceof String code&&REVIEW_VALIDATION_REJECTIONS.contains(code));
            boolean bad=!rejected&&(hasError||Set.of("FAILED","ERROR","UNAVAILABLE").contains(status));
            if(review&&name.equals("submit_review_plan")&&!bad&&!rejected)acceptPlan(result);
            if(!bad)refreshTargets(result);
            String revision=safeId(result.get("targetEvidenceRevision"));if(!bad&&!revision.isBlank()&&!revision.equals(evidenceRevision)){evidenceRevision=revision;completed=false;if(!typed){targetCompleted=false;closedTargets.clear();}}
            if(bad)unresolved.add(operation);
            else if(rejected)rejections++;
            else if((status.isBlank()||Set.of("COMPLETED","NO_RECORD").contains(status))&&unresolved.remove(operation))recovered++;
            if(Set.of("search_evidence","read_section","read_table_record","submit_candidate_fact").contains(name)&&!bad&&!Set.of("FAILED","ERROR","UNAVAILABLE").contains(status)&&result.get("acceptedFactIds") instanceof List<?> ids){int before=accepted.size();for(Object id:ids)if(id instanceof String value&&value.length()<=256&&!value.isBlank())accepted.add(value);if(before!=accepted.size()){completed=false;if(!typed){targetCompleted=false;closedTargets.clear();}}}
            if(name.equals("recompute_check")){
                completed=!bad&&!rejected&&currentPlan(result)&&Boolean.TRUE.equals(result.get("businessCheckCompleted"));
                targetCompleted=!bad&&!rejected&&currentPlan(result)&&(completed||Boolean.TRUE.equals(result.get("targetCheckCompleted")));
                if(!bad&&!rejected&&currentPlan(result))observeTargets(result);
            }
            if((Set.of("search_evidence","read_section","read_table_record","submit_candidate_fact").contains(name)||review&&name.equals("submit_review_judgment"))&&!bad&&result.get("computationReceipt") instanceof Map<?,?> receipt
                    &&"HOST_RULE_RECOMPUTE".equals(receipt.get("kind"))&&"COMPLETED".equals(receipt.get("status"))&&!revision.isBlank()&&revision.equals(receipt.get("targetEvidenceRevision"))
                    &&receipt.get("businessCheckCompleted") instanceof Boolean&&receipt.get("targetCheckCompleted") instanceof Boolean&&currentPlan(receipt)){
                completed=Boolean.TRUE.equals(receipt.get("businessCheckCompleted"));targetCompleted=completed||Boolean.TRUE.equals(receipt.get("targetCheckCompleted"));automaticRecomputations++;observeTargets(receipt);
            }
            if(!bad)observeWork(result);
        }
        void observeTargets(Map<?,?> receipt)throws BridgeFailure{
            if(typed&&(receipt.containsKey("targetReceiptAuthority")||receipt.containsKey("completedTargetObligationIds")||receipt.containsKey("remainingTargetObligationIds"))){
                Object rawDone=receipt.get("completedTargetObligationIds"),rawRemaining=receipt.get("remainingTargetObligationIds");
                if(!(rawDone instanceof List<?> done)||!(rawRemaining instanceof List<?> remaining))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
                Set<String> doneIds=new LinkedHashSet<>(),remainingIds=new LinkedHashSet<>();
                for(Object id:done){String value=safeId(id);if(!targets.contains(value)||!doneIds.add(value))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");}
                for(Object id:remaining){String value=safeId(id);if(!targets.contains(value)||!remainingIds.add(value)||doneIds.contains(value))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");}
                Set<String> partition=new HashSet<>(doneIds);partition.addAll(remainingIds);
                boolean plan=review?currentPlan(receipt):!serverPlanRevision.isBlank()&&serverPlanRevision.equals(receipt.get("serverPlanRevision"))&&(serverPlanFingerprint.isBlank()||serverPlanFingerprint.equals(receipt.get("serverPlanFingerprint")));
                boolean revisions=receipt.get("targetEvidenceRevisions") instanceof Map<?,?> values&&targets.stream().allMatch(id->!targetRevisions.getOrDefault(id,"").isBlank()&&targetRevisions.get(id).equals(values.get(id)));
                if(!"CURRENT_SERVER_PROOF".equals(receipt.get("targetReceiptAuthority"))||!partition.equals(targets)||!plan||!revisions||evidenceRevision.isBlank()||!evidenceRevision.equals(receipt.get("targetEvidenceRevision"))||!remainingIds.isEmpty()&&(completed||targetCompleted))throw new BridgeFailure("HOST_TARGET_RECEIPT_INVALID");
                closedTargets.clear();closedTargets.addAll(doneIds);doneIds.forEach(blockedTargets::remove);return;
            }
            if(completed||targetCompleted){closedTargets.addAll(targets);return;}
            if(receipt.get("obligations") instanceof Collection<?> obligations)for(Object raw:obligations)if(raw instanceof Map<?,?> obligation){String id=safeId(obligation.get("id"));if(!targets.contains(id))continue;
                if(Set.of("PASS","FAIL","NOT_APPLICABLE").contains(Objects.toString(obligation.get("status"),""))&&List.of().equals(obligation.get("gaps")))closedTargets.add(id);else closedTargets.remove(id);
            }
        }
    }
    private static Object normalizeOperation(Object value,String field){
        if(value instanceof Map<?,?> map){Map<String,Object> sorted=new TreeMap<>();map.forEach((key,item)->sorted.put(key.toString(),normalizeOperation(item,key.toString())));return sorted;}
        if(value instanceof List<?> list){List<Object> items=list.stream().map(item->normalizeOperation(item,"")).toList();if(field.equals("sourceIds")&&items.stream().allMatch(String.class::isInstance))return items.stream().map(String.class::cast).distinct().sorted().toList();return items;}
        if(value instanceof String text&&!Set.of("sourceId","sourceIds").contains(field))return Normalizer.normalize(text,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ");
        return value;
    }
}
