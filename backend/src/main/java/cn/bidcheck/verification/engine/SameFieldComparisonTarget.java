package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A reference to another document's information is a comparison operand, never its literal value. */
final class SameFieldComparisonTarget {
    private static final Pattern RELATION=Pattern.compile("(?:与|和)([^，,。；;（）()\\n]{1,80}?)(?:应|须|必须|需)?(?:保持)?(?:完全)?一致");
    private static final String ENTERPRISE_DOCUMENT="(?:营业执照|资质证书|资质证明文件|登记证书|法人证书|其他组织证明(?:文件)?)";
    private static final Pattern ENTERPRISE_DOCUMENTS=Pattern.compile("^"+ENTERPRISE_DOCUMENT+"(?:(?:、|或|及|和)"+ENTERPRISE_DOCUMENT+")*(?:(?:中|上)?(?:所载|载明|记载|登记|的)?(?:企业名称|单位名称|法定名称|名称))?$");
    private static final Pattern DOCUMENT_FIELD=Pattern.compile("^(?:该|此|原|本|对应的?|同一|所提供的?|提交的?|有效的?)?(?:证明文件|证书|证照|查询截图|查询结果|招标文件|投标文件|原件|扫描件|复印件)(?:(?:中|上)?(?:所载|载明|记载|注明|登记|的)?(?:[‘“\\\"])?(?:信息|内容|记载|字段|结果|(?:证书|注册|执业|统一社会信用代码)?编号|执业印章号|证号|名称|姓名|(?:注册|聘用|所属)?单位(?:名称)?|持有人(?:姓名)?|有效期(?:限|起止日期|截止日期|开始日期|结束日期)?|(?:签署|发证|截止|生效|到期|开始|结束)?日期|(?:含税|不含税|总)?金额|报价|数量)(?:[’”\\\"])?|中的?(?:信息|内容|编号|名称|姓名|注册单位|有效期|日期|金额))?$");
    private SameFieldComparisonTarget() {}

    static String reference(Rule rule) {
        if(!"SAME_FIELD".equals(rule.predicate()))return "";
        Matcher match=RELATION.matcher(clean(rule.sourceQuote()));
        while(match.find()) {
            String target=match.group(1).replaceFirst("(?:应|须|必须|需|保持|完全)+$","");
            // Anchored document nouns plus field labels distinguish a source reference from an
            // actual company such as “证书科技有限公司”, or a quoted literal project/name/value.
            if(DOCUMENT_FIELD.matcher(target).matches()||ENTERPRISE_DOCUMENTS.matcher(target).matches()||cn.bidcheck.compat.Java8Api.setOf("登记信息", "注册信息").contains(target))return target;
        }
        return "";
    }

    static boolean enterpriseDocuments(String reference){return ENTERPRISE_DOCUMENTS.matcher(reference).matches();}

    static Rule normalize(Rule rule) {
        if(!"SAME_FIELD".equals(rule.predicate()))return rule;
        Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());
        parameters.remove("comparisonOperandKind");parameters.remove("comparisonTargetReference");
        String target=reference(rule);
        if(!cn.bidcheck.compat.Java8Api.isBlank(target)) {
            parameters.put("comparisonOperandKind","SOURCE_REFERENCE");
            parameters.put("comparisonTargetReference",target);
        }
        return new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),
            cn.bidcheck.compat.Java8Api.isBlank(target)?rule.expected():"",rule.unit(),parameters,rule.sourceQuote(),rule.logic(),rule.children());
    }

    static Decision unresolved(Rule rule,List<Fact> matched,RuleEvaluator evaluator) {
        String target=reference(rule);
        String reason="本项要求跨来源信息一致，尚未建立“"+target+"”对应原文、逐字段映射与完整比较范围；该引用名称不是待比较的实际值";
        return evaluator.result(rule,"UNDETERMINED",reason,matched,
            cn.bidcheck.compat.Java8Api.listOf(new Gap("REQUIREMENT_COMPARISON_TARGET_UNRESOLVED",reason,
                "由系统明确比较两端来源、对应字段及覆盖范围；不要求用户补交已经存在的材料","REQUIREMENT_MAPPING_CHANGED")),
            linked("comparisonOperandKind","SOURCE_REFERENCE","comparisonTargetReference",target,
                "missingBindings",cn.bidcheck.compat.Java8Api.listOf("RIGHT_SOURCE", "FIELD_CORRESPONDENCE", "COMPARISON_FIELD_SET"),
                "literalTargetComparisonRejected",true,"materialMissingProven",false));
    }
}
