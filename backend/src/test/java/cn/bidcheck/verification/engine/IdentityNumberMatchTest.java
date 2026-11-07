package cn.bidcheck.verification.engine;

import cn.bidcheck.service.InferenceConfirmationServiceTest;
import cn.bidcheck.verification.source.CitizenId;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityNumberMatchTest {
    private static final String FIRST="110101198001010010",SECOND="110101199001010015";
    private final Rule rule=new Rule("credential","证书对应关系","CERTIFICATE","项目负责人","证书","","","",
        Collections.singletonMap("category","注册证书"),"项目负责人注册证书","",Collections.emptyList());
    private final Decision unknown=new Decision("credential","证书对应关系","UNDETERMINED","待核",Collections.emptyList(),
        Collections.singletonList(gap("ENTITY_IDENTITY_AMBIGUOUS","待确认同一人")),Collections.emptyMap());
    private static class Pair {
        final EvidenceCorpus corpus;final Fact role,proof;final List<Fact> facts;
        Pair(String roleNumber,String proofNumber,boolean split){
            String appointment="本项目人员配置\n项目负责人：李明\n姓 名 李明 年龄 40岁\n"+(split
                ?roleNumber.substring(0,13)+"\n职 称 身份证号 拟在本合同任职 项目负责人\n工程师 "+roleNumber.substring(13)
                :"身份证号："+roleNumber);
            corpus=new EvidenceCorpus(Collections.singletonList(SharedFactReuseTest.doc("bid","project",1,appointment,
                "注册证书\n姓名：李明\n身份证号码："+proofNumber+"\n注册编号：R100")));
            role=new Fact("role","PERSON_ROLE","项目负责人","personName","李明","李明","","","CURRENT_PROJECT",
                Collections.singletonList(corpus.all.get(0).id()),Collections.emptyMap());
            proof=new Fact("cert","CERTIFICATE","李明","证书","注册证书","R100","","","CURRENT_PROJECT",
                Collections.singletonList(corpus.all.get(1).id()),linked("category","注册证书","number","R100","numberField","注册编号"));
            facts=Arrays.asList(role,proof);
        }
    }
    @Test void splitIdAcrossLinesMatchesContinuousIdWithoutCard(){
        Pair pair=new Pair(FIRST,FIRST,true);
        assertTrue(IdentityNumberEvidence.read(pair.role,pair.corpus).complete.isEmpty(),"split text alone is not an identity");
        assertEquals(IdentityNumberEvidence.Match.SAME,IdentityNumberEvidence.compare(pair.role,pair.proof,pair.corpus));
        Map<String,Object> edge=new SubjectIdentity(pair.facts,pair.corpus,Collections.emptyList()).bindingBetween(pair.role,pair.proof);
        assertEquals("ID_NUMBER_MATCH",edge.get("method"));assertEquals(false,edge.get("businessProof"));
        assertTrue(ProgrammaticInferenceCandidates.collect(rule,unknown,pair.facts,pair.corpus).isEmpty());
    }
    @Test void invalidChecksumIsIgnored(){
        Pair pair=new Pair(FIRST.substring(0,17)+"1",FIRST,true);
        assertFalse(CitizenId.valid(FIRST.substring(0,17)+"1"));
        assertEquals(IdentityNumberEvidence.Match.INSUFFICIENT,IdentityNumberEvidence.compare(pair.role,pair.proof,pair.corpus));
        assertFalse(ConfirmedSubjectBindings.matchesPair(pair.role,pair.proof,pair.facts,pair.corpus));
    }
    @Test void differentIdsIsConflictNotCard(){
        Pair pair=new Pair(FIRST,SECOND,false);
        Decision result=IdentityNumberEvidence.attach(rule,unknown,pair.facts,pair.corpus);
        assertTrue(result.gaps().stream().anyMatch(g->"SUBJECT_IDENTITY_CONFLICT".equals(g.code())));
        assertTrue(result.gaps().stream().anyMatch(g->"ID_NUMBER_PAIR_CONFLICT".equals(g.code())));
        assertTrue(ProgrammaticInferenceCandidates.collect(rule,result,pair.facts,pair.corpus).isEmpty());
    }
    @Test void oneSidedIdStillAsks(){
        Pair pair=new Pair("",FIRST,false);
        assertEquals(IdentityNumberEvidence.Match.INSUFFICIENT,IdentityNumberEvidence.compare(pair.role,pair.proof,pair.corpus));
        assertEquals(1,ProgrammaticInferenceCandidates.collect(rule,unknown,pair.facts,pair.corpus).size());
    }
    @Test void ambiguousNumbersOnOneSideDoNotChooseOrEstablishAConflict(){
        for(String other:Arrays.asList("",FIRST)){
            Pair pair=new Pair(FIRST+" 身份证号："+SECOND,other,false);
            assertEquals(IdentityNumberEvidence.Match.INSUFFICIENT,IdentityNumberEvidence.compare(pair.role,pair.proof,pair.corpus));
            assertEquals(1,ProgrammaticInferenceCandidates.collect(rule,unknown,pair.facts,pair.corpus).size());
            assertFalse(IdentityNumberEvidence.attach(rule,unknown,pair.facts,pair.corpus).gaps().stream().anyMatch(g->"ID_NUMBER_PAIR_CONFLICT".equals(g.code())));
        }
    }
    @Test void passedCheckpointHidesInheritedPendingCard(){
        Map<String,Object> export=InferenceConfirmationServiceTest.cardsForPassedCheckpoint(false);
        assertTrue(RuleEvaluator.maps(export.get("claims")).isEmpty());
        assertEquals("CHECKPOINT_ALREADY_DECIDED",RuleEvaluator.maps(export.get("supersededClaims")).get(0).get("supersededReason"));
    }
    @Test void confirmedCardIsKept(){
        Map<String,Object> export=InferenceConfirmationServiceTest.cardsForPassedCheckpoint(true);
        assertEquals("CONFIRMED",RuleEvaluator.maps(export.get("claims")).get(0).get("status"));
        assertTrue(RuleEvaluator.maps(export.get("supersededClaims")).isEmpty());
    }
    @Test void exportMasksValidIdsAndEveryCopyOfAnExplicitlyLabelledId() throws Exception {
        String invalid=FIRST.substring(0,17)+"1",other=FIRST.substring(0,17)+"2";
        String data="{\"unlabelled\":\""+invalid+"\",\"text\":\"身份证号："+invalid+"\",\"driverLicence\":\""+other+"\",\"someText\":\""+FIRST+"\"}";
        String masked=CitizenId.maskJson(data);
        assertFalse(masked.contains(invalid));assertFalse(masked.contains(FIRST));assertTrue(masked.contains(other));
        assertEquals(other,new com.fasterxml.jackson.databind.ObjectMapper().readTree(masked).path("driverLicence").asText());
    }
}
