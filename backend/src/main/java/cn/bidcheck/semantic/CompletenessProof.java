package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;

/** Completeness is a closed set of obligations with body evidence, not a model's aggregate assertion. */
final class CompletenessProof {
    private static final Pattern ENUMERATION=Pattern.compile("([^。；\\n\\t:：]{2,220}、[^。；\\n\\t:：]{2,220}?)([2-9]|1[0-9]|20)项(?:内容|要求|材料|文件)");
    private static final Pattern COLLECTION=Pattern.compile("(?:全部|所有).{0,20}(?:文件|材料|承诺|章节)|等(?:必须|必需|全部)?的?(?:承诺)?(?:文件|材料)|完整性|内容齐全");
    private static final Pattern MATERIAL_TITLE=Pattern.compile("(?m)^\\s*(?:[#*★]+\\s*)?(?:[（(]?\\d+[）).、]\\s*)?([\\p{IsHan}A-Za-z0-9]{2,32}(?:承诺书|承诺|授权书|声明书))(?=\\s*(?:[.．·…]{2}|$))");
    private CompletenessProof() { }

    /** A response declaration does not complete a collection whose substantive checks are explicitly deferred. */
    static String deferredSubstantiveCollection(Checkpoint checkpoint,JsonNode answer) {
        String requirement=Objects.toString(checkpoint.expected(),"");
        boolean collection=requirement.matches("(?s).*(?:标识.{0,12}★|标注.{0,20}不可偏离项|(?:全部|所有|各项)实质性条款|均为实质性条款).*" );
        if(!collection)return "";
        StringBuilder assertions=new StringBuilder(answer.path("reason").asText()).append('。').append(answer.path("suggestion").asText());
        for(JsonNode claim:answer.path("claims"))assertions.append('。').append(claim.path("text").asText());
        for(String sentence:assertions.toString().split("[。；;！？\\n]")) {
            Matcher deferred=Pattern.compile("仍需|尚需|还需|需进一步|需结合|建议|待|后续|评标时").matcher(sentence);
            while(deferred.find()) {
                String pending=sentence.substring(deferred.start());
                boolean visualOnly=pending.matches("(?s).*(?:签章|签字|盖章|印章|原图|原件).*" )
                    &&!pending.matches("(?s).*(?:正文|实质|偏差|内容|具体要求).*" )
                    &&!pending.matches("(?s).*(?:核验|核对|核实|审查|检查|确认|验证).{0,8}[，,].*" );
                if(!visualOnly&&sentence.matches("(?s).*(?:条款|正文|实质性|实质满足|实质偏差|具体要求).*" )
                    &&pending.matches("(?s).*(?:核验|核对|核实|审查|检查|确认|验证).*"))
                    return "投标人的无偏离声明、索引或通用承诺仅证明其响应表述；模型仍明确将具体实质性条款的正文核验留待后续，不能同时认定整个条款集合通过。";
            }
        }
        return "";
    }

