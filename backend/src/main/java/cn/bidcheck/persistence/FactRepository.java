package cn.bidcheck.persistence;

import java.util.*;

/** Core immutable storage. Implementations join the caller's transaction; they never replace existing values. */
public interface FactRepository {
    Map<String,Object> get(String occurrenceId);
    default Map<String,Map<String,Object>> getAll(Collection<String> ids) {
        Map<String,Map<String,Object>> values=new LinkedHashMap<>();for(String id:new LinkedHashSet<>(ids)){Map<String,Object> value=get(id);if(!value.isEmpty())values.put(id,value);}return values;
    }
    void insert(Map<String,Object> occurrence);
    /** Bounded caller-owned batches; implementations must retain insert-only semantics. */
    default Set<String> existingIds(Collection<String> ids) {
        Set<String> found=new LinkedHashSet<>();for(String id:ids)if(!get(id).isEmpty())found.add(id);return found;
    }
    default void insertBatch(List<Map<String,Object>> occurrences) { for(Map<String,Object> occurrence:occurrences)insert(occurrence); }
    List<String> nativeIds(String project,String engine,String document,int version,String hash);
    /**
     * Hydrates native occurrences for one immutable document snapshot. SQL implementations should
     * override this with a joined read; the default keeps isolated in-memory fixtures simple.
     */
    default List<Map<String,Object>> nativeRecords(String project,String engine,String document,int version,String hash) {
        return cn.bidcheck.compat.Java8Api.toList(nativeIds(project,engine,document,version,hash).stream().map(this::get));
    }
    default List<Stored> legacyRows(String project,long afterCreatedAt,String afterId,int limit){throw new UnsupportedOperationException("SQL_MIGRATION_REQUIRED");}

    /** Isolated unit tests only. Spring always injects RelationalFactRepository. */
    static FactRepository memoryForTests() {
        return new FactRepository() {
            final Map<String,Map<String,Object>> rows=new LinkedHashMap<>();
            final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked") Map<String,Object> copy(Map<String,Object> x){return json.convertValue(x,LinkedHashMap.class);}
            public Map<String,Object> get(String id){return rows.containsKey(id)?copy(rows.get(id)):cn.bidcheck.compat.Java8Api.mapOf();}
            public void insert(Map<String,Object> row){String id=Objects.toString(row.get("id"),"");if(rows.containsKey(id))throw new IllegalStateException("FACT_ALREADY_EXISTS");rows.put(id,copy(row));}
            public List<String> nativeIds(String p,String e,String d,int v,String h){return cn.bidcheck.compat.Java8Api.toList(rows.values().stream().filter(r->p.equals(r.get("projectId"))&&e.equals(r.get("engineIdentity"))&&"NATIVE_FACT".equals(r.get("sourceDomain"))).filter(r->{Object refs=r.get("sources");return refs instanceof List<?>&&!((List<?>) (refs)).isEmpty()&&((List<?>) (refs)).get(0) instanceof Map<?,?>&&Objects.equals(((Map<?,?>) (((List<?>) (refs)).get(0))).get("documentHash"),h)&&Objects.equals(((Map<?,?>) (((List<?>) (refs)).get(0))).get("documentVersion"),v)&&((Map<?,?>) (((List<?>) (refs)).get(0))).get("evidence") instanceof Map<?,?>&&d.equals(((Map<?,?>) (((Map<?,?>) (((List<?>) (refs)).get(0))).get("evidence"))).get("documentId"));}).map(r->r.get("id").toString()));}
        };
    }
}
