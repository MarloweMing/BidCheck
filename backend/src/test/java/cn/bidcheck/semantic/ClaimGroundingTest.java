package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaimGroundingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void printedDeclarationDateAllowsControlledSigningDateAliasButNotValidityOrIssueDate(){
        Evidence declaration=source(276,"企业经营状况承诺书\n我方承诺满足要求。\n投标人（盖章）：甲公司\n法定代表人（签字）：\n日期：2025 年 10 月 8 日\n注：本承诺书未签章或未提交，将导致投标被否决。");
        for(String field:cn.bidcheck.compat.Java8Api.listOf("签署日期", "落款日期", "签字日期"))assertTrue(ClaimGrounding.dateValueGrounded(mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "DATE", "validityField", field, "value", "2025-10-08")),cn.bidcheck.compat.Java8Api.listOf(declaration)),field);
        for(String field:cn.bidcheck.compat.Java8Api.listOf("有效期", "发证日期", "投标截止时间"))assertFalse(ClaimGrounding.dateValueGrounded(mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "DATE", "validityField", field, "value", "2025-10-08")),cn.bidcheck.compat.Java8Api.listOf(declaration)),field);
        Evidence certificate=source(15,"资质证书\n持证人：甲公司\n发证日期：2025年10月8日\n有效期：2028年10月8日");
        assertFalse(ClaimGrounding.dateValueGrounded(mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "DATE", "validityField", "签署日期", "value", "2025-10-08")),cn.bidcheck.compat.Java8Api.listOf(certificate)));
    }

    @Test void aNamedChapterTitleListIsNavigationAndCannotProveBodies(){
        java.util.Map<java.lang.String, cn.bidcheck.domain.Domain.Evidence> sources=cn.bidcheck.compat.Java8Api.mapOf("C1", source(4,"目录\n1.安全承诺书 ............. 470\n2.保密承诺书 ............. 471"));
        JsonNode navigation=mapper.valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "TEXT", "text", "承诺书章节标题及子项清单", "value", "安全承诺书 保密承诺书", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C1"))));
        assertEquals("",ClaimGrounding.contentUseIssue("承诺书清单完整性","PASS","各份正文均已提供。",navigation,sources));
        for(String statement:cn.bidcheck.compat.Java8Api.listOf("目录证明各份正文均已提供", "章节标题及子项清单证明正文齐全", "设计方案包含设计理念章节")){
            JsonNode claimedBody=mapper.valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "TEXT", "text", statement, "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C1"))));
            assertFalse(ClaimGrounding.contentUseIssue("内容完整性","PASS","各章节均有实质性内容描述。",claimedBody,sources).isEmpty(),statement);
        }
    }

    @Test void expiryOnlyFieldDoesNotConsumeIssueAuthorityDate(){
        Evidence certificate=source(15,"资质证书\n有效期：至2026年06月04日\n中华人民共和国住房和城乡建设部制\n发证机关：\n2021年06月16日");
        JsonNode end=mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "DATE", "validityField", "有效期", "endDate", "2026-06-04"));
        assertTrue(ClaimGrounding.dateValueGrounded(end,cn.bidcheck.compat.Java8Api.listOf(certificate)));
        JsonNode invented=mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "DATE", "validityField", "有效期", "startDate", "2021-06-16", "endDate", "2026-06-04"));
        assertFalse(ClaimGrounding.dateValueGrounded(invented,cn.bidcheck.compat.Java8Api.listOf(certificate)));
        JsonNode mislabelled=mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "DATE", "validityField", "有效期", "value", "2021-06-16"));
        assertFalse(ClaimGrounding.dateValueGrounded(mislabelled,cn.bidcheck.compat.Java8Api.listOf(certificate)));
    }

    @Test void listedChapterCannotProveSubstantiveBodyButIndexExistenceRemainsValid() {
        Map<String,Evidence> available=cn.bidcheck.compat.Java8Api.mapOf("C1", source(1,"目录\n第三章、设计理念 ................. 18\n第四章、重难点 ................. 24"), "C2", source(2,"第三章、设计理念\n结合现场动线与办公需求，按功能布局组织设备与公共空间。"));
        JsonNode indexClaim=mapper.valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "TEXT", "text", "设计方案包含设计理念章节", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C1"))));
        assertFalse(ClaimGrounding.contentUseIssue("设计方案五项内容完整性","PASS","各章节均有实质性内容描述，内容齐全。",indexClaim,available).isEmpty());
        assertEquals("",ClaimGrounding.contentUseIssue("技术索引页码定位","PASS","目录列有该章节页码。",indexClaim,available));
        JsonNode bodyClaim=mapper.valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "TEXT", "text", "设计理念章节有方案正文", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C2"))));
        assertEquals("",ClaimGrounding.contentUseIssue("设计理念内容完整性","PASS","该章节已有正文。",bodyClaim,available));
    }

    @Test void moneyOriginalValuesKeepDecimalPointAndAcceptOnlyFormattingNormalization() {
        assertFalse(ClaimGrounding.amountValueGrounded("12.3",cn.bidcheck.compat.Java8Api.listOf(source(1,"合同总价123元。"))));
        assertFalse(ClaimGrounding.amountValueGrounded("1335.059797万元",cn.bidcheck.compat.Java8Api.listOf(source(1,"总价13350597.97元。"))));
        assertTrue(ClaimGrounding.amountValueGrounded("1,234.50",cn.bidcheck.compat.Java8Api.listOf(source(1,"合同总价 1234.50 元。"))));
        assertTrue(ClaimGrounding.amountValueGrounded("1234.5",cn.bidcheck.compat.Java8Api.listOf(source(1,"合同总价1,234.50元。"))));
    }

    @Test void exactTotalPriceStillCannotBeRelabeledAsConstructionCostForScoring() {
        JsonNode claim=mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "AMOUNT", "text", "不含税总价属建安费范畴", "amountBasis", "建安费", "value", "13880000"));
        assertFalse(ClaimGrounding.amountScopeIssue(claim,cn.bidcheck.compat.Java8Api.listOf(source(1,"中标公示：不含税总价13880000元；工程造价13880000元。"))).isEmpty());
        assertEquals("",ClaimGrounding.amountScopeIssue(claim,cn.bidcheck.compat.Java8Api.listOf(source(1,"合同建安费：13880000元，不含税。"))));
        JsonNode smaller=mapper.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("kind", "AMOUNT", "text", "建安费1335.06万元", "amountBasis", "建安费", "value", "1335.06"));
        assertFalse(ClaimGrounding.amountScopeIssue(smaller,cn.bidcheck.compat.Java8Api.listOf(source(1,"工程总价1335.06万元。"), source(2,"招标要求建安费不低于200万元。"))).isEmpty());
        assertFalse(ClaimGrounding.amountScopeIssue(smaller,cn.bidcheck.compat.Java8Api.listOf(source(1,"建安费暂未明确，工程总价1335.06万元。"))).isEmpty());
    }

    @Test void scoringCannotBeDeclaredWhileItsOwnRequiredProofsAreExplicitlyUnverified() {
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),"其余材料形式完整，可按规则计分。建议评审时核对社保缴纳单位，并确认项目负责人签字及甲方盖章。").isEmpty());
        assertEquals("",validate(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),"所列合同金额尚未核实，暂不能确定可得分情况。"));
        assertEquals("",validate(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),"该条件经逐项核对符合，可按规则计分。"));
    }

    @Test void projectNumberAgreementDoesNotTriggerCertificateFieldInstructions() {
        assertEquals("",validate(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),"封面与投标函招标编号均为TEST-2025，与本项目编号一致。"));
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),"证书查询的注册编号一致。").isEmpty());
    }

    @Test void agreementWithOnlyCompanyNamesCannotProveAPersonsEmployer() {
        Map<String, Evidence> sources = cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, "联合体协议书\n甲方施工有限公司负责施工，乙方设计有限公司负责设计。"));
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(relation("合成人员甲", "乙方设计有限公司", "注册单位", "合成人员甲的注册单位为乙方设计有限公司。", "C1")), sources, "").isEmpty());
    }

    @Test void independentlySourcedAtomicRelationsAreAccepted() {
        Map<String, Evidence> sources = cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, "姓名：合成人员甲\n注册单位：乙方设计有限公司"), "C2", source(2, "联合体协议书\n乙方设计有限公司负责本项目设计工作。"));
        assertEquals("", validate(cn.bidcheck.compat.Java8Api.listOf(relation("合成人员甲", "乙方设计有限公司", "注册单位", "合成人员甲的注册单位为乙方设计有限公司。", "C1"), relation("乙方设计有限公司", "本项目设计工作", "负责", "乙方设计有限公司负责本项目设计工作。", "C2")), sources, ""));
    }

    @Test void electronicCertificateNumberCannotBeRelabeledAsRegistrationNumber() {
        Map<String, Evidence> sources = cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, "注册编号：合成证甲123\n姓名：合成人员甲"), "C2", source(2, "电子证书编号：合成证甲123\n注册编号：合成注乙456"));
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(comparison("注册编号", "合成证甲123")), sources, "两处注册编号一致。").isEmpty());
    }

    @Test void MatchingValuesOfTheSameNamedFieldAreAccepted() {
        Map<String, Evidence> sources = cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, "注册编号：合成注甲123"), "C2", source(2, "查询结果\n注册编号：合成注甲123"));
        assertEquals("", validate(cn.bidcheck.compat.Java8Api.listOf(comparison("注册编号", "合成注甲123")), sources, "两处注册编号一致。"));
    }

    @Test void assertionOfMatchingNumbersRequiresStructuredFieldEvidence() {
        Map<String, Evidence> sources = cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, "注册编号：合成注甲123"));
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(), sources, "证书和查询的注册编号一致。").isEmpty());
        assertEquals("", validate(cn.bidcheck.compat.Java8Api.listOf(), sources, "无法确认注册编号是否一致，需核对。"));
    }

    @Test void validityCoverageWithoutAnExplicitReferenceSourceIsRejected() {
        Map<String, Evidence> sources = cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, "注册有效期：2024年01月01日至2026年12月31日"));
        Map<String,Object> claim = date("注册有效期", "2024-01-01", "2026-12-31", "2025-06-01", "C1");
        claim.put("referenceEvidenceIds", cn.bidcheck.compat.Java8Api.listOf());
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(claim), sources, "注册有效期覆盖投标截止日。").isEmpty());
    }

    @Test void certificateExpiredBeforeTheCitedReferenceCannotClaimCoverage() {
        Map<String, Evidence> sources = datedSources("注册有效期：2024年01月01日至2025年05月31日");
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(date("注册有效期", "2024-01-01", "2025-05-31", "2025-06-01", "C1", "T1")), sources, "注册有效期覆盖投标截止日。").isEmpty());
    }

    @Test void inventedIntervalCannotBorrowOtherDatesFromTheSameCertificate() {
        Map<String, Evidence> sources = datedSources("注册有效期：2024年01月01日至2025年05月31日\n打印日期：2025年06月01日\n备注更新日：2026年12月31日");
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(date("注册有效期", "2024-01-01", "2026-12-31", "2025-06-01", "C1", "T1")), sources, "注册有效期覆盖投标截止日。").isEmpty());
    }

    @Test void separateUseValidityCannotBeIgnoredByACoverageClaim() {
        Map<String, Evidence> sources = datedSources("注册有效期：2024年01月01日至2026年12月31日\n使用有效期：2025年01月01日至2025年05月31日");
        assertFalse(validate(cn.bidcheck.compat.Java8Api.listOf(date("注册有效期", "2024-01-01", "2026-12-31", "2025-06-01", "C1", "T1")), sources, "证书有效期覆盖投标截止日。").isEmpty());
    }

    @Test void bothActualValidityIntervalsWithTheCitedReferenceAreAccepted() {
        Map<String, Evidence> sources = datedSources("注册有效期：2024年01月01日至2026年12月31日\n使用有效期：2025年01月01日至2025年12月31日");
        assertEquals("", validate(cn.bidcheck.compat.Java8Api.listOf(date("注册有效期", "2024-01-01", "2026-12-31", "2025-06-01", "C1", "T1"), date("使用有效期", "2025-01-01", "2025-12-31", "2025-06-01", "C1", "T1")), sources, "两项期限均覆盖所提供招标文本的截止日期。"));
    }

    private String validate(List<Map<String, Object>> claims, Map<String, Evidence> sources, String explanation) {
        JsonNode node = mapper.valueToTree(claims);
        return ClaimGrounding.validate(node, sources, explanation);
    }
    private static Map<String, Object> relation(String subject, String object, String relation, String text, String id) {
        return cn.bidcheck.compat.Java8Api.mapOf("kind", "RELATION", "subject", subject, "object", object, "relation", relation, "text", text, "evidenceIds", cn.bidcheck.compat.Java8Api.listOf(id));
    }
    private static Map<String, Object> comparison(String field, String value) {
        return cn.bidcheck.compat.Java8Api.mapOf("kind", "FIELD_COMPARISON", "field", field, "text", "两份来源的相同字段值一致。", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C1", "C2"), "comparisons", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("evidenceId", "C1", "label", field, "value", value), cn.bidcheck.compat.Java8Api.mapOf("evidenceId", "C2", "label", field, "value", value)));
    }
    private static Map<String, Object> date(String field, String start, String end, String reference, String... ids) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "DATE"); value.put("text", field + "覆盖招标文本载明的投标截止日期。");
        value.put("validityField", field); value.put("startDate", start); value.put("endDate", end);
        value.put("referenceDate", reference); value.put("referenceBasis", "投标截止时间");
        value.put("evidenceIds", cn.bidcheck.compat.Java8Api.listOf(ids)); value.put("referenceEvidenceIds", cn.bidcheck.compat.Java8Api.listOf("T1"));
        return value;
    }
    private static Map<String, Evidence> datedSources(String certificate) {
        return cn.bidcheck.compat.Java8Api.mapOf("C1", source(1, certificate), "T1", new Evidence("synthetic-tender", "合成招标文件", 1, "PARAGRAPH", "投标截止时间：2025年06月01日09时30分", "投标截止时间", 1));
    }
    private static Evidence source(int page, String quote) {
        return new Evidence("synthetic-bid", "合成投标文件", page, "PAGE", quote, "原文", 1);
    }
}
