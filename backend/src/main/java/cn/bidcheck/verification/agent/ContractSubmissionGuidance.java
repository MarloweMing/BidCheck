package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Describes an already advertised Host protocol; it never rewrites submitted evidence. */
final class ContractSubmissionGuidance {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SYSTEM = "合同跨页多属性或整包拒收时，按已声明工具优先使用 submissionMode=CONTRACT_FIELDS。"
        + "fact 填真实已读的独立身份事实，不造占位；每个 atom 用自己的 sourceIds 证明本字段，封面/身份页不能承载正文金额和签订日期。"
        + "缺身份先读取，不继承被拒整包属性。按可信回执的 fieldPath、remainingFieldNeeds、submissionExample 修正具体字段；"
        + "contractFieldWorkPlan.remainingActions 和 contractFieldReviewActions 给出要核对的字段及原文位置；先查指定来源。不同金额、日期或姓名应分别核对原文，不能直接替换；已执行的修正不重复提交。"
        + "readSourceCandidates 仍只是导航。alreadyKnownFactIds 不当新增进展，继续缺失字段。示例值必须替换为当前原文，"
        + "部分采信不代表完整合同或业务通过；普通单字段 fact 请求仍兼容。";

    private ContractSubmissionGuidance() {}

    static Map<String,Object> describe(Map<String,Object> definition) {
        if (!"submit_candidate_fact".equals(definition.get("name"))) return definition;
        Map<String,Object> original = object(object(definition.get("parameters")).get("properties"));
        Map<String,Object> mode = object(original.get("submissionMode"));
        Map<String,Object> items = object(object(original.get("contractFields")).get("items"));
        if (!(mode.get("enum") instanceof Collection<?>)
                || !((Collection<?>) mode.get("enum")).contains("CONTRACT_FIELDS")
                || !object(items.get("properties")).keySet().containsAll(Arrays.asList("field", "rawValue", "sourceIds", "attributes")))
            return definition;
        // Valid older JSON Schemas can use boolean property schemas. Descriptions are
        // optional annotations: never make an otherwise usable tool fail at startup.
        if (!(original.get("fact") instanceof Map<?,?>)) return definition;
        for (String property : Arrays.asList("field", "rawValue", "sourceIds", "attributes"))
            if (!(object(items.get("properties")).get(property) instanceof Map<?,?>)) return definition;
        // Preserve the Host's allowed kinds, required fact, limits and legacy request shape.
        Map<String,Object> copy = JSON.convertValue(definition, Map.class);
        Map<String,Object> parameters = object(copy.get("parameters"));
        Map<String,Object> properties = object(parameters.get("properties"));
        object(properties.get("submissionMode")).put("description",
            "多字段或跨来源合同优先填 CONTRACT_FIELDS；普通单字段可省略。仅对当前允许 CONTRACT 的目标使用。");
        object(properties.get("fact")).put("description",
            "必填的独立原文事实。原子模式建议只放已读合同名称或编号的真实身份锚点；没有身份原文先读取，不能造占位或把正文金额日期附到封面。");
        Map<String,Object> array = object(properties.get("contractFields"));
        array.put("description", "显式 CONTRACT_FIELDS 模式逐字段核验，不从 fact 继承 sourceIds 或业务属性。每项使用自己的原文来源；部分采信不代表完整合同。");
        Map<String,Object> atom = object(object(array.get("items")).get("properties"));
        object(atom.get("field")).put("enum", Arrays.asList("contractName", "contractNumber", "party", "person", "personRole", "signedDate", "amount"));
        object(atom.get("field")).put("description", "一个服务器支持的规范字段名；金额与签订日期分别提交，不用单字段夹带其他未证明属性。");
        object(atom.get("rawValue")).put("description", "本字段已读原文中的连续文字；金额填原文数字，日期填完整年月日，不拼接猜测。");
        object(atom.get("sourceIds")).put("description", "本字段实际已读原文来源；跨页时可加同一合同的已读身份锚点，但不能仅引用封面/身份页承载金额或日期。");
        object(atom.get("attributes")).put("description", "只含原文可证 contractName/contractNumber 和本字段；amount还需amountBasis/unit，可含taxBasis；person/personRole可配对。signedDate须有明确签订日期角色。不复制被拒整包的其他字段。");
        parameters.put("examples", Collections.singletonList(example(properties.containsKey("targetObligationId"))));
        copy.put("description", "合同逐字段模式：多字段/跨来源或整包拒绝时优先 submissionMode=CONTRACT_FIELDS。"
            + "参数examples仅示范结构，其中目标/sourceId/编号/金额/日期均为示例，必须替换为本次已读原文。" + String.valueOf(copy.get("description")));
        return copy;
    }

    private static Map<String,Object> example(boolean typed) {
        Map<String,Object> value = map("fact", map("kind", "CONTRACT", "field", "contractNumber",
            "rawValue", "<已读原文合同编号>", "sourceIds", Collections.singletonList("<已读身份页sourceId>"),
            "attributes", map("contractNumber", "<已读原文合同编号>")),
            "submissionMode", "CONTRACT_FIELDS", "contractFields", Arrays.asList(
                map("field", "amount", "rawValue", "200", "sourceIds", Arrays.asList("<已读身份页sourceId>", "<已读金额正文sourceId>"),
                    "attributes", map("contractNumber", "<已读原文合同编号>", "amount", "200", "amountBasis", "合同金额", "unit", "万元")),
                map("field", "signedDate", "rawValue", "2023年1月1日", "sourceIds", Arrays.asList("<已读身份页sourceId>", "<已读签订日期正文sourceId>"),
                    "attributes", map("contractNumber", "<已读原文合同编号>", "signedDate", "2023-01-01"))));
        if (typed) value.put("targetObligationId", "<当前允许CONTRACT的目标ID>");
        return value;
    }

    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>) value : Collections.emptyMap();
    }
}
