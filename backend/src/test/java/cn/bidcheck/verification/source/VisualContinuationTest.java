package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.ocr.OcrLayout;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualContinuationTest {
    static Page text(int number,String value){return new Page(number,value,"PAGE","PDF_TEXT","");}
    static Document document(List<Page> pages){return new Document("continuation","project","BID","synthetic.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",pages,cn.bidcheck.compat.Java8Api.listOf());}
    static Page table(int page,String title,String secondHeader){
        List<String> values=cn.bidcheck.compat.Java8Api.listOf("序号", secondHeader, Integer.toString(page), "合成记录"+page);
        List<OcrLayout.TextBox> blocks=new ArrayList<>();List<OcrLayout.Cell> cells=new ArrayList<>();List<String> order=new ArrayList<>();
        for(int i=0;i<4;i++){
            int row=i/2,column=i%2;String block="b"+i,cell="c"+i;double x=10+column*100,y=50+row*30;
            blocks.add(new OcrLayout.TextBox(block,"frame",values.get(i),new OcrLayout.Box(x+2,y+2,90,20),null));order.add(block);
            cells.add(new OcrLayout.Cell(cell,row,column,1,1,values.get(i),new OcrLayout.Box(x,y,100,30),cn.bidcheck.compat.Java8Api.listOf(block),row==0?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf("c"+column),row==0));
        }
        cn.bidcheck.ocr.OcrLayout.Frame frame=new OcrLayout.Frame("frame",400,300,cn.bidcheck.compat.Java8Api.repeat("a", 64),"PDF_RENDER",page,null,null,null);
        cn.bidcheck.ocr.OcrLayout layout=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,cn.bidcheck.compat.Java8Api.listOf(frame),blocks,cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.Table("table","frame",2,2,cells)),order,cn.bidcheck.compat.Java8Api.listOf());
        String content=title+"\n"+String.join("\n",values);layout.validated(content);
        return new Page(page,content,"PAGE","OCR","",layout);
    }
    @Test void namedContinuationTablesAllowDifferentRecordsAndKeepThreePageOverlap(){
        List<Page> pages=new ArrayList<>();for(int p=1;p<=5;p++)pages.add(table(p,"拟投入车辆一览表"+(p==1?"":"（续表）"),"车牌号"));
        Document doc=document(pages);assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3, 4, 5)),VisualPageWindows.forPage(doc,3,cn.bidcheck.compat.Java8Api.mapOf()));
        assertTrue(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3)));assertTrue(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(3, 4, 5)));
        assertFalse(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3, 4)));
    }
    @Test void tableNameContinuationAndValidatedHeadersAreAllRequired(){
        Page first=table(1,"拟投入车辆一览表","车牌号");
        List<Page> unsafe=cn.bidcheck.compat.Java8Api.listOf(table(2,"拟投入车辆一览表","车牌号"), table(2,"拟投入人员一览表（续表）","车牌号"), table(2,"拟投入车辆一览表（续表）","人员姓名"), text(2,"拟投入车辆一览表（续表）\n序号\n车牌号"), table(2,"附件二：其他材料\n拟投入车辆一览表（续表）","车牌号"));
        for(Page second:unsafe){Document doc=document(cn.bidcheck.compat.Java8Api.listOf(first, second));assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1)),VisualPageWindows.forPage(doc,1,cn.bidcheck.compat.Java8Api.mapOf()),second.text());assertFalse(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2)));}
        Page valid=table(2,"拟投入车辆一览表（续表）","车牌号");Page forged=new Page(2,valid.text().replace("车牌号","人员姓名"),"PAGE","OCR","",valid.layout());
        assertFalse(VisualPageWindows.valid(document(cn.bidcheck.compat.Java8Api.listOf(first, forged)),cn.bidcheck.compat.Java8Api.listOf(1, 2)));
    }
    @Test void contractNumberOnFirstPageAnchorsRepeatedTitleContinuations(){
        Document doc=document(cn.bidcheck.compat.Java8Api.listOf(text(1,"设备采购合同\n合同编号：TEST-2026-001\n第一条 采购范围"), text(2,"设备采购合同\n前页条款剩余正文"), text(3,"设备采购合同（续）\n剩余履行要求")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3)),VisualPageWindows.forPage(doc,2,cn.bidcheck.compat.Java8Api.mapOf()));assertTrue(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3)));
    }
    @Test void contractNumberOnFirstPageAnchorsSequentialClausesAcrossLaterWindows(){
        Document doc=document(cn.bidcheck.compat.Java8Api.listOf(text(1,"设备采购合同\n合同编号：TEST-2026-001\n第一条 采购范围\n第二条 数量"), text(2,"第三条 价格\n第四条 交货"), text(3,"第五条 验收\n第六条 售后"), text(4,"第七条 争议解决")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3, 4)),VisualPageWindows.forPage(doc,3,cn.bidcheck.compat.Java8Api.mapOf()));assertTrue(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(3, 4)));
    }
    @Test void titleOrClauseSimilarityWithoutContractNumberNeverStartsContext(){
        Document doc=document(cn.bidcheck.compat.Java8Api.listOf(text(1,"设备采购合同\n第一条 采购范围"), text(2,"设备采购合同\n第二条 付款")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1)),VisualPageWindows.forPage(doc,1,cn.bidcheck.compat.Java8Api.mapOf()));assertFalse(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2)));
    }
    @Test void newMaterialConflictingNumberPartiesTitleOrBrokenClauseStopsContractChain(){
        Page first=text(1,"设备采购合同\n合同编号：TEST-2026-001\n第一条 采购范围");
        for(String value:cn.bidcheck.compat.Java8Api.listOf("附件二：其他材料\n第二条 付款", "张三身份证（正面）\n第二条 付款", "设备采购合同\n合同编号：OTHER-2026-002\n第二条 付款", "设备采购合同\n甲方：新主体\n第二条 付款", "人员服务合同\n第二条 付款", "第三条 验收", "相同主体有限公司\n第二条 付款", "同名主体的另一份材料")){
            Document doc=document(cn.bidcheck.compat.Java8Api.listOf(first, text(2,value)));assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1)),VisualPageWindows.forPage(doc,1,cn.bidcheck.compat.Java8Api.mapOf()),value);assertFalse(VisualPageWindows.valid(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2)),value);
        }
    }
    @Test void pdfOcrMarkdownTablesNeedNoFabricatedLayoutAndKeepOverlappingSourceRelations(){
        List<Page> pages=new ArrayList<>();for(int page=1;page<=5;page++)pages.add(markdown(page,page==1?"拟投入车辆一览表":"续表","车牌号","渝A1234"+page));
        Document doc=document(pages);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3, 4, 5)),VisualPageWindows.plan(doc));
        List<Map<String,Object>> relations=VisualPageWindows.relations(doc,cn.bidcheck.compat.Java8Api.listOf(3, 4, 5));assertEquals(2,relations.size());
        assertEquals("TABLE_CONTINUATION",relations.get(0).get("type"));assertEquals(3,relations.get(0).get("fromPage"));assertEquals(4,relations.get(0).get("toPage"));
        assertEquals(false,relations.get(0).get("identityEstablished"));assertEquals(doc.sha256(),relations.get(0).get("documentHash"));assertNull(pages.get(0).layout());
    }
    @Test void conflictingHeaderNewMaterialAndBareSimilarityDoNotJoinPdfOcrTables(){
        Page first=markdown(1,"拟投入车辆一览表","车牌号","渝A12345");
        for(Page other:cn.bidcheck.compat.Java8Api.listOf(markdown(2,"拟投入车辆一览表","车牌号","渝B12345"), markdown(2,"续表","车辆类型","货车"), markdown(2,"附件二：其他材料\n续表","车牌号","渝B12345"))){
            Document doc=document(cn.bidcheck.compat.Java8Api.listOf(first, other));assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1), cn.bidcheck.compat.Java8Api.listOf(2)),VisualPageWindows.plan(doc));assertTrue(VisualPageWindows.relations(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2)).isEmpty());
        }
    }
    @Test void frontBackAndContractLinksExposeOnlyReadingContextAndVersionedIdentity(){
        Document doc=document(cn.bidcheck.compat.Java8Api.listOf(text(1,"张三身份证（正面）"), text(2,"张三身份证（反面）"), text(3,"李四身份证（正面）")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2), cn.bidcheck.compat.Java8Api.listOf(3)),VisualPageWindows.plan(doc));Map<String,Object> relation=VisualPageWindows.relations(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2)).get(0);
        assertEquals("CARD_FACES",relation.get("type"));assertEquals(false,relation.get("identityEstablished"));
        Document changed=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),2,doc.sha256(),"",doc.pages(),cn.bidcheck.compat.Java8Api.listOf());
        assertNotEquals(relation.get("relationId"),VisualPageWindows.relations(changed,cn.bidcheck.compat.Java8Api.listOf(1, 2)).get(0).get("relationId"));
    }
    @Test void explicitHtmlAndMarkdownHeadingsUseSourceHeadersWithoutInventingTableGeometry(){
        String table="<table><tr><th>序号</th><th>车牌号</th><th>车架号</th></tr><tr><td>1</td><td>渝A12345</td><td>LSVAB123456789011</td></tr></table>";
        Document doc=document(cn.bidcheck.compat.Java8Api.listOf(text(1,"## 拟投入车辆一览表\n"+table), text(2,"<h2>续表</h2>\n"+table)));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2)),VisualPageWindows.plan(doc));assertEquals("TABLE_CONTINUATION",VisualPageWindows.relations(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2)).get(0).get("type"));
        assertNull(doc.pages().get(0).layout());
    }
    @Test void explicitContinuationWithoutRepeatedHeaderInheritsSchemaThroughOverlappingWindows(){
        Document doc=document(cn.bidcheck.compat.Java8Api.listOf(markdown(1,"拟投入车辆一览表","车牌号","渝A12341"), text(2,"续表\n| 2 | 渝A12342 | LSVAB123456789012 |"), text(3,"续表\n| 3 | 渝A12343 | LSVAB123456789013 |"), text(4,"续表\n| 4 | 渝A12344 | LSVAB123456789014 |")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3, 4)),VisualPageWindows.plan(doc));
        Map<String,Object> relation=VisualPageWindows.relations(doc,cn.bidcheck.compat.Java8Api.listOf(3, 4)).get(0);
        assertEquals("EXPLICIT_CONTINUATION_INHERITED_HEADERS",relation.get("basis"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("序号|车牌号|车架号"),relation.get("tableHeaders"));assertEquals(false,relation.get("identityEstablished"));
        Document broken=document(cn.bidcheck.compat.Java8Api.listOf(doc.pages().get(0), text(2,"续表\n| 2 | 其他表格 |")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1), cn.bidcheck.compat.Java8Api.listOf(2)),VisualPageWindows.plan(broken));
    }
    static Page markdown(int page,String title,String header,String value){return text(page,title+"\n| 序号 | "+header+" | 车架号 |\n| --- | --- | --- |\n| "+page+" | "+value+" | LSVAB12345678901"+page+" |\n");}
}
