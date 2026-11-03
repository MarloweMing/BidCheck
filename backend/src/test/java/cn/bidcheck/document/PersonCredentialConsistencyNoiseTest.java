package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonCredentialConsistencyNoiseTest {
    private static Page page(String text){return new Page(22,text,"PAGE","QWEN_PDF_OCR",null);}
    private static Document doc(Page page){return new Document("bid","project","BID","人员证明.pdf",2,"source-hash","2026-10-16",cn.bidcheck.compat.Java8Api.listOf(page),cn.bidcheck.compat.Java8Api.listOf());}
    private static PersonCredentialFacts.Result scan(String text){Page p=page(text);return PersonCredentialFacts.analyzeForConsistency(doc(p),p);}
    private static List<PersonCredentialFacts.Occurrence> fields(PersonCredentialFacts.Result r,String field){return cn.bidcheck.compat.Java8Api.toList(r.occurrences().stream().filter(o->o.field().equals(field)));}
    private static List<Map<String,Object>> rejected(PersonCredentialFacts.Result r){return cn.bidcheck.compat.Java8Api.toList(r.issues().stream().filter(i->"PERSON_EXTRACTION_NOISE_REJECTED".equals(i.get("code"))));}

    @Test void filtersPortraitEstimatesButKeepsExplicitAgeAndEveryOtherField(){
        for(String description:cn.bidcheck.compat.Java8Api.listOf("估计**：约35–45岁（基于面部轮廓、皮肤状态、发际线）", "段：青年（约18–30岁）", "精确估算、背景色校正），可提供更高清原图")){
            cn.bidcheck.document.PersonCredentialFacts.Result r=scan("姓名：测试甲\n年龄"+description+"\n性别：男\n身份证号：110101199001011234");
            assertTrue(fields(r,"age").isEmpty());assertEquals("测试甲",fields(r,"personName").get(0).value());
            assertEquals("男",fields(r,"gender").get(0).value());assertEquals(1,fields(r,"idNumber").size());
            assertEquals("IMAGE_AGE_ESTIMATE",rejected(r).get(0).get("reasonCode"));
        }
        assertEquals("44岁",fields(scan("姓名：测试甲\n年龄：44岁"),"age").get(0).value());
    }

    @Test void rejectsNameDescriptionsWithoutInventingAnOwnerForRemainingFields(){
        for(String value:cn.bidcheck.compat.Java8Api.listOf("通过国家统一组织的考试，取得一级建造师的执业资格。", "已经通过考核，成绩合格。", "近期免冠彩色头像照片（图中人物身穿黄色上衣）。", "信息（左侧卡片）", "签名：", "证件照*", "> -")){
            cn.bidcheck.document.PersonCredentialFacts.Result r=scan("身份证\n持证人："+value+"\n公民身份号码：110101199001011234");
            assertTrue(fields(r,"personName").isEmpty(),value);assertEquals(1,fields(r,"idNumber").size());
            assertEquals("",fields(r,"idNumber").get(0).personName());
            assertEquals("MISSING",fields(r,"idNumber").get(0).context().get("personNameStatus"));
            assertEquals(1,rejected(r).size());
        }
    }

    @Test void preservesNamesInDifferentLanguagesAndMaskedOrMissingValuesForReview(){
        for(String value:cn.bidcheck.compat.Java8Api.listOf("张 三", "欧阳测试", "阿依古丽·测试", "Jane Example", "张*", "/", "未知", "无", "-", "信息", "印章")){
            cn.bidcheck.document.PersonCredentialFacts.Result r=scan("姓名："+value+"\n身份证号：11010119900101****");
            assertEquals(1,fields(r,"personName").size(),value);assertTrue(rejected(r).isEmpty(),value);
            assertEquals(1,fields(r,"idNumber").size());
        }
    }

    @Test void removesDutiesAndQrDescriptionsButKeepsActualRoles(){
        for(String value:cn.bidcheck.compat.Java8Api.listOf("发包人及", "号", "Detection Patterns**:", "patterns — partially visible", "职责划分", "职责的划分，确保项目高效、有序地推进。", "的具体职责和工作流程，确保每个环节都有专人负责", "培训等证书的核验。")){
            cn.bidcheck.document.PersonCredentialFacts.Result r=scan("姓名：测试甲\n岗位："+value+"\n性别：男");
            assertTrue(fields(r,"role").isEmpty(),value);assertEquals("",fields(r,"personName").get(0).context().get("role"));
            assertEquals(1,rejected(r).size(),value);
        }
        for(String value:cn.bidcheck.compat.Java8Api.listOf("项目经理", "专职安全员", "建筑施工工程师", "Project Manager", "/"))
            assertEquals(value,fields(scan("姓名：测试甲\n岗位："+value),"role").get(0).value());
    }

    @Test void removesOnlyExactCompoundLabelFragments(){
        cn.bidcheck.document.PersonCredentialFacts.Result r=scan("姓名：测试甲\n学历证书\n专业 主要工作经历\n职称审批专用章\n出生日期：1990-01-01");
        assertTrue(fields(r,"education").isEmpty());assertTrue(fields(r,"professionalMajor").isEmpty());assertTrue(fields(r,"professionalTitle").isEmpty());
        assertEquals(3,rejected(r).size());assertEquals(1,fields(r,"birthDate").size());
        cn.bidcheck.document.PersonCredentialFacts.Result valid=scan("姓名：测试甲\n学历：本科\n专业：建筑设计\n职称：高级工程师");
        assertEquals("本科",fields(valid,"education").get(0).value());assertEquals("建筑设计",fields(valid,"professionalMajor").get(0).value());
        assertEquals("高级工程师",fields(valid,"professionalTitle").get(0).value());assertTrue(rejected(valid).isEmpty());
    }

    @Test void rejectionsKeepExactSourceIdsAndOffsetsForReconciliation(){
        String source="姓名：测试甲\n年龄估计：约40–50岁\n岗位职责划分\n性别：男";
        Page p=page(source);Document d=doc(p);cn.bidcheck.document.PersonCredentialFacts.Result legacy=PersonCredentialFacts.analyze(d,p);cn.bidcheck.document.PersonCredentialFacts.Result filtered=PersonCredentialFacts.analyzeForConsistency(d,p);
        assertEquals(2,rejected(filtered).size());
        for(java.util.Map<java.lang.String, java.lang.Object> item:rejected(filtered)){
            cn.bidcheck.document.PersonCredentialFacts.Occurrence old=legacy.occurrences().stream().filter(o->o.id().equals(item.get("oldOccurrenceId"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
            assertEquals(old.rawValue(),item.get("rawValue"));assertEquals(old.evidence(),item.get("evidence"));
            assertEquals(item.get("rawValue"),source.substring((int)item.get("start"),(int)item.get("end")));
            assertEquals("source-hash",item.get("documentHash"));assertEquals(2,item.get("documentVersion"));assertEquals(false,item.get("requiresReview"));
            assertEquals("EXTRACTION_NOISE",item.get("category"));
        }
        assertEquals(filtered,PersonCredentialFacts.analyzeForConsistency(d,p));assertEquals(source,p.text());
        assertEquals(legacy,PersonCredentialFacts.analyze(d,p));assertEquals("person-credential-source-2-layout-text",PersonCredentialFacts.VERSION);
        cn.bidcheck.document.PersonCredentialFacts.Occurrence oldGender=fields(legacy,"gender").get(0);cn.bidcheck.document.PersonCredentialFacts.Occurrence newGender=fields(filtered,"gender").get(0);
        assertEquals(oldGender.id(),newGender.id());assertEquals(oldGender.evidence(),newGender.evidence());
    }

    @Test void doesNotDiscardLaterRealFactsOnPagesContainingGeneratedCommentary(){
        cn.bidcheck.document.PersonCredentialFacts.Result r=scan("姓名：测试甲\n年龄估计：约35–45岁\n图像分析：照片背景为白色\n性别：男\n出生日期：1990-01-01\n岗位：安全员\n签发机关：测试住建局");
        assertEquals(1,rejected(r).size());assertEquals("男",fields(r,"gender").get(0).value());
        assertEquals("安全员",fields(r,"role").get(0).value());assertEquals("测试住建局",fields(r,"issuingAuthority").get(0).value());
    }

    @Test void retainsTableAssociationsAndActualRowValues(){
        cn.bidcheck.document.PersonCredentialFacts.Result r=scan("<table><tr><th>姓名</th><th>岗位</th><th>年龄</th></tr><tr><td>测试甲</td><td>施工负责人</td><td>44岁</td></tr></table>");
        assertEquals(3,r.occurrences().size());assertTrue(rejected(r).isEmpty());
        assertTrue(r.occurrences().stream().allMatch(o->"RESOLVED".equals(o.context().get("tableAssociationStatus"))));
    }

    @Test void keepsAdjacentNoSeparatorLabelsAndDoesNotTreatRareNamesAsNoise(){
        cn.bidcheck.document.PersonCredentialFacts.Result r=scan("身份证\n姓名测试甲\n证号110101199001011234\n年龄44岁\n职务项目经理");
        assertEquals("测试甲",fields(r,"personName").get(0).value());assertEquals("44岁",fields(r,"age").get(0).value());
        assertEquals("项目经理",fields(r,"role").get(0).value());assertTrue(rejected(r).isEmpty());
    }
}
