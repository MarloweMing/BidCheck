package cn.bidcheck.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PreparedRecordTest {
    @Test void encodedPayloadIsImmutableAndFinalWriteDoesNotEncodeAgain() {
        RecordMapper mapper=mock(RecordMapper.class);Store store=spy(new Store(mapper,new ObjectMapper()));
        Map<String,Object> input=new LinkedHashMap<>(Map.of("text","original","nested",new ArrayList<>(List.of("source"))));
        var prepared=store.prepareRecord("finding","id","project","run",input);
        input.put("text","changed");((List<String>)input.get("nested")).add("changed");
        doThrow(new AssertionError("Encoding during final persistence")).when(store).encode(any());
        store.savePrepared(prepared);ArgumentCaptor<Stored> saved=ArgumentCaptor.forClass(Stored.class);verify(mapper).save(saved.capture());
        assertEquals(Map.of("text","original","nested",List.of("source")),store.map(saved.getValue().body));
    }
    @Test void actualCglibProxyCanPrepareAndPersistItsOwnPayload() {
        RecordMapper mapper=mock(RecordMapper.class);ProxyFactory factory=new ProxyFactory(new Store(mapper,new ObjectMapper()));factory.setProxyTargetClass(true);
        Store proxy=(Store)factory.getProxy();var prepared=proxy.prepareRecord("finding","id","project","run",Map.of("value",1));
        assertDoesNotThrow(()->proxy.savePrepared(prepared));verify(mapper).save(any());
        Store other=new Store(mapper,new ObjectMapper());assertEquals("RECORD_PREPARATION_OWNER_INVALID",assertThrows(IllegalArgumentException.class,()->other.savePrepared(prepared)).getMessage());
    }
    @Test void preparationInsideTransactionAndDocumentSummaryBypassAreRejected() {
        Store store=new Store(mock(RecordMapper.class),new ObjectMapper());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertEquals("RECORD_PREPARATION_REQUIRES_NO_TRANSACTION",assertThrows(IllegalStateException.class,()->store.prepareRecord("finding","id","project","run",Map.of())).getMessage());}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals("DOCUMENT_REQUIRES_SUMMARY_SAVE",assertThrows(IllegalArgumentException.class,()->store.prepareRecord("document","id","project","run",Map.of())).getMessage());
    }
    @Test void preparedDestinationCannotOverwriteAnotherScope() {
        RecordMapper mapper=mock(RecordMapper.class);Store store=new Store(mapper,new ObjectMapper());
        var prepared=store.prepareRecord("finding","id","project","run",Map.of());
        Stored existing=new Stored();existing.id="id";existing.kind="finding";existing.projectId="other-project";existing.parentId="run";when(mapper.get("id")).thenReturn(existing);
        assertEquals("PREPARED_RECORD_SCOPE_MISMATCH",assertThrows(IllegalStateException.class,()->store.savePrepared(prepared)).getMessage());verify(mapper,never()).save(any());
    }
}
