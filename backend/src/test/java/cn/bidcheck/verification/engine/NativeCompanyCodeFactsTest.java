package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCompanyCodeFactsTest {
    final PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();
    static final String COMPANY="北方建设有限公司",CODE="9111010577196369XG";
    Rule rule(){return new Rule("credit","代码一致性","SAME_FIELD","","creditCode","=","","",cn.bidcheck.compat.Java8Api.mapOf(),"","",cn.bidcheck.compat.Java8Api.listOf());}
    String cover(){return "投标文件\n项目名称：北方办公楼装修工程\n投标人名称：（盖章）"+COMPANY;}
    @Test void typedComparisonFindsCurrentCredentialOutsideTheSearchWindowWithoutBorrowingAnotherOwner(){
        Rule r=new Rule("typed","信用代码","SAME_FIELD","","creditCode","=","","",
                linked("intentAuthority","CONFIRMED_CHECKPOINT_METADATA","comparisonScope","CURRENT_PROJECT_OCCURRENCES"),"","",Collections.emptyList());
        String license="营业执照\n名称："+COMPANY+"\n统一社会信用代码："+CODE+"\n营业期限：长期";
        String table="<table><tr><td>企业名称</td><td>"+COMPANY+"</td></tr><tr><td>证书编号</td><td>B1234</td></tr>"
                +"<tr><td>统一社会信用代码(或营业执照注册号)</td><td>"+CODE+"</td></tr></table>";
        for(boolean unrelated:Arrays.asList(false,true)){
            EvidenceCorpus c=fixture.corpus(cover(),license,unrelated?table.replace(COMPANY,"无关企业有限公司"):table);
            List<Fact> facts=NativeCompanyCodeFacts.extract(r,c,Collections.emptyList());
            Decision result=new RuleEvaluator(facts,c,Collections.emptyMap()).evaluate(r);
            assertEquals(unrelated?"UNDETERMINED":"PASS",result.status(),result.toString());
        }
    }
    @Test void chineseCodeLabelAcrossNewlineAndBackticksHasExactValueAndRealOwner(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=fixture.corpus(cover(),"营业执照\n名称："+COMPANY+"\n统一社会信用代码：\n`"+CODE+"`\n营业期限：长期", "代理费承诺书\n单位名称 "+COMPANY+"\n纳税人识别号 "+CODE);
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> facts=cn.bidcheck.compat.Java8Api.toList(c.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(rule()),c.all).stream().filter(f->f.field().equals("creditCode")));assertEquals(2,facts.size(),facts.toString());assertTrue(facts.stream().allMatch(f->f.subject().equals(COMPANY)&&f.rawValue().equals(CODE)&&f.value().equals(CODE)));facts.forEach(f->assertNull(new ProofVerifier().issue(f,c.byId)));
        assertEquals(2,SameFieldSourceOccurrences.inspect(facts,c).distinctCount());
        assertEquals("PASS",new RuleEvaluator(facts,c,cn.bidcheck.compat.Java8Api.mapOf()).sameField(rule(),facts).status());
    }
    @Test void differentConsortiumMembersAreComparedSeparatelyAndEachNeedsTwoOccurrences(){
        String second="南方设计有限公司",other="9111010577196369XA";
        cn.bidcheck.verification.engine.EvidenceCorpus c=fixture.corpus(COMPANY+" 统一社会信用代码："+CODE,COMPANY+" 统一社会信用代码："+CODE,second+" 统一社会信用代码："+other,second+" 统一社会信用代码："+other);
        List<Fact> facts=new ArrayList<>();for(int i=0;i<4;i++){String owner=i<2?COMPANY:second,code=i<2?CODE:other;facts.add(new Fact("code-"+i,"FIELD",owner,"creditCode",code,code,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(c.all.get(i).id()),cn.bidcheck.compat.Java8Api.mapOf()));}
        assertEquals("PASS",new RuleEvaluator(facts,c,cn.bidcheck.compat.Java8Api.mapOf()).sameField(rule(),facts).status());
        assertEquals("UNDETERMINED",new RuleEvaluator(facts.subList(0,3),c,cn.bidcheck.compat.Java8Api.mapOf()).sameField(rule(),facts.subList(0,3)).status());
    }
    @Test void otherCompanyAndUnlabelledNumberNeverBorrowBidderScope(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=fixture.corpus(cover(),"营业执照\n名称：南方建设有限公司\n统一社会信用代码："+CODE,"单位名称："+COMPANY+"\n其他号码："+CODE,"历史项目\n单位名称："+COMPANY+"\n统一社会信用代码："+CODE);
        assertTrue(c.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(rule()),c.all).stream().noneMatch(f->f.field().equals("creditCode")));
    }
    @Test void multipleOwnersOnOneLoosePageDoNotInheritTheFirstCompany(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=fixture.corpus(cover(),"对照材料\n单位名称："+COMPANY+"\n单位名称：南方建设有限公司\n统一社会信用代码："+CODE);assertTrue(c.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(rule()),c.all).stream().noneMatch(f->f.field().equals("creditCode")));
    }
}
