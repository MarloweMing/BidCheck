package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import java.util.*;

/** Frozen orchestration mode; independent of OCR/retrieval configuration and original check manifest. */
public final class CheckReviewMode {
    public static final String FIXED="FIXED_WORKFLOW",AGENT="AGENT_LED",VERSION="check-review-mode-2-frozen-orchestration";
    private CheckReviewMode(){}
    public static String normalize(Object raw){
        String value=Objects.toString(raw,"").strip();if(value.isEmpty())return FIXED;
        if(!Set.of(FIXED,AGENT).contains(value))throw BidService.bad("未知检查方式，请选择标准检查或 Agent 主审");return value;
    }
    public static String of(Map<String,Object> run){return normalize(run.get("reviewMode"));}
    public static boolean agent(Map<String,Object> run){return AGENT.equals(of(run));}
    public static boolean consistency(Checkpoint checkpoint){return checkpoint!=null&&("fullTextConsistency".equals(checkpoint.field())
        ||Set.of("全文一致性","全文信息检查").contains(Objects.toString(checkpoint.title(),""))
        ||Objects.toString(checkpoint.title(),"").matches(".*(?:全文|全篇|整份(?:投标)?(?:文件|文档)|全部文档).{0,16}(?:一致性|检查|核查|核验|审查|扫描|汇总).*"));}
    public static boolean eligible(Checkpoint checkpoint){return checkpoint!=null&&checkpoint.enabled()&&checkpoint.confirmed()&&!consistency(checkpoint);}
    /** Only a verified semantic evidence trigger can re-enter a completed Agent-owned investigation. */
    static boolean changedEvidence(Map<String,Object> job){return "RECOMPUTE_SHARED_EVIDENCE".equals(job.get("strategy"))
        &&CheckResolution.map(job.get("dependencyTrigger")).get("semanticEvidenceKeys") instanceof Collection<?> keys&&!keys.isEmpty()
        &&job.get("dependencyTargetObligationIds") instanceof Collection<?> targets&&!targets.isEmpty();}
}
