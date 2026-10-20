package cn.bidcheck.persistence;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface FactMapper {
 FactRows.Entity getEntity(@Param("id") String id);
 List<FactRows.Entity> getEntities(@Param("ids") List<String> ids);
 void insertEntity(FactRows.Entity row);
 FactRows.Occurrence getOccurrence(@Param("id") String id);
 List<String> existingOccurrenceIds(@Param("ids") List<String> ids);
 void insertOccurrence(FactRows.Occurrence row);
 FactRows.Evidence getEvidence(@Param("id") String id);
 List<FactRows.Evidence> getEvidences(@Param("ids") List<String> ids);
 void insertEvidence(FactRows.Evidence row);
 FactRows.Link getLink(@Param("id") String id);
 void insertLink(FactRows.Link row);
 List<FactRows.Source> sources(@Param("id") String occurrenceId);
 void attach(@Param("occurrenceId") String occurrenceId,@Param("evidenceId") String evidenceId,@Param("sourceId") String sourceId,@Param("sourceOrder") int sourceOrder);
 void attachBatch(@Param("rows") List<java.util.Map<String,Object>> rows);
 List<String> nativeIds(@Param("project") String project,@Param("engine") String engine,@Param("document") String document,@Param("version") int version,@Param("hash") String hash);
 List<FactRows.NativeFact> nativeFacts(@Param("project") String project,@Param("engine") String engine,@Param("document") String document,@Param("version") int version,@Param("hash") String hash);
 List<Stored> legacyRows(@Param("project") String project,@Param("afterCreatedAt") long afterCreatedAt,@Param("afterId") String afterId,@Param("limit") int limit);
}
