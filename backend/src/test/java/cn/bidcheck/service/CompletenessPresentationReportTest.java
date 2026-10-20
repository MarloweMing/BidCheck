package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletenessPresentationReportTest {
    Evidence source(){return new Evidence("tender","招标文件.docx",120,"PARAGRAPH","第六章须提供<承诺书>","第六章 / 投标文件组成",1);}
    Map<String,Object> item(String status){return Map.of("id","i-1","item","承诺书","status",status,"reason","尚未取得正文，不表示文件缺失","requirementSources",List.of(source()),"evidences",List.of());}
    @Test void unresolvedChapterIsNotReportedAsMissingBidMaterial(){
        var value=Map.<String,Object>of("required",true,"inventoryResolved",false,"unresolvedReferences",List.of(Map.of("text","第六章","reason","所指范围未展开","sources",List.of(source()))),"itemResults",List.of(item("REQUIREMENT_UNRESOLVED")));
        String html=ReportService.completenessHtml(value),text=ReportService.completenessText(value);
        assertTrue(html.contains("清单尚未完整展开"));assertTrue(html.contains("不能据此认定投标材料缺失"));assertTrue(text.contains("尚未展开的引用：第六章"));
        assertFalse(html.contains("整体通过"));assertFalse(text.contains("必须补交"));
    }
    @Test void sourcesKeepChapterAndParagraphDistinctFromPhysicalPages(){
        var value=Map.<String,Object>of("required",true,"inventoryResolved",true,"itemResults",List.of(item("EVIDENCE_PENDING")),"inventorySources",List.of(source()));
        String html=ReportService.completenessHtml(value),text=ReportService.completenessText(value);
        assertTrue(html.contains("第 120 段"));assertFalse(html.contains("第 120 页"));assertTrue(html.contains("第六章 / 投标文件组成"));assertTrue(text.contains("待补证据"));
        assertTrue(html.contains("&lt;承诺书&gt;"));assertFalse(html.contains("<承诺书>"));
    }
    @Test void verifiedItemHasItsOwnBodyWithoutPromotingWholeRequirement(){
        var proof=new LinkedHashMap<>(item("VERIFIED"));proof.put("evidences",List.of(Map.of("documentId","bid","documentName","投标文件.pdf","page",26,"locationType","PAGE","anchor","经营状况承诺书","quote","本单位经营状况良好。")));
        var value=Map.<String,Object>of("required",true,"inventoryResolved",true,"itemResults",List.of(proof,item("EVIDENCE_PENDING")),"complete",false);
        String html=ReportService.completenessHtml(value);assertTrue(html.contains("已证实"));assertTrue(html.contains("待补证据"));assertTrue(html.contains("第 26 页"));assertTrue(html.contains("整个事项仍以最终结论为准"));assertFalse(html.contains("整体通过"));
    }
    @Test void legacyIdsDoNotInventClickableSourceOrUpgradeToVerified(){
        var value=Map.<String,Object>of("required",true,"inventoryResolved",true,"items",List.of("承诺书"),"verified",List.of(Map.of("item","承诺书","evidenceIds",List.of("C1"))));
        String html=ReportService.completenessHtml(value);assertTrue(html.contains("已记录依据"));assertTrue(html.contains("未提供逐项定位"));assertFalse(html.contains("已证实"));assertFalse(html.contains("href="));
    }
    @Test void absentCompletenessDoesNotInventASection(){assertEquals("",ReportService.completenessHtml(Map.of()));assertEquals("",ReportService.completenessText(Map.of("required",false)));}
    @Test void excludedOptionalItemRetainsReasonButDoesNotIncreaseRequiredCount(){
        var value=Map.<String,Object>of("required",true,"inventoryResolved",true,"itemResults",List.of(item("EVIDENCE_PENDING")),"excludedItems",List.of(Map.of("item","可选补充说明","reason","条款明确为可选附件","requirementSources",List.of(source()))));
        String html=ReportService.completenessHtml(value),text=ReportService.completenessText(value);assertTrue(html.contains("已展开 1 项要求"));assertTrue(html.contains("不纳入必检清单"));assertTrue(html.contains("条款明确为可选附件"));assertTrue(text.contains("不纳入必检清单：可选补充说明"));
    }
    @Test void bodyProofScopeLabelsLocatedTextSeparatelyFromStampCheck(){
        var value=Map.<String,Object>of("required",true,"inventoryResolved",true,"proofScope","BODY_PRESENCE_ONLY","authenticityVerified",false,"itemResults",List.of(item("VERIFIED")));
        String html=ReportService.completenessHtml(value),text=ReportService.completenessText(value);assertTrue(html.contains("清单正文核对"));assertTrue(html.contains("已定位正文"));assertTrue(text.contains("已定位正文"));assertTrue(html.contains("盖章按对应项展示"));assertFalse(html.contains("真实性"));assertFalse(html.contains("无效"));assertFalse(html.contains("要求未展开"));
    }
}
