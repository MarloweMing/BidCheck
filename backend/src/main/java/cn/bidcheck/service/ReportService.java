package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.consistency.PersonCredentialConsistency;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    public record Report(byte[] bytes,String contentType) {}
    static String dimensionLabel(Finding finding,String dimension) {
        return BidService.text(map(BusinessResultPresentation.dimensions(finding.evaluation(),finding.assessment(),map(map(finding.assessment()).get("resolutionJob"))).get(dimension)),"label");
    }
    static String inheritedReviewText(List<Map<String,Object>> reviews,String priorFindingId) {
        if(priorFindingId!=null&&priorFindingId.isBlank())return "";
        return String.join("\n",reviews.stream().filter(r->priorFindingId==null||priorFindingId.equals(BidService.text(r,"resultId"))).map(r->
            "前版复核（未自动套用）："+label(BidService.text(r,"status"))+" · "+BidService.text(r,"reviewer")+" · "+BidService.text(r,"note")+" · 来源批次 "+BidService.text(r,"runId")).toList());
    }
    static String resolutionDimensionsHtml(Finding finding) {
        Map<String,Object> job=map(map(finding.assessment()).get("resolutionJob"));
        String reason=BidService.text(job,"stopReason").isBlank()?BidService.text(job,"reason"):BidService.text(job,"stopReason");
        return "<div class=resolution-dimensions><p>执行："+html(dimensionLabel(finding,"execution"))+" · 证据："+html(dimensionLabel(finding,"evidence"))+" · 自动结论："+html(dimensionLabel(finding,"conclusion"))+"</p>"+
            (reason.isBlank()?"":"<p>补查说明："+html(reason)+"</p>")+"</div>";
    }
    static String resolutionSummaryHtml(Map<String,Object> summary,Map<String,Object> run,List<Finding> results) {
        long unknown=results.stream().filter(f->!Boolean.TRUE.equals(map(BusinessResultPresentation.dimensions(f.evaluation(),f.assessment(),Map.of()).get("conclusion")).get("determined"))).count();
        return "<section class=ocr-context><h2>本轮执行结束</h2><p>共 "+results.size()+" 条原记录，仍有 "+unknown+" 条尚未形成确定结论；疑似风险不计为已证实不满足。</p>"+
            (BidService.text(run,"parentRunId").isBlank()?"":"<p>补查来源批次："+html(BidService.text(run,"parentRunId"))+"；历史报告与人工记录保留。</p>")+
            (BidService.text(summary,"blockReason").isBlank()?"":"<p>补查状态："+html(BidService.text(summary,"blockReason"))+"</p>")+"<p>流程结束不代表全部要求通过；系统未完成与证据尚未核全不自动转为人工任务。</p></section>";
    }
    private final BidService service;
    public ReportService(BidService service) { this.service=service; }
    public Report generate(String id,String format) { return generate(id,format,"source"); }
    public Report generate(String id,String format,String resultVersionSelector) {
        if(!Set.of("html","csv","json").contains(format)) throw BidService.bad("仅支持 HTML、CSV 或 JSON 报告");
        Map<String,Object> payload=service.run(id,false);
        @SuppressWarnings("unchecked") Map<String,Object> run=(Map<String,Object>)payload.get("run");
        if(!"COMPLETED".equals(run.get("status"))) throw BidService.bad("检查完成后才能导出完整报告");
        @SuppressWarnings("unchecked") List<Finding> sourceResults=(List<Finding>)payload.get("results");
        String requestedVersion=resultVersionSelector==null?"source":resultVersionSelector.trim();
        Map<String,Object> publishedVersion=service.publishedTemplateResultVersion(id,requestedVersion);
        if(!"source".equalsIgnoreCase(requestedVersion)&&publishedVersion.isEmpty())throw BidService.bad("当前检查尚未发布增量结果版本");
        List<Finding> results=publishedVersion.isEmpty()?sourceResults:overlayPublishedResults(sourceResults,publishedVersion);
        // Project current display semantics over old reports without modifying saved decisions or proofs.
        results=results.stream().map(f->new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),FocusedCheckEvaluator.reconcileAssessment(f.evaluation(),f.assessment()))).toList();
        @SuppressWarnings("unchecked") List<Map<String,Object>> reviews=(List<Map<String,Object>>)payload.get("reviews");
        Map<String,Map<String,Object>> latest=new LinkedHashMap<>(); reviews.forEach(r->latest.put(BidService.text(r,"resultId"),r));
        Map<String,Object> project=service.store.getMap("project",BidService.text(run,"projectId"));
        // A later correction must not rename or change the reference time of a historical report.
        project.putAll(map(run.get("projectInformation")));
        Document document=service.document(BidService.text(run,"documentId"));
        Map<String,Object> ocrContext=map(payload.get("ocrContext"));
        Map<String,Object> consistency=map(payload.get("consistency"));
        Map<String,Map<String,Object>> consistencyGroups=new HashMap<>();
        for(Map<String,Object> group:maps(consistency.get("groups")))consistencyGroups.put(BidService.text(group,"findingId"),group);
        Map<String,Map<String,Object>> consistencyOccurrences=new HashMap<>();
        for(Map<String,Object> occurrence:maps(consistency.get("occurrences")))consistencyOccurrences.put(BidService.text(occurrence,"id"),occurrence);
        Map<String,String> consistencyCategories=new LinkedHashMap<>();
        for(Map<String,Object> category:maps(map(consistency.get("coverage")).get("categories")))
            consistencyCategories.put(BidService.text(category,"id"),BidService.text(category,"title"));
        Map<String,Object> onDemand=map(map(payload.get("summary")).get("onDemandModel"));
        boolean includesOcr=results.stream().anyMatch(f->safe(f.evaluation().method()).contains("OCR_REVIEW")||Boolean.TRUE.equals(map(map(f.assessment()).get("ocrReview")).get("required")));
        String scope="文本规则检查"+(includesOcr?"（包含OCR识别文字，请对照原图核实）":"")+(BidService.text(run,"model").isBlank()?"":"及模型辅助语义检查（"+BidService.text(run,"model")+"）")+"；盖章按要求位置是否已有盖章核验，待确认不代表通过。";
        String projectId=BidService.text(run,"projectId"), runId=BidService.text(run,"id");
        if(!publishedVersion.isEmpty())scope+=" 本报告采用已审核发布的增量结果版本 V"+publishedVersion.get("revision")+"；原始检查报告仍独立保留。";
        if("json".equals(format)) {Map<String,Object> exported=new LinkedHashMap<>(BidService.fields("projectId",projectId,"runId",runId,"project",project,"run",run,"document",document,"results",results,"reviews",reviews,"scope",scope,"ocrContext",ocrContext,"consistency",consistency,"summary",payload.getOrDefault("summary",Map.of()),"businessGroups",payload.getOrDefault("businessGroups",List.of()),"resolutionSummary",payload.getOrDefault("resolutionSummary",Map.of()),"inheritedReviews",payload.getOrDefault("inheritedReviews",List.of())));
            if(!publishedVersion.isEmpty()){exported.put("resultVersion",publishedVersion);exported.put("resultVersionSelection","PUBLISHED");exported.put("historicalSourceResultsUnchanged",true);}return report(service.store.encode(exported),"application/json;charset=UTF-8");}
        @SuppressWarnings("unchecked") List<Map<String,Object>> documents=(List<Map<String,Object>>)payload.get("documents");
        String tenderId=BidService.text(run,"tenderDocumentId");
        Set<String> tenderIds=new HashSet<>();
        for(Map<String,Object> item:documents) if(projectId.equals(item.get("projectId"))&&"TENDER".equals(item.get("role"))) tenderIds.add(BidService.text(item,"id"));
        String bidId=projectId.equals(document.projectId())&&"BID".equals(document.role())?document.id():"";
        if("csv".equals(format)) {
            String versionColumns=publishedVersion.isEmpty()?"":"结果版本,版本发布人,版本发布说明,";
            StringBuilder b=new StringBuilder("\uFEFF项目ID,检查批次ID,"+versionColumns+"检查项,分类,风险,自动结论,人工结论,说明,修改建议,招标依据,投标证据,复核人,复核意见,解析范围,原始自动说明,原图复核提示,原始判断,原图策略前判断,调整原因,检索方式,核验状态,全文一致性扫描范围,差异组标识,一致性类别,检查关系,比较前提,结构字段范围（冻结记录）,定向补证复核,事实归属与表格关联（冻结记录）,条件核验状态,条件核验记录与待补信息,业务处理分类,责任方,是否需要用户动作,下一步,清单展开与逐项证据,本项执行状态,证据状态,确定业务结论,补查来源批次,补查停止原因,前版复核记录（未自动套用）,原始模型建议（未作为用户待办）\r\n");
            for(Finding f:results) { Map<String,Object> r=latest.getOrDefault(f.id(),Map.of()); Evaluation e=f.evaluation(); Checkpoint c=f.checkpoint();
                Map<String,Object> presentation=findingPresentation(ocrContext,f);
                Evidence source=tenderSource(c.source(),tenderId,tenderIds);
                List<Evidence> tender=new ArrayList<>();if(source!=null) tender.add(source);tender.addAll(additionalTender(e,source,tenderId,tenderIds));
                Map<String,Object> assessment=map(f.assessment());
                Map<String,Object> group=consistencyGroups.getOrDefault(f.id(),Map.of());
                List<String> row=List.of(c.title(),safe(c.category()),safe(c.severity()),label(e.status()),label(BidService.text(r,"status")),presented(presentation,"reason",e.reason()),atomicText(BusinessResultPresentation.describe(e,assessment),"nextStep"),joinedEvidence(tender),joinedEvidence(bidEvidence(e,bidId)),BidService.text(r,"reviewer"),BidService.text(r,"note"),parsingScope(ocrContext,presentation),originalExplanation(presentation,e),originalReviewText(f,presentation),evaluationText(assessment.get("rawEvaluation")),evaluationText(assessment.get("preOcrEvaluation")),adjustmentText(assessment),assessment.isEmpty()?"":retrievalText(map(assessment.get("retrieval"))),assessmentState(f),consistencyScope(consistency)+(Boolean.TRUE.equals(onDemand.get("attemptsUnlimited"))?"；模型调用次数不限":""),BidService.text(assessment,"consistencyGroupId"),consistencyCategories.getOrDefault(BidService.text(group,"categoryId"),""),relationLabel(BidService.text(group,"relationType")),comparisonContext(map(group.get("comparisonContext"))),service.store.encode(structuredCoverageSnapshots(ocrContext)),group.isEmpty()?"":targetedReviewText(map(group.get("targetedReview"))),group.isEmpty()?"":service.store.encode(groupOccurrences(group,consistencyOccurrences).stream().map(o->BidService.fields("occurrenceId",o.get("id"),"contextAssignment",o.get("contextAssignment"),"tableAssociation",o.get("tableAssociation"))).toList()),atomicWorkflowLabel(map(assessment.get("atomicCheck"))),atomicCheckText(map(assessment.get("atomicCheck"))),atomicText(BusinessResultPresentation.describe(e,assessment),"label"),atomicText(BusinessResultPresentation.describe(e,assessment),"owner"),Boolean.TRUE.equals(BusinessResultPresentation.describe(e,assessment).get("userActionRequired"))?"是":"否",atomicText(BusinessResultPresentation.describe(e,assessment),"nextStep"),completenessText(map(map(assessment.get("coverage")).get("completeness"))),dimensionLabel(f,"execution"),dimensionLabel(f,"evidence"),dimensionLabel(f,"conclusion"),BidService.text(run,"parentRunId"),BidService.text(map(assessment.get("resolutionJob")),"stopReason"),inheritedReviewText(maps(payload.get("inheritedReviews")),BidService.text(map(assessment.get("inheritedFrom")),"findingId")),safe(e.suggestion()));
                List<String> traceable=new ArrayList<>(row.size()+5);traceable.add(projectId);traceable.add(runId);
                if(!publishedVersion.isEmpty()){traceable.add("V"+publishedVersion.get("revision"));traceable.add(BidService.text(publishedVersion,"publishedBy"));traceable.add(BidService.text(publishedVersion,"publicationNote"));}
                traceable.addAll(row);row=traceable;
                b.append(String.join(",",row.stream().map(ReportService::csv).toList())).append("\r\n");
            }
            return report(b.toString(),"text/csv;charset=UTF-8");
        }
        StringBuilder b=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>BidCheck 标书检查报告</title><style>body{font:16px/1.7 system-ui,sans-serif;color:#172b4d;max-width:1080px;margin:44px auto;padding:0 24px}h1{font-size:30px}h2{font-size:20px;margin-top:0}.meta{color:#607089}article{border:1px solid #dce3ed;border-radius:10px;padding:22px;margin:24px 0;break-inside:avoid}blockquote{white-space:pre-wrap;margin:12px 0;padding:14px;background:#f4f7fb;border-left:3px solid #316bff}.FAIL{color:#bc2f35}.WARNING{color:#b76512}.PASS{color:#14745b}.UNDETERMINED{color:#7b678e}small{color:#607089}.ocr-context{border:1px solid #dce3ed;padding:18px;border-radius:8px}.ocr-context p,.ocr-context li,.original-explanation blockquote{overflow-wrap:anywhere}.ocr-context ul,.original-explanation blockquote{max-height:280px;overflow:auto}summary{cursor:pointer}@media print{body{margin:0;max-width:none}article{border-color:#bbb}}</style><h1>标书检查报告</h1>");
        b.append("<p>").append(html(BidService.text(project,"name"))).append("</p><p class=meta>项目ID：").append(html(projectId)).append("<br>检查批次ID：").append(html(runId)).append("<br>文件：").append(html(document.name())).append(" · 版本 ").append(document.version()).append("<br>生成时间：").append(html(BidService.now())).append("<br>规则版本：").append(html(BidService.text(run,"ruleVersion"))).append("<br>文件 SHA-256：").append(html(document.sha256()));
        if(!publishedVersion.isEmpty())b.append("<br>结果版本：V").append(html(Objects.toString(publishedVersion.get("revision"),""))).append(" · 发布人：").append(html(BidService.text(publishedVersion,"publishedBy"))).append(" · 发布说明：").append(html(BidService.text(publishedVersion,"publicationNote")));
        b.append("</p>");
        b.append("<p>").append(html(scope)).append("人工复核记录单独保留，不覆盖自动结论。</p>");
        b.append(resolutionSummaryHtml(map(payload.get("resolutionSummary")),run,results));
        if(!maps(payload.get("inheritedReviews")).isEmpty())b.append("<details><summary>前版复核记录（未自动套用）</summary><p>原批次人工记录仅供对照，当前批次结论和待办独立保留。</p><blockquote>").append(html(inheritedReviewText(maps(payload.get("inheritedReviews")),null))).append("</blockquote></details>");
        appendOcrContext(b,ocrContext);
        if("RULES_RETRIEVAL".equals(run.get("checkMode"))) {
            b.append("<section class=ocr-context><h2>规则与按需核验范围</h2><p>本地规则遍历可读全文；混合检索补充相关原文。仅复杂疑点使用模型，检索候选和模型次数均不代表全文语义覆盖。</p><p>模型 HTTP 尝试：")
                .append(html(Objects.toString(onDemand.get("httpAttempts"),"未知"))).append("；有效输出：")
                .append(html(Objects.toString(onDemand.get("validOutputs"),"未知"))).append("；经保护调整：")
                .append(html(Objects.toString(onDemand.get("guardedOutputs"),"未知")));
            if(Boolean.TRUE.equals(onDemand.get("attemptsUnlimited")))b.append("；模型调用次数不限");
            if(onDemand.get("budgetDeferredCheckpoints") instanceof Number deferred&&deferred.longValue()>0)
                b.append("；本次记录的旧调用次数上限导致暂缓：").append(deferred.longValue()).append(" 项");
            b.append("。未确定项请继续核对原文。</p>");
            if(onDemand.get("atomicCheckpoints") instanceof Number atomicCount&&atomicCount.longValue()>0)
                b.append("<details><summary>条件核验处理记录</summary><p>条件核验模型尝试 ").append(reviewCount(onDemand,"structuredModelAttempts"))
                    .append(" 次；处理完成 ").append(reviewCount(onDemand,"structuredModelCompleted")).append(" 次；失败 ").append(reviewCount(onDemand,"structuredModelFailed"))
                    .append(" 次；状态未知 ").append(reviewCount(onDemand,"structuredModelUnknown")).append(" 次。</p><p>此项按条件核验的处理记录单独统计，不代表实际发送次数或业务通过数。</p></details>");
            b.append("</section>");
            if(onDemand.containsKey("consistencyModelRequestedGroups"))b.append("<p>模型请求：招标检查 ").append(reviewCount(onDemand,"modelRequestedCheckpoints"))
                .append(" 项＋一致性复核 ").append(reviewCount(onDemand,"consistencyModelRequestedGroups")).append(" 组，合计实发 ").append(reviewCount(onDemand,"httpAttempts"))
                .append(" 次；发送情况未知 ").append(reviewCount(onDemand,"consistencyUnknownRequestGroups")).append(" 组。</p>");
        }
        Map<String,Object> personCoverage=map(map(consistency.get("coverage")).get("personCredentials"));
        if(personCoverage.isEmpty()&&map(consistency.get("coverage")).containsKey("personCredentialVersion")) personCoverage=map(consistency.get("coverage"));
        b.append(personCredentialCoverageHtml(personCoverage,maps(consistency.get("sourceManifest")),List.of(BidService.fields(
            "id",document.id(),"name",document.name(),"version",document.version(),"sha256",document.sha256()))));
        b.append(contextTableCoverageHtml(map(consistency.get("coverage"))));
        b.append(consortiumAssignmentsHtml(maps(consistency.get("occurrences"))));
        Map<String,Object> doubtReview=map(consistency.get("doubtReview"));
        if(!doubtReview.isEmpty())b.append("<section class=ocr-context data-doubt-review><h2>疑点定向复核范围</h2><p>本轮处理 ")
            .append(reviewCount(doubtReview,"reviewedGroups")).append(" / ").append(reviewCount(doubtReview,"totalGroups"))
            .append(" 组；暂缓 ").append(reviewCount(doubtReview,"deferredGroups")).append(" 组；检索补证 ").append(reviewCount(doubtReview,"retrievalGroups"))
            .append(" 组；请求模型 ").append(reviewCount(doubtReview,"modelRequestedGroups")).append(" 组；模型尝试 ").append(reviewCount(doubtReview,"modelAttempts"))
            .append(" 次；发送情况未知 ").append(reviewCount(doubtReview,"unknownRequestGroups")).append(" 组。</p><p>").append(html(BidService.text(doubtReview,"boundary"))).append(" 已处理不代表疑点已确认。</p></section>");
        if(!consistency.isEmpty()) {
            Map<String,Object> coverage=map(consistency.get("coverage")),modelCoverage=map(coverage.get("model"));
            b.append("<section class=ocr-context><h2>全文一致性扫描范围</h2><p>原文位置：")
                .append(html(Objects.toString(coverage.get("pagesTotal"),"—"))).append("；已扫描：")
                .append(html(Objects.toString(coverage.get("pagesScanned"),"—"))).append("；未完整解析：")
                .append(html(Objects.toString(coverage.get("pagesUnparsed"),"—"))).append("。</p><p>模型辅助扫描：")
                .append("RULES_RETRIEVAL".equals(coverage.get("checkMode"))?"本次未请求全文生成式抽取，复杂疑点按需核验":Boolean.TRUE.equals(modelCoverage.get("enabled"))?(Boolean.TRUE.equals(modelCoverage.get("complete"))?"已完成可读文本扫描":"存在未完成位置"):"未启用")
                .append("。扫描覆盖不等于全部信息已核实；每组差异及复核记录见下方。</p>");
            if(consistency.get("limitations") instanceof List<?> limitations)
                for(Object limitation:limitations)b.append("<p>").append(html(Objects.toString(limitation,""))).append("</p>");
            List<Map<String,Object>> categories=maps(coverage.get("categories"));
            if(!categories.isEmpty()) {
                b.append("<details open><summary>检查目录与实际识别范围（").append(categories.size()).append(" 类）</summary><ul>");
                for(Map<String,Object> category:categories) {
                    b.append("<li><strong>").append(html(BidService.text(category,"title"))).append("</strong>：")
                        .append(html(categoryStatus(BidService.text(category,"status")))).append("；事实 ")
                        .append(html(Objects.toString(category.get("occurrences"),"0"))).append(" 条，待核对 ")
                        .append(html(Objects.toString(category.get("groups"),"0"))).append(" 组");
                    if(category.get("limitations") instanceof List<?> bounds)
                        for(Object bound:bounds)b.append("；").append(html(Objects.toString(bound,"")));
                    b.append("</li>");
                }
                b.append("</ul><p>未提取到事实不表示该类核验通过；标签规则和模型候选不能保证识别任意表述。</p></details>");
            }
            b.append("</section>");
        }
        if(!document.warnings().isEmpty()) {
            long recognized=document.pages().stream().filter(p->p.parser()!=null&&p.parser().contains("OCR")).count();
            List<String> attention=document.warnings().stream().filter(w->!deferredOnly(w)&&(!w.contains("[OCR已识别]")||w.contains("[OCR未完成]"))).toList();
            b.append("<p>投标文件共 ").append(document.pages().size()).append(" 个原文位置，其中 ").append(recognized)
                .append(" 个位置包含 OCR 识别文字；另有 ").append(attention.size()).append(" 条解析提示需要核对。</p>");
            if(!attention.isEmpty()) b.append("<details open><summary>需要核对的解析提示</summary><blockquote>")
                .append(html(String.join("\n",attention))).append("</blockquote></details>");
            b.append("<details><summary>查看全部 ").append(document.warnings().size()).append(" 条逐页识别记录</summary><blockquote>")
                .append(html(String.join("\n",document.warnings()))).append("</blockquote></details>");
        }
        List<Map<String,Object>> mergedGroups=maps(payload.get("businessGroups")).stream().filter(g->Boolean.TRUE.equals(g.get("merged"))).toList();
        if(!mergedGroups.isEmpty()){
            b.append("<section class=ocr-context><h2>同一业务事项的来源索引</h2><p>相同义务合并展示；下方逐条保留原结论与人工复核记录。</p>");
            for(Map<String,Object> g:mergedGroups){b.append("<p><strong>").append(html(atomicText(g,"title"))).append("</strong> · ").append(html(Objects.toString(g.get("sourceCount"),"0"))).append(" 处招标来源：");
                if(g.get("resultIds") instanceof List<?> ids)for(Object resultId:ids)b.append(" <a href=\"#finding-").append(html(Objects.toString(resultId,""))).append("\">原项记录</a>");
                b.append("</p><p>").append(html(atomicText(g,"explanation"))).append("</p>");
                for(Map<String,Object> occurrence:maps(g.get("occurrences"))){
                    b.append("<p>原项自动结论：").append(html(label(atomicText(occurrence,"decisionStatus")))).append("</p>");
                    for(String line:completenessSourceLines(Collections.singletonList(occurrence.get("source"))))b.append("<blockquote>").append(html(line)).append("</blockquote>");
                }}
            b.append("</section>");
        }
        for(Finding f:results) {
            Evaluation e=f.evaluation(); Checkpoint c=f.checkpoint();
            Map<String,Object> presentation=findingPresentation(ocrContext,f);
            Map<String,Object> business=BusinessResultPresentation.describe(e,f.assessment());
            b.append("<article id=\"finding-").append(html(f.id())).append("\"><h2>").append(html(c.title())).append("</h2><strong class=\"").append(html(e.status())).append("\">业务结论（自动）：").append(html(atomicText(business,"label"))).append("</strong><p class=analysis-reason>").append(html(presented(presentation,"reason",e.reason()))).append("</p><p>下一步：").append(html(atomicText(business,"nextStep"))).append("</p>");
            b.append(resolutionDimensionsHtml(f));
            b.append("<p>条款风险等级：").append(switch(safe(c.severity())){case "HIGH"->"高";case "MEDIUM"->"中";case "LOW"->"低";default->"未设置";}).append("</p>");
            Map<String,Object> consistencyGroup=consistencyGroups.getOrDefault(f.id(),Map.of());
            if(!consistencyGroup.isEmpty()) {
                b.append("<p>一致性类别：").append(html(consistencyCategories.getOrDefault(BidService.text(consistencyGroup,"categoryId"),"全文信息")))
                    .append("；检查关系：").append(html(relationLabel(BidService.text(consistencyGroup,"relationType")))).append("</p>");
                String comparison=comparisonContext(map(consistencyGroup.get("comparisonContext")));
                if(!comparison.isBlank())b.append("<p>比较前提：").append(html(comparison)).append("</p>");
                if("EVIDENCE_INSUFFICIENT".equals(BidService.text(consistencyGroup,"status")))
                    b.append("<p>比较证据不足：不能据此认定事实冲突或材料不存在。</p>");
                b.append(consistencyEnhancementHtml(consistencyGroup,groupOccurrences(consistencyGroup,consistencyOccurrences)));
            }
            String state=assessmentState(f);if(!state.isBlank()) b.append("<p class=assessment-state>核验状态：").append(html(state)).append("</p>");
            b.append(originalReviewHtml(f,presentation));
            b.append(completenessHtml(map(map(map(f.assessment()).get("coverage")).get("completeness"))));
            b.append(atomicCheckHtml(map(map(f.assessment()).get("atomicCheck"))));
            if(Boolean.TRUE.equals(presentation.get("legacyClarified"))) b.append("<p>解析提示已按范围澄清，历史结论未修改</p><details class=original-explanation><summary>查看历史原始说明</summary><blockquote>").append(html(originalExplanation(presentation,e))).append("</blockquote></details>");
            appendAssessment(b,f);
            Evidence source=tenderSource(c.source(),tenderId,tenderIds);
            b.append("<h3>招标依据</h3><blockquote>").append(html(source==null?(c.source()==null?"系统内置检查（非招标条款）":"未取得本次招标文件的有效依据"):evidence(source))).append("</blockquote>");
            List<Evidence> supplemental=additionalTender(e,source,tenderId,tenderIds);
            if(!supplemental.isEmpty()) {
                b.append("<h3>补充招标依据</h3>");
                for(Evidence ev:supplemental) b.append("<blockquote>").append(html(evidence(ev))).append("</blockquote>");
            }
            b.append("<h3>投标证据</h3>");
            List<Evidence> bidEvidence=bidEvidence(e,bidId);
            if(bidEvidence.isEmpty()) b.append("<p>未取得充分的投标证据</p>");
            for(Evidence ev:bidEvidence) b.append("<blockquote>").append(html(evidence(ev))).append("</blockquote>");
            for(Map<String,Object> r:reviews) if(f.id().equals(r.get("resultId"))) b.append("<p>人工复核：").append(html(label(BidService.text(r,"status")))).append(" · ").append(html(BidService.text(r,"reviewer"))).append(" · ").append(html(BidService.text(r,"createdAt"))).append("<br>").append(html(BidService.text(r,"note"))).append("</p>");
            b.append("<small>方法：").append(html(safe(e.method()).startsWith("FOCUSED_ATOMIC_RULES")?"逐条件核验":e.method())).append(" · 检查项：").append(html(c.id())).append("</small></article>");
        }
        return report(b.append("</html>").toString(),"text/html;charset=UTF-8");
    }
    /** Applies the immutable template-only overlay while retaining non-template source findings. */
    private List<Finding> overlayPublishedResults(List<Finding> sourceResults,Map<String,Object> version) {
        Map<String,Finding> byCheckpoint=new LinkedHashMap<>();
        for(Finding finding:sourceResults)byCheckpoint.put(finding.checkpoint().id(),finding);
        for(Map<String,Object> entry:maps(version.get("results"))){String checkpointId=BidService.text(entry,"checkpointId");Object raw=entry.get("finding");
            if(checkpointId.isBlank()||raw==null||!byCheckpoint.containsKey(checkpointId))throw new IllegalStateException("RESULT_VERSION_FINDING_SCOPE_MISMATCH");
            Finding finding=service.store.decode(service.store.encode(raw),Finding.class);
            if(finding.checkpoint()==null||!checkpointId.equals(finding.checkpoint().id()))throw new IllegalStateException("RESULT_VERSION_FINDING_SCOPE_MISMATCH");
            byCheckpoint.put(checkpointId,finding);
        }
        List<Finding> merged=new ArrayList<>(sourceResults.size());for(Finding source:sourceResults)merged.add(byCheckpoint.get(source.checkpoint().id()));return List.copyOf(merged);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of(); }
    private static List<Map<String,Object>> groupOccurrences(Map<String,Object> group,Map<String,Map<String,Object>> index) {
        if(!(group.get("occurrenceIds") instanceof List<?> ids))return List.of();
        return ids.stream().map(id->index.get(Objects.toString(id,""))).filter(Objects::nonNull).toList();
    }
    private static List<Map<String,Object>> completenessItems(Map<String,Object> value) {
        if(value.get("itemResults") instanceof List<?>)return maps(value.get("itemResults"));
        Set<String> verified=new HashSet<>();for(Map<String,Object> row:maps(value.get("verified")))verified.add(atomicText(row,"item"));
        if(!(value.get("items") instanceof List<?> items))return List.of();
        return items.stream().filter(String.class::isInstance).map(String.class::cast).map(item->Map.<String,Object>of("item",item,"status",!Boolean.TRUE.equals(value.get("inventoryResolved"))?"REQUIREMENT_UNRESOLVED":verified.contains(item)?"RECORDED":"EVIDENCE_PENDING","reason",verified.contains(item)?"历史记录已有依据编号，未提供逐项定位。":"尚未取得逐项正文依据；不据此认定材料缺失。")).toList();
    }
    private static String completenessStatus(Map<String,Object> item,boolean bodyOnly){return switch(atomicText(item,"status")){case "VERIFIED"->bodyOnly?"已定位正文":"已证实";case "EVIDENCE_PENDING"->"待补证据";case "RECORDED"->"已记录依据";default->"要求未展开";};}
    private static List<String> completenessSourceLines(Object raw) {
        if(!(raw instanceof List<?> sources))return List.of();List<String> out=new ArrayList<>();
        for(Object source:sources){String name,quote,anchor,kind;int page;
            if(source instanceof Evidence e){name=safe(e.documentName());quote=safe(e.quote());anchor=safe(e.anchor());kind=safe(e.locationType());page=e.page();}
            else{Map<String,Object> e=map(source);if(e.isEmpty())continue;name=atomicText(e,"documentName");quote=atomicText(e,"quote");anchor=atomicText(e,"anchor");kind=atomicText(e,"locationType");page=e.get("page") instanceof Number n?n.intValue():0;}
            String location=page<=0?"位置未记录":"第 "+page+switch(kind){case "PAGE"->" 页";case "PARAGRAPH"->" 段";case "BLOCK"->" 内容块";case "IMAGE"->" 处图片";default->" 处原文位置";};
            out.add((name.isBlank()?"来源文件":name)+" · "+location+(anchor.isBlank()?"":" · "+anchor)+" · "+quote);
        }return out;
    }
    static String completenessText(Map<String,Object> value) {
        if(value.isEmpty()||Boolean.FALSE.equals(value.get("required")))return "";
        boolean bodyOnly="BODY_PRESENCE_ONLY".equals(value.get("proofScope"));
        List<String> lines=new ArrayList<>();List<Map<String,Object>> items=completenessItems(value),references=maps(value.get("unresolvedReferences"));
        boolean resolved=Boolean.TRUE.equals(value.get("inventoryResolved"))&&references.isEmpty();
        lines.add(resolved?"已展开 "+items.size()+" 项要求":"清单尚未完整展开，不能据此认定投标材料缺失。");
        if(!atomicText(value,"reason").isBlank())lines.add(atomicText(value,"reason"));
        for(Map<String,Object> reference:references){lines.add("尚未展开的引用："+atomicText(reference,"text")+" · "+atomicText(reference,"reason"));lines.addAll(completenessSourceLines(reference.get("sources")));}
        for(Map<String,Object> item:items){lines.add(atomicText(item,"item")+"："+completenessStatus(item,bodyOnly)+" · "+atomicText(item,"reason"));
            lines.add("招标要求所在章节：");lines.addAll(completenessSourceLines(item.get("requirementSources")));
            List<String> evidence=completenessSourceLines(item.get("evidences"));lines.add("对应投标正文："+(evidence.isEmpty()?"尚未记录可定位依据；不据此认定材料缺失。":""));lines.addAll(evidence);}
        for(Map<String,Object> item:maps(value.get("excludedItems"))){lines.add("不纳入必检清单："+atomicText(item,"item")+" · "+atomicText(item,"reason"));lines.addAll(completenessSourceLines(item.get("requirementSources")));}
        lines.add("清单展开所用招标章节：");lines.addAll(completenessSourceLines(value.get("inventorySources")));
        lines.add(bodyOnly?"盖章按对应项展示。":"分项证明仅覆盖本清单要求；整个事项仍以最终结论为准。");return String.join("\n",lines);
    }
    static String completenessHtml(Map<String,Object> value) {
        if(value.isEmpty()||Boolean.FALSE.equals(value.get("required")))return "";
        boolean bodyOnly="BODY_PRESENCE_ONLY".equals(value.get("proofScope"));
        StringBuilder out=new StringBuilder("<section class=ocr-context data-completeness><h3>").append(bodyOnly?"清单正文核对":"清单核对").append("</h3>");
        List<Map<String,Object>> items=completenessItems(value),references=maps(value.get("unresolvedReferences"));
        boolean resolved=Boolean.TRUE.equals(value.get("inventoryResolved"))&&references.isEmpty();
        out.append("<p>").append(resolved?"已展开 "+items.size()+" 项要求":"清单尚未完整展开，不能据此认定投标材料缺失。").append("</p>");
        if(!atomicText(value,"reason").isBlank())out.append("<p>").append(html(atomicText(value,"reason"))).append("</p>");
        out.append("<details><summary>查看分项与章节来源</summary>");
        for(Map<String,Object> reference:references){out.append("<h4>尚未展开的引用</h4><p>").append(html(atomicText(reference,"text"))).append(" · ").append(html(atomicText(reference,"reason"))).append("</p>");appendCompletenessSources(out,reference.get("sources"),"引用原文");}
        for(Map<String,Object> item:items){out.append("<details><summary>").append(html(atomicText(item,"item"))).append(" · ").append(html(completenessStatus(item,bodyOnly))).append("</summary><p>").append(html(atomicText(item,"reason"))).append("</p>");appendCompletenessSources(out,item.get("requirementSources"),"招标要求所在章节");appendCompletenessSources(out,item.get("evidences"),"对应投标正文");if(completenessSourceLines(item.get("evidences")).isEmpty())out.append("<p>尚未记录可定位的投标正文依据，不据此认定缺少材料。</p>");out.append("</details>");}
        for(Map<String,Object> item:maps(value.get("excludedItems"))){out.append("<h4>不纳入必检清单</h4><p>").append(html(atomicText(item,"item"))).append(" · ").append(html(atomicText(item,"reason"))).append("</p>");appendCompletenessSources(out,item.get("requirementSources"),"适用依据");}
        appendCompletenessSources(out,value.get("inventorySources"),"清单展开所用招标章节");return out.append("<p>").append(bodyOnly?"盖章按对应项展示。":"分项证明仅覆盖本清单要求；整个事项仍以最终结论为准。").append("</p></details></section>").toString();
    }
    private static void appendCompletenessSources(StringBuilder out,Object sources,String title){List<String> lines=completenessSourceLines(sources);if(lines.isEmpty())return;out.append("<h4>").append(html(title)).append("</h4>");for(String line:lines)out.append("<blockquote>").append(html(line)).append("</blockquote>");}
    private static String atomicText(Map<String,Object> value,String key) {
        return value.get(key) instanceof String text?text.trim():"";
    }
    private static String atomicWorkflowLabel(Map<String,Object> check) {
        if(check.isEmpty())return "";
        return atomicText(FocusedCheckEvaluator.presentation(check),"label");
    }
    private static String atomicConditionLabel(Map<String,Object> condition) {
        String status=atomicText(condition,"status");
        if("REFERENCE_TIME".equals(condition.get("predicate"))&&"PASS".equals(status))return "参考时点已确认";
        if("APPLICABILITY".equals(atomicText(condition,"conditionRole")))return switch(status) {
            case "PASS"->"适用判断：成立";case "FAIL"->"适用判断：不成立";
            case "NOT_APPLICABLE"->"适用判断：本分支未执行";default->"适用判断：待确认";
        };
        if("UNDETERMINED".equals(status))return atomicText(FocusedCheckEvaluator.presentation(condition),"label");
        return switch(status) {
            case "PASS"->"条件满足";case "FAIL"->"条件不满足";case "UNDETERMINED"->"待核实";
            case "NOT_APPLICABLE"->"不适用";case "WARNING"->"存在疑点";default->"需核实";
        };
    }
    private static List<String> atomicDomains(Map<String,Object> check) {
        if(!(check.get("domains") instanceof List<?> domains))return List.of();
        return domains.stream().map(value->switch(Objects.toString(value,"")) {
            case "PERSONNEL_CONSORTIUM"->"人员与联合体";case "CERTIFICATE_VALIDITY"->"证书有效期";case "PRICE_BASIS"->"报价口径";default->"";
        }).filter(value->!value.isBlank()).distinct().toList();
    }
    private static List<String> atomicGapLines(Object raw) {
        return maps(raw).stream().map(gap->{
            String description=atomicText(gap,"description"),required=atomicText(gap,"requiredInput");
            return !description.isBlank()&&!required.isBlank()&&!description.equals(required)?description+"；需补充："+required:
                !description.isBlank()?description:!required.isBlank()?required:"待补信息尚未明确，请人工核实。";
        }).distinct().toList();
    }
    private static List<String> atomicNextSteps(Map<String,Object> check) {
        Object next=FocusedCheckEvaluator.presentation(check).get("nextSteps");return next instanceof List<?> list?list.stream().filter(String.class::isInstance).map(String.class::cast).toList():List.of();
    }
    private static List<String> atomicEvidenceLines(Object raw) {
        if(!(raw instanceof List<?> values))return List.of();List<String> lines=new ArrayList<>();
        for(Object value:values) {
            if(value instanceof Evidence source){lines.add(evidence(source));continue;}
            Map<String,Object> source=map(value);if(source.isEmpty())continue;
            String name=atomicText(source,"documentName"),quote=atomicText(source,"quote");
            int page=source.get("page") instanceof Number number?number.intValue():0;
            lines.add((name.isBlank()?"来源文件":name)+(page>0?" · 第 "+page+("PAGE".equals(source.getOrDefault("locationType","PAGE"))?" 页":" 处原文位置"):" · 位置未提供")+" · "+(quote.isBlank()?"引用原文未记录":quote));
        }return lines;
    }
    private static void appendAtomicGaps(StringBuilder out,List<String> gaps) {
        if(gaps.isEmpty())return;out.append("<ul>");for(String gap:gaps)out.append("<li>").append(html(gap)).append("</li>");out.append("</ul>");
    }
    static String atomicCheckHtml(Map<String,Object> check) {
        if(check.isEmpty())return "";List<Map<String,Object>> conditions=maps(check.get("conditions"));List<String> next=atomicNextSteps(check);
        Map<String,String> names=atomicNames(conditions);List<Map<String,Object>> issues=maps(FocusedCheckEvaluator.presentation(check).get("issues"));
        StringBuilder out=new StringBuilder("<section class=ocr-context data-atomic-check><h3>条件核验 · ").append(html(atomicWorkflowLabel(check))).append("</h3>");
        List<String> domains=atomicDomains(check);if(!domains.isEmpty())out.append("<p>").append(html(String.join(" · ",domains))).append("</p>");
        out.append("<p><strong>下一步</strong></p>");appendAtomicGaps(out,next.stream().limit(2).toList());
        if(!issues.isEmpty()){out.append("<details><summary>查看处理说明 · ").append(issues.size()).append(" 项</summary>");appendAtomicGaps(out,issues.stream().map(issue->atomicText(issue,"label")+" · "+FocusedCheckEvaluator.readable(atomicText(issue,"description"),names)).toList());out.append("</details>");}
        out.append("<details><summary>查看 ").append(conditions.size()).append(" 个条件与依据</summary>");
        if(conditions.isEmpty())out.append("<p>条件清单尚未完整记录，请人工核实。</p>");
        int index=0;for(Map<String,Object> condition:conditions) {
            Map<String,Object> display=FocusedCheckEvaluator.presentation(condition);
            String title=FocusedCheckEvaluator.readable(atomicText(condition,"title"),names),reason="UNDETERMINED".equals(condition.get("status"))?atomicText(display,"reason"):FocusedCheckEvaluator.readable(atomicText(condition,"reason"),names);index++;
            out.append("<details><summary>").append(html(title.isBlank()?"条件 "+index:title)).append(" · ").append(html(atomicConditionLabel(condition))).append("</summary><p>")
                .append(html(reason.isBlank()?"此条件的判断说明未完整记录，请核对原文。":reason)).append("</p>");
            List<String> gaps=maps(display.get("issues")).stream().map(issue->FocusedCheckEvaluator.readable(atomicText(issue,"description"),names)).toList();if(!gaps.isEmpty()){out.append("<p><strong>待处理事项</strong></p>");appendAtomicGaps(out,gaps);}
            List<String> requirement=atomicRequirementText(condition);if(!requirement.isEmpty()){out.append("<details><summary>查看条件原句与要求对象</summary>");for(String text:requirement)out.append("<blockquote>").append(html(text)).append("</blockquote>");out.append("</details>");}
            List<String> sources=atomicEvidenceLines(condition.get("evidences"));
            if(sources.isEmpty())out.append("<p>").append(Set.of("PROCESSING_FAILED","REQUIREMENT_UNPARSED").contains(atomicText(display,"state"))?"本条件处理尚未完成，未形成可定位的事实依据；不能据此判断材料缺失。":"未记录可定位的事实依据，请先核对本次文件。").append("</p>");
            else for(String source:sources)out.append("<blockquote>").append(html(source)).append("</blockquote>");
            out.append("</details>");
        }
        return out.append("<p>条件是否需要同时满足，以招标条款为准。分项结果不替代整项检查结论、原图复核或人工决定。</p></details></section>").toString();
    }
    static String atomicCheckText(Map<String,Object> check) {
        if(check.isEmpty())return "";List<String> parts=new ArrayList<>();
        List<String> domains=atomicDomains(check);if(!domains.isEmpty())parts.add("核验范围："+String.join("、",domains));
        parts.add("下一步："+String.join("；",atomicNextSteps(check)));int index=0;
        List<Map<String,Object>> conditions=maps(check.get("conditions"));
        Map<String,String> names=atomicNames(conditions);
        if(conditions.isEmpty())parts.add("条件清单尚未完整记录，请人工核实。");
        for(Map<String,Object> condition:conditions) {
            Map<String,Object> display=FocusedCheckEvaluator.presentation(condition);
            String title=FocusedCheckEvaluator.readable(atomicText(condition,"title"),names),reason="UNDETERMINED".equals(condition.get("status"))?atomicText(display,"reason"):FocusedCheckEvaluator.readable(atomicText(condition,"reason"),names);index++;
            parts.add((title.isBlank()?"条件 "+index:title)+"："+atomicConditionLabel(condition)+(reason.isBlank()?"；判断说明未完整记录":"；"+reason));
            List<String> gaps=maps(display.get("issues")).stream().map(issue->FocusedCheckEvaluator.readable(atomicText(issue,"description"),names)).toList();if(!gaps.isEmpty())parts.add("待处理事项："+String.join("；",gaps));
            parts.addAll(atomicRequirementText(condition));
            List<String> sources=atomicEvidenceLines(condition.get("evidences"));parts.add(sources.isEmpty()?"未记录可定位的事实依据，不据此认定材料缺失。":"原文依据："+String.join("\n",sources));
        }
        parts.add("条件是否需要同时满足，以招标条款为准。分项结果不替代整项检查结论、原图复核或人工决定。");
        return String.join("\n",parts);
    }
    private static Map<String,String> atomicNames(List<Map<String,Object>> conditions){Map<String,String> names=new LinkedHashMap<>();for(Map<String,Object> condition:conditions)names.put(atomicText(condition,"id"),atomicText(condition,"title"));return names;}
    private static List<String> atomicRequirementText(Map<String,Object> condition){
        List<String> values=new ArrayList<>();String quote=atomicText(condition,"sourceQuote");if(!quote.isBlank())values.add("条件原句："+quote);
        Map<String,Object> source=map(condition.get("requirementContext"));
        if(!atomicText(source,"value").isBlank()&&!atomicText(source,"sourceQuote").isBlank()&&Set.of("ORIGINAL_REQUIREMENT","REVIEWED_REQUIREMENT").contains(atomicText(source,"sourceKind"))&&Set.of("LOCAL","PARENT","CLAUSE").contains(atomicText(source,"bindingKind"))){
            values.add("条款要求对象："+atomicText(source,"value"));values.add("PARENT".equals(source.get("bindingKind"))?"沿用上级条件："+(atomicText(source,"parentTitle").isBlank()?"上级条件":atomicText(source,"parentTitle")):"来源：本条款原文");values.add(atomicText(source,"sourceQuote"));
            values.add("仅说明招标要求对象的来源，不代表已确认投标人员、单位或证件归属。");
        }return values;
    }
    private static String targetedReviewStatus(String status) {
        return switch(status) {
            case "IN_PROGRESS"->"正在补证与复核";
            case "RULE_CONFIRMED"->"规则已确认差异";
            case "EVIDENCE_AUGMENTED"->"已补证据，待人工核对";
            case "MODEL_REVIEWED"->"已取得模型建议，待人工核对";
            case "EVIDENCE_INSUFFICIENT"->"比较依据仍不足";
            case "BUDGET_DEFERRED"->"本次记录的旧调用次数上限导致暂缓，待核对";
            case "GROUP_LIMIT_DEFERRED"->"本次记录的旧组数或时限限制导致暂缓";
            case "MODEL_UNAVAILABLE"->"模型暂不可用";
            case "ERROR"->"定向复核未完成";
            default->"复核状态待确认";
        };
    }
    public static String contextTableCoverageHtml(Map<String,Object> coverage) {
        Map<String,Object> assignment=map(coverage.get("contextAssignments")),table=map(coverage.get("tableRelations"));
        if(assignment.isEmpty()&&table.isEmpty())return "";
        StringBuilder h=new StringBuilder("<section class=ocr-context data-context-table-coverage><h2>上下文与表格整理</h2>");
        if(!assignment.isEmpty())h.append("<p>事实归属：明确标记 ").append(reviewCount(assignment,"EXPLICIT")).append(" 条；沿用章节 ")
            .append(reviewCount(assignment,"INHERITED")).append(" 条；文档暂定 ").append(reviewCount(assignment,"DOCUMENT_DEFAULT"))
            .append(" 条；未明确 ").append(reviewCount(assignment,"UNRESOLVED")).append(" 条。</p>");
        if(!table.isEmpty()) {
            h.append("<p>识别表格 ").append(reviewCount(table,"tables")).append(" 个；行关系已关联 ").append(reviewCount(table,"resolvedRows"))
                .append(" / ").append(reviewCount(table,"rows")).append(" 行；待核对 ").append(reviewCount(table,"unresolvedRows"))
                .append(" 行；续表结构关联 ").append(reviewCount(table,"linkedContinuations")).append(" 组。</p>");
            if(Boolean.TRUE.equals(table.get("requiresReview")))h.append("<p>部分表格关系仍需核对原文，未确认的关联不参与确定性判断。</p>");
            List<Map<String,Object>> issues=maps(table.get("issues"));
            if(!issues.isEmpty()) {
                h.append("<details><summary>表格关联提示（").append(issues.size()).append(" 项）</summary><ul>");
                for(Map<String,Object> issue:issues) {
                    Map<String,Object> source=map(issue.get("source"));Object rawPage=source.getOrDefault("page",issue.get("page"));
                    int page=rawPage instanceof Number number?number.intValue():0;
                    h.append("<li>").append(page>0?"第 "+page+" 页：":"范围提示：").append(html(BidService.text(issue,"reason"))).append("</li>");
                }h.append("</ul></details>");
            }
        }
        return h.append("<p>归属数量与表格关联量是规则处理范围，不能作为 OCR 准确率或业务通过率；续表结构关联不自动继承人员身份。</p></section>").toString();
    }
    private static String contextAssignmentStatus(String status) {
        return switch(status) {
            case "EXPLICIT"->"明确标记归属";case "INHERITED"->"沿用章节归属";
            case "DOCUMENT_DEFAULT"->"按文档角色暂定";case "UNRESOLVED"->"上下文归属待明确";default->"归属状态未提供";
        };
    }
    /** Document declarations remain distinct from verified legal identity. */
    public static String consortiumAssignmentsHtml(List<Map<String,Object>> occurrences) {
        Map<String,List<Map<String,Object>>> members=new LinkedHashMap<>();
        for(Map<String,Object> occurrence:occurrences) {
            Map<String,Object> assignment=map(occurrence.get("subjectAssignment"));
            if(!"DECLARED_CONSORTIUM_MEMBER".equals(assignment.get("status")))continue;
            String name=BidService.text(assignment,"memberName");
            members.computeIfAbsent(name,k->new ArrayList<>()).add(assignment);
        }
        if(members.isEmpty())return "";
        StringBuilder h=new StringBuilder("<section class=ocr-context data-subject-assignments><h2>联合体成员声明</h2><p>本项仅核对文档中的成员声明，未核验签章及法律身份。</p>");
        for(var member:members.entrySet()) {
            h.append("<details data-subject-assignment><summary>联合体成员 · ").append(html(member.getKey().isBlank()?"名称未提供":member.getKey())).append("</summary>");
            boolean matched=member.getValue().stream().allMatch(a->Boolean.TRUE.equals(a.get("projectMatched")));
            h.append("<p>").append(matched?"协议明确声明的联合体成员；协议项目名称与本次项目匹配。":"成员声明待核对；项目匹配依据尚不完整。").append("</p>");
            Set<Object> sources=new LinkedHashSet<>();
            for(Map<String,Object> assignment:member.getValue())if(assignment.get("evidences") instanceof List<?> evidences)sources.addAll(evidences);
            appendReviewSources(h,new ArrayList<>(sources),"协议与项目封面依据");
            h.append("</details>");
        }
        return h.append("</section>").toString();
    }
    private static String reviewCount(Map<String,Object> item,String key) {
        return item.get(key) instanceof Number number&&number.longValue()>=0?number.toString():"未知";
    }
    public static String targetedReviewText(Map<String,Object> review) {
        if(review.isEmpty())return "本报告未记录组级定向复核，不能确认该组已检索补证或模型核验。";
        List<String> parts=new ArrayList<>(List.of(targetedReviewStatus(BidService.text(review,"status")),BidService.text(review,"reason"),
            "检索候选 "+reviewCount(review,"candidateCount")+" 条；来源校验通过 "+reviewCount(review,"acceptedEvidenceCount")+" 条；排除 "+reviewCount(review,"rejectedEvidenceCount")+" 条",
            "模型请求："+(Boolean.TRUE.equals(review.get("requestOutcomeUnknown"))?"发送情况未知":"IN_PROGRESS".equals(review.get("status"))?"处理中，等待回执":Boolean.TRUE.equals(review.get("modelRequested"))?"已请求":Boolean.FALSE.equals(review.get("modelRequested"))?"未请求":"未记录")));
        if(review.containsKey("deduplicatedEvidenceCount"))parts.add("去重 "+reviewCount(review,"deduplicatedEvidenceCount")+" 条");
        if(review.get("missingDimensions") instanceof List<?> missing&&!missing.isEmpty())parts.add("待明确："+String.join("、",missing.stream().map(v->Objects.toString(v,"")).toList()));
        if(review.get("guards") instanceof List<?> guards&&!guards.isEmpty())parts.add("比较保护与限制："+String.join("；",guards.stream().map(v->map(v).isEmpty()?Objects.toString(v,""):BidService.text(map(v),"reason")).toList()));
        Map<String,Object> recommendation=map(review.get("recommendation"));
        if(!recommendation.isEmpty())parts.add("复核建议（供人工确认）："+label(BidService.text(recommendation,"status"))+"；"+BidService.text(recommendation,"reason")+"；"+BidService.text(recommendation,"suggestion"));
        parts.add("候选数量不代表全文覆盖，模型建议不自动替代本组结论或人工复核。");
        return String.join("\n",parts.stream().filter(s->!s.isBlank()).toList());
    }
    public static String consistencyEnhancementHtml(Map<String,Object> group,List<Map<String,Object>> occurrences) {
        if(group.isEmpty())return "";
        Map<String,Object> review=map(group.get("targetedReview"));
        StringBuilder h=new StringBuilder("<section class=ocr-context data-targeted-review><h3>定向补证与复核</h3><blockquote>").append(html(targetedReviewText(review))).append("</blockquote>");
        appendReviewSources(h,review.get("evidences"),"补充原文证据");h.append("</section>");
        for(Map<String,Object> occurrence:occurrences) {
            Map<String,Object> assignment=map(occurrence.get("contextAssignment")),table=map(occurrence.get("tableAssociation"));
            if(assignment.isEmpty()&&table.isEmpty())continue;
            h.append("<details data-consistency-assignment><summary>事实归属与表格关联：").append(html(Objects.toString(occurrence.getOrDefault("rawValue",occurrence.getOrDefault("value","原文记录"))))).append("</summary>");
            if(!assignment.isEmpty()) {
                h.append("<p>上下文归属：").append(contextAssignmentStatus(BidService.text(assignment,"status"))).append("；").append(html(BidService.text(assignment,"reason"))).append("</p>");
                if(assignment.get("sectionPath") instanceof List<?> sections&&!sections.isEmpty())h.append("<p>所属章节：").append(html(String.join(" / ",sections.stream().map(v->Objects.toString(v,"")).toList()))).append("</p>");
                if("DOCUMENT_DEFAULT".equals(assignment.get("status")))h.append("<p>按文档角色暂定，尚未找到明确章节依据。</p>");
                if(Boolean.TRUE.equals(assignment.get("crossPage")))h.append("<p>此处承接前页章节；归属依据见下方原文。</p>");
                appendReviewSources(h,assignment.get("evidences"),"章节与归属依据");
            }
            if(!table.isEmpty()) {
                h.append("<p>表格关联：").append("RESOLVED".equals(table.get("status"))?"行列已关联":"关联待确认").append("</p>");
                if(table.get("row") instanceof Number row&&row.intValue()>0&&table.get("column") instanceof Number column&&column.intValue()>0)
                    h.append("<p>识别表格：第 ").append(row.intValue()).append(" 行；第 ").append(column.intValue()).append(" 列</p>");
                if(table.get("headerPath") instanceof List<?> headers&&!headers.isEmpty())h.append("<p>对应表头：").append(html(String.join(" / ",headers.stream().map(v->Objects.toString(v,"")).toList()))).append("</p>");
                if(Boolean.TRUE.equals(table.get("inherited")))h.append("<p>本行沿用同页合并单元格中的信息。</p>");
                List<Object> sources=new ArrayList<>();if(table.get("source")!=null)sources.add(table.get("source"));if(table.get("headerSources") instanceof List<?> headers)sources.addAll(headers);
                appendReviewSources(h,sources,"单元格与表头来源");
                h.append("<p>关联基于已识别文字与行列结构，仍需核对原图布局；相邻页不自动认定为同一人员记录。</p>");
            }h.append("</details>");
        }
        return h.toString();
    }
    private static void appendReviewSources(StringBuilder h,Object raw,String title) {
        if(!(raw instanceof List<?> sources)||sources.isEmpty())return;
        h.append("<details><summary>").append(html(title)).append("</summary>");
        for(Object value:sources) {
            if(value instanceof Evidence ev){h.append("<blockquote>").append(html(evidence(ev))).append("</blockquote>");continue;}
            Map<String,Object> source=map(value);if(source.isEmpty())continue;
            Object rawPage=source.get("page");int page=rawPage instanceof Number number?number.intValue():0;
            h.append("<blockquote>").append(html(BidService.text(source,"documentName").isBlank()?"来源文件":BidService.text(source,"documentName")))
                .append(page>0?" · 第 "+page+("PAGE".equals(source.getOrDefault("locationType","PAGE"))?" 页":" 处原文位置"):" · 位置未提供")
                .append("\n").append(html(BidService.text(source,"quote"))).append("</blockquote>");
        }h.append("</details>");
    }
    /** Extraction notes retain the frozen scope without turning unavailable fields into findings. */
    public static String personCredentialCoverageHtml(Map<String,Object> coverage,List<Map<String,Object>> manifest,List<Map<String,Object>> documents) {
        if(coverage==null||coverage.isEmpty()) return "";
        boolean requiresReview=PersonCredentialConsistency.requiresReview(coverage);
        String state=switch(BidService.text(coverage,"status")) {
            case "TEXT_SCANNED"->"已扫描可读文本中的明确字段";
            case "PARTIAL"->requiresReview?"人员与证件扫描存在未完成范围":"已提取可识别的人员与证件字段";
            case "NO_FACTS"->"尚未提取到可用的人员证件事实";
            default->"人员与证件事实覆盖尚未确认";
        };
        StringBuilder h=new StringBuilder("<section class=ocr-context data-person-credential-coverage><h2>人员与证件事实覆盖</h2><p><strong>")
            .append(html(state)).append("</strong></p><p>已扫描 ").append(html(Objects.toString(coverage.getOrDefault("pagesScanned","—"))))
            .append(" / ").append(html(Objects.toString(coverage.getOrDefault("pagesTotal","—")))).append(" 个原文位置；发现人员材料 ")
            .append(html(Objects.toString(coverage.getOrDefault("candidatePages","—")))).append(" 处；提取事实 ")
            .append(html(Objects.toString(coverage.getOrDefault("occurrencesCount","—")))).append(" 条；字段差异 ")
            .append(html(Objects.toString(coverage.getOrDefault("comparisonGroupsCount","—")))).append(" 组。</p>");
        List<Map<String,Object>> sources=manifest.isEmpty()?documents:manifest;
        if(!sources.isEmpty()) {
            h.append("<p>来源文件：</p><ul>");
            for(Map<String,Object> source:sources) {
                String id=BidService.text(source,"documentId");if(id.isBlank())id=BidService.text(source,"id");
                String docId=id;Map<String,Object> document=documents.stream().filter(d->docId.equals(BidService.text(d,"id"))).findFirst().orElse(Map.of());
                String name=BidService.text(source,"documentName");if(name.isBlank())name=BidService.text(source,"name");if(name.isBlank())name=BidService.text(document,"name");
                h.append("<li>").append(html(name.isBlank()?"来源文件 "+id:name)).append(" · V")
                    .append(html(Objects.toString(source.getOrDefault("documentVersion",source.getOrDefault("version",document.getOrDefault("version","—")))))).append("</li>");
            }h.append("</ul>");
        }
        List<Map<String,Object>> issues=maps(coverage.get("issues"));
        List<Map<String,Object>> actionable=coverage.containsKey("actionableIssues")?maps(coverage.get("actionableIssues")):
            issues.stream().filter(PersonCredentialConsistency::issueRequiresReview).toList();
        List<Map<String,Object>> informational=coverage.containsKey("informationalIssues")?maps(coverage.get("informationalIssues")):
            issues.stream().filter(issue->!PersonCredentialConsistency.issueRequiresReview(issue)).toList();
        if(!actionable.isEmpty()) {
            h.append("<details open data-person-credential-attention><summary>扫描与来源处理提示（").append(actionable.size()).append(" 处）</summary>");
            personCredentialIssueList(h,actionable,documents,true);
            h.append("</details>");
        } else if(requiresReview) {
            h.append("<p data-person-credential-attention>本轮仍有未完成的扫描或来源验证范围，详见处理记录。</p>");
        }
        List<Map<String,Object>> missing=maps(coverage.get("missingCandidateLocations"));
        h.append("<details data-person-credential-extraction-notes><summary>提取范围与说明</summary>");
        if("PARTIAL".equals(coverage.get("status"))) h.append("<p>部分字段未进入比较，原始提取范围记录为部分覆盖。</p>");
        if(!informational.isEmpty()) personCredentialIssueList(h,informational,documents,false);
        if(!missing.isEmpty()) {
            h.append("<p>未提取到明确字段的位置：</p><ul>");
            for(Map<String,Object> item:missing) h.append("<li>").append(html(Objects.toString(item.getOrDefault("documentName",item.getOrDefault("documentId","来源待定位")))))
                .append(" · 第 ").append(html(Objects.toString(item.getOrDefault("page","—")))).append(" 页</li>");
            h.append("</ul>");
        }
        h.append("<p>比较范围为已识别且能定位原文的字段，不代表所有字段均已提取。字段缺失、归属不明、号码遮挡或格式异常、证件类型不明确等只保留为提取说明，不计为检查问题。同名也不直接认定为同一人。</p></details>")
            .append("<p>字段差异对应的页码和原文证据见检查结果。</p></section>");
        return h.toString();
    }
    private static void personCredentialIssueList(StringBuilder h,List<Map<String,Object>> issues,List<Map<String,Object>> documents,boolean actionable) {
        h.append("<ul>");
        for(Map<String,Object> issue:issues) {
            String documentId=BidService.text(issue,"documentId"),name=BidService.text(issue,"documentName");
            if(name.isBlank()&&!documentId.isBlank()) name=documents.stream().filter(d->documentId.equals(BidService.text(d,"id"))).map(d->BidService.text(d,"name")).findFirst().orElse("");
            String reason=BidService.text(issue,"reason");if(reason.isBlank())reason=BidService.text(issue,"message");
            Object rawPage=issue.get("page");int page=rawPage instanceof Number number?number.intValue():0;
            h.append("<li>").append(html(name.isBlank()?(documentId.isBlank()?"本轮资料":"来源文件 "+documentId):name)).append(" · ")
                .append(page>0?"第 "+page+("PAGE".equals(issue.getOrDefault("locationType","PAGE"))?" 页":" 处原文位置"):"范围提示")
                .append("：").append(html(reason.isBlank()?(actionable?"该处扫描或来源验证尚未完成。":"该处部分字段未参与比较。"):reason)).append("</li>");
        }
        h.append("</ul>");
    }
    private static String categoryStatus(String status) {
        return switch(status){case "OBSERVED"->"已提取信息";case "NEEDS_CONTEXT"->"比较依据待补充";default->"未提取到可用事实";};
    }
    private static String relationLabel(String value) {
        return switch(value){case "VALUE"->"同字段差异";case "ARITHMETIC"->"计算关系";case "TEMPORAL"->"时间关系";case "SEMANTIC"->"承诺关系";case "EVIDENCE"->"证据关联";default->value;};
    }
    private static String comparisonContext(Map<String,Object> context) {
        Map<String,String> labels=Map.ofEntries(Map.entry("subject","主体"),Map.entry("scope","范围"),Map.entry("lot","标段"),
            Map.entry("object","对象"),Map.entry("stage","阶段"),Map.entry("trigger","起算事件"),Map.entry("condition","条件"),
            Map.entry("version","版本"),Map.entry("range","适用范围"),Map.entry("period","期间"),Map.entry("currency","币种"),
            Map.entry("taxBasis","税额口径"),Map.entry("unitBasis","计量口径"),Map.entry("dayType","日历口径"),
            Map.entry("basis","口径"),Map.entry("unit","单位"),Map.entry("formula","计算式"),Map.entry("expected","计算值"),Map.entry("actual","原文值"),
            Map.entry("missing","待补充"),Map.entry("dimensions","上下文"),Map.entry("calculation","计算详情"),
            Map.entry("tolerance","允许误差"),Map.entry("parameterName","参数名称"),Map.entry("reason","依据"),
            Map.entry("quantity","数量"),Map.entry("unitPrice","单价"),Map.entry("lineAmount","行金额"));
        List<String> parts=new ArrayList<>();
        for(var item:context.entrySet()) {
            String value=item.getValue() instanceof Map<?,?>?comparisonContext(map(item.getValue())):Objects.toString(item.getValue(),"").replace("BIDDER_MEMBER:","联合体成员 · ");
            if(!value.isBlank())parts.add(labels.getOrDefault(item.getKey(),item.getKey())+"："+value);
        }
        return String.join("；",parts);
    }
    static String consistencyScope(Map<String,Object> report) {
        if(report.isEmpty())return "";
        Map<String,Object> coverage=map(report.get("coverage")),model=map(coverage.get("model"));
        StringBuilder scope=new StringBuilder("原文位置 "+Objects.toString(coverage.get("pagesTotal"),"—")+"；已扫描 "+Objects.toString(coverage.get("pagesScanned"),"—")
            +"；未完整解析 "+Objects.toString(coverage.get("pagesUnparsed"),"—")+"；模型："
            +("RULES_RETRIEVAL".equals(coverage.get("checkMode"))?"未请求全文生成式抽取；复杂疑点按需核验":Boolean.TRUE.equals(model.get("enabled"))?(Boolean.TRUE.equals(model.get("complete"))?"已扫描可读文本":"存在未完成位置"):"未启用")
            +"。扫描覆盖不等于全部信息已核实。");
        Map<String,Object> assignment=map(coverage.get("contextAssignments")),table=map(coverage.get("tableRelations"));
        if(!assignment.isEmpty())scope.append("事实归属：明确标记 ").append(reviewCount(assignment,"EXPLICIT"))
            .append(" 条；沿用章节 ").append(reviewCount(assignment,"INHERITED"))
            .append(" 条；文档暂定 ").append(reviewCount(assignment,"DOCUMENT_DEFAULT"))
            .append(" 条；未明确 ").append(reviewCount(assignment,"UNRESOLVED")).append(" 条。");
        if(!table.isEmpty())scope.append("表格关系：总表 ").append(reviewCount(table,"tables"))
            .append(" 个；总行 ").append(reviewCount(table,"rows"))
            .append(" 行；可关联 ").append(reviewCount(table,"resolvedRows"))
            .append(" 行；待核对 ").append(reviewCount(table,"unresolvedRows")).append(" 行。");
        return scope.append("CSV按检查结果行提供全局范围摘要及关联组事实；未列入差异组的完整事实和来源请查看JSON导出。").toString();
    }
    private static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> values?values.stream().filter(Map.class::isInstance).map(ReportService::map).toList():List.of(); }
    private static Map<String,Object> findingPresentation(Map<String,Object> context,Finding finding) {
        Map<String,Object> value=new LinkedHashMap<>(map(map(context.get("findings")).get(finding.id()))),assessment=map(finding.assessment()),check=map(assessment.get("atomicCheck"));
        if(check.isEmpty())return value;Map<String,String> names=atomicNames(maps(check.get("conditions")));Map<String,Object> review=map(assessment.get("ocrReview"));
        boolean same="UNDETERMINED".equals(finding.evaluation().status())&&finding.evaluation().status().equals(check.get("businessDecision"))&&!Boolean.TRUE.equals(review.get("required"))&&!Boolean.TRUE.equals(review.get("incomplete"));
        value.put("reason",same?atomicText(FocusedCheckEvaluator.presentation(check),"reason"):FocusedCheckEvaluator.readable(presented(value,"reason",finding.evaluation().reason()),names));
        value.put("suggestion",same?String.join("；",atomicNextSteps(check).stream().limit(2).toList()):FocusedCheckEvaluator.readable(presented(value,"suggestion",finding.evaluation().suggestion()),names));return value;
    }
    private static String presented(Map<String,Object> value,String key,String fallback) { return value.get(key) instanceof String text?text:safe(fallback); }
    static String originalReviewHtml(Finding finding,Map<String,Object> presentation) {
        String note=originalReviewText(finding,presentation);if(note.isBlank())return "";
        Map<String,Object> business=BusinessResultPresentation.describe(finding.evaluation(),finding.assessment());
        boolean sourceOnly=Set.of("SYSTEM_INCOMPLETE","EVIDENCE_PENDING").contains(business.get("state"))&&!Boolean.TRUE.equals(business.get("userActionRequired"));
        if(sourceOnly)return "<details class=recognition-source-record><summary>识别来源记录</summary><p>保留本项使用的识别来源与处理说明，不计入人工核对待办。</p><details><summary>原始识别说明与位置</summary><blockquote>"+html(note)+"</blockquote></details></details>";
        return "<section class=ocr-review-note><h3>原图复核提示</h3><blockquote>"+html(note)+"</blockquote></section>";
    }
    private static String originalReviewText(Finding finding,Map<String,Object> presentation) {
        Map<String,Object> review=map(map(finding.assessment()).get("ocrReview"));
        if(review.isEmpty()) return presented(presentation,"note","");
        if(!Boolean.TRUE.equals(review.get("required"))&&!Boolean.TRUE.equals(review.get("incomplete"))) return "";
        List<String> lines=new ArrayList<>();
        if(Boolean.TRUE.equals(review.get("deferred"))) lines.add("相关位置待按需补识别，计划延期不表示识别服务失败。");
        if(Boolean.TRUE.equals(review.get("incomplete"))&&(!Boolean.TRUE.equals(review.get("deferred"))||Boolean.TRUE.equals(review.get("failed")))) lines.add("相关位置识别未完成。");
        String reason=BidService.text(review,"reason");lines.add(reason.isBlank()?"请对照相关原图核对文字准确性；文字识别不等于签章验证。":reason);
        for(Map<String,Object> location:maps(review.get("locations"))) lines.add(BidService.text(location,"label"));
        return String.join("\n",lines);
    }
    private static String evaluationText(Object value) {
        if(value instanceof Evaluation evaluation) return label(safe(evaluation.status()))+" · "+safe(evaluation.reason());
        Map<String,Object> evaluation=map(value);return evaluation.isEmpty()?"":label(BidService.text(evaluation,"status"))+" · "+BidService.text(evaluation,"reason");
    }
    private static String adjustmentText(Map<String,Object> assessment) { return String.join("\n",maps(assessment.get("adjustments")).stream().map(item->BidService.text(item,"reason")).filter(reason->!reason.isBlank()).toList()); }
    private static String assessmentState(Finding finding) {
        Map<String,Object> assessment=map(finding.assessment());if(assessment.isEmpty()) return "";
        if(!map(assessment.get("atomicCheck")).isEmpty())return atomicText(BusinessResultPresentation.describe(finding.evaluation(),assessment),"label");
        if("BUDGET_DEFERRED".equals(BidService.text(map(assessment.get("model")),"decision")))return "本次记录的旧调用次数上限导致暂缓";
        Set<String> codes=new HashSet<>();maps(assessment.get("adjustments")).forEach(item->codes.add(BidService.text(item,"code")));
        if(codes.contains("MODEL_NOT_CONFIGURED")) return "语义服务未配置";
        if(List.of("MODEL_HTTP_ERROR","MODEL_TIMEOUT","MODEL_COMMUNICATION_ERROR","MODEL_INTERRUPTED").stream().anyMatch(codes::contains)) return "模型核验未完成";
        if(List.of("MODEL_COMPLETION_TRUNCATED","MODEL_JSON_INVALID","MODEL_SCHEMA_INVALID","MODEL_CITATION_INVALID").stream().anyMatch(codes::contains)) return "模型输出未通过校验";
        if(codes.contains("CONTEXT_BUDGET_EXCEEDED")) return "证据上下文超限";
        if(codes.contains("INPUT_INVALID")) return "检查输入不完整";
        if(codes.contains("OCR_COVERAGE_DEFERRED")||codes.contains("OCR_SOURCE_DEFERRED")) return "待按计划补识别";
        if(codes.contains("EVIDENCE_SCOPE_INCOMPLETE")) return "证据未覆盖检查范围";
        if(List.of("EVIDENCE_MISSING","PERSONNEL_COVERAGE_INCOMPLETE").stream().anyMatch(codes::contains)) return "证据覆盖不足";
        if(List.of("DATE_ASSERTION_UNGROUNDED","VISUAL_ASSERTION_UNGROUNDED","CLAIM_GROUNDING_INVALID").stream().anyMatch(codes::contains)) return "推断缺少可靠依据";
        return switch(BidService.text(map(assessment.get("model")),"state")) { case "UNAVAILABLE"->"模型核验未完成";case "GUARDED"->"模型结论已调整";default->""; };
    }
    private static String retrievalLabel(String mode) { return switch(mode) {
        case "HYBRID_RERANKED"->"全文检索与证据重排";case "HYBRID_RRF_DEGRADED"->"融合检索（排序降级）";case "BM25_DEGRADED"->"关键词检索（服务降级）";case "BM25_DISABLED"->"关键词检索（未启用增强检索）";case "OFFLINE_LEXICAL"->"离线关键词检索";case "LEXICAL","KEYWORD"->"关键词检索";case "HYBRID"->"混合证据检索";case "VECTOR"->"语义检索";case "PERSONNEL_LINKED"->"岗位与人员证据关联";case "DETERMINISTIC"->"规则直接核对";default->"未记录";
    }; }
    private static String initialRetrievalMode(Map<String,Object> retrieval) {
        String initial=BidService.text(map(retrieval.get("initial")),"mode");return initial.isBlank()?BidService.text(retrieval,"mode"):initial;
    }
    private static String retrievalText(Map<String,Object> retrieval) {
        String value="初次检索："+retrievalLabel(initialRetrievalMode(retrieval));Map<String,Object> followUp=map(retrieval.get("followUp"));
        return Boolean.TRUE.equals(followUp.get("attempted"))?value+"\n缺口补充检索："+retrievalLabel(BidService.text(followUp,"mode")):value;
    }
    private static void appendAssessment(StringBuilder b,Finding finding) {
        Map<String,Object> assessment=map(finding.assessment());if(assessment.isEmpty()) return;
        String adjustments=adjustmentText(assessment);Map<String,Object> retrieval=map(assessment.get("retrieval")),followUp=map(retrieval.get("followUp"));
        b.append("<details class=assessment-details><summary>查看原始判断与调整说明</summary><p>原始判断：</p><blockquote>")
            .append(html(evaluationText(assessment.get("rawEvaluation")))).append("</blockquote><p>原图策略前判断：</p><blockquote>")
            .append(html(evaluationText(assessment.get("preOcrEvaluation")))).append("</blockquote><p>结论调整：</p><blockquote>")
            .append(html(adjustments.isBlank()?"未记录额外调整。":adjustments)).append("</blockquote><p>初次检索：")
            .append(html(retrievalLabel(initialRetrievalMode(retrieval)))).append("</p>");
        if(Boolean.TRUE.equals(followUp.get("attempted"))) b.append("<p>缺口补充检索：").append(html(retrievalLabel(BidService.text(followUp,"mode")))).append("</p>");
        String modelState=switch(BidService.text(map(assessment.get("model")),"state")) { case "COMPLETED"->"已完成";case "REPAIRED"->"输出纠正后完成";case "UNAVAILABLE"->"未能完成";case "GUARDED"->"输出经证据校验调整";default->""; };
        if(!modelState.isBlank()) b.append("<p>模型核验：").append(html(modelState)).append("</p>");
        b.append("</details>");
    }
    private static String originalExplanation(Map<String,Object> presentation,Evaluation evaluation) {
        String suggestion=presented(presentation,"originalSuggestion",evaluation.suggestion());
        return presented(presentation,"originalReason",evaluation.reason())+(suggestion.isBlank()?"":"\n原始建议："+suggestion);
    }
    private static List<Map<String,Object>> structuredCoverageSnapshots(Map<String,Object> context) {
        List<Map<String,Object>> snapshots=new ArrayList<>();
        for(Map<String,Object> document:maps(context.get("documents"))) {
            Map<String,Object> processing=map(document.get("processing")),snapshot=new LinkedHashMap<>();
            for(String key:List.of("processingMode","visualAnalysisStatus","automaticVisualRequested","contextLimitedPageCount","contextLimitedPages","contextCoverageLimitations"))
                if(processing.containsKey(key)) snapshot.put(key,processing.get(key));
            if(!snapshot.isEmpty()) {snapshot.put("documentId",document.get("documentId"));snapshots.add(snapshot);}
        }
        return snapshots;
    }
    private static List<Integer> structuredLimitedPages(Map<String,Object> processing,Map<String,Object> document) {
        Object raw=processing.get("contextLimitedPages");if(!(raw instanceof List<?> pages)) return List.of();
        int maximum=document.get("totalLocations") instanceof Number n?n.intValue():Integer.MAX_VALUE;
        return pages.stream().filter(Number.class::isInstance).map(Number.class::cast)
            .filter(n->n.doubleValue()==n.intValue()&&n.intValue()>0&&n.intValue()<=maximum)
            .map(Number::intValue).distinct().sorted().toList();
    }
    private static int structuredLimitedCount(Map<String,Object> processing) {
        Object raw=processing.get("contextLimitedPageCount");
        return raw instanceof Number n&&n.doubleValue()==n.intValue()&&n.intValue()>=0?n.intValue():structuredLimitedPages(processing,Map.of()).size();
    }
    private static List<String> structuredLimitationReasons(Map<String,Object> processing) {
        if(!(processing.get("contextCoverageLimitations") instanceof List<?> reasons)) return List.of();
        return reasons.stream().filter(String.class::isInstance).map(String.class::cast).filter(s->!s.isBlank()).map(code->switch(code) {
            case "STRUCTURED_FIELD_LIMIT_REACHED"->"单页字段数量达到提取上限";
            case "STRUCTURED_FIELDS_REMAINING"->"原页仍有字段未提取";
            case "STRUCTURED_SOURCE_TRUNCATED"->"本次仅处理了部分原文内容";
            case "STRUCTURED_FIELDS_REJECTED"->"未通过来源校验的字段未采纳，不代表原文没有材料";
            case "STRUCTURED_OUTPUT_TARGET_EXCEEDED"->"字段数量超过建议范围，已接受字段仍保留，需核对完整性";
            default->"部分字段未完整提取，请核对原文";
        }).distinct().toList();
    }
    private static String structuredCoverageNotice(Map<String,Object> processing) {
        if(textOnlyScope(processing)) return "";
        int count=structuredLimitedCount(processing);
        if(count==0&&structuredLimitationReasons(processing).isEmpty()&&structuredLimitedPages(processing,Map.of()).isEmpty()) return "";
        return " "+(count>0?count+" 页":"部分页")+"字段提取范围有限，需核对原文。";
    }
    private static void appendStructuredCoverage(StringBuilder b,Map<String,Object> document) {
        Map<String,Object> processing=map(document.get("processing"));
        if(structuredCoverageNotice(processing).isBlank()) return;
        b.append("<details class=structured-coverage-limited");
        for(String key:List.of("contextLimitedPageCount","contextLimitedPages","contextCoverageLimitations"))
            if(processing.containsKey(key)) b.append(" data-").append(key).append("=\"").append(html(Objects.toString(processing.get(key),""))).append("\"");
        b.append("><summary>查看字段提取范围有限的原页与说明</summary><p>已提取的有效字段仍可查看，不代表该页全部字段均已提取。以下范围在检查开始时保存。</p><ul>");
        for(String reason:structuredLimitationReasons(processing)) b.append("<li>").append(html(reason)).append("</li>");
        List<Integer> pages=structuredLimitedPages(processing,document);
        String documentId=BidService.text(document,"documentId");
        for(int page:pages) {
            b.append("<li>");
            if(!documentId.isBlank()) b.append("<a href=\"/api/documents/").append(html(java.net.URLEncoder.encode(documentId,StandardCharsets.UTF_8)))
                .append("/pages/").append(page).append("/image\" target=\"_blank\" rel=\"noopener\">第 ").append(page).append(" 页原图</a>");
            else b.append("第 ").append(page).append(" 页");
            b.append("</li>");
        }
        b.append("</ul>");
        if(pages.isEmpty()) b.append("<p>未提供可定位的页码，请结合原文件核对。</p>");
        else b.append("<p>原图链接需在 BidCheck 服务中打开。</p>");
        b.append("</details>");
    }
    private static String documentScope(Map<String,Object> document) {
        String role=switch(BidService.text(document,"role")) { case "TENDER"->"招标文件"; case "BID"->"投标文件"; default->"文件"; };
        Map<String,Object> processing=map(document.get("processing"));
        String visual=textOnlyScope(processing)?" 检查范围：文字提取与全文上下文检查；未请求逐页图像、签章或视觉字段核验。表格文字按原页行列保留，不代表原图布局已核验（检查开始时保存的范围）。":processing.isEmpty()?"":" 图像分析："+processing.getOrDefault("contextWindows",0)+" 个窗口，"+processing.getOrDefault("contextPending",0)+" 个未完成，"+processing.getOrDefault("contextReviewRequired",0)+" 个需复核，"+processing.getOrDefault("contextNoFacts",0)+" 个窗口含未提取到可用事实的页面"+(processing.containsKey("contextNoFactPageCount")?"（"+processing.get("contextNoFactPageCount")+" 页）":"")+"（检查开始时保存的范围）。";
        return role+" · "+BidService.text(document,"name")+" · V"+Objects.toString(document.get("version"),"?")+"："+BidService.text(document,"notice")+visual+structuredCoverageNotice(processing);
    }
    private static boolean textOnlyScope(Map<String,Object> processing){
        return "TEXT_ONLY".equals(processing.get("processingMode"))&&"NOT_REQUESTED".equals(processing.get("visualAnalysisStatus"));
    }
    private static String parsingScope(Map<String,Object> context,Map<String,Object> presentation) {
        List<String> lines=new ArrayList<>();
        if(Boolean.FALSE.equals(map(context.get("retrieval")).get("denseCoverageComplete"))) lines.add("全文向量索引未完整建立，已降级检索；检索结果不能证明全文无遗漏。");
        for(Map<String,Object> document:maps(context.get("documents"))) {
            lines.add(documentScope(document));
            Map<String,Object> processing=map(document.get("processing"));
            List<Integer> limitedPages=structuredLimitedPages(processing,document);
            if(!limitedPages.isEmpty()) lines.add("字段提取范围有限的原页："+String.join("、",limitedPages.stream().map(page->"第 "+page+" 页").toList()));
            for(String reason:structuredLimitationReasons(processing)) lines.add("字段范围说明："+reason);
            if(document.containsKey("deferredLocations")) {
                for(Map<String,Object> location:maps(document.get("deferredLocations"))) lines.add("待按需补识别 · "+BidService.text(location,"label")+"："+BidService.text(location,"warning"));
                for(Map<String,Object> location:maps(document.get("failedLocations"))) lines.add("识别未完成 · "+BidService.text(location,"label")+"："+BidService.text(location,"warning"));
            } else for(Map<String,Object> location:maps(document.get("incompleteLocations"))) lines.add(BidService.text(location,"label")+"："+BidService.text(location,"warning"));
            for(Map<String,Object> location:maps(document.get("structureIncompleteLocations"))) lines.add("布局待复核 · "+BidService.text(location,"label")+"："+BidService.text(location,"warning"));
            if(document.get("unlocatedStructureWarnings") instanceof List<?> warnings) for(Object warning:warnings) lines.add("布局提示未定位："+warning);
        }
        String note=presented(presentation,"note","");if(!note.isBlank()) lines.add(note);
        if(Boolean.TRUE.equals(presentation.get("legacyClarified"))) lines.add("解析提示已按范围澄清，历史结论未修改");
        return String.join("\n",lines);
    }
    private static void appendOcrContext(StringBuilder b,Map<String,Object> context) {
        List<Map<String,Object>> documents=maps(context.get("documents"));if(documents.isEmpty()) return;
        b.append("<section class=ocr-context><h2>本次文档解析范围</h2><p>以下范围对应本次报告使用的文件版本。文字转录状态与检查结论分别展示；识别文字仍需对照原图核对。</p>");
        if(Boolean.FALSE.equals(map(context.get("retrieval")).get("denseCoverageComplete"))) b.append("<p>全文向量索引未完整建立，已降级检索；检索结果不能证明全文无遗漏。</p>");
        for(Map<String,Object> document:documents) {
            b.append("<p>").append(html(documentScope(document))).append("</p>");
            appendStructuredCoverage(b,document);
            if(document.containsKey("deferredLocations")) {
                appendCoverageLocations(b,maps(document.get("deferredLocations")),"待按需补识别位置");
                appendCoverageLocations(b,maps(document.get("failedLocations")),"未完成位置");
            } else appendCoverageLocations(b,maps(document.get("incompleteLocations")),"未完成位置");
            appendCoverageLocations(b,maps(document.get("structureIncompleteLocations")),"文字或表格布局待复核位置");
            if(document.get("unlocatedStructureWarnings") instanceof List<?> warnings && !warnings.isEmpty()) {
                b.append("<details><summary>布局提示未定位</summary><ul>");
                for(Object warning:warnings) b.append("<li>").append(html(Objects.toString(warning,""))).append("</li>");
                b.append("</ul></details>");
            }
        }
        b.append("</section>");
    }
    private static boolean deferredOnly(String warning) {
        return warning.contains("[OCR_DEFERRED]")&&!warning.replaceAll("\\[OCR_DEFERRED\\]\\s*\\[OCR未完成\\]","").contains("[OCR未完成]");
    }
    private static void appendCoverageLocations(StringBuilder b,List<Map<String,Object>> locations,String label) {
        if(locations.isEmpty()) return;
        b.append("<details><summary>查看 ").append(locations.size()).append(" 处").append(label).append("</summary><ul>");
        for(Map<String,Object> location:locations) b.append("<li>").append(html(BidService.text(location,"label"))).append("：").append(html(BidService.text(location,"warning"))).append("</li>");
        b.append("</ul></details>");
    }
    private record EvidenceLocation(String documentId,int page,String locationType,String quote) {}
    private static EvidenceLocation location(Evidence evidence) { return new EvidenceLocation(evidence.documentId(),evidence.page(),evidence.locationType(),evidence.quote()); }
    private static Evidence tenderSource(Evidence source,String tenderId,Set<String> tenderIds) {
        return source!=null&&tenderIds.contains(source.documentId())&&(tenderId.isBlank()||tenderId.equals(source.documentId()))?source:null;
    }
    private static List<Evidence> additionalTender(Evaluation evaluation,Evidence source,String tenderId,Set<String> tenderIds) {
        if(tenderId.isBlank()||!tenderIds.contains(tenderId)) return List.of();
        Set<EvidenceLocation> seen=new HashSet<>();if(source!=null) seen.add(location(source));
        return evaluation.evidences().stream().filter(Objects::nonNull).filter(v->tenderId.equals(v.documentId())&&seen.add(location(v))).toList();
    }
    private static List<Evidence> bidEvidence(Evaluation evaluation,String bidId) {
        return bidId.isBlank()?List.of():evaluation.evidences().stream().filter(Objects::nonNull).filter(v->bidId.equals(v.documentId())).toList();
    }
    private static String joinedEvidence(List<Evidence> evidence) { return String.join(" | ",evidence.stream().map(ReportService::evidence).toList()); }
    private static Report report(String text,String type) { return new Report(text.getBytes(StandardCharsets.UTF_8),type); }
    private static String safe(String s) { return s==null?"":s; }
    public static String html(String s) { return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
    private static String csv(String s) { s=safe(s); if(s.matches("(?s)^[\\s]*[=+@-].*")) s="'"+s; return "\""+s.replace("\"","\"\"")+"\""; }
    private static String evidence(Evidence e) { return e==null?"":e.documentName()+" · "+("PAGE".equals(e.locationType())?"第"+e.page()+"页":"第"+e.page()+"内容块")+" · "+e.quote(); }
    private static String label(String status) { return switch(status) { case "PASS"->"通过"; case "FAIL"->"不符合"; case "WARNING"->"风险提示"; case "UNDETERMINED"->"待确认"; case "NOT_APPLICABLE"->"不适用"; default->status; }; }
}
