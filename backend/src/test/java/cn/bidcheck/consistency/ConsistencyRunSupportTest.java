package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.facts.FactLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

class ConsistencyRunSupportTest {
    final MemoryStore store=new MemoryStore();
    final FactLedgerService ledger=new FactLedgerService(store,FactRepository.memoryForTests());
    Document doc(String id,String number){return new Document(id,"project","BID",id+".pdf",1,cn.bidcheck.compat.Java8Api.repeat((id.equals("a")?"a":"b"), 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"机动车驾驶证\n姓名：合成甲\n驾驶证号码："+number,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());}

    @Test void publishesSeparateVolumeEvidenceWithoutCollidingSameNumberedPages() {
        Document a=doc("a","110101199001010011"),b=doc("b","110101199001010038");
        List<Document> docs=cn.bidcheck.compat.Java8Api.listOf(a, b);Map<String,Object> report=new PersonCredentialConsistency().scan(docs);
        publish(store,ledger,"run",docs,"engine",report);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("a", "b"),report.get("documentIds"));assertEquals(2,maps(report.get("sourceManifest")).size());
        Map<String,Object> snapshot=ledger.view("project","run","engine",docs);
        assertEquals(4,((Number)map(snapshot.get("summary")).get("occurrences")).intValue());
        Set<String> sourceIds=new HashSet<>();
        for(Map<String,Object> occurrence:maps(snapshot.get("occurrences"))) for(Map<String,Object> source:maps(occurrence.get("sources"))) sourceIds.add(string(map(source.get("evidence")),"documentId"));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("a", "b"),sourceIds);
        assertEquals(1,maps(report.get("groups")).size());
    }

    @Test void sourceDocumentMustBelongToTheManifestEvenWhenPageAndQuoteMatch() {
        Document a=doc("a","110101199001010011"),b=doc("b","110101199001010011");
        java.util.Map<java.lang.String, java.lang.Object> report=new PersonCredentialConsistency().scan(b);
        assertThrows(IllegalArgumentException.class,()->publish(store,ledger,"run",cn.bidcheck.compat.Java8Api.listOf(a),"engine",report));
    }

    @Test void refusesMixedProjectsBeforePublishingAnything() {
        Document a=doc("a","110101199001010011"),first=doc("b","110101199001010038");
        Document b=new Document(first.id(),"foreign",first.role(),first.name(),first.version(),first.sha256(),first.createdAt(),first.pages(),first.warnings());
        assertThrows(IllegalArgumentException.class,()->publish(store,ledger,"run",cn.bidcheck.compat.Java8Api.listOf(a, b),"engine",new PersonCredentialConsistency().scan(cn.bidcheck.compat.Java8Api.listOf(a, b))));
        assertTrue(store.rows.isEmpty());
    }

    @Test void singleDocumentOverloadRetainsExistingSnapshotContract() {
        Document a=doc("a","110101199001010011");java.util.Map<java.lang.String, java.lang.Object> report=new PersonCredentialConsistency().scan(a);
        publish(store,ledger,"run",a,"engine",report);
        assertEquals(a.id(),report.get("documentId"));assertEquals(a.sha256(),report.get("documentHash"));assertEquals(a.version(),report.get("documentVersion"));
        assertNotNull(report.get("ledgerSnapshotId"));
    }

    @Test void baselinePublicationLeavesLedgerWritableUntilAllChecksFinish() {
        Document a=doc("a","110101199001010011");List<Document> docs=cn.bidcheck.compat.Java8Api.listOf(a);
        java.util.Map<java.lang.String, java.lang.Object> baseline=new PersonCredentialConsistency().scan(a);
        publish(store,ledger,"run",docs,"engine",baseline,false);
        assertEquals(false,baseline.get("ledgerFrozen"));assertFalse(baseline.containsKey("ledgerSnapshotId"));
        assertEquals(false,ledger.view("project","run","engine",docs).get("frozen"));
        java.util.Map<java.lang.String, java.lang.Object> completed=new PersonCredentialConsistency().scan(a);
        publish(store,ledger,"run",docs,"engine",completed);
        assertEquals(true,completed.get("ledgerFrozen"));
        assertEquals(2,((Number)map(ledger.view("project","run","engine",docs).get("summary")).get("occurrences")).intValue());
    }

    @Test void htmlEntitiesKeepLiteralRawEvidenceAndPublishWithoutExceedingReferenceBudget() {
        String text="本次投标函\n<table><tr><th>投标人名称</th><th>备注</th></tr><tr><td>甲&amp;乙公司</td><td>无</td></tr><tr><td>甲&乙公司</td><td>无</td></tr></table>";
        Document d=new Document("html","project","BID","html.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(d,cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> companies=cn.bidcheck.compat.Java8Api.toList(maps(report.get("occurrences")).stream().filter(o->"companyName".equals(o.get("field"))));
        assertEquals(2,companies.size());assertEquals(1,companies.stream().map(o->o.get("value")).distinct().count());
        assertTrue(companies.stream().anyMatch(o->"甲&amp;乙公司".equals(o.get("rawValue"))));
        for(Map<String,Object> occurrence:companies){assertTrue(((Evidence)occurrence.get("evidence")).quote().contains(occurrence.get("rawValue").toString()));assertTrue(((List<?>)occurrence.get("contextEvidences")).size()<=15);assertFalse(occurrence.containsKey("contextQuote"));}
        assertDoesNotThrow(()->publish(store,ledger,"html-run",d,"engine",report));
    }

    @Test void declaredConsortiumAssignmentAndExactSourcesSurviveVersionBoundPublication() {
        String cover="投标文件\n项目名称：示范装修工程\n本次投标函\n投标人名称：甲建设有限公司\n投标人名称：乙设计有限公司";
        String agreement="联合体协议书\n甲建设有限公司、乙设计有限公司[所有成员单位名称]自愿组成：本项目联合体。\n共同投标示范装修工程。\n甲建设有限公司为联合体牵头人；乙设计有限公司为联合体成员。";
        Document d=new Document("consortium","project","BID","consortium.pdf",3,cn.bidcheck.compat.Java8Api.repeat("c", 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,cover,"PAGE","PDFBOX",null), new Page(2,agreement,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(d,cn.bidcheck.compat.Java8Api.listOf());
        assertDoesNotThrow(()->publish(store,ledger,"member-run",d,"engine",report));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> companies=cn.bidcheck.compat.Java8Api.toList(maps(ledger.view("project","member-run","engine",cn.bidcheck.compat.Java8Api.listOf(d)).get("occurrences")).stream().filter(o->"companyName".equals(o.get("field"))));
        assertEquals(2,companies.size());
        for(Map<String,Object> occurrence:companies) {
            java.util.Map<java.lang.String, java.lang.Object> assignment=map(map(occurrence.get("attributes")).get("subjectAssignment"));
            assertEquals("DECLARED_CONSORTIUM_MEMBER",assignment.get("status"));assertEquals(false,assignment.get("identityVerified"));
            assertEquals("BIDDER_MEMBER:"+assignment.get("memberName"),occurrence.get("subject"));
            assertTrue(maps(assignment.get("evidences")).stream().anyMatch(e->((Number)e.get("page")).intValue()==2&&string(e,"quote").contains("所有成员单位名称")));
            for(Map<String,Object> source:maps(occurrence.get("sources"))) {
                assertEquals(3,((Number)source.get("documentVersion")).intValue());assertEquals(d.sha256(),source.get("documentHash"));
                java.util.Map<java.lang.String, java.lang.Object> evidence=map(source.get("evidence"));int page=((Number)evidence.get("page")).intValue();
                assertEquals(d.id(),evidence.get("documentId"));assertTrue(d.pages().get(page-1).text().contains(string(evidence,"quote")));
            }
        }
    }

    private static final class MemoryStore extends Store {
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
        final Map<String,Row> rows=new LinkedHashMap<>();
        MemoryStore(){super(null,new ObjectMapper());}
        @Override public void save(String kind,String id,String project,String parent,Object value){rows.put(id,new Row(kind,project,parent,map(encode(value))));}
        @Override public Map<String,Object> getMap(String kind,String id){Row row=rows.get(id);if(row==null||!kind.equals(row.kind))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return map(encode(row.value));}
        @Override public List<Map<String,Object>> maps(String kind,String project,String parent){return cn.bidcheck.compat.Java8Api.toList(rows.values().stream().filter(r->kind.equals(r.kind)&&(project==null||project.equals(r.project))&&(parent==null||parent.equals(r.parent))).map(r->map(encode(r.value))));}
    }
}
