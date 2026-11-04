package cn.bidcheck.verification.engine;
import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Entirely synthetic source formula; no frozen tender or model. */
public class Ticket09ReadTargetTest {
    static Rule score(){
        String quote="项目负责人实力（商务）\t具有（合成专业）高级工程师职称得3分，中级得1分，未提交不得分。证明材料：填写附表6-1。需提交有效证明文件（并提供查询网址及网址查询截图）及提交2024年1月（含）至文件递交截止时间任意连续三个月在本单位缴纳社保证明材料，特殊情况提交说明文件（格式自拟）及有效的证明文件（如单位设立时间不足、人员新入职、离退休、转业等）。";
        Checkpoint cp=new Checkpoint("synthetic-score","合成职称评分","评分","MEDIUM","SEMANTIC_RESPONSE","scoring","RESPONDS",quote,"",true,true,true,new Evidence("synthetic-tender","合成文件",1,"BLOCK",quote,"",1));
        List<Rule> rules=SourceScoringRules.compile(cp,quote,new RequirementSourceContext.Context(quote,linked("synthetic",true),Collections.singletonList(linked("kind","OBLIGATION","sourceQuote",quote))),Collections.emptyMap());
        assertEquals(1,rules.size());assertNull(SourceScoringRules.issue(rules.get(0)));return rules.get(0);
    }
    public static Map<String,Object> scoreMap(){return new ObjectMapper().convertValue(score(),Map.class);}
    @Test void exactVerifiedReadSelectionDoesNotReopenSiblingAndParentStillExpands(){
        Rule parent=score();List<Rule> reads=SourceScoringRules.evidenceRules(parent);Rule title=reads.get(1),social=reads.get(2);
        Map<String,Object> context=linked("recoveryMode","TARGETED","recoveryTargetObligationIds",Collections.singletonList(title.id()));
        List<Rule> selected=RecoveryTargets.select(Collections.singletonList(parent),reads,context);
        assertTrue(selected.contains(title));assertFalse(selected.contains(social));
        RuleEvaluator evaluator=new RuleEvaluator(Collections.emptyList(),new EvidenceCorpus(Collections.emptyList()),Collections.emptyMap());
        Plan plan=new Plan("ALL",Collections.singletonList(parent),"SYNTHETIC",Collections.emptyList());
        List<Rule> pending=AgentInvestigationScope.pending(plan,reads,context,evaluator);
        assertEquals(Collections.singletonList(title),pending);
        context.put("recoveryTargetObligationIds",Collections.singletonList(parent.id()));
        assertEquals(reads,RecoveryTargets.select(Collections.singletonList(parent),reads,context));
        context.put("recoveryTargetObligationIds",Collections.singletonList(parent.id()+"-READ-FORGED"));
        assertTrue(RecoveryTargets.select(Collections.singletonList(parent),reads,context).isEmpty());
        assertTrue(AgentInvestigationScope.pending(plan,reads,context,evaluator).isEmpty());
    }
}
