package cn.bidcheck.consistency;

import cn.bidcheck.document.TableRelationIndex;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableItemNameSemanticsTest {
    private static String row(String...values){return "<tr>"+Arrays.stream(values).map(v->"<td>"+v+"</td>").reduce("",String::concat)+"</tr>";}
    private static Document doc(String text){return new Document("doc","project","BID","test.pdf",1,"sha","",List.of(new Page(1,text,"PAGE","QWEN_OCR_TEXT","")),List.of());}
    private static void assertItem(Document doc,String value){
        var index=TableRelationIndex.build(doc);var r=index.rows().stream().filter(x->x.associations().stream().anyMatch(a->a.value().equals(value))).findFirst().orElseThrow();
        var a=r.associations().stream().filter(x->x.value().equals(value)).findFirst().orElseThrow();
        assertEquals("项目名称",a.label());assertEquals("品目名称",TableFactSupport.semanticHeader(r,a));
        assertEquals("项目名称",doc.pages().get(0).text().substring(a.headerSources().get(0).start(),a.headerSources().get(0).end()));
        assertEquals(value,a.rawValue());
        var facts=(List<Map<String,Object>>)new ConsistencyEngine().scan(doc,List.of()).get("occurrences");
        assertFalse(facts.stream().anyMatch(f->"projectName".equals(f.get("field"))&&value.equals(f.get("rawValue"))));
    }
    @Test void engineeringLineItemsDoNotReplaceAnExplicitProjectIdentity(){
        Document doc=doc("历史项目\n<table><tr><td colspan='4'>项目名称:历史装修工程</td></tr>"+row("项目名称","项目描述","数量","单位")+row("旧设备拆除","成品保护性拆除","1","项")+"</table>");
        assertItem(doc,"旧设备拆除");
        var facts=(List<Map<String,Object>>)new ConsistencyEngine().scan(doc,List.of()).get("occurrences");
        assertTrue(facts.stream().anyMatch(f->"projectName".equals(f.get("field"))&&"历史装修工程".equals(f.get("rawValue"))));
    }
    @Test void invoiceGoodsAndConstructionServicesStayItemsButUnprovenGenericNamesStayLiteral(){
        assertItem(doc("<table>"+row("项目名称","规格型号","数量","税率/征收率")+row("网络交换机","MODEL-1","1","13%")+"</table>"),"网络交换机");
        assertItem(doc("<table>"+row("项目名称","建筑服务发生地","建筑项目名称","税率/征收率")+row("建筑装修服务","测试地址","历史营业厅项目","9%")+"</table>"),"建筑装修服务");
        var index=TableRelationIndex.build(doc("<table>"+row("项目名称","金额(元)")+row("项目甲","100")+"</table>"));
        var r=index.rows().get(0);assertEquals("项目名称",TableFactSupport.semanticHeader(r,r.associations().get(0)));
    }
    @Test void explicitMaterialAndUnitPriceIdentifyEngineeringItemsWithoutAQuantityColumn(){
        // Pages137/194: the local header has a unit price but deliberately no quantity column.
        for(String price:List.of("不含税单价(元)","不含增值税单价（元）","最高不含税单价限价（元）")){
            Document doc=doc("历史项目\n<table>"+row("序号","物料名称","项目名称","项目描述","单位",price)
                    +row("1","拆除","整体拆除部分","原顶面、地面及墙面整体拆除","项","40")
                    +row("2","拆除","垃圾外运","垃圾清运至指定地点","车","690")+"</table>");
            assertItem(doc,"整体拆除部分");assertItem(doc,"垃圾外运");
        }
    }
    @Test void incompleteMaterialUnitPriceSchemaCannotReclassifyAnOrdinaryProjectName(){
        for(String text:List.of(
                "<table>"+row("项目名称","项目描述","单位","物料名称")+row("项目甲","整体说明","项","材料甲")+"</table>",
                "<table>"+row("项目名称","项目描述","单位","金额(元)")+row("项目甲","整体说明","项","100")+"</table>",
                "<table>"+row("项目名称","物料名称","单位","不含税单价(元)")+row("项目甲","材料甲","项","100")+"</table>")){
            var index=TableRelationIndex.build(doc(text));var r=index.rows().get(0);
            assertEquals("RESOLVED",r.status());assertEquals("项目名称",TableFactSupport.semanticHeader(r,r.associations().get(0)));
        }
    }
}
