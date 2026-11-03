package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source shapes from p264–268; synthetic values, no original business identities. */
class TableVerifiedContinuationTest {
    private static String row(String...values){return "<tr>"+Arrays.stream(values).map(v->"<td>"+v+"</td>").reduce("",String::concat)+"</tr>";}
    private static String table(String rows){return "<table>"+rows+"</table>";}
    private static String data(int ordinal){return row(""+ordinal,"设备"+ordinal,"2","X-"+ordinal);}
    private static Page page(int number,String text){return new Page(number,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document doc(Page...pages){return new Document("d","p","BID","synthetic.pdf",2,"source-sha","",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf());}
    private static Page anchor(){return page(264,"租赁合同\n264\n【PDF识别文字】\n# 设备租赁合同\n# 一、设备清单\n"+table(row("序号","设备名称","数量","型号")+data(1)+data(2)));}
    private static Page next(String prefix,String rows){return page(265,prefix+table(rows));}
    private static void unresolved(Page following){cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(doc(anchor(),following));assertTrue(index.tables().get(1).rows().stream().allMatch(r->r.status().equals("UNRESOLVED")));assertEquals(0L,index.summary().get("restoredHeaderRows"));}

    @Test void strictNumberedListRestoresHeadersAndKeepsCurrentPageValuesAndExactCrossPageCitations(){
        Document document=doc(anchor(),next("265\n【PDF识别文字】\n",data(3)+row("4","设备🙂四","3","")));
        cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(document);cn.bidcheck.document.TableRelationIndex.Table restored=index.tables().get(1);
        assertEquals(2L,index.summary().get("restoredHeaderRows"));assertEquals(8L,index.summary().get("restoredHeaderAssociations"));
        assertEquals(1L,index.summary().get("linkedContinuations"));assertEquals(0,restored.headerRows());assertEquals("",restored.headerSignature());
        for(cn.bidcheck.document.TableRelationIndex.Row r:restored.rows())for(cn.bidcheck.document.TableRelationIndex.Association a:r.associations()){
            assertEquals("RESOLVED",r.status());assertEquals(265,a.source().page());assertFalse(a.inherited());
            assertEquals(a.rawValue(),document.pages().get(1).text().substring(a.source().start(),a.source().end()));
            assertEquals(264,a.headerSources().get(0).page());cn.bidcheck.document.TableRelationIndex.Source h=a.headerSources().get(0);
            assertEquals(a.label(),document.pages().get(0).text().substring(h.start(),h.end()));
            assertEquals("VERIFIED_NUMBERED_ITEM_LIST",a.headerContinuation().method());
            assertTrue(a.asMap().containsKey("headerContinuation"));
        }
        assertEquals("",restored.rows().get(1).associations().get(3).value());
        assertTrue(index.issues().stream().noneMatch(i->i.code().equals("TABLE_HEADER_UNRESOLVED")));
    }

    @Test void uninterruptedChainRetainsOriginalAnchorAndLeavesFinalTotalUnresolved(){
        cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(doc(anchor(),next("265\n【PDF识别文字】\n",data(3)+data(4)),
                page(266,"266\n【PDF识别文字】\n"+table(data(5)+data(6)+row("","合计:","12",""))+"\n# 二、租用期限\n2024年起")));
        assertEquals(4L,index.summary().get("restoredHeaderRows"));assertEquals(2L,index.summary().get("linkedContinuations"));
        cn.bidcheck.document.TableRelationIndex.Table tail=index.tables().get(2);assertEquals("UNRESOLVED",tail.rows().get(2).status());
        assertEquals("",tail.rows().get(2).associations().get(2).label());assertNull(tail.rows().get(2).associations().get(2).headerContinuation());
        cn.bidcheck.document.TableRelationIndex.HeaderContinuation proof=tail.rows().get(0).associations().get(0).headerContinuation();
        assertEquals(index.tables().get(0).id(),proof.anchorTableId());assertEquals(index.tables().get(1).id(),proof.previousTableId());
        assertEquals(264,proof.anchorTitleSource().page());assertEquals(265,proof.sequenceSources().get(0).page());
        assertEquals(266,proof.sequenceSources().get(1).page());assertEquals(5,proof.firstSequence());assertEquals(6,proof.lastSequence());
        assertEquals(8L,index.summary().get("physicalRows"));assertEquals(1L,index.summary().get("headerRowsExcluded"));
    }

    @Test void newContractOrChapterOrArbitraryTextBetweenPagesPreventsRestoration(){
        for(String prefix:cn.bidcheck.compat.Java8Api.listOf("# 二、其他合同\n", "合同编号：OTHER-2\n", "另一个项目\n", "# 设备清单\n"))unresolved(next(prefix,data(3)+data(4)));
        Page first=page(264,anchor().text()+"\n# 二、租用期限");cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(doc(first,next("265\n",data(3)+data(4))));
        assertEquals(0L,index.summary().get("restoredHeaderRows"));
    }

    @Test void sequenceGapsResetsDuplicatesAndMissingItemNamesPreventRestoration(){
        for(String rows:cn.bidcheck.compat.Java8Api.listOf(data(4)+data(5), data(1)+data(2), data(3)+data(3), data(3)+data(5), data(3)+row("4","","1","X")))unresolved(next("265\n",rows));
    }

    @Test void sameColumnCountAloneCannotOverrideDifferentQuantityType(){
        unresolved(next("265\n",row("3","设备三","MODEL-3","2")+row("4","设备四","MODEL-4","2")));
    }

    @Test void columnDriftOrMergedCellsPreventAnySchemaRepair(){
        unresolved(next("265\n",row("3","区域","设备三","2","X")+row("4","区域","设备四","2","Y")));
        unresolved(next("265\n","<tr><td>3</td><td colspan='2'>设备三</td><td>X</td></tr><tr><td>4</td><td colspan='2'>设备四</td><td>Y</td></tr>"));
    }

    @Test void distantRepeatedAttachmentOrDuplicatePageIdentityIsNotAContinuation(){
        cn.bidcheck.domain.Domain.Page distant=page(266,"266\n"+table(data(3)+data(4)));
        assertEquals(0L,TableRelationIndex.build(doc(anchor(),distant)).summary().get("restoredHeaderRows"));
        cn.bidcheck.document.TableRelationIndex duplicate=TableRelationIndex.build(doc(anchor(),anchor(),next("265\n",data(3)+data(4))));
        assertEquals(0L,duplicate.summary().get("restoredHeaderRows"));
    }

    @Test void noTitleSingleAnchorRowAndSensitiveSchemasStayConservative(){
        List<String> starts=cn.bidcheck.compat.Java8Api.listOf(table(row("序号","设备名称","数量","型号")+data(1)+data(2)), "# 设备清单\n"+table(row("序号","设备名称","数量","型号")+data(2)), "# 设备清单\n"+table(row("序号","姓名","数量","型号")+row("1","测试甲","2","A")+row("2","测试乙","2","B")), "# 设备清单\n"+table(row("序号","设备名称","金额","型号")+data(1)+data(2)));
        for(String start:starts)assertEquals(0L,TableRelationIndex.build(doc(page(264,start),next("265\n",data(3)+data(4)))).summary().get("restoredHeaderRows"));
    }

    @Test void explicitContinuationTitleUsesOneConsistentLinkAndStillDoesNotFillValues(){
        cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(doc(anchor(),next("# 一、设备清单（续）\n",data(3)+row("4","设备四","1",""))));
        assertEquals(1,index.continuations().size());assertEquals("LINKED",index.continuations().get(0).status());
        assertEquals(2L,index.summary().get("restoredHeaderRows"));assertEquals("",index.tables().get(1).rows().get(1).associations().get(3).value());
    }

    @Test void incompleteSourceOrOtherTableBetweenFragmentsStopsChain(){
        cn.bidcheck.domain.Domain.Page deferred=new Page(265,"265\n"+table(data(3)+data(4)),"PAGE","QWEN_OCR_TEXT","OCR_DEFERRED");
        assertEquals(0L,TableRelationIndex.build(doc(anchor(),deferred)).summary().get("restoredHeaderRows"));
        cn.bidcheck.domain.Domain.Page first=page(264,anchor().text()+"\n"+table(row("姓名","职务")+row("测试甲","经理")));
        assertEquals(0L,TableRelationIndex.build(doc(first,next("265\n",data(3)+data(4)))).summary().get("restoredHeaderRows"));
    }
}
