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
    private static final String HASH=cn.bidcheck.compat.Java8Api.repeat("a", 64);

    @Test void defaultsToSupportedCurrentRelationsAndReturnsOnlyTheirEndpointsWithFullSource() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-a","revision-a"));

        EvidenceGraphReadService.GraphView view=fixture.reads.graph("run-a",cn.bidcheck.compat.Java8Api.listOf());

        assertEquals("revision-a",view.revision().id());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("edge-current"),cn.bidcheck.compat.Java8Api.toList(view.relations().stream().map(EvidenceGraph.Relation::id)));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("company:a", "person:a"),view.entities().stream().map(EvidenceGraph.Entity::entityKey).collect(java.util.stream.Collectors.toSet()));
        EvidenceGraph.SourceRef source=view.relations().get(0).sources().get(0);
        assertAll(()->assertEquals("source-current",source.sourceId()),()->assertEquals("fingerprint-current",source.sourceFingerprint()),
                ()->assertEquals("bid",source.documentId()),()->assertEquals(1,source.documentVersion()),
                ()->assertEquals(HASH,source.documentHash()),()->assertEquals("PAGE",source.locationType()),
                ()->assertEquals(3,source.locationNumber()),()->assertEquals("张三所属单位甲公司",source.quote()));
    }

    @Test void predicateFilterIsAppliedAfterStatusAndScopeFences() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-a","revision-a"));
        assertEquals(1,fixture.reads.graph("run-a",cn.bidcheck.compat.Java8Api.listOf("employed_by")).relations().size());
        assertTrue(fixture.reads.graph("run-a",cn.bidcheck.compat.Java8Api.listOf("HOLDS_CERTIFICATE")).relations().isEmpty());
        ResponseStatusException invalid=assertThrows(ResponseStatusException.class,
                ()->fixture.reads.graph("run-a",cn.bidcheck.compat.Java8Api.listOf("NOT_A_RELATION")));
        assertEquals(HttpStatus.BAD_REQUEST,invalid.getStatus());
    }

    @Test void runProjectScopeCannotReadSameRevisionFromAnotherProject() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-b","revision-a"));
        ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->fixture.reads.graph("run-a",cn.bidcheck.compat.Java8Api.listOf()));
        assertEquals(HttpStatus.NOT_FOUND,failure.getStatus());
        verify(fixture.store).require("run","run-a");
    }

    @Test void missingRunGraphRevisionReturnsNotFoundWithoutRepositoryLookup() {
        Fixture fixture=fixture("project-a",false);
        ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->fixture.reads.graph("run-a",cn.bidcheck.compat.Java8Api.listOf()));
        assertEquals(HttpStatus.NOT_FOUND,failure.getStatus());assertTrue(fixture.repository.rows.isEmpty());
    }

    @Test void proofIsBoundedToSupportedCurrentProjectRelations() {
        Fixture fixture=fixture("project-a",true);fixture.repository.publish(snapshot("project-a","revision-a"));
        EvidenceGraph.ProofPath current=fixture.reads.proof("run-a","person:a","company:a",cn.bidcheck.compat.Java8Api.listOf("EMPLOYED_BY"),1);
        assertTrue(current.found());assertEquals(cn.bidcheck.compat.Java8Api.listOf("edge-current"),cn.bidcheck.compat.Java8Api.toList(current.relations().stream().map(EvidenceGraph.Relation::id)));
        assertEquals("source-current",current.sources().get(0).sourceId());
        assertFalse(fixture.reads.proof("run-a","person:a","role:old",cn.bidcheck.compat.Java8Api.listOf("SERVED_AS"),2).found());
        ResponseStatusException depth=assertThrows(ResponseStatusException.class,
                ()->fixture.reads.proof("run-a","person:a","company:a",cn.bidcheck.compat.Java8Api.listOf(),7));
        assertEquals(HttpStatus.BAD_REQUEST,depth.getStatus());
    }

    @Test void verificationRunUsesTheSameProjectAndCurrentScopeFences() {
        Store store=mock(Store.class);Stored row=new Stored();row.id="verification-a";row.kind="verification_run";
        row.projectId="project-a";row.parentId="";row.body="verification-body";
        when(store.require("run","verification-a")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在"));
        when(store.require("verification_run","verification-a")).thenReturn(row);
        when(store.map("verification-body")).thenReturn(cn.bidcheck.compat.Java8Api.mapOf("projectId", "project-a", "evidenceGraph", cn.bidcheck.compat.Java8Api.mapOf("revisionId", "revision-a")));
        EvidenceGraphServiceTest.MemoryRepository repository=new EvidenceGraphServiceTest.MemoryRepository();
        repository.publish(snapshot("project-a","revision-a"));
        EvidenceGraphReadService reads=new EvidenceGraphReadService(store,new EvidenceGraphService(repository,new com.fasterxml.jackson.databind.ObjectMapper()));

        assertEquals(cn.bidcheck.compat.Java8Api.listOf("edge-current"),cn.bidcheck.compat.Java8Api.toList(reads.graph("verification-a",cn.bidcheck.compat.Java8Api.listOf()).relations().stream().map(EvidenceGraph.Relation::id)));
        assertFalse(reads.proof("verification-a","person:a","role:old",cn.bidcheck.compat.Java8Api.listOf("SERVED_AS"),2).found());
    }

    private static Fixture fixture(String projectId,boolean revision) {
        Store store=mock(Store.class);Stored row=new Stored();row.id="run-a";row.kind="run";row.projectId=projectId;row.parentId=projectId;row.body="run-body";
        when(store.require("run","run-a")).thenReturn(row);
        Map<String,Object> run=new LinkedHashMap<>();run.put("projectId",projectId);
        if(revision)run.put("evidenceGraph",cn.bidcheck.compat.Java8Api.mapOf("revisionId", "revision-a"));
        when(store.map("run-body")).thenReturn(run);
        EvidenceGraphServiceTest.MemoryRepository repository=new EvidenceGraphServiceTest.MemoryRepository();
        return new Fixture(store,repository,new EvidenceGraphReadService(store,new EvidenceGraphService(repository,new com.fasterxml.jackson.databind.ObjectMapper())));
    }

    private static EvidenceGraph.Snapshot snapshot(String projectId,String revisionId) {
        List<EvidenceGraph.Entity> entities=cn.bidcheck.compat.Java8Api.listOf(entity(revisionId,"person:a",EvidenceGraph.EntityType.PERSON,"张三"), entity(revisionId,"company:a",EvidenceGraph.EntityType.COMPANY,"甲公司"), entity(revisionId,"certificate:a",EvidenceGraph.EntityType.CERTIFICATE,"一级建造师"), entity(revisionId,"role:old",EvidenceGraph.EntityType.ROLE,"历史项目经理"));
        List<EvidenceGraph.Relation> relations=cn.bidcheck.compat.Java8Api.listOf(relation("edge-current",revisionId,"person:a","EMPLOYED_BY","company:a","CURRENT_PROJECT",EvidenceGraph.RelationStatus.SUPPORTED,"current"), relation("edge-candidate",revisionId,"person:a","HOLDS_CERTIFICATE","certificate:a","CURRENT_PROJECT",EvidenceGraph.RelationStatus.CANDIDATE,"candidate"), relation("edge-history",revisionId,"person:a","SERVED_AS","role:old","HISTORICAL_PROJECT",EvidenceGraph.RelationStatus.SUPPORTED,"history"));
        EvidenceGraph.Revision revision=new EvidenceGraph.Revision(revisionId,projectId,"engine","manifest","occurrences",
                EvidenceGraphService.VERSION,"content",cn.bidcheck.compat.Java8Api.listOf(new EvidenceGraph.DocumentRevision("bid",1,HASH)),"2026-10-19T00:00:00Z");
        return new EvidenceGraph.Snapshot(revision,entities,relations,cn.bidcheck.compat.Java8Api.mapOf());
    }

    private static EvidenceGraph.Entity entity(String revision,String key,EvidenceGraph.EntityType type,String name) {
        return new EvidenceGraph.Entity(revision,key,type,"STRONG",name,cn.bidcheck.compat.Java8Api.listOf("occurrence:"+key),cn.bidcheck.compat.Java8Api.mapOf());
    }
    private static EvidenceGraph.Relation relation(String id,String revision,String from,String predicate,String to,String scope,
                                                    EvidenceGraph.RelationStatus status,String suffix) {
        EvidenceGraph.SourceRef source=new EvidenceGraph.SourceRef("source-"+suffix,"fingerprint-"+suffix,"bid",1,HASH,
                "parser-"+suffix,"PAGE",3,"张三所属单位甲公司","anchor",.95);
        return new EvidenceGraph.Relation(id,revision,from,predicate,to,"occurrence:"+suffix,cn.bidcheck.compat.Java8Api.listOf(source),scope,"","",status,"SOURCE_BOUND",cn.bidcheck.compat.Java8Api.mapOf());
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Fixture {
        private final Store store;
        private final EvidenceGraphServiceTest.MemoryRepository repository;
        private final EvidenceGraphReadService reads;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Fixture(@com.fasterxml.jackson.annotation.JsonProperty("store") Store store, @com.fasterxml.jackson.annotation.JsonProperty("repository") EvidenceGraphServiceTest.MemoryRepository repository, @com.fasterxml.jackson.annotation.JsonProperty("reads") EvidenceGraphReadService reads) { this.store = store; this.repository = repository; this.reads = reads; }
        public Store store() { return store; }
        public EvidenceGraphServiceTest.MemoryRepository repository() { return repository; }
        public EvidenceGraphReadService reads() { return reads; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fixture that = (Fixture) other; return java.util.Objects.equals(this.store, that.store) && java.util.Objects.equals(this.repository, that.repository) && java.util.Objects.equals(this.reads, that.reads); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.store); hash = 31 * hash + java.util.Objects.hashCode(this.repository); hash = 31 * hash + java.util.Objects.hashCode(this.reads); return hash; }
        @Override public String toString() { return "Fixture[" + "store=" + store + ", repository=" + repository + ", reads=" + reads + "]"; }
}
}
