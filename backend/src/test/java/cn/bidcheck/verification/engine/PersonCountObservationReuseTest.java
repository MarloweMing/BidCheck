package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.SharedFactReuseTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonCountObservationReuseTest {
    Rule count(){return new Rule("count","施工负责人至少2人","PERSON_COUNT","施工负责人","count",">=","2","人",Map.of(),"施工负责人至少2人","",List.of());}
    EvidenceCorpus corpus(){return new EvidenceCorpus(List.of(doc("bid","project",1,"人员简历表\n姓名：张三\n拟在本合同任职：施工负责人\n工作经验：五年")));}
    Fact appointment(EvidenceCorpus corpus){return new Fact("appointment","PERSON_ROLE","施工负责人","姓名","张三","张三","","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of());}

    @Test void verifiedAppointmentIsReusedWithoutPretendingTwoDistinctPeopleExist(){
        var fixture=new SharedFactReuseTest();var ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=corpus();Rule rule=count();
        SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"fixture","engine");first.publish(List.of(appointment(corpus)),corpus.all,List.of(rule),List.of(),true,"EXTRACTED");
        assertEquals("EXTRACTED",first.coverage().get(0).get("status"));
        SharedFactReuse second=new SharedFactReuse(corpus,ledger.context(),"fixture","engine");List<Fact> facts=second.lookup(List.of(rule),corpus.all,List.of());assertEquals(1,facts.size());
        RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of());assertEquals("UNDETERMINED",evaluator.evaluate(rule).status());assertTrue(second.extractionRules(List.of(rule),corpus.all,evaluator).isEmpty());assertTrue(second.pendingSources(rule,corpus.all).isEmpty());assertEquals(false,second.coverage().get(0).get("absenceProven"));
        assertEquals("PERSON_COUNT_IDENTITY_REQUIRED",evaluator.evaluate(rule).gaps().get(0).code());
    }
    @Test void rejectedAppointmentIsNotRecordedAsANegativeReadEvenWhenRuleFieldIsCount(){
        EvidenceCorpus corpus=corpus();SharedFactReuse shared=new SharedFactReuse(corpus,Map.of(),"fixture","engine");
        shared.publish(List.of(),corpus.all,List.of(count()),List.of(),false,"EXTRACTED",List.of(linked("kind","PERSON_ROLE","field","姓名","sourceIds",List.of(corpus.all.get(0).id()),"code","PERSON_ROLE_UNGROUNDED")));
        assertEquals("FAILED",shared.coverage().get(0).get("status"));assertFalse(shared.pendingSources(count(),corpus.all).isEmpty());
    }
    @Test void unrelatedRejectedCertificateDoesNotInvalidateCompletedAppointment(){
        EvidenceCorpus corpus=corpus();SharedFactReuse shared=new SharedFactReuse(corpus,Map.of(),"fixture","engine");
        shared.publish(List.of(appointment(corpus)),corpus.all,List.of(count()),List.of(),false,"EXTRACTED",List.of(linked("kind","CERTIFICATE","field","license","sourceIds",List.of(corpus.all.get(0).id()),"code","RAW_VALUE_UNGROUNDED")));
        assertEquals("EXTRACTED",shared.coverage().get(0).get("status"));assertTrue(shared.pendingSources(count(),corpus.all).isEmpty());
    }
    @Test void newWindowStillRequiresReadingAndNoAppointmentIsNotAProvenMissingPerson(){
        var fixture=new SharedFactReuseTest();var ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("bid","project",1,"说明一","说明二")));
        new SharedFactReuse(corpus,ledger.context(),"fixture","engine").publish(List.of(),List.of(corpus.all.get(0)),List.of(count()),List.of(),true,"EXTRACTED");
        SharedFactReuse current=new SharedFactReuse(corpus,ledger.context(),"fixture","engine");List<Fact> facts=current.lookup(List.of(count()),corpus.all,List.of());assertEquals(Set.of(corpus.all.get(1).id()),current.pendingSources(count(),corpus.all));assertEquals("UNDETERMINED",new RuleEvaluator(facts,corpus,Map.of()).evaluate(count()).status());assertEquals(false,current.coverage().get(0).get("absenceProven"));
    }
}