    static Map<String,Object> contract(Checkpoint checkpoint) {
        String requirement=Objects.toString(checkpoint.expected(),"");
        Matcher enumeration=ENUMERATION.matcher(requirement);
        if(enumeration.find()) {
            String list=enumeration.group(1).replaceFirst("^.*(?:对本项目的|包括|包含|内容为)","");
            List<String> items=cn.bidcheck.compat.Java8Api.toList(Arrays.stream(list.split("、")).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
            boolean closed=items.size()==Integer.parseInt(enumeration.group(2))&&new HashSet<>(items).size()==items.size();
            return cn.bidcheck.compat.Java8Api.mapOf("required", true, "inventoryResolved", closed, "items", items, "sourceQuote", enumeration.group(), "boundary", "每个清单项需要对应本项目正文。目录、同意承诺、章节标题或其他项正文不能代替；不能据未召回判缺失。");
        }
        if(COLLECTION.matcher(requirement).find())return cn.bidcheck.compat.Java8Api.mapOf("required", true, "inventoryResolved", false, "items", cn.bidcheck.compat.Java8Api.listOf(), "sourceQuote", requirement, "boundary", "本项要求完整集合；须先解析明确引用的全部清单，再逐项核对正文。模型不得自行宣称集合完整。");
        return cn.bidcheck.compat.Java8Api.mapOf("required", false);
    }

    static Map<String,Object> contract(Checkpoint checkpoint,Document tender) {
        Map<String,Object> base=contract(checkpoint);
        if(!Boolean.TRUE.equals(base.get("required")))return base;
        if(!Boolean.TRUE.equals(base.get("inventoryResolved"))) {
            Map<String,Object> expanded=TenderChecklistResolver.resolve(checkpoint,tender);
            if(!expanded.isEmpty()){Map<String,Object> out=new LinkedHashMap<>(expanded);out.put("requirementText",Objects.toString(checkpoint.expected(),""));return out;}
        }
        Map<String,Object> out=new LinkedHashMap<>(base);
        out.put("requirementText",Objects.toString(checkpoint.expected(),""));
        out.put("inventoryId",TenderChecklistResolver.id(checkpoint.id()+":"+checkpoint.expected()+":"+(tender==null?"":tender.sha256())));
        out.put("inventorySources",checkpoint.source()==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(checkpoint.source()));
        out.put("unresolvedReferences",cn.bidcheck.compat.Java8Api.listOf());
        return out;
    }

    static Map<String,Object> assess(Checkpoint checkpoint,JsonNode answer,Map<String,Evidence> available,Document bid) {
        Map<String,Object> inventory=contract(checkpoint,null);
        return assess(inventory,answer,available,bid,ChecklistBodyRecall.recall(inventory,bid));
    }
    static Map<String,Object> assess(Map<String,Object> inventory,JsonNode answer,Map<String,Evidence> available,Document bid,ChecklistBodyRecall.Recall recall) {
        if(!Boolean.TRUE.equals(inventory.get("required")))return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> report=new LinkedHashMap<>(inventory);
        report.put("assessmentStage",answer.path("claims").isArray()?"BODY_EVIDENCE_ASSESSED":"AWAITING_MODEL_CLAIMS");
        report.put("proofScope","BODY_PRESENCE_ONLY");
        List<String> missing=new ArrayList<>();List<Map<String,Object>> verified=new ArrayList<>(),results=new ArrayList<>();
        List<?> requirements=inventory.get("itemRequirements") instanceof List<?>?((List<?>) (inventory.get("itemRequirements"))):cn.bidcheck.compat.Java8Api.listOf();
        for(ChecklistBodyRecall.Item item:recall.items()) {
            Map<?,?> requirement=requirements.stream().filter(v->v instanceof Map<?,?>&&item.item().equals(((Map<?,?>) (v)).get("item"))).map(v->(Map<?,?>)v).findFirst().orElse(cn.bidcheck.compat.Java8Api.mapOf());
            String applicability=Objects.toString(requirement.get("applicability"),"REQUIRED");
            List<String> ids=new ArrayList<>();
            for(JsonNode claim:answer.path("claims")) {
                String text=claim.path("text").asText();
                if(!cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "TEXT").contains(claim.path("kind").asText())
                    ||!"CURRENT_PROJECT".equals(claim.path("scope").asText())||!text.contains(item.item())
                    ||text.matches("(?s).*(?:未发现|未提供|缺少|尚无|待补充|未确认|未能|无法|不能确认|尚未|待核|待确认|未完成|存疑|需核).*"))continue;
                for(JsonNode id:claim.path("evidenceIds"))if(id.asText().startsWith("C")&&available.containsKey(id.asText()))ids.add(id.asText());
            }
            List<Evidence> used=cn.bidcheck.compat.Java8Api.toList(ids.stream().distinct().map(available::get));
            List<Evidence> citedCopy=item.equivalentCopies().stream().filter(copy->!copy.isEmpty()
                &&copy.stream().allMatch(required->used.stream().anyMatch(actual->covers(actual,required)))).findFirst().orElse(cn.bidcheck.compat.Java8Api.listOf());
            boolean cited=!citedCopy.isEmpty();
            boolean complete=item.bodyLocated()&&item.currentScope()&&cited&&!item.evidences().isEmpty()&&applicability.equals("REQUIRED");
            String reason=complete?"该项独立正文已定位并被逐项引用；仅证明文字材料存在，不替代全部内容或签章核验":applicability.equals("CONDITIONAL")?"提交条件尚未确认："+Objects.toString(requirement.get("condition"),"")
                :!item.bodyLocated()||!item.currentScope()?item.reason():"已找到正文位置，但本次判断尚未逐项引用完整标题与正文证据链";
            Map<String,Object> result=new LinkedHashMap<>();result.put("id",requirement.get("id")==null?TenderChecklistResolver.id(item.item()):requirement.get("id"));result.put("item",item.item());
            result.put("status",complete?"VERIFIED":applicability.equals("CONDITIONAL")?"REQUIREMENT_UNRESOLVED":"EVIDENCE_PENDING");
            result.put("requirementSources",requirement.get("requirementSources")==null?inventory.getOrDefault("inventorySources",cn.bidcheck.compat.Java8Api.listOf()):requirement.get("requirementSources"));
            result.put("evidences",complete?citedCopy:item.evidences());result.put("reason",reason);result.put("excerpted",item.excerpted());results.add(result);
            if(complete)verified.add(cn.bidcheck.compat.Java8Api.mapOf("item", item.item(), "evidenceIds", cn.bidcheck.compat.Java8Api.toList(ids.stream().distinct()), "evidences", citedCopy));else missing.add(item.item());
        }
        String explanation=answer.path("reason").asText()+"。"+answer.path("suggestion").asText();
        List<Map<String,Object>> contentPending=deferredContentObligations(Objects.toString(inventory.get("requirementText"),""),answer);
        boolean coreDeferred=!contentPending.isEmpty()||explanation.matches("(?s).*(?:仍需|尚需|建议|待|补充).{0,20}(?:核对|核实|检查|确认|补核|复核).{0,45}(?:完整清单|全部.{0,10}(?:材料|文件)|逐一|第[一二三四五六七八九十0-9]+章|正文).*" );
        boolean complete=Boolean.TRUE.equals(inventory.get("inventoryResolved"))&&!recall.items().isEmpty()&&missing.isEmpty()&&!coreDeferred;
        report.put("verified",verified);report.put("missingEvidenceFor",missing);report.put("complete",complete);report.put("coreRequirementDeferred",coreDeferred);report.put("itemResults",results);report.put("recall",recall.audit());
        report.put("deferredContentObligations",contentPending);
        report.put("reason",complete?"明确清单中的每份材料已有独立正文及逐项引用；其他原文与判断保护仍分别执行。":!Boolean.TRUE.equals(inventory.get("inventoryResolved"))
            ?"完整要求清单尚有未解析引用，不能用部分文件或目录证明全部齐全。":!contentPending.isEmpty()?"已保留 "+verified.size()+" 项独立正文证明；条款要求的内容质量判断未完成，不能仅凭齐全判定整项通过。":coreDeferred?"本项必需的正文或内容判断仍待核对，不能同时标记整体通过。"
            :"以下要求尚无完整的逐项正文证明："+String.join("、",missing)+"。未召回不表示原文件缺失。");
        return report;
    }

