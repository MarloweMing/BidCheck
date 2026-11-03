package cn.bidcheck.verification.graph;

import cn.bidcheck.persistence.RecordMapper;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoreEvidenceGraphRepositoryTest {
    @Test void graphRevisionSurvivesRepositoryRestartAndKeepsProjectIsolation()throws Exception{
        org.apache.ibatis.datasource.unpooled.UnpooledDataSource ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:graph_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection connection=ds.getConnection();java.io.InputStreamReader reader=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(connection,reader);}
        Configuration configuration=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));
        try(InputStream input=resource("mappers/RecordMapper.xml")){new XMLMapperBuilder(input,configuration,"RecordMapper.xml",configuration.getSqlFragments()).parse();}
        try(SqlSession session=new SqlSessionFactoryBuilder().build(configuration).openSession(false)){
            ObjectMapper json=new ObjectMapper();Store store=new Store(session.getMapper(RecordMapper.class),json);StoreEvidenceGraphRepository first=new StoreEvidenceGraphRepository(store);
            String revision="egraph:"+cn.bidcheck.compat.Java8Api.repeat("1", 40),entityA="person:a",entityB="project:b";
            cn.bidcheck.verification.graph.EvidenceGraph.SourceRef source=new EvidenceGraph.SourceRef("s","fp","bid",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"parser","PAGE",1,"项目经理 张三","",1);
            java.util.List<cn.bidcheck.verification.graph.EvidenceGraph.Entity> entities=cn.bidcheck.compat.Java8Api.listOf(new EvidenceGraph.Entity(revision,entityA,EvidenceGraph.EntityType.PERSON,"STRONG","张三",cn.bidcheck.compat.Java8Api.listOf("o"),cn.bidcheck.compat.Java8Api.mapOf()), new EvidenceGraph.Entity(revision,entityB,EvidenceGraph.EntityType.PROJECT,"PROJECT_KEY","项目",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
            cn.bidcheck.verification.graph.EvidenceGraph.Relation relation=new EvidenceGraph.Relation("edge",revision,entityA,"APPOINTED_TO",entityB,"o",cn.bidcheck.compat.Java8Api.listOf(source),"CURRENT_PROJECT","","",EvidenceGraph.RelationStatus.SUPPORTED,"SOURCE_BOUND",cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理"));
            cn.bidcheck.verification.graph.EvidenceGraph.Revision metadata=new EvidenceGraph.Revision(revision,"project","engine","manifest","occurrences",EvidenceGraphService.VERSION,"content",cn.bidcheck.compat.Java8Api.listOf(new EvidenceGraph.DocumentRevision("bid",1,cn.bidcheck.compat.Java8Api.repeat("a", 64))),"2026-10-19T00:00:00Z");
            cn.bidcheck.verification.graph.EvidenceGraph.Snapshot snapshot=new EvidenceGraph.Snapshot(metadata,entities,cn.bidcheck.compat.Java8Api.listOf(relation),cn.bidcheck.compat.Java8Api.mapOf("modelCalls", 0));first.publish(snapshot);session.commit();
            StoreEvidenceGraphRepository restarted=new StoreEvidenceGraphRepository(new Store(session.getMapper(RecordMapper.class),json));
            assertEquals(snapshot,restarted.find("project",revision).orElseThrow(java.util.NoSuchElementException::new));assertTrue((!restarted.find("other",revision).isPresent()));
            assertEquals(snapshot,restarted.publish(snapshot));
        }
    }
    static InputStream resource(String path){return Objects.requireNonNull(StoreEvidenceGraphRepositoryTest.class.getClassLoader().getResourceAsStream(path));}
}
