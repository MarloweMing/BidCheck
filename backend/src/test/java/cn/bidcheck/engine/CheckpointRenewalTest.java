package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointRenewalTest {
    private Document doc(String... pages) {
        List<Page> content=new ArrayList<>();
        for(int i=0;i<pages.length;i++) content.add(new Page(i+1,pages[i],"PAGE","PDFBOX",null));
        return new Document("tender","project","TENDER","招标.pdf",1,"hash","",content,List.of());
    }
    private List<Checkpoint> extract(String... pages) { return new CheckpointExtractor().extract(doc(pages)); }

    @Test void qualificationProvidesMaterialsPreservesAlternativesAndBranchConditions() {
        String clause="应答人须为合法注册的法人或其他组织，提供营业执照（或事业单位法人证书，或社会团体法人登记证书，或执业许可证）的复印件；若应答人为法人的分支机构，还须同时提供具有法人资格的总公司的营业执照复印件、总公司对应答人出具的有效授权书（授权范围须指定或覆盖本项目）。注：1）已由总公司合法授权的，总公司取得的相关资质证书和授权范围内的事项对分支机构有效，但法律法规或者行业另有规定的除外。";
        var rules=extract("资格要求\n"+clause);
        var qualification=rules.stream().filter(c->c.category().equals("资格检查")).toList();
        assertEquals(2,qualification.size());
        assertTrue(qualification.get(0).expected().startsWith("提供营业执照（或事业单位法人证书"));
        assertTrue(qualification.get(0).expected().contains("或执业许可证"));
        assertTrue(qualification.get(1).expected().startsWith("若应答人为法人的分支机构"));
        assertTrue(qualification.get(1).expected().contains("授权范围须指定或覆盖本项目"));
        assertFalse(qualification.get(1).required());
        assertTrue(qualification.stream().allMatch(c->c.source().quote().equals(clause)));
        assertFalse(rules.stream().anyMatch(c->c.type().equals("MATERIAL_EXISTENCE") && c.expected().equals("营业执照")));
    }

    @Test void undertakingKeepsSealRequirementWithoutPromotionalIntroduction() {
        var rules=extract("资格评审标准\n具有良好的银行资信、商业信誉和较强的企业综合实力，能够满足采购人的要求，提供《企业经营状况承诺书》（按询比采购文件提供的模板），并盖公章。");
        var rule=rules.stream().filter(c->c.field().equals("qualification")).findFirst().orElseThrow();
        assertEquals("提供《企业经营状况承诺书》（按询比采购文件提供的模板），并盖公章。",rule.expected());
        assertEquals("资格检查",rule.category());
    }

    @Test void eachScoredCertificateBecomesASeparateResponseCandidate() {
        var rules=extract("评分标准\n考察应答人管理体系认证情况，本项满分3分：\n（1）具备有效的ISO/IEC 20000系列信息技术服务管理体系认证证书的，得1分；\n（2）具备有效的GB/T 22080系列或ISO/IEC 27000系列信息安全管理体系认证证书的，得1分；\n（3）具备有效的GB/T 19000系列或ISO/IEC 9000系列质量管理体系认证证书的，得1分；");
        var scored=rules.stream().filter(c->c.field().equals("scoring")).toList();
        assertEquals(3,scored.size());
        assertEquals("ISO/IEC 20000系列信息技术服务管理体系认证证书",scored.get(0).expected());
        assertTrue(scored.get(1).expected().contains("22080系列或ISO/IEC 27000"));
        assertTrue(scored.stream().allMatch(c->c.category().equals("响应检查")));
    }

    @Test void planCombinesRequiredContentsWithTheHighestScoringTierOnly() {
        String plan="提供人员保障方案，保障人员的稳定性，不影响项目正常工作开展。方案包括但不仅限于不同项目间人员转换风险分析、人员稳定措施、人员补充及替换方式、人员薪酬保障措施。";
        String top="A、方案内容完整、合理、风险分析全面、可行性强的为优，得（1.3,2]分；";
        var rules=extract("评审标准\n"+plan+"\n以上方案内容分为优、良、中、差四个档次进行评分：\n"+top+"\nB、方案内容较为完整，风险分析较为全面，可行性较强为良，得(0.6,1.3]分；\nC、方案内容基本完整，风险分析准确，可执行性一般为中，得(0,0.6]分。\nD、未提供方案或方案不完整，风险分析不准确，可执行性较差，为差，得0分。");
        var scored=rules.stream().filter(c->c.field().equals("scoring")).toList();
        assertEquals(1,scored.size());
        assertEquals(plan+"方案内容完整、合理、风险分析全面、可行性强。",scored.get(0).expected());
        assertFalse(scored.get(0).expected().contains("较为"));
        assertTrue(scored.get(0).source().quote().contains(top));
    }

    @Test void tierSelectionUsesScoreInsteadOfAssumingFirstTierIsHighest() {
        var rules=extract("评分标准\n提供实施方案。\nA、方案基本完整，得1分。\nB、方案完整且可执行，得3分。");
        assertTrue(rules.stream().filter(c->c.field().equals("scoring")).findFirst().orElseThrow().expected().contains("方案完整且可执行"));
    }

    @Test void fiveGroupsAndGlobalChecksRemainEditableUnconfirmedCandidates() {
        var rules=extract("项目名称：示例项目\n开标时间：2026年10月21日\n投标截止时间：2026年10月20日\n资格要求\n应具备独立法人资格。\n第六章 投标文件格式\n一、商务部分\n1.1 投标函\n第七章 评分标准\n施工方案应满足要求，得3分。");
        assertEquals(Set.of("形式检查","资格检查","响应检查","全文信息检查","效期检查"),new HashSet<>(rules.stream().map(Checkpoint::category).toList()));
        assertEquals(7,rules.stream().filter(c->c.type().equals("FULL_TEXT_REVIEW")).count());
        assertTrue(rules.stream().allMatch(c->!c.confirmed() && c.enabled()));
        assertEquals("2026-10-21",rules.stream().filter(c->c.field().equals("signingAfterOpening")).findFirst().orElseThrow().expected());
        assertEquals("2026-10-20",rules.stream().filter(c->c.field().equals("certificateCoverage")).findFirst().orElseThrow().expected());
    }

    @Test void certificateReviewStillExistsWhenReferenceDateWasNotExtracted() {
        var rule=extract("投标文件格式\n投标函").stream().filter(c->c.field().equals("certificateCoverage")).findFirst().orElseThrow();
        assertEquals("",rule.expected()); assertNull(rule.source());
    }
}
