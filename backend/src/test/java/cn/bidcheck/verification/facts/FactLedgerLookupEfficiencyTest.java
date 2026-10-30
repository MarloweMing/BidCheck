package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

/** Frozen original and optimized lookup compared against isolated H2. Counts actual JDBC SELECT statements. */
class FactLedgerLookupEfficiencyTest {
    SqlSession session;Store store;LegacyFactLedgerLookupFixture original;FactLedgerService candidate;Statements statements=new Statements();ObjectMapper json=new ObjectMapper();
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static final class Statements implements Interceptor {int selects;String failSql="";List<String> sql=new ArrayList<>(),kinds=new ArrayList<>(),idPrefixes=new ArrayList<>();public Object intercept(Invocation call)throws Throwable {var bound=((StatementHandler)call.getTarget()).getBoundSql();String text=bound.getSql().strip().toUpperCase(Locale.ROOT);if(text.startsWith("SELECT")){selects++;sql.add(text.replaceAll("\\s+"," "));if(bound.getParameterObject() instanceof Map<?,?> args){if(args.containsKey("kind")&&args.get("kind") instanceof String kind)kinds.add(kind);if(args.containsKey("ids")&&args.get("ids") instanceof Collection<?> ids)for(Object id:ids)idPrefixes.add(String.valueOf(id).split(":",2)[0]);}if(!failSql.isBlank()&&text.contains(failSql))throw new IllegalStateException("SYNTHETIC_REQUIRED_READ_FAILED");}return call.proceed();}void reset(){selects=0;sql.clear();kinds.clear();idPrefixes.clear();}}
    @BeforeEach void open()throws Exception {var ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:lookup_scratch_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");try(Connection c=ds.getConnection();Reader r=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(c,r);}var cfg=new Configuration(new Environment("scratch",new JdbcTransactionFactory(),ds));cfg.setDatabaseId("h2");cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);cfg.addInterceptor(statements);for(String name:List.of("RecordMapper","FactMapper"))try(InputStream in=resource("mappers/"+name+".xml")){new XMLMapperBuilder(in,cfg,name,cfg.getSqlFragments()).parse();}session=new SqlSessionFactoryBuilder().build(cfg).openSession(false);store=new Store(session.getMapper(RecordMapper.class),json);var repository=new RelationalFactRepository(session.getMapper(FactMapper.class),json);original=new LegacyFactLedgerLookupFixture(store,repository);candidate=new FactLedgerService(store,repository);}
    @AfterEach void close(){session.rollback();session.close();}
    InputStream resource(String name){return Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(name));}
    Document doc(int count){List<Page> pages=new ArrayList<>();for(int i=0;i<count;i++)pages.add(new Page(i+1,"合成甲 类别等级"+i+"，本页补充原文","PAGE","PDFBOX_NATIVE",""));return new Document("bid","project","BID","合成资料.pdf",1,"a".repeat(64),"",pages,List.of());}
    Map<String,Object> source(Document d,int page,String alias){var p=d.pages().get(page-1);return of("id",alias,"projectId",d.projectId(),"documentHash",d.sha256(),"documentVersion",d.version(),"parserFingerprint",FactLedgerService.parserFingerprint(p),"evidence",new Evidence(d.id(),d.name(),page,"PAGE",p.text(),"chars:0",1));}
    Map<String,Object> fact(String id,String value,List<String> refs){return of("id",id,"kind","FIELD","subject","合成甲","field","category","rawValue",value,"value",value,"unit","","basis","","scope","CURRENT_PROJECT","sourceIds",refs,"attributes",Map.of());}
    Map<String,Object> batch(List<Map<String,Object>> facts,List<Map<String,Object>> sources,String op){return of("facts",facts,"sources",sources,"operationIdentity",op,"engineVersion","engine-v1");}
    void publish(Document d,Map<String,Object> b){original.publish("project","run","cp","engine",List.of(d),b);}
    record Compared(int oldReads,int newReads,List<Map<String,Object>> result,List<String> oldSql,List<String> newSql){}
    Compared compare(Document d,Map<String,Object> req){var manifest=candidate.prepareManifest("project","engine",List.of(d));session.clearCache();statements.reset();var expected=original.lookup("project","engine",List.of(d),req);int before=statements.selects;var oldSql=List.copyOf(statements.sql);session.clearCache();statements.reset();var actual=candidate.lookup(manifest,req);int after=statements.selects;assertEquals(canonical(expected),canonical(withoutNewCapacityDiagnostics(actual)));assertTrue(after<=before);return new Compared(before,after,actual,oldSql,List.copyOf(statements.sql));}
    List<Map<String,Object>> withoutNewCapacityDiagnostics(List<Map<String,Object>> rows){return rows.stream().map(row->{var value=copy(row);for(String key:List.of("lookupTruncated","factLookupTruncated","fieldLookupTruncated","lookupTruncation"))value.remove(key);return value;}).toList();}
    @Test void hundredHistoricalWindowsOnlySelectedSourceHydratesAndNoOutputIsLost()throws Exception {var d=doc(100);for(int i=0;i<100;i++)publish(d,batch(List.of(fact("F"+i,"等级"+i,List.of("S"+i))),List.of(source(d,i+1,"S"+i)),"op"+i));var comparison=compare(d,Map.of("sourceIds",List.of("S42"),"factKinds",List.of("FIELD"),"includeFieldCoverage",true,"limit",200));assertEquals(1,comparison.result().size());assertTrue(comparison.newReads()<comparison.oldReads()/2);write("jdbc-source-prefilter.json",of("fixture","100 synthetic source windows, selected S42, same immutable original document","originalSelectStatements",comparison.oldReads(),"candidateSelectStatements",comparison.newReads(),"resultCanonicalEqual",true,"oldSqlCounts",hist(comparison.oldSql()),"newSqlCounts",hist(comparison.newSql()),"productionDatabaseUsed",false,"productionSourceModified",false));}
    @Test void allSourcesAndUnfilteredQueriesReturnAllFactsWithSameOrder(){var d=doc(6);for(int i=0;i<6;i++)publish(d,batch(List.of(fact("F"+i,"等级"+i,List.of("S"+i))),List.of(source(d,i+1,"S"+i)),"op"+i));assertEquals(6,compare(d,Map.of()).result().size());assertEquals(2,compare(d,Map.of("sourceIds",List.of("S4","S1"))).result().size());assertEquals(1,compare(d,Map.of("limit",1)).result().size());assertEquals(0,compare(d,Map.of("sourceIds",List.of("never"))).result().size());}
    @Test void alternateSourceAliasesRemainUsable(){var d=doc(1);publish(d,batch(List.of(fact("first","等级0",List.of("A"))),List.of(source(d,1,"A")),"a"));publish(d,batch(List.of(fact("second","等级0",List.of("B"))),List.of(source(d,1,"B")),"b"));assertEquals(List.of("B"),map(compare(d,Map.of("sourceIds",List.of("B"))).result().get(0).get("fact")).get("sourceIds"));}
    @Test void multiSourceFactRequiresAllAliasesAndPreservesFullChain(){var d=doc(2);publish(d,batch(List.of(fact("f","等级0",List.of("A","B"))),List.of(source(d,1,"A"),source(d,2,"B")),"multi"));assertTrue(compare(d,Map.of("sourceIds",List.of("A"))).result().isEmpty());assertEquals(1,compare(d,Map.of("sourceIds",List.of("A","B"))).result().size());}
    Map<String,Object> field(String source){var target=of("predicate","FIELD","subject","合成甲","field","category","unit","","parameters",Map.of(),"targetExpected","","children",List.of());return of("observationKey",FieldCoverage.key(target),"target",target,"status","READ_NOT_FOUND","sourceIds",List.of(source),"factIds",List.of(),"sourceScopeComplete",true,"observationPolicy",FieldCoverage.POLICY,"modelIdentity","m","absenceProven",false);}
    @Test void emptyFactCoverageStillReturnedAndNotTreatedAsAbsent(){var d=doc(2);var b=batch(List.of(),List.of(source(d,1,"A")),"read");b.put("fieldCoverage",List.of(field("A")));b.put("observationPolicy",FieldCoverage.POLICY);publish(d,b);var result=compare(d,Map.of("sourceIds",List.of("A"),"factKinds",List.of("CERTIFICATE"),"includeFieldCoverage",true)).result();assertEquals(1,result.size());assertEquals("FIELD_COVERAGE",result.get(0).get("recordType"));assertFalse((Boolean)maps(result.get(0).get("fieldCoverage")).get(0).get("absenceProven"));}
    @Test void corruptBatchKindCannotHideAuthoritativeCoreFact(){var d=doc(1);publish(d,batch(List.of(fact("f","等级0",List.of("A"))),List.of(source(d,1,"A")),"one"));Map<String,Object> b=store.maps("fact_batch","project",null).get(0);var entries=(List<Map<String,Object>>)b.get("entries");((Map<String,Object>)entries.get(0).get("fact")).put("kind","CERTIFICATE");store.save("fact_batch",text(b,"id"),"project","ignored",b);assertEquals(1,compare(d,Map.of("sourceIds",List.of("A"),"factKinds",List.of("FIELD"))).result().size());}
    @Test void sourceFingerprintCorruptionStaysRejected(){var d=doc(1);publish(d,batch(List.of(fact("f","等级0",List.of("A"))),List.of(source(d,1,"A")),"one"));Map<String,Object> b=store.maps("fact_batch","project",null).get(0);var entries=(List<Map<String,Object>>)b.get("entries");var refs=(List<Map<String,Object>>)entries.get(0).get("sources");refs.get(0).put("sourceFingerprint","forged");store.save("fact_batch",text(b,"id"),"project","ignored",b);assertTrue(compare(d,Map.of("sourceIds",List.of("A"))).result().isEmpty());}
    @Test void changedParserAndVersionBuildDifferentManifestAndCannotReuse(){var d=doc(1);publish(d,batch(List.of(fact("f","等级0",List.of("A"))),List.of(source(d,1,"A")),"one"));var changed=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),"",List.of(new Page(1,d.pages().get(0).text(),"PAGE","QWEN_OCR","")),List.of());assertTrue(compare(changed,Map.of("sourceIds",List.of("A"))).result().isEmpty());var newer=new Document(d.id(),d.projectId(),d.role(),d.name(),2,d.sha256(),"",d.pages(),List.of());assertTrue(compare(newer,Map.of("sourceIds",List.of("A"))).result().isEmpty());}
    @Test void differentCompaniesAndInterleavedQueriesDoNotShareTheWrongSource(){var d=new Document("bid","project","BID","合成资料",1,"a".repeat(64),"",List.of(new Page(1,"合成甲 等级甲","PAGE","PDFBOX_NATIVE",""),new Page(2,"合成乙 等级乙","PAGE","PDFBOX_NATIVE","")),List.of());var a=fact("a","等级甲",List.of("A"));var b=fact("b","等级乙",List.of("B"));b.put("subject","合成乙");publish(d,batch(List.of(a),List.of(source(d,1,"A")),"a"));publish(d,batch(List.of(b),List.of(source(d,2,"B")),"b"));var manifest=candidate.prepareManifest("project","engine",List.of(d));for(String id:List.of("A","B","A")){var req=Map.<String,Object>of("sourceIds",List.of(id));assertEquals(canonical(original.lookup("project","engine",List.of(d),req)),canonical(candidate.lookup(manifest,req)));assertEquals(id.equals("A")?"合成甲":"合成乙",map(candidate.lookup(manifest,req).get(0).get("fact")).get("subject"));}}
    @Test void foreignProjectEngineAndDocumentRequestStayIsolated(){var d=doc(1);publish(d,batch(List.of(fact("f","等级0",List.of("A"))),List.of(source(d,1,"A")),"one"));assertEquals(List.of(),candidate.lookup(candidate.prepareManifest("project","other-engine",List.of(d)),Map.of("sourceIds",List.of("A"))));assertEquals(List.of(),original.lookup("project","other-engine",List.of(d),Map.of("sourceIds",List.of("A"))));var manifest=candidate.prepareManifest("project","engine",List.of(d));assertThrows(IllegalArgumentException.class,()->candidate.lookup(manifest,Map.of("documentIds",List.of("other"))));assertThrows(IllegalArgumentException.class,()->original.lookup("project","engine",List.of(d),Map.of("documentIds",List.of("other"))));assertThrows(IllegalArgumentException.class,()->candidate.prepareManifest("other-project","engine",List.of(d)));}
    @Test void largeManifestIdentityAndRepeatedLookupStayEquivalentWithoutDatabaseReadsDuringPreparation(){var d=doc(581);publish(d,batch(List.of(fact("last","等级580",List.of("LAST"))),List.of(source(d,581,"LAST")),"last"));statements.reset();var before=original.prepareManifest("project","engine",List.of(d));var after=candidate.prepareManifest("project","engine",List.of(d));assertEquals(before.identity(),after.identity());assertEquals(0,statements.selects,"Preparing an immutable manifest must not query the database");for(String id:List.of("LAST","unknown","LAST")){var request=Map.<String,Object>of("sourceIds",List.of(id),"limit",200);assertEquals(canonical(original.lookup("project","engine",List.of(d),request)),canonical(candidate.lookup(after,request)));}}
    @Test void acquiredLeaseRecheckReadsFreshIndexAndOriginsWithoutRehydratingImmutableHistory(){
        var d=doc(80);for(int i=0;i<80;i++)publish(d,batch(List.of(fact("F"+i,"等级"+i,List.of("S"+i))),List.of(source(d,i+1,"S"+i)),"op"+i));
        var manifest=candidate.prepareManifest("project","engine",List.of(d));var req=Map.<String,Object>of("sourceIds",List.of("S42"),"includeFieldCoverage",true);
        var first=candidate.lookup(manifest,req);session.clearCache();statements.reset();var second=candidate.lookup(manifest,req);
        assertEquals(canonical(first),canonical(second));assertEquals(2,statements.selects,"Only current index and mutable origins need refreshing after the lease");
        assertTrue(statements.sql.stream().noneMatch(sql->sql.contains("QC_FACT_OCCURRENCE")||sql.contains("QC_RECORD WHERE ID")));
        var same=batch(List.of(fact("F42","等级42",List.of("S42"))),List.of(source(d,43,"S42")),"op42");
        candidate.publish("project","other-run","other-cp","engine",List.of(d),same);
        var refreshed=candidate.lookup(manifest,req);assertEquals(2,maps(refreshed.get(0).get("origins")).size(),"Usage provenance is not cached");
        assertTrue(maps(refreshed.get(0).get("origins")).stream().anyMatch(row->"other-cp".equals(row.get("checkpointId"))));
    }
    @Test void newPublicationAfterCachedMissIsVisibleIncludingEmptyFieldObservation(){
        var d=doc(2);var manifest=candidate.prepareManifest("project","engine",List.of(d));var req=Map.<String,Object>of("sourceIds",List.of("A"),"includeFieldCoverage",true);
        assertTrue(candidate.lookup(manifest,req).isEmpty());
        var b=batch(List.of(),List.of(source(d,1,"A")),"later-read");b.put("fieldCoverage",List.of(field("A")));b.put("observationPolicy",FieldCoverage.POLICY);
        candidate.publish("project","run","cp","engine",List.of(d),b);
        assertEquals("FIELD_COVERAGE",candidate.lookup(manifest,req).get(0).get("recordType"));
        candidate.publish("project","run","cp","engine",List.of(d),batch(List.of(fact("new","等级0",List.of("A"))),List.of(source(d,1,"A")),"later-fact"));
        assertTrue(candidate.lookup(manifest,req).stream().anyMatch(row->!map(row.get("fact")).isEmpty()));
    }
    @Test void sourceIndexSkipsUnrelatedBatchBodiesBeforeFirstHydration(){
        var d=doc(60);for(int i=0;i<60;i++)candidate.publish("project","run","cp","engine",List.of(d),batch(List.of(fact("F"+i,"等级"+i,List.of("S"+i))),List.of(source(d,i+1,"S"+i)),"op"+i));
        var manifest=candidate.prepareManifest("project","engine",List.of(d));session.clearCache();statements.reset();
        var actual=candidate.lookup(manifest,Map.of("sourceIds",List.of("S42")));
        assertEquals(1,actual.size());assertEquals(6,statements.selects,"Index + one batch read + occurrence/entity/source bulk reads + origins");
    }
    @Test void canonicalSqlLookupNeverReadsHistoricalBatchIndexBodiesOrUsage(){
        var d=doc(60);for(int i=0;i<60;i++)candidate.publish("project","run","cp","engine",List.of(d),batch(List.of(fact("F"+i,"等级"+i,List.of("S"+i))),List.of(source(d,i+1,"S"+i)),"op"+i));
        var manifest=candidate.prepareManifest("project","engine",List.of(d));session.clearCache();statements.reset();
        var request=of("lookupMode","CANONICAL_SOURCE","sourceIds",List.of("S42"),"sources",List.of(source(d,43,"S42")),"documentIds",List.of(d.id()),"factKinds",List.of("FIELD"),"limit",240);
        var result=candidate.lookup(manifest,request);assertEquals(1,result.size());assertEquals("等级42",map(result.get(0).get("fact")).get("value"));
        assertTrue(statements.kinds.stream().noneMatch(Set.of("fact_index","fact_batch","fact_origin")::contains),statements.kinds.toString());
        assertFalse(statements.idPrefixes.contains("fact-batch"));assertFalse(statements.idPrefixes.contains("fact-index"));
        assertTrue(statements.selects<12,"Current source bucket lookup must stay bounded independently of 60 audit batches: "+statements.selects);
        assertEquals(false,result.get(0).get("originsIncluded"));
    }
    @Test void canonicalSqlAdoptionIsIndexStableAndNewReadIsVisibleAfterCachedMiss(){
        var d=doc(1);var sources=List.of(source(d,1,"S"));var facts=List.of(fact("f","等级0",List.of("S")));
        var first=batch(facts,sources,"first");first.put("origin","NATIVE");candidate.publish("project","run","cp","engine",List.of(d),first);
        var manifest=candidate.prepareManifest("project","engine",List.of(d));String revision=candidate.lookupRevision(manifest);
        long refs=store.mapper().count("fact_lookup_ref","project",null),items=store.mapper().count("fact_lookup_item","project",null),legacy=store.mapper().count("fact_index","project",null);
        for(int i=0;i<12;i++){var adoption=batch(facts,sources,"reuse-"+i);adoption.put("origin","REUSED");candidate.publish("project","run","cp-"+i,"engine",List.of(d),adoption);}
        assertEquals(refs,store.mapper().count("fact_lookup_ref","project",null));assertEquals(items,store.mapper().count("fact_lookup_item","project",null));assertEquals(legacy,store.mapper().count("fact_index","project",null));assertEquals(revision,candidate.lookupRevision(manifest));
        var receipt=field("S");var request=of("lookupMode","CANONICAL_SOURCE","sourceIds",List.of("S"),"sources",sources,"documentIds",List.of(d.id()),"factKinds",List.of("CERTIFICATE"),"includeFieldCoverage",true,"modelIdentity","m","observationKeys",List.of(receipt.get("observationKey")));
        assertTrue(candidate.lookup(manifest,request).isEmpty());var observed=batch(List.of(),sources,"new-read");observed.put("origin","EXTRACTED");observed.put("fieldCoverage",List.of(receipt));observed.put("observationPolicy",FieldCoverage.POLICY);candidate.publish("project","run","new-read","engine",List.of(d),observed);
        assertNotEquals(revision,candidate.lookupRevision(manifest));var result=candidate.lookup(manifest,request);assertEquals(1,result.size());assertEquals("READ_NOT_FOUND",maps(result.get(0).get("fieldCoverage")).get(0).get("status"));
    }
    @Test void requiredCoreReadFailureStillEscapesInsteadOfReturningMissingEvidence(){requiredReadFailure("QC_FACT_OCCURRENCE WHERE");}
    @Test void requiredBatchReadFailureStillEscapesInsteadOfReturningMissingEvidence(){requiredReadFailure("QC_RECORD WHERE ID");}
    void requiredReadFailure(String sql){var d=doc(1);publish(d,batch(List.of(fact("f","等级0",List.of("A"))),List.of(source(d,1,"A")),"one"));var manifest=candidate.prepareManifest("project","engine",List.of(d));var req=Map.<String,Object>of("sourceIds",List.of("A"),"includeFieldCoverage",true);statements.failSql=sql;
        session.clearCache();RuntimeException before=assertThrows(RuntimeException.class,()->original.lookup("project","engine",List.of(d),req));assertTrue(messageChain(before).contains("SYNTHETIC_REQUIRED_READ_FAILED"));
        session.clearCache();RuntimeException after=assertThrows(RuntimeException.class,()->candidate.lookup(manifest,req));assertTrue(messageChain(after).contains("SYNTHETIC_REQUIRED_READ_FAILED"));statements.failSql="";
        assertEquals(1,compare(d,req).result().size(),"After the database recovers, required evidence is still available; a failure did not persist absence.");
    }
    String messageChain(Throwable error){StringBuilder out=new StringBuilder();for(Throwable cause=error;cause!=null;cause=cause.getCause())out.append(cause.getMessage()).append(' ');return out.toString();}
    Map<String,Long> hist(List<String> sql){Map<String,Long> out=new TreeMap<>();for(String s:sql)out.merge(s,1L,Long::sum);return out;}
    void write(String file,Object value)throws Exception {String folder=System.getProperty("lookup.replay.output");if(folder==null)return;Path out=Path.of(folder);Files.createDirectories(out);json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(file).toFile(),value);}
}
