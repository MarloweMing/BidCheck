package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A completed, fingerprinted step is reusable; a failed or changed step is never a paid-result cache hit. */
final class RunSession {
    final ObjectMapper json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);final ModelGateway model;final Map<String,Object> context;
    final List<Map<String,Object>> steps=new ArrayList<>();final String scope;
    int modelCalls,resumedSteps,rounds,priorCalls; final int maxCalls;String activeAttempt="";
    Map<String,Object> compilationAudit=Map.of();private boolean reusedOutput;
    RunSession(ModelGateway model,Map<String,Object> context,String scope){this.model=model;this.context=context;this.scope=scope;
        // Production extraction is source/rule packet based and may legitimately need more than the
        // former four/six calls. A total-call ceiling is now an explicit test/diagnostic control only.
        // Request timeout, one retry, interruption and the caller's lease permit remain enforced.
        maxCalls=context.containsKey("maxModelCalls")?Math.max(1,Math.min(2000,number(context.get("maxModelCalls"),1))):Integer.MAX_VALUE;
        Set<String> attempts=new HashSet<>();if(context.get("priorSteps") instanceof List<?> list)for(Object o:list)if(o instanceof Map<?,?> m&&Set.of("COMPILE","EXTRACT").contains(m.get("type"))&&!Boolean.TRUE.equals(m.get("paidInPriorRevision"))&&VERSION.equals(m.get("schemaVersion"))&&model.identity().equals(m.get("modelIdentity")))attempts.add(String.valueOf((m.containsKey("attemptId")?m.get("attemptId"):m.get("id"))));priorCalls=attempts.size();}
    static int number(Object x,int fallback){try{return Integer.parseInt(String.valueOf(x));}catch(Exception ignored){return fallback;}}
    JsonNode model(String type,Map<String,Object> input)throws IOException{
        reusedOutput=false;String fp=fingerprint(type,input);JsonNode cached=prior(type,fp);if(cached!=null){resumedSteps++;reusedOutput=true;return cached;}
        for(int retry=0;;retry++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("核验已中断");
            if(!Boolean.TRUE.equals(context.get("unlimitedModelCalls"))&&modelCalls+priorCalls>=maxCalls)throw new IOException("MODEL_BUDGET_EXHAUSTED");
            if(!model.enabled())throw new IOException("MODEL_DISABLED");
            if(context.get("modelAttemptPermit") instanceof BooleanSupplier permit&&!permit.getAsBoolean())throw new IOException("MODEL_BUDGET_EXHAUSTED");
            modelCalls++;activeAttempt=UUID.randomUUID().toString();long started=System.nanoTime();save(type,fp,"STARTED",Map.of("attemptNumber",modelCalls+priorCalls,"retryIndex",retry));
            JsonNode output;
            try{output=model.request(type,input);}catch(IOException ex){
                Map<String,Object> diagnostic=failure(ex);diagnostic.put("durationMs",(System.nanoTime()-started)/1_000_000);
                // A splittable extraction failure is retried as smaller source/rule packets by the
                // engine. Repeating the same oversized or malformed packet only wastes a paid call.
                boolean delegatedSplit=type.equals("EXTRACT")&&Boolean.TRUE.equals(diagnostic.get("splittable"))&&ExtractionBatches.canSplitFailed(input);
                boolean canRetry=retry==0&&Boolean.TRUE.equals(diagnostic.get("retryable"))&&!delegatedSplit
                    &&(Boolean.TRUE.equals(context.get("unlimitedModelCalls"))||modelCalls+priorCalls<maxCalls);
                diagnostic.put("retryPlanned",canRetry);diagnostic.put("splitPlanned",delegatedSplit);save(type,fp,"FAILED",diagnostic);
                if(!canRetry)throw ex;
                // One retry for this exact request only. Each attempt is persisted and obtains a fresh permit.
                try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("核验已中断");}
                continue;
            }
            // A syntactically valid compiler response is not yet a valid business plan.
            save(type,fp,type.equals("COMPILE")?"RECEIVED":"COMPLETED",output);return output;
        }
    }
    void completeCompilation(Map<String,Object> input,JsonNode raw,List<Map<String,Object>> diagnostics){
        if(reusedOutput)return;
        String fp=fingerprint("COMPILE",input);
        save("COMPILE",fp,diagnostics.isEmpty()?"COMPLETED":"VALIDATION_FAILED",raw,
            linked("validationVersion",CompilationRecovery.VERSION,"validationIssues",List.copyOf(diagnostics)));
    }
    static Map<String,Object> failure(IOException error){
        if(error instanceof InterruptedIOException)return linked("errorCode","INTERRUPTED","httpStatus",0,"retryable",false,"splittable",false);
        if(error instanceof VerificationModelClient.ModelRequestException safe){
            String code=safe.code();boolean known=code!=null&&(Set.of("NOT_CONFIGURED","ENDPOINT_INVALID","CAPACITY_TIMEOUT","INPUT_LIMIT","OUTPUT_LIMIT","OUTPUT_INCOMPLETE","OUTPUT_SCHEMA_INVALID","REQUEST_TIMEOUT","INPUT_SERIALIZATION_INVALID","OUTPUT_JSON_INVALID","TRANSPORT_FAILURE","CLIENT_FAILURE").contains(code)||code.matches("HTTP_[1-5][0-9]{2}"));
            return linked("errorCode",known?code:"MODEL_UNAVAILABLE","httpStatus",safe.httpStatus()>=100&&safe.httpStatus()<=599?safe.httpStatus():0,"retryable",known&&safe.retryable(),"splittable",known&&safe.splittable());
        }
        String code=Set.of("MODEL_BUDGET_EXHAUSTED","MODEL_DISABLED").contains(Objects.toString(error.getMessage(),""))?error.getMessage():"MODEL_UNAVAILABLE";
        return linked("errorCode",code,"httpStatus",0,"retryable",false,"splittable",false);
    }
    Map<String,Object> diagnostics(){
        List<Map<String,Object>> failures=new ArrayList<>();
        for(int at=0;at<steps.size();at++){
            Map<String,Object> step=steps.get(at);if(!"FAILED".equals(step.get("status"))||!Set.of("COMPILE","EXTRACT").contains(step.get("type")))continue;
            Map<?,?> detail=step.get("output") instanceof Map<?,?> m?m:Map.of();boolean recovered=false;
            for(int next=at+1;next<steps.size();next++){Map<String,Object> later=steps.get(next);if(Set.of("RECEIVED","COMPLETED","VALIDATION_FAILED").contains(later.get("status"))&&Objects.equals(step.get("type"),later.get("type"))&&Objects.equals(step.get("inputFingerprint"),later.get("inputFingerprint"))){recovered=true;break;}}
            failures.add(linked("stage",step.get("type"),"errorCode",detail.get("errorCode"),"httpStatus",detail.get("httpStatus"),"retryable",detail.get("retryable"),"splittable",detail.get("splittable"),"recoveredByRetry",recovered,"transportRecovered",recovered,"businessValidationSeparate",true));
        }
        long recovered=failures.stream().filter(row->Boolean.TRUE.equals(row.get("recoveredByRetry"))).count();
        return linked("failures",failures,"failedAttempts",failures.size(),"recoveredAttempts",recovered,"unresolvedFailures",failures.size()-recovered);
    }
    JsonNode prior(String type,String fp){Object prior=context.get("priorSteps");if(!(prior instanceof List<?> list))return null;for(Object item:list){if(!(item instanceof Map<?,?> row))continue;if(type.equals(row.get("type"))&&fp.equals(row.get("inputFingerprint"))&&VERSION.equals(row.get("schemaVersion"))&&model.identity().equals(row.get("modelIdentity"))&&"COMPLETED".equals(row.get("status"))&&(!type.equals("COMPILE")||CompilationRecovery.VERSION.equals(row.get("validationVersion"))))return json.valueToTree(row.get("output"));}return null;}
    void record(String type,Object input,Object output){save(type,fingerprint(type,input),"COMPLETED",output);}
    String fingerprint(String type,Object input){try{return identity((type.equals("COMPILE")?"":scope)+VERSION+model.identity()+type+json.writeValueAsString(input));}catch(Exception e){throw new IllegalArgumentException("核验输入不能序列化");}}
    void save(String type,String fp,String status,Object output){save(type,fp,status,output,Map.of());}
    @SuppressWarnings("unchecked") private void save(String type,String fp,String status,Object output,Map<String,Object> metadata){boolean paid=Set.of("COMPILE","EXTRACT").contains(type);String execution=paid?activeAttempt:UUID.randomUUID().toString();Map<String,Object> step=linked("id",identity(type+fp+execution),"attemptId",paid?activeAttempt:"","executionId",execution,"type",type,"inputFingerprint",fp,"schemaVersion",VERSION,"modelIdentity",model.identity(),"status",status,"output",json.convertValue(output,Object.class));step.putAll(metadata);steps.add(step);Object observer=context.get("stepObserver");if(observer instanceof Consumer<?> listener)((Consumer<Map<String,Object>>)listener).accept(step);}
}
