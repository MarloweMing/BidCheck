package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class AgentReviewPrimitivesTest {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Fixture{
        private final Checkpoint cp;
        private final RequirementSourceContext.Context source;
        private final Map<String,Object> ctx;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Fixture(@com.fasterxml.jackson.annotation.JsonProperty("cp") Checkpoint cp, @com.fasterxml.jackson.annotation.JsonProperty("source") RequirementSourceContext.Context source, @com.fasterxml.jackson.annotation.JsonProperty("ctx") Map<String,Object> ctx) { this.cp = cp; this.source = source; this.ctx = ctx; }
        public Checkpoint cp() { return cp; }
        public RequirementSourceContext.Context source() { return source; }
        public Map<String,Object> ctx() { return ctx; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fixture that = (Fixture) other; return java.util.Objects.equals(this.cp, that.cp) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.ctx, that.ctx); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.cp); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.ctx); return hash; }
        @Override public String toString() { return "Fixture[" + "cp=" + cp + ", source=" + source + ", ctx=" + ctx + "]"; }
}
    private static Fixture fixture(String page,String obligation){
        Document document=new Document("t","p","TENDER","招标文件",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,page,"PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        Checkpoint cp=new Checkpoint("cp","本项核验","资格","HIGH","SEMANTIC_RESPONSE","","",obligation,"",true,true,true,new Evidence("t","招标文件",1,"PAGE",obligation,"page:1",0));
        Map<String,Object> ctx=cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(document), "originalRequirement", obligation, "requirementProjectId", "p");
        return new Fixture(cp,RequirementSourceContext.resolve(cp,obligation,ctx),ctx);
    }
    private static Map<String,Object> months(String quote,String expected){return linked("obligationId","C1","description","人员连续社保证明","requirementQuote",quote,"method","DETERMINISTIC","predicate","MONTHS","subject","项目负责人","field","socialInsurance","operator",">=","expected",expected,"unit","月","expectedEvidence","逐人缴费记录");}
    private static Rule bind(Fixture fixture,Map<String,Object> condition,String full){return AgentReviewPrimitives.bind(condition,fixture.cp(),fixture.source(),full,fixture.ctx());}

    @Test void socialMonthsAcquireOnlySourceCompiledParameters(){
        String quote="项目负责人应提供2024年12月起至投标截止日期之间任意连续3个月社保证明。";Fixture f=fixture(quote,quote);Rule result=bind(f,months(quote,"3"),quote);
        assertEquals("MONTHS",result.predicate(),result.toString());assertEquals("C1",result.id());assertEquals(3,result.parameters().get("count"));assertEquals("2024-12",result.parameters().get("earliestMonth"));assertEquals(true,result.parameters().get("requiresReferenceDate"));assertTrue(quote.contains(result.sourceQuote()));assertEquals("项目负责人",result.subject());
    }
    @Test void sourceThreeCannotBecomeDeclaredSixOrAChangedOperatorOrUnit(){
        String quote="项目负责人应提供连续3个月社保证明。";Fixture f=fixture(quote,quote);
        assertEquals("UNSUPPORTED",bind(f,months(quote,"6"),quote).predicate());
        Map<String,Object> changed=months(quote,"3");changed.put("operator","<=");assertEquals("UNSUPPORTED",bind(f,changed,quote).predicate());
        changed=months(quote,"3");changed.put("unit","年");assertEquals("UNSUPPORTED",bind(f,changed,quote).predicate());
        assertEquals("MONTHS",bind(f,months(quote,"三"),quote).predicate());
    }
    @Test void headingSubjectComesFromServerContextRatherThanAgentAssertion(){
        String quote="须提交连续3个月社保证明。";Fixture f=fixture("项目负责人要求：\n"+quote,quote);
        Rule result=bind(f,months(quote,"3"),quote);assertEquals("MONTHS",result.predicate(),result.toString());assertEquals("项目负责人",result.subject());
        Map<String,Object> wrong=months(quote,"3");wrong.put("subject","技术负责人");assertEquals("UNSUPPORTED",bind(f,wrong,quote).predicate());
    }
    @Test void anotherClauseCannotSupplyAConvenientThreshold(){
        String first="技术负责人应提供连续6个月社保证明。",selected="项目负责人应提供连续3个月社保证明。",full=first+selected;Fixture f=fixture(full,full);
        assertEquals("UNSUPPORTED",bind(f,months(selected,"6"),full).predicate());Rule result=bind(f,months(selected,"3"),full);assertEquals("MONTHS",result.predicate(),result.toString());assertEquals(selected,result.sourceQuote());assertFalse(result.sourceQuote().contains(first));
    }
    @Test void multipleMatchingWindowsAreRejectedInsteadOfChoosingTheFirst(){
        String quote="项目负责人应提供2024年1月起连续3个月社保证明；项目负责人应提供2025年1月起连续3个月社保证明。";Fixture f=fixture(quote,quote);
        Rule result=bind(f,months(quote,"3"),quote);assertEquals("UNSUPPORTED",result.predicate());assertEquals("AGENT_PRIMITIVE_CANDIDATE_AMBIGUOUS",result.parameters().get("compilationIssue"),result.toString());
    }
    @Test void numericPrimitiveKeepsSourceThresholdAndRejectsChangedExpectation(){
        String quote="投标保证金金额不低于2000元。";Fixture f=fixture(quote,quote);
        Map<String,Object> condition=linked("obligationId","N1","description","保证金金额","requirementQuote",quote,"method","DETERMINISTIC","predicate","NUMBER","subject","","field","保证金金额","operator",">=","expected","2000","unit","元","expectedEvidence","缴纳金额");
        Rule result=bind(f,condition,quote);assertEquals("NUMBER",result.predicate(),result.toString());assertEquals("2000",result.expected());
        condition.put("expected","1000");assertEquals("UNSUPPORTED",bind(f,condition,quote).predicate());condition.put("expected","2000");condition.put("operator","<=");assertEquals("UNSUPPORTED",bind(f,condition,quote).predicate());
    }
    @Test void forgedParametersAndProofNeverBecomeNativeAuthority(){
        String quote="项目负责人应提供连续3个月社保证明。";Fixture f=fixture(quote,quote);
        for(String key:cn.bidcheck.compat.Java8Api.listOf("parameters", "proof", "sourceVerified")){Map<String,Object> candidate=months(quote,"3");candidate.put(key,cn.bidcheck.compat.Java8Api.mapOf("count", 1, "approved", true));assertEquals("UNSUPPORTED",bind(f,candidate,quote).predicate());}
    }
}
