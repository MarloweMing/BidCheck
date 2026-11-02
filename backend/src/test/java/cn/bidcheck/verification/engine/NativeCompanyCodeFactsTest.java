package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCompanyCodeFactsTest {
    final PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();
    static final String COMPANY="北方建设有限公司",CODE="9111010577196369XG";
    Rule rule(){return new Rule("credit","代码一致性","SAME_FIELD","","creditCode","=","","",Map.of(),"","",List.of());}
    String cover(){return "投标文件\n项目名称：北方办公楼装修工程\n投标人名称：（盖章）"+COMPANY;}
    @Test void chineseCodeLabelAcrossNewlineAndBackticksHasExactValueAndRealOwner(){
        var c=fixture.corpus(cover(),"营业执照\n名称："+COMPANY+"\n统一社会信用代码：\n`"+CODE+"`\n营业期限：长期", "代理费承诺书\n单位名称 "+COMPANY+"\n纳税人识别号 "+CODE);
        var facts=c.nativeFacts(List.of(rule()),c.all).stream().filter(f->f.field().equals("creditCode")).toList();assertEquals(2,facts.size(),facts.toString());assertTrue(facts.stream().allMatch(f->f.subject().equals(COMPANY)&&f.rawValue().equals(CODE)&&f.value().equals(CODE)));facts.forEach(f->assertNull(new ProofVerifier().issue(f,c.byId)));
        assertEquals(2,SameFieldSourceOccurrences.inspect(facts,c).distinctCount());
        assertEquals("PASS",new RuleEvaluator(facts,c,Map.of()).sameField(rule(),facts).status());
    }
    @Test void differentConsortiumMembersAreComparedSeparatelyAndEachNeedsTwoOccurrences(){
        String second="南方设计有限公司",other="9111010577196369XA";
        var c=fixture.corpus(COMPANY+" 统一社会信用代码："+CODE,COMPANY+" 统一社会信用代码："+CODE,second+" 统一社会信用代码："+other,second+" 统一社会信用代码："+other);
        List<Fact> facts=new ArrayList<>();for(int i=0;i<4;i++){String owner=i<2?COMPANY:second,code=i<2?CODE:other;facts.add(new Fact("code-"+i,"FIELD",owner,"creditCode",code,code,"","","CURRENT_PROJECT",List.of(c.all.get(i).id()),Map.of()));}
        assertEquals("PASS",new RuleEvaluator(facts,c,Map.of()).sameField(rule(),facts).status());
        assertEquals("UNDETERMINED",new RuleEvaluator(facts.subList(0,3),c,Map.of()).sameField(rule(),facts.subList(0,3)).status());
    }
    @Test void otherCompanyAndUnlabelledNumberNeverBorrowBidderScope(){
        var c=fixture.corpus(cover(),"营业执照\n名称：南方建设有限公司\n统一社会信用代码："+CODE,"单位名称："+COMPANY+"\n其他号码："+CODE,"历史项目\n单位名称："+COMPANY+"\n统一社会信用代码："+CODE);
        assertTrue(c.nativeFacts(List.of(rule()),c.all).stream().noneMatch(f->f.field().equals("creditCode")));
    }
    @Test void multipleOwnersOnOneLoosePageDoNotInheritTheFirstCompany(){
        var c=fixture.corpus(cover(),"对照材料\n单位名称："+COMPANY+"\n单位名称：南方建设有限公司\n统一社会信用代码："+CODE);assertTrue(c.nativeFacts(List.of(rule()),c.all).stream().noneMatch(f->f.field().equals("creditCode")));
    }
}
