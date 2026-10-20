package cn.bidcheck.verification.graph;

import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceGraphReadServiceTest {
    private static final String HASH="a".repeat(64);

    @Test void defaultsToSupportedCurrentRelationsAndReturnsOnlyTheirEndpointsWithFullSource() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-a","revision-a"));

        EvidenceGraphReadService.GraphView view=fixture.reads.graph("run-a",List.of());

        assertEquals("revision-a",view.revision().id());
        assertEquals(List.of("edge-current"),view.relations().stream().map(EvidenceGraph.Relation::id).toList());
        assertEquals(Set.of("company:a","person:a"),view.entities().stream().map(EvidenceGraph.Entity::entityKey).collect(java.util.stream.Collectors.toSet()));
        EvidenceGraph.SourceRef source=view.relations().get(0).sources().get(0);
        assertAll(()->assertEquals("source-current",source.sourceId()),()->assertEquals("fingerprint-current",source.sourceFingerprint()),
                ()->assertEquals("bid",source.documentId()),()->assertEquals(1,source.documentVersion()),
                ()->assertEquals(HASH,source.documentHash()),()->assertEquals("PAGE",source.locationType()),
                ()->assertEquals(3,source.locationNumber()),()->assertEquals("张三所属单位甲公司",source.quote()));
    }

    @Test void predicateFilterIsAppliedAfterStatusAndScopeFences() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-a","revision-a"));
        assertEquals(1,fixture.reads.graph("run-a",List.of("employed_by")).relations().size());
        assertTrue(fixture.reads.graph("run-a",List.of("HOLDS_CERTIFICATE")).relations().isEmpty());
        ResponseStatusException invalid=assertThrows(ResponseStatusException.class,
                ()->fixture.reads.graph("run-a",List.of("NOT_A_RELATION")));
        assertEquals(HttpStatus.BAD_REQUEST,invalid.getStatusCode());
    }

    @Test void runProjectScopeCannotReadSameRevisionFromAnotherProject() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-b","revision-a"));
        ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->fixture.reads.graph("run-a",List.of()));
        assertEquals(HttpStatus.NOT_FOUND,failure.getStatusCode());
        verify(fixture.store).require("run","run-a");
    }

    @Test void missingRunGraphRevisionReturnsNotFoundWithoutRepositoryLookup() {
        Fixture fixture=fixture("project-a",false);
        ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->fixture.reads.graph("run-a",List.of()));
        assertEquals(HttpStatus.NOT_FOUND,failure.getStatusCode());assertTrue(fixture.repository.rows.isEmpty());
    }

    @Test void proofIsBoundedToSupportedCurrentProjectRelations() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-a","revision-a"));
        EvidenceGraph.ProofPath current=fixture.reads.proof("run-a","person:a","company:a",List.of("EMPLOYED_BY"),1);
        assertTrue(current.found());assertEquals(List.of("edge-current"),current.relations().stream().map(EvidenceGraph.Relation::id).toList());
        assertEquals("source-current",current.sources().get(0).sourceId());
        assertFalse(fixture.reads.proof("run-a","person:a","role:old",List.of("SERVED_AS"),2).found());
        ResponseStatusException depth=assertThrows(ResponseStatusException.class,
                ()->fixture.reads.proof("run-a","person:a","company:a",List.of(),7));
        assertEquals(HttpStatus.BAD_REQUEST,depth.getStatusCode());
    }

    @Test void verificationRunUsesTheSameProjectAndCurrentScopeFences() {
        Store store=mock(Store.class);Stored row=new Stored();row.id="verification-a";row.kind="verification_run";
        row.projectId="project-a";row.parentId="";row.body="verification-body";
        when(store.require("run","verification-a")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在"));
        when(store.require("verification_run","verification-a")).thenReturn(row);
        when(store.map("verification-body")).thenReturn(Map.of("projectId","project-a","evidenceGraph",Map.of("revisionId","revision-a")));
        EvidenceGraphServiceTest.MemoryRepository repository=new EvidenceGraphServiceTest.MemoryRepository();
        repository.publish(snapshot("project-a","revision-a"));
        EvidenceGraphReadService reads=new EvidenceGraphReadService(store,new EvidenceGraphService(repository,new com.fasterxml.jackson.databind.ObjectMapper()));

        assertEquals(List.of("edge-current"),reads.graph("verification-a",List.of()).relations().stream().map(EvidenceGraph.Relation::id).toList());
        assertFalse(reads.proof("verification-a","person:a","role:old",List.of("SERVED_AS"),2).found());
    }

    private static Fixture fixture(String projectId,boolean revision) {
        Store store=mock(Store.class);Stored row=new Stored();row.id="run-a";row.kind="run";row.projectId=projectId;row.parentId=projectId;row.body="run-body";
        when(store.require("run","run-a")).thenReturn(row);
        Map<String,Object> run=new LinkedHashMap<>();run.put("projectId",projectId);
        if(revision)run.put("evidenceGraph",Map.of("revisionId","revision-a"));
        when(store.map("run-body")).thenReturn(run);
        EvidenceGraphServiceTest.MemoryRepository repository=new EvidenceGraphServiceTest.MemoryRepository();
        return new Fixture(store,repository,new EvidenceGraphReadService(store,new EvidenceGraphService(repository,new com.fasterxml.jackson.databind.ObjectMapper())));
    }

    private static EvidenceGraph.Snapshot snapshot(String projectId,String revisionId) {
        List<EvidenceGraph.Entity> entities=List.of(
                entity(revisionId,"person:a",EvidenceGraph.EntityType.PERSON,"张三"),
                entity(revisionId,"company:a",EvidenceGraph.EntityType.COMPANY,"甲公司"),
                entity(revisionId,"certificate:a",EvidenceGraph.EntityType.CERTIFICATE,"一级建造师"),
                entity(revisionId,"role:old",EvidenceGraph.EntityType.ROLE,"历史项目经理"));
        List<EvidenceGraph.Relation> relations=List.of(
                relation("edge-current",revisionId,"person:a","EMPLOYED_BY","company:a","CURRENT_PROJECT",EvidenceGraph.RelationStatus.SUPPORTED,"current"),
                relation("edge-candidate",revisionId,"person:a","HOLDS_CERTIFICATE","certificate:a","CURRENT_PROJECT",EvidenceGraph.RelationStatus.CANDIDATE,"candidate"),
                relation("edge-history",revisionId,"person:a","SERVED_AS","role:old","HISTORICAL_PROJECT",EvidenceGraph.RelationStatus.SUPPORTED,"history"));
        EvidenceGraph.Revision revision=new EvidenceGraph.Revision(revisionId,projectId,"engine","manifest","occurrences",
                EvidenceGraphService.VERSION,"content",List.of(new EvidenceGraph.DocumentRevision("bid",1,HASH)),"2026-10-19T00:00:00Z");
        return new EvidenceGraph.Snapshot(revision,entities,relations,Map.of());
    }

    private static EvidenceGraph.Entity entity(String revision,String key,EvidenceGraph.EntityType type,String name) {
        return new EvidenceGraph.Entity(revision,key,type,"STRONG",name,List.of("occurrence:"+key),Map.of());
    }
    private static EvidenceGraph.Relation relation(String id,String revision,String from,String predicate,String to,String scope,
                                                    EvidenceGraph.RelationStatus status,String suffix) {
        EvidenceGraph.SourceRef source=new EvidenceGraph.SourceRef("source-"+suffix,"fingerprint-"+suffix,"bid",1,HASH,
                "parser-"+suffix,"PAGE",3,"张三所属单位甲公司","anchor",.95);
        return new EvidenceGraph.Relation(id,revision,from,predicate,to,"occurrence:"+suffix,List.of(source),scope,"","",status,"SOURCE_BOUND",Map.of());
    }

    private record Fixture(Store store,EvidenceGraphServiceTest.MemoryRepository repository,EvidenceGraphReadService reads) {}
}
