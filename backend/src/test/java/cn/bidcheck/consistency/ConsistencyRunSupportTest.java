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
    Document doc(String id,String number){return new Document(id,"project","BID",id+".pdf",1,(id.equals("a")?"a":"b").repeat(64),"",List.of(new Page(1,"机动车驾驶证\n姓名：合成甲\n驾驶证号码："+number,"PAGE","PDFBOX",null)),List.of());}

    @Test void publishesSeparateVolumeEvidenceWithoutCollidingSameNumberedPages() {
        Document a=doc("a","110101199001010011"),b=doc("b","110101199001010038");
        List<Document> docs=List.of(a,b);Map<String,Object> report=new PersonCredentialConsistency().scan(docs);
        publish(store,ledger,"run",docs,"engine",report);
        assertEquals(List.of("a","b"),report.get("documentIds"));assertEquals(2,maps(report.get("sourceManifest")).size());
        Map<String,Object> snapshot=ledger.view("project","run","engine",docs);
        assertEquals(4,((Number)map(snapshot.get("summary")).get("occurrences")).intValue());
        Set<String> sourceIds=new HashSet<>();
        for(Map<String,Object> occurrence:maps(snapshot.get("occurrences"))) for(Map<String,Object> source:maps(occurrence.get("sources"))) sourceIds.add(string(map(source.get("evidence")),"documentId"));
        assertEquals(Set.of("a","b"),sourceIds);
        assertEquals(1,maps(report.get("groups")).size());
    }

    @Test void sourceDocumentMustBelongToTheManifestEvenWhenPageAndQuoteMatch() {
        Document a=doc("a","110101199001010011"),b=doc("b","110101199001010011");
        var report=new PersonCredentialConsistency().scan(b);
        assertThrows(IllegalArgumentException.class,()->publish(store,ledger,"run",List.of(a),"engine",report));
    }

    @Test void refusesMixedProjectsBeforePublishingAnything() {
        Document a=doc("a","110101199001010011"),first=doc("b","110101199001010038");
        Document b=new Document(first.id(),"foreign",first.role(),first.name(),first.version(),first.sha256(),first.createdAt(),first.pages(),first.warnings());
        assertThrows(IllegalArgumentException.class,()->publish(store,ledger,"run",List.of(a,b),"engine",new PersonCredentialConsistency().scan(List.of(a,b))));
        assertTrue(store.rows.isEmpty());
    }

    @Test void singleDocumentOverloadRetainsExistingSnapshotContract() {
        Document a=doc("a","110101199001010011");var report=new PersonCredentialConsistency().scan(a);
        publish(store,ledger,"run",a,"engine",report);
        assertEquals(a.id(),report.get("documentId"));assertEquals(a.sha256(),report.get("documentHash"));assertEquals(a.version(),report.get("documentVersion"));
        assertNotNull(report.get("ledgerSnapshotId"));
    }

    @Test void baselinePublicationLeavesLedgerWritableUntilAllChecksFinish() {
        Document a=doc("a","110101199001010011");List<Document> docs=List.of(a);
        var baseline=new PersonCredentialConsistency().scan(a);
        publish(store,ledger,"run",docs,"engine",baseline,false);
        assertEquals(false,baseline.get("ledgerFrozen"));assertFalse(baseline.containsKey("ledgerSnapshotId"));
        assertEquals(false,ledger.view("project","run","engine",docs).get("frozen"));
        var completed=new PersonCredentialConsistency().scan(a);
        publish(store,ledger,"run",docs,"engine",completed);
        assertEquals(true,completed.get("ledgerFrozen"));
        assertEquals(2,((Number)map(ledger.view("project","run","engine",docs).get("summary")).get("occurrences")).intValue());
    }

    @Test void htmlEntitiesKeepLiteralRawEvidenceAndPublishWithoutExceedingReferenceBudget() {
        String text="本次投标函\n<table><tr><th>投标人名称</th><th>备注</th></tr><tr><td>甲&amp;乙公司</td><td>无</td></tr><tr><td>甲&乙公司</td><td>无</td></tr></table>";
        Document d=new Document("html","project","BID","html.pdf",1,"a".repeat(64),"",List.of(new Page(1,text,"PAGE","PDFBOX",null)),List.of());
        var report=new ConsistencyEngine().scan(d,List.of());
        var companies=maps(report.get("occurrences")).stream().filter(o->"companyName".equals(o.get("field"))).toList();
        assertEquals(2,companies.size());assertEquals(1,companies.stream().map(o->o.get("value")).distinct().count());
        assertTrue(companies.stream().anyMatch(o->"甲&amp;乙公司".equals(o.get("rawValue"))));
        for(Map<String,Object> occurrence:companies){assertTrue(((Evidence)occurrence.get("evidence")).quote().contains(occurrence.get("rawValue").toString()));assertTrue(((List<?>)occurrence.get("contextEvidences")).size()<=15);assertFalse(occurrence.containsKey("contextQuote"));}
        assertDoesNotThrow(()->publish(store,ledger,"html-run",d,"engine",report));
    }

    @Test void declaredConsortiumAssignmentAndExactSourcesSurviveVersionBoundPublication() {
        String cover="投标文件\n项目名称：示范装修工程\n本次投标函\n投标人名称：甲建设有限公司\n投标人名称：乙设计有限公司";
        String agreement="联合体协议书\n甲建设有限公司、乙设计有限公司[所有成员单位名称]自愿组成：本项目联合体。\n共同投标示范装修工程。\n甲建设有限公司为联合体牵头人；乙设计有限公司为联合体成员。";
        Document d=new Document("consortium","project","BID","consortium.pdf",3,"c".repeat(64),"",List.of(new Page(1,cover,"PAGE","PDFBOX",null),new Page(2,agreement,"PAGE","PDFBOX",null)),List.of());
        var report=new ConsistencyEngine().scan(d,List.of());
        assertDoesNotThrow(()->publish(store,ledger,"member-run",d,"engine",report));
        var companies=maps(ledger.view("project","member-run","engine",List.of(d)).get("occurrences")).stream().filter(o->"companyName".equals(o.get("field"))).toList();
        assertEquals(2,companies.size());
        for(Map<String,Object> occurrence:companies) {
            var assignment=map(map(occurrence.get("attributes")).get("subjectAssignment"));
            assertEquals("DECLARED_CONSORTIUM_MEMBER",assignment.get("status"));assertEquals(false,assignment.get("identityVerified"));
            assertEquals("BIDDER_MEMBER:"+assignment.get("memberName"),occurrence.get("subject"));
            assertTrue(maps(assignment.get("evidences")).stream().anyMatch(e->((Number)e.get("page")).intValue()==2&&string(e,"quote").contains("所有成员单位名称")));
            for(Map<String,Object> source:maps(occurrence.get("sources"))) {
                assertEquals(3,((Number)source.get("documentVersion")).intValue());assertEquals(d.sha256(),source.get("documentHash"));
                var evidence=map(source.get("evidence"));int page=((Number)evidence.get("page")).intValue();
                assertEquals(d.id(),evidence.get("documentId"));assertTrue(d.pages().get(page-1).text().contains(string(evidence,"quote")));
            }
        }
    }

    private static final class MemoryStore extends Store {
        record Row(String kind,String project,String parent,Map<String,Object> value){}
        final Map<String,Row> rows=new LinkedHashMap<>();
        MemoryStore(){super(null,new ObjectMapper());}
        @Override public void save(String kind,String id,String project,String parent,Object value){rows.put(id,new Row(kind,project,parent,map(encode(value))));}
        @Override public Map<String,Object> getMap(String kind,String id){Row row=rows.get(id);if(row==null||!kind.equals(row.kind))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return map(encode(row.value));}
        @Override public List<Map<String,Object>> maps(String kind,String project,String parent){return rows.values().stream().filter(r->kind.equals(r.kind)&&(project==null||project.equals(r.project))&&(parent==null||parent.equals(r.parent))).map(r->map(encode(r.value))).toList();}
    }
}
