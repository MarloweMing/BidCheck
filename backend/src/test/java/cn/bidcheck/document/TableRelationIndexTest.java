package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableRelationIndexTest {
    private static Page page(int number,String text){return new Page(number,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document document(Page...pages){return new Document("doc","project","BID","test.pdf",3,"sha","",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf());}
    private static TableRelationIndex index(String text){return TableRelationIndex.build(document(page(1,text)));}
    private static String header(){return "<tr><th>姓名</th><th>职务</th><th>证书编号</th></tr>";}
    private static String table(String rows){return "<table>"+header()+rows+"</table>";}
    private static String row(String name,String role,String number){return "<tr><td>"+name+"</td><td>"+role+"</td><td>"+number+"</td></tr>";}

    @Test void everyCellAndHeaderPointsToItsExactUnmodifiedSource(){
        String text="前文\n"+table(row("张三","项目经理","CERT-01"));TableRelationIndex index=index(text);
        assertTrue(index.issues().isEmpty(),index.issues().toString());assertEquals(1,index.rows().size());
        for(cn.bidcheck.document.TableRelationIndex.Association association:index.rows().get(0).associations()){
            assertEquals(association.rawValue(),text.substring(association.source().start(),association.source().end()));
            assertEquals("sha",association.source().documentHash());assertEquals(3,association.source().documentVersion());
            assertEquals(association.headerPath(),cn.bidcheck.compat.Java8Api.toList(association.headerSources().stream().map(s->text.substring(s.start(),s.end()))));
        }
    }

    @Test void resolvesMultiLevelHeadersWithoutLosingParentNames(){
        cn.bidcheck.document.TableRelationIndex index=index("<table><tr><th rowspan='2'>姓名</th><th colspan='2'>证件信息</th></tr><tr><th>证书名称</th><th>证书编号</th></tr>"
                +row("张三","资格证书","A001")+"</table>");
        assertTrue(index.issues().isEmpty(),index.issues().toString());assertEquals(2,index.tables().get(0).headerRows());
        cn.bidcheck.document.TableRelationIndex.Association certificate=index.rows().get(0).associations().get(2);assertEquals(cn.bidcheck.compat.Java8Api.listOf("证件信息", "证书编号"),certificate.headerPath());
        assertEquals("证书编号",certificate.label());assertEquals(3,certificate.column());assertEquals(3,certificate.row());
    }

    @Test void rowspanRefersBackToTheOriginalCellAndDoesNotFabricateAnotherNameCell(){
        String source=table("<tr><td rowspan='2'>张三</td><td>项目经理</td><td>A001</td></tr><tr><td>技术负责人</td><td>A002</td></tr>");
        cn.bidcheck.document.TableRelationIndex index=index(source);assertTrue(index.issues().isEmpty(),index.issues().toString());assertEquals(2,index.rows().size());
        cn.bidcheck.document.TableRelationIndex.Association first=index.rows().get(0).associations().get(0);cn.bidcheck.document.TableRelationIndex.Association inherited=index.rows().get(1).associations().get(0);
        assertFalse(first.inherited());assertTrue(inherited.inherited());assertEquals(first.source(),inherited.source());assertEquals(first.cellId(),inherited.cellId());
        assertEquals(1,index.tables().get(0).cells().stream().filter(c->c.value().equals("张三")).count());
        assertTrue(source.substring(index.rows().get(1).start(),index.rows().get(1).end()).contains("张三"));
    }

    @Test void mergedDataAcrossDifferentFieldsIsAmbiguousAndNotDuplicatedAsTwoValues(){
        cn.bidcheck.document.TableRelationIndex index=index(table("<tr><td>张三</td><td colspan='2'>项目经理 A001</td></tr>"));
        assertEquals("UNRESOLVED",index.rows().get(0).status());assertEquals(2,index.rows().get(0).associations().size());
        assertEquals("AMBIGUOUS",index.rows().get(0).associations().get(1).status());assertTrue((Boolean)index.summary().get("requiresReview"));
    }

    @Test void badSpansGapsNestedAndUnclosedTablesRemainReviewable(){
        for(String source:cn.bidcheck.compat.Java8Api.listOf(table("<tr><td rowspan='2'>张三</td><td>经理</td><td>A001</td></tr>"), table("<tr><td>张三</td><td>A001</td></tr>"), table("<tr><td rowspan='0'>张三</td><td>经理</td><td>A001</td></tr>"), table("<tr><td rowspan='2' rowspan='1'>张三</td><td>经理</td><td>A001</td></tr>"), table("<tr><td><table><tr><td>张三</td></tr></table></td><td>经理</td><td>A001</td></tr>"), "<table>"+header()+row("张三","经理","A001"))){
            cn.bidcheck.document.TableRelationIndex index=index(source);assertTrue(index.rows().isEmpty(),source);assertFalse(index.issues().isEmpty(),source);
            assertTrue((Boolean)index.summary().get("requiresReview"));
        }
    }

    @Test void overlapCannotPushAnotherColumnSilentlyIntoAnExistingSpan(){
        cn.bidcheck.document.TableRelationIndex index=index("<table><tr><th>姓名</th><th>职务</th><th>证书编号</th></tr>"
                +"<tr><td>张三</td><td rowspan='2'>经理</td><td>A001</td></tr><tr><td colspan='2'>李四</td><td>A002</td></tr></table>");
        assertTrue(index.rows().isEmpty());assertEquals("TABLE_SPAN_COLLISION",index.issues().get(0).code());
    }

    @Test void tdHeaderLabelsCanResolveButArbitraryFirstDataRowIsNotAHeader(){
        cn.bidcheck.document.TableRelationIndex labels=index("<table><tr><td>姓名</td><td>职务</td></tr><tr><td>张三</td><td>经理</td></tr></table>");
        assertEquals("RESOLVED",labels.rows().get(0).status());assertEquals(1,labels.tables().get(0).headerRows());
        cn.bidcheck.document.TableRelationIndex unknown=index("<table><tr><td>张三</td><td>经理</td></tr><tr><td>李四</td><td>技术员</td></tr></table>");
        assertEquals(0,unknown.tables().get(0).headerRows());assertTrue(unknown.rows().stream().allMatch(r->r.status().equals("UNRESOLVED")));
    }

    @Test void separateTablesOnOnePageKeepIndependentSourceIdentities(){
        cn.bidcheck.document.TableRelationIndex index=index(table(row("张三","经理","A001"))+"\n另一表\n"+table(row("李四","经理","A002")));
        assertEquals(2,index.tables().size());assertNotEquals(index.rows().get(0).tableId(),index.rows().get(1).tableId());
    }

    @Test void adjacentPagesWithSameHeadersNeverAutomaticallyMerge(){
        cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(document(page(10,"人员表\n"+table(row("张三","经理","A001"))),page(11,"人员表\n"+table(row("李四","经理","A002")))));
        assertTrue(index.continuations().isEmpty());
    }

    @Test void explicitSameTitleAndSameHeaderContinuationLinksTablesOnly(){
        String next="人员表（续）\n"+table(row("","经理","A002"));
        cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(document(page(10,"人员表\n"+table(row("张三","经理","A001"))),page(11,next)));
        assertEquals(1,index.continuations().size());assertEquals("LINKED",index.continuations().get(0).status());
        cn.bidcheck.document.TableRelationIndex.Association nextName=index.rows().get(1).associations().get(0);assertEquals("",nextName.value());assertFalse(nextName.inherited());assertEquals(11,nextName.source().page());
    }

    @Test void missingHeaderDifferentTitleAndNonAdjacentContinuationStayUnresolved(){
        for(Page next:cn.bidcheck.compat.Java8Api.listOf(page(11,"其他表（续）\n"+table(row("李四","经理","A002"))), page(11,"人员表（续）\n<table>"+row("李四","经理","A002")+"</table>"), page(12,"人员表（续）\n"+table(row("李四","经理","A002"))))){
            cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(document(page(10,"人员表\n"+table(row("张三","经理","A001"))),next));
            assertEquals("UNRESOLVED",index.continuations().get(0).status());
        }
    }

    @Test void duplicateNamedPredecessorsDoNotProduceAnArbitraryContinuationLink(){
        cn.bidcheck.document.TableRelationIndex index=TableRelationIndex.build(document(page(10,"人员表\n"+table(row("张三","经理","A001"))+"\n人员表\n"+table(row("李四","经理","A002"))),page(11,"人员表（续）\n"+table(row("王五","经理","A003")))));
        assertEquals("UNRESOLVED",index.continuations().get(0).status());
    }

    @Test void unrelatedDocumentVersionAndIncompleteOcrCannotPublishRelations(){
        Page page=page(1,table(row("张三","经理","A001")));cn.bidcheck.document.TableRelationIndex mismatch=TableRelationIndex.analyze(document(page(2,page.text())),page);
        assertTrue(mismatch.rows().isEmpty());assertEquals("TABLE_SOURCE_NOT_BOUND",mismatch.issues().get(0).code());
        Page pending=new Page(1,page.text(),"PAGE",page.parser(),"[OCR_DEFERRED]");cn.bidcheck.document.TableRelationIndex incomplete=TableRelationIndex.build(document(pending));
        assertTrue(incomplete.rows().isEmpty());assertEquals("TABLE_SOURCE_INCOMPLETE",incomplete.issues().get(0).code());
    }

    @Test void repeatedSingleHeaderIsNotAStaffRecord(){
        cn.bidcheck.document.TableRelationIndex index=index(table(row("张三","经理","A001")+header()+row("李四","经理","A002")));
        assertEquals(2,index.rows().size());assertTrue(index.rows().stream().allMatch(r->r.status().equals("RESOLVED")));
    }

    @Test void encodedTextRemainsTraceableAndDoesNotExecuteMarkup(){
        String source=table(row("张&amp;三","经理<br>负责人","A001"));cn.bidcheck.document.TableRelationIndex index=index(source);
        assertEquals("张&三",index.rows().get(0).associations().get(0).value());
        assertEquals("张&amp;三",index.rows().get(0).associations().get(0).rawValue());assertEquals("经理 负责人",index.rows().get(0).associations().get(1).value());
        cn.bidcheck.document.TableRelationIndex script=index(table(row("<script>alert(1)</script>","经理","A001")));assertTrue(script.rows().isEmpty());
    }

    @Test void changingDocumentVersionChangesIdsButNotTheUnderlyingTextOffsets(){
        Page page=page(1,table(row("张三","经理","A001")));Document a=document(page),b=new Document(a.id(),a.projectId(),a.role(),a.name(),4,"sha-next","",a.pages(),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.document.TableRelationIndex first=TableRelationIndex.build(a);cn.bidcheck.document.TableRelationIndex second=TableRelationIndex.build(b);assertNotEquals(first.tables().get(0).id(),second.tables().get(0).id());
        assertEquals(first.rows().get(0).associations().get(0).source().start(),second.rows().get(0).associations().get(0).source().start());
    }

    @Test void tdMultiLevelHeadersUseOnlyExplicitKnownLabels(){
        cn.bidcheck.document.TableRelationIndex index=index("<table><tr><td rowspan='2'>姓名</td><td colspan='2'>证件信息</td></tr><tr><td>证书名称</td><td>证书编号</td></tr>"+row("张三","资格证书","A001")+"</table>");
        assertEquals(2,index.tables().get(0).headerRows());assertEquals("RESOLVED",index.rows().get(0).status());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("证件信息", "证书编号"),index.rows().get(0).associations().get(2).headerPath());
    }

    @Test void idSpanCoveringDifferentNamesCannotCorroborateTheSamePerson(){
        cn.bidcheck.document.TableRelationIndex index=index("<table><tr><th>姓名</th><th>身份证号</th><th>职务</th></tr><tr><td>张三</td><td rowspan='2'>110101199001010011</td><td>经理</td></tr><tr><td>李四</td><td>工程师</td></tr></table>");
        assertTrue(index.rows().stream().allMatch(r->r.status().equals("UNRESOLVED")));
        assertTrue(index.rows().stream().flatMap(r->r.associations().stream()).allMatch(a->a.status().equals("AMBIGUOUS")));
    }

    @Test void repeatedExplicitIdWithinOneMergedNameKeepsValidSourceRelationships(){
        cn.bidcheck.document.TableRelationIndex index=index("<table><tr><th>姓名</th><th>身份证号</th><th>职务</th></tr><tr><td rowspan='2'>张三</td><td>110101199001010011</td><td>经理</td></tr><tr><td>110101199001010011</td><td>工程师</td></tr></table>");
        assertTrue(index.issues().isEmpty(),index.issues().toString());assertTrue(index.rows().stream().allMatch(r->r.status().equals("RESOLVED")));
    }

    @Test void explicitlyEmptyHtmlRowCanBeFullyCoveredByExistingSpans(){
        cn.bidcheck.document.TableRelationIndex index=index("<table><tr><th>姓名</th><th>职务</th></tr><tr><td rowspan='2'>张三</td><td rowspan='2'>经理</td></tr><tr></tr></table>");
        assertTrue(index.issues().isEmpty(),index.issues().toString());assertEquals(2,index.rows().size());assertTrue(index.rows().get(1).associations().stream().allMatch(TableRelationIndex.Association::inherited));
    }
}
