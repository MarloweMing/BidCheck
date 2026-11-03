package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class VerificationPendingFailureGuardTest {
    Decision pending(){return new Decision("r","人员确认","NEEDS_CONFIRMATION","待确认",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(new Gap("INFERENCE_CONFIRMATION_REQUIRED","待确认","确认原文","USER_CONFIRMATION")),cn.bidcheck.compat.Java8Api.mapOf("inferredFacts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "claim"))));}
    @Test void terminalRequestFailureCannotHideBehindPendingConfirmation(){Decision before=pending();Decision after=VerificationEngine.outstandingRequestIssues(before,cn.bidcheck.compat.Java8Api.listOf("FACT_EXTRACTION_UNAVAILABLE"),cn.bidcheck.compat.Java8Api.listOf(new Gap("HTTP_503","请求失败","重试","RETRY_FAILED_STEP")));
        assertEquals("UNDETERMINED",after.status());assertEquals(before.proof(),after.proof());assertEquals(cn.bidcheck.compat.Java8Api.setOf("INFERENCE_CONFIRMATION_REQUIRED", "FACT_EXTRACTION_UNAVAILABLE", "HTTP_503"),after.gaps().stream().map(Gap::code).collect(java.util.stream.Collectors.toSet()));}
    @Test void outstandingGapWithoutSummaryCodeStillPreventsConfirmationOnlyState(){assertEquals("UNDETERMINED",VerificationEngine.outstandingRequestIssues(pending(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(new Gap("REQUEST_TIMEOUT","超时","重试","RETRY_FAILED_STEP"))).status());}
    @Test void recoveredAttemptHistoryDoesNotCreateANewTerminalFailure(){RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"scope");
        session.save("EXTRACT","same-input","FAILED",cn.bidcheck.compat.Java8Api.mapOf("errorCode", "HTTP_503", "retryable", true));session.save("EXTRACT","same-input","COMPLETED",cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf()));
        assertEquals(0L,session.diagnostics().get("unresolvedFailures"));Decision pending=pending();assertSame(pending,VerificationEngine.outstandingRequestIssues(pending,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf()),"A successful retry never enters the terminal handler lists");}
}
