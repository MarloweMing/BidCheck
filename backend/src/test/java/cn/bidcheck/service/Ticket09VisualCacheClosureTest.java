package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Finding;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.fields;
import static org.junit.jupiter.api.Assertions.*;

class Ticket09VisualCacheClosureTest {
    Finding pending(int page) {
        Map<String,Object> request = fields("obligationId", "form-" + page,
            "documentId", "synthetic", "locationType", "PAGE", "location", page,
            "task", "SIGNATURE_VISIBILITY", "fields", Collections.singletonList("李明"),
            "sourceIds", Collections.singletonList("source-" + page));
        return new Finding("finding", "run", null,
            new Evaluation("UNDETERMINED", "尚有未完成条件", "", Collections.emptyList(), 0, "TEST"),
            fields("atomicCheck", fields("visualRequests", Collections.singletonList(request),
                "gaps", Collections.singletonList(fields("code", "VISUAL_OBSERVATION_REQUIRED")))));
    }

    Map<String,Object> observation(int page, String value) {
        return fields("obligationId", "form-" + page, "type", "SIGNATURE", "value", value,
            "source", fields("documentId", "synthetic", "documentHash", "hash",
                "documentVersion", 1, "page", page));
    }

    Finding close(Finding initial, Map<String,Object> context,
            Function<Finding,Map<String,Object>> refresh,
            Function<Map<String,Object>,Finding> evaluate) throws Exception {
        Method method = BidService.class.getDeclaredMethod("closeFirstRoundVisualCache",
            Finding.class, Map.class, Function.class, Function.class);
        method.setAccessible(true);
        return (Finding) method.invoke(null, initial, context, refresh, evaluate);
    }

    @Test void newlyExpandedRequestsConsumeTheirExistingCacheBeforePublication() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        List<Map<String,Object>> observations = new ArrayList<>();
        Finding finalFinding = pending(3);
        Finding result = close(pending(1), Collections.emptyMap(), current -> {
            int page = loads.incrementAndGet();
            if (page == 3) return Collections.emptyMap();
            observations.add(observation(page, page == 1 ? "NOT_OBSERVED" : "UNREADABLE"));
            return fields("visualObservations", new ArrayList<>(observations));
        }, context -> {
            assertEquals(false, context.get("recoveryModelAllowed"));
            int page = evaluations.incrementAndGet();
            assertEquals(Collections.singletonList("form-" + page), context.get("recoveryTargetObligationIds"));
            return page == 1 ? pending(2) : finalFinding;
        });
        assertEquals(3, loads.get());
        assertEquals(2, evaluations.get());
        assertEquals(finalFinding.evaluation(), result.evaluation());
        assertEquals(CheckResolution.atomic(finalFinding).get("visualRequests"),
            CheckResolution.atomic(result).get("visualRequests"));
    }

    @Test void unchangedObservationsAndCacheMissDoNotRecomputeOrCloseTheGap() throws Exception {
        Finding initial = pending(1);
        Map<String,Object> context = fields("visualObservations", Collections.singletonList(observation(1, "UNREADABLE")));
        for (Map<String,Object> refreshed : Arrays.asList(context, Collections.<String,Object>emptyMap())) {
            AtomicInteger loads = new AtomicInteger();
            assertSame(initial, close(initial, context, value -> {
                loads.incrementAndGet();
                return refreshed;
            }, value -> { throw new AssertionError("No new observation"); }));
            assertEquals(1, loads.get());
        }
    }

    @Test void repeatedRequestSetStopsEvenIfReceiptIdsOrTimestampsChange() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Finding result = close(pending(1), Collections.emptyMap(), current -> {
            int call = loads.incrementAndGet();
            Map<String,Object> observation = observation(1, "UNREADABLE");
            observation.put("id", "receipt-" + call);
            return fields("visualObservations", Collections.singletonList(observation));
        }, context -> { evaluations.incrementAndGet(); return pending(1); });
        assertEquals(1, loads.get());
        assertEquals(1, evaluations.get());
        assertEquals("UNDETERMINED", result.evaluation().status());
    }

    @Test void multipleLocalRoundsPreserveTheOriginalInvestigationReceipt() throws Exception {
        Finding initial = withInvestigation(pending(1), "original-agent");
        AtomicInteger rounds = new AtomicInteger();
        List<Map<String,Object>> observations = new ArrayList<>();
        Finding result = close(initial, Collections.emptyMap(), current -> {
            int page = rounds.incrementAndGet();
            if (page == 3) return Collections.emptyMap();
            observations.add(observation(page, "UNREADABLE"));
            return fields("visualObservations", new ArrayList<>(observations));
        }, context -> withInvestigation(pending(rounds.get() + 1), "local-" + rounds.get()));
        assertEquals("original-agent", CheckResolution.map(
            CheckResolution.atomic(result).get("firstRoundInvestigation")).get("requestId"));
    }

    Finding withInvestigation(Finding finding, String requestId) {
        Map<String,Object> atomic = new LinkedHashMap<>(CheckResolution.atomic(finding));
        atomic.put("agentInvestigation", fields("requestId", requestId));
        return new Finding(finding.id(), finding.runId(), finding.checkpoint(), finding.evaluation(),
            fields("atomicCheck", atomic));
    }
}
