package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.service.BusinessResultPresentation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameVariantAttributionTest {
    private Document document(Page... pages) {
        return new Document("bid","project","BID","bid.pdf",1,"hash","",Arrays.asList(pages),Collections.emptyList());
    }
    private Page page(int number,String name,String parser) {
        return new Page(number,"本次投标\n项目负责人："+name,"PAGE",parser,"");
    }
    private Map<String,Object> group(Document document) {
        Map<String,Object> report=new ConsistencyEngine().scan(document,Collections.emptyList());
        return ConsistencyRunSupport.maps(report.get("groups")).stream()
            .filter(g->"personName".equals(g.get("field")) && "legacy-scoped-personName".equals(g.get("ruleId")))
            .findFirst().orElseThrow(AssertionError::new);
    }
    private Evaluation evaluate(Map<String,Object> group) {
        Evaluation raw=new Evaluation("WARNING",Objects.toString(group.get("reason")),"核对原文",Collections.emptyList(),0.6,"FULL_TEXT_CONSISTENCY");
        return NameVariantAttribution.evaluation(group,raw);
    }
    private String state(Map<String,Object> group) {
        Map<String,Object> trace=new LinkedHashMap<>();
        trace.put("consistencyGroupId",group.get("id"));
        trace.put("nameVariantAttribution",group.get("nameVariantAttribution"));
        return Objects.toString(BusinessResultPresentation.describe(evaluate(group),trace).get("state"));
    }

    @Test void ocrOnlySingleCharacterDifferenceAsksUser() {
        Document document=document(page(12,"王明","PDFBOX_NATIVE"),page(17,"王鸣","PDFBOX_OCR"));
        Map<String,Object> group=group(document);
        assertEquals("USER_ACTION",state(group));
        assertEquals("NEEDS_CONFIRMATION",evaluate(group).status());
        assertTrue(evaluate(group).suggestion().contains("第 17 页原图"));
        assertTrue(evaluate(group).suggestion().contains("王明"));
        assertTrue(evaluate(group).suggestion().contains("王鸣"));
    }

    @Test void textLayerNameConflictStaysRisk() {
        Map<String,Object> group=group(document(page(12,"王明","PDFBOX_NATIVE"),page(17,"王鸣","PDFBOX_NATIVE")));
        assertEquals("RISK",state(group));
        assertEquals("WARNING",evaluate(group).status());
        assertFalse(group.containsKey("nameVariantAttribution"));
    }

    @Test void visualReadingKeepsItsActualSourceInsteadOfCallingItOcr() {
        Map<String,Object> group=group(document(page(12,"王明","PDFBOX_NATIVE"),page(17,"王鸣","VISUAL_TRANSCRIPT")));
        assertEquals("USER_ACTION",state(group));
        assertTrue(evaluate(group).reason().contains("视觉识别"));
        assertFalse(evaluate(group).reason().contains("OCR"));
        assertTrue(evaluate(group).suggestion().contains("第 17 页原图"));
    }

    @Test void nativePortionOfMixedPageAndMultipleCharacterDifferencesStayRisk() {
        Page mixed=new Page(17,"【原生文字】\n本次投标\n项目负责人：王鸣\n\n【OCR识别文字】\n项目负责人：王鸣", "PAGE","PDFBOX_NATIVE_WITH_OCR","");
        assertEquals("RISK",state(group(document(page(12,"王明","PDFBOX_NATIVE"),mixed))));
        assertEquals("RISK",state(group(document(page(12,"王明","PDFBOX_NATIVE"),page(17,"李青","PDFBOX_OCR")))));
    }

    @Test void aNativeReadingElsewherePreventsCallingThatNameImageOnly() {
        Document document=document(page(12,"王明","PDFBOX_NATIVE"),page(17,"王鸣","PDFBOX_OCR"),
            new Page(20,"人员签名：王鸣","PAGE","PDFBOX_NATIVE",""));
        assertEquals("RISK",state(group(document)));
    }

    @Test void companyScopedLegalRepresentativeUsesActualPageProvenanceAfterModelExtraction() {
        String nativeText="本次投标\n企业基本信息\n企业名称：北方示例建设有限公司\n法定代表人：王明";
        String recognized="17\n\n【PDF识别文字】\n本次投标\n企业基本信息\n企业名称：北方示例建设有限公司\n法定代表人：王鸣";
        Document document=document(new Page(12,nativeText,"PAGE","PDFBOX_NATIVE",""),new Page(17,recognized,"PAGE","PDFBOX_PDF_OCR",""));
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("field","personName");input.put("subject","法定代表人");input.put("rawValue","王鸣");input.put("scope","CURRENT_PROJECT");
        input.put("evidence",new Evidence("bid","bid.pdf",17,"PAGE",recognized,"p17",0.8));
        Map<String,Object> report=new ConsistencyEngine().scan(document,Collections.singletonList(input));
        Map<String,Object> group=ConsistencyRunSupport.maps(report.get("groups")).stream()
            .filter(g->"personName".equals(g.get("field")) && "法定代表人".equals(g.get("subject"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("USER_ACTION",state(group));
        assertTrue(evaluate(group).reason().contains("17"));
    }
}
