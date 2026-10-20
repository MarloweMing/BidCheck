package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceScopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void totalScheduleCannotPassFromAValidPromiseAndAContentsHeadingAlone() {
        Checkpoint checkpoint = rule("总工期响应及进度核验", "总工期为30日历天。投标函按格式填写‘满足’属于有效响应。核对施工进度安排是否有相反承诺；没有可确认的具体进度安排时保留待核实。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "投标函\n承诺工期为：满足。"),
                "C2", source(2, "目录\n第三章 进度控制 ........ 20"));
        assertFalse(issue(checkpoint, "投标函格式合规，目录列出进度控制，未发现相反承诺。后续再检查具体计划。", sources).isEmpty());
    }

    @Test void constructionStageCannotPassWithoutActualStageMilestones() {
        Checkpoint checkpoint = rule("施工阶段工期核验", "核对施工阶段不超过20日历天的承诺及进度安排。投标函填写‘满足’可以作为总体承诺；具体阶段节点未能确认时返回待核实。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "投标函\n我方承诺工期满足要求。"),
                "C2", source(2, "施工组织设计目录\n进度控制 ........ 20"));
        assertFalse(issue(checkpoint, "总体承诺满足，因此施工阶段符合；建议后续核对具体节点安排。", sources).isEmpty());
    }

    @Test void InternalConsortiumDeclarationsDoNotCompleteCrossBidderRelationshipChecks() {
        Checkpoint checkpoint = rule("不同投标主体之间的负责人及控股关系", "单位负责人为同一人或存在控股、管理关系的不同单位不得同时投标。联合体成员属于同一投标主体。缺少其他独立投标主体信息时，跨投标人关系仅能待核实。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "联合体协议书\n合成甲施工有限公司与合成乙设计有限公司组成联合体。"),
                "C2", source(2, "股权及管理关系情况表\n对我方拥有管理关系的单位：无。\n我方承诺不存在投标限制情形。"));
        assertFalse(issue(checkpoint, "联合体内部负责人不同，各自声明无管理关系，因此符合全部限制，无需补充。", sources).isEmpty());
    }

    @Test void CurrentEpcDesignResponsibilitiesDoNotEstablishHistoricalServiceAbsence() {
        Checkpoint checkpoint = rule("前期咨询和监督评价参与限制", "为本项目提供前期咨询、监督或评价的单位不得参与，保留EPC例外。不因成员承担本次设计工作认定为被禁止单位。实际历史服务关系不明时待核实。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "联合体协议书\n本项目采用EPC，合成乙设计有限公司负责本次设计。"),
                "C2", source(2, "承诺书\n我方承诺不存在被禁止投标的情形。"));
        assertFalse(issue(checkpoint, "本次设计职责符合EPC例外，承诺书也已提供，满足限制。建议之后查历史咨询及监理关系。", sources).isEmpty());
    }

    @Test void indexStructureCannotStandInForRequiredReferencedMaterialsAndAnAbsentQuotedVolume() {
        Checkpoint checkpoint = rule("商务评审索引及实际材料定位", "提交商务评审索引。核对评审因素、对应页码与实际材料，不以索引中的‘满足要求’替代材料正文。报价明确指向另册时保留待补核验。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "商务评审索引表\n资格证明 P8-10 满足要求\n报价 详见报价部分 满足要求"),
                "C2", source(2, "目录\n商务评审索引表 ........ 1\n资格证明 ........ 8"));
        assertFalse(issue(checkpoint, "索引格式与目录一致，因此符合；后续逐项核查实际材料和报价另册。", sources).isEmpty());
    }

    @Test void aCheckpointLimitedToThePromiseFormatCanPassFromTheActualPromise() {
        Checkpoint checkpoint = rule("投标函工期承诺格式", "仅核对投标函工期栏是否按规定填写‘满足’，不要求在该栏重复总天数。具体施工进度由独立检查项核对。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "投标函\n承诺工期为：满足（填写满足）。"));
        assertEquals("", issue(checkpoint, "实际工期栏填写满足，符合本项格式要求。", sources));
    }

    @Test void actualCurrentProjectPlanWithMilestonesCanCompleteScheduleScope() {
        Checkpoint checkpoint = rule("总工期与实际施工进度", "总工期30日历天，核对本项目投标函与施工进度安排。没有可确认的具体进度安排时保留待核实。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "投标函\n本项目承诺工期为满足。"),
                "C2", source(2, "本项目施工进度计划\n总工期30日历天。第1至5天完成施工准备，第6至25天完成主体施工，第26至30天完成验收及移交。各阶段按上述节点实施。"));
        assertEquals("", issue(checkpoint, "投标函承诺与实际计划对应：准备5天、施工20天、验收5天，总工期30日历天。", sources));
    }

    @Test void aDirectoryExistenceCheckpointDoesNotRequireAllListedDocumentBodies() {
        Checkpoint checkpoint = rule("投标目录存在性", "提交列有章节名称和页码的投标目录。本项仅核对目录存在和目录格式。");
        Map<String,Evidence> sources = Map.of("C1", source(1, "目录\n第一章 投标函 ........ 3\n第二章 资格证明 ........ 5"));
        assertEquals("", issue(checkpoint, "已提供含章节名称与页码的目录，目录格式满足本项。", sources));
    }

    private String issue(Checkpoint checkpoint, String explanation, Map<String,Evidence> sources) {
        List<Map<String,Object>> claims = sources.entrySet().stream().map(entry -> {
            Map<String,Object> claim = new LinkedHashMap<>();
            claim.put("kind", "TEXT"); claim.put("scope", "CURRENT_PROJECT");
            claim.put("text", entry.getValue().quote()); claim.put("evidenceIds", List.of(entry.getKey()));
            return claim;
        }).toList();
        return EvidenceScope.issue(checkpoint, "PASS", explanation, mapper.valueToTree(claims), sources);
    }
    private static Checkpoint rule(String title, String expected) {
        return new Checkpoint("synthetic-rule", title, "资格", "HIGH", "SEMANTIC_RESPONSE", "", "", expected, "", true, true, true, null);
    }
    private static Evidence source(int page, String text) {
        return new Evidence("synthetic-bid", "合成投标文件", page, "PAGE", text, "p" + page, 1);
    }
}
