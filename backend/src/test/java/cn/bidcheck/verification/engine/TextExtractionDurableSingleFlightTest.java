package cn.bidcheck.verification.engine;

import cn.bidcheck.infrastructure.DatabaseConfiguration;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.persistence.RecordMapper;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.verification.evidence.EvidenceOperationRepository;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import cn.bidcheck.verification.engine.VerificationTypes.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the text path with separate process registries and separate database sessions. */
class TextExtractionDurableSingleFlightTest {
    @Test void secondInstanceWaitsThenReusesTheFirstInstancesDurableObservation()throws Exception{
        SqlSessionFactory database=database();
        try(SqlSession firstSession=database.openSession(true);SqlSession secondSession=database.openSession(true)){
            EvidenceOperationRepository firstRepository=independent(new Store(firstSession.getMapper(RecordMapper.class),new ObjectMapper()));
            EvidenceOperationRepository secondRepository=independent(new Store(secondSession.getMapper(RecordMapper.class),new ObjectMapper()));
            EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc("durable-text","p",1,"报价：80元")));
            Rule rule=rule();Fact fact=new Fact("fact","NUMBER","","amount","80","80","元","报价","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
            SharedFactReuse first=new SharedFactReuse(corpus,context(firstRepository),"model","engine","instance-a:");
            SharedFactReuse second=new SharedFactReuse(corpus,context(secondRepository),"model","engine","instance-b:");
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try(SharedFactReuse.Lease owner=first.coordinate(cn.bidcheck.compat.Java8Api.listOf(rule),corpus.all)){
                Future<Map<String,Object>> follower=worker.submit(()->{try(cn.bidcheck.verification.engine.SharedFactReuse.Lease ignored=second.coordinate(cn.bidcheck.compat.Java8Api.listOf(rule),corpus.all)){
                    List<Fact> reused=second.lookup(cn.bidcheck.compat.Java8Api.listOf(rule),corpus.all,cn.bidcheck.compat.Java8Api.listOf());
                    boolean noExtraction=second.extractionRules(cn.bidcheck.compat.Java8Api.listOf(rule),corpus.all,new RuleEvaluator(reused,corpus,cn.bidcheck.compat.Java8Api.mapOf())).isEmpty();
                    return cn.bidcheck.compat.Java8Api.mapOf("facts", reused.size(), "noExtraction", noExtraction);
                }});
                assertThrows(TimeoutException.class,()->follower.get(100,TimeUnit.MILLISECONDS),"separate instance must wait on the database lease");
                first.publish(cn.bidcheck.compat.Java8Api.listOf(fact),corpus.all,cn.bidcheck.compat.Java8Api.listOf(rule),cn.bidcheck.compat.Java8Api.listOf(),true,"EXTRACTED");
                owner.close();
                assertEquals(cn.bidcheck.compat.Java8Api.mapOf("facts", 1, "noExtraction", true),follower.get(3,TimeUnit.SECONDS));
            }finally{worker.shutdownNow();}
        }
    }

    private static Map<String,Object> context(EvidenceOperationRepository repository){
        Map<String,Object> context=new HashMap<>();context.put("evidenceOperationRepository",repository);
        context.put("evidenceOperationLookup",(Function<Map<String,Object>,Map<String,Object>>)request->repository.completed("p",String.valueOf(request.get("operationKey"))).orElse(cn.bidcheck.compat.Java8Api.mapOf()));
        context.put("sharedFactPublisher",(Consumer<Map<String,Object>>)batch->{
            if(!"EXTRACTED".equals(batch.get("origin"))||!(batch.get("evidenceOperationDescriptor") instanceof Map<?,?>))return;
            Map<String,Object> descriptor=new ObjectMapper().convertValue(((Map<?,?>) (batch.get("evidenceOperationDescriptor"))),Map.class);String key=String.valueOf(batch.get("evidenceOperationKey"));
            repository.publishCompleted("p",key,descriptor,cn.bidcheck.compat.Java8Api.mapOf("facts", batch.getOrDefault("facts",cn.bidcheck.compat.Java8Api.listOf()), "sources", batch.getOrDefault("sources",cn.bidcheck.compat.Java8Api.listOf()), "fieldCoverage", batch.getOrDefault("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf())));
        });return context;
    }
    private static Document doc(String id,String project,int version,String text){
        return new Document(id,project,"BID",id+".pdf",version,"hash-"+id,"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());
    }
    private static Rule rule(){return new Rule("O1","报价","NUMBER","","amount","<=","100","元",cn.bidcheck.compat.Java8Api.mapOf(),"100","",cn.bidcheck.compat.Java8Api.listOf());}

    @SuppressWarnings("unchecked") private static EvidenceOperationRepository independent(Store store)throws Exception{
        Constructor<EvidenceOperationRepository> constructor=EvidenceOperationRepository.class.getDeclaredConstructor(Store.class,boolean.class,long.class,long.class,long.class);
        constructor.setAccessible(true);return constructor.newInstance(store,false,2_000L,0L,5L);
    }
    private static SqlSessionFactory database()throws Exception{
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:text_evidence_lease_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setDatabaseIdProvider(new DatabaseConfiguration().databaseIdProvider());
        factory.setMapperLocations(new org.springframework.core.io.Resource[]{new ClassPathResource("mappers/RecordMapper.xml")});return factory.getObject();
    }
}
