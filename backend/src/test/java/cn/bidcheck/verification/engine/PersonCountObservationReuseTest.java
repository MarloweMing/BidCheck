package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.SharedFactReuseTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonCountObservationReuseTest {
    Rule count(){return new Rule("count","施工负责人至少2人","PERSON_COUNT","施工负责人","count",">=","2","人",cn.bidcheck.compat.Java8Api.mapOf(),"施工负责人至少2人","",cn.bidcheck.compat.Java8Api.listOf());}
    EvidenceCorpus corpus(){return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc("bid","project",1,"人员简历表\n姓名：张三\n拟在本合同任职：施工负责人\n工作经验：五年")));}
    Fact appointment(EvidenceCorpus corpus){return new Fact("appointment","PERSON_ROLE","施工负责人","姓名","张三","张三","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());}

    @Test void verifiedAppointmentIsReusedWithoutPretendingTwoDistinctPeopleExist(){
        cn.bidcheck.verification.engine.SharedFactReuseTest fixture=new SharedFactReuseTest();cn.bidcheck.verification.engine.SharedFactReuseTest.MemoryLedger ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=corpus();Rule rule=count();
        SharedFactReuse first=new SharedFactReuse(corpus,ledger.context(),"fixture","engine");first.publish(cn.bidcheck.compat.Java8Api.listOf(appointment(corpus)),corpus.all,cn.bidcheck.compat.Java8Api.listOf(rule),cn.bidcheck.compat.Java8Api.listOf(),true,"EXTRACTED");
        assertEquals("EXTRACTED",first.coverage().get(0).get("status"));
        SharedFactReuse second=new SharedFactReuse(corpus,ledger.context(),"fixture","engine");List<Fact> facts=second.lookup(cn.bidcheck.compat.Java8Api.listOf(rule),corpus.all,cn.bidcheck.compat.Java8Api.listOf());assertEquals(1,facts.size());
        RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,cn.bidcheck.compat.Java8Api.mapOf());assertEquals("UNDETERMINED",evaluator.evaluate(rule).status());assertTrue(second.extractionRules(cn.bidcheck.compat.Java8Api.listOf(rule),corpus.all,evaluator).isEmpty());assertTrue(second.pendingSources(rule,corpus.all).isEmpty());assertEquals(false,second.coverage().get(0).get("absenceProven"));
        assertEquals("PERSON_COUNT_IDENTITY_REQUIRED",evaluator.evaluate(rule).gaps().get(0).code());
    }
    @Test void rejectedAppointmentIsNotRecordedAsANegativeReadEvenWhenRuleFieldIsCount(){
        EvidenceCorpus corpus=corpus();SharedFactReuse shared=new SharedFactReuse(corpus,cn.bidcheck.compat.Java8Api.mapOf(),"fixture","engine");
        shared.publish(cn.bidcheck.compat.Java8Api.listOf(),corpus.all,cn.bidcheck.compat.Java8Api.listOf(count()),cn.bidcheck.compat.Java8Api.listOf(),false,"EXTRACTED",cn.bidcheck.compat.Java8Api.listOf(linked("kind","PERSON_ROLE","field","姓名","sourceIds",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),"code","PERSON_ROLE_UNGROUNDED")));
        assertEquals("FAILED",shared.coverage().get(0).get("status"));assertFalse(shared.pendingSources(count(),corpus.all).isEmpty());
    }
    @Test void unrelatedRejectedCertificateDoesNotInvalidateCompletedAppointment(){
        EvidenceCorpus corpus=corpus();SharedFactReuse shared=new SharedFactReuse(corpus,cn.bidcheck.compat.Java8Api.mapOf(),"fixture","engine");
        shared.publish(cn.bidcheck.compat.Java8Api.listOf(appointment(corpus)),corpus.all,cn.bidcheck.compat.Java8Api.listOf(count()),cn.bidcheck.compat.Java8Api.listOf(),false,"EXTRACTED",cn.bidcheck.compat.Java8Api.listOf(linked("kind","CERTIFICATE","field","license","sourceIds",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),"code","RAW_VALUE_UNGROUNDED")));
        assertEquals("EXTRACTED",shared.coverage().get(0).get("status"));assertTrue(shared.pendingSources(count(),corpus.all).isEmpty());
    }
    @Test void newWindowStillRequiresReadingAndNoAppointmentIsNotAProvenMissingPerson(){
        cn.bidcheck.verification.engine.SharedFactReuseTest fixture=new SharedFactReuseTest();cn.bidcheck.verification.engine.SharedFactReuseTest.MemoryLedger ledger=fixture.new MemoryLedger();EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc("bid","project",1,"说明一","说明二")));
        new SharedFactReuse(corpus,ledger.context(),"fixture","engine").publish(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0)),cn.bidcheck.compat.Java8Api.listOf(count()),cn.bidcheck.compat.Java8Api.listOf(),true,"EXTRACTED");
        SharedFactReuse current=new SharedFactReuse(corpus,ledger.context(),"fixture","engine");List<Fact> facts=current.lookup(cn.bidcheck.compat.Java8Api.listOf(count()),corpus.all,cn.bidcheck.compat.Java8Api.listOf());assertEquals(cn.bidcheck.compat.Java8Api.setOf(corpus.all.get(1).id()),current.pendingSources(count(),corpus.all));assertEquals("UNDETERMINED",new RuleEvaluator(facts,corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(count()).status());assertEquals(false,current.coverage().get(0).get("absenceProven"));
    }
}
