package cn.bidcheck.consistency;

import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonCredentialTableRelationTest {
    private static Page page(int page,String text){return new Page(page,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document document(Page...pages){return new Document("doc","project","BID","test.pdf",1,"sha","",List.of(pages),List.of());}
    private static final String HEADER="<tr><th rowspan='2'>姓名</th><th colspan='2'>证件信息</th></tr><tr><th>证书名称</th><th>证书编号</th></tr>";
    private static String table(String rows){return "<table>"+HEADER+rows+"</table>";}

    @Test void newConsistencyEntryResolvesMergedCellsWhileOcrPreparationEntryStaysUnchanged(){
        Page page=page(10,table("<tr><td rowspan='2'>张三</td><td>一级建造师资格证书</td><td>CERT001</td></tr><tr><td>安全考核合格证书</td><td>CERT002</td></tr>"));Document document=document(page);
        var legacy=PersonCredentialFacts.analyze(document,page);assertTrue(legacy.occurrences().isEmpty());assertTrue(legacy.issues().stream().anyMatch(i->"PERSON_TABLE_SPAN_UNSUPPORTED".equals(i.get("code"))));
        var enhanced=PersonCredentialFacts.analyzeForConsistency(document,page);assertEquals(6,enhanced.occurrences().size());
        var numbers=enhanced.occurrences().stream().filter(o->o.field().equals("certificateNumber")).toList();assertEquals(2,numbers.size());
        assertEquals(List.of("张三","张三"),numbers.stream().map(PersonCredentialFacts.Occurrence::personName).toList());
        assertNotEquals(numbers.get(0).recordKey(),numbers.get(1).recordKey());
        for(var occurrence:enhanced.occurrences()){assertTrue(page.text().contains(occurrence.evidence().quote()));assertTrue(occurrence.evidence().quote().contains(occurrence.rawValue()));}
        assertEquals("person-credential-source-2-layout-text",PersonCredentialFacts.VERSION);
    }

    @Test void consistencyReportPublishesExactCellAndHeaderSourcesForInheritedNames(){
        Page page=page(10,table("<tr><td rowspan='2'>张三</td><td>一级建造师资格证书</td><td>CERT001</td></tr><tr><td>安全考核合格证书</td><td>CERT002</td></tr>"));
        var report=new PersonCredentialConsistency().scan(document(page));
        var occurrences=maps(report.get("occurrences"));assertEquals(6,occurrences.size());
        for(var occurrence:occurrences){Map<String,Object> association=map(occurrence.get("tableAssociation"));assertEquals("RESOLVED",association.get("status"));assertEquals(false,association.get("geometryAvailable"));
            Map<String,Object> source=map(association.get("source"));assertEquals(occurrence.get("rawValue"),page.text().substring(((Number)source.get("start")).intValue(),((Number)source.get("end")).intValue()));assertFalse(maps(association.get("headerSources")).isEmpty());}
        assertEquals(1,occurrences.stream().map(o->map(o.get("tableAssociation"))).filter(a->Boolean.TRUE.equals(a.get("inherited"))).count());
    }

    @Test void blankNameOnAnotherPageNeverInheritsThePreviousPersonsIdentity(){
        Page first=page(10,"人员表\n"+table("<tr><td>张三</td><td>一级建造师资格证书</td><td>CERT001</td></tr>"));
        Page second=page(11,"人员表（续）\n"+table("<tr><td></td><td>一级建造师资格证书</td><td>CERT002</td></tr>"));Document document=document(first,second);
        var enhanced=PersonCredentialFacts.analyzeForConsistency(document,second);assertTrue(enhanced.occurrences().stream().allMatch(o->o.personName().isEmpty()));
        assertTrue(enhanced.issues().stream().anyMatch(i->"PERSON_NAME_MISSING".equals(i.get("code"))));
    }

    @Test void ambiguousSpansAndDuplicateColumnsNeverMakePersonRecords(){
        for(String text:List.of(table("<tr><td>张三</td><td colspan='2'>资格证书 CERT001</td></tr>"),
                "<table><tr><th>姓名</th><th>证书编号</th><th>证书编号</th></tr><tr><td>张三</td><td>A001</td><td>A002</td></tr></table>")){
            Page page=page(10,text);var parsed=PersonCredentialFacts.analyzeForConsistency(document(page),page);assertTrue(parsed.occurrences().isEmpty());assertFalse(parsed.issues().isEmpty());
        }
    }

    @Test void enhancedPathStillSupportsPlainCredentialAndExplicitKeyValueTables(){
        for(String text:List.of("居民身份证\n姓名：张三\n身份证号码：110101199001010011", "<table><tr><td>姓名</td><td>张三</td></tr><tr><td>身份证号</td><td>110101199001010011</td></tr></table>")){
            Page page=page(10,text);var parsed=PersonCredentialFacts.analyzeForConsistency(document(page),page);assertEquals(2,parsed.occurrences().size());assertEquals("张三",parsed.occurrences().get(1).personName());
        }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}
