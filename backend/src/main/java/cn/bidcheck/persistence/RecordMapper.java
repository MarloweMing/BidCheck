package cn.bidcheck.persistence;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecordMapper {
    void save(Stored record);
    int insertRecord(Stored record);
    void insertRecords(@Param("records") List<Stored> records);
    Stored get(@Param("id") String id);
    Stored getForUpdate(@Param("id") String id);
    List<Stored> list(@Param("kind") String kind, @Param("projectId") String projectId, @Param("parentId") String parentId);
    long count(@Param("kind") String kind, @Param("projectId") String projectId, @Param("parentId") String parentId);
    Stored latest(@Param("kind") String kind, @Param("projectId") String projectId, @Param("parentId") String parentId);
    void deleteChildren(@Param("kind") String kind, @Param("parentId") String parentId);
    void insertTask(TaskRow task);
    TaskRow task(@Param("id") String id);
    TaskRow lockTask(@Param("id") String id);
    List<TaskRow> pendingTasks();
    List<TaskRow> projectTasks(@Param("projectId") String projectId);
    int claimTask(@Param("id") String id, @Param("now") String now, @Param("owner") String owner, @Param("leaseUntil") long leaseUntil);
    int updateTask(TaskRow task);
    int recoverTasks(@Param("expiredBefore") long expiredBefore, @Param("now") String now);
    int renewLease(@Param("id") String id, @Param("owner") String owner, @Param("now") long now, @Param("leaseUntil") long leaseUntil);
    int releaseClaim(@Param("id") String id, @Param("owner") String owner, @Param("now") String now);
    int clearLease(@Param("id") String id, @Param("owner") String owner);
    int failExhaustedTask(@Param("id") String id, @Param("now") String now);
    int retryTask(@Param("id") String id, @Param("now") String now);
    int tryInsertEvidenceOperationLease(EvidenceOperationLeaseRow lease);
    EvidenceOperationLeaseRow evidenceOperationLease(@Param("operationKey") String operationKey);
    int takeOverEvidenceOperationLease(@Param("operationKey") String operationKey, @Param("projectId") String projectId,
        @Param("owner") String owner, @Param("now") long now, @Param("leaseUntil") long leaseUntil);
    int renewEvidenceOperationLease(@Param("operationKey") String operationKey, @Param("projectId") String projectId,
        @Param("owner") String owner, @Param("now") long now, @Param("leaseUntil") long leaseUntil);
    int releaseEvidenceOperationLease(@Param("operationKey") String operationKey, @Param("projectId") String projectId,
        @Param("owner") String owner);
}
