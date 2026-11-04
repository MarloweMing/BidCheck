package cn.bidcheck.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;
class Ticket09RecoveryBudgetTest {
    String reason(long now,long deadline,int consecutive,int max)throws Exception{
        java.lang.reflect.Method m=BidService.class.getDeclaredMethod("recoveryLimitReason",long.class,long.class,int.class,int.class);m.setAccessible(true);return (String)m.invoke(null,now,deadline,consecutive,max);
    }
    @Test void timeBoundaryAndConsecutiveLimitStopWithoutDiscardingOutstandingGaps()throws Exception{
        assertEquals("",reason(899,900,19,20));assertEquals("RECOVERY_TIME_LIMIT",reason(900,900,0,20));
        assertEquals("RECOVERY_NO_PROGRESS_LIMIT",reason(1,900,20,20));assertEquals("",reason(1,900,0,20));
        Map<String,Object> pending=fields("status","QUEUED","gaps",Collections.singletonList("MISSING_PROOF"),"dependencyTargetObligationIds",Collections.singletonList("still-unread"));
        java.lang.reflect.Method m=BidService.class.getDeclaredMethod("stoppedRecoveryJob",Map.class,String.class);m.setAccessible(true);
        Map<?,?> stopped=(Map<?,?>)m.invoke(null,pending,"RECOVERY_TIME_LIMIT");
        assertEquals("BLOCKED_NO_PROGRESS",stopped.get("status"));assertEquals("RECOVERY_TIME_LIMIT",stopped.get("stopReason"));
        assertEquals(pending.get("gaps"),stopped.get("gaps"));assertEquals(pending.get("dependencyTargetObligationIds"),stopped.get("dependencyTargetObligationIds"));
        assertEquals("QUEUED",pending.get("status"));
    }
}
