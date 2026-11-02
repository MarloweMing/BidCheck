package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class VerificationPendingFailureGuardTest {
    Decision pending(){return new Decision("r","人员确认","NEEDS_CONFIRMATION","待确认",List.of(),List.of(new Gap("INFERENCE_CONFIRMATION_REQUIRED","待确认","确认原文","USER_CONFIRMATION")),Map.of("inferredFacts",List.of(Map.of("id","claim"))));}
    @Test void terminalRequestFailureCannotHideBehindPendingConfirmation(){Decision before=pending();Decision after=VerificationEngine.outstandingRequestIssues(before,List.of("FACT_EXTRACTION_UNAVAILABLE"),List.of(new Gap("HTTP_503","请求失败","重试","RETRY_FAILED_STEP")));
        assertEquals("UNDETERMINED",after.status());assertEquals(before.proof(),after.proof());assertEquals(Set.of("INFERENCE_CONFIRMATION_REQUIRED","FACT_EXTRACTION_UNAVAILABLE","HTTP_503"),after.gaps().stream().map(Gap::code).collect(java.util.stream.Collectors.toSet()));}
    @Test void outstandingGapWithoutSummaryCodeStillPreventsConfirmationOnlyState(){assertEquals("UNDETERMINED",VerificationEngine.outstandingRequestIssues(pending(),List.of(),List.of(new Gap("REQUEST_TIMEOUT","超时","重试","RETRY_FAILED_STEP"))).status());}
    @Test void recoveredAttemptHistoryDoesNotCreateANewTerminalFailure(){RunSession session=new RunSession(ModelGateway.disabled(),Map.of(),"scope");
        session.save("EXTRACT","same-input","FAILED",Map.of("errorCode","HTTP_503","retryable",true));session.save("EXTRACT","same-input","COMPLETED",Map.of("facts",List.of()));
        assertEquals(0L,session.diagnostics().get("unresolvedFailures"));Decision pending=pending();assertSame(pending,VerificationEngine.outstandingRequestIssues(pending,List.of(),List.of()),"A successful retry never enters the terminal handler lists");}
}
