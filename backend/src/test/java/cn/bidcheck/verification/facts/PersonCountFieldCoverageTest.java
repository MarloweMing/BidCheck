package cn.bidcheck.verification.facts;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonCountFieldCoverageTest {
    Map<String,Object> target(){return of("predicate","PERSON_COUNT","subject","施工部分负责人","field","count","unit","人","parameters",Map.of(),"targetExpected","2");}
    Map<String,Object> appointment(){return of("kind","PERSON_ROLE","subject","施工负责人","field","姓名","value","张三","unit","","scope","CURRENT_PROJECT","sourceIds",List.of("source"),"attributes",Map.of("identityStrength","SOURCE_LOCAL"));}
    Map<String,Object> receipt(Map<String,Object> target,String status,List<String> ids){return of("target",target,"observationKey",FieldCoverage.key(target),"observationPolicy",FieldCoverage.POLICY,"modelIdentity","fixture","status",status,"sourceIds",List.of("source"),"factIds",ids,"sourceScopeComplete",true);}

    @Test void countObservationRecognizesSourceLocalAppointmentWithoutClaimingIdentityOrNumber(){
        Map<String,Object> target=target(),fact=appointment();assertTrue(FieldCoverage.matches(target,fact));
        var accepted=FieldCoverage.validate(List.of(receipt(target,"EXTRACTED",List.of("fact"))),Map.of("source",Map.of()),List.of(of("originalFactId","fact","fact",fact)));
        assertEquals(1,accepted.size());assertTrue(FieldCoverage.completeRead(accepted.get(0)));assertEquals(false,accepted.get(0).get("absenceProven"));assertEquals("SELECTED_SOURCES_ONLY",accepted.get(0).get("scope"));
        assertFalse(accepted.get(0).containsKey("businessDecision"));assertFalse(accepted.get(0).containsKey("provenDistinctPeople"));
    }
    @Test void onlyVocabularyAliasesShareAnObservation(){
        Map<String,Object> target=target(),fact=appointment();
        for(String role:List.of("施工部分负责人","施工负责人","本项目施工负责人")){fact.put("subject",role);assertTrue(FieldCoverage.matches(target,fact),role);}
        for(String role:List.of("项目负责人","设计部分负责人","企业主要负责人","施工企业主要负责人","施工单位","未知负责人","")){fact.put("subject",role);assertFalse(FieldCoverage.matches(target,fact),role);}
        target.put("subject","项目负责人");fact.put("subject","项目经理");assertFalse(FieldCoverage.matches(target,fact),"Evidence-dependent aliases require proof and are not vocabulary-only observation aliases");
    }
    @Test void historicalCertificateEmptyNameOrUnlocatedFactsDoNotCompleteAnAppointmentObservation(){
        for(Map<String,Object> change:List.of(of("scope","HISTORICAL"),of("scope","UNRESOLVED"),of("kind","CERTIFICATE"),of("value",""),of("sourceIds",List.of()))){Map<String,Object> fact=appointment();fact.putAll(change);assertFalse(FieldCoverage.matches(target(),fact),change.toString());}
        Map<String,Object> target=target();target.put("unit","个");assertFalse(FieldCoverage.matches(target,appointment()));
    }
    @Test void qualifiedCountsAndDifferentMemberOrLotMustRemainSeparate(){
        for(Map<String,Object> params:List.of(of("qualificationRequired",true),of("countBasis","QUALIFIED"),of("category","注册建造师"),of("grade","一级"),of("profession","建筑工程"),of("employer","甲公司"),of("lot","一标段"))){Map<String,Object> target=target();target.put("parameters",params);assertFalse(FieldCoverage.matches(target,appointment()),params.toString());}
        Map<String,Object> target=target();target.put("field","合格人数");assertFalse(FieldCoverage.matches(target,appointment()));
        target=target();target.put("parameters",Map.of("employer","甲公司","lot","一标段"));Map<String,Object> fact=appointment();fact.put("attributes",Map.of("employer","甲公司","lot","一标段"));assertTrue(FieldCoverage.matches(target,fact));fact.put("attributes",Map.of("employer","乙公司","lot","一标段"));assertFalse(FieldCoverage.matches(target,fact));
    }
    @Test void onlyPersonCountObservationKeysInvalidateTheOldIncompatiblePolicy(){
        Map<String,Object> count=target();assertNotEquals(hash(FieldCoverage.POLICY+canonical(count)),FieldCoverage.key(count));
        for(String predicate:List.of("NUMBER","CERTIFICATE","VALIDITY","PERSON_ROLE","MATERIAL","RELATION")){Map<String,Object> other=target();other.put("predicate",predicate);assertEquals(hash(FieldCoverage.POLICY+canonical(other)),FieldCoverage.key(other),predicate);}
        Map<String,Object> old=receipt(count,"READ_NOT_FOUND",List.of());old.put("observationKey",hash(FieldCoverage.POLICY+canonical(count)));
        assertTrue(FieldCoverage.validate(List.of(old),Map.of("source",Map.of()),List.of()).isEmpty());
        assertEquals(1,FieldCoverage.validate(List.of(receipt(count,"READ_NOT_FOUND",List.of())),Map.of("source",Map.of()),List.of()).size());
    }
    @Test void extractedReceiptStillRequiresMatchingVerifiedFactsAndTheSameSources(){
        Map<String,Object> target=target();assertTrue(FieldCoverage.validate(List.of(receipt(target,"EXTRACTED",List.of())),Map.of("source",Map.of()),List.of()).isEmpty());
        Map<String,Object> wrong=appointment();wrong.put("subject","设计负责人");assertTrue(FieldCoverage.validate(List.of(receipt(target,"EXTRACTED",List.of("fact"))),Map.of("source",Map.of()),List.of(of("originalFactId","fact","fact",wrong))).isEmpty());
        Map<String,Object> elsewhere=appointment();elsewhere.put("sourceIds",List.of("another-source"));assertTrue(FieldCoverage.validate(List.of(receipt(target,"EXTRACTED",List.of("fact"))),Map.of("source",Map.of()),List.of(of("originalFactId","fact","fact",elsewhere))).isEmpty());
    }
}
