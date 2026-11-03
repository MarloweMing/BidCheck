package cn.bidcheck.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompressedPreparedRecordTest {
    static InputStream resource(String name){return Objects.requireNonNull(CompressedPreparedRecordTest.class.getClassLoader().getResourceAsStream(name));}
    @Test void compressedSnapshotAndReportRollbackTogetherAndLegacyBodyIsNeverRewritten()throws Exception {
        org.apache.ibatis.datasource.unpooled.UnpooledDataSource ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:compressed_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection connection=ds.getConnection();Reader schema=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(connection,schema);}
        Configuration config=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));config.setDatabaseId("h2");
        try(java.io.InputStream in=resource("mappers/RecordMapper.xml")){new XMLMapperBuilder(in,config,"RecordMapper",config.getSqlFragments()).parse();}
        try(org.apache.ibatis.session.SqlSession session=new SqlSessionFactoryBuilder().build(config).openSession(false)) {
            cn.bidcheck.persistence.RecordMapper mapper=session.getMapper(RecordMapper.class);cn.bidcheck.persistence.Store store=new Store(mapper,new ObjectMapper());
            Stored legacy=new Stored();legacy.id="old";legacy.kind="fact_snapshot";legacy.projectId="project";legacy.parentId="old-run";legacy.body="{ \"text\": \"old snapshot\" }";legacy.createdAt=1;mapper.save(legacy);session.commit();
            cn.bidcheck.persistence.Store.PreparedRecord report=store.prepareRecord("consistency_report","report","project","run",cn.bidcheck.compat.Java8Api.mapOf("text", StoredBodyCodecTest.LARGE));
            cn.bidcheck.persistence.Store.PreparedRecord snapshot=store.prepareRecord("fact_snapshot","snapshot","project","run",cn.bidcheck.compat.Java8Api.mapOf("text", StoredBodyCodecTest.LARGE));
            store.savePrepared(snapshot);store.savePrepared(report);assertTrue(mapper.get("report").body.startsWith(StoredBodyCodec.HEADER));
            session.rollback();session.clearCache();assertNull(mapper.get("report"));assertNull(mapper.get("snapshot"));assertEquals(legacy.body,mapper.get("old").body);
            store.savePrepared(snapshot);store.savePrepared(report);session.commit();session.clearCache();
            assertEquals(StoredBodyCodecTest.LARGE,store.getMap("fact_snapshot","snapshot").get("text"));assertEquals(StoredBodyCodecTest.LARGE,store.getMap("consistency_report","report").get("text"));
            assertEquals(legacy.body,mapper.get("old").body);assertEquals("old snapshot",store.getMap("fact_snapshot","old").get("text"));
        }
    }
}
