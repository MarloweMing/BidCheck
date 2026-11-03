package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyTableRelationsTest {
    private Document doc(String text){return new Document("bid","project","BID","test.pdf",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> report,String key){return (List<Map<String,Object>>)report.get(key);}
    @Test void htmlBidTableUsesExactCellAndHeaderSourceAndFindsSameLotAmounts(){
        Document d=doc("第一章 本次投标函\n<table><tr><th>标段</th><th>投标总价（万元）</th></tr><tr><td>1</td><td>100</td></tr><tr><td>1</td><td>110</td></tr></table>");
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(d,cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> group=rows(report,"groups").stream().filter(g->"totalPrice".equals(g.get("field"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("CONFLICT",group.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("1000000", "1100000"),group.get("values"));
        for(Map<String,Object> fact:rows(report,"occurrences"))if("totalPrice".equals(fact.get("field"))){
            Evidence e=(Evidence)fact.get("evidence");assertTrue(d.pages().get(0).text().startsWith(e.quote(),((Number)fact.get("sourceOffset")).intValue()));
            assertTrue(fact.get("tableAssociation") instanceof Map<?,?>);assertTrue(((List<?>)fact.get("contextEvidences")).stream().map(Evidence.class::cast).anyMatch(ref->ref.quote().contains("投标总价（万元）")));
        }
    }
    @Test void htmlArithmeticUsesOnePhysicalRowAndNeverBorrowsAnotherItem(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次投标报价表\n<table><tr><th>品目</th><th>数量（台）</th><th>单价（元）</th><th>行金额（元）</th></tr><tr><td>服务器A</td><td>3</td><td>100</td><td>250</td></tr><tr><td>服务器B</td><td>2</td><td>100</td><td>200</td></tr></table>"),cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> groups=cn.bidcheck.compat.Java8Api.toList(rows(report,"groups").stream().filter(g->"ARITHMETIC".equals(g.get("relationType"))));
        assertEquals(1,groups.size());assertEquals(3,((List<?>)groups.get(0).get("occurrenceIds")).size());
        assertTrue(((List<?>)groups.get(0).get("evidences")).stream().map(Evidence.class::cast).noneMatch(e->e.quote().contains("服务器B")));
    }
    @Test void rowspanObjectAssociationKeepsPhysicalOriginAndCreatesDistinctRowFacts(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次投标技术方案\n<table><tr><th>品目</th><th>产品型号</th></tr><tr><td rowspan=\"2\">服务器A</td><td>X100</td></tr><tr><td>X200</td></tr></table>"),cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> facts=cn.bidcheck.compat.Java8Api.toList(rows(report,"occurrences").stream().filter(f->"productModel".equals(f.get("field"))));
        assertEquals(2,facts.size());assertEquals(2,facts.stream().map(f->f.get("rowId")).distinct().count());
        assertTrue(rows(report,"groups").stream().anyMatch(g->"productModel".equals(g.get("field"))&&"CONFLICT".equals(g.get("status"))));
    }
    @Test void malformedOrAmbiguousTableIsVisibleAndNeverCreatesConflictsFromUnboundCells(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次投标函\n<table><tr><th>投标总价</th><th>项目编号</th></tr><tr><td colspan=\"2\">100万元</td></tr></table>"),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"groups").stream().noneMatch(g->"CONFLICT".equals(g.get("status"))));
        Map<?,?> coverage=(Map<?,?>)report.get("coverage"),relations=(Map<?,?>)coverage.get("tableRelations");
        assertEquals(1L,((Number)relations.get("unresolvedRows")).longValue());assertEquals(true,relations.get("requiresReview"));
    }
    @Test void rawTextInsideHtmlCellDoesNotEscapeTableBindingOrChangeDocumentContext(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("第一章 本次投标函\n<table><tr><th>备注</th></tr><tr><td>\n历史业绩\n投标人名称：历史公司\n</td></tr></table>\n投标人名称：当前公司"),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"occurrences").stream().noneMatch(f->"历史公司".equals(f.get("rawValue"))));
        assertEquals("CURRENT_PROJECT",rows(report,"occurrences").stream().filter(f->"当前公司".equals(f.get("rawValue"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("scope"));
    }
    @Test void multiLevelTaxHeadersKeepInclusiveAndExclusivePricesSeparate(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次投标报价表\n<table><tr><th>含税（元）</th><th>不含税（元）</th></tr><tr><th>投标总价</th><th>投标总价</th></tr><tr><td>113</td><td>100</td></tr></table>"),cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> prices=cn.bidcheck.compat.Java8Api.toList(rows(report,"occurrences").stream().filter(f->"totalPrice".equals(f.get("field"))));
        assertEquals(2,prices.size());assertEquals(cn.bidcheck.compat.Java8Api.setOf("INCLUSIVE|TOTAL", "EXCLUSIVE|TOTAL"),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(prices.stream().map(p->p.get("basis").toString()))));
        assertTrue(rows(report,"groups").stream().noneMatch(g->"totalPrice".equals(g.get("field"))));
    }
}
