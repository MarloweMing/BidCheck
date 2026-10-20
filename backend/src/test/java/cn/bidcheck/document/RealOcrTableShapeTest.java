package cn.bidcheck.document;

import cn.bidcheck.consistency.PersonCredentialConsistency;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Anonymized structure fixtures copied from the frozen Hainan OCR pages, with synthetic values. */
class RealOcrTableShapeTest {
    private static Page page(int page,String text){return new Page(page,text,"PAGE","QWEN_OCR_TEXT","");}
    private static Document doc(Page...pages){return new Document("doc","project","BID","synthetic.pdf",1,"sha","",List.of(pages),List.of());}
    private static String td(String value){return "<td>"+value+"\n</td>";}
    private static final String INSURANCE_HEADER="<tr><td rowspan=2>序号\n</td><td rowspan=2>姓名\n</td><td rowspan=2>社会保障号码\n</td><td rowspan=2>险种\n</td><td colspan=2>缴费情况\n</td><td rowspan=2>本单位实际缴费月数\n</td></tr><tr>"+td("起始年月")+td("截止年月")+"</tr>";

    @Test void page23NewlineTerminatedTdHeadersAndUnquotedSpansActuallyEnterConsistency(){
        String payment=td("基本养老保险")+td("2025年01月")+td("2025年06月")+td("6");
        Page page=page(23,"<table>"+INSURANCE_HEADER+"<tr><td rowspan=2>1\n</td><td rowspan=2>测试甲\n</td><td rowspan=2>110101199001010011\n</td>"+payment+"</tr><tr>"+payment.replace("基本养老保险","工伤保险")+"</tr></table>");
        var parsed=PersonCredentialFacts.analyzeForConsistency(doc(page),page);assertTrue(parsed.candidate());
        var names=parsed.occurrences().stream().filter(o->o.field().equals("personName")).toList();assertEquals(1,names.size(),"one physical merged identity is not multiplied by insurance rows");
        assertEquals(2,parsed.occurrences().size());assertEquals("测试甲",names.get(0).personName());assertEquals("RESOLVED",names.get(0).context().get("tableAssociationStatus"));
        var report=new PersonCredentialConsistency().scan(doc(page));assertEquals(2,((List<?>)report.get("occurrences")).size());
    }

    @Test void page197ExplicitRoleHeaderAliasKeepsStaffRowsButRejectsMergedSignatureCells(){
        String header="<tr>"+td("在本项目中担任职务")+td("标段号")+td("姓名")+td("注册建造师证")+td("《安全生产考核合格证书》(B/C证)")+td("社保证明")+"</tr>";
        String staff="<tr>"+td("项目经理")+td("第一标段")+td("测试甲")+td("建筑工程专业证书")+td("B证")+td("见附件")+"</tr>";
        String signature="<tr>"+td("法定代表人签字")+td("第一标段")+"<td colspan=2>签字\n</td><td colspan=2>盖章\n</td></tr>";
        Page page=page(197,"<table>"+header+staff+signature+"</table>");var index=TableRelationIndex.build(doc(page));
        assertEquals("RESOLVED",index.rows().get(0).status());assertEquals("UNRESOLVED",index.rows().get(1).status());
        var parsed=PersonCredentialFacts.analyzeForConsistency(doc(page),page);assertEquals(2,parsed.occurrences().size());
        assertEquals("项目经理",parsed.occurrences().stream().filter(o->o.field().equals("role")).findFirst().orElseThrow().value());
        assertTrue(parsed.occurrences().stream().allMatch(o->o.personName().equals("测试甲")));
    }

    @Test void page220FourColumnKeyValueCredentialKeepsExpirySeparateFromReviewNotes(){
        String rows="<tr>"+td("姓名")+td("测试甲")+td("初领日期")+td("2024-05-14")+"</tr>"
                +"<tr>"+td("性别")+td("男")+td("应复审日期")+td("2027-05-13")+"</tr>"
                +"<tr>"+td("作业类别")+td("电工作业")+td("有效期开始日期")+td("2024-05-14")+"</tr>"
                +"<tr>"+td("操作项目")+td("低压电工作业")+td("有效期结束日期")+td("2030-05-13")+"</tr>"
                +"<tr>"+td("签发机关")+td("测试管理局")+td("实际复审日期")+td("2027-04-30")+"</tr>"
                +"<tr><td colspan=4>备注：本证书应于2027-05-13前进行复审\n</td></tr>";
        Page page=page(220,"<table>"+rows+"</table>");var index=TableRelationIndex.build(doc(page));
        assertEquals("KEY_VALUE_PAIRS",index.tables().get(0).structure());assertEquals(5,index.rows().size());assertTrue(index.issues().isEmpty(),index.issues().toString());
        var parsed=PersonCredentialFacts.analyzeForConsistency(doc(page),page);var expiry=parsed.occurrences().stream().filter(o->o.field().equals("validUntil")).toList();
        assertEquals(1,expiry.size());assertEquals("2030-05-13",expiry.get(0).value());assertEquals("测试甲",expiry.get(0).personName());
        assertFalse(parsed.occurrences().stream().anyMatch(o->o.value().equals("2027-05-13")));assertEquals(1,parsed.occurrences().stream().map(PersonCredentialFacts.Occurrence::recordKey).distinct().count());
        assertTrue(parsed.occurrences().stream().allMatch(o->o.evidence().quote().contains(o.rawValue())));
    }

