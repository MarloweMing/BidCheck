package cn.bidcheck.web;

import cn.bidcheck.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TargetedRunControllerTest {
    @Test void explicitCheckpointArrayIsPassedToScopedServiceWhileDefaultRequestIsUnchanged(){
        BidService service=mock(BidService.class);ApiController api=new ApiController(service,null,null,null);
        api.run("project",cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "reviewMode", "FIXED_WORKFLOW", "checkpointIds", cn.bidcheck.compat.Java8Api.listOf("a", "b")));
        verify(service).startRun("project","bid",false,"",null,"","FIXED_WORKFLOW",cn.bidcheck.compat.Java8Api.listOf("a", "b"));
        api.run("project",cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid"));verify(service).startRun("project","bid",false,"",null,"","");
    }
    @Test void malformedTargetRequestCannotSilentlyExpandToAllChecks(){
        BidService service=mock(BidService.class);ApiController api=new ApiController(service,null,null,null);
        assertThrows(RuntimeException.class,()->api.run("p",cn.bidcheck.compat.Java8Api.mapOf("checkpointIds", cn.bidcheck.compat.Java8Api.listOf())));
        assertThrows(RuntimeException.class,()->api.run("p",cn.bidcheck.compat.Java8Api.mapOf("checkpointIds", "a,b")));
        assertThrows(RuntimeException.class,()->api.run("p",cn.bidcheck.compat.Java8Api.mapOf("checkpointIds", cn.bidcheck.compat.Java8Api.listOf("a", 1))));verifyNoInteractions(service);
    }
}
