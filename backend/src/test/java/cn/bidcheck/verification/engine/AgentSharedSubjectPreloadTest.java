package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSharedSubjectPreloadTest {
    static final String TEXT="本项目人员配置\n项目负责人：测试甲\n姓名：测试甲\n拟在本合同任职：项目负责人";
    Rule rule(){return new EvidenceInvestigationTest().rule();}
    EvidenceCorpus corpus(int version){return new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",version,"无关的首页",TEXT)));}
    Map<String,Object> entry(EvidenceCorpus corpus){Fact fact=corpus.nativeFacts(List.of(rule()),List.of(corpus.all.get(1))).stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.value().equals("测试甲")).findFirst().orElseThrow();return SharedFactReuseTest.entry(corpus,SharedFactReuse.value(fact));}
    @Test void startupLoadsRoleFactFromCurrentManifestBeforeAnySourceIsDisclosed(){
        EvidenceCorpus corpus=corpus(1);List<Map<String,Object>> requests=new ArrayList<>();Map<String,Object> context=new LinkedHashMap<>();
        Map<String,Object> previousEngineFact=entry(corpus);previousEngineFact.put("engineVersion","2.28-previous-engine");previousEngineFact.put("observationPolicy",cn.bidcheck.verification.facts.FieldCoverage.POLICY);
        context.put("sharedFactLookup",(Function<Map<String,Object>,Object>)request->{requests.add(request);return List.of(previousEngineFact);});
        RunSession session=new RunSession(ModelGateway.disabled(),context,"fixture");var host=new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",List.of(rule()),"LOCAL",List.of()),List.of(rule()),corpus,new LinkedHashMap<>(),context,new SharedFactReuse(corpus,context,"fixture","fixture"),session,null,new ArrayList<>(),Set.of());
        Map<String,Object> start=host.start();assertTrue(host.disclosed.isEmpty());assertFalse(host.facts.isEmpty());
        assertTrue(host.facts.values().stream().anyMatch(f->f.kind().equals("PERSON_ROLE")&&f.value().equals("测试甲")));assertTrue(start.toString().contains("测试甲"));
        var request=requests.get(0);assertEquals("CANONICAL_SUBJECT",request.get("lookupMode"));assertEquals("SUBJECT_PREREQUISITES",request.get("lookupPhase"));assertTrue(((List<?>)request.get("subjects")).contains("项目负责人"));assertEquals(corpus.all.size(),((List<?>)request.get("sourceIds")).size());assertEquals(false,request.get("includeFieldCoverage"));
        assertTrue(requests.stream().anyMatch(r->"SUBJECT_ENTITY_DETAILS".equals(r.get("lookupPhase"))&&((List<?>)r.get("subjects")).contains("测试甲")),"Resolved role must trigger a concrete-person profile lookup");
        int lookups=requests.size();host.start();assertEquals(lookups,requests.size(),"Repeated start must not repeat subject discovery");
    }
    @Test void subjectLookupStillRejectsOldVersionAndUnrelatedRole(){
        EvidenceCorpus previous=corpus(1),current=corpus(2);var cached=entry(previous);
        SharedFactReuse shared=new SharedFactReuse(current,Map.of("sharedFactLookup",(Function<Map<String,Object>,Object>)r->List.of(cached)),"fixture","fixture");
        assertTrue(shared.preloadSubjects(List.of(rule()),List.of()).isEmpty());
        Map<String,Object> unrelated=entry(current);Map<String,Object> fact=new LinkedHashMap<>((Map<String,Object>)unrelated.get("fact"));fact.put("subject","施工部分负责人");unrelated.put("fact",fact);
        SharedFactReuse filtered=new SharedFactReuse(current,Map.of("sharedFactLookup",(Function<Map<String,Object>,Object>)r->List.of(unrelated)),"fixture","fixture");
        assertTrue(filtered.preloadSubjects(List.of(rule()),List.of()).isEmpty());
    }
}
