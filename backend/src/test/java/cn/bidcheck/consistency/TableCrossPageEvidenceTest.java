package cn.bidcheck.consistency;

import cn.bidcheck.document.TableRelationIndex;
import cn.bidcheck.document.TableRelationIndex.*;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableCrossPageEvidenceTest {
    private static Page page(int number,String text){return new Page(number,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document doc(Page...pages){return new Document("doc","project","BID","test.pdf",3,"sha","",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf());}
    private static Source ref(int page,int start,int end){return new Source("doc",3,"sha",page,"PAGE",start,end);}
    private static String row(String...values){return "<tr>"+Arrays.stream(values).map(v->"<td>"+v+"</td>").reduce("",String::concat)+"</tr>";}
    private static String item(int n){return row(""+n,"设备"+n,""+(n+1),"MODEL-"+n);}
    private static Document continued(){return doc(page(264,"# 设备租赁合同\n"+cn.bidcheck.compat.Java8Api.repeat("前置合同正文。\n", 180)+"# 一、设备清单\n<table>"+row("序号","设备名称","数量","型号")+item(1)+item(2)+"</table>"),page(265,"265\n【PDF识别文字】\n<table>"+item(3)+item(4)+"</table>"));}

    @Test void headerOffsetsResolveAgainstTheActualEarlierPageEvenIfBeyondCurrentPageLength(){
        Document document=continued();Row row=TableRelationIndex.build(document).tables().get(1).rows().get(0);Page current=document.pages().get(1);
        assertTrue(row.associations().get(2).headerSources().get(0).start()>current.text().length());
        Evidence header=TableFactSupport.header(document,current,row);assertNotNull(header);assertEquals(264,header.page());assertTrue(header.quote().contains("数量"));
        Evidence value=TableFactSupport.evidence(document,current,row.associations().get(2).source());assertEquals(265,value.page());assertEquals("4",value.quote());
    }

    @Test void wholeConsistencyScanPublishesNewQuantitiesWithExactCurrentCellAndEarlierHeaderEvidence(){
        Document document=continued();Map<String,Object> report=new ConsistencyEngine().scan(document,cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> facts=(List<Map<String,Object>>)report.get("occurrences");
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> restored=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.get("tableAssociation") instanceof Map<?,?>&&((Map<?,?>) (f.get("tableAssociation"))).containsKey("headerContinuation")));
        assertFalse(restored.isEmpty());assertEquals(2,restored.stream().filter(f->"quantity".equals(f.get("field"))).count());
        for(java.util.Map<java.lang.String, java.lang.Object> fact:restored){
            Evidence evidence=(Evidence)fact.get("evidence");assertEquals(265,evidence.page());assertTrue(document.pages().get(1).text().contains(evidence.quote()));
            java.util.Map<java.lang.String, java.lang.Object> association=(Map<String,Object>)fact.get("tableAssociation");java.util.Map<java.lang.String, java.lang.Object> source=(Map<String,Object>)association.get("source");
            assertEquals(evidence.quote(),document.pages().get(1).text().substring((Integer)source.get("start"),(Integer)source.get("end")));
            List<Evidence> references=(List<Evidence>)fact.get("contextEvidences");assertTrue(references.stream().anyMatch(e->e.page()==264));
            for(Evidence reference:references){Page p=document.pages().stream().filter(x->x.number()==reference.page()&&x.locationType().equals(reference.locationType())).findFirst().orElseThrow(java.util.NoSuchElementException::new);assertTrue(p.text().contains(reference.quote()));}
            for(java.util.Map<java.lang.String, java.lang.Object> header:(List<Map<String,Object>>)association.get("headerSources"))assertEquals(264,header.get("page"));
        }
    }

    @Test void sourceIdentityVersionHashMissingPageAmbiguityAndOffsetsFailClosed(){
        Page current=page(2,"数量");Document document=doc(page(1,"数量"),current);
        for(Source bad:cn.bidcheck.compat.Java8Api.listOf(new Source("other",3,"sha",1,"PAGE",0,2), new Source("doc",2,"sha",1,"PAGE",0,2), new Source("doc",3,"other",1,"PAGE",0,2), ref(9,0,2), ref(1,-1,2), ref(1,0,20), new Source("doc",3,"sha",1,"OTHER",0,2)))
            assertTrue(assertThrows(IllegalArgumentException.class,()->TableFactSupport.evidence(document,current,bad)).getMessage().startsWith("TABLE_SOURCE_NOT_BOUND"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->TableFactSupport.evidence(doc(page(1,"数量"),page(1,"数量")),current,ref(1,0,2))).getMessage().startsWith("TABLE_SOURCE_NOT_BOUND"));
    }

    @Test void multiPageHeaderPathsCannotBeAggregatedByMixingOffsets(){
        Document document=doc(page(1,"数量"),page(2,"前文前文型号"),page(3,"2 X"));
        Association quantity=new Association("t",1,1,"c1","数量",cn.bidcheck.compat.Java8Api.listOf("数量"),"2","2",ref(3,0,1),cn.bidcheck.compat.Java8Api.listOf(ref(1,0,2)),false,"RESOLVED");
        Association model=new Association("t",1,2,"c2","型号",cn.bidcheck.compat.Java8Api.listOf("型号"),"X","X",ref(3,2,3),cn.bidcheck.compat.Java8Api.listOf(ref(2,4,6)),false,"RESOLVED");
        Row row=new Row("t",1,3,"PAGE",0,3,1,"RESOLVED",cn.bidcheck.compat.Java8Api.listOf(quantity, model),"");
        Evidence aggregate=TableFactSupport.header(document,document.pages().get(2),row);assertEquals(1,aggregate.page());assertEquals("数量",aggregate.quote());
        List<Map<String,Object>> facts=new ArrayList<>();facts.add(new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "fact", "field", "productModel", "fieldLabel", "型号", "rawValue", "X", "sourceColumn", 2)));
        TableFactSupport.bind(document,document.pages().get(2),row,facts,0);
        assertEquals(3,((Evidence)facts.get(0).get("evidence")).page());
        assertTrue(((List<Evidence>)facts.get(0).get("contextEvidences")).stream().anyMatch(e->e.page()==2&&e.quote().equals("型号")));
    }

    @Test void invalidHeaderFailsVisiblyWithoutPublishingOrSilentlyDiscardingFacts(){
        Document document=doc(page(1,"数量"),page(2,"2"));
        Association association=new Association("t",1,1,"c","数量",cn.bidcheck.compat.Java8Api.listOf("数量"),"2","2",ref(2,0,1),cn.bidcheck.compat.Java8Api.listOf(ref(9,0,2)),false,"RESOLVED");
        Row row=new Row("t",1,2,"PAGE",0,1,1,"RESOLVED",cn.bidcheck.compat.Java8Api.listOf(association),"");
        assertTrue(assertThrows(IllegalArgumentException.class,()->TableFactSupport.header(document,document.pages().get(1),row)).getMessage().startsWith("TABLE_SOURCE_NOT_BOUND"));
        Map<String,Object> existing=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "keep"));List<Map<String,Object>> facts=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(existing, new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "discard"))));
        assertTrue(assertThrows(IllegalArgumentException.class,()->TableFactSupport.bind(document,document.pages().get(1),row,facts,1)).getMessage().startsWith("TABLE_SOURCE_NOT_BOUND"));assertEquals(2,facts.size());assertEquals(existing,facts.get(0));
    }
}
