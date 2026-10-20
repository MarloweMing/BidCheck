package cn.bidcheck.verification;

import cn.bidcheck.service.BidService;
import cn.bidcheck.service.ReportService.Report;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import static cn.bidcheck.verification.VerificationService.*;
import static cn.bidcheck.service.BidService.text;

@Service
public final class VerificationReportService {
    private final VerificationService service;
    private final ObjectMapper json;
    public VerificationReportService(VerificationService service,ObjectMapper json) {this.service=service;this.json=json;}
    public Report generate(String id,String format) {
        if(!Set.of("html","json","csv").contains(format)) throw BidService.bad("支持HTML、JSON或CSV报告");
        Map<String,Object> detail=new LinkedHashMap<>(service.detail(id)),run=map(detail.get("run"));
        // Family input events continue after this run. The exported revision contains its frozen manifest only.
        detail.remove("inputs");
        if(!"COMPLETED".equals(run.get("status"))) throw BidService.bad("本轮核验处理结束后可导出报告");
        if(!text(run,"factIdentity").isBlank()) detail.put("factLedger",service.factsForReport(id));
        List<Map<String,Object>> results=maps(detail.get("results"));
        String preparedScope=preparedScope(run,maps(detail.get("documents")));
        if("json".equals(format)) try {return new Report(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(detail),"application/json;charset=UTF-8");}
        catch(Exception e) {throw new IllegalStateException("报告序列化失败",e);}
        if("csv".equals(format)) {
            StringBuilder csv=new StringBuilder("\uFEFF检查项,业务结论,判断类型,自动完成,核验说明,未完成原因,证据位置,计算和预评分"+(preparedScope.isBlank()?"":",实际证据版本与识别范围")+"\r\n");
            for(var r:results) csv.append(String.join(",",List.of(text(r,"title"),label(text(r,"businessDecision")),label(text(r,"decisionBasis")),Boolean.TRUE.equals(r.get("complete"))?"是":"否",text(r,"reason"),
                String.join("；",maps(r.get("gaps")).stream().map(g->text(g,"description")).toList()),String.join("；",maps(r.get("evidences")).stream().map(VerificationReportService::location).toList()),scoreSummary(r)).stream().map(VerificationReportService::csv).toList())).append(preparedScope.isBlank()?"":","+csv(preparedScope)).append("\r\n");
            return new Report(csv.toString().getBytes(StandardCharsets.UTF_8),"text/csv;charset=UTF-8");
        }
        Map<String,Object> sum=map(detail.get("summary"));
        StringBuilder h=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>BidCheck 自动核验报告</title><style>body{font:15px/1.8 system-ui,sans-serif;color:#20324a;max-width:1100px;margin:40px auto;padding:0 20px;background:#f7f9fc}article,section{background:white;border:1px solid #dce4ee;border-radius:12px;margin:18px 0;padding:24px}h1{font-size:28px}h2{font-size:20px}blockquote{white-space:pre-wrap;overflow-wrap:anywhere;border-left:3px solid #6487db;background:#f4f7fc;margin:10px 0;padding:12px}small{color:#607089}.PASS{color:#13795b}.FAIL{color:#b42318}.UNDETERMINED{color:#7858a4}summary{cursor:pointer}table{border-collapse:collapse;width:100%}td,th{text-align:left;border-bottom:1px solid #eee;padding:8px;overflow-wrap:anywhere}@media print{body{background:white}article{break-inside:avoid}}</style><h1>自动业务核验报告</h1>");
        h.append("<p>修订 ").append(html(run.get("revision"))).append(" · 处理结束：").append(html(run.get("finishedAt"))).append("<br>运行：").append(html(id)).append(" · 引擎：").append(html(run.get("ruleVersion"))).append("</p>");
        h.append("<section><strong>已处理 ").append(html(sum.get("processed"))).append(" / ").append(html(sum.get("total")))
            .append(" 项；客观业务自动完成 ").append(html(sum.get("automaticComplete"))).append(" 项；质量预评完成 ").append(html(sum.get("qualityComplete"))).append(" 项；仍未完成 ").append(html(sum.get("waiting"))).append(" 项。</strong><p>处理结束不代表所有业务条件已确定。原图观察、外部记录来源与正式评审分别记录。</p><ul>");
        for(var d:maps(run.get("manifest"))) h.append("<li>").append(html(d.get("name"))).append(" · V").append(html(d.get("version"))).append(" · ").append(html(d.get("role"))).append("<br><small>SHA-256：").append(html(d.get("sha256"))).append("</small></li>");
        h.append("</ul></section>");
        if(!preparedScope.isBlank()) h.append("<section><h2>实际证据版本与识别范围</h2><p>上方原始提交资料继续保留。以下为本轮按已确认检查计划准备的识别版本，历史结论不被新版本覆盖。未读取范围不能直接证明材料缺失或全文通过。</p><blockquote>").append(html(preparedScope)).append("</blockquote></section>");
        Map<String,Object> ledger=map(detail.get("factLedger")),factSummary=map(ledger.get("summary"));
        Map<String,Object> personnel=map(detail.get("personCredentialConsistency"));
        Map<String,Object> personCoverage=map(personnel.get("coverage"));
        if(personCoverage.isEmpty())personCoverage=map(ledger.get("personCredentialCoverage"));
        h.append(cn.bidcheck.service.ReportService.personCredentialCoverageHtml(personCoverage,maps(personnel.get("sourceManifest")),maps(detail.get("documents"))));
        if(!factSummary.isEmpty()) h.append("<section><h2>本次使用的统一事实库</h2><p>事实出现记录 ").append(html(factSummary.get("occurrences"))).append(" 条；实体 ").append(html(factSummary.get("entities"))).append(" 个；关系 ").append(html(factSummary.get("relations"))).append(" 条；跨检查项复用事实 ").append(html(factSummary.get("sharedOccurrences"))).append(" 条；待复核事实冲突 ").append(html(factSummary.get("conflicts"))).append(" 组。</p><p>原始值、来源和使用检查项保留在本次冻结快照中，完整明细可从JSON报告查看。同名不代表同一身份，事实冲突不自动等于业务不符合。</p></section>");
        if(!maps(run.get("documentIntegrity")).isEmpty()) {
            h.append("<section><h2>文件数字签名检查</h2><p>摘要及签名完整性与可信身份分别验证。未发现数字签名容器，不代表没有手写签字或实体章。</p>");
            for(Map<String,Object> file:maps(run.get("documentIntegrity"))) {
                Map<String,Object> inspection=map(file.get("inspection"));h.append("<p><strong>").append(html(file.get("documentName"))).append("</strong> · ").append(html(inspection.get("status"))).append("</p>");
                for(Map<String,Object> signature:maps(inspection.get("signatures"))) h.append("<p>签名 ").append(html(signature.get("index"))).append("：字节完整性 ").append(html(signature.get("cryptographicIntegrity"))).append("；覆盖当前版本 ").append(Boolean.TRUE.equals(signature.get("coversCurrentRevisionBytes"))?"是":"否").append("；信任链/吊销/可信时间未验证。</p>");
            }h.append("</section>");
        }
        for(var r:results) {
            h.append("<article><h2>").append(html(r.get("title"))).append("</h2><strong class=\"").append(html(r.get("businessDecision"))).append("\">")
                .append(html(label(text(r,"businessDecision")))).append("</strong> · ").append(html(label(text(r,"decisionBasis")))).append(" · ")
                .append(Boolean.TRUE.equals(r.get("complete"))?"本项核验已完成":"本项仍有未完成条件").append("<p>").append(html(r.get("reason"))).append("</p>");
            for(var o:maps(r.get("obligations"))) {
                h.append("<details><summary>").append(html(label(text(o,"status")))).append(" · ").append(html(o.get("description"))).append("</summary><p>").append(html(o.get("reason"))).append("</p>");
                proof(h,map(o.get("proof")));
                for(var e:maps(o.get("evidences"))) evidence(h,e);
                h.append("</details>");
            }
            if(!maps(r.get("gaps")).isEmpty()) {h.append("<h3>尚未完成的条件</h3><ul>");for(var g:maps(r.get("gaps"))) h.append("<li>").append(html(g.get("description"))).append("<br><small>").append(html(g.get("requiredInput"))).append("</small></li>");h.append("</ul>");}
            h.append("<details><summary>原文证据</summary>");for(var e:maps(r.get("evidences"))) evidence(h,e);h.append("</details></article>");
        }
        h.append("</html>");return new Report(h.toString().getBytes(StandardCharsets.UTF_8),"text/html;charset=UTF-8");
    }
    static String preparedScope(Map<String,Object> run,List<Map<String,Object>> documents) {
        List<String> lines=new ArrayList<>();
        Set<String> evidenceIds=new HashSet<>(strings(run.get("evidenceDocumentIds")));
        for(Map<String,Object> prepared:maps(run.get("preparedManifest"))) {
            String id=text(prepared,"documentId");if(id.isBlank()||!evidenceIds.contains(id)) continue;
            lines.add(text(prepared,"name")+" · V"+Objects.toString(prepared.get("version"),"?")+" · 文件 "+id+" · SHA-256："+text(prepared,"sha256"));
            Map<String,Object> document=documents.stream().filter(item->id.equals(item.get("id"))&&Objects.equals(prepared.get("sha256"),item.get("sha256"))&&Objects.toString(prepared.get("version"),"").equals(Objects.toString(item.get("version"),""))).findFirst().orElse(Map.of());
            Map<String,Object> coverage=map(document.get("ocrCoverage"));
            lines.add(text(coverage,"notice").isBlank()?"该冻结版本未返回识别范围说明，不能据此声称全文识别完成。":text(coverage,"notice"));
            if(coverage.containsKey("deferredLocations")) {
                for(Map<String,Object> location:maps(coverage.get("deferredLocations"))) if(id.equals(location.get("documentId"))) lines.add("待按需补识别 · "+text(location,"label")+"："+text(location,"warning"));
                for(Map<String,Object> location:maps(coverage.get("failedLocations"))) if(id.equals(location.get("documentId"))) lines.add("识别未完成 · "+text(location,"label")+"："+text(location,"warning"));
            } else for(Map<String,Object> location:maps(coverage.get("incompleteLocations"))) if(id.equals(location.get("documentId"))) lines.add(text(location,"label")+"："+text(location,"warning"));
        }
        return String.join("\n",lines);
    }
    private static void proof(StringBuilder h,Map<String,Object> proof) {
        Map<String,String> labels=new LinkedHashMap<>();labels.put("requirementQuote","规则依据");labels.put("actual","实际值");labels.put("expected","要求值");labels.put("operator","比较方式");
        labels.put("referenceDate","参考日期");labels.put("longestConsecutive","连续月份数");labels.put("requiredMonths","要求月份数");labels.put("months","已核月份");
        labels.put("score","SEMANTIC_PREASSESSMENT".equals(proof.get("decisionBasis"))?"预评建议分（非正式评分）":"规则计算得分");labels.put("scoreLower","已证得分下限");labels.put("scoreUpper","可能得分上限");labels.put("band","预评档次");labels.put("rubric","评分量表");labels.put("deduplicatedContracts","去重合同数");
        labels.put("targetBidder","本次计分投标人");labels.put("targetPriceB","评标价 B（元）");labels.put("benchmarkA","基准价 A（元，展示值）");labels.put("validBidderCount","有效投标人数");labels.put("usedBidderCount","参与均值计算数");
        h.append("<table>");labels.forEach((key,label)->{if(proof.containsKey(key))h.append("<tr><th>").append(html(label)).append("</th><td>").append(html(proof.get(key))).append("</td></tr>");});h.append("</table>");
        if(Boolean.FALSE.equals(proof.get("officialReviewScore"))) h.append("<p>规则计算参考分，非正式评审分。计算保留精确比值，仅最终得分四舍五入到两位小数。</p>");
        for(String key:List.of("includedRecords","excludedRecords")) if(!maps(proof.get(key)).isEmpty()) {
            h.append("<h4>").append(key.equals("includedRecords")?"参与均值计算的报价":"按条款剔除的最高、最低报价").append("</h4><ul>");
            for(Map<String,Object> row:maps(proof.get(key))) {h.append("<li>").append(html(row.get("bidder"))).append("：").append(html(row.get("price"))).append(" 元");evidence(h,map(row.get("evidence")));h.append("</li>");}h.append("</ul>");
        }
        if(proof.containsKey("benchmarkAExact")) {Map<String,Object> exact=map(proof.get("benchmarkAExact"));h.append("<p>精确基准价 A = ").append(html(exact.get("numerator"))).append(" ÷ ").append(html(exact.get("denominator"))).append(" 元。</p>");}
        Map<String,Object> formula=map(proof.get("formula"));if(!formula.isEmpty()) h.append("<p>均值乘数：").append(html(formula.get("meanMultiplier"))).append("；基准分：").append(html(formula.get("maximumScore"))).append("；每高于 1% 扣 ").append(html(formula.get("aboveCoefficient"))).append(" 分，每低于 1% 扣 ").append(html(formula.get("belowCoefficient"))).append(" 分；最低 ").append(html(formula.get("minimumScore"))).append(" 分。</p>");
    }
    private static String scoreSummary(Map<String,Object> result) {
        List<String> parts=new ArrayList<>();for(Map<String,Object> obligation:maps(result.get("obligations"))) {Map<String,Object> proof=map(obligation.get("proof"));
            String suffix="SEMANTIC_PREASSESSMENT".equals(proof.get("decisionBasis"))||Boolean.FALSE.equals(proof.get("officialReviewScore"))?"（参考计算或预评，非正式评分）":"";
            for(String key:List.of("score","scoreLower","scoreUpper","band")) if(proof.containsKey(key)) parts.add(text(obligation,"description")+"："+key+"="+proof.get(key)+suffix);}
        return String.join("；",parts);
    }
    private static void evidence(StringBuilder h,Map<String,Object> e) {h.append("<small>").append(html(location(e))).append("</small><blockquote>").append(html(e.get("quote"))).append("</blockquote>");}
    private static String location(Map<String,Object> e) {return text(e,"documentName")+" · "+("PAGE".equals(e.get("locationType"))?"第 ":"位置 ")+e.getOrDefault("page",e.getOrDefault("location",""))+" · "+text(e,"anchor");}
    static String html(Object value) {return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static String csv(String value) {String v=value==null?"":value;if(v.matches("(?s)^[\\s]*[=+@-].*"))v="'"+v;return "\""+v.replace("\"","\"\"")+"\"";}
    static String label(String v) {return switch(v){case "PASS","SATISFIED","PROVED"->"符合";case "FAIL","VIOLATED","DISPROVED"->"存在明确偏差";case "WARNING"->"有风险";case "UNDETERMINED","UNKNOWN"->"待核验";case "NOT_APPLICABLE"->"不适用";case "RULE_COMPUTED","RULE_CALCULATION","SOURCE_GROUNDED_RULES"->"有来源的规则计算";case "SEMANTIC_PREASSESSMENT"->"方案质量预评";case "EXTERNAL_RECORD","EXTERNAL_VERIFICATION"->"外部记录核验";case "ENGINE_FAILURE","ERROR"->"处理故障";case "BUDGET_EXHAUSTED"->"本轮调用额度已用尽";case "WAIT_STAGE"->"等待适用核验阶段";case "WAIT_INPUT"->"等待所需资料";case "NEEDS_REVIEW"->"规则或证明仍需核对";case "COMPLETED"->"本轮处理完成";default->v;};}
}
