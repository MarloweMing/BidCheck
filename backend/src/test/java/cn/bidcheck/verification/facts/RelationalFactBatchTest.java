package cn.bidcheck.verification.facts;

import cn.bidcheck.persistence.*;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Counts real JDBC statement preparations, not merely mapper method invocations. */
class RelationalFactBatchTest {
    SqlSession session;RelationalFactRepository repository;Store store;FactLedgerService ledger;final Statements statements=new Statements();
    final FactLedgerServiceTest fixture=new FactLedgerServiceTest();
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static final class Statements implements Interceptor {
        @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
        @Override public void setProperties(java.util.Properties properties) { }

        int count;boolean failAttachment;
        public Object intercept(Invocation call)throws Throwable {
            String sql=cn.bidcheck.compat.Java8Api.strip(((StatementHandler)call.getTarget()).getBoundSql().getSql()).toUpperCase(Locale.ROOT);
            count++;
            if(failAttachment&&sql.startsWith("INSERT INTO QC_FACT_EVIDENCE"))throw new IllegalStateException("SYNTHETIC_ATTACHMENT_FAILURE");
            return call.proceed();
        }
    }
    @BeforeEach void open()throws Exception {
        org.apache.ibatis.datasource.unpooled.UnpooledDataSource ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:batch_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection c=ds.getConnection();Reader r=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(c,r);}
        Configuration config=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));config.setDatabaseId("h2");config.addInterceptor(statements);
        for(String name:cn.bidcheck.compat.Java8Api.listOf("RecordMapper", "FactMapper"))try(InputStream in=resource("mappers/"+name+".xml")){new XMLMapperBuilder(in,config,name,config.getSqlFragments()).parse();}
        session=new SqlSessionFactoryBuilder().build(config).openSession(false);com.fasterxml.jackson.databind.ObjectMapper json=new ObjectMapper();
        store=new Store(session.getMapper(RecordMapper.class),json);repository=new RelationalFactRepository(session.getMapper(FactMapper.class),json);ledger=new FactLedgerService(store,repository);
    }
    @AfterEach void close(){if(session!=null){session.rollback();session.close();}}
    static InputStream resource(String name){return Objects.requireNonNull(RelationalFactBatchTest.class.getClassLoader().getResourceAsStream(name));}
    long count(String table)throws Exception {try(java.sql.Statement s=session.getConnection().createStatement();java.sql.ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    void noRows()throws Exception {for(String table:cn.bidcheck.compat.Java8Api.listOf("qc_entity", "qc_evidence", "qc_fact_occurrence", "qc_fact_evidence", "qc_entity_link", "qc_record"))assertEquals(0,count(table),table);}
    Map<String,Object> source(int n,String alias){return of("id",alias,"projectId","project","documentHash",cn.bidcheck.compat.Java8Api.repeat("a", 64),"documentVersion",1,"parserFingerprint",cn.bidcheck.compat.Java8Api.repeat("b", 64),"sourceFingerprint",RelationalFactRepository.hash("source-"+n),"evidence",of("documentId","doc","documentName","test.pdf","page",n,"locationType","PAGE","quote","source-"+n,"anchor","anchor-"+n,"confidence",1d));}
    Map<String,Object> occurrence(String id){return of("id",id,"occurrenceId",id,"projectId","project","engineIdentity","engine","fact",of("id",id,"kind","FIELD","subject","person","field","field-"+id,"rawValue","value","value","value","unit","","basis","","scope","UNRESOLVED","sourceIds",cn.bidcheck.compat.Java8Api.listOf("s1", "s2", "s3"),"attributes",cn.bidcheck.compat.Java8Api.mapOf()),"sources",cn.bidcheck.compat.Java8Api.listOf(source(1,"s1"), source(2,"s2"), source(3,"s3")),"entity",of("id","same-entity","type","PERSON","identityStrength","SOURCE_LOCAL","displayName","person","identityClaims",cn.bidcheck.compat.Java8Api.listOf(id)),"layoutIdentity","","createdAt","2026-10-16T00:00:00Z","ledgerVersion","test");}

    @Test void tenFactsWithThirtyReferencesReduceRealJdbcStatementsFrom94To18WithoutChangingValues()throws Exception {
        List<Map<String,Object>> values=new ArrayList<>();for(int i=0;i<10;i++)values.add(occurrence("fact-"+i));
        statements.count=0;values.forEach(repository::insert);int sequential=statements.count;
        List<Map<String,Object>> expected=cn.bidcheck.compat.Java8Api.toList(values.stream().map(v->repository.get(text(v,"id"))));session.rollback();session.clearCache();
        statements.count=0;repository.insertBatch(values);int batched=statements.count;
        assertEquals(94,sequential);assertEquals(18,batched);assertTrue(batched<sequential/2);
        assertEquals(10,count("qc_fact_occurrence"));assertEquals(3,count("qc_evidence"));assertEquals(30,count("qc_fact_evidence"));
        assertEquals(canonical(expected),canonical(cn.bidcheck.compat.Java8Api.toList(values.stream().map(v->repository.get(text(v,"id"))))));
    }
    @Test void emptyBatchAndEmptyLookupDoNotExecuteSql(){statements.count=0;repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf());assertTrue(repository.existingIds(cn.bidcheck.compat.Java8Api.listOf()).isEmpty());assertEquals(0,statements.count);}
    @Test void bulkReadPreservesAllSourceAliasesAndEntityAuditUsingThreeQueries(){
        List<Map<String,Object>> input=new ArrayList<>();for(int i=0;i<10;i++)input.add(occurrence("bulk-"+i));repository.insertBatch(input);
        java.util.List<java.lang.String> ids=cn.bidcheck.compat.Java8Api.toList(input.stream().map(row->text(row,"id")));Map<String,Map<String,Object>> expected=new LinkedHashMap<>();ids.forEach(id->expected.put(id,repository.get(id)));
        session.clearCache();statements.count=0;java.util.Map<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> actual=repository.getAll(ids);
        assertEquals(canonical(expected),canonical(actual));assertEquals(3,statements.count);
        statements.count=0;assertTrue(repository.getAll(cn.bidcheck.compat.Java8Api.listOf()).isEmpty());assertEquals(0,statements.count);
    }
    @Test void nativeDocumentFactsHydrateInOneJoinedReadAndKeepDomainValues(){
        List<String> lines=new ArrayList<>();List<Fact> expected=new ArrayList<>();
        for(int i=0;i<80;i++){String raw="原始值-"+i;lines.add(raw);expected.add(new Fact("PERSON","人员-"+i,"字段-"+i,raw,"规范值-"+i,
            new Evidence("doc","test.pdf",1,"PAGE",raw,"anchor-"+i,1d)));}
        Document document=new Document("doc","project","BID","test.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",
            cn.bidcheck.compat.Java8Api.listOf(new Page(1,String.join("\n",lines),"PAGE","PDFBOX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());
        ledger.persistNativeFacts(document,expected,"engine");
        statements.count=0;List<Fact> actual=ledger.nativeFacts(document,"engine");
        assertEquals(1,statements.count,"native facts must be loaded with one bounded joined select");
        assertEquals(expected,actual);
    }
    @Test void completelyColdTenEntitiesAndThirtyTwoDistinctSourcesReduceJdbcStatementsFrom136To56()throws Exception {
        List<Map<String,Object>> values=new ArrayList<>();int source=0;
        for(int i=0;i<10;i++){
            java.util.Map<java.lang.String, java.lang.Object> value=occurrence("cold-"+i);java.util.Map<java.lang.String, java.lang.Object> identity=map(value.get("entity"));identity.put("id","entity-"+i);value.put("entity",identity);
            List<Map<String,Object>> refs=new ArrayList<>();for(int j=0;j<(i<2?4:3);j++)refs.add(source(++source,"s"+j));value.put("sources",refs);
            java.util.Map<java.lang.String, java.lang.Object> fact=map(value.get("fact"));fact.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(refs.stream().map(ref->text(ref,"id"))));value.put("fact",fact);values.add(value);
        }
        statements.count=0;values.forEach(repository::insert);assertEquals(136,statements.count);session.rollback();session.clearCache();noRows();
        statements.count=0;repository.insertBatch(values);assertEquals(56,statements.count);
        assertEquals(10,count("qc_entity"));assertEquals(10,count("qc_fact_occurrence"));assertEquals(32,count("qc_evidence"));assertEquals(32,count("qc_fact_evidence"));
    }
    @Test void existingOccurrenceAndDuplicateInputIdsAreNeverOverwritten()throws Exception {
        java.util.Map<java.lang.String, java.lang.Object> a=occurrence("a");repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(a));session.commit();String old=canonical(repository.get("a"));
        java.util.Map<java.lang.String, java.lang.Object> changed=occurrence("a");java.util.Map<java.lang.String, java.lang.Object> fact=map(changed.get("fact"));fact.put("value","changed");changed.put("fact",fact);
        assertEquals("FACT_ALREADY_EXISTS",assertThrows(IllegalStateException.class,()->repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(changed))).getMessage());
        assertEquals("FACT_ALREADY_EXISTS",assertThrows(IllegalStateException.class,()->repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(occurrence("b"), occurrence("b")))).getMessage());
        assertEquals(old,canonical(repository.get("a")));assertEquals(1,count("qc_fact_occurrence"));
    }
    @Test void repeatedGlobalEvidenceRetainsEachLocalAliasAndSourceOrder()throws Exception {
        java.util.Map<java.lang.String, java.lang.Object> first=occurrence("a");first.put("sources",cn.bidcheck.compat.Java8Api.listOf(source(1,"first"), source(1,"second")));repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(first, occurrence("b")));
        assertEquals(3,count("qc_evidence"));assertEquals(5,count("qc_fact_evidence"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("first", "second"),cn.bidcheck.compat.Java8Api.toList(maps(repository.get("a").get("sources")).stream().map(s->text(s,"id"))));
        repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(occurrence("c")));assertEquals(3,count("qc_evidence"));assertEquals(8,count("qc_fact_evidence"));
    }
    @Test void changedSourceUnderTheSameFingerprintRejectsEveryImmutableCoordinate()throws Exception {
        repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(occurrence("baseline")));session.commit();String old=canonical(repository.get("baseline"));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("quote", "page", "locationType", "documentId", "anchor", "confidence", "documentHash", "documentVersion", "parserFingerprint")){
            java.util.Map<java.lang.String, java.lang.Object> candidate=occurrence("new-"+key);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> refs=maps(candidate.get("sources"));java.util.Map<java.lang.String, java.lang.Object> source=refs.get(0);
            if(cn.bidcheck.compat.Java8Api.setOf("documentHash", "documentVersion", "parserFingerprint").contains(key))source.put(key,key.equals("documentVersion")?2:"changed");
            else {java.util.Map<java.lang.String, java.lang.Object> ev=map(source.get("evidence"));ev.put(key,key.equals("page")?2:key.equals("confidence")?0d:"changed");source.put("evidence",ev);}
            candidate.put("sources",refs);
            assertEquals("EVIDENCE_IMMUTABILITY_VIOLATION",assertThrows(IllegalStateException.class,()->repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(candidate)),key).getMessage());
            session.rollback();assertEquals(old,canonical(repository.get("baseline")));assertEquals(1,count("qc_fact_occurrence"));
        }
    }
    @Test void sameBatchSourceAndEntityCollisionsRollBackButDifferentClaimsRemainLegal()throws Exception {
        java.util.Map<java.lang.String, java.lang.Object> a=occurrence("a");java.util.Map<java.lang.String, java.lang.Object> b=occurrence("b");java.util.List<java.util.Map<java.lang.String, java.lang.Object>> refs=maps(b.get("sources"));java.util.Map<java.lang.String, java.lang.Object> ev=map(refs.get(0).get("evidence"));ev.put("quote","different");refs.get(0).put("evidence",ev);b.put("sources",refs);
        java.util.Map<java.lang.String, java.lang.Object> corruptSource=b;assertThrows(IllegalStateException.class,()->repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(a, corruptSource)));session.rollback();noRows();
        b=occurrence("b");java.util.Map<java.lang.String, java.lang.Object> identity=map(b.get("entity"));identity.put("type","COMPANY");b.put("entity",identity);java.util.Map<java.lang.String, java.lang.Object> incompatible=b;
        assertEquals("ENTITY_IMMUTABILITY_VIOLATION",assertThrows(IllegalStateException.class,()->repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(a, incompatible))).getMessage());session.rollback();noRows();
        repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(a, occurrence("b")));assertEquals(1,count("qc_entity"));assertNotEquals(map(repository.get("a").get("entity")).get("identityClaims"),map(repository.get("b").get("entity")).get("identityClaims"));
    }
    @Test void callLocalCachesNeverMixProjectOrEngineScopes()throws Exception {
        java.util.Map<java.lang.String, java.lang.Object> other=occurrence("other");other.put("projectId","another-project");other.put("engineIdentity","another-engine");
        repository.insertBatch(cn.bidcheck.compat.Java8Api.listOf(occurrence("a"), other));assertEquals(2,count("qc_entity"));assertEquals(6,count("qc_evidence"));
        assertEquals("project",repository.get("a").get("projectId"));assertEquals("another-project",repository.get("other").get("projectId"));
    }
    @Test void attachmentFailureRollsBackCoreAndLedgerDependenciesTogether()throws Exception {
        cn.bidcheck.domain.Domain.Document doc=fixture.doc("doc","合成甲 一级 二级");java.util.Map<java.lang.String, java.lang.Object> batch=fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fixture.fact("a","一级","S"), fixture.fact("b","二级","S")),cn.bidcheck.compat.Java8Api.listOf(fixture.source(doc,"S")));
        statements.failAttachment=true;assertThrows(RuntimeException.class,()->ledger.publish("project","run","cp","engine",cn.bidcheck.compat.Java8Api.listOf(doc),batch));
        session.rollback();noRows();statements.failAttachment=false;
        assertEquals(2,ledger.publish("project","run","cp","engine",cn.bidcheck.compat.Java8Api.listOf(doc),batch).get("accepted"));assertEquals(2,count("qc_fact_occurrence"));
    }
    @Test void duplicateSemanticFactsAndRepeatedPublicationKeepOriginalAcceptedAndReusedCounts()throws Exception {
        cn.bidcheck.domain.Domain.Document doc=fixture.doc("doc","合成甲 一级");java.util.Map<java.lang.String, java.lang.Object> batch=fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fixture.fact("a","一级","S"), fixture.fact("b","一级","S")),cn.bidcheck.compat.Java8Api.listOf(fixture.source(doc,"S")));
        java.util.Map<java.lang.String, java.lang.Object> first=ledger.publish("project","run","cp","engine",cn.bidcheck.compat.Java8Api.listOf(doc),batch);assertEquals(2,first.get("accepted"));assertEquals(1,first.get("reused"));assertEquals(1,count("qc_fact_occurrence"));
        java.util.Map<java.lang.String, java.lang.Object> repeated=ledger.publish("project","run","cp","engine",cn.bidcheck.compat.Java8Api.listOf(doc),batch);assertEquals(2,repeated.get("accepted"));assertEquals(2,repeated.get("reused"));assertEquals(first.get("occurrenceIds"),repeated.get("occurrenceIds"));assertEquals(1,count("qc_fact_occurrence"));
    }
}
