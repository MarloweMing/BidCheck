package cn.bidcheck.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class Store {
    public final RecordMapper mapper;
    public final ObjectMapper json;
    public Store(RecordMapper mapper, ObjectMapper json) { this.mapper=mapper; this.json=json; }
    public RecordMapper mapper() { return mapper; }
    public static String id() { return UUID.randomUUID().toString(); }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("数据序列化失败",e); }
    }
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(StoredBodyCodec.decode(value),type); } catch (JsonProcessingException e) { throw new IllegalStateException("存储记录不可读",e); }
    }
    public Map<String,Object> map(String value) {
        try { return json.readValue(StoredBodyCodec.decode(value),new TypeReference<LinkedHashMap<String,Object>>(){}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("存储记录不可读",e); }
    }
    public void save(String kind,String id,String project,String parent,Object value) {
        Stored previous=mapper.get(id); Stored row=new Stored(); row.id=id; row.kind=kind;
        row.projectId=project; row.parentId=parent==null?"":parent; row.body=StoredBodyCodec.encode(kind,encode(value));
        row.createdAt=previous==null?System.currentTimeMillis():previous.createdAt; mapper.save(row);
        if("document".equals(kind) && value instanceof Document document) saveSummary(document);
    }
    public record ImmutableInsertResult(boolean inserted,String contentDigest) {}
    /**
     * Publishes an immutable record exactly once. A repeated publication is accepted only when
     * identity, scope and encoded content are byte-for-byte identical; mutable save semantics are
     * deliberately unavailable here.
     */
    public ImmutableInsertResult insertImmutableIfAbsent(String kind,String id,String project,String parent,Object value) {
        String effectiveParent=parent==null?"":parent;
        String body=StoredBodyCodec.encode(kind,encode(value));
        String digest=digest(kind,project,effectiveParent,body);
        Stored row=new Stored();row.id=id;row.kind=kind;row.projectId=project;row.parentId=effectiveParent;row.body=body;row.createdAt=System.currentTimeMillis();
        try {
            mapper.insertRecord(row);
            return new ImmutableInsertResult(true,digest);
        } catch(DuplicateKeyException duplicate) {
            Stored existing=mapper.get(id);
            if(existing!=null&&kind.equals(existing.kind)&&Objects.equals(project,existing.projectId)
                    &&effectiveParent.equals(existing.parentId)&&digest.equals(digest(existing.kind,existing.projectId,existing.parentId,existing.body)))
                return new ImmutableInsertResult(false,digest);
            throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");
        }
    }
    private static String digest(String kind,String project,String parent,String body) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((kind+'\n'+project+'\n'+parent+'\n'+body).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("CONTENT_DIGEST_UNAVAILABLE",impossible); }
    }
    /** Immutable encoded content, bound to its destination and the Store instance that prepared it. */
    public static final class PreparedRecord {
        private final Store owner;private final String kind,id,project,parent,body;
        private PreparedRecord(Store owner,String kind,String id,String project,String parent,String body) {
            this.owner=owner;this.kind=kind;this.id=id;this.project=project;this.parent=parent;this.body=body;
        }
    }
    public PreparedRecord prepareRecord(String kind,String id,String project,String parent,Object value) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RECORD_PREPARATION_REQUIRES_NO_TRANSACTION");
        if("document".equals(kind))throw new IllegalArgumentException("DOCUMENT_REQUIRES_SUMMARY_SAVE");
        return new PreparedRecord(this,kind,id,project,parent==null?"":parent,StoredBodyCodec.encode(kind,encode(value)));
    }
    /** No JSON encoding or compression occurs while the caller holds its final publication fence. */
    public void savePrepared(PreparedRecord prepared) {
        if(prepared==null||prepared.owner!=this)throw new IllegalArgumentException("RECORD_PREPARATION_OWNER_INVALID");
        Stored previous=mapper.get(prepared.id);
        if(previous!=null&&(!prepared.kind.equals(previous.kind)||!Objects.equals(prepared.project,previous.projectId)||!prepared.parent.equals(previous.parentId)))
            throw new IllegalStateException("PREPARED_RECORD_SCOPE_MISMATCH");
        Stored row=new Stored();row.id=prepared.id;row.kind=prepared.kind;row.projectId=prepared.project;row.parentId=prepared.parent;row.body=prepared.body;
        row.createdAt=previous==null?System.currentTimeMillis():previous.createdAt;mapper.save(row);
    }
    private void saveSummary(Document d) {
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("id",d.id()); summary.put("projectId",d.projectId()); summary.put("role",d.role());
        summary.put("name",d.name()); summary.put("version",d.version()); summary.put("sha256",d.sha256());
        summary.put("createdAt",d.createdAt()); summary.put("warnings",d.warnings());
        summary.put("pageCount",d.pages().size()); summary.put("locationType",d.pages().isEmpty()?"BLOCK":d.pages().get(0).locationType());
        summary.put("readableText",d.pages().stream().anyMatch(p->p.text()!=null&&!p.text().isBlank()));
        summary.put("contentIncluded",false);
        summary.put("ocrPages",d.pages().stream().filter(p->p.parser()!=null&&p.parser().contains("OCR")).count());
        summary.put("ocrIncomplete",d.warnings().stream().anyMatch(w->w.contains("[OCR未完成]")));
        summary.put("ocrCoverage",cn.bidcheck.engine.OcrPresentation.coverage(d));
        save("document_summary","summary:"+d.id(),d.projectId(),"",summary);
    }
    /** Older installations are backfilled once; polling never deserializes document bodies afterwards. */
    public List<Map<String,Object>> documentSummaries(String project) {
        List<Map<String,Object>> summaries=maps("document_summary",project,null);
        if(summaries.size()<mapper.count("document",project,null)) {
            Set<String> known=new HashSet<>(); summaries.forEach(s->known.add((String)s.get("id")));
            for(Document document:list("document",project,null,Document.class)) if(!known.contains(document.id())) saveSummary(document);
            summaries=maps("document_summary",project,null);
        }
        return summaries;
    }
    public Stored lock(String kind,String id) {
        Stored row=mapper.getForUpdate(id);
        if(row==null || !kind.equals(row.kind)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在");
        return row;
    }
    public <T> void insertNew(String kind,String project,String parent,List<T> values,java.util.function.Function<T,String> identity) {
        List<Stored> batch=new ArrayList<>(100); long created=System.currentTimeMillis();
        for(T value:values) {
            Stored row=new Stored(); row.id=identity.apply(value); row.kind=kind; row.projectId=project;
            row.parentId=parent; row.body=StoredBodyCodec.encode(kind,encode(value)); row.createdAt=created++; batch.add(row);
            if(batch.size()==100) { mapper.insertRecords(batch); batch.clear(); }
        }
        if(!batch.isEmpty()) mapper.insertRecords(batch);
    }
    public Stored require(String kind,String id) {
        Stored row=mapper.get(id);
        if(row==null || !kind.equals(row.kind)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在");
        return row;
    }
    public <T> T get(String kind,String id,Class<T> type) { return decode(require(kind,id).body,type); }
    public Map<String,Object> getMap(String kind,String id) { return map(require(kind,id).body); }
    public <T> List<T> list(String kind,String project,String parent,Class<T> type) {
        return mapper.list(kind,project,parent).stream().map(x->decode(x.body,type)).toList();
    }
    public List<Map<String,Object>> maps(String kind,String project,String parent) {
        return mapper.list(kind,project,parent).stream().map(x->map(x.body)).toList();
    }
}
