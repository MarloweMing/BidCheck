package cn.bidcheck.verification.evidence;

import cn.bidcheck.infrastructure.DatabaseConfiguration;
import cn.bidcheck.persistence.RecordMapper;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class DurableEvidenceOperationLeaseTest {
    @Test void anotherInstanceWaitsForTheImmutableResultInsteadOfBecomingASecondOwner()throws Exception{
        SqlSessionFactory database=database();
        try(SqlSession firstSession=database.openSession(true);SqlSession secondSession=database.openSession(true)){
            EvidenceOperationRepository first=repository(firstSession,false,2_000,0,10);
            EvidenceOperationRepository second=repository(secondSession,false,2_000,0,10);
            Map<String,Object> descriptor=descriptor(),result=result();String key=EvidenceOperationRepository.key(descriptor);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try(EvidenceOperationRepository.Lease owner=first.coordinate("project",key)){
                assertTrue(owner.ownsDurableExecution());
                CountDownLatch entered=new CountDownLatch(1);
                Future<List<Boolean>> follower=worker.submit(()->{entered.countDown();try(cn.bidcheck.verification.evidence.EvidenceOperationRepository.Lease lease=second.coordinate("project",key)){
                    return cn.bidcheck.compat.Java8Api.listOf(lease.ownsDurableExecution(), lease.completionObserved(), second.completed("project",key).isPresent());
                }});
                assertTrue(entered.await(1,TimeUnit.SECONDS));Thread.sleep(80);assertFalse(follower.isDone());
                first.publishCompleted("project",key,descriptor,result);
                assertEquals(cn.bidcheck.compat.Java8Api.listOf(false, true, true),follower.get(2,TimeUnit.SECONDS));
            }finally{worker.shutdownNow();}
            assertNull(firstSession.getMapper(RecordMapper.class).evidenceOperationLease(key));
        }
    }

    @Test void expiredOwnerIsAtomicallyReplacedAndCannotReleaseItsSuccessor()throws Exception{
        SqlSessionFactory database=database();
        try(SqlSession firstSession=database.openSession(true);SqlSession secondSession=database.openSession(true)){
            EvidenceOperationRepository first=repository(firstSession,false,90,0,5);
            EvidenceOperationRepository second=repository(secondSession,false,1_000,0,5);
            String key=EvidenceOperationRepository.key(descriptor());
            EvidenceOperationRepository.Lease stale=first.coordinate("project",key);assertTrue(stale.ownsDurableExecution());
            Thread.sleep(120);
            CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<Boolean> replacement=worker.submit(()->{try(cn.bidcheck.verification.evidence.EvidenceOperationRepository.Lease lease=second.coordinate("project",key)){
                    boolean owns=lease.ownsDurableExecution();acquired.countDown();release.await();return owns;
                }});
                assertTrue(acquired.await(2,TimeUnit.SECONDS));
                stale.close();
                firstSession.clearCache();
                assertNotNull(firstSession.getMapper(RecordMapper.class).evidenceOperationLease(key),"stale owner must not delete replacement lease");
                release.countDown();assertTrue(replacement.get(2,TimeUnit.SECONDS));
                firstSession.clearCache();
                assertNull(firstSession.getMapper(RecordMapper.class).evidenceOperationLease(key));
            }finally{release.countDown();worker.shutdownNow();if(!stale.completionObserved())stale.close();}
        }
    }

    @Test void heartbeatPreventsTakeoverDuringALongProviderCall()throws Exception{
        SqlSessionFactory database=database();
        try(SqlSession firstSession=database.openSession(true);SqlSession secondSession=database.openSession(true)){
            EvidenceOperationRepository first=repository(firstSession,false,120,30,5);
            EvidenceOperationRepository second=repository(secondSession,false,120,30,5);
            Map<String,Object> descriptor=descriptor();String key=EvidenceOperationRepository.key(descriptor);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try(EvidenceOperationRepository.Lease owner=first.coordinate("project",key)){
                Future<Boolean> follower=worker.submit(()->{try(cn.bidcheck.verification.evidence.EvidenceOperationRepository.Lease lease=second.coordinate("project",key)){return lease.ownsDurableExecution();}});
                Thread.sleep(320);assertFalse(follower.isDone(),"renewed live lease must not be taken over");
                first.publishCompleted("project",key,descriptor,result());
                assertFalse(follower.get(2,TimeUnit.SECONDS),"follower must consume completed evidence");
            }finally{worker.shutdownNow();}
        }
    }

    private static EvidenceOperationRepository repository(SqlSession session,boolean local,long ttl,long renew,long poll){
        return new EvidenceOperationRepository(new Store(session.getMapper(RecordMapper.class),new ObjectMapper()),local,ttl,renew,poll);
    }
    private static Map<String,Object> descriptor(){
        return EvidenceOperationRepository.descriptor("project","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),
            cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "source", "documentHash", cn.bidcheck.compat.Java8Api.repeat("a", 64))),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")),
            "requirement","model","FACTS_ONLY","extractor");
    }
    private static Map<String,Object> result(){return cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "source")), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")));}
    private static SqlSessionFactory database()throws Exception{
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:evidence_lease_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setDatabaseIdProvider(new DatabaseConfiguration().databaseIdProvider());
        factory.setMapperLocations(new org.springframework.core.io.Resource[]{new ClassPathResource("mappers/RecordMapper.xml")});return factory.getObject();
    }
}
