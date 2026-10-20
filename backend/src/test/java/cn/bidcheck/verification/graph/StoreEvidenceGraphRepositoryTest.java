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
        var ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:graph_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection connection=ds.getConnection();var reader=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(connection,reader);}
        Configuration configuration=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));
        try(InputStream input=resource("mappers/RecordMapper.xml")){new XMLMapperBuilder(input,configuration,"RecordMapper.xml",configuration.getSqlFragments()).parse();}
        try(SqlSession session=new SqlSessionFactoryBuilder().build(configuration).openSession(false)){
            ObjectMapper json=new ObjectMapper();Store store=new Store(session.getMapper(RecordMapper.class),json);StoreEvidenceGraphRepository first=new StoreEvidenceGraphRepository(store);
            String revision="egraph:"+"1".repeat(40),entityA="person:a",entityB="project:b";
            var source=new EvidenceGraph.SourceRef("s","fp","bid",1,"a".repeat(64),"parser","PAGE",1,"项目经理 张三","",1);
            var entities=List.of(new EvidenceGraph.Entity(revision,entityA,EvidenceGraph.EntityType.PERSON,"STRONG","张三",List.of("o"),Map.of()),new EvidenceGraph.Entity(revision,entityB,EvidenceGraph.EntityType.PROJECT,"PROJECT_KEY","项目",List.of(),Map.of()));
            var relation=new EvidenceGraph.Relation("edge",revision,entityA,"APPOINTED_TO",entityB,"o",List.of(source),"CURRENT_PROJECT","","",EvidenceGraph.RelationStatus.SUPPORTED,"SOURCE_BOUND",Map.of("role","项目经理"));
            var metadata=new EvidenceGraph.Revision(revision,"project","engine","manifest","occurrences",EvidenceGraphService.VERSION,"content",List.of(new EvidenceGraph.DocumentRevision("bid",1,"a".repeat(64))),"2026-10-19T00:00:00Z");
            var snapshot=new EvidenceGraph.Snapshot(metadata,entities,List.of(relation),Map.of("modelCalls",0));first.publish(snapshot);session.commit();
            StoreEvidenceGraphRepository restarted=new StoreEvidenceGraphRepository(new Store(session.getMapper(RecordMapper.class),json));
            assertEquals(snapshot,restarted.find("project",revision).orElseThrow());assertTrue(restarted.find("other",revision).isEmpty());
            assertEquals(snapshot,restarted.publish(snapshot));
        }
    }
    static InputStream resource(String path){return Objects.requireNonNull(StoreEvidenceGraphRepositoryTest.class.getClassLoader().getResourceAsStream(path));}
}
