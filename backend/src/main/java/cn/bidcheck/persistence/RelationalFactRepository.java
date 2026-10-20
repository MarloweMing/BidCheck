package cn.bidcheck.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL columns are authoritative for core facts and source locations. No write-through JSON fallback. */
@Repository
public class RelationalFactRepository implements FactRepository {
    private final FactMapper mapper;private final ObjectMapper json;
    public RelationalFactRepository(FactMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    public Map<String,Object> get(String id){
        FactRows.Occurrence row=mapper.getOccurrence(id);if(row==null)return Map.of();
        FactRows.Entity entity=mapper.getEntity(row.entityKey);if(entity==null)throw new IllegalStateException("FACT_ENTITY_MISSING");
        if(!row.projectId.equals(entity.projectId)||!row.engineKey.equals(entity.engineKey))throw new IllegalStateException("FACT_ENTITY_SCOPE_MISMATCH");
        List<Map<String,Object>> sources=new ArrayList<>();for(FactRows.Source s:mapper.sources(id)){
            if(!row.projectId.equals(s.projectId)||!row.engineKey.equals(s.engineKey))throw new IllegalStateException("FACT_SOURCE_SCOPE_MISMATCH");
            sources.add(map("id",s.sourceId,"projectId",s.projectId,"documentHash",s.documentHash,"documentVersion",s.documentVersion,"parserFingerprint",s.parserFingerprint,"sourceFingerprint",s.sourceFingerprint,
                "evidence",map("documentId",s.documentId,"documentName",s.documentName,"page",s.locationNumber,"locationType",s.locationType,"quote",s.quote,"anchor",s.anchor,"confidence",s.confidence)));
        }
        if(sources.isEmpty())throw new IllegalStateException("FACT_EVIDENCE_MISSING");
        Map<String,Object> fact=map("kind",row.kind,"subject",row.subject,"field",row.field,"rawValue",row.rawValue,"value",row.value,"unit",row.unit,"basis",row.basis,"scope",row.scope,"sourceIds",sources.stream().map(s->s.get("id")).toList(),"attributes",object(row.attributesJson));
        if(!row.modelFactId.isBlank())fact.put("id",row.modelFactId);
        Map<String,Object> identity=object(row.entityAuditJson);identity.put("id",entity.entityId);identity.put("type",entity.entityType);identity.put("identityStrength",entity.identityStrength);
        Map<String,Object> result=map("id",id,"occurrenceId",id,"projectId",row.projectId,"engineIdentity",row.engineIdentity,"fact",fact,"sources",sources,"entity",identity,"layoutIdentity",row.layoutIdentity,"createdAt",row.createdAt,"ledgerVersion",row.ledgerVersion,"sourceDomain",row.sourceDomain,"extractionMethod",row.extractionMethod);
        if(Set.of("RELATION","PERSON_ROLE").contains(row.kind)){
            FactRows.Link link=mapper.getLink(id);if(link==null||!row.projectId.equals(link.projectId)||!row.engineKey.equals(link.engineKey))throw new IllegalStateException("FACT_RELATION_MISSING");
            result.put("relation",map("id",link.id,"kind",link.kind,"subject",row.subject,"field",link.predicate,"predicate",link.predicate,"object",link.object,"scope",link.scope,"resolution",link.resolution,"value",row.value,"attributes",object(link.attributesJson),"entityId",entity.entityId));
        }
        return result;
    }
    public Set<String> existingIds(Collection<String> ids){
        if(ids.isEmpty())return Set.of();
        return new LinkedHashSet<>(mapper.existingOccurrenceIds(ids.stream().distinct().toList()));
    }
    /** All lookup state belongs to this call and is discarded before the caller releases its project fence. */
    public void insertBatch(List<Map<String,Object>> values){
        if(values.isEmpty())return;
        List<String> ids=values.stream().map(value->s(value,"id")).toList();
        if(new HashSet<>(ids).size()!=ids.size()||!existingIds(ids).isEmpty())throw new IllegalStateException("FACT_ALREADY_EXISTS");
        Set<String> entityIds=new LinkedHashSet<>(),evidenceIds=new LinkedHashSet<>();
        for(Map<String,Object> value:values){
            String project=s(value,"projectId"),engine=s(value,"engineIdentity");
            entityIds.add(hash(project+"\0"+engine+"\0"+s(m(value.get("entity")),"id")));
            List<Map<String,Object>> sources=maps(value.get("sources"));if(sources.isEmpty())throw new IllegalArgumentException("FACT_EVIDENCE_MISSING");
            for(Map<String,Object> source:sources)evidenceIds.add(hash(project+"\0"+engine+"\0"+s(source,"sourceFingerprint")));
        }
        BatchLookup lookup=new BatchLookup();
        for(FactRows.Entity entity:mapper.getEntities(new ArrayList<>(entityIds)))lookup.entities.put(entity.id,entity);
        for(FactRows.Evidence evidence:mapper.getEvidences(new ArrayList<>(evidenceIds)))lookup.evidences.put(evidence.id,evidence);
        for(Map<String,Object> value:values)insert(value,lookup);
        if(!lookup.attachments.isEmpty())mapper.attachBatch(lookup.attachments);
    }
    private static final class BatchLookup{
        final Map<String,FactRows.Entity> entities=new HashMap<>();
        final Map<String,FactRows.Evidence> evidences=new HashMap<>();
        final List<Map<String,Object>> attachments=new ArrayList<>();
    }
    public void insert(Map<String,Object> value){insert(value,null);}
    private void insert(Map<String,Object> value,BatchLookup lookup){
        String id=s(value,"id"),project=s(value,"projectId"),engine=s(value,"engineIdentity"),engineKey=hash(engine);
        if(lookup==null&&mapper.getOccurrence(id)!=null)throw new IllegalStateException("FACT_ALREADY_EXISTS");
        Map<String,Object> fact=m(value.get("fact")),identity=m(value.get("entity"));List<Map<String,Object>> sources=maps(value.get("sources"));if(sources.isEmpty())throw new IllegalArgumentException("FACT_EVIDENCE_MISSING");
        String entityKey=hash(project+"\0"+engine+"\0"+s(identity,"id"));
        FactRows.Entity entity=lookup==null?mapper.getEntity(entityKey):lookup.entities.get(entityKey);
        if(entity==null){entity=new FactRows.Entity();entity.id=entityKey;entity.entityId=s(identity,"id");entity.projectId=project;entity.engineKey=engineKey;entity.engineIdentity=engine;entity.entityType=s(identity,"type");entity.identityStrength=s(identity,"identityStrength");entity.displayName=s(identity,"displayName");entity.identifiersJson=encode(identity.getOrDefault("identifiers",List.of()));entity.claimsJson=encode(identity.getOrDefault("identityClaims",List.of()));entity.auditJson=encode(identity);entity.contentHash=hash(project+engine+entity.entityId+entity.entityType+entity.identityStrength);mapper.insertEntity(entity);if(lookup!=null)lookup.entities.put(entityKey,entity);}
        else if(!entity.entityId.equals(s(identity,"id"))||!entity.projectId.equals(project)||!entity.engineIdentity.equals(engine)||!entity.entityType.equals(s(identity,"type"))||!entity.identityStrength.equals(s(identity,"identityStrength")))throw new IllegalStateException("ENTITY_IMMUTABILITY_VIOLATION");
        List<String> evidenceIds=new ArrayList<>();
        for(Map<String,Object> src:sources){Map<String,Object> ev=m(src.get("evidence"));String eid=hash(project+"\0"+engine+"\0"+s(src,"sourceFingerprint"));FactRows.Evidence previous=lookup==null?mapper.getEvidence(eid):lookup.evidences.get(eid);
            if(previous==null){FactRows.Evidence row=new FactRows.Evidence();row.id=eid;row.projectId=project;row.engineKey=engineKey;row.documentId=s(ev,"documentId");row.documentName=s(ev,"documentName");row.documentHash=s(src,"documentHash");row.documentVersion=n(src,"documentVersion");row.parserFingerprint=s(src,"parserFingerprint");row.sourceFingerprint=s(src,"sourceFingerprint");row.locationType=s(ev,"locationType");row.locationNumber=n(ev,"page");row.quote=s(ev,"quote");row.anchor=s(ev,"anchor");row.confidence=((Number)ev.getOrDefault("confidence",0)).doubleValue();row.contentHash=hash(encode(map("project",project,"engine",engine,"source",row.sourceFingerprint)));mapper.insertEvidence(row);if(lookup!=null)lookup.evidences.put(eid,row);}
            else if(!project.equals(previous.projectId)||!engineKey.equals(previous.engineKey)||!s(ev,"quote").equals(previous.quote)||!s(src,"parserFingerprint").equals(previous.parserFingerprint)||!s(src,"documentHash").equals(previous.documentHash)||n(src,"documentVersion")!=previous.documentVersion||!s(ev,"documentId").equals(previous.documentId)||n(ev,"page")!=previous.locationNumber||!s(ev,"locationType").equals(previous.locationType)||!s(ev,"anchor").equals(previous.anchor)||Double.compare(((Number)ev.getOrDefault("confidence",0)).doubleValue(),previous.confidence)!=0)throw new IllegalStateException("EVIDENCE_IMMUTABILITY_VIOLATION");
            evidenceIds.add(eid);
        }
        FactRows.Occurrence row=new FactRows.Occurrence();row.id=id;row.projectId=project;row.engineKey=engineKey;row.engineIdentity=engine;row.entityKey=entityKey;row.kind=s(fact,"kind");row.subject=s(fact,"subject");row.field=s(fact,"field");row.fieldHash=hash(row.field);row.rawValue=s(fact,"rawValue");row.value=s(fact,"value");row.unit=s(fact,"unit");row.basis=s(fact,"basis");row.scope=s(fact,"scope");row.modelFactId=s(fact,"id");row.attributesJson=encode(fact.getOrDefault("attributes",Map.of()));row.entityAuditJson=encode(identity);row.layoutIdentity=s(value,"layoutIdentity");row.createdAt=s(value,"createdAt");row.ledgerVersion=s(value,"ledgerVersion");Map<String,Object> first=sources.get(0);row.documentId=s(m(first.get("evidence")),"documentId");row.documentVersion=n(first,"documentVersion");row.documentHash=s(first,"documentHash");row.sourceDomain=s(value,"sourceDomain").isBlank()?"STRUCTURED_FACT":s(value,"sourceDomain");row.extractionMethod=s(value,"extractionMethod").isBlank()?"SOURCE_GROUNDED":s(value,"extractionMethod");row.contentHash=hash(encode(value));mapper.insertOccurrence(row);
        for(int i=0;i<sources.size();i++){if(lookup==null)mapper.attach(id,evidenceIds.get(i),s(sources.get(i),"id"),i);else lookup.attachments.add(map("occurrenceId",id,"evidenceId",evidenceIds.get(i),"sourceId",s(sources.get(i),"id"),"sourceOrder",i));}
        if(Set.of("RELATION","PERSON_ROLE").contains(row.kind)){FactRows.Link link=new FactRows.Link();link.id=id;link.occurrenceId=id;link.projectId=project;link.engineKey=engineKey;link.subjectEntityKey=entityKey;link.kind=row.kind;link.predicate=row.field;link.predicateHash=row.fieldHash;link.object=row.kind.equals("RELATION")?s(m(fact.get("attributes")),"object"):row.value;link.scope=row.scope;link.resolution="SOURCE_BOUND";link.attributesJson=row.attributesJson;mapper.insertLink(link);}
    }
    public List<String> nativeIds(String project,String engine,String document,int version,String hash){return mapper.nativeIds(project,hash(engine),document,version,hash);}
    public List<Map<String,Object>> nativeRecords(String project,String engine,String document,int version,String hash){
        List<FactRows.NativeFact> rows=mapper.nativeFacts(project,hash(engine),document,version,hash);
        List<Map<String,Object>> values=new ArrayList<>(rows.size());String previous="";
        for(FactRows.NativeFact row:rows){
            // Native extraction writes exactly one source per fact. Refuse malformed cardinality
            // instead of silently dropping provenance while taking the fast read path.
            if(row.occurrenceId==null||row.occurrenceId.isBlank()||row.occurrenceId.equals(previous)||row.sourceOrder!=0)
                throw new IllegalStateException("NATIVE_FACT_SOURCE_CARDINALITY_INVALID");
            previous=row.occurrenceId;Map<String,Object> attributes=object(row.attributesJson);
            Map<String,Object> fact=map("field",row.field,"rawValue",row.rawValue,"value",row.value,"attributes",attributes);
            Map<String,Object> evidence=map("documentId",row.documentId,"documentName",row.documentName,"page",row.locationNumber,
                "locationType",row.locationType,"quote",row.quote,"anchor",row.anchor,"confidence",row.confidence);
            values.add(map("id",row.occurrenceId,"occurrenceId",row.occurrenceId,"fact",fact,
                "sources",List.of(map("id",row.sourceId,"projectId",row.projectId,"evidence",evidence,
                    "documentHash",row.documentHash,"documentVersion",row.documentVersion,
                    "parserFingerprint",row.parserFingerprint,"sourceFingerprint",row.sourceFingerprint))));
        }
        return List.copyOf(values);
    }
    public List<Stored> legacyRows(String project,long afterCreatedAt,String afterId,int limit){if(limit<1||limit>26)throw new IllegalArgumentException("LEGACY_PAGE_LIMIT");return mapper.legacyRows(project,afterCreatedAt,afterId,limit);}
    private String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception e){throw new IllegalArgumentException("FACT_JSON_INVALID");}}
    private Map<String,Object> object(String x){try{return json.readValue(x,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("FACT_JSON_UNREADABLE");}}
    @SuppressWarnings("unchecked") private static Map<String,Object> m(Object x){return x instanceof Map<?,?>?(Map<String,Object>)x:Map.of();}
    private static List<Map<String,Object>> maps(Object x){if(!(x instanceof List<?> l))return List.of();return l.stream().map(RelationalFactRepository::m).toList();}
    private static String s(Map<String,Object> m,String k){return Objects.toString(m.get(k),"");}
    private static int n(Map<String,Object> m,String k){return ((Number)m.get(k)).intValue();}
    private static Map<String,Object> map(Object... args){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)m.put(args[i].toString(),args[i+1]);return m;}
    public static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
