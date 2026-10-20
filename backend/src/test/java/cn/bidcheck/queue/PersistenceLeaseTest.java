package cn.bidcheck.queue;

import cn.bidcheck.infrastructure.DatabaseConfiguration;
import cn.bidcheck.persistence.*;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class PersistenceLeaseTest {
    private SqlSession database() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:lease-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setDatabaseIdProvider(new DatabaseConfiguration().databaseIdProvider());
        factory.setMapperLocations(new ClassPathResource("mappers/RecordMapper.xml"));
        return factory.getObject().openSession(true);
    }
    private String pending(RecordMapper mapper) {
        TaskRow t=new TaskRow();t.id=UUID.randomUUID().toString();t.projectId="project";t.taskType="CHECK";t.inputJson="{}";t.status="PENDING";t.stage="等待";t.createdAt="now";t.updatedAt="now";
        mapper.insertTask(t);return t.id;
    }
    private void expire(SqlSession session,String id) throws Exception {
        try(var sql=session.getConnection().prepareStatement("UPDATE qc_task SET lease_until=? WHERE id=?")) { sql.setLong(1,System.currentTimeMillis()-1);sql.setString(2,id);sql.executeUpdate(); }
        session.clearCache();
    }
    private Stored record(String id,String kind,String projectId,String parentId,long createdAt) {
        Stored record=new Stored();record.id=id;record.kind=kind;record.projectId=projectId;record.parentId=parentId;record.createdAt=createdAt;record.body="{\"id\":\""+id+"\"}";return record;
    }
    @Test void expiryRecoveryPreservesLiveWorkerAndRecoversOnlyExpiredClaim() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);String expired=pending(mapper),live=pending(mapper);long now=System.currentTimeMillis();
            assertThat(mapper.claimTask(expired,"now","worker-a",now+60000)).isEqualTo(1);expire(session,expired);
            assertThat(mapper.claimTask(live,"now","worker-b",now+60000)).isEqualTo(1);
            assertThat(mapper.recoverTasks(System.currentTimeMillis(),"later")).isEqualTo(1);
            assertThat(mapper.task(expired).status).isEqualTo("PENDING");assertThat(mapper.task(expired).leaseOwner).isNull();
            assertThat(mapper.task(live).status).isEqualTo("RUNNING");assertThat(mapper.task(live).leaseOwner).isEqualTo("worker-b");
        }
    }
    @Test void duplicateClaimsAndStaleWorkerCompletionAreFenced() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);String id=pending(mapper);long now=System.currentTimeMillis();
            assertThat(mapper.claimTask(id,"now","old",now+60000)).isEqualTo(1);expire(session,id);TaskRow stale=mapper.task(id);
            stale.status="SUCCEEDED";assertThat(mapper.updateTask(stale)).as("过期租约在回收前也不能写终态").isZero();
            assertThat(mapper.claimTask(id,"now","duplicate",now+60000)).isZero();
            mapper.recoverTasks(System.currentTimeMillis(),"recover");assertThat(mapper.claimTask(id,"later","new",now+60000)).isEqualTo(1);
            stale.status="SUCCEEDED";assertThat(mapper.updateTask(stale)).isZero();
            assertThat(mapper.task(id).status).isEqualTo("RUNNING");assertThat(mapper.task(id).leaseOwner).isEqualTo("new");
            assertThat(mapper.renewLease(id,"old",now,now+120000)).isZero();
            assertThat(mapper.renewLease(id,"new",now,now+120000)).isEqualTo(1);
        }
    }
    @Test void userRetryClearsLeaseAndCanBeClaimedByAnotherWorker() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);String id=pending(mapper);long deadline=System.currentTimeMillis()+60000;
            mapper.claimTask(id,"now","old",deadline);TaskRow failed=mapper.task(id);failed.status="FAILED";assertThat(mapper.updateTask(failed)).isEqualTo(1);
            assertThat(mapper.retryTask(id,"retry")).isEqualTo(1);
            assertThat(mapper.task(id).leaseOwner).isNull();assertThat(mapper.task(id).leaseUntil).isNull();
            assertThat(mapper.claimTask(id,"later","new",deadline)).isEqualTo(1);
            assertThat(mapper.retryTask(id,"duplicate retry")).as("第二次重试不能重置已经RUNNING的任务").isZero();
            assertThat(mapper.task(id).leaseOwner).isEqualTo("new");
        }
    }
    @Test void repeatedCrashesStopAfterThreeAttemptsAndCannotBeClaimedAgain() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);String id=pending(mapper);
            for(int attempt=1;attempt<=3;attempt++) {
                assertThat(mapper.claimTask(id,"now","owner-"+attempt,System.currentTimeMillis()+60000)).isEqualTo(1);
                expire(session,id);mapper.recoverTasks(System.currentTimeMillis(),"recover");
            }
            assertThat(mapper.claimTask(id,"later","fourth",System.currentTimeMillis()+60000)).isZero();
            assertThat(mapper.failExhaustedTask(id,"failed")).isEqualTo(1);
            assertThat(mapper.task(id).status).isEqualTo("FAILED");assertThat(mapper.task(id).attempt).isEqualTo(3);
            assertThat(mapper.retryTask(id,"retry")).isZero();assertThat(mapper.failExhaustedTask(id,"again")).isZero();
        }
    }
    @Test void returningAnUnstartedClaimPreservesAttemptBudgetAndFencesOldOwner() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);String id=pending(mapper);long deadline=System.currentTimeMillis()+60000;
            mapper.claimTask(id,"now","first",deadline);assertThat(mapper.task(id).attempt).isEqualTo(1);
            assertThat(mapper.releaseClaim(id,"first","rejected")).isEqualTo(1);assertThat(mapper.task(id).attempt).isZero();
            mapper.claimTask(id,"now","second",deadline);
            assertThat(mapper.releaseClaim(id,"first","stale refund")).isZero();assertThat(mapper.task(id).attempt).isEqualTo(1);
            assertThat(mapper.task(id).leaseOwner).isEqualTo("second");
        }
    }
    @Test void delayedHeartbeatCannotResurrectAnExpiredLeaseUsingOldTimestamp() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);String id=pending(mapper);long staleNow=System.currentTimeMillis()-10000;
            mapper.claimTask(id,"now","owner",System.currentTimeMillis()+60000);expire(session,id);
            assertThat(mapper.renewLease(id,"owner",staleNow,System.currentTimeMillis()+60000)).isZero();
            assertThat(mapper.claimTask(pending(mapper),"now","owner",System.currentTimeMillis()-1)).isZero();
        }
    }
    @Test void batchInsertAndSummaryQueriesPreserveProjectAndParentScope() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);
            mapper.insertRecords(List.of(record("old","fact","project-a","doc-a",1),record("new","fact","project-a","doc-a",2),record("other-document","fact","project-a","doc-b",3),record("other-project","fact","project-b","doc-a",4),record("other-kind","snapshot","project-a","doc-a",5)));
            assertThat(mapper.count("fact","project-a","doc-a")).isEqualTo(2);
            assertThat(mapper.count("fact","project-a",null)).isEqualTo(3);
            assertThat(mapper.count("fact",null,null)).isEqualTo(4);
            assertThat(mapper.latest("fact","project-a","doc-a").id).isEqualTo("new");
            assertThat(mapper.latest("fact","project-a",null).id).isEqualTo("other-document");
            assertThat(mapper.latest("fact","missing",null)).isNull();
            assertThat(mapper.getForUpdate("new").body).isEqualTo("{\"id\":\"new\"}");
        }
    }
    @Test void batchCollisionCannotOverwriteExistingEvidenceOrPartiallyInsert() throws Exception {
        try(SqlSession session=database()) {
            RecordMapper mapper=session.getMapper(RecordMapper.class);Stored existing=record("evidence","fact","project","doc",1);
            mapper.insertRecords(List.of(existing));Stored replacement=record("evidence","snapshot","other","other",2);
            assertThatThrownBy(()->mapper.insertRecords(List.of(record("must-rollback","fact","project","doc",2),replacement))).isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
            assertThat(mapper.get("must-rollback")).isNull();
            assertThat(mapper.get("evidence").kind).isEqualTo("fact");
            assertThat(mapper.count("fact","project","doc")).isEqualTo(1);
        }
    }
}
