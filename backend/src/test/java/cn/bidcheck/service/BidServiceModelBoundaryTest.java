package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.OcrClient;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.semantic.SemanticAssessment;
import cn.bidcheck.semantic.SemanticChecker;
import cn.bidcheck.storage.ArtifactStorage;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BidServiceModelBoundaryTest {
    @Test void missingAtomicContextIsAnExplicitSystemGapAndDoesNotCreateAModelCandidate(){
        Checkpoint checkpoint=new Checkpoint("c","复杂条款","资格","HIGH","SEMANTIC_RESPONSE","","","提交相关材料","",true,true,true,null);
        SemanticAssessment pending=BidService.atomicContextUnavailable(checkpoint);
        assertEquals("UNDETERMINED",pending.evaluation().status());
        assertEquals("ATOMIC_CONTEXT_UNAVAILABLE",pending.evaluation().method());
        assertSame(pending.evaluation(),pending.rawEvaluation());
        assertEquals("NOT_REQUESTED",pending.audit().get("modelOutputRole"));
        assertEquals(false,pending.audit().get("legacySemanticFallback"));
        assertEquals("ATOMIC_CONTEXT_REQUIRED",((Map<?,?>)pending.audit().get("publicationGuard")).get("code"));
        assertFalse(pending.audit().containsKey("candidateStatus"));
    }

    @Test void productionLegacySemanticBranchDoesNotCallSemanticChecker(){
        Store store=mock(Store.class);when(store.getMap("run","run")).thenReturn(Map.of("templateRouteEnabled",false));
        SemanticChecker semantic=mock(SemanticChecker.class);
        BidService service=new BidService(store,mock(PlatformTransactionManager.class),semantic,mock(ArtifactStorage.class),mock(OcrClient.class));
        Checkpoint checkpoint=new Checkpoint("c","复杂条款","资格","HIGH","SEMANTIC_RESPONSE","","","提交相关材料","",true,true,true,null);
        Document bid=new Document("bid","project","BID","投标文件",1,"sha","",List.of(new Page(1,"投标材料","PARAGRAPH","TEXT","")),List.of());
        BooleanSupplier paidCall=()->{fail("legacy semantic path must not reserve a model call");return false;};
        Finding finding=ReflectionTestUtils.invokeMethod(service,"evaluateFinding","run",checkpoint,bid,null,List.of(),Map.of(),paidCall,Map.of());
        assertNotNull(finding);assertEquals("UNDETERMINED",finding.evaluation().status());
        assertTrue(finding.evaluation().method().startsWith("ATOMIC_CONTEXT_UNAVAILABLE"));
        assertEquals("NOT_REQUESTED",finding.assessment().get("modelOutputRole"));
        verifyNoInteractions(semantic);
    }
}
