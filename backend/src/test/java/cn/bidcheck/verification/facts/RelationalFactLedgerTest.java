package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

class RelationalFactLedgerTest {
    SqlSession session;Store store;RelationalFactRepository repository;FactLedgerService ledger;
    final FactLedgerServiceTest fixture=new FactLedgerServiceTest();
    @BeforeEach void open()throws Exception {
        org.apache.ibatis.datasource.unpooled.UnpooledDataSource ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:fact_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection c=ds.getConnection();Reader r=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(c,r);}
        Configuration config=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));config.setDatabaseId("h2");
        for(String name:cn.bidcheck.compat.Java8Api.listOf("RecordMapper", "FactMapper"))try(InputStream in=resource("mappers/"+name+".xml")){new XMLMapperBuilder(in,config,name,config.getSqlFragments()).parse();}
        session=new SqlSessionFactoryBuilder().build(config).openSession(false);ObjectMapper json=new ObjectMapper();store=new Store(session.getMapper(RecordMapper.class),json);repository=new RelationalFactRepository(session.getMapper(FactMapper.class),json);ledger=new FactLedgerService(store,repository);
    }
    @AfterEach void close(){if(session!=null){session.rollback();session.close();}}
    static InputStream resource(String path){return Objects.requireNonNull(RelationalFactLedgerTest.class.getClassLoader().getResourceAsStream(path));}
    long count(String table)throws Exception {try(java.sql.Statement s=session.getConnection().createStatement();java.sql.ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    String cell(String sql)throws Exception {try(java.sql.Statement s=session.getConnection().createStatement();java.sql.ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    Map<String,Object> publish(String run,String cp,List<Document> docs,Map<String,Object> batch){return ledger.publish("project",run,cp,"engine",docs,batch);}
    Map<String,Object> batch(Document d,String source,String value){return fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fixture.fact("model-"+source,value,source)),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,source)));}

    @Test void explicitColumnsAndJoinsAreAuthoritativeAndNewFactsNeverUseGenericOccurrence()throws Exception {
        Document d=fixture.doc("doc","合成甲 类别一级");publish("run","cp",cn.bidcheck.compat.Java8Api.listOf(d),batch(d,"A","一级"));
        assertEquals(1,count("qc_entity"));assertEquals(1,count("qc_fact_occurrence"));assertEquals(1,count("qc_evidence"));assertEquals(1,count("qc_fact_evidence"));
        assertEquals("一级",cell("SELECT raw_value FROM qc_fact_occurrence"));assertEquals("doc",cell("SELECT document_id FROM qc_evidence"));assertEquals("PAGE",cell("SELECT location_type FROM qc_evidence"));
        assertEquals("0",cell("SELECT COUNT(*) FROM qc_record WHERE kind='fact_occurrence'"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> entries=ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),cn.bidcheck.compat.Java8Api.mapOf());assertEquals("一级",map(entries.get(0).get("fact")).get("value"));assertEquals(1.0,map(maps(entries.get(0).get("sources")).get(0).get("evidence")).get("confidence"));
        // Batch receipts are retained for audit/coverage; their duplicated values cannot override core columns.
        Map<String,Object> receipt=store.maps("fact_batch","project",null).get(0);maps(receipt.get("entries")).forEach(e->map(e.get("fact")).put("value","伪造"));
        // Change via the actual mutable nested structure, not LedgerValues' defensive map copy.
        @SuppressWarnings("unchecked") java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=(List<Map<String,Object>>)receipt.get("entries");@SuppressWarnings("unchecked") java.util.Map<java.lang.String, java.lang.Object> fact=(Map<String,Object>)rows.get(0).get("fact");fact.put("value","伪造");store.save("fact_batch",text(receipt,"id"),"project","ignored-parent",receipt);
        assertEquals("一级",map(ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),cn.bidcheck.compat.Java8Api.mapOf()).get(0).get("fact")).get("value"));
    }
    @Test void repeatedBatchesAndSourceAliasesReuseTheImmutableCore()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");java.util.Map<java.lang.String, java.lang.Object> a=publish("run","cp1",cn.bidcheck.compat.Java8Api.listOf(d),batch(d,"A","一级"));java.util.Map<java.lang.String, java.lang.Object> b=publish("run","cp2",cn.bidcheck.compat.Java8Api.listOf(d),batch(d,"B","一级"));
        assertEquals(a.get("occurrenceIds"),b.get("occurrenceIds"));assertEquals(1,b.get("reused"));assertEquals(1,count("qc_fact_occurrence"));assertEquals(1,count("qc_evidence"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> found=ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),cn.bidcheck.compat.Java8Api.mapOf("sourceIds", cn.bidcheck.compat.Java8Api.listOf("B")));assertEquals(1,found.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("B"),map(found.get(0).get("fact")).get("sourceIds"));assertEquals("B",maps(found.get(0).get("sources")).get(0).get("id"));
    }
    @Test void declarationExplanationCreatesAnAuditReceiptButNotAnotherOccurrence()throws Exception {
        Document d=fixture.doc("declaration-doc","合成甲承诺未超出最大承接数量");
        Map<String,Object> fact=fixture.fact("first","未超出最大承接数量","A");fact.put("kind","DECLARATION");fact.put("field","commitment");fact.put("basis","人员承诺书");
        java.util.Map<java.lang.String, java.lang.Object> first=publish("run","cp1",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fact),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        fact.put("id","second");fact.put("basis","人员承诺书明确承诺未超出最大承接数量");
        java.util.Map<java.lang.String, java.lang.Object> second=publish("run","cp2",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fact),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        assertEquals(first.get("occurrenceIds"),second.get("occurrenceIds"));assertEquals(1,second.get("reused"));assertEquals(1,count("qc_fact_occurrence"));
        assertEquals(2,store.maps("fact_batch","project",null).size(),"explanations remain auditable");
    }
    @Test void pricingBasisStillCreatesDistinctOccurrences()throws Exception {
        Document d=fixture.doc("price-doc","合成甲 含税总价100元，不含税单价100元");
        Map<String,Object> fact=fixture.fact("first","100","A");fact.put("kind","NUMBER");fact.put("field","amount");fact.put("basis","含税总价");fact.put("unit","元");
        java.util.Map<java.lang.String, java.lang.Object> first=publish("run","cp1",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fact),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        fact.put("id","second");fact.put("basis","不含税单价");
        java.util.Map<java.lang.String, java.lang.Object> second=publish("run","cp2",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fact),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        assertNotEquals(first.get("occurrenceIds"),second.get("occurrenceIds"));assertEquals(2,count("qc_fact_occurrence"));
    }
    @Test void legacyDeclarationIdentityIsStillVerifiedAndNeverRewritten()throws Exception {
        Document d=fixture.doc("legacy-declaration","合成甲承诺未超出最大承接数量");store.save("document",d.id(),"project","",d);
        Map<String,Object> fact=fixture.fact("first","未超出最大承接数量","A");fact.put("kind","DECLARATION");fact.put("field","commitment");fact.put("basis","人员承诺书");
        java.util.Map<java.lang.String, java.lang.Object> receipt=publish("old","cp",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fact),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        Map<String,Object> original=repository.get(strings(receipt.get("occurrenceIds")).get(0));
        Map<String,Object> semantic=new LinkedHashMap<>(map(original.get("fact")));semantic.remove("id");semantic.put("sourceIds",cn.bidcheck.compat.Java8Api.toList(maps(original.get("sources")).stream().map(s->text(s,"sourceFingerprint")).sorted()));
        String legacyId=dbKey("occurrence",canonical(cn.bidcheck.compat.Java8Api.listOf("project", "engine", semantic)));assertNotEquals(original.get("id"),legacyId);
        original.put("id",legacyId);original.put("occurrenceId",legacyId);store.save("fact_occurrence",legacyId,"project","legacy",original);
        String frozenBody=store.mapper().get(legacyId).body;
        assertEquals(1,ledger.migrateLegacyOccurrences("project",10).get("migrated"));
        assertEquals(frozenBody,store.mapper().get(legacyId).body);assertEquals(2,count("qc_fact_occurrence"),"old historical row remains, new writes use the typed key");
    }
    @Test void parentTransactionRollbackRemovesCoreAndDependenciesTogether()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");publish("run","cp",cn.bidcheck.compat.Java8Api.listOf(d),batch(d,"A","一级"));assertEquals(1,count("qc_fact_occurrence"));session.rollback();
        for(String table:cn.bidcheck.compat.Java8Api.listOf("qc_entity", "qc_fact_occurrence", "qc_evidence", "qc_fact_evidence", "qc_record"))assertEquals(0,count(table),table);
    }
    @Test void relationsHaveDedicatedRowsAndOriginalEvidenceJoins()throws Exception {
        Document d=fixture.doc("doc","合成甲 任职 合成乙");Map<String,Object> f=fixture.fact("r","任职","A");f.put("kind","RELATION");f.put("field","任职");f.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "合成乙"));publish("run","cp",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        assertEquals(1,count("qc_entity_link"));assertEquals("合成乙",cell("SELECT object_text FROM qc_entity_link"));assertEquals("1",cell("SELECT COUNT(*) FROM qc_entity_link l JOIN qc_fact_evidence f ON l.occurrence_id=f.occurrence_id JOIN qc_evidence e ON e.id=f.evidence_id"));
        assertEquals("合成乙",maps(ledger.view("project","run","engine",cn.bidcheck.compat.Java8Api.listOf(d)).get("relations")).get(0).get("object"));
    }
    @Test void oldJsonIsReadOnlyUntilPublishMigratesSameIdAndKeepsSnapshot()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");java.util.Map<java.lang.String, java.lang.Object> b=batch(d,"A","一级");String id=strings(publish("old","cp",cn.bidcheck.compat.Java8Api.listOf(d),b).get("occurrenceIds")).get(0);Map<String,Object> frozen=ledger.snapshot("project","old","engine",cn.bidcheck.compat.Java8Api.listOf(d));Map<String,Object> occurrence=repository.get(id);store.save("fact_occurrence",id,"project","legacy",occurrence);String original=store.mapper().get(id).body;
        try(java.sql.Statement sql=session.getConnection().createStatement()){for(String table:cn.bidcheck.compat.Java8Api.listOf("qc_entity_link", "qc_fact_evidence", "qc_fact_occurrence", "qc_evidence", "qc_entity"))sql.executeUpdate("DELETE FROM "+table);}session.clearCache();
        assertEquals(1,ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),cn.bidcheck.compat.Java8Api.mapOf()).size());assertEquals(0,count("qc_fact_occurrence"));
        assertEquals(canonical(frozen),canonical(ledger.view("project","old","engine",cn.bidcheck.compat.Java8Api.listOf(d))));assertThrows(IllegalStateException.class,()->publish("old","new-cp",cn.bidcheck.compat.Java8Api.listOf(d),b));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(id),publish("new","cp2",cn.bidcheck.compat.Java8Api.listOf(d),b).get("occurrenceIds"));assertEquals(1,count("qc_fact_occurrence"));publish("third","cp3",cn.bidcheck.compat.Java8Api.listOf(d),b);assertEquals(1,count("qc_fact_occurrence"));assertEquals(original,store.mapper().get(id).body);assertEquals(canonical(frozen),canonical(ledger.view("project","old","engine",cn.bidcheck.compat.Java8Api.listOf(d))));
    }
    @Test void nativeExtractorFactsUseSameSqlTablesAndInvalidateOnParserOrVersionChanges()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");Fact f=new Fact("PERSON","合成甲","category","一级","一级",new Evidence(d.id(),d.name(),1,"PAGE",d.pages().get(0).text(),"",1));
        ledger.persistNativeFacts(d,cn.bidcheck.compat.Java8Api.listOf(f),"native-v1");ledger.persistNativeFacts(d,cn.bidcheck.compat.Java8Api.listOf(f),"native-v1");assertEquals(cn.bidcheck.compat.Java8Api.listOf(f),ledger.nativeFacts(d,"native-v1"));assertEquals(1,count("qc_fact_occurrence"));assertEquals("NATIVE_FACT",cell("SELECT source_domain FROM qc_fact_occurrence"));assertEquals("0",cell("SELECT COUNT(*) FROM qc_record WHERE kind='fact'"));assertTrue(ledger.nativeFacts(d,"native-v2").isEmpty());
        Document updated=new Document(d.id(),d.projectId(),d.role(),d.name(),2,d.sha256(),"",d.pages(),cn.bidcheck.compat.Java8Api.listOf());assertTrue(ledger.nativeFacts(updated,"native-v1").isEmpty());Document reparsed=new Document(d.id(),d.projectId(),d.role(),d.name(),1,d.sha256(),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,d.pages().get(0).text(),"PAGE","NEW_PARSER","")),cn.bidcheck.compat.Java8Api.listOf());assertTrue(ledger.nativeFacts(reparsed,"native-v1").isEmpty());
        ledger.persistNativeFacts(updated,cn.bidcheck.compat.Java8Api.listOf(f),"native-v1");assertEquals(2,count("qc_fact_occurrence"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(f),ledger.nativeFacts(updated,"native-v1"));
    }
    @Test void realStoreCannotSilentlyFallBackToEphemeralMemory(){assertThrows(IllegalArgumentException.class,()->new FactLedgerService(store));}
    @Test void sqlScopesKeepProjectsEnginesAndDocumentVersionsSeparate()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");publish("run","cp",cn.bidcheck.compat.Java8Api.listOf(d),batch(d,"A","一级"));
        assertTrue(ledger.lookup("project","other-engine",cn.bidcheck.compat.Java8Api.listOf(d),cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
        Document updated=new Document(d.id(),d.projectId(),d.role(),d.name(),2,d.sha256(),"",d.pages(),cn.bidcheck.compat.Java8Api.listOf());assertTrue(ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(updated),cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
        Document foreign=new Document(d.id(),"another-project",d.role(),d.name(),1,d.sha256(),"",d.pages(),cn.bidcheck.compat.Java8Api.listOf());assertTrue(ledger.lookup("another-project","engine",cn.bidcheck.compat.Java8Api.listOf(foreign),cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
        ledger.publish("another-project","other-run","cp","engine",cn.bidcheck.compat.Java8Api.listOf(foreign),batch(foreign,"A","一级"));
        assertEquals(2,count("qc_entity"));assertEquals(2,count("qc_fact_occurrence"));assertEquals(2,count("qc_evidence"));assertEquals(1,ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),cn.bidcheck.compat.Java8Api.mapOf()).size());assertEquals(1,ledger.lookup("another-project","engine",cn.bidcheck.compat.Java8Api.listOf(foreign),cn.bidcheck.compat.Java8Api.mapOf()).size());
    }
    @Test void sqlConflictsRetainBothValuesAndFrozenRunDoesNotAcquireLaterFacts()throws Exception {
        Document a=fixture.doc("a","截至2030-01-01，合成甲 合成资格证 注册编号 CERT-A 类别一级"),b=fixture.doc("b","截至2030-01-01，合成甲 合成资格证 注册编号 CERT-A 类别二级");java.util.List<cn.bidcheck.domain.Domain.Document> docs=cn.bidcheck.compat.Java8Api.listOf(a, b);
        java.util.Map<java.lang.String, java.lang.Object> first=fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fixture.certificate("f1","CERT-A","一级","A")),cn.bidcheck.compat.Java8Api.listOf(fixture.source(a,"A")));java.util.Map<java.lang.String, java.lang.Object> second=fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fixture.certificate("f2","CERT-A","二级","B")),cn.bidcheck.compat.Java8Api.listOf(fixture.source(b,"B")));
        String id=strings(publish("old","cp1",docs,first).get("occurrenceIds")).get(0);String frozen=canonical(ledger.snapshot("project","old","engine",docs));publish("new","cp1",docs,first);publish("new","cp2",docs,second);
        assertEquals(1,count("qc_entity"));assertEquals(2,count("qc_fact_occurrence"));assertEquals(1,maps(ledger.view("project","new","engine",docs).get("conflicts")).size());assertEquals(frozen,canonical(ledger.view("project","old","engine",docs)));
        Map<String,Object> replacement=repository.get(id);map(replacement.get("fact")).put("rawValue","无效覆盖");assertThrows(IllegalStateException.class,()->repository.insert(replacement));assertEquals("一级",map(repository.get(id).get("fact")).get("rawValue"));
    }
    @Test void overlongModelIdIsRejectedBeforeAnyCoreRowIsInserted()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");Map<String,Object> f=fixture.fact(cn.bidcheck.compat.Java8Api.repeat("x", 201),"一级","A");java.util.Map<java.lang.String, java.lang.Object> result=publish("run","cp",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));
        assertEquals(0,result.get("accepted"));assertEquals("FACT_ID_LIMIT",maps(result.get("rejected")).get(0).get("code"));assertEquals(0,count("qc_entity"));assertEquals(0,count("qc_fact_occurrence"));
    }
    @Test void nativeVehicleContextRecoversOnlyOriginalWhitespaceWithoutInventingJoinedEvidence()throws Exception {
        Document d=fixture.doc("doc","  车牌号码：京A12345  \n\n  车辆识别代号：L1234567890123456  \n  发动机号码：ABCDE12345  ");
        java.util.List<cn.bidcheck.domain.Domain.Fact> extracted=new cn.bidcheck.engine.FactExtractor().extract(d);assertTrue(extracted.size()>=3);assertTrue(extracted.stream().anyMatch(f->!d.pages().get(0).text().contains(f.evidence().quote())));
        ledger.persistNativeFacts(d,extracted,"native");java.util.List<cn.bidcheck.domain.Domain.Fact> persisted=ledger.nativeFacts(d,"native");assertEquals(extracted.size(),persisted.size());
        for(int i=0;i<extracted.size();i++){assertEquals(extracted.get(i).rawValue(),persisted.get(i).rawValue());assertTrue(d.pages().get(0).text().contains(persisted.get(i).evidence().quote()));}
        Document other=fixture.doc("unrelated","车牌号码：京A12345\n不能跳过的另一项材料\n车辆识别代号：L1234567890123456");
        cn.bidcheck.domain.Domain.Fact fake=new Fact("VEHICLE","vehicle","plateNumber","京A12345","京A12345",new Evidence(other.id(),other.name(),1,"PAGE","车牌号码：京A12345\n车辆识别代号：L1234567890123456","",1));
        assertThrows(IllegalArgumentException.class,()->ledger.persistNativeFacts(other,cn.bidcheck.compat.Java8Api.listOf(fake),"native"));
    }
    void dropCore()throws Exception {try(java.sql.Statement sql=session.getConnection().createStatement()){for(String table:cn.bidcheck.compat.Java8Api.listOf("qc_entity_link", "qc_fact_evidence", "qc_fact_occurrence", "qc_evidence", "qc_entity"))sql.executeUpdate("DELETE FROM "+table);}session.clearCache();}
    List<String> legacy(Document d,List<Map<String,Object>> facts)throws Exception {
        store.save("document",d.id(),d.projectId(),"",d);java.util.Map<java.lang.String, java.lang.Object> result=publish("legacy-run","cp",cn.bidcheck.compat.Java8Api.listOf(d),fixture.batch(facts,cn.bidcheck.compat.Java8Api.listOf(fixture.source(d,"A"))));java.util.List<java.lang.String> ids=strings(result.get("occurrenceIds"));
        for(String id:ids)store.save("fact_occurrence",id,d.projectId(),"old-parent",repository.get(id));dropCore();return ids;
    }
    @Test void explicitLegacyMigrationIsIdempotentAndNeverChangesHistoryOrCreatesUsage()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");String id=legacy(d,cn.bidcheck.compat.Java8Api.listOf(fixture.fact("f","一级","A"))).get(0);String original=store.mapper().get(id).body;long records=count("qc_record");
        java.util.Map<java.lang.String, java.lang.Object> first=ledger.migrateLegacyOccurrences("project",100);assertEquals(1,first.get("scanned"));assertEquals(1,first.get("migrated"));assertEquals(0,first.get("rejected"));assertEquals(false,first.get("hasMore"));assertEquals(false,first.get("adoptedForRun"));assertEquals(records,count("qc_record"));assertEquals(original,store.mapper().get(id).body);
        java.util.Map<java.lang.String, java.lang.Object> repeated=ledger.migrateLegacyOccurrences("project",100);assertEquals(0,repeated.get("migrated"));assertEquals(1,repeated.get("alreadyPresent"));assertEquals(1,count("qc_fact_occurrence"));assertEquals(records,count("qc_record"));
    }
    @Test void legacyCursorAdvancesAcrossEqualTimestampsAndRejectedEntriesWithoutBlockingTail()throws Exception {
        String all=java.util.stream.IntStream.range(0,28).mapToObj(i->"级别"+i).collect(java.util.stream.Collectors.joining("、"));Document d=fixture.doc("doc","合成甲 "+all);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> facts=cn.bidcheck.compat.Java8Api.toList(java.util.stream.IntStream.range(0,28).mapToObj(i->fixture.fact("f"+i,"级别"+i,"A")));List<String> ids=legacy(d,facts);
        String bad=ids.stream().sorted().findFirst().orElseThrow(java.util.NoSuchElementException::new);Map<String,Object> forged=store.getMap("fact_occurrence",bad);forged.put("entity",cn.bidcheck.compat.Java8Api.mapOf("id", "forged", "identityStrength", "STRONG", "type", "PERSON"));store.save("fact_occurrence",bad,"project","old-parent",forged);
        try(java.sql.Statement sql=session.getConnection().createStatement()){sql.executeUpdate("UPDATE qc_record SET created_at=777 WHERE kind='fact_occurrence'");}session.clearCache();
        java.util.Map<java.lang.String, java.lang.Object> first=ledger.migrateLegacyOccurrences("project",26);assertEquals(26,first.get("scanned"));assertEquals(25,first.get("migrated"));assertEquals(1,first.get("rejected"));assertEquals(cn.bidcheck.compat.Java8Api.mapOf("LEGACY_ENTITY_UNVERIFIED", 1),first.get("errors"));assertEquals(true,first.get("hasMore"));
        java.util.Map<java.lang.String, java.lang.Object> tail=ledger.migrateLegacyOccurrences("project",26,text(first,"nextCursor"));assertEquals(2,tail.get("scanned"));assertEquals(2,tail.get("migrated"));assertEquals(false,tail.get("hasMore"));assertEquals(27,count("qc_fact_occurrence"));assertTrue(repository.get(bad).isEmpty());
        assertThrows(IllegalArgumentException.class,()->ledger.migrateLegacyOccurrences("other",26,text(first,"nextCursor")));assertEquals(0,ledger.migrateLegacyOccurrences("project",26,text(tail,"nextCursor")).get("scanned"));
    }
    @Test void legacyMigrationRejectsOldParserAndForgedQuoteWithoutEchoingSourceText()throws Exception {
        Document d=fixture.doc("doc","合成甲 敏感示例 一级");String id=legacy(d,cn.bidcheck.compat.Java8Api.listOf(fixture.fact("f","一级","A"))).get(0);String original=store.mapper().get(id).body;
        Document reparsed=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,d.pages().get(0).text(),"PAGE","NEW_PARSER","")),cn.bidcheck.compat.Java8Api.listOf());store.save("document",d.id(),d.projectId(),"",reparsed);
        java.util.Map<java.lang.String, java.lang.Object> rejected=ledger.migrateLegacyOccurrences("project",1);assertEquals(1,rejected.get("rejected"));assertFalse(canonical(rejected).contains("敏感示例"));assertEquals(0,count("qc_fact_occurrence"));assertEquals(original,store.mapper().get(id).body);
        store.save("document",d.id(),d.projectId(),"",d);Map<String,Object> bad=store.getMap("fact_occurrence",id);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> refs=maps(bad.get("sources"));Map<String,Object> ev=map(refs.get(0).get("evidence"));ev.put("quote","虚构的私人信息 一级");refs.get(0).put("evidence",ev);bad.put("sources",refs);store.save("fact_occurrence",id,"project","old-parent",bad);
        java.util.Map<java.lang.String, java.lang.Object> forged=ledger.migrateLegacyOccurrences("project",1);assertEquals(1,forged.get("rejected"));assertFalse(canonical(forged).contains("私人信息"));assertEquals(0,count("qc_fact_occurrence"));
    }
    @Test void legacyMigrationJoinsCallerTransactionAndEnforcesLimits()throws Exception {
        Document d=fixture.doc("doc","合成甲 一级");legacy(d,cn.bidcheck.compat.Java8Api.listOf(fixture.fact("f","一级","A")));session.commit();
        assertThrows(IllegalArgumentException.class,()->ledger.migrateLegacyOccurrences("project",0));assertThrows(IllegalArgumentException.class,()->ledger.migrateLegacyOccurrences("project",2001));assertThrows(IllegalArgumentException.class,()->ledger.migrateLegacyOccurrences("project",1,"invalid_cursor"));
        assertEquals(1,ledger.migrateLegacyOccurrences("project",1).get("migrated"));session.rollback();assertEquals(0,count("qc_fact_occurrence"));assertEquals(1,store.mapper().count("fact_occurrence","project",null));
    }
    @Test void incrementalPageFactsUseSqlCoreAndSurviveAServiceRestart()throws Exception {
        Page a=new Page(18,"车牌号码：京A12345\n车辆识别代号：LSVA1234567890123","PAGE","PDF_OCR",""),b=new Page(137,"车牌号码：京A54321\n车辆识别代号：LSVA1234567890123","PAGE","PDF_OCR","");
        Document d=new Document("stream-doc","project","BID","投标.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(a, b),cn.bidcheck.compat.Java8Api.listOf());
        ledger.persistPageFacts(d,a,"pipeline-v1");assertEquals(2,count("qc_fact_occurrence"));assertEquals(1,count("qc_entity"));
        ledger.persistPageFacts(d,b,"pipeline-v1");session.commit();
        FactLedgerService restarted=new FactLedgerService(store,new RelationalFactRepository(session.getMapper(FactMapper.class),new ObjectMapper()));
        Map<String,Object> view=restarted.documentView(d,"pipeline-v1");assertEquals(4,maps(view.get("occurrences")).size());assertEquals(1,maps(view.get("conflicts")).size());assertEquals(1,count("qc_entity"));assertEquals(2,count("qc_evidence"));
        assertEquals("PAGE_FACT",cell("SELECT source_domain FROM qc_fact_occurrence LIMIT 1"));assertEquals(4,restarted.documentFacts(d,"pipeline-v1").size());
        java.util.Map<java.lang.String, java.lang.Object> replay=restarted.persistPageFacts(d,a,"pipeline-v1");assertEquals(2,replay.get("reused"));assertEquals(4,count("qc_fact_occurrence"));
    }
    @Test void incrementalPointerAndCoreRowsRollbackTogether()throws Exception {
        Page p=new Page(1,"项目编号：DEMO-001","PAGE","PDF_OCR","");Document d=new Document("stream-doc","project","BID","投标.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(p),cn.bidcheck.compat.Java8Api.listOf());
        ledger.persistPageFacts(d,p,"pipeline-v1");assertEquals(1,count("qc_fact_occurrence"));assertEquals(1,store.maps("page_fact_index","project",null).size());session.rollback();
        assertEquals(0,count("qc_fact_occurrence"));assertEquals(0,store.maps("page_fact_index","project",null).size());assertTrue(maps(ledger.documentView(d,"pipeline-v1").get("occurrences")).isEmpty());
    }
}
