package cn.bidcheck.verification.evidence;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import cn.bidcheck.persistence.EvidenceOperationLeaseRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DuplicateKeyException;

/**
 * Durable cache identity and single-flight coordinator for evidence-producing operations.
 *
 * <p>The key is derived only from immutable input identities. A run/checkpoint id is deliberately
 * excluded, so a validated observation can be reused by a later revision. Business decisions are
 * never stored here: callers may publish only source-grounded facts, visual observations or trusted
 * external records after their domain validator has accepted them.</p>
 */
public final class EvidenceOperationRepository {
    public static final String VERSION="evidence-operation-1-source-grounded";
    private static final String KIND="evidence_operation";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ConcurrentHashMap<String,Flight> FLIGHTS=new ConcurrentHashMap<>();
    private static final long DEFAULT_LEASE_MILLIS=5*60_000L;
    private static final long DEFAULT_RENEW_MILLIS=60_000L;
    private static final long DEFAULT_POLL_MILLIS=100L;
    private static final ScheduledExecutorService RENEWALS=Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread=new Thread(task,"evidence-operation-lease-renewer");thread.setDaemon(true);return thread;
    });
    private static final Set<String> DECISION_KEYS=cn.bidcheck.compat.Java8Api.setOf("businessdecision", "decision", "evaluation", "complianceconclusion", "compliancedecision", "complianceresult", "decisionresult", "evaluationresult", "checkdecision", "checkconclusion", "checkresult", "auditdecision", "auditconclusion", "verdict", "pass", "passed", "fail", "failed", "ispass", "ispassed", "isfail", "isfailed", "结论", "判定", "审核结论", "检查结论", "是否通过", "通过", "不通过");
    private static final Set<String> DECISION_VALUES=cn.bidcheck.compat.Java8Api.setOf("PASS", "PASSED", "FAIL", "FAILED", "COMPLIANT", "NONCOMPLIANT", "NON_COMPLIANT", "SATISFIED", "NOTSATISFIED", "NOT_SATISFIED", "通过", "未通过", "不通过", "符合", "不符合", "合格", "不合格");
    private static final class Flight {final ReentrantLock lock=new ReentrantLock(true);int references;}

    private final Store store;
    private final boolean processSingleFlight;
    private final long leaseMillis,renewMillis,pollMillis;
    public EvidenceOperationRepository(Store store){this(store,true,DEFAULT_LEASE_MILLIS,DEFAULT_RENEW_MILLIS,DEFAULT_POLL_MILLIS);}
    /** Package-private controls let repository tests represent independent JVMs without weakening
     * the production path, where both the cheap process lock and the database lease are enabled. */
    EvidenceOperationRepository(Store store,boolean processSingleFlight,long leaseMillis,long renewMillis,long pollMillis){
        this.store=Objects.requireNonNull(store);this.processSingleFlight=processSingleFlight;
        if(leaseMillis<50||pollMillis<1||renewMillis<0||(renewMillis>0&&renewMillis>=leaseMillis))
            throw new IllegalArgumentException("EVIDENCE_OPERATION_LEASE_CONFIGURATION_INVALID");
        this.leaseMillis=leaseMillis;this.renewMillis=renewMillis;this.pollMillis=pollMillis;
    }

    /** Build a safe descriptor. Source text is reduced to a digest and cannot leak into cache keys. */
    public static Map<String,Object> descriptor(String projectId,String operationType,Object documents,Object sources,
            Object requestedFields,String requirementArtifactId,String modelIdentity,String schemaVersion,String extractorVersion){
        return linked("version",VERSION,"projectId",clean(projectId),"operationType",clean(operationType),
            "documents",fingerprints(documents),"sources",fingerprints(sources),"requestedFields",stableRequested(requestedFields),
            "requirementArtifactId",clean(requirementArtifactId),"modelIdentity",clean(modelIdentity),
            "schemaVersion",clean(schemaVersion),"extractorVersion",clean(extractorVersion));
    }

    public static String key(Map<String,Object> descriptor){
        try{return hex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(ordered(descriptor))));}
        catch(Exception impossible){throw new IllegalArgumentException("EVIDENCE_OPERATION_IDENTITY_INVALID",impossible);}
    }

    /** Stable text operation identity used by the fact ledger and its single-flight lease. */
    public static String textKey(String projectId,String modelIdentity,String engineIdentity,Object sources,Object targets,String requirementArtifactId){
        return key(descriptor(projectId,"TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),sources,targets,requirementArtifactId,modelIdentity,
            "FACTS_ONLY",engineIdentity));
    }

    public Optional<Map<String,Object>> completed(String projectId,String operationKey){
        if(projectId==null||cn.bidcheck.compat.Java8Api.isBlank(projectId)||operationKey==null||!operationKey.matches("[a-f0-9]{64}"))return Optional.empty();
        Stored row=store.mapper().get(operationKey);
        if(row==null||!KIND.equals(row.kind)||!projectId.equals(row.projectId))return Optional.empty();
        Map<String,Object> value=store.map(row.body);
        if(!VERSION.equals(value.get("version"))||!operationKey.equals(value.get("operationKey"))||!"COMPLETED".equals(value.get("status")))return Optional.empty();
        Object result=value.get("result"),savedDescriptor=value.get("descriptor");if(!(result instanceof Map<?,?>)||!(savedDescriptor instanceof Map<?,?>))return Optional.empty();
        try{
            Map<String,Object> descriptor=stringMap(((Map<?,?>) (savedDescriptor)));if(!projectId.equals(clean(descriptor.get("projectId")))||!operationKey.equals(key(descriptor)))return Optional.empty();
            @SuppressWarnings("unchecked") Map<String,Object> copy=new LinkedHashMap<>((Map<String,Object>)((Map<?,?>) (result)));validateEvidenceOnly(descriptor,copy);
            copy.put("persistentCacheHit",true);return Optional.of(Collections.unmodifiableMap(copy));
        }catch(IllegalArgumentException corrupted){return Optional.empty();}
    }

    /** First validated success wins. A later nondeterministic response cannot replace it. */
    public void publishCompleted(String projectId,String operationKey,Map<String,Object> descriptor,Map<String,Object> result){
        if(projectId==null||cn.bidcheck.compat.Java8Api.isBlank(projectId)||operationKey==null||descriptor==null||!operationKey.equals(key(descriptor)))throw new IllegalArgumentException("EVIDENCE_OPERATION_SCOPE_INVALID");
        if(result==null||result.isEmpty())throw new IllegalArgumentException("EVIDENCE_OPERATION_RESULT_EMPTY");
        validateEvidenceOnly(descriptor,result);
        Map<String,Object> value=linked("version",VERSION,"operationKey",operationKey,"status","COMPLETED","descriptor",ordered(descriptor),
            "result",ordered(result),"completedAt",Instant.now().toString(),"businessDecisionStored",false);
        try{store.insertImmutableIfAbsent(KIND,operationKey,projectId,clean(descriptor.get("operationType")),value);}
        catch(IllegalStateException conflict){if((!completed(projectId,operationKey).isPresent()))throw conflict;}
    }

    /** The durable layer accepts observations only. A caller cannot label a cached model response as
     * evidence while smuggling a business decision into the same envelope. */
    private static void validateEvidenceOnly(Map<String,Object> descriptor,Map<String,Object> result){
        if(containsDecisionContent(result))throw new IllegalArgumentException("EVIDENCE_OPERATION_DECISION_FORBIDDEN");
        String type=clean(descriptor.get("operationType"));
        if(type.startsWith("TEXT_FACT_")){
            if(!(result.get("facts") instanceof Collection<?>)||!(result.get("sources") instanceof Collection<?>)||!(result.get("fieldCoverage") instanceof Collection<?>))
                throw new IllegalArgumentException("EVIDENCE_OPERATION_TEXT_SCHEMA_INVALID");
            return;
        }
        if("VISUAL_OBSERVATION".equals(type)){
            if(!(result.get("pageResults") instanceof Collection<?>)||Boolean.TRUE.equals(result.get("authenticityVerified")))
                throw new IllegalArgumentException("EVIDENCE_OPERATION_VISUAL_SCHEMA_INVALID");
            return;
        }
        if("EXTERNAL_SOURCE_QUERY".equals(type)){
            if(!(result.get("record") instanceof Map<?,?>)||!"TRUSTED_CONNECTOR".equals(((Map<?,?>) (result.get("record"))).get("provenanceStatus")))
                throw new IllegalArgumentException("EVIDENCE_OPERATION_EXTERNAL_SCHEMA_INVALID");
            return;
        }
        throw new IllegalArgumentException("EVIDENCE_OPERATION_TYPE_INVALID");
    }

    /** Returns true when an observation tree carries an application/business conclusion. The walk is
     * deliberately recursive: placing a verdict below facts, attributes, page observations or a
     * connector record must not turn it into reusable evidence. */
    public static boolean containsDecisionContent(Object root){
        @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    final class Node{
        private final Object value;
        private final int depth;
        @com.fasterxml.jackson.annotation.JsonCreator
        Node(@com.fasterxml.jackson.annotation.JsonProperty("value") Object value, @com.fasterxml.jackson.annotation.JsonProperty("depth") int depth) { this.value = value; this.depth = depth; }
        public Object value() { return value; }
        public int depth() { return depth; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Node that = (Node) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.depth, that.depth); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.depth); return hash; }
        @Override public String toString() { return "Node[" + "value=" + value + ", depth=" + depth + "]"; }
}
        Deque<Node> pending=new ArrayDeque<>();pending.push(new Node(root,0));
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());int visited=0;
        while(!pending.isEmpty()){
            Node current=pending.pop();Object value=current.value();if(value==null)continue;
            if(++visited>100_000||current.depth()>64)return true; // reject pathological payloads at the evidence boundary
            if(value instanceof JsonNode){
                if(((JsonNode) (value)).isObject()){
                    Iterator<Map.Entry<String,JsonNode>> fields=((JsonNode) (value)).fields();while(fields.hasNext()){
                        Map.Entry<String,JsonNode> field=fields.next();if(decisionField(field.getKey(),field.getValue()))return true;
                        pending.push(new Node(field.getValue(),current.depth()+1));
                    }
                }else if(((JsonNode) (value)).isArray())for(JsonNode child:((JsonNode) (value)))pending.push(new Node(child,current.depth()+1));
                continue;
            }
            if(value instanceof Map<?,?>){
                if(!seen.add(value))continue;
                for(Map.Entry<?,?> entry:((Map<?,?>) (value)).entrySet()){
                    String key=String.valueOf(entry.getKey());if(decisionField(key,entry.getValue()))return true;
                    pending.push(new Node(entry.getValue(),current.depth()+1));
                }
                continue;
            }
            if(value instanceof Collection<?>){if(!seen.add(value))continue;for(Object child:((Collection<?>) (value)))pending.push(new Node(child,current.depth()+1));continue;}
            if(value.getClass().isArray()){if(!seen.add(value))continue;for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)pending.push(new Node(java.lang.reflect.Array.get(value,i),current.depth()+1));}
        }
        return false;
    }

    private static boolean decisionField(String raw,Object value){
        String key=normalizeKey(raw);
        if(DECISION_KEYS.contains(key)||key.contains("decision")||key.contains("conclusion")||key.contains("verdict")
                ||key.contains("业务判定")||key.contains("合规结论"))return true;
        return (key.equals("status")||key.endsWith("status")||key.equals("result")||key.endsWith("result")
                ||key.equals("状态")||key.endsWith("结果"))&&decisionValue(value);
    }
    private static boolean decisionValue(Object value){
        String text;
        if(value instanceof JsonNode){if(!((JsonNode) (value)).isTextual())return false;text=((JsonNode) (value)).textValue();}
        else if(value instanceof CharSequence)text=((CharSequence) (value)).toString();else return false;
        return DECISION_VALUES.contains(cn.bidcheck.compat.Java8Api.strip(text).toUpperCase(Locale.ROOT).replace('-','_').replace(" ",""));
    }
    private static String normalizeKey(String value){return cn.bidcheck.compat.Java8Api.strip(Objects.toString(value,"")).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}

    /** Coordinates identical work inside this process. Kept for callers that do not have a Store scope. */
    public static Lease coordinate(String operationKey)throws java.io.InterruptedIOException{
        if(operationKey==null||cn.bidcheck.compat.Java8Api.isBlank(operationKey))throw new IllegalArgumentException("EVIDENCE_OPERATION_KEY_EMPTY");
        Flight flight=FLIGHTS.compute(operationKey,(ignored,current)->{Flight value=current==null?new Flight():current;value.references++;return value;});
        try{flight.lock.lockInterruptibly();return new Lease(operationKey,flight);}
        catch(InterruptedException interrupted){releaseReference(operationKey,flight);Thread.currentThread().interrupt();java.io.InterruptedIOException out=new java.io.InterruptedIOException("证据操作已中断");out.initCause(interrupted);throw out;}
    }

    /**
     * Coordinates first execution across application instances. The local lock is acquired first,
     * then one owner is installed with an atomic database insert or expired-lease compare-and-set.
     * Waiters repeatedly re-read the immutable completed value and therefore return as soon as the
     * owner publishes, without making another provider call. A crashed owner is replaced only after
     * its renewable lease expires.
     */
    public Lease coordinate(String projectId,String operationKey)throws java.io.InterruptedIOException{
        if(projectId==null||cn.bidcheck.compat.Java8Api.isBlank(projectId))throw new IllegalArgumentException("EVIDENCE_OPERATION_PROJECT_EMPTY");
        if(operationKey==null||!operationKey.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("EVIDENCE_OPERATION_KEY_INVALID");
        Lease lease=processSingleFlight?coordinate(operationKey):new Lease(operationKey,null);
        try{
            String owner=UUID.randomUUID().toString();
            while(true){
                if(completed(projectId,operationKey).isPresent()){lease.observeCompletion();return lease;}
                long now=System.currentTimeMillis(),until=safeUntil(now,leaseMillis);
                if(tryClaim(projectId,operationKey,owner,now,until)){
                    lease.attachDurable(this,projectId,owner,until);
                    // Completion may have been published between the read and the CAS. Relinquish
                    // this now-unnecessary claim before allowing the caller to continue.
                    if(completed(projectId,operationKey).isPresent()){lease.relinquishDurable();lease.observeCompletion();return lease;}
                    lease.startRenewal();return lease;
                }
                EvidenceOperationLeaseRow current=store.mapper().evidenceOperationLease(operationKey);
                if(current!=null&&!projectId.equals(current.projectId))throw new IllegalStateException("EVIDENCE_OPERATION_LEASE_SCOPE_CONFLICT");
                long wait=pollMillis;
                if(current!=null&&current.leaseUntil>now)wait=Math.max(1,Math.min(wait,current.leaseUntil-now));
                sleep(wait);
            }
        }catch(java.io.InterruptedIOException interrupted){lease.close();throw interrupted;}
        catch(RuntimeException failure){lease.close();throw failure;}
    }

    private boolean tryClaim(String projectId,String operationKey,String owner,long now,long until){
        return EvidenceLeaseClaimRetry.execute(projectId,operationKey,()->{
            long claimNow=Math.max(now,System.currentTimeMillis());
            return tryClaimOnce(projectId,operationKey,owner,claimNow,safeUntil(claimNow,until-now));
        });
    }
    private boolean tryClaimOnce(String projectId,String operationKey,String owner,long now,long until){
        EvidenceOperationLeaseRow row=new EvidenceOperationLeaseRow();row.operationKey=operationKey;row.projectId=projectId;
        row.owner=owner;row.acquiredAt=now;row.leaseUntil=until;
        try{if(store.mapper().tryInsertEvidenceOperationLease(row)==1)return true;}
        catch(RuntimeException collision){
            // A plain INSERT can still lose a race on databases without INSERT IGNORE. Treat it as
            // contention only when the winning row is visible; genuine database failures propagate.
            if(!duplicateKey(collision))throw collision;
            if(store.mapper().evidenceOperationLease(operationKey)==null)throw collision;
        }
        return store.mapper().takeOverEvidenceOperationLease(operationKey,projectId,owner,now,until)==1;
    }
    private static boolean duplicateKey(Throwable error){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable next=error;next!=null&&seen.add(next);next=next.getCause()){
            if(next instanceof DuplicateKeyException)return true;
            // Direct MyBatis sessions (including offline integration tests) retain the JDBC cause.
            if(next instanceof java.sql.SQLException&&(((java.sql.SQLException) (next)).getErrorCode()==1062||"23505".equals(((java.sql.SQLException) (next)).getSQLState())))return true;
        }
        return false;
    }

    private static long safeUntil(long now,long duration){return Long.MAX_VALUE-now<duration?Long.MAX_VALUE:now+duration;}
    private static void sleep(long millis)throws java.io.InterruptedIOException{
        try{Thread.sleep(millis);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();java.io.InterruptedIOException out=new java.io.InterruptedIOException("证据操作已中断");out.initCause(interrupted);throw out;}
    }

    public static int activeFlights(){return FLIGHTS.size();}
    public static final class Lease implements AutoCloseable{
        private final String key;private final Flight flight;private final Thread owner=Thread.currentThread();private volatile boolean closed;
        private EvidenceOperationRepository repository;private String projectId,durableOwner;
        private volatile long durableUntil;private volatile boolean durableLost,completionObserved;
        private ScheduledFuture<?> renewal;
        private Lease(String key,Flight flight){this.key=key;this.flight=flight;}
        private synchronized void attachDurable(EvidenceOperationRepository repository,String projectId,String owner,long until){
            this.repository=repository;this.projectId=projectId;this.durableOwner=owner;this.durableUntil=until;
        }
        private synchronized void startRenewal(){
            if(repository==null||repository.renewMillis==0||closed)return;
            renewal=RENEWALS.scheduleWithFixedDelay(this::renew,repository.renewMillis,repository.renewMillis,TimeUnit.MILLISECONDS);
        }
        private void renew(){
            EvidenceOperationRepository repo=repository;if(repo==null||closed||durableLost)return;
            long now=System.currentTimeMillis(),until=safeUntil(now,repo.leaseMillis);
            try{
                if(repo.store.mapper().renewEvidenceOperationLease(key,projectId,durableOwner,now,until)==1)durableUntil=until;
                else durableLost=true;
            }catch(RuntimeException unavailable){if(now>=durableUntil)durableLost=true;}
        }
        private void observeCompletion(){completionObserved=true;}
        private synchronized void relinquishDurable(){
            ScheduledFuture<?> scheduled=renewal;if(scheduled!=null)scheduled.cancel(false);renewal=null;
            EvidenceOperationRepository repo=repository;
            if(repo!=null&&durableOwner!=null)try{repo.store.mapper().releaseEvidenceOperationLease(key,projectId,durableOwner);}catch(RuntimeException ignored){}
            repository=null;projectId=null;durableOwner=null;durableUntil=0;
        }
        /** False means the caller must consume the completed durable result instead of executing. */
        public boolean ownsDurableExecution(){return repository!=null&&!durableLost&&!completionObserved&&System.currentTimeMillis()<durableUntil;}
        public boolean completionObserved(){return completionObserved;}
        public void close(){
            if(closed)return;if(Thread.currentThread()!=owner)throw new IllegalMonitorStateException("Evidence lease must close on its acquiring thread");
            closed=true;try{relinquishDurable();}finally{if(flight!=null){flight.lock.unlock();releaseReference(key,flight);}}
        }
    }
    private static void releaseReference(String key,Flight flight){FLIGHTS.compute(key,(ignored,current)->{
        if(current!=flight||current.references<=0)throw new IllegalStateException("EVIDENCE_OPERATION_LEASE_MISMATCH");return --current.references==0?null:current;});}

    private static List<Map<String,Object>> fingerprints(Object raw){
        if(!(raw instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();
        for(Object value:((Collection<?>) (raw))){Map<String,Object> row=value instanceof Map<?,?>?stringMap(((Map<?,?>) (value))):cn.bidcheck.compat.Java8Api.mapOf("value", value);
            Map<String,Object> safe=new TreeMap<>();for(String name:cn.bidcheck.compat.Java8Api.listOf("id", "documentId", "documentHash", "documentVersion", "parserFingerprint", "locationType", "location", "page", "sourceFingerprint", "contentHash"))if(row.containsKey(name))safe.put(name,ordered(row.get(name)));
            Object evidence=row.get("evidence");if(evidence instanceof Map<?,?>){Map<String,Object> ev=stringMap(((Map<?,?>) (evidence)));for(String name:cn.bidcheck.compat.Java8Api.listOf("documentId", "page", "locationType", "anchor"))if(ev.containsKey(name))safe.put("evidence."+name,ordered(ev.get(name)));if(ev.get("quote")!=null)safe.put("evidence.quoteHash",digest(String.valueOf(ev.get("quote"))));}
            for(String name:cn.bidcheck.compat.Java8Api.listOf("quote", "text", "sourceText", "rawValue"))if(row.get(name)!=null)safe.put(name+"Hash",digest(String.valueOf(row.get(name))));
            if(safe.isEmpty())safe.put("valueHash",digest(canonical(value)));out.add(safe);
        }
        out.sort(Comparator.comparing(EvidenceOperationRepository::canonical));return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    private static Map<String,Object> stringMap(Map<?,?> raw){Map<String,Object> out=new LinkedHashMap<>();raw.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    private static Object stableRequested(Object value){
        if(!(value instanceof Collection<?>))return ordered(value);
        List<Object> out=((Collection<?>) (value)).stream().map(EvidenceOperationRepository::ordered).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        out.sort(Comparator.comparing(EvidenceOperationRepository::canonical));return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    private static Object ordered(Object value){
        if(value instanceof Map<?,?>){Map<String,Object> out=new TreeMap<>();((Map<?,?>) (value)).forEach((k,v)->out.put(String.valueOf(k),ordered(v)));return out;}
        if(value instanceof Collection<?>)return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(EvidenceOperationRepository::ordered));
        if(value!=null&&value.getClass().isArray()){int n=java.lang.reflect.Array.getLength(value);List<Object> out=new ArrayList<>(n);for(int i=0;i<n;i++)out.add(ordered(java.lang.reflect.Array.get(value,i)));return out;}
        return value;
    }
    private static String canonical(Object value){try{return JSON.writeValueAsString(ordered(value));}catch(Exception e){throw new IllegalArgumentException("EVIDENCE_OPERATION_VALUE_INVALID",e);}}
    private static String digest(String text){try{return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
    private static String clean(Object value){return cn.bidcheck.compat.Java8Api.strip(Objects.toString(value,""));}
    private static Map<String,Object> linked(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
