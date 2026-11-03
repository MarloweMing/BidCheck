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
    static final String HASH=cn.bidcheck.compat.Java8Api.repeat("a", 64);
    final MemoryRepository repository=new MemoryRepository();
    final EvidenceGraphService service=new EvidenceGraphService(repository,new ObjectMapper());

    @Test void buildsTraceablePersonnelCertificateEmployerAndConsortiumProofPathsAndReusesRevision(){
        Document doc=document(1,HASH);List<Map<String,Object>> occurrences=new ArrayList<>();
        occurrences.add(personRole(doc,"role","项目经理","张三","CURRENT_PROJECT","拟在本合同任职：项目经理 张三"));
        occurrences.add(certificate(doc,"cert","张三","person:zhang","一级建造师","CERT-001","张三 一级建造师证书 注册编号 CERT-001"));
        occurrences.add(relation(doc,"employer","person:zhang","张三","所属单位","甲公司","张三 所属单位 甲公司"));
        occurrences.add(relation(doc,"member","company:a","甲公司","联合体成员","本项目联合体","甲公司 联合体成员 本项目联合体"));
        cn.bidcheck.verification.graph.EvidenceGraph.BuildResult first=service.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc),cn.bidcheck.compat.Java8Api.mapOf("occurrences", occurrences));
        assertFalse(first.reused());assertEquals(5,first.snapshot().relations().size());
        assertTrue(first.snapshot().relations().stream().allMatch(r->!cn.bidcheck.compat.Java8Api.isBlank(r.subjectEntityKey())&&!cn.bidcheck.compat.Java8Api.isBlank(r.objectEntityKey())&&!r.sources().isEmpty()));
        assertTrue(first.snapshot().relations().stream().allMatch(r->r.sources().stream().allMatch(s->HASH.equals(s.documentHash())&&s.documentVersion()==1&&s.locationNumber()==1)));

        String certificate=first.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.CERTIFICATE).findFirst().orElseThrow(java.util.NoSuchElementException::new).entityKey();
        String company=first.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.COMPANY&&"甲公司".equals(e.displayName())).findFirst().orElseThrow(java.util.NoSuchElementException::new).entityKey();
        String consortium=first.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.CONSORTIUM).findFirst().orElseThrow(java.util.NoSuchElementException::new).entityKey();
        assertTrue(service.proofPath("project",first.snapshot().revision().id(),"person:zhang",certificate,cn.bidcheck.compat.Java8Api.setOf("HOLDS_CERTIFICATE"),1).found());
        assertTrue(service.proofPath("project",first.snapshot().revision().id(),"person:zhang",company,cn.bidcheck.compat.Java8Api.setOf("EMPLOYED_BY"),1).found());
        assertTrue(service.proofPath("project",first.snapshot().revision().id(),company,consortium,cn.bidcheck.compat.Java8Api.setOf("MEMBER_OF"),1).found());
        cn.bidcheck.verification.graph.EvidenceGraph.BuildResult again=service.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc),cn.bidcheck.compat.Java8Api.mapOf("occurrences", occurrences),first.snapshot().revision().id());
        assertTrue(again.reused());assertTrue(again.changedDependencyKeys().isEmpty());assertEquals(1,repository.writes);
    }

    @Test void historicalAppointmentCannotBecomeCurrentProjectProofAndCandidatesDoNotSatisfyProofPath(){
        Document doc=document(1,HASH);java.util.Map<java.lang.String, java.lang.Object> historical=personRole(doc,"old","项目经理","李四","HISTORICAL_PROJECT","历史业绩：项目经理 李四");
        java.util.Map<java.lang.String, java.lang.Object> candidate=personRole(doc,"candidate","项目经理","王五","CURRENT_PROJECT","王五个人简历");
        cn.bidcheck.verification.graph.EvidenceGraph.BuildResult result=service.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc),cn.bidcheck.compat.Java8Api.mapOf("occurrences", cn.bidcheck.compat.Java8Api.listOf(historical, candidate)));
        String project=result.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.PROJECT).findFirst().orElseThrow(java.util.NoSuchElementException::new).entityKey();
        String historicalRole=result.snapshot().entities().stream().filter(e->e.type()==EvidenceGraph.EntityType.ROLE&&"项目经理".equals(e.displayName()))
            .filter(e->e.sourceOccurrenceIds().contains("occurrence:old")).findFirst().orElseThrow(java.util.NoSuchElementException::new).entityKey();
        assertTrue(result.snapshot().relations().stream().noneMatch(r->"person:li".equals(r.subjectEntityKey())&&"APPOINTED_TO".equals(r.predicate())));
        assertEquals(EvidenceGraph.RelationStatus.CANDIDATE,result.snapshot().relations().stream().filter(r->"person:wang".equals(r.subjectEntityKey())&&"APPOINTED_TO".equals(r.predicate())).findFirst().orElseThrow(java.util.NoSuchElementException::new).status());
        assertFalse(service.proofPath("project",result.snapshot().revision().id(),"person:wang",project,cn.bidcheck.compat.Java8Api.setOf("APPOINTED_TO"),1).found());
        assertFalse(service.proofPath("project",result.snapshot().revision().id(),"person:li",historicalRole,cn.bidcheck.compat.Java8Api.setOf("SERVED_AS"),1).found());
        assertFalse(service.proofPath("project",result.snapshot().revision().id(),"person:li",historicalRole,cn.bidcheck.compat.Java8Api.setOf("SERVED_AS"),cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT"),1).found());
        assertTrue(service.proofPath("project",result.snapshot().revision().id(),"person:li",historicalRole,cn.bidcheck.compat.Java8Api.setOf("SERVED_AS"),cn.bidcheck.compat.Java8Api.setOf("HISTORICAL_PROJECT"),1).found());
        assertTrue(service.relations("project",result.snapshot().revision().id(),"person:li","SERVED_AS",cn.bidcheck.compat.Java8Api.setOf()).isEmpty());
        assertEquals(1,service.relations("project",result.snapshot().revision().id(),"person:li","SERVED_AS",cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.setOf("HISTORICAL_PROJECT")).size());
    }

    @Test void changedFactsCreateNewRevisionAndMapOnlyAffectedTemplateNeeds(){
        Document doc=document(1,HASH);cn.bidcheck.verification.graph.EvidenceGraph.BuildResult old=service.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc),cn.bidcheck.compat.Java8Api.mapOf("occurrences", cn.bidcheck.compat.Java8Api.listOf(personRole(doc,"a","项目经理","张三","CURRENT_PROJECT","项目经理 张三"))));
        List<Map<String,Object>> updated=cn.bidcheck.compat.Java8Api.listOf(personRole(doc,"a","项目经理","张三","CURRENT_PROJECT","项目经理 张三"), certificate(doc,"b","张三","person:zhang","一级建造师","CERT-1","张三 一级建造师 CERT-1"));
        cn.bidcheck.verification.graph.EvidenceGraph.BuildResult current=service.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc),cn.bidcheck.compat.Java8Api.mapOf("occurrences", updated),old.snapshot().revision().id());
        assertNotEquals(old.snapshot().revision().id(),current.snapshot().revision().id());assertTrue(current.changedDependencyKeys().contains("graph:predicate:HOLDS_CERTIFICATE"));
        EvidenceNeed cert=new EvidenceNeed("cp:cert",TemplateTypes.NeedKind.CERTIFICATE,SubjectSelector.personnel("项目经理"),cn.bidcheck.compat.Java8Api.setOf("certificateNumber"),cn.bidcheck.compat.Java8Api.mapOf(),true,false);
        EvidenceNeed price=new EvidenceNeed("cp:price",TemplateTypes.NeedKind.PRICE,SubjectSelector.bidder(),cn.bidcheck.compat.Java8Api.setOf("amount"),cn.bidcheck.compat.Java8Api.mapOf(),true,false);
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("cp:cert"),EvidenceGraphDependencyAdapter.affectedNeedKeys(cn.bidcheck.compat.Java8Api.listOf(cert, price),current.changedDependencyKeys()));
        Map<String,Object> persistedDependencyGraph=cn.bidcheck.compat.Java8Api.mapOf("nodes", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("attributes", cn.bidcheck.compat.Java8Api.mapOf("needKey", "cp:cert", "kind", "CERTIFICATE")), cn.bidcheck.compat.Java8Api.mapOf("attributes", cn.bidcheck.compat.Java8Api.mapOf("needKey", "cp:price", "kind", "PRICE"))));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("cp:cert"),EvidenceGraphDependencyAdapter.affectedNeedKeys(persistedDependencyGraph,current.changedDependencyKeys()));
    }

    @Test void invalidOrForeignSourceVersionIsNeverAdopted(){
        Document doc=document(1,HASH);Map<String,Object> occurrence=personRole(doc,"bad","项目经理","张三","CURRENT_PROJECT","项目经理 张三");
        @SuppressWarnings("unchecked") List<Map<String,Object>> sources=(List<Map<String,Object>>)occurrence.get("sources");sources.get(0).put("documentVersion",2);
        cn.bidcheck.verification.graph.EvidenceGraph.Snapshot graph=service.build("project","engine",cn.bidcheck.compat.Java8Api.listOf(doc),cn.bidcheck.compat.Java8Api.mapOf("occurrences", cn.bidcheck.compat.Java8Api.listOf(occurrence))).snapshot();
        assertTrue(graph.relations().isEmpty());assertEquals(1,graph.diagnostics().get("skippedOccurrences"));
    }

    static Document document(int version,String hash){return new Document("bid","project","BID","投标文件",version,hash,"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"fixture","PAGE","OCR","")),cn.bidcheck.compat.Java8Api.listOf());}
    static Map<String,Object> personRole(Document doc,String id,String role,String person,String scope,String quote){
        String key=person.equals("李四")?"person:li":person.equals("王五")?"person:wang":"person:zhang";
        return occurrence(doc,id,cn.bidcheck.compat.Java8Api.mapOf("kind", "PERSON_ROLE", "subject", role, "field", "岗位", "rawValue", person, "value", person, "unit", "", "basis", "", "scope", scope, "attributes", cn.bidcheck.compat.Java8Api.mapOf()),cn.bidcheck.compat.Java8Api.mapOf("id", key, "type", "PERSON", "identityStrength", "STRONG", "displayName", person),quote);
    }
    static Map<String,Object> certificate(Document doc,String id,String holder,String holderKey,String type,String number,String quote){
        return occurrence(doc,id,cn.bidcheck.compat.Java8Api.mapOf("kind", "CERTIFICATE", "subject", holder, "field", "证书", "rawValue", number, "value", number, "unit", "", "basis", "", "scope", "CURRENT_PROJECT", "attributes", cn.bidcheck.compat.Java8Api.mapOf("category", type, "number", number)),cn.bidcheck.compat.Java8Api.mapOf("id", "certificate:"+number, "type", "CERTIFICATE", "identityStrength", "STRONG", "displayName", type, "holderIdentity", cn.bidcheck.compat.Java8Api.mapOf("id", holderKey, "type", "PERSON", "identityStrength", "STRONG", "displayName", holder)),quote);
    }
    static Map<String,Object> relation(Document doc,String id,String entityKey,String subject,String field,String object,String quote){
        String type=subject.endsWith("公司")?"COMPANY":"PERSON";
        return occurrence(doc,id,cn.bidcheck.compat.Java8Api.mapOf("kind", "RELATION", "subject", subject, "field", field, "rawValue", object, "value", object, "unit", "", "basis", "", "scope", "CURRENT_PROJECT", "attributes", cn.bidcheck.compat.Java8Api.mapOf("object", object)),cn.bidcheck.compat.Java8Api.mapOf("id", entityKey, "type", type, "identityStrength", "STRONG", "displayName", subject),quote);
    }
    static Map<String,Object> occurrence(Document doc,String id,Map<String,Object> fact,Map<String,Object> entity,String quote){Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("documentId",doc.id());evidence.put("documentName",doc.name());evidence.put("page",1);evidence.put("locationType","PAGE");evidence.put("quote",quote);evidence.put("anchor","");evidence.put("confidence",1d);Map<String,Object> source=new LinkedHashMap<>();source.put("id","source:"+id);source.put("sourceFingerprint","fingerprint:"+id);source.put("documentId",doc.id());source.put("documentVersion",doc.version());source.put("documentHash",doc.sha256());source.put("parserFingerprint","parser:"+id);source.put("evidence",evidence);Map<String,Object> out=new LinkedHashMap<>();out.put("occurrenceId","occurrence:"+id);out.put("fact",new LinkedHashMap<>(fact));out.put("entity",new LinkedHashMap<>(entity));out.put("sources",new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(source)));return out;}

    static final class MemoryRepository implements EvidenceGraphRepository {final Map<String,EvidenceGraph.Snapshot> rows=new LinkedHashMap<>();int writes;
        public Optional<EvidenceGraph.Snapshot> find(String project,String revision){EvidenceGraph.Snapshot value=rows.get(revision);return value!=null&&project.equals(value.revision().projectId())?Optional.of(value):Optional.empty();}
        public EvidenceGraph.Snapshot publish(EvidenceGraph.Snapshot value){EvidenceGraph.Snapshot old=rows.putIfAbsent(value.revision().id(),value);if(old==null){writes++;return value;}return old;}}
}
