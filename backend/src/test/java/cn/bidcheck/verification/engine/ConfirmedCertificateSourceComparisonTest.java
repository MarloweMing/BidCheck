package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedCertificateSourceComparisonTest {
    @Test void confirmedQueryHolderClosesOnlyNameAndKeepsOtherFieldComparisons(){
        for(boolean wrongEmployer:cn.bidcheck.compat.Java8Api.listOf(false, true)){
            String certificate="一级注册建筑师注册证书\n姓名：李明\n证件号码：110101198001010010\n性别：男\n注册编号：AR1001\n聘用单位：北方设计有限公司\n注册有效期：2024年01月01日-2027年01月01日\n发证日期：2024年01月01日";
            String query="证书查询截图\n一级注册建筑师\n姓名：李*\n性别：男\n注册单位："+(wrongEmployer?"南方设计有限公司":"北方设计有限公司")+"\n电子证书编号：AR1001\n注册编号/执业印章号：9001-001\n注册专业：不分专业\n有效期：2027年01月01日";
            cn.bidcheck.verification.engine.PersonnelCountRoleTest h=new PersonnelCountRoleTest();cn.bidcheck.verification.engine.EvidenceCorpus c=h.corpus("投标文件\n项目名称：北方中心综合楼装修工程\n本项目拟派李明担任设计负责人。\n姓名：李明\n身份证号：110101198001010010",certificate,query);Fact role=h.role(c,1,"设计负责人","李明","","");String quote="查询截图信息应与证明文件信息一致";Rule rule=new Rule("R5",quote,"SAME_FIELD","设计负责人","查询截图信息","=","证明文件信息","",cn.bidcheck.compat.Java8Api.mapOf(),quote,"",cn.bidcheck.compat.Java8Api.listOf());
            assertEquals("UNDETERMINED",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(role),c,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule).status());Fact approved=new ConfirmedSubjectBindingsTest().confirmation(c,c.all);Decision d=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(role, approved),c,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule);assertEquals(wrongEmployer?"FAIL":"PASS",d.status(),d.toString());assertTrue(RuleEvaluator.maps(d.proof().get("fieldComparisons")).stream().anyMatch(f->"NAME".equals(f.get("field"))&&"PASS".equals(f.get("status"))&&!((Map<?,?>)f.get("identityConfirmation")).isEmpty()));
        }
    }
}
