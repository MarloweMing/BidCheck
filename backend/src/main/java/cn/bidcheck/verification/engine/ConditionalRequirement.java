package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** An inapplicable branch is a source-bound consequence, never a substitute for unknown evidence. */
final class ConditionalRequirement {
    private ConditionalRequirement(){}
    static Map<String,Object> inactiveBranch(JsonNode parent,int index,String requirement){
        if(index!=2||!parent.path("logic").asText().equals("CONDITIONAL")||parent.path("children").size()!=3)return cn.bidcheck.compat.Java8Api.mapOf();
        String quote=parent.path("sourceQuote").asText(),condition=parent.path("children").get(0).path("sourceQuote").asText();
        String branch=parent.path("children").get(2).path("sourceQuote").asText();
        if(cn.bidcheck.compat.Java8Api.isBlank(quote)||cn.bidcheck.compat.Java8Api.isBlank(condition)||cn.bidcheck.compat.Java8Api.isBlank(branch)||!requirement.contains(quote)||!quote.contains(condition)||!quote.contains(branch))return cn.bidcheck.compat.Java8Api.mapOf();
        // Unknown applicability never selects this branch; its condition must yield grounded FAIL.
        String clause=cn.bidcheck.compat.Java8Api.strip(quote).replaceAll("[。；;]$", "");
        // Only a single restrictive clause can imply no obligation outside that condition.
        // A paragraph containing a separate false/unconditional duty must compile that duty.
        if(clause.matches("(?s).*[。；;\\n].*")||clause.matches("(?s).*(?:否则|非联合体|不属于|未变更|无变更|其他情况下).*(?:须|应|需|提供|提交|附).*"))return cn.bidcheck.compat.Java8Api.mapOf();
        boolean conditional=clause.matches("^(?:若|如果|如为|若为|当).{1,80}?(?:时|的)?[，,]?(?:须|应|需|提供|提交|附).+")
            ||clause.matches("^(?:注册类资质|联合体投标|委托代理人签署)(?:时)?(?:须|应|需).+")
            ||clause.matches("^(?:投标人|企业|单位)?名称如有变更(?:须|应|需|附).+");
        if(!conditional)return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("version","source-conditional-1","parentId",parent.path("id").asText(),"conditionId",parent.path("children").get(0).path("id").asText(),
            "sourceQuote",quote,"conditionQuote",condition,"selectedWhen","FAIL","branchIndex",2);
    }
    static boolean verified(Rule rule){
        if(!(rule.parameters().get("inactiveBranchProof") instanceof Map<?,?>))return false;
        return "source-conditional-1".equals(((Map<?,?>) (rule.parameters().get("inactiveBranchProof"))).get("version"))&&"FAIL".equals(((Map<?,?>) (rule.parameters().get("inactiveBranchProof"))).get("selectedWhen"))&&Integer.valueOf(2).equals(((Map<?,?>) (rule.parameters().get("inactiveBranchProof"))).get("branchIndex"))
            &&!cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(((Map<?,?>) (rule.parameters().get("inactiveBranchProof"))).get("conditionId"),""))&&Objects.toString(((Map<?,?>) (rule.parameters().get("inactiveBranchProof"))).get("sourceQuote"),"").contains(rule.sourceQuote());
    }
}
