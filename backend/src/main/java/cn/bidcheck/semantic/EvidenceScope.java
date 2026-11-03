package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A declaration proves its wording, not every separate fact the checkpoint asks to verify. */
final class EvidenceScope {
    private EvidenceScope() { }

    static String issue(Checkpoint checkpoint, String status, String explanation,
                        JsonNode claims, Map<String, Evidence> available) {
        String requirement = checkpoint.expected() == null ? "" : checkpoint.expected();
        if (requirement.matches("(?s).*(附件|另册).*" )
                && requirement.matches("(?s).*(本轮|本次审查).{0,20}(未提供|未取得|待补).*")) {
            boolean changedObligation = explanation.matches("(?s).*(要求|必须|需要)投标人.{0,15}(另行引用|提交|补交|补充提供).{0,60}(附件|合同|规范书|保修书).*" );
            boolean conflatedPeriods = false;
            for (String sentence : explanation.split("[。；！？\\n]")) {
                if (sentence.matches("(?s).*(不能混|不得混|不能直接比较|不能要求一致|不要求一致|分开核验|分别核验|不同义务).*")) continue;
                if (sentence.contains("保修") && sentence.contains("缺陷责任期")
                        && sentence.matches("(?s).*(一致|相同|冲突).*")) conflatedPeriods = true;
            }
            if (changedObligation || conflatedPeriods)
                return "本轮尚未取得检查点要求对照的招标附件或另册输入，相关条款仍待核；不能据此认定投标文件漏项，也不能把审查输入缺口改成投标人的新增提交义务。缺陷责任期与工程质量保修义务须按各自条款分别核验，不能预设两者期限必须一致或直接认定冲突。";
        }
        // Only an explicit review boundary activates these contracts. Ordinary declaration,
        // directory-presence and OCR-review recommendations do not acquire extra obligations.
        if (!requirement.matches("(?s).*(待核|待补|未能确认|无法确认|不明时|UNDETERMINED).*")) return "";
        List<Proof> proofs = proofs(claims, available);
        if ("WARNING".equals(status) && requirement.contains("黑名单")
                && requirement.matches("(?s).*(现场|名单|禁入期|官方).*" )
                && explanation.matches("(?s).*(未提供|缺少|缺乏|没有|无法|未取得|未见|尚无|无证据).{0,30}(官方|现场|核验结果|名单|核查结果).*")) {
            boolean adverse = proofs.stream().anyMatch(p -> !declarationOnly(p.source.quote())
                    && p.source.quote().matches("(?s).*(查询结果|查询记录|处罚决定|核查结论).{0,100}(已列入|命中|列入黑名单|处于禁入期).*"));
            if (!adverse) return "相关承诺已有，但检查点要求的官方或现场黑名单、禁入期核验结果尚未确认。当前引用没有经核实的不利名单命中，属于外部事实待核，不能仅因缺核验结果给出风险命中，也不新增投标人提交证明的义务。";
        }
        if (!"PASS".equals(status)) return "";
        if (requirement.matches("(?s).*(现场|评标|联网|官方).{0,25}(核查|查询|核验).*")) {
            boolean currentResult = proofs.stream().anyMatch(p -> !declarationOnly(p.source.quote())
                    && !ClaimGrounding.indexOnly(p.source.quote())
                    && p.statement.matches("(?s).*(现场|评标阶段).{0,20}(核查|查询|核验).*" )
                    && p.source.quote().matches("(?s).*(现场核查记录|现场查询结果|现场核验结果|评标阶段联网核查结果).*"));
            boolean deferred = explanation.matches("(?s).*(建议|后续|仍需|仍按|尚需|尚未|待).{0,80}(现场|联网|官方).{0,20}(核查|查询|核验).*" );
            if (!currentResult || deferred)
                return "声明和已有查询截图只能证明各自载明的内容及查询时点，不能替代检查点明确要求的评标、现场或官方核验结果。当前该部分尚未完成，整体待核实；这不是不利名单命中，也不新增投标人提交义务。";
        }
        if (requirement.matches("(?s).*(进度安排|进度计划|阶段节点).*")) {
            boolean plan = proofs.stream().anyMatch(p -> "CURRENT_PROJECT".equals(p.scope)
                    && !ClaimGrounding.indexOnly(p.source.quote())
                    && p.statement.matches("(?s).*(进度|计划|节点|阶段).*" )
                    && p.source.quote().matches("(?s).*(进度计划|进度安排|施工计划|阶段节点|施工进度|进度控制).*" )
                    && p.source.quote().matches("(?s).*(第\\s*[0-9一二三四五六七八九十]+\\s*天|[0-9]+\\s*(?:日历天|天|日)|[0-9]{4}年[0-9]+月).*" )
                    && !declarationOnly(p.source.quote()));
            boolean deferred = explanation.matches("(?s).*(无法|未能|尚未|不能).{0,16}(确认|核实|核验).{0,25}(进度|节点|计划|冲突).*" )
                    || explanation.matches("(?s).*(后续|仍需|尚需|建议).{0,12}(核对|核实|核查|审查).{0,30}(具体|阶段).{0,15}(进度|节点|计划).*" );
            if (!plan || deferred) return "投标函的工期承诺可按指定格式响应，但本检查点还要求核对本项目实际进度安排或阶段节点；当前引用未完成该部分核验，目录和总体承诺不能替代具体计划，故整体仍待核实。未召回计划正文不表示原文件缺失。";
        }
        if (requirement.matches("(?s).*(其他独立投标|跨投标人关系|其他投标主体).*")) {
            boolean external = proofs.stream().anyMatch(p -> !declarationOnly(p.source.quote())
                    && !ClaimGrounding.indexOnly(p.source.quote())
                    && p.statement.matches("(?s).*(其他|不同|独立).{0,12}(投标人|投标主体|投标单位).*" )
                    && p.source.quote().matches("(?s).*(投标人名单|投标单位名单|开标记录|工商查询|企业登记查询|股权穿透).*"));
            if (!external) return "已提供的联合体成员申报或承诺只能核对本联合体信息，不能证明与其他独立投标人的负责人、控股或管理关系。该检查点要求的跨投标人资料尚未核实，整体待核实；共同组成一个联合体本身不构成违规。";
        }
        if (requirement.matches("(?s).*(历史服务关系|前期.{0,8}服务关系).*")) {
            boolean history = proofs.stream().anyMatch(p -> !declarationOnly(p.source.quote())
                    && !ClaimGrounding.indexOnly(p.source.quote())
                    && p.statement.matches("(?s).*(历史|前期|曾|监督|评价).*" )
                    && p.source.quote().matches("(?s).*(委托合同|咨询合同|监理合同|审计合同|服务记录|委托记录|服务单位名单).*"));
            if (!history) return "本次联合体设计分工及自述承诺不能证明实际历史咨询、监督或评价服务关系。EPC例外可以保留，但检查点要求核实的历史服务范围尚无相应记录支持，不能据此给出整体通过。";
        }
        if (requirement.contains("索引") && requirement.matches("(?s).*(实际材料|实际证明|实际内容|真实定位|对应正文).*")) {
            boolean substantive = proofs.stream().anyMatch(p -> !ClaimGrounding.indexOnly(p.source.quote())
                    && !declarationOnly(p.source.quote())
                    && p.statement.matches("(?s).*(实际|正文|定位|对应).*(核对|核实|一致|相符|内容|材料).*"));
            boolean deferred = explanation.matches("(?s).*(后续|仍需|尚需|待|建议).{0,25}(逐项|实际|正文).{0,25}(核对|核实|审查|验证).*" )
                    || explanation.matches("(?s).*(后续|仍需|尚需|待|建议).{0,25}(核对|核实|审查|验证).{0,25}(逐项|实际|正文).*" );
            if (!substantive || deferred) return "索引或目录的存在已可核对，但本检查点要求的评审因素、页码与实际材料对应关系尚未完整核实。索引不能证明对应正文真实齐备；另册或未提供的输入应保留待补核范围，不能将仅检查索引格式视为全部通过。";
        }
        return "";
    }

