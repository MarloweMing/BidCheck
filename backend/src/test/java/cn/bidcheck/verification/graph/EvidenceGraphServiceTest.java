package cn.bidcheck.verification.graph;

import cn.bidcheck.checktemplate.EvidenceNeed;
import cn.bidcheck.checktemplate.SubjectSelector;
import cn.bidcheck.checktemplate.TemplateTypes;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceGraphServiceTest {
    static final String HASH="a".repeat(64);
    final MemoryRepository repository=new MemoryRepository();
    final EvidenceGraphService service=new EvidenceGraphService(repository,new ObjectMapper());

    @Test void buildsTraceablePersonnelCertificateEmployerAndConsortiumProofPathsAndReusesRevision(){
        Document doc=document(1,HASH);List<Map<String,Object>> occurrences=new ArrayList<>();
        occurrences.add(personRole(doc,"role","项目经理","张三","CURRENT_PROJECT","拟在本合同任职：项目经理 张三"));
        occurrences.add(certificate(doc,"cert","张三","person:zhang","一级建造师","CERT-001","张三 一级建造师证书 注册编号 CERT-001"));
        occurrences.add(relation(doc,"employer","person:zhang","张三","所属单位","甲公司","张三 所属单位 甲公司"));
        occurrences.add(relation(doc,"member","company:a","甲公司","联合体成员","本项目联合体","甲公司 联合体成员 本项目联合体"));
        var first=service.build("project","engine",List.of(doc),Map.of("occurrences",occurrences));
        assertFalse(first.reused());assertEquals(5,first.snapshot().relations().size());
        assertTrue(first.snapshot().relations().stream().allMatch(r->!r.subjectEntityKey().isBlank()&&!r.objectEntityKey().isBlank()&&!r.sources().isEmpty()));
        assertTrue(first.snapshot().relations().stream().allMatch(r->r.sources().stream().allMatch(s->HASH.equals(s.documentHash())&&s.documentVersion()==1&&s.locationNumber()==1)));

        String certificate=first.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.CERTIFICATE).findFirst().orElseThrow().entityKey();
        String company=first.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.COMPANY&&"甲公司".equals(e.displayName())).findFirst().orElseThrow().entityKey();
        String consortium=first.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.CONSORTIUM).findFirst().orElseThrow().entityKey();
        assertTrue(service.proofPath("project",first.snapshot().revision().id(),"person:zhang",certificate,Set.of("HOLDS_CERTIFICATE"),1).found());
        assertTrue(service.proofPath("project",first.snapshot().revision().id(),"person:zhang",company,Set.of("EMPLOYED_BY"),1).found());
        assertTrue(service.proofPath("project",first.snapshot().revision().id(),company,consortium,Set.of("MEMBER_OF"),1).found());
        var again=service.build("project","engine",List.of(doc),Map.of("occurrences",occurrences),first.snapshot().revision().id());
        assertTrue(again.reused());assertTrue(again.changedDependencyKeys().isEmpty());assertEquals(1,repository.writes);
    }

    @Test void historicalAppointmentCannotBecomeCurrentProjectProofAndCandidatesDoNotSatisfyProofPath(){
        Document doc=document(1,HASH);var historical=personRole(doc,"old","项目经理","李四","HISTORICAL_PROJECT","历史业绩：项目经理 李四");
        var candidate=personRole(doc,"candidate","项目经理","王五","CURRENT_PROJECT","王五个人简历");
        var result=service.build("project","engine",List.of(doc),Map.of("occurrences",List.of(historical,candidate)));
        String project=result.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.PROJECT).findFirst().orElseThrow().entityKey();
        String historicalRole=result.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.ROLE&&"项目经理".equals(e.displayName()))
            .filter(e->e.sourceOccurrenceIds().contains("occurrence:old")).findFirst().orElseThrow().entityKey();
        assertTrue(result.snapshot().relations().stream().noneMatch(r->"person:li".equals(r.subjectEntityKey())&&"APPOINTED_TO".equals(r.predicate())));
        assertEquals(EvidenceGraph.RelationStatus.CANDIDATE,result.snapshot().relations().stream().filter(r->"person:wang".equals(r.subjectEntityKey())&&"APPOINTED_TO".equals(r.predicate())).findFirst().orElseThrow().status());
        assertFalse(service.proofPath("project",result.snapshot().revision().id(),"person:wang",project,Set.of("APPOINTED_TO"),1).found());
        assertFalse(service.proofPath("project",result.snapshot().revision().id(),"person:li",historicalRole,Set.of("SERVED_AS"),1).found());
        assertFalse(service.proofPath("project",result.snapshot().revision().id(),"person:li",historicalRole,Set.of("SERVED_AS"),Set.of("CURRENT_PROJECT"),1).found());
        assertTrue(service.proofPath("project",result.snapshot().revision().id(),"person:li",historicalRole,Set.of("SERVED_AS"),Set.of("HISTORICAL_PROJECT"),1).found());
        assertTrue(service.relations("project",result.snapshot().revision().id(),"person:li","SERVED_AS",Set.of()).isEmpty());
        assertEquals(1,service.relations("project",result.snapshot().revision().id(),"person:li","SERVED_AS",Set.of(),Set.of("HISTORICAL_PROJECT")).size());
    }

    @Test void changedFactsCreateNewRevisionAndMapOnlyAffectedTemplateNeeds(){
        Document doc=document(1,HASH);var old=service.build("project","engine",List.of(doc),Map.of("occurrences",List.of(personRole(doc,"a","项目经理","张三","CURRENT_PROJECT","项目经理 张三"))));
        List<Map<String,Object>> updated=List.of(personRole(doc,"a","项目经理","张三","CURRENT_PROJECT","项目经理 张三"),certificate(doc,"b","张三","person:zhang","一级建造师","CERT-1","张三 一级建造师 CERT-1"));
        var current=service.build("project","engine",List.of(doc),Map.of("occurrences",updated),old.snapshot().revision().id());
        assertNotEquals(old.snapshot().revision().id(),current.snapshot().revision().id());assertTrue(current.changedDependencyKeys().contains("graph:predicate:HOLDS_CERTIFICATE"));
        EvidenceNeed cert=new EvidenceNeed("cp:cert",TemplateTypes.NeedKind.CERTIFICATE,SubjectSelector.personnel("项目经理"),Set.of("certificateNumber"),Map.of(),true,false);
        EvidenceNeed price=new EvidenceNeed("cp:price",TemplateTypes.NeedKind.PRICE,SubjectSelector.bidder(),Set.of("amount"),Map.of(),true,false);
        assertEquals(Set.of("cp:cert"),EvidenceGraphDependencyAdapter.affectedNeedKeys(List.of(cert,price),current.changedDependencyKeys()));
        Map<String,Object> persistedDependencyGraph=Map.of("nodes",List.of(
            Map.of("attributes",Map.of("needKey","cp:cert","kind","CERTIFICATE")),
            Map.of("attributes",Map.of("needKey","cp:price","kind","PRICE"))));
        assertEquals(Set.of("cp:cert"),EvidenceGraphDependencyAdapter.affectedNeedKeys(persistedDependencyGraph,current.changedDependencyKeys()));
    }

    @Test void invalidOrForeignSourceVersionIsNeverAdopted(){
        Document doc=document(1,HASH);Map<String,Object> occurrence=personRole(doc,"bad","项目经理","张三","CURRENT_PROJECT","项目经理 张三");
        @SuppressWarnings("unchecked") List<Map<String,Object>> sources=(List<Map<String,Object>>)occurrence.get("sources");sources.get(0).put("documentVersion",2);
        var graph=service.build("project","engine",List.of(doc),Map.of("occurrences",List.of(occurrence))).snapshot();
        assertTrue(graph.relations().isEmpty());assertEquals(1,graph.diagnostics().get("skippedOccurrences"));
    }

    static Document document(int version,String hash){return new Document("bid","project","BID","投标文件",version,hash,"",List.of(new Page(1,"fixture","PAGE","OCR","")),List.of());}
    static Map<String,Object> personRole(Document doc,String id,String role,String person,String scope,String quote){
        String key=person.equals("李四")?"person:li":person.equals("王五")?"person:wang":"person:zhang";
        return occurrence(doc,id,Map.of("kind","PERSON_ROLE","subject",role,"field","岗位","rawValue",person,"value",person,"unit","","basis","","scope",scope,"attributes",Map.of()),Map.of("id",key,"type","PERSON","identityStrength","STRONG","displayName",person),quote);
    }
    static Map<String,Object> certificate(Document doc,String id,String holder,String holderKey,String type,String number,String quote){
        return occurrence(doc,id,Map.of("kind","CERTIFICATE","subject",holder,"field","证书","rawValue",number,"value",number,"unit","","basis","","scope","CURRENT_PROJECT","attributes",Map.of("category",type,"number",number)),Map.of("id","certificate:"+number,"type","CERTIFICATE","identityStrength","STRONG","displayName",type,"holderIdentity",Map.of("id",holderKey,"type","PERSON","identityStrength","STRONG","displayName",holder)),quote);
    }
    static Map<String,Object> relation(Document doc,String id,String entityKey,String subject,String field,String object,String quote){
        String type=subject.endsWith("公司")?"COMPANY":"PERSON";
        return occurrence(doc,id,Map.of("kind","RELATION","subject",subject,"field",field,"rawValue",object,"value",object,"unit","","basis","","scope","CURRENT_PROJECT","attributes",Map.of("object",object)),Map.of("id",entityKey,"type",type,"identityStrength","STRONG","displayName",subject),quote);
    }
    static Map<String,Object> occurrence(Document doc,String id,Map<String,Object> fact,Map<String,Object> entity,String quote){Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("documentId",doc.id());evidence.put("documentName",doc.name());evidence.put("page",1);evidence.put("locationType","PAGE");evidence.put("quote",quote);evidence.put("anchor","");evidence.put("confidence",1d);Map<String,Object> source=new LinkedHashMap<>();source.put("id","source:"+id);source.put("sourceFingerprint","fingerprint:"+id);source.put("documentId",doc.id());source.put("documentVersion",doc.version());source.put("documentHash",doc.sha256());source.put("parserFingerprint","parser:"+id);source.put("evidence",evidence);Map<String,Object> out=new LinkedHashMap<>();out.put("occurrenceId","occurrence:"+id);out.put("fact",new LinkedHashMap<>(fact));out.put("entity",new LinkedHashMap<>(entity));out.put("sources",new ArrayList<>(List.of(source)));return out;}

    static final class MemoryRepository implements EvidenceGraphRepository {final Map<String,EvidenceGraph.Snapshot> rows=new LinkedHashMap<>();int writes;
        public Optional<EvidenceGraph.Snapshot> find(String project,String revision){EvidenceGraph.Snapshot value=rows.get(revision);return value!=null&&project.equals(value.revision().projectId())?Optional.of(value):Optional.empty();}
        public EvidenceGraph.Snapshot publish(EvidenceGraph.Snapshot value){EvidenceGraph.Snapshot old=rows.putIfAbsent(value.revision().id(),value);if(old==null){writes++;return value;}return old;}}
}
