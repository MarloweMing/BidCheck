package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real OCR structural shapes, synthetic business values; no guessed values or cross-page headers. */
class TableHeaderRecoveryTest {
    private static String row(String...values){return "<tr>"+Arrays.stream(values).map(v->"<td>"+v+"</td>").reduce("",String::concat)+"</tr>";}
    private static Page page(int number,String text){return new Page(number,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document doc(Page...pages){return new Document("doc","project","BID","synthetic.pdf",1,"sha","",List.of(pages),List.of());}
    private static TableRelationIndex index(String text){return TableRelationIndex.build(doc(page(1,text)));}
    private static void exactSources(String text,TableRelationIndex index){
        for(var table:index.tables())for(var r:table.rows())for(var a:r.associations()){
            assertEquals(a.rawValue(),text.substring(a.source().start(),a.source().end()));
            assertEquals(a.headerPath().size(),a.headerSources().size());
            for(int i=0;i<a.headerSources().size();i++){var s=a.headerSources().get(i);assertEquals(a.headerPath().get(i),text.substring(s.start(),s.end()).strip());}
        }
    }

    @Test void procurementVocabularyRecoversDataAndSeparatelyExcludesTheRealHeader(){
        String text="<table>"+row("序号","商品","物料编码","计量单位","采购数量","税率","净价","税额","含税价","净价小计","价税小计")
                +row("1","装修服务","MAT-1","元","100","9%","1","0.09","1.09","100","109")+"</table>";
        var index=index(text);assertEquals(1,index.rows().size());assertEquals("RESOLVED",index.rows().get(0).status());
        assertEquals(2L,index.summary().get("physicalRows"));assertEquals(1L,index.summary().get("headerRowsExcluded"));
        assertEquals(0L,index.summary().get("unresolvedRows"));exactSources(text,index);
    }

    @Test void wrappedHeaderJoinsOnlyAlignedExplicitFragmentsAndKeepsBothCitations(){
        String text="<table>"+row("在本项目","","《安全生产考")+row("中担任职务","姓名","核合格证书》(B/C证)")
                +row("项目经理","测试甲","见证书附件")+"</table>";
        var index=index(text);var role=index.rows().get(0).associations().get(0);
        assertEquals("RESOLVED",index.rows().get(0).status());assertEquals("在本项目中担任职务",role.label());
        assertEquals(List.of("在本项目","中担任职务"),role.headerPath());assertEquals(2,role.headerSources().size());
        assertEquals("EXPLICIT_FRAGMENTS_JOINED",role.asMap().get("headerComposition"));exactSources(text,index);
    }

    @Test void unknownFragmentsAreNeverRepairedByGuessingAHeader(){
        var index=index("<table>"+row("在本项目","未知")+row("未识别文字","姓名")+row("项目经理","测试甲")+"</table>");
        assertEquals(0,index.tables().get(0).headerRows());assertTrue(index.rows().stream().allMatch(r->r.status().equals("UNRESOLVED")));
    }

    @Test void parentGroupUsesIndependentlyExplicitLeafHeaders(){
        String text="<table><tr><td colspan='2'>智能化硬件设备</td><td rowspan='2'>统一折扣(cm)报价</td></tr>"
                +row("物料名称","不含税单价基价(元)")+row("测试设备","100","48%")+"</table>";
        var index=index(text);assertEquals(2,index.tables().get(0).headerRows());assertEquals("RESOLVED",index.rows().get(0).status());
        assertEquals(List.of("智能化硬件设备","不含税单价基价(元)"),index.rows().get(0).associations().get(1).headerPath());exactSources(text,index);
    }

    @Test void localHeaderSwitchDoesNotInterpretQuantityAsThePreviousPersonName(){
        String text="<table>"+row("在本项目中担任职务","姓名","证书编号","备注")+row("项目经理","测试甲","A-001","")
                +"<tr><td>在本项目担任职务</td><td colspan='2'>数量</td><td>说明</td></tr>"
                +"<tr><td>质量员</td><td colspan='2'>至少1人</td><td>须具备相应资格</td></tr></table>";
        var index=index(text);assertEquals(2,index.rows().size());assertEquals(2,index.tables().get(0).headerRows());
        var requirement=index.rows().get(1);assertEquals("RESOLVED",requirement.status());
        assertTrue(requirement.associations().stream().noneMatch(a->a.label().equals("姓名")));assertEquals("数量",requirement.associations().get(1).label());
        var p=page(1,text);var names=PersonCredentialFacts.analyzeForConsistency(doc(p),p).occurrences().stream().filter(o->o.field().equals("personName")).toList();
        assertEquals(1,names.size());assertEquals("测试甲",names.get(0).value());exactSources(text,index);
    }

    @Test void twoPhysicalValuesUnderOneLeafHeaderStayAmbiguous(){
        var index=index("<table><tr><td>序号</td><td colspan='2'>验收结论</td></tr>"
                +"<tr><td>1</td><td>施工单位</td><td>设计单位</td></tr></table>");
        assertEquals("UNRESOLVED",index.rows().get(0).status());assertEquals("DATA_CELLS_SPLIT_ONE_FIELD",index.rows().get(0).reviewReason());
        assertEquals(2,index.rows().get(0).associations().stream().filter(a->a.status().equals("AMBIGUOUS")).count());
    }

    @Test void explicitEngineeringPairsAndLocalGridKeepTheirOwnLabels(){
        String text="<table>"+row("工程名称","历史测试项目","建筑面积","100平方米")+row("施工单位","测试公司","完工日期","")
                +row("序号","项目","验收记录","验收结论")+row("1","质量控制资料","资料齐全","合格")+"</table>";
        var index=index(text);assertEquals(3,index.rows().size());assertTrue(index.rows().stream().allMatch(r->r.status().equals("RESOLVED")));
        assertEquals("工程名称",index.rows().get(0).associations().get(0).label());assertEquals("",index.rows().get(1).associations().get(1).value());
        assertEquals("验收结论",index.rows().get(2).associations().get(3).label());exactSources(text,index);
    }

    @Test void laterExplicitHeaderDoesNotRetroactivelyLabelEarlierInvoiceParties(){
        String text="<table>"+row("购买方信息","名称:测试公司","销售方信息")+row("序号","金额(元)","税额(元)")+row("1","100","9")+"</table>";
        var index=index(text);assertEquals("UNRESOLVED",index.rows().get(0).status());assertEquals("RESOLVED",index.rows().get(1).status());
        assertTrue(index.rows().get(0).associations().stream().allMatch(a->a.headerSources().isEmpty()));exactSources(text,index);
    }

    @Test void headerlessNumberedContinuationNeverBorrowsAnAdjacentEquipmentHeader(){
        Page first=page(10,"<table>"+row("序号","设备名称","数量","型号")+row("1","测试设备","2","MODEL-1")+"</table>");
        Page next=page(11,"<table>"+row("2","其他设备","3","MODEL-2")+"</table>");var index=TableRelationIndex.build(doc(first,next));
        assertEquals("RESOLVED",index.tables().get(0).rows().get(0).status());assertEquals("UNRESOLVED",index.tables().get(1).rows().get(0).status());
        assertTrue(index.continuations().isEmpty());
    }

    @Test void rowSummaryReconcilesHeadersLayoutAndUnresolvedWithoutCallingThemErrors(){
        var index=index("<table>"+row("序号","设备名称","数量","型号")+row("1","测试设备","1","X")+"</table>"
                +"<table>"+row("证书延期")+row("有效期延至____年____月____日")+"</table>");
        var summary=index.summary();assertEquals(4L,summary.get("physicalRows"));assertEquals(1L,summary.get("headerRowsExcluded"));
        assertEquals(0L,summary.get("layoutRowsExcluded"));assertEquals(3,summary.get("rows"));assertEquals(2L,summary.get("unresolvedRows"));
        assertEquals(Map.of("SINGLE_COLUMN_LAYOUT",2L),summary.get("unresolvedRowsByReason"));
    }
}
