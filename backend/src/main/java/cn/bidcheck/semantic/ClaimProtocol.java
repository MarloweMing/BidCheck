package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;

/** Normalises assertion metadata, never invents evidence or upgrades the model judgment. */
final class ClaimProtocol {
    static final String VERSION="claim-protocol-2-written-signing-date-scope";
    private static final Pattern SIGNING_DATE=Pattern.compile("(?:签署|落款|签字)(?:日期|时间)");
    private ClaimProtocol() {}

    static List<Map<String,Object>> normalize(JsonNode answer,Map<String,Evidence> available){
        List<Map<String,Object>> changes=new ArrayList<>();int index=0;
        for(JsonNode raw:answer.path("claims")){
            if(!(raw instanceof ObjectNode)){index++;continue;}
            for(String enumField:cn.bidcheck.compat.Java8Api.listOf("kind", "scope")){
                String original=((ObjectNode) (raw)).path(enumField).asText(),canonical=cn.bidcheck.compat.Java8Api.strip(original).toUpperCase(Locale.ROOT);
                Set<String> allowed="kind".equals(enumField)?cn.bidcheck.compat.Java8Api.setOf("TEXT", "MATERIAL", "IDENTITY", "AMOUNT", "DURATION", "DATE", "RELATION", "QUALITY", "SIGNATURE", "FIELD_COMPARISON")
                    :cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "TENDER_REFERENCE");
                if(!original.equals(canonical)&&allowed.contains(canonical)){
                    ((ObjectNode) (raw)).put(enumField,canonical);changes.add(cn.bidcheck.compat.Java8Api.mapOf("claimIndex", index, "field", enumField, "from", original, "to", canonical, "basis", "EXACT_ENUM_NORMALIZATION"));
                }
            }
            String kind=((ObjectNode) (raw)).path("kind").asText(),text=((ObjectNode) (raw)).path("text").asText();
            // A missing date label can be recovered from an unambiguous asserted label;
            // dateValueGrounded must still prove that field and value against the source.
            if("DATE".equals(kind)&&cn.bidcheck.compat.Java8Api.isBlank(((ObjectNode) (raw)).path("validityField").asText())){
                String explicit=((ObjectNode) (raw)).path("field").asText();
                if(!cn.bidcheck.compat.Java8Api.isBlank(explicit)){
                    ((ObjectNode) (raw)).put("validityField",explicit);
                    changes.add(cn.bidcheck.compat.Java8Api.mapOf("claimIndex", index, "field", "validityField", "from", "", "to", explicit, "basis", "EXPLICIT_DATE_FIELD_ALIAS"));
                }
            }
            if("DATE".equals(kind)&&cn.bidcheck.compat.Java8Api.isBlank(((ObjectNode) (raw)).path("validityField").asText())){
                java.util.regex.Matcher labels=SIGNING_DATE.matcher(text);Set<String> found=new LinkedHashSet<>();
                while(labels.find())found.add(labels.group());
                if(found.size()==1){String field=found.iterator().next();((ObjectNode) (raw)).put("validityField",field);
                    changes.add(cn.bidcheck.compat.Java8Api.mapOf("claimIndex", index, "field", "validityField", "from", "", "to", field, "basis", "EXPLICIT_ASSERTED_FIELD"));}
            }
            if(ClaimGrounding.navigationStatement(((ObjectNode) (raw)))&&allSources(((ObjectNode) (raw)),available).size()>0
                    &&allSources(((ObjectNode) (raw)),available).stream().allMatch(e->ClaimGrounding.indexOnly(e.quote()))){
                ((ObjectNode) (raw)).put("proofPurpose","NAVIGATION_ONLY");
                if("MATERIAL".equals(kind)){
                    ((ObjectNode) (raw)).put("kind","TEXT");changes.add(cn.bidcheck.compat.Java8Api.mapOf("claimIndex", index, "field", "kind", "from", kind, "to", "TEXT", "basis", "SOURCE_BOUND_NAVIGATION_ONLY"));
                }
            }
            index++;
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(changes);
    }

