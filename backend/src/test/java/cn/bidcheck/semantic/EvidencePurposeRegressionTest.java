package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidencePurposeRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void numericTableCellsKeepTheirBoundariesWithoutChangingDecimalsOrUnits() {
        java.util.List<cn.bidcheck.domain.Domain.Evidence> sources=cn.bidcheck.compat.Java8Api.listOf(source("项目甲 2022 年 4 1501.16 合同金额\n项目乙 2022 年 11 348.80\n金额 1, 234.50 元"));
        assertTrue(ClaimGrounding.amountValueGrounded("1501.16",sources));
        assertTrue(ClaimGrounding.amountValueGrounded("348.80",sources));
        assertTrue(ClaimGrounding.amountValueGrounded("1234.50",sources));
        assertFalse(ClaimGrounding.amountValueGrounded("123",cn.bidcheck.compat.Java8Api.listOf(source("金额12.3元"))));
        assertFalse(ClaimGrounding.amountValueGrounded("1.233",cn.bidcheck.compat.Java8Api.listOf(source("金额12330元"))));
    }
    @Test void unavailableTenderAttachmentsDoNotCreateBidderObligationsOrMergeWarrantyPeriods() {
        Checkpoint cp=rule("附件待核", "本轮未提供合同及保修书附件，本项应UNDETERMINED。缺陷责任期与保修义务分开核验。");
        JsonNode claims=claim("TEXT","有技术分册目录","C1");Map<String,Evidence> source=cn.bidcheck.compat.Java8Api.mapOf("C1", source("技术分册目录"));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(EvidenceScope.issue(cp,"UNDETERMINED","无法核验其保修期限是否与招标正文缺陷责任期一致或存在冲突。",claims,source)));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(EvidenceScope.issue(cp,"UNDETERMINED","招标文件要求投标人另行引用招标文件附件合同及保修书。",claims,source)));
        assertEquals("",EvidenceScope.issue(cp,"UNDETERMINED","本轮未取得招标附件；缺陷责任期与保修期限是不同义务，应分开核验，不要求一致。",claims,source));
    }
    @Test void explicitUndeterminedForOnSiteQueryIsNotSatisfiedByOldScreenshots() {
        Checkpoint cp=rule("信誉核验", "在投标截止后评标结束前进行联网核查，以现场查询结果为准。现场查询结果未提供的部分应UNDETERMINED。");
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(EvidenceScope.issue(cp,"PASS","截图覆盖范围。建议评标阶段仍按招标要求进行现场联网核查。",claim("TEXT","已有信用查询截图","C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("历史信用查询截图：未列入经营异常名录。")))));
        assertEquals("",EvidenceScope.issue(cp,"WARNING","官方查询发现实际不利记录。",claim("TEXT","实际查询命中","C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("现场查询结果：合成单位已列入经营异常名录。"))));
        assertEquals("",EvidenceScope.issue(cp,"PASS","已核对本次评标现场查询结果。",claim("TEXT","本次现场查询结果无异常","C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("本次评标现场查询结果：合成单位未列入经营异常名录。"))));
    }
    @Test void literalDeadlineIsNotACertificateValidityAssertion() {
        JsonNode claims = claim("DATE", "招标文件载明的投标截止时间为2025年08月05日09时30分", "T1");
        assertEquals("", ClaimGrounding.validate(claims, cn.bidcheck.compat.Java8Api.mapOf("T1", source("投标截止时间为2025年08月05日09时30分")),
                "日期可能影响投标有效性，最终截止版本尚待核实。"));
    }
    @Test void actualCertificateCoverageStillNeedsItsTimingChain() {
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(ClaimGrounding.validate(claim("DATE", "注册有效期覆盖投标截止日期", "C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("注册有效期2024年01月01日至2027年01月01日")), "证书有效。")));
    }
    @Test void unresolvedExternalAttachmentsMayCiteAnExistingDirectoryWithoutClaimingCompleteBodies() {
        JsonNode claims = claim("TEXT", "投标文件包含技术分册目录及章节", "C1");
        assertEquals("", ClaimGrounding.contentUseIssue("核对全部实质性内容及完整性", "UNDETERMINED",
                "仅凭声明不能确认对全部实质性合同、技术或保修条款的符合性。当前技术分册不视为缺失，但是否覆盖全部要求仍需对照附件验证。",
                claims, cn.bidcheck.compat.Java8Api.mapOf("C1", source("目录\n施工组织设计........38\n设计方案........5"))));
    }
    @Test void directoryCannotProveCompleteSubstantiveContent() {
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(ClaimGrounding.contentUseIssue("设计方案内容完整性", "PASS", "五项均有实质性内容。",
                claim("TEXT", "五项方案正文均有完整内容", "C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("目录\n建设目标........5\n解决方案........30")))));
    }
    @Test void copiedTenderMarkerCannotBecomeAnAdverseSelfCheckInference() {
        String text = "在最近三年内有骗取中标、严重违约、重大工程质量或安全问题的（待查）";
        JsonNode claims = claim("TEXT", "承诺书标注待查", "C1");
        String inference = "标注为待查，表明该事项未完成自查，需补充证明。";
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(EvidenceScope.templateIssue(inference, claims, cn.bidcheck.compat.Java8Api.mapOf("C1", source(text)), tender(text))));
        assertEquals("", EvidenceScope.templateIssue(inference, claims, cn.bidcheck.compat.Java8Api.mapOf("C1", source(text)), tender("不同事项（待查）")));
        assertEquals("", EvidenceScope.templateIssue("模板标注待查，不能据此认定漏填。", claims, cn.bidcheck.compat.Java8Api.mapOf("C1", source(text)), tender(text)));
    }
    @Test void blacklistVerificationGapWithoutAnAdverseRecordIsPendingNotARiskHit() {
        Checkpoint rule=rule("黑名单核查", "核对黑名单承诺，禁入期和招标人现场名单结果未提供时应待核实。");
        String explanation="已提供承诺，当前无证据表明已提供招标人现场核查结果或官方联网查询证明。";
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(EvidenceScope.issue(rule,"WARNING",explanation,claim("TEXT","投标人提供承诺","C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("黑名单管理承诺书\n我方承诺遵守禁入规定。")))));
        assertEquals("",EvidenceScope.issue(rule,"WARNING",explanation,claim("TEXT","官方记录命中","C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("查询结果：合成公司已列入黑名单，处于禁入期。"))));
    }
    @Test void acknowledgedScheduleGapStillPreventsPassEvenIfAPlanWasRetrieved() {
        Checkpoint rule=rule("计划节点", "核对本项目进度安排，具体阶段节点未能确认时待核实。");
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(EvidenceScope.issue(rule,"PASS","已找到计划，但无法确认具体阶段节点是否符合。",
                claim("TEXT","本项目进度计划包含节点","C1"),
                cn.bidcheck.compat.Java8Api.mapOf("C1", source("本项目施工进度计划：第1天开工，第20天完工。")))));
    }
    private JsonNode claim(String kind,String text,String id){return mapper.valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", kind, "scope", "CURRENT_PROJECT", "text", text, "evidenceIds", cn.bidcheck.compat.Java8Api.listOf(id))));}
    private static Evidence source(String text){return new Evidence("bid","合成材料",1,"PAGE",text,"p1",1);}
    private static Document tender(String text){return new Document("tender","project","TENDER","招标",1,"hash","now",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PARAGRAPH","NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());}
    private static Checkpoint rule(String title,String expected){return new Checkpoint("rule",title,"资格","HIGH","SEMANTIC_RESPONSE","","",expected,"",true,true,true,null);}
}
