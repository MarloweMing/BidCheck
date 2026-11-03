package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalFactLookupTest {
    final FactLedgerServiceTest data=new FactLedgerServiceTest();
    final TracingStore store=new TracingStore();final FactLedgerService ledger=new FactLedgerService(store);
    Document doc(){return data.doc("bid","合成甲 类别：一级、二级、三级");}
    Map<String,Object> publish(Document doc,String op,List<Map<String,Object>> facts,List<Map<String,Object>> sources,List<Map<String,Object>> fields,String origin){
        Map<String,Object> batch=data.batch(facts,sources);batch.put("operationIdentity",op);batch.put("fieldCoverage",fields);batch.put("origin",origin);batch.put("observationPolicy",FieldCoverage.POLICY);
        return ledger.publish("project","run",op,"engine",cn.bidcheck.compat.Java8Api.listOf(doc),batch);
    }
    Map<String,Object> request(Document doc,List<Map<String,Object>> sources,boolean fields){return of("lookupMode","CANONICAL_SOURCE","sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(s->text(s,"id"))),"sources",sources,"factKinds",cn.bidcheck.compat.Java8Api.listOf("FIELD"),"documentIds",cn.bidcheck.compat.Java8Api.listOf(doc.id()),"includeFieldCoverage",fields,"observationKeys",cn.bidcheck.compat.Java8Api.listOf(text(data.fieldReceipt("category","READ_NOT_FOUND",cn.bidcheck.compat.Java8Api.listOf()),"observationKey")),"modelIdentity","model","limit",240,"fieldCoverageLimit",240);}
    List<Map<String,Object>> lookup(Document doc,Map<String,Object> req){return ledger.lookup(ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc)),req);}

    @Test void invalidExplicitFieldIdentityFailsBeforeAnyDatabaseOrLegacyRead(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");
        for(String missing:cn.bidcheck.compat.Java8Api.listOf("observationKeys", "modelIdentity")){
            Map<String,Object> req=request(d,sources,true);req.remove(missing);store.reads.clear();
            assertEquals("FIELD_LOOKUP_IDENTITY_REQUIRED",assertThrows(IllegalArgumentException.class,()->lookup(d,req)).getMessage());
            assertTrue(store.reads.isEmpty(),"An invalid target must not enumerate historical batches: "+store.reads);
        }
        Map<String,Object> factsOnly=request(d,sources,false);factsOnly.remove("observationKeys");factsOnly.remove("modelIdentity");store.reads.clear();
        assertEquals(1,lookup(d,factsOnly).size());assertFalse(store.reads.contains("fact_index"));assertFalse(store.reads.contains("fact_batch"));
    }

    @Test void repeatedAdoptionAddsUsageWithoutGrowingSearchIndexOrEvidenceRevision(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));java.util.List<java.util.Map<java.lang.String, java.lang.Object>> facts=cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S"));java.util.Map<java.lang.String, java.lang.Object> field=data.fieldReceipt("category","EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("f"));
        java.util.Map<java.lang.String, java.lang.Object> initial=publish(d,"original",facts,sources,cn.bidcheck.compat.Java8Api.listOf(field),"EXTRACTED");cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(d));
        assertEquals(2,maps(initial.get("changedEvidence")).size());assertEquals("category",map(maps(initial.get("changedEvidence")).get(0).get("fact")).get("field"));
        String revision=ledger.lookupRevision(manifest);assertFalse(cn.bidcheck.compat.Java8Api.isBlank(revision));Map<String,Long> before=store.searchCounts();
        for(int i=0;i<20;i++){Map<String,Object> receipt=copy(field);receipt.put("reusedObservation",true);receipt.put("reusedFromBatchId",initial.get("batchId"));assertTrue(maps(publish(d,"adopt-"+i,facts,sources,cn.bidcheck.compat.Java8Api.listOf(receipt),"REUSED_OBSERVATION").get("changedEvidence")).isEmpty());}
        assertEquals(before,store.searchCounts());assertEquals(revision,ledger.lookupRevision(manifest));
        assertEquals(21,store.count("fact_usage"));assertEquals(21,store.count("fact_coverage_usage"));
        Map<String,Object> auditRequest=request(d,sources,true);auditRequest.put("includeOrigins",true);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=lookup(d,auditRequest);assertEquals(2,rows.size());assertEquals(21,maps(rows.get(0).get("origins")).size());
        assertEquals(21,maps(ledger.view("project","run","engine",cn.bidcheck.compat.Java8Api.listOf(d)).get("dependencies")).size());
    }
    @Test void readyLookupLoadsOnlySourceBucketsAndNeverAuditBatchBodies(){
        Document d=doc();for(int i=0;i<80;i++)publish(d,"original-"+i,cn.bidcheck.compat.Java8Api.listOf(data.fact("f"+i,"一级","S"+i)),cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"+i)),cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");
        // Same text/source fingerprint is deliberately one canonical occurrence with many aliases.
        store.reads.clear();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=lookup(d,request(d,cn.bidcheck.compat.Java8Api.listOf(data.source(d,"CURRENT")),false));assertEquals(1,rows.size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("CURRENT"),map(rows.get(0).get("fact")).get("sourceIds"));
        assertTrue(store.reads.stream().noneMatch(v->v.equals("fact_index")||v.equals("fact_batch")||v.equals("fact_origin")),store.reads.toString());
    }
    @Test void currentContractPrefilterAndFailedAttemptCannotEraseCompletedObservation(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));java.util.Map<java.lang.String, java.lang.Object> read=data.fieldReceipt("category","READ_NOT_FOUND",cn.bidcheck.compat.Java8Api.listOf());
        publish(d,"read",cn.bidcheck.compat.Java8Api.listOf(),sources,cn.bidcheck.compat.Java8Api.listOf(read),"EXTRACTED");cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(d));String revision=ledger.lookupRevision(manifest);
        publish(d,"failure",cn.bidcheck.compat.Java8Api.listOf(),sources,cn.bidcheck.compat.Java8Api.listOf(data.fieldReceipt("category","FAILED",cn.bidcheck.compat.Java8Api.listOf())),"FAILED");
        assertEquals(revision,ledger.lookupRevision(manifest));assertEquals(1,lookup(d,request(d,sources,true)).size());
        Map<String,Object> incompatible=request(d,sources,true);incompatible.put("modelIdentity","different-contract");assertTrue(lookup(d,incompatible).isEmpty());
        assertEquals(2,maps(ledger.view("project","run","engine",cn.bidcheck.compat.Java8Api.listOf(d)).get("fieldCoverage")).size(),"Failure remains auditable without becoming reusable completion");
    }
    @Test void crossSourceFactAndReceiptRequireAllDependenciesAndRemapOnlyExactSources(){
        Document d=new Document("bid","project","BID","bid.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"合成甲 类别：一级","PAGE","PDFBOX_NATIVE",""), new Page(2,"合成甲 说明：二级","PAGE","PDFBOX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> a=data.source(d,"A"),b=of("id","B","projectId","project","documentHash",d.sha256(),"documentVersion",1,"parserFingerprint",FactLedgerService.parserFingerprint(d.pages().get(1)),"evidence",new Evidence("bid","bid.pdf",2,"PAGE",d.pages().get(1).text(),"chars:0",1));
        Map<String,Object> fact=data.fact("f","一级","A");fact.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("A", "B"));Map<String,Object> receipt=data.fieldReceipt("category","EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("f"));receipt.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("A", "B"));
        publish(d,"multi",cn.bidcheck.compat.Java8Api.listOf(fact),cn.bidcheck.compat.Java8Api.listOf(a, b),cn.bidcheck.compat.Java8Api.listOf(receipt),"EXTRACTED");assertTrue(lookup(d,request(d,cn.bidcheck.compat.Java8Api.listOf(a),true)).isEmpty());
        Map<String,Object> a2=copy(a),b2=copy(b);a2.put("id","X");b2.put("id","Y");java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=lookup(d,request(d,cn.bidcheck.compat.Java8Api.listOf(a2, b2),true));
        assertEquals(2,rows.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("X", "Y"),map(rows.get(0).get("fact")).get("sourceIds"));
        java.util.Map<java.lang.String, java.lang.Object> field=maps(rows.get(1).get("fieldCoverage")).get(0);assertEquals(cn.bidcheck.compat.Java8Api.listOf(rows.get(0).get("occurrenceId")),field.get("factIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("X", "Y"),field.get("sourceIds"));
        Map<String,Object> narrowed=copy(a2);narrowed.put("evidence",new Evidence("bid","bid.pdf",1,"PAGE","类别：一级","chars:4",1));assertTrue(lookup(d,request(d,cn.bidcheck.compat.Java8Api.listOf(narrowed, b2),true)).isEmpty());
    }
    @Test void corruptReferenceFallsBackAndCannotSilentlyClaimNoEvidence(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");
        store.rows.values().stream().filter(r->r.kind.equals("fact_lookup_ref")).forEach(r->r.value.put("itemDigest","corrupt"));
        store.reads.clear();assertEquals(1,lookup(d,request(d,sources,false)).size());assertTrue(store.reads.contains("fact_index"));
    }
    @Test void interruptedBackfillKeepsLegacyReadableAndNewPublicationsAreNotLost(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));
        for(int i=0;i<4;i++)publish(d,"original-"+i,cn.bidcheck.compat.Java8Api.listOf(data.fact("f"+i,i%2==0?"一级":"二级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");
        store.removeLookup();cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(d));assertEquals("",ledger.lookupRevision(manifest));
        java.util.Map<java.lang.String, java.lang.Object> first=ledger.migrateLookupIndex(manifest,1);assertEquals(1,first.get("processed"));assertEquals("",ledger.lookupRevision(manifest));assertEquals(2,lookup(d,request(d,sources,false)).size());
        publish(d,"new-during-migration",cn.bidcheck.compat.Java8Api.listOf(data.fact("new","三级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(ledger.lookupRevision(manifest)));store.reads.clear();assertEquals(3,lookup(d,request(d,sources,false)).size());assertFalse(store.reads.contains("fact_index"));
    }
    @Test void invalidLegacyBatchBlocksReadinessRatherThanHidingIncompleteMigration(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");store.removeLookup();
        store.rows.values().stream().filter(r->r.kind.equals("fact_batch")).forEach(r->r.value.put("projectId","foreign"));
        cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(d));java.util.Map<java.lang.String, java.lang.Object> status=ledger.migrateLookupIndex(manifest,10);
        assertEquals("BLOCKED",maps(status.get("documents")).get(0).get("status"));assertEquals("",ledger.lookupRevision(manifest));
    }
    @Test void obsoleteFieldPolicyIsExplicitlyExcludedWithoutInvalidatingGroundedFacts(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(data.fieldReceipt("category","EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("f"))),"EXTRACTED");store.removeLookup();
        store.rows.values().stream().filter(r->r.kind.equals("fact_batch")).forEach(r->{List<Map<String,Object>> fields=maps(r.value.get("fieldCoverage"));fields.get(0).put("observationPolicy","obsolete-policy");r.value.put("fieldCoverage",fields);});
        cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(d));ledger.migrateLookupIndex(manifest,50);assertFalse(cn.bidcheck.compat.Java8Api.isBlank(ledger.lookupRevision(manifest)));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=lookup(d,request(d,sources,true));assertEquals(1,rows.size());assertTrue(rows.get(0).containsKey("fact"));
        assertEquals(1,store.rows.values().stream().filter(r->r.kind.equals("fact_lookup_state")).findFirst().orElseThrow(java.util.NoSuchElementException::new).value.get("obsoleteObservationsExcluded"));
    }
    @Test void factLimitDoesNotPublishReceiptWhoseDependencyWasTruncated(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("a","一级","S"), data.fact("b","二级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(data.fieldReceipt("category","EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("a", "b"))),"EXTRACTED");
        Map<String,Object> request=request(d,sources,true);request.put("limit",1);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result=lookup(d,request);assertEquals(1,result.size());assertEquals(true,result.get(0).get("lookupTruncated"));assertEquals(true,result.get(0).get("factLookupTruncated"));assertEquals(false,result.get(0).get("fieldLookupTruncated"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> legacy=ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),of("sourceIds",cn.bidcheck.compat.Java8Api.listOf("S"),"limit",1,"includeFieldCoverage",true));assertEquals(true,legacy.get(0).get("factLookupTruncated"),"Legacy fallback exposes the same incomplete fact set to its consumer");
    }
    @Test void receiptCapacityDoesNotMislabelTheCompleteFactSetAsTruncated(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));java.util.List<java.util.Map<java.lang.String, java.lang.Object>> fields=cn.bidcheck.compat.Java8Api.listOf(data.fieldReceipt("category","EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("f")), data.fieldReceipt("employer","READ_NOT_FOUND",cn.bidcheck.compat.Java8Api.listOf()));
        publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S")),sources,fields,"EXTRACTED");Map<String,Object> request=request(d,sources,true);request.put("observationKeys",cn.bidcheck.compat.Java8Api.toList(fields.stream().map(f->text(f,"observationKey"))));request.put("fieldCoverageLimit",1);
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result=lookup(d,request);assertEquals(2,result.size());assertTrue(result.stream().allMatch(row->Boolean.TRUE.equals(row.get("fieldLookupTruncated"))&&Boolean.FALSE.equals(row.get("factLookupTruncated"))));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> legacy=ledger.lookup("project","engine",cn.bidcheck.compat.Java8Api.listOf(d),of("sourceIds",cn.bidcheck.compat.Java8Api.listOf("S"),"limit",240,"fieldCoverageLimit",1,"includeFieldCoverage",true));assertTrue(legacy.stream().allMatch(row->Boolean.TRUE.equals(row.get("fieldLookupTruncated"))&&Boolean.FALSE.equals(row.get("factLookupTruncated"))));
    }
    @Test void subjectIndexFindsFactsBeforeTheirSourceIsSelectedAndPreservesSourceIdentity(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> original=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"OLD"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","OLD")),original,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");
        Map<String,Object> request=request(d,cn.bidcheck.compat.Java8Api.listOf(data.source(d,"CURRENT")),false);request.put("lookupMode","CANONICAL_SUBJECT");request.put("subjects",cn.bidcheck.compat.Java8Api.listOf("合成甲"));
        store.reads.clear();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=lookup(d,request);assertEquals(1,rows.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("CURRENT"),map(rows.get(0).get("fact")).get("sourceIds"));
        assertTrue(store.reads.stream().noneMatch(v->cn.bidcheck.compat.Java8Api.setOf("fact_index", "fact_batch", "fact_origin").contains(v)),store.reads.toString());
        request.put("subjects",cn.bidcheck.compat.Java8Api.listOf("无关乙"));assertTrue(lookup(d,request).isEmpty());
        request.put("subjects",cn.bidcheck.compat.Java8Api.listOf("合成甲"));java.util.Map<java.lang.String, java.lang.Object> narrowed=data.source(d,"CURRENT");narrowed.put("evidence",new Evidence(d.id(),d.name(),1,"PAGE","类别：一级","chars:4",1));request.put("sources",cn.bidcheck.compat.Java8Api.listOf(narrowed));
        assertTrue(lookup(d,request).isEmpty(),"Subject discovery never relaxes the exact current source dependency");
    }
    @Test void subjectIndexFallbackAndMigrationKeepOldFactsReadable(){
        Document d=doc();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=cn.bidcheck.compat.Java8Api.listOf(data.source(d,"S"));publish(d,"original",cn.bidcheck.compat.Java8Api.listOf(data.fact("f","一级","S")),sources,cn.bidcheck.compat.Java8Api.listOf(),"NATIVE");store.removeLookup();
        Map<String,Object> request=request(d,sources,false);request.put("lookupMode","CANONICAL_SUBJECT");request.put("subjects",cn.bidcheck.compat.Java8Api.listOf("合成甲"));
        assertEquals(1,lookup(d,request).size());assertTrue(store.reads.contains("fact_index"));
        ledger.migrateLookupIndex(ledger.prepareManifest("project","engine",cn.bidcheck.compat.Java8Api.listOf(d)),50);store.reads.clear();assertEquals(1,lookup(d,request).size());assertFalse(store.reads.contains("fact_index"));
        request.put("subjects",cn.bidcheck.compat.Java8Api.listOf("其他人"));assertTrue(lookup(d,request).isEmpty());
    }
    static class TracingStore extends Store {
        @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Row{
        private final String kind;
        private final String project;
        private final String parent;
        private final Map<String,Object> value;
        @com.fasterxml.jackson.annotation.JsonCreator
        Row(@com.fasterxml.jackson.annotation.JsonProperty("kind") String kind, @com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("parent") String parent, @com.fasterxml.jackson.annotation.JsonProperty("value") Map<String,Object> value) { this.kind = kind; this.project = project; this.parent = parent; this.value = value; }
        public String kind() { return kind; }
        public String project() { return project; }
        public String parent() { return parent; }
        public Map<String,Object> value() { return value; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Row that = (Row) other; return java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.parent, that.parent) && java.util.Objects.equals(this.value, that.value); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.parent); hash = 31 * hash + java.util.Objects.hashCode(this.value); return hash; }
        @Override public String toString() { return "Row[" + "kind=" + kind + ", project=" + project + ", parent=" + parent + ", value=" + value + "]"; }
}
        final Map<String,Row> rows=new LinkedHashMap<>();final List<String> reads=new ArrayList<>();TracingStore(){super(null,new ObjectMapper());}
        @Override public void save(String kind,String id,String project,String parent,Object value){assertTrue(id.length()<=64);assertTrue(parent==null||parent.length()<=64);rows.put(id,new Row(kind,project,parent,LedgerValues.map(json.convertValue(value,Map.class))));}
        @Override public Map<String,Object> getMap(String kind,String id){reads.add(kind);Row row=rows.get(id);if(row==null||!row.kind.equals(kind))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return copy(row.value);}
        @Override public List<Map<String,Object>> maps(String kind,String project,String parent){reads.add(kind);return cn.bidcheck.compat.Java8Api.toList(rows.values().stream().filter(r->r.kind.equals(kind)&&(project==null||project.equals(r.project))&&(parent==null||parent.equals(r.parent))).map(r->copy(r.value)));}
        long count(String kind){return rows.values().stream().filter(r->r.kind.equals(kind)).count();}
        Map<String,Long> searchCounts(){Map<String,Long> result=new TreeMap<>();for(String kind:cn.bidcheck.compat.Java8Api.listOf("fact_index", "fact_lookup_item", "fact_lookup_ref", "field_lookup_ref"))result.put(kind,count(kind));return result;}
        void removeLookup(){rows.entrySet().removeIf(e->e.getValue().kind.startsWith("fact_lookup_")||e.getValue().kind.equals("field_lookup_ref"));}
    }
}
