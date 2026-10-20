package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;

/** Relevance is admission to a bounded review, never proof that an obligation is met. */
public final class OnDemandPolicy {
    public static final String VERSION = "rules-retrieval-on-demand-4-shared-proofs";
    private OnDemandPolicy() { }
    public record Decision(String code, String reason, boolean requestModel) { }

    public static Decision decide(Checkpoint check, List<Evidence> evidence) {
        String requirement = Objects.toString(check.expected(), "");
        String query = Objects.toString(check.title(), "") + " " + requirement;
        if (requirement.matches("(?s).*(本轮|本次审查).{0,30}(未提供|未取得|待补).*")
                || query.matches("(?s).*(签字真伪|印章真伪|现场核验|官方实时|实时查询|原件真实性).*") )
            return new Decision("EXTERNAL_INPUT_REQUIRED", "所需外部输入、原图真实性或现场信息不能由现有文字证明，保留待核；未调用文字模型。", false);
        List<Evidence> substantive = evidence.stream().filter(e -> e != null && e.quote() != null)
                .filter(e -> !ClaimGrounding.indexOnly(e.quote())).filter(e -> related(query, e.quote())).toList();
        if (substantive.isEmpty())
            return new Decision("EVIDENCE_INSUFFICIENT", "定向检索尚未取得实质相关原文；候选排序或目录提及不能证明响应，也不能据此认定文件缺失。", false);
        boolean separateProof = query.matches("(?s).*(证明|证书|证件|社保|缴费|业绩|有效期|任职|合同|资质|信用).*");
        if (separateProof && substantive.stream().allMatch(e -> e.quote().substring(0, Math.min(160, e.quote().length())).matches("(?s).*(承诺书|声明书|投标函|联合体协议).*")))
            return new Decision("EVIDENCE_INSUFFICIENT", "现有相关片段仅为声明或承诺，尚不能替代要求中的独立证明；保留待核。", false);
        boolean complex = query.matches("(?s).*(一致|冲突|矛盾|归属|对应|关联|连续|任意|至少|不低于|除外|同时|分别|主体|标段|含税|不含税|口径|有效|期限|证明|证书|任职|社保|资质|技术方案|偏离).*")
                || requirement.matches("(?s).*[；;].*")
                || substantive.stream().map(e -> e.page() + "|" + e.locationType()).distinct().count() > 1;
        return complex
                ? new Decision("MODEL_ESCALATED", "存在相关原文，但条件、范围或跨来源关系不能由确定性规则确认，提交有界疑点核验。", true)
                : new Decision("RULE_UNSUPPORTED", "已定位相关原文，但本项尚无可用的确定性判定规则；这是系统判定能力缺口，不表示投标材料有问题。", false);
    }

    static boolean related(String query, String quote) {
        Set<String> terms = grams(query), matches = grams(quote);
        matches.retainAll(terms);
        return !terms.isEmpty() && matches.size() >= Math.max(2, Math.min(8, terms.size() / 5));
    }
    private static Set<String> grams(String value) {
        Set<String> result = new HashSet<>();
        for (String token : value.split("[^\\p{IsHan}A-Za-z0-9]+")) {
            for (int i = 0; i + 1 < token.length(); i++) result.add(token.substring(i, i + 2));
        }
        result.removeAll(Set.of("投标", "标人", "文件", "要求", "提供", "项目", "应当", "是否", "检查", "符合", "本项", "本次", "满足"));
        return result;
    }
}