    /** A future quality assessment only blocks a quality obligation explicitly present in this checkpoint. */
    static List<Map<String,Object>> deferredContentObligations(String requirement,JsonNode answer) {
        String[][] topics={
            {"TARGETEDNESS","针对性","针对性(?:强|高|充分)|(?:应|须|必须|要求|具有|具备).{0,12}针对性","针对性|针对需求"},
            {"FEASIBILITY","可行性","合理可行|切实可行|可行性(?:强|高|充分)|(?:应|须|必须|要求).{0,12}可行|(?:具有|具备).{0,8}可行性","可行性|合理可行|切实可行"},
            {"REASONABLENESS","合理性","合理可行|合理性(?:强|高|充分)|(?:应|须|必须|要求).{0,12}合理","合理性|合理可行"},
            {"NEEDS_ALIGNMENT","需求满足","(?:完全|全面|充分|应|须|必须).{0,8}满足.{0,8}(?:项目|采购|业务|技术)?需求","需求满足|满足.{0,8}需求|需求匹配|需求适配"}
        };
        StringBuilder explanation=new StringBuilder(answer.path("reason").asText()).append('。').append(answer.path("suggestion").asText());
        for(JsonNode claim:answer.path("claims"))explanation.append('。').append(claim.path("text").asText());
        List<Map<String,Object>> pending=new ArrayList<>();
        for(String[] topic:topics) {
            String requiredClause=Arrays.stream(requirement.split("[。；;\\n]")).filter(clause->Pattern.compile(topic[2]).matcher(clause).find())
                .filter(clause->!clause.matches("(?s).*(?:可选|仅供参考|不要求|无需|不必).{0,20}(?:针对性|合理|可行|需求).*" )).findFirst().orElse("");
            if(requiredClause.isEmpty())continue;
            for(String sentence:explanation.toString().split("[。；;！？\\n]")) {
                if(!Pattern.compile(topic[3]).matcher(sentence).find())continue;
                if(sentence.matches("(?s).*(?:可选优化|仅供参考|不影响本项结论|如需进一步优化).*"))continue;
                if(sentence.matches("(?s).*(?:保留|保存|归档|整理).{0,30}(?:评估|评审|评价|核验|判断)(?:记录|过程|依据|资料|报告).*"))continue;
                boolean deferred=sentence.matches("(?s).*(?:后续|评标时|仍需|尚需|有待|待|进一步|建议).{0,55}(?:评估|评判|评审|评价|核验|核对|核实|检查|确认|验证|分析|判断).*" );
                if(deferred){pending.add(cn.bidcheck.compat.Java8Api.mapOf("type", topic[0], "label", topic[1], "status", "DEFERRED", "requirementQuote", cn.bidcheck.compat.Java8Api.strip(requiredClause), "reason", cn.bidcheck.compat.Java8Api.strip(sentence)));break;}
            }
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(pending);
    }

    /** Bidder-added titles are navigation, never additional tender obligations. */
    static List<String> extraRequiredMaterials(Map<String,Object> inventory,JsonNode answer,Map<String,Evidence> available) {
        if(!Boolean.TRUE.equals(inventory.get("required"))||!Boolean.TRUE.equals(inventory.get("inventoryResolved")))return cn.bidcheck.compat.Java8Api.listOf();
        List<?> required=inventory.get("items") instanceof List<?>?((List<?>) (inventory.get("items"))):cn.bidcheck.compat.Java8Api.listOf();
        Set<String> titles=new LinkedHashSet<>();
        for(java.util.Map.Entry<java.lang.String, cn.bidcheck.domain.Domain.Evidence> entry:available.entrySet())if(entry.getKey().startsWith("C")) {
            Matcher matcher=MATERIAL_TITLE.matcher(Objects.toString(entry.getValue().quote(),""));
            while(matcher.find())titles.add(matcher.group(1));
        }
        titles.removeIf(title->required.stream().anyMatch(item->materialName(title).equals(materialName(item.toString()))));
        StringBuilder assertions=new StringBuilder(answer.path("reason").asText()).append('。').append(answer.path("suggestion").asText());
        for(JsonNode claim:answer.path("claims"))if("CURRENT_PROJECT".equals(claim.path("scope").asText()))assertions.append('。').append(claim.path("text").asText());
        Set<String> added=new LinkedHashSet<>();
        for(String sentence:assertions.toString().split("[。；;！？\\n]")) {
            if(sentence.matches("(?s).*(?:无需|不需|不必|不用|不应|不要求).{0,12}(?:补交|补充|提交|提供).*|.*(?:不是|不属于|不纳入).{0,10}(?:必需|必须|必检|清单).*"))continue;
            if(sentence.matches("(?s).*(?:并非|不是|不等于)(?:未提供|未提交|缺少|缺失).*|.*(?:不能|不得|不应).{0,8}(?:据|因).{0,35}未召回.{0,40}(?:认定|判断|说明).{0,10}(?:缺少|缺失|未提交).*"))continue;
            if(!sentence.matches("(?s).*(?:未召回|未提供|未提交|缺少|缺失|补充提供|补交|必须提交|必须提供|待核实是否提交|无法确认是否提交).*"))continue;
            for(String title:titles)if(sentence.contains(title))added.add(title);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(added);
    }
    private static String materialName(String title){return title.replaceAll("[\\s《》“”‘’]","").replaceFirst("书$","");}
    private static boolean covers(Evidence actual,Evidence required) {
        return Objects.equals(actual.documentId(),required.documentId())&&actual.page()==required.page()&&Objects.equals(actual.locationType(),required.locationType())
            &&Objects.toString(actual.quote(),"").contains(required.quote());
    }

}
