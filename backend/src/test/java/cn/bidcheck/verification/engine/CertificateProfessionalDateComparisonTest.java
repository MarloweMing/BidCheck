package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class CertificateProfessionalDateComparisonTest {
    private static final String COMPARE="查询截图信息应与证明文件信息一致";
    private static final String FULL="项目负责人具有贰级含以上注册建造师执业资格证书（房屋建筑工程专业）。"+COMPARE;
    private Decision compare(String queryProfession,String queryEnd,String full,Map<String,Object> extra){
        String appointment="投标文件\n项目名称：北方装修工程\n本项目拟派李明担任项目负责人。\n姓名：李明\n身份证号：110101198001010010";
        String cert="一级建造师注册证书\n姓名：李明\n身份证号：110101198001010010\n注册编号：京1999999999990001\n聘用企业：北方建设有限公司\n注册专业：机电工程(有效期：2024-01-01至2027-04-06)\n建筑工程(有效期：2023-08-07至2026-08-06)";
        String query="证书查询截图\n| 姓名 | 企业名称 | 人员类型 | 注册号 | 注册专业 | 有效期结束日期 |\n|---|---|---|---|---|---|\n| 李明 | 北方建设有限公司 | 一级建造师 | 京1999999999990001 | "+queryProfession+" | "+queryEnd+" |";
        var corpus=new EvidenceCorpus(List.of(new Document("b","p","BID","投标文件",1,"h","",List.of(new Page(1,appointment,"PAGE","TEXT",null),new Page(2,cert,"PAGE","TEXT",null),new Page(3,query,"PAGE","TEXT",null)),List.of())));
        Fact role=new Fact("role","PERSON_ROLE","项目负责人","姓名","李明","李明","","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of());
        Map<String,Object> parameters=new LinkedHashMap<>(extra);parameters.put("certificateRequirementQuote",full);
        Rule rule=new Rule("MATCH",COMPARE,"SAME_FIELD","项目负责人","证书信息","","","",parameters,COMPARE,"",List.of());
        return new RuleEvaluator(List.of(role),corpus,Map.of("originalRequirement",full)).evaluate(rule);
    }
    private Map<String,Object> date(Decision d){return RuleEvaluator.maps(d.proof().get("fieldComparisons")).stream().filter(f->"VALIDITY_END".equals(f.get("field"))).findFirst().orElseThrow();}
    @Test void requirementProfessionSelectsItsDateNotAnUnqualifiedMissingKey(){
        Decision d=compare("建筑","2026-08-06",FULL,Map.of());assertEquals("PASS",d.status(),d.toString());
        assertEquals("VALIDITY_END:建筑工程",((Map<?,?>)date(d).get("certificate")).get("field"));
        assertEquals("SAME_SOURCE_BOUND_PROFESSION",((Map<?,?>)date(d).get("dateScope")).get("resolution"));
    }
    @Test void multiProfessionalQueryWithOneDateCannotBorrowOtherProfessionEnd(){
        Decision d=compare("建筑,机电","2027-04-06",FULL,Map.of());assertEquals("UNDETERMINED",d.status(),d.toString());
        assertEquals(3,RuleEvaluator.maps(d.proof().get("fieldComparisons")).stream().filter(f->"PASS".equals(f.get("status"))).count());
        assertEquals("2026-08-06",((Map<?,?>)date(d).get("certificate")).get("value"));assertEquals(Map.of(),date(d).get("query"));
        assertEquals("PROFESSION_DATE_SCOPE_NOT_ALIGNED",((Map<?,?>)date(d).get("dateScope")).get("resolution"));
    }
    @Test void actualDifferentEndOfSameExplicitProfessionRemainsRisk(){assertEquals("FAIL",compare("建筑工程","2027-04-06",FULL,Map.of()).status());}
    @Test void modelParameterCannotInventRequirementProfession(){
        Decision d=compare("建筑","2026-08-06","项目负责人应有注册建造师执业资格证书。"+COMPARE,Map.of("requiredProfession","建筑工程"));
        assertEquals("UNDETERMINED",d.status());assertEquals("REQUIREMENT_PROFESSION_UNRESOLVED",((Map<?,?>)date(d).get("dateScope")).get("resolution"));
    }
    @Test void explicitOtherProfessionCannotSilentlyBecomeSelectedProfession(){assertEquals("UNDETERMINED",compare("机电工程","2027-04-06",FULL,Map.of()).status());}
}
