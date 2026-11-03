package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsortiumContextPolicyTest {
    private static final String A="甲建设有限公司",B="乙设计有限公司",C="丙装饰有限公司";
    private Document doc(String project,String agreementProject,boolean declared,String... names){
        String first="投标文件\n项目名称："+project+"\n本次投标函\n"+Arrays.stream(names).map(n->"投标人名称："+n).reduce((a,b)->a+"\n"+b).orElse("");
        String second="联合体协议书\n"+A+"、"+B+(declared?"[所有成员单位名称]自愿组成：本项目联合体。":"曾参与同一项目的工作。")+"\n共同投标"+agreementProject+"。\n"+A+"为联合体牵头人；"+B+"为联合体成员。";
        return new Document("bid","project","BID","test.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,first,"PAGE","PDFBOX",null), new Page(2,second,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> report,String key){return (List<Map<String,Object>>)report.get(key);}
    @Test void currentProjectDeclaredMembersAreBoundByExactNameAndComparedSeparately(){
        Document d=doc("示例装修工程","示例装修工程",true,"（盖章）"+A,B,A);
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(d,cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"groups").stream().noneMatch(g->"companyName".equals(g.get("field"))));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> companies=cn.bidcheck.compat.Java8Api.toList(rows(report,"occurrences").stream().filter(o->"companyName".equals(o.get("field"))));
        assertEquals(3,companies.size());assertEquals(2,companies.stream().map(o->o.get("subject")).distinct().count());
        assertTrue(companies.stream().allMatch(o->o.get("subject").toString().startsWith("BIDDER_MEMBER:")));
        assertTrue(companies.stream().allMatch(o->((List<?>)o.get("contextEvidences")).stream().map(Evidence.class::cast).anyMatch(e->e.page()==2&&e.quote().contains("所有成员单位名称"))));
    }
    @Test void nonMemberNameStillProducesAReviewableDifferenceAgainstTheDeclaration(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("示例装修工程","示例装修工程",true,A,B,C),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> group=rows(report,"groups").stream().filter(g->g.containsKey("declaredMembers")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("SUSPECTED",group.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(C),group.get("values"));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(A, B),new HashSet<>((List<?>)group.get("declaredMembers")));
    }
    @Test void agreementForAnotherProjectCannotExplainAwayCurrentNameConflict(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次装修工程","历史装修工程",true,A,B),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"groups").stream().anyMatch(g->"companyName".equals(g.get("field"))&&"CONFLICT".equals(g.get("status"))));
        assertTrue(rows(report,"occurrences").stream().noneMatch(o->o.containsKey("subjectAssignment")));
    }
    @Test void MerelyMentioningTwoCompaniesIsNotAnExplicitMemberDeclaration(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("示例装修工程","示例装修工程",false,A,B),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"groups").stream().anyMatch(g->"companyName".equals(g.get("field"))&&"CONFLICT".equals(g.get("status"))));
    }
    @Test void nearSpellingIsNotSilentlyMergedIntoADeclaredMember(){
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("示例装修工程","示例装修工程",true,A,"乙设技有限公司"),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"groups").stream().anyMatch(g->g.containsKey("declaredMembers")&&((List<?>)g.get("values")).contains("乙设技有限公司")));
    }
    @Test void selfReferentialAgreementWithoutMatchedCoverCannotBindMembers(){
        Document original=doc("示例装修工程","本招标项目",true,A,B);
        Document d=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,"本次投标函\n投标人名称："+A+"\n投标人名称："+B,"PAGE","PDFBOX",null), original.pages().get(1)),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(d,cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"occurrences").stream().noneMatch(o->o.containsKey("subjectAssignment")));
        assertTrue(rows(report,"groups").stream().anyMatch(g->"companyName".equals(g.get("field"))&&"CONFLICT".equals(g.get("status"))));
    }
    @Test void sameProjectNameInsideHistoricalAppendixDoesNotEstablishCurrentMembership(){
        Document original=doc("示例装修工程","示例装修工程",true,A,B);Page agreement=original.pages().get(1);
        Document d=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),cn.bidcheck.compat.Java8Api.listOf(original.pages().get(0), new Page(2,"第一章 历史业绩\n"+agreement.text(),"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(d,cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(rows(report,"occurrences").stream().noneMatch(o->o.containsKey("subjectAssignment")));
        assertTrue(rows(report,"groups").stream().anyMatch(g->"companyName".equals(g.get("field"))&&"CONFLICT".equals(g.get("status"))));
    }
}
