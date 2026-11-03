package cn.bidcheck.consistency;

import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonCredentialTableRelationTest {
    private static Page page(int page,String text){return new Page(page,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document document(Page...pages){return new Document("doc","project","BID","test.pdf",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf());}
    private static final String HEADER="<tr><th rowspan='2'>姓名</th><th colspan='2'>证件信息</th></tr><tr><th>证书名称</th><th>证书编号</th></tr>";
    private static String table(String rows){return "<table>"+HEADER+rows+"</table>";}

    @Test void newConsistencyEntryResolvesMergedCellsWhileOcrPreparationEntryStaysUnchanged(){
        Page page=page(10,table("<tr><td rowspan='2'>张三</td><td>一级建造师资格证书</td><td>CERT001</td></tr><tr><td>安全考核合格证书</td><td>CERT002</td></tr>"));Document document=document(page);
        cn.bidcheck.document.PersonCredentialFacts.Result legacy=PersonCredentialFacts.analyze(document,page);assertTrue(legacy.occurrences().isEmpty());assertTrue(legacy.issues().stream().anyMatch(i->"PERSON_TABLE_SPAN_UNSUPPORTED".equals(i.get("code"))));
        cn.bidcheck.document.PersonCredentialFacts.Result enhanced=PersonCredentialFacts.analyzeForConsistency(document,page);assertEquals(6,enhanced.occurrences().size());
        java.util.List<cn.bidcheck.document.PersonCredentialFacts.Occurrence> numbers=cn.bidcheck.compat.Java8Api.toList(enhanced.occurrences().stream().filter(o->o.field().equals("certificateNumber")));assertEquals(2,numbers.size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("张三", "张三"),cn.bidcheck.compat.Java8Api.toList(numbers.stream().map(PersonCredentialFacts.Occurrence::personName)));
        assertNotEquals(numbers.get(0).recordKey(),numbers.get(1).recordKey());
        for(cn.bidcheck.document.PersonCredentialFacts.Occurrence occurrence:enhanced.occurrences()){assertTrue(page.text().contains(occurrence.evidence().quote()));assertTrue(occurrence.evidence().quote().contains(occurrence.rawValue()));}
        assertEquals("person-credential-source-2-layout-text",PersonCredentialFacts.VERSION);
    }

    @Test void consistencyReportPublishesExactCellAndHeaderSourcesForInheritedNames(){
        Page page=page(10,table("<tr><td rowspan='2'>张三</td><td>一级建造师资格证书</td><td>CERT001</td></tr><tr><td>安全考核合格证书</td><td>CERT002</td></tr>"));
        java.util.Map<java.lang.String, java.lang.Object> report=new PersonCredentialConsistency().scan(document(page));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> occurrences=maps(report.get("occurrences"));assertEquals(6,occurrences.size());
        for(java.util.Map<java.lang.String, java.lang.Object> occurrence:occurrences){Map<String,Object> association=map(occurrence.get("tableAssociation"));assertEquals("RESOLVED",association.get("status"));assertEquals(false,association.get("geometryAvailable"));
            Map<String,Object> source=map(association.get("source"));assertEquals(occurrence.get("rawValue"),page.text().substring(((Number)source.get("start")).intValue(),((Number)source.get("end")).intValue()));assertFalse(maps(association.get("headerSources")).isEmpty());}
        assertEquals(1,occurrences.stream().map(o->map(o.get("tableAssociation"))).filter(a->Boolean.TRUE.equals(a.get("inherited"))).count());
    }

    @Test void blankNameOnAnotherPageNeverInheritsThePreviousPersonsIdentity(){
        Page first=page(10,"人员表\n"+table("<tr><td>张三</td><td>一级建造师资格证书</td><td>CERT001</td></tr>"));
        Page second=page(11,"人员表（续）\n"+table("<tr><td></td><td>一级建造师资格证书</td><td>CERT002</td></tr>"));Document document=document(first,second);
        cn.bidcheck.document.PersonCredentialFacts.Result enhanced=PersonCredentialFacts.analyzeForConsistency(document,second);assertTrue(enhanced.occurrences().stream().allMatch(o->o.personName().isEmpty()));
        assertTrue(enhanced.issues().stream().anyMatch(i->"PERSON_NAME_MISSING".equals(i.get("code"))));
    }

    @Test void ambiguousSpansAndDuplicateColumnsNeverMakePersonRecords(){
        for(String text:cn.bidcheck.compat.Java8Api.listOf(table("<tr><td>张三</td><td colspan='2'>资格证书 CERT001</td></tr>"), "<table><tr><th>姓名</th><th>证书编号</th><th>证书编号</th></tr><tr><td>张三</td><td>A001</td><td>A002</td></tr></table>")){
            Page page=page(10,text);cn.bidcheck.document.PersonCredentialFacts.Result parsed=PersonCredentialFacts.analyzeForConsistency(document(page),page);assertTrue(parsed.occurrences().isEmpty());assertFalse(parsed.issues().isEmpty());
        }
    }

    @Test void enhancedPathStillSupportsPlainCredentialAndExplicitKeyValueTables(){
        for(String text:cn.bidcheck.compat.Java8Api.listOf("居民身份证\n姓名：张三\n身份证号码：110101199001010011", "<table><tr><td>姓名</td><td>张三</td></tr><tr><td>身份证号</td><td>110101199001010011</td></tr></table>")){
            Page page=page(10,text);cn.bidcheck.document.PersonCredentialFacts.Result parsed=PersonCredentialFacts.analyzeForConsistency(document(page),page);assertEquals(2,parsed.occurrences().size());assertEquals("张三",parsed.occurrences().get(1).personName());
        }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}