    static String templateIssue(String explanation, JsonNode claims, Map<String,Evidence> available, Document tender) {
        if (tender == null) return "";
        boolean adverseInference = false;
        for (String sentence : explanation.split("[。；！？\\n]")) {
            if (sentence.matches("(?s).*(不能据|不应|不得|不表示|不证明|不表明|不等于).*")) continue;
            if (sentence.matches("(?s).*待查.{0,100}(未完成自查|未自查|未提供证明|漏填|补充证明|构成未核实).*")) adverseInference = true;
        }
        if (!adverseInference) return "";
        String tenderText = SourceRelations.normalized(tender.pages().stream().map(p -> p.text()).collect(java.util.stream.Collectors.joining("\n")));
        for (Proof proof : proofs(claims, available)) {
            String quote = SourceRelations.normalized(proof.source.quote());
            int at = quote.indexOf("待查");
            while (at >= 0) {
                String context = quote.substring(Math.max(0, at - 24), at + 2);
                if (context.length() >= 12 && tenderText.contains(context))
                    return "所引投标文字中的‘待查’及相邻条款与当前招标原文相同，不能仅据该模板文字认定投标人漏填、未自查或缺证明。已撤回这一不利推断；声明与其他实际事实的核验范围仍应分别审查。";
                at = quote.indexOf("待查", at + 2);
            }
        }
        return "";
    }

    private static boolean declarationOnly(String quote) {
        String heading = quote.substring(0, Math.min(160, quote.length()));
        return heading.matches("(?s).*(承诺书|声明书|投标函|联合体协议).*" );
    }
    private static List<Proof> proofs(JsonNode claims, Map<String, Evidence> available) {
        List<Proof> result = new ArrayList<>();
        if (!claims.isArray()) return result;
        for (JsonNode claim : claims) {
            if ("TENDER_REFERENCE".equals(claim.path("scope").asText())) continue;
            for (JsonNode id : claim.path("evidenceIds")) {
                Evidence source = available.get(id.asText());
                if (id.asText().startsWith("C") && source != null)
                    result.add(new Proof(claim.path("scope").asText(), claim.path("text").asText(), source));
            }
        }
        return result;
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Proof {
        private final String scope;
        private final String statement;
        private final Evidence source;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Proof(@com.fasterxml.jackson.annotation.JsonProperty("scope") String scope, @com.fasterxml.jackson.annotation.JsonProperty("statement") String statement, @com.fasterxml.jackson.annotation.JsonProperty("source") Evidence source) { this.scope = scope; this.statement = statement; this.source = source; }
        public String scope() { return scope; }
        public String statement() { return statement; }
        public Evidence source() { return source; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Proof that = (Proof) other; return java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.statement, that.statement) && java.util.Objects.equals(this.source, that.source); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.statement); hash = 31 * hash + java.util.Objects.hashCode(this.source); return hash; }
        @Override public String toString() { return "Proof[" + "scope=" + scope + ", statement=" + statement + ", source=" + source + "]"; }
 }
}
