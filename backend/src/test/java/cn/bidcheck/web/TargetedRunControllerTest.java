package cn.bidcheck.web;

import cn.bidcheck.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TargetedRunControllerTest {
    @Test void explicitCheckpointArrayIsPassedToScopedServiceWhileDefaultRequestIsUnchanged(){
        BidService service=mock(BidService.class);ApiController api=new ApiController(service,null,null,null);
        api.run("project",Map.of("documentId","bid","reviewMode","FIXED_WORKFLOW","checkpointIds",List.of("a","b")));
        verify(service).startRun("project","bid",false,"",null,"","FIXED_WORKFLOW",List.of("a","b"));
        api.run("project",Map.of("documentId","bid"));verify(service).startRun("project","bid",false,"",null,"","");
    }
    @Test void malformedTargetRequestCannotSilentlyExpandToAllChecks(){
        BidService service=mock(BidService.class);ApiController api=new ApiController(service,null,null,null);
        assertThrows(RuntimeException.class,()->api.run("p",Map.of("checkpointIds",List.of())));
        assertThrows(RuntimeException.class,()->api.run("p",Map.of("checkpointIds","a,b")));
        assertThrows(RuntimeException.class,()->api.run("p",Map.of("checkpointIds",List.of("a",1))));verifyNoInteractions(service);
    }
}