    @Test void page41ProjectMetadataBeforePriceHeaderKeepsTrueOffsetsAndDifferentTaxColumns(){
        String meta="<tr><td colspan=2>招标编号:TEST-2022-001\n</td><td colspan=3>项目名称:历史演示厅装修项目\n</td></tr>";
        String header="<tr>"+td("序号")+td("分项名称")+td("不含增值税总价(元)")+td("税率")+td("含增值税总价(元)")+"</tr>";
        String data="<tr>"+td("1")+td("拆除项目")+td("1000.00")+td("9%")+td("1090.00")+"</tr>";
        Page page=page(41,"<table>"+meta+header+data+"</table>");var index=TableRelationIndex.build(doc(page));assertTrue(index.issues().isEmpty(),index.issues().toString());
        assertEquals(1,index.tables().get(0).headerRows());assertEquals(2,index.rows().size());
        var project=index.rows().get(0).associations().stream().filter(a->a.label().equals("项目名称")).findFirst().orElseThrow();
        assertEquals("历史演示厅装修项目",project.value());assertEquals(project.rawValue(),page.text().substring(project.source().start(),project.source().end()));
        assertEquals("项目名称",page.text().substring(project.headerSources().get(0).start(),project.headerSources().get(0).end()));
        var price=index.rows().get(1).associations();assertEquals("不含增值税总价(元)",price.get(2).label());assertEquals("含增值税总价(元)",price.get(4).label());
    }

    @Test void page42HeaderlessContinuationDoesNotBorrowThePreviousTablesHeadings(){
        Page first=page(41,"报价表\n<table><tr>"+td("项目名称")+td("金额(元)")+"</tr><tr>"+td("项目甲")+td("1000")+"</tr></table>");
        Page next=page(42,"<table><tr>"+td("项目乙")+td("2000")+"</tr></table>");var index=TableRelationIndex.build(doc(first,next));
        assertEquals("UNRESOLVED",index.tables().get(1).structure());assertEquals("UNRESOLVED",index.tables().get(1).rows().get(0).status());assertTrue(index.continuations().isEmpty());
    }

    @Test void twoColumnFieldValueTitleKeepsExistingCredentialExtraction(){
        Page page=page(12,"<table><tr><th>字段</th><th>内容</th></tr><tr>"+td("姓名")+td("测试甲")+"</tr><tr>"+td("身份证号")+td("110101199001010011")+"</tr></table>");
        var index=TableRelationIndex.build(doc(page));assertEquals("KEY_VALUE_PAIRS",index.tables().get(0).structure());assertTrue(index.issues().isEmpty(),index.issues().toString());
        var parsed=PersonCredentialFacts.analyzeForConsistency(doc(page),page);assertEquals(2,parsed.occurrences().size());assertEquals("测试甲",parsed.occurrences().get(1).personName());
    }

    @Test void explicitMetadataRemainsTraceableEvenWhenOtherColumnsHaveNoHeader(){
        Page page=page(41,"<table><tr><td colspan=2>项目名称:历史测试项目\n</td></tr><tr>"+td("工程一")+td("1000")+"</tr></table>");
        var index=TableRelationIndex.build(doc(page));assertEquals("UNRESOLVED",index.tables().get(0).structure());assertEquals("RESOLVED",index.rows().get(0).status());assertEquals("UNRESOLVED",index.rows().get(1).status());
        var field=index.rows().get(0).associations().get(0);assertEquals("项目名称",field.label());assertEquals("历史测试项目",field.value());assertEquals(field.rawValue(),page.text().substring(field.source().start(),field.source().end()));
        assertTrue(index.issues().stream().anyMatch(i->i.code().equals("TABLE_HEADER_UNRESOLVED")));
    }
}