    /** Only call after all semantic/source/completeness guards; this scope is narrower than PASS. */
    static Map<String,Object> proof(JsonNode answer,Map<String,Evidence> available){
        if(!"PASS".equals(answer.path("status").asText()))return cn.bidcheck.compat.Java8Api.mapOf();
        List<JsonNode> current=new ArrayList<>();for(JsonNode c:answer.path("claims"))if("CURRENT_PROJECT".equals(c.path("scope").asText()))current.add(c);
        if(current.isEmpty()||current.stream().anyMatch(c->allSources(c,available).isEmpty()))return cn.bidcheck.compat.Java8Api.mapOf();
        // Do not infer factual verification from generic TEXT or from a model-supplied label.
        // Explicit response/declaration content and document presence are written proof only.
        boolean written=current.stream().allMatch(c->writtenClaim(c,available));
        if(!written)return cn.bidcheck.compat.Java8Api.mapOf();
        Set<String> ids=new LinkedHashSet<>();current.forEach(c->c.path("evidenceIds").forEach(id->ids.add(id.asText())));
        return cn.bidcheck.compat.Java8Api.mapOf("version", VERSION, "validated", true, "scope", "WRITTEN_RESPONSE", "evidenceIds", cn.bidcheck.compat.Java8Api.listCopyOf(ids), "claimCount", current.size(), "boundary", "仅证明所引文件中的书面响应，不代表已取得外部查询结果或完成实际资格事实核验。");
    }
    private static boolean writtenClaim(JsonNode c,Map<String,Evidence> available){
        String kind=c.path("kind").asText(),text=c.path("text").asText(),value=c.path("value").asText();
        List<Evidence> sources=allSources(c,available);
        if("NAVIGATION_ONLY".equals(c.path("proofPurpose").asText()))return true;
        if("MATERIAL".equals(kind))return sources.stream().anyMatch(e->!ClaimGrounding.indexOnly(e.quote()));
        if("RELATION".equals(kind))return cn.bidcheck.compat.Java8Api.setOf("ISSUED_COMMITMENT", "DECLARATION_BY", "ISSUED").contains(c.path("relation").asText())
            &&sources.stream().anyMatch(e->e.quote().matches("(?s).*(?:我方承诺|我公司承诺|我方声明|特此声明).*"));
        if("DATE".equals(kind))return SIGNING_DATE.matcher(c.path("validityField").asText()).matches()
            ||cn.bidcheck.compat.Java8Api.setOf("日期", "时间").contains(c.path("validityField").asText())&&SIGNING_DATE.matcher(text).find();
        if(!"TEXT".equals(kind)||cn.bidcheck.compat.Java8Api.isBlank(value)||sources.stream().noneMatch(e->compact(e.quote()).contains(compact(value))))return false;
        return text.matches("(?s).*(?:承诺|声明|正文|填报|填写|文字响应|书面响应|落款|标题|目录|索引).*" )
            &&!text.matches("(?s).*(?:实际状态|经官方核验|经平台核验|查询确认|联网核实|已核实资格).*" );
    }

    static Map<String,Object> gap(JsonNode answer,Map<String,Evidence> available){
        if(!"UNDETERMINED".equals(answer.path("status").asText()))return cn.bidcheck.compat.Java8Api.mapOf();
        JsonNode gap=answer.path("gap");String kind=gap.path("kind").asText();
        if(!cn.bidcheck.compat.Java8Api.setOf("SYSTEM_REQUIREMENT", "SYSTEM_RETRIEVAL", "SYSTEM_RELATION", "EXTERNAL_INPUT").contains(kind))return cn.bidcheck.compat.Java8Api.mapOf();
        List<String> ids=new ArrayList<>();if(!gap.path("evidenceIds").isArray())return cn.bidcheck.compat.Java8Api.mapOf();
        for(JsonNode id:gap.path("evidenceIds")){if(!id.isTextual()||!available.containsKey(id.asText()))return cn.bidcheck.compat.Java8Api.mapOf();ids.add(id.asText());}
        String reason=gap.path("reason").asText();if(cn.bidcheck.compat.Java8Api.isBlank(reason)||reason.length()>800||ids.isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        if("EXTERNAL_INPUT".equals(kind)){
            // A model suggestion to obtain a platform record cannot itself create an external task.
            boolean sourceRequired=ids.stream().filter(id->!id.startsWith("C")).map(available::get).anyMatch(e->
                e.quote().matches("(?s).*(?:现场.{0,20}(?:核验|查询|评审|判定)|(?:平台|系统|官网|官方网站).{0,25}(?:日志|记录|核验结果|查询结果)|以.{0,25}(?:核验结果|查询结果|现场评审).{0,10}为准).*"));
            if(!sourceRequired)return cn.bidcheck.compat.Java8Api.mapOf();
        }
        return cn.bidcheck.compat.Java8Api.mapOf("version", VERSION, "kind", kind, "reason", reason, "sourceEvidenceIds", ids, "validated", true, "materialAbsenceProven", false);
    }
    private static List<Evidence> allSources(JsonNode claim,Map<String,Evidence> available){
        if(!claim.path("evidenceIds").isArray()||claim.path("evidenceIds").isEmpty())return cn.bidcheck.compat.Java8Api.listOf();List<Evidence> list=new ArrayList<>();
        for(JsonNode id:claim.path("evidenceIds")){Evidence e=available.get(id.asText());if(e==null)return cn.bidcheck.compat.Java8Api.listOf();list.add(e);}return list;
    }
    private static String compact(String text){return Objects.toString(text,"").replaceAll("\\s+","");}
}
