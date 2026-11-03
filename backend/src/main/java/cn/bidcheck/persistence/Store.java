package cn.bidcheck.persistence;
import cn.bidcheck.compat.HexFormat;

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
        if("document".equals(kind) && value instanceof Document) saveSummary(((Document) (value)));
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class ImmutableInsertResult {
        private final boolean inserted;
        private final String contentDigest;
        @com.fasterxml.jackson.annotation.JsonCreator
        public ImmutableInsertResult(@com.fasterxml.jackson.annotation.JsonProperty("inserted") boolean inserted, @com.fasterxml.jackson.annotation.JsonProperty("contentDigest") String contentDigest) { this.inserted = inserted; this.contentDigest = contentDigest; }
        public boolean inserted() { return inserted; }
        public String contentDigest() { return contentDigest; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ImmutableInsertResult that = (ImmutableInsertResult) other; return java.util.Objects.equals(this.inserted, that.inserted) && java.util.Objects.equals(this.contentDigest, that.contentDigest); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.inserted); hash = 31 * hash + java.util.Objects.hashCode(this.contentDigest); return hash; }
        @Override public String toString() { return "ImmutableInsertResult[" + "inserted=" + inserted + ", contentDigest=" + contentDigest + "]"; }
}
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
        summary.put("readableText",d.pages().stream().anyMatch(p->p.text()!=null&&!cn.bidcheck.compat.Java8Api.isBlank(p.text())));
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
    /** Bounded reads preserve kind and project isolation; missing rows are never cached. */
    public Map<String,Map<String,Object>> mapsByIds(String kind,String project,Collection<String> ids) {
        List<String> keys=cn.bidcheck.compat.Java8Api.toList(ids.stream().filter(Objects::nonNull).distinct());Map<String,Map<String,Object>> out=new LinkedHashMap<>();
        for(int at=0;at<keys.size();at+=100){List<String> part=keys.subList(at,Math.min(keys.size(),at+100));
            if(mapper==null){for(String id:part)try{Map<String,Object> value=getMap(kind,id);if(project==null||!value.containsKey("projectId")||project.equals(value.get("projectId")))out.put(id,value);}catch(ResponseStatusException missing){if(missing.getStatus().value()!=404)throw missing;}}
            else for(Stored row:mapper.getMany(part))if(kind.equals(row.kind)&&(project==null||project.equals(row.projectId)))out.put(row.id,map(row.body));
        }return out;
    }
    public Map<String,List<Map<String,Object>>> mapsByParents(String kind,String project,Collection<String> parents) {
        List<String> keys=cn.bidcheck.compat.Java8Api.toList(parents.stream().filter(Objects::nonNull).distinct());Map<String,List<Map<String,Object>>> out=new LinkedHashMap<>();
        for(int at=0;at<keys.size();at+=100){List<String> part=keys.subList(at,Math.min(keys.size(),at+100));
            if(mapper==null)for(String parent:part)out.put(parent,maps(kind,project,parent));
            else for(Stored row:mapper.listParents(kind,project,part))out.computeIfAbsent(row.parentId,k->new ArrayList<>()).add(map(row.body));
        }return out;
    }
    /** Caller owns the same transaction/project fence as insertNew. Different parents may be batched. */
    public void insertRows(List<Stored> rows) {
        if(mapper==null){for(Stored row:rows)save(row.kind,row.id,row.projectId,row.parentId,map(row.body));return;}
        for(int at=0;at<rows.size();at+=100)mapper.insertRecords(rows.subList(at,Math.min(rows.size(),at+100)));
    }
    public Stored newRow(String kind,String id,String project,String parent,Object value){
        Stored row=new Stored();row.id=id;row.kind=kind;row.projectId=project;row.parentId=parent==null?"":parent;row.body=StoredBodyCodec.encode(kind,encode(value));row.createdAt=System.currentTimeMillis();return row;
    }
    public <T> List<T> list(String kind,String project,String parent,Class<T> type) {
        return cn.bidcheck.compat.Java8Api.toList(mapper.list(kind,project,parent).stream().map(x->decode(x.body,type)));
    }
    public List<Map<String,Object>> maps(String kind,String project,String parent) {
        return cn.bidcheck.compat.Java8Api.toList(mapper.list(kind,project,parent).stream().map(x->map(x.body)));
    }
}
