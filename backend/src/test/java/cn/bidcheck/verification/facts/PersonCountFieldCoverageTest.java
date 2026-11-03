package cn.bidcheck.verification.facts;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonCountFieldCoverageTest {
    Map<String,Object> target(){return of("predicate","PERSON_COUNT","subject","施工部分负责人","field","count","unit","人","parameters",cn.bidcheck.compat.Java8Api.mapOf(),"targetExpected","2");}
    Map<String,Object> appointment(){return of("kind","PERSON_ROLE","subject","施工负责人","field","姓名","value","张三","unit","","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf("source"),"attributes",cn.bidcheck.compat.Java8Api.mapOf("identityStrength", "SOURCE_LOCAL"));}
    Map<String,Object> receipt(Map<String,Object> target,String status,List<String> ids){return of("target",target,"observationKey",FieldCoverage.key(target),"observationPolicy",FieldCoverage.POLICY,"modelIdentity","fixture","status",status,"sourceIds",cn.bidcheck.compat.Java8Api.listOf("source"),"factIds",ids,"sourceScopeComplete",true);}

    @Test void countObservationRecognizesSourceLocalAppointmentWithoutClaimingIdentityOrNumber(){
        Map<String,Object> target=target(),fact=appointment();assertTrue(FieldCoverage.matches(target,fact));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> accepted=FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(receipt(target,"EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("fact"))),cn.bidcheck.compat.Java8Api.mapOf("source", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.listOf(of("originalFactId","fact","fact",fact)));
        assertEquals(1,accepted.size());assertTrue(FieldCoverage.completeRead(accepted.get(0)));assertEquals(false,accepted.get(0).get("absenceProven"));assertEquals("SELECTED_SOURCES_ONLY",accepted.get(0).get("scope"));
        assertFalse(accepted.get(0).containsKey("businessDecision"));assertFalse(accepted.get(0).containsKey("provenDistinctPeople"));
    }
    @Test void onlyVocabularyAliasesShareAnObservation(){
        Map<String,Object> target=target(),fact=appointment();
        for(String role:cn.bidcheck.compat.Java8Api.listOf("施工部分负责人", "施工负责人", "本项目施工负责人")){fact.put("subject",role);assertTrue(FieldCoverage.matches(target,fact),role);}
        for(String role:cn.bidcheck.compat.Java8Api.listOf("项目负责人", "设计部分负责人", "企业主要负责人", "施工企业主要负责人", "施工单位", "未知负责人", "")){fact.put("subject",role);assertFalse(FieldCoverage.matches(target,fact),role);}
        target.put("subject","项目负责人");fact.put("subject","项目经理");assertFalse(FieldCoverage.matches(target,fact),"Evidence-dependent aliases require proof and are not vocabulary-only observation aliases");
    }
    @Test void historicalCertificateEmptyNameOrUnlocatedFactsDoNotCompleteAnAppointmentObservation(){
        for(Map<String,Object> change:cn.bidcheck.compat.Java8Api.listOf(of("scope","HISTORICAL"), of("scope","UNRESOLVED"), of("kind","CERTIFICATE"), of("value",""), of("sourceIds",cn.bidcheck.compat.Java8Api.listOf()))){Map<String,Object> fact=appointment();fact.putAll(change);assertFalse(FieldCoverage.matches(target(),fact),change.toString());}
        Map<String,Object> target=target();target.put("unit","个");assertFalse(FieldCoverage.matches(target,appointment()));
    }
    @Test void qualifiedCountsAndDifferentMemberOrLotMustRemainSeparate(){
        for(Map<String,Object> params:cn.bidcheck.compat.Java8Api.listOf(of("qualificationRequired",true), of("countBasis","QUALIFIED"), of("category","注册建造师"), of("grade","一级"), of("profession","建筑工程"), of("employer","甲公司"), of("lot","一标段"))){Map<String,Object> target=target();target.put("parameters",params);assertFalse(FieldCoverage.matches(target,appointment()),params.toString());}
        Map<String,Object> target=target();target.put("field","合格人数");assertFalse(FieldCoverage.matches(target,appointment()));
        target=target();target.put("parameters",cn.bidcheck.compat.Java8Api.mapOf("employer", "甲公司", "lot", "一标段"));Map<String,Object> fact=appointment();fact.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("employer", "甲公司", "lot", "一标段"));assertTrue(FieldCoverage.matches(target,fact));fact.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("employer", "乙公司", "lot", "一标段"));assertFalse(FieldCoverage.matches(target,fact));
    }
    @Test void onlyPersonCountObservationKeysInvalidateTheOldIncompatiblePolicy(){
        Map<String,Object> count=target();assertNotEquals(hash(FieldCoverage.POLICY+canonical(count)),FieldCoverage.key(count));
        for(String predicate:cn.bidcheck.compat.Java8Api.listOf("NUMBER", "CERTIFICATE", "VALIDITY", "PERSON_ROLE", "MATERIAL", "RELATION")){Map<String,Object> other=target();other.put("predicate",predicate);assertEquals(hash(FieldCoverage.POLICY+canonical(other)),FieldCoverage.key(other),predicate);}
        Map<String,Object> old=receipt(count,"READ_NOT_FOUND",cn.bidcheck.compat.Java8Api.listOf());old.put("observationKey",hash(FieldCoverage.POLICY+canonical(count)));
        assertTrue(FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(old),cn.bidcheck.compat.Java8Api.mapOf("source", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.listOf()).isEmpty());
        assertEquals(1,FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(receipt(count,"READ_NOT_FOUND",cn.bidcheck.compat.Java8Api.listOf())),cn.bidcheck.compat.Java8Api.mapOf("source", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.listOf()).size());
    }
    @Test void extractedReceiptStillRequiresMatchingVerifiedFactsAndTheSameSources(){
        Map<String,Object> target=target();assertTrue(FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(receipt(target,"EXTRACTED",cn.bidcheck.compat.Java8Api.listOf())),cn.bidcheck.compat.Java8Api.mapOf("source", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.listOf()).isEmpty());
        Map<String,Object> wrong=appointment();wrong.put("subject","设计负责人");assertTrue(FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(receipt(target,"EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("fact"))),cn.bidcheck.compat.Java8Api.mapOf("source", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.listOf(of("originalFactId","fact","fact",wrong))).isEmpty());
        Map<String,Object> elsewhere=appointment();elsewhere.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("another-source"));assertTrue(FieldCoverage.validate(cn.bidcheck.compat.Java8Api.listOf(receipt(target,"EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("fact"))),cn.bidcheck.compat.Java8Api.mapOf("source", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.listOf(of("originalFactId","fact","fact",elsewhere))).isEmpty());
    }
}
