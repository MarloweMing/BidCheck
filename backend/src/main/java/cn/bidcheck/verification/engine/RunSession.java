package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A completed, fingerprinted step is reusable; a failed or changed step is never a paid-result cache hit. */
final class RunSession {
    private static final Logger LOG=LoggerFactory.getLogger(RunSession.class);
    final ObjectMapper json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);final ModelGateway model;final Map<String,Object> context;
    final List<Map<String,Object>> steps=new ArrayList<>();final String scope;
    int modelCalls,resumedSteps,rounds,priorCalls; final int maxCalls;String activeAttempt="";
    Map<String,Object> compilationAudit=cn.bidcheck.compat.Java8Api.mapOf();private boolean reusedOutput;
    RunSession(ModelGateway model,Map<String,Object> context,String scope){this.model=model;this.context=context;this.scope=scope;
        // Production extraction is source/rule packet based and may legitimately need more than the
        // former four/six calls. A total-call ceiling is now an explicit test/diagnostic control only.
        // Request timeout, one retry, interruption and the caller's lease permit remain enforced.
        maxCalls=context.containsKey("maxModelCalls")?Math.max(1,Math.min(2000,number(context.get("maxModelCalls"),1))):Integer.MAX_VALUE;
        Set<String> attempts=new HashSet<>();if(context.get("priorSteps") instanceof List<?>)for(Object o:((List<?>) (context.get("priorSteps"))))if(o instanceof Map<?,?>&&cn.bidcheck.compat.Java8Api.setOf("COMPILE", "EXTRACT", "REVIEW_PLAN", "REVIEW_JUDGMENT").contains(((Map<?,?>) (o)).get("type"))&&!Boolean.TRUE.equals(((Map<?,?>) (o)).get("paidInPriorRevision"))&&ExtractionContracts.stepSchema(Objects.toString(((Map<?,?>) (o)).get("type"),"")).equals(((Map<?,?>) (o)).get("schemaVersion"))&&model.identity().equals(((Map<?,?>) (o)).get("modelIdentity")))attempts.add(String.valueOf((((Map<?,?>) (o)).containsKey("attemptId")?((Map<?,?>) (o)).get("attemptId"):((Map<?,?>) (o)).get("id"))));priorCalls=attempts.size();}
    static int number(Object x,int fallback){try{return Integer.parseInt(String.valueOf(x));}catch(Exception ignored){return fallback;}}
    JsonNode model(String type,Map<String,Object> input)throws IOException{
        reusedOutput=false;String fp=fingerprint(type,input);JsonNode cached=prior(type,fp);if(cached!=null){resumedSteps++;reusedOutput=true;
            if(type.equals("EXTRACT"))record("EXTRACTION_RESULT_REUSED",cn.bidcheck.compat.Java8Api.mapOf("inputFingerprint", fp),linked("inputFingerprint",fp,"schemaVersion",ExtractionContracts.VERSION,"modelIdentity",model.identity(),"newModelCall",false));return cached;}
        Map<String,Object> feedback=cn.bidcheck.compat.Java8Api.mapOf();String scopeIdentity=identity(scope),sourceRunId=diagnosticId(context.get("sourceRunId")),checkpointId=diagnosticId(context.get("checkpointId"));int sourceCount=input.get("sources") instanceof Collection<?>?((Collection<?>) (input.get("sources"))).size():0;
        int ruleCount=input.get("rules") instanceof Collection<?>?((Collection<?>) (input.get("rules"))).size():0;
        for(int retry=0;;retry++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("核验已中断");
            if(!Boolean.TRUE.equals(context.get("unlimitedModelCalls"))&&modelCalls+priorCalls>=maxCalls)throw new IOException("MODEL_BUDGET_EXHAUSTED");
            if(!model.enabled())throw new IOException("MODEL_DISABLED");
            if(context.get("modelAttemptPermit") instanceof BooleanSupplier&&!((BooleanSupplier) (context.get("modelAttemptPermit"))).getAsBoolean())throw new IOException("MODEL_BUDGET_EXHAUSTED");
            modelCalls++;activeAttempt=UUID.randomUUID().toString();long started=System.nanoTime();save(type,fp,"STARTED",linked("attemptNumber",modelCalls+priorCalls,"retryIndex",retry,"sourceCount",sourceCount,"ruleCount",ruleCount,"schemaFeedback",!feedback.isEmpty()));
            String stepId=identity(type+fp+activeAttempt);
            LOG.info("verification_extraction sourceRunId={} checkpointId={} scope={} requestId={} stepId={} input={} stage={} state=STARTED retryIndex={} sourceCount={} ruleCount={} schemaFeedback={}",sourceRunId,checkpointId,scopeIdentity,activeAttempt,stepId,fp,type,retry,sourceCount,ruleCount,!feedback.isEmpty());
            JsonNode output;
            try(org.slf4j.MDC.MDCCloseable runLog=MDC.putCloseable("verification.sourceRunId",sourceRunId);org.slf4j.MDC.MDCCloseable checkpointLog=MDC.putCloseable("verification.checkpointId",checkpointId);org.slf4j.MDC.MDCCloseable requestLog=MDC.putCloseable("verification.requestId",activeAttempt);org.slf4j.MDC.MDCCloseable stepLog=MDC.putCloseable("verification.stepId",stepId)){
                output=feedback.isEmpty()?model.request(type,input):model.requestWithFeedback(type,input,feedback);
            }catch(IOException ex){
                Map<String,Object> diagnostic=failure(ex);diagnostic.put("durationMs",(System.nanoTime()-started)/1_000_000);
                // A splittable extraction failure is retried as smaller source/rule packets by the
                // engine. Repeating the same oversized or malformed packet only wastes a paid call.
                boolean delegatedSplit=type.equals("EXTRACT")&&Boolean.TRUE.equals(diagnostic.get("splittable"))&&ExtractionBatches.canSplitFailed(input);
                boolean canRetry=retry==0&&Boolean.TRUE.equals(diagnostic.get("retryable"))&&!delegatedSplit
                    &&(Boolean.TRUE.equals(context.get("unlimitedModelCalls"))||modelCalls+priorCalls<maxCalls);
                diagnostic.put("retryPlanned",canRetry);diagnostic.put("splitPlanned",delegatedSplit);save(type,fp,"FAILED",diagnostic);
                LOG.warn("verification_extraction sourceRunId={} checkpointId={} scope={} requestId={} stepId={} input={} stage={} state=FAILED durationMs={} errorCode={} schemaIssue={} schemaPath={} retryPlanned={} splitPlanned={}",
                    sourceRunId,checkpointId,scopeIdentity,activeAttempt,stepId,fp,type,diagnostic.get("durationMs"),diagnostic.get("errorCode"),diagnostic.get("schemaIssue"),diagnostic.get("schemaPath"),canRetry,delegatedSplit);
                if(!canRetry)throw ex;
                if(cn.bidcheck.compat.Java8Api.setOf("OUTPUT_SCHEMA_INVALID", "OUTPUT_JSON_INVALID").contains(diagnostic.get("errorCode")))feedback=diagnostic;
                // One retry for this exact request only. Each attempt is persisted and obtains a fresh permit.
                try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("核验已中断");}
                continue;
            }
            // A syntactically valid compiler response is not yet a valid business plan.
            long durationMs=(System.nanoTime()-started)/1_000_000;
            save(type,fp,type.equals("COMPILE")?"RECEIVED":"COMPLETED",output,linked("durationMs",durationMs,"sourceCount",sourceCount,"ruleCount",ruleCount));
            LOG.info("verification_extraction sourceRunId={} checkpointId={} scope={} requestId={} stepId={} input={} stage={} state=COMPLETED durationMs={} facts={}",sourceRunId,checkpointId,scopeIdentity,activeAttempt,stepId,fp,type,durationMs,output.path("facts").size());return output;
        }
    }
    private static String diagnosticId(Object value){String text=Objects.toString(value,"");return cn.bidcheck.compat.Java8Api.isBlank(text)?"":text.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")?text:"sha256:"+identity(text);}
    void completeCompilation(Map<String,Object> input,JsonNode raw,List<Map<String,Object>> diagnostics){
        if(reusedOutput)return;
        String fp=fingerprint("COMPILE",input);
        save("COMPILE",fp,diagnostics.isEmpty()?"COMPLETED":"VALIDATION_FAILED",raw,
            linked("validationVersion",CompilationRecovery.VERSION,"validationIssues",cn.bidcheck.compat.Java8Api.listCopyOf(diagnostics)));
    }
    static Map<String,Object> failure(IOException error){
        if(error instanceof InterruptedIOException)return linked("errorCode","INTERRUPTED","httpStatus",0,"retryable",false,"splittable",false);
        if(error instanceof VerificationModelClient.ModelRequestException){
            String code=((VerificationModelClient.ModelRequestException) (error)).code();boolean known=code!=null&&(cn.bidcheck.compat.Java8Api.setOf("OPERATION_NOT_ALLOWED", "NOT_CONFIGURED", "ENDPOINT_INVALID", "CAPACITY_TIMEOUT", "INPUT_LIMIT", "OUTPUT_LIMIT", "OUTPUT_INCOMPLETE", "OUTPUT_SCHEMA_INVALID", "REQUEST_TIMEOUT", "INPUT_SERIALIZATION_INVALID", "INPUT_SCHEMA_INVALID", "OUTPUT_JSON_INVALID", "TRANSPORT_FAILURE", "CLIENT_FAILURE").contains(code)||code.matches("HTTP_[1-5][0-9]{2}"));
            Map<String,Object> diagnostic=linked("errorCode",known?code:"MODEL_UNAVAILABLE","httpStatus",((VerificationModelClient.ModelRequestException) (error)).httpStatus()>=100&&((VerificationModelClient.ModelRequestException) (error)).httpStatus()<=599?((VerificationModelClient.ModelRequestException) (error)).httpStatus():0,"retryable",known&&((VerificationModelClient.ModelRequestException) (error)).retryable(),"splittable",known&&((VerificationModelClient.ModelRequestException) (error)).splittable());
            if(((VerificationModelClient.ModelRequestException) (error)).schemaIssue().matches("[A-Z_]{1,64}")&&((VerificationModelClient.ModelRequestException) (error)).schemaPath().matches("[a-zA-Z0-9_./@\\[\\]-]{1,256}")){
                diagnostic.put("schemaIssue",((VerificationModelClient.ModelRequestException) (error)).schemaIssue());diagnostic.put("schemaPath",((VerificationModelClient.ModelRequestException) (error)).schemaPath());
            }return diagnostic;
        }
        String code=cn.bidcheck.compat.Java8Api.setOf("MODEL_BUDGET_EXHAUSTED", "MODEL_DISABLED").contains(Objects.toString(error.getMessage(),""))?error.getMessage():"MODEL_UNAVAILABLE";
        return linked("errorCode",code,"httpStatus",0,"retryable",false,"splittable",false);
    }
    Map<String,Object> diagnostics(){
        List<Map<String,Object>> failures=new ArrayList<>();
        for(int at=0;at<steps.size();at++){
            Map<String,Object> step=steps.get(at);if(!"FAILED".equals(step.get("status"))||!cn.bidcheck.compat.Java8Api.setOf("COMPILE", "EXTRACT", "REVIEW_PLAN", "REVIEW_JUDGMENT").contains(step.get("type")))continue;
            Map<?,?> detail=step.get("output") instanceof Map<?,?>?((Map<?,?>) (step.get("output"))):cn.bidcheck.compat.Java8Api.mapOf();boolean recovered=false;
            for(int next=at+1;next<steps.size();next++){Map<String,Object> later=steps.get(next);if(cn.bidcheck.compat.Java8Api.setOf("RECEIVED", "COMPLETED", "VALIDATION_FAILED").contains(later.get("status"))&&Objects.equals(step.get("type"),later.get("type"))&&Objects.equals(step.get("inputFingerprint"),later.get("inputFingerprint"))){recovered=true;break;}}
            boolean recoveredBySplit=!recovered&&"EXTRACT".equals(step.get("type"))&&splitRecovered(String.valueOf(step.get("inputFingerprint")),at,new HashSet<>());
            Map<String,Object> failure=linked("stage",step.get("type"),"errorCode",detail.get("errorCode"),"httpStatus",detail.get("httpStatus"),"retryable",detail.get("retryable"),"splittable",detail.get("splittable"),"recoveredByRetry",recovered,"recoveredBySplit",recoveredBySplit,"transportRecovered",recovered||recoveredBySplit,"businessValidationSeparate",true);
            for(String key:cn.bidcheck.compat.Java8Api.listOf("schemaIssue", "schemaPath", "durationMs", "retryPlanned", "splitPlanned"))if(detail.containsKey(key))failure.put(key,detail.get(key));failures.add(failure);
        }
        long recovered=failures.stream().filter(row->Boolean.TRUE.equals(row.get("transportRecovered"))).count();
        Map<String,Long> byCode=new TreeMap<>(),bySchemaIssue=new TreeMap<>();for(Map<String,Object> failure:failures){byCode.merge(Objects.toString(failure.get("errorCode"),"UNKNOWN"),1L,Long::sum);if(failure.get("schemaIssue") instanceof String)bySchemaIssue.merge(((String) (failure.get("schemaIssue"))),1L,Long::sum);}
        return linked("failures",failures,"failedAttempts",failures.size(),"recoveredAttempts",recovered,"unresolvedFailures",failures.size()-recovered,"failuresByCode",byCode,"failuresBySchemaIssue",bySchemaIssue);
    }
    /** Record the exact source/obligation partition. A later unrelated successful call cannot close it. */
    void recordExtractionSplit(Map<String,Object> parent,List<Map<String,Object>> children,Map<String,Object> failure){
        Set<String> expected=extractionPairs(parent),actual=new LinkedHashSet<>();
        for(Map<String,Object> child:children)actual.addAll(extractionPairs(child));
        String parentFingerprint=fingerprint("EXTRACT",parent);List<String> childFingerprints=cn.bidcheck.compat.Java8Api.toList(children.stream().map(child->fingerprint("EXTRACT",child)));
        boolean complete=children.size()>1&&!expected.isEmpty()&&expected.equals(actual)&&!childFingerprints.contains(parentFingerprint)
            &&childFingerprints.stream().distinct().count()==childFingerprints.size();
        record("EXTRACTION_FAILURE_SPLIT",cn.bidcheck.compat.Java8Api.mapOf("packetId", parent.get("packetId")),linked("errorCode",failure.get("errorCode"),
            "splitBy",ExtractionBatches.sourceIds(parent).size()>1?"SOURCE_WINDOW":"OBLIGATION","children",cn.bidcheck.compat.Java8Api.toList(children.stream().map(child->child.get("packetId"))),
            "parentInputFingerprint",parentFingerprint,"childInputFingerprints",childFingerprints,"sourceCoveragePreserved",complete,"expectedSourceFieldPairs",expected.size()));
        LOG.info("bidcheck.extraction_split sourceRunId={} checkpointId={} parentInput={} childInputs={} sourceFieldPairs={} coveragePreserved={}",
            diagnosticId(context.get("sourceRunId")),diagnosticId(context.get("checkpointId")),parentFingerprint,childFingerprints,expected.size(),complete);
    }
    private static Set<String> extractionPairs(Map<String,Object> input){
        Set<String> out=new LinkedHashSet<>();for(cn.bidcheck.verification.engine.VerificationTypes.Rule rule:ExtractionBatches.rules(input))for(String source:ExtractionBatches.sourceIds(input))out.add(rule.id()+":"+source);return out;
    }
    private boolean splitRecovered(String input,int after,Set<String> path){
        if(!path.add(input))return false;
        try{for(int at=after+1;at<steps.size();at++){
            Map<String,Object> step=steps.get(at);if(!"EXTRACTION_FAILURE_SPLIT".equals(step.get("type"))||!(step.get("output") instanceof Map<?,?>)
                ||!input.equals(((Map<?,?>) (step.get("output"))).get("parentInputFingerprint"))||!Boolean.TRUE.equals(((Map<?,?>) (step.get("output"))).get("sourceCoveragePreserved")))continue;
            List<String> children=ProofVerifier.strings(((Map<?,?>) (step.get("output"))).get("childInputFingerprints"));if(children.isEmpty())continue;
            boolean complete=true;for(String child:children){boolean successful=false;for(int later=at+1;later<steps.size();later++){
                Map<String,Object> result=steps.get(later);if("EXTRACT".equals(result.get("type"))&&"COMPLETED".equals(result.get("status"))&&child.equals(result.get("inputFingerprint"))
                    ||"EXTRACTION_RESULT_REUSED".equals(result.get("type"))&&result.get("output") instanceof Map<?,?>&&child.equals(((Map<?,?>) (result.get("output"))).get("inputFingerprint"))
                    &&ExtractionContracts.VERSION.equals(((Map<?,?>) (result.get("output"))).get("schemaVersion"))&&model.identity().equals(((Map<?,?>) (result.get("output"))).get("modelIdentity"))){successful=true;break;}}
                if(!successful&&!splitRecovered(child,at,path)){complete=false;break;}
            }if(complete)return true;
        }return false;}finally{path.remove(input);}
    }
    JsonNode prior(String type,String fp){Object prior=context.get("priorSteps");if(!(prior instanceof List<?>))return null;for(Object item:((List<?>) (prior))){if(!(item instanceof Map<?,?>))continue;if(type.equals(((Map<?,?>) (item)).get("type"))&&fp.equals(((Map<?,?>) (item)).get("inputFingerprint"))&&ExtractionContracts.stepSchema(type).equals(((Map<?,?>) (item)).get("schemaVersion"))&&model.identity().equals(((Map<?,?>) (item)).get("modelIdentity"))&&"COMPLETED".equals(((Map<?,?>) (item)).get("status"))&&(!type.equals("COMPILE")||CompilationRecovery.VERSION.equals(((Map<?,?>) (item)).get("validationVersion"))))return json.valueToTree(((Map<?,?>) (item)).get("output"));}return null;}
    void record(String type,Object input,Object output){save(type,fingerprint(type,input),"COMPLETED",output);}
    String fingerprint(String type,Object input){try{return identity((type.equals("COMPILE")?"":scope)+ExtractionContracts.stepSchema(type)+model.identity()+type+json.writeValueAsString(input));}catch(Exception e){throw new IllegalArgumentException("核验输入不能序列化");}}
    void save(String type,String fp,String status,Object output){save(type,fp,status,output,cn.bidcheck.compat.Java8Api.mapOf());}
    @SuppressWarnings("unchecked") private void save(String type,String fp,String status,Object output,Map<String,Object> metadata){boolean paid=cn.bidcheck.compat.Java8Api.setOf("COMPILE", "EXTRACT", "REVIEW_PLAN", "REVIEW_JUDGMENT").contains(type);String execution=paid?activeAttempt:UUID.randomUUID().toString();Map<String,Object> step=linked("id",identity(type+fp+execution),"attemptId",paid?activeAttempt:"","executionId",execution,"type",type,"inputFingerprint",fp,"schemaVersion",ExtractionContracts.stepSchema(type),"engineVersion",VERSION,"modelIdentity",model.identity(),"status",status,"output",json.convertValue(output,Object.class));step.putAll(metadata);steps.add(step);Object observer=context.get("stepObserver");if(observer instanceof Consumer<?>)((Consumer<Map<String,Object>>)((Consumer<?>) (observer))).accept(step);}
}
