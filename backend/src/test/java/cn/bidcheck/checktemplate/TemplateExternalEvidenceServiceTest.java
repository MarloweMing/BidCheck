package cn.bidcheck.checktemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.persistence.RecordMapper;
import cn.bidcheck.persistence.EvidenceOperationLeaseRow;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import cn.bidcheck.verification.external.ExternalVerificationAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class TemplateExternalEvidenceServiceTest {
    @Test void configuredAdapterQueriesTheResolvedBidderAndPublishesTrustedFacts() {
        RecordMapper mapper = leaseMapper();
        Store store = new Store(mapper, new ObjectMapper());
        ExternalVerificationAdapter adapter = mock(ExternalVerificationAdapter.class);
        when(adapter.identity()).thenReturn("connector-identity");
        when(adapter.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));
        when(adapter.enabledSourceTypes()).thenReturn(Map.of("信用中国", "CREDIT_CHINA"));
        when(adapter.query(anyMap())).thenReturn(Map.of(
                "status", "COMPLETED",
                "record", Map.of(
                        "type", "CREDIT_CHINA", "subject", "海南测试有限公司",
                        "source", "信用中国", "effectiveAt", "2026-10-19",
                        "evaluationStage", "BID_OPENING", "coverageComplete", true,
                        "rawSnapshotHash", "snapshot-sha", "provenanceStatus", "TRUSTED_CONNECTOR",
                        "result", Map.of("status", "VERIFIED", "facts", List.of(Map.of(
                                "field", "dishonestJudgmentDebtor", "value", false,
                                "rawValue", "false", "sourceQuote", "dishonestJudgmentDebtor=false"))))));
        TemplateExternalEvidenceService service = new TemplateExternalEvidenceService(store, adapter);
        Fact bidder = new Fact("COMPANY", "BIDDER", "companyName", "海南测试有限公司", "海南测试有限公司",
                new Evidence("bid", "投标文件", 1, "PAGE", "投标人名称：海南测试有限公司", "p1", 1));

        List<Fact> facts = service.resolve("project", "run", externalSpec(true), List.of(bidder));

        assertEquals(1, facts.size());
        assertEquals("TRUSTED_CONNECTOR", facts.get(0).entityType());
        assertEquals("投标人", facts.get(0).entityKey());
        assertEquals("CLEAR", facts.get(0).normalizedValue());
        assertTrue(facts.get(0).evidence().anchor().contains("rawSnapshotHash:snapshot-sha"));
        verify(adapter).query(Map.of("type", "CREDIT_CHINA", "subject", "海南测试有限公司",
                "evaluationStage", "BID_OPENING", "referenceDate", "2026-10-19"));
    }

    @Test void missingExplicitAdapterNeverStartsAnExternalRequest() {
        Store store = new Store(leaseMapper(), new ObjectMapper());
        ExternalVerificationAdapter adapter = mock(ExternalVerificationAdapter.class);
        TemplateExternalEvidenceService service = new TemplateExternalEvidenceService(store, adapter);

        assertTrue(service.resolve("project", "run", externalSpec(false), List.of()).isEmpty());
        verify(adapter, never()).query(anyMap());
    }

    @Test void transientAdapterFailureDoesNotPoisonTheRunScopedRetry() {
        Store store = new Store(leaseMapper(), new ObjectMapper());
        ExternalVerificationAdapter adapter = mock(ExternalVerificationAdapter.class);
        when(adapter.identity()).thenReturn("connector-identity");
        when(adapter.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));
        when(adapter.enabledSourceTypes()).thenReturn(Map.of("信用中国", "CREDIT_CHINA"));
        when(adapter.query(anyMap())).thenReturn(Map.of("status", "ERROR", "errorCode", "EXTERNAL_TIMEOUT"));
        TemplateExternalEvidenceService service = new TemplateExternalEvidenceService(store, adapter);
        Fact bidder = new Fact("COMPANY", "BIDDER", "companyName", "海南测试有限公司", "海南测试有限公司",
                new Evidence("bid", "投标文件", 1, "PAGE", "投标人名称：海南测试有限公司", "p1", 1));

        assertTrue(service.resolve("project", "run", externalSpec(true), List.of(bidder)).isEmpty());
        assertTrue(service.resolve("project", "run", externalSpec(true), List.of(bidder)).isEmpty());

        verify(adapter, times(2)).query(anyMap());
    }

    @Test void sameQueryIsSingleFlightAndDurablyReusedAcrossRunIds() throws Exception {
        Map<String, Stored> rows = new ConcurrentHashMap<>();
        RecordMapper mapper = leaseMapper();
        when(mapper.get(anyString())).thenAnswer(call -> rows.get(call.getArgument(0, String.class)));
        when(mapper.insertRecord(any(Stored.class))).thenAnswer(call -> {
            Stored row = call.getArgument(0, Stored.class);
            if (rows.putIfAbsent(row.id, row) != null) throw new DuplicateKeyException("duplicate");
            return 1;
        });
        Store store = new Store(mapper, new ObjectMapper());
        ExternalVerificationAdapter adapter = mock(ExternalVerificationAdapter.class);
        when(adapter.identity()).thenReturn("connector-identity");
        when(adapter.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));
        when(adapter.enabledSourceTypes()).thenReturn(Map.of("信用中国", "CREDIT_CHINA"));
        CountDownLatch queryEntered = new CountDownLatch(1), releaseQuery = new CountDownLatch(1);
        when(adapter.query(anyMap())).thenAnswer(call -> {
            queryEntered.countDown();
            assertTrue(releaseQuery.await(2, TimeUnit.SECONDS));
            return completedResponse();
        });
        Fact bidder = new Fact("COMPANY", "BIDDER", "companyName", "海南测试有限公司", "海南测试有限公司",
                new Evidence("bid", "投标文件", 1, "PAGE", "投标人名称：海南测试有限公司", "p1", 1));
        TemplateExternalEvidenceService first = new TemplateExternalEvidenceService(store, adapter);
        TemplateExternalEvidenceService second = new TemplateExternalEvidenceService(store, adapter);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<Fact>> one = workers.submit(() -> first.resolve("project", "run-a", externalSpec(true), List.of(bidder)));
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS));
            Future<List<Fact>> two = workers.submit(() -> second.resolve("project", "run-b", externalSpec(true), List.of(bidder)));
            releaseQuery.countDown();
            assertEquals(1, one.get(3, TimeUnit.SECONDS).size());
            assertEquals(1, two.get(3, TimeUnit.SECONDS).size());
        } finally {
            releaseQuery.countDown();
            workers.shutdownNow();
        }
        assertEquals(1, new TemplateExternalEvidenceService(store, adapter)
                .resolve("project", "run-c", externalSpec(true), List.of(bidder)).size());
        verify(adapter, times(1)).query(anyMap());
    }

    @Test void similarlyNamedSourceAndUnknownFieldMeaningCannotCreateTrustedFacts() {
        Store store = new Store(leaseMapper(), new ObjectMapper());
        ExternalVerificationAdapter adapter = mock(ExternalVerificationAdapter.class);
        when(adapter.identity()).thenReturn("connector-identity");
        when(adapter.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));
        when(adapter.enabledSourceTypes()).thenReturn(Map.of("信用中国", "CREDIT_CHINA"));
        when(adapter.query(anyMap())).thenReturn(Map.of(
                "status", "COMPLETED",
                "record", Map.of(
                        "type", "CREDIT_CHINA", "subject", "海南测试有限公司",
                        "source", "某信用中国镜像", "effectiveAt", "2026-10-19",
                        "evaluationStage", "BID_OPENING", "coverageComplete", true,
                        "rawSnapshotHash", "snapshot-sha", "provenanceStatus", "TRUSTED_CONNECTOR",
                        "result", Map.of("status", "VERIFIED", "facts", List.of(Map.of(
                                "field", "dishonestJudgmentDebtor", "value", false,
                                "rawValue", "false", "sourceQuote", "dishonestJudgmentDebtor=false"))))));
        TemplateExternalEvidenceService service = new TemplateExternalEvidenceService(store, adapter);
        Fact bidder = new Fact("COMPANY", "BIDDER", "companyName", "海南测试有限公司", "海南测试有限公司",
                new Evidence("bid", "投标文件", 1, "PAGE", "投标人名称：海南测试有限公司", "p1", 1));

        assertTrue(service.resolve("project", "run", externalSpec(true), List.of(bidder)).isEmpty());
    }

    @Test void mismatchedStageOrIncompleteCoverageCannotCreateTrustedFacts() {
        Store store = new Store(leaseMapper(), new ObjectMapper());
        ExternalVerificationAdapter adapter = mock(ExternalVerificationAdapter.class);
        when(adapter.identity()).thenReturn("connector-identity");
        when(adapter.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));
        when(adapter.enabledSourceTypes()).thenReturn(Map.of("信用中国", "CREDIT_CHINA"));
        Fact bidder = new Fact("COMPANY", "BIDDER", "companyName", "海南测试有限公司", "海南测试有限公司",
                new Evidence("bid", "投标文件", 1, "PAGE", "投标人名称：海南测试有限公司", "p1", 1));
        for (Map<String, Object> overrides : List.<Map<String, Object>>of(
                Map.<String, Object>of("evaluationStage", "EVALUATION", "coverageComplete", true),
                Map.<String, Object>of("evaluationStage", "BID_OPENING", "coverageComplete", false))) {
            when(adapter.query(anyMap())).thenReturn(Map.of("status", "COMPLETED", "record", Map.of(
                    "type", "CREDIT_CHINA", "subject", "海南测试有限公司", "source", "信用中国",
                    "effectiveAt", "2026-10-19", "evaluationStage", overrides.get("evaluationStage"),
                    "coverageComplete", overrides.get("coverageComplete"), "rawSnapshotHash", "snapshot-sha",
                    "provenanceStatus", "TRUSTED_CONNECTOR", "result", Map.of("status", "VERIFIED",
                            "facts", List.of(Map.of("field", "dishonestJudgmentDebtor", "value", false,
                                    "rawValue", "false", "sourceQuote", "dishonestJudgmentDebtor=false"))))));
            assertTrue(new TemplateExternalEvidenceService(store, adapter)
                    .resolve("project", "run-" + overrides.hashCode(), externalSpec(true), List.of(bidder)).isEmpty());
        }
    }

    private static CheckSpec externalSpec(boolean configured) {
        Map<String, Object> parameters = configured
                ? Map.of("source", "信用中国", "queryObject", "投标人",
                "requiredFields", List.of("dishonestJudgmentDebtor"), "asOfDate", "2026-10-19",
                "adapterId", "CREDIT_CHINA", "evaluationStage", "BID_OPENING")
                : Map.of("source", "信用中国", "queryObject", "投标人",
                "requiredFields", List.of("dishonestJudgmentDebtor"), "asOfDate", "2026-10-19",
                "evaluationStage", "BID_OPENING");
        return new CheckSpec("external-spec", 1, "item", "checkpoint",
                TemplateTypes.Category.QUALIFICATION, TemplateTypes.Purpose.MANDATORY,
                TemplateTypes.VerificationMode.EXTERNAL, "EXTERNAL_CHECK", "1.0",
                SubjectSelector.bidder(), parameters, Applicability.applies(),
                TemplateTypes.ProofStandard.FACTUAL_CONDITION, List.of(), List.of(), Map.of(), Map.of(),
                Map.of("state", "FULL", "complete", true), true,
                Confirmation.legacyConfirmed(), "external-semantic");
    }

    /** These unit tests keep record bodies in Mockito; durable lease CAS itself is covered against H2. */
    private static RecordMapper leaseMapper() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.tryInsertEvidenceOperationLease(any(EvidenceOperationLeaseRow.class))).thenReturn(1);
        when(mapper.renewEvidenceOperationLease(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.releaseEvidenceOperationLease(anyString(), anyString(), anyString())).thenReturn(1);
        return mapper;
    }

    private static Map<String, Object> completedResponse() {
        return Map.of("status", "COMPLETED", "record", Map.of(
                "type", "CREDIT_CHINA", "subject", "海南测试有限公司", "source", "信用中国",
                "effectiveAt", "2026-10-19", "evaluationStage", "BID_OPENING",
                "coverageComplete", true, "rawSnapshotHash", "snapshot-sha",
                "provenanceStatus", "TRUSTED_CONNECTOR", "result", Map.of("status", "VERIFIED",
                        "facts", List.of(Map.of("field", "dishonestJudgmentDebtor", "value", false,
                                "rawValue", "false", "sourceQuote", "dishonestJudgmentDebtor=false")))));
    }
}
