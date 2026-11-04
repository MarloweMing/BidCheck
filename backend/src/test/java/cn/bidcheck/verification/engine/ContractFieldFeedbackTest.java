package cn.bidcheck.verification.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** All pages and parties are invented. Exercises the actual Host boundary. */
class ContractFieldFeedbackTest {
    private static final String BODY = "合同金额（含税）：200万元\n合同签订日期：2023年1月1日\n甲方：合成业主有限公司";

    private Map<String,Object> candidate(Fixture fixture, Object... fields) {
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        Map<String,Object> attributes = linked("contractName", NAME, "contractNumber", NUMBER);
        attributes.putAll(linked(fields));
        candidate.put("attributes", attributes);
        return candidate;
    }

    private Map<String,Object> firstIssue(Map<String,Object> receipt) {
        return RuleEvaluator.maps(receipt.get("rejections")).get(0);
    }

    @Test void amountRejectionNamesTheFailedFieldAndItsActuallyReadSource() throws Exception {
        Fixture fixture = new Fixture(identityBody(), BODY);
        Map<String,Object> receipt = fixture.submit(candidate(fixture, "amount", "200",
            "amountBasis", "合同金额（含税）", "unit", "万元", "taxBasis", "含税"));
        assertEquals("CONTRACT_AMOUNT_BASIS_UNPROVEN", firstIssue(receipt).get("code"));
        assertEquals("/fact/attributes/amount", firstIssue(receipt).get("fieldPath"));
        assertEquals("/fact/attributes/amount", RuleEvaluator.maps(receipt.get("correctionHints")).get(0).get("fieldPath"));
        assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty());
        List<Map<String,Object>> candidates = RuleEvaluator.maps(receipt.get("readSourceCandidates"));
        assertEquals(1, candidates.size(), receipt.toString());
        assertEquals(source(fixture.corpus, 5).id(), candidates.get(0).get("sourceId"));
        assertEquals(false, candidates.get(0).get("businessProof"));
        assertEquals(true, candidates.get(0).get("requiresExplicitSelection"));
        assertTrue(receipt.get("submittedSourceChecks").toString().contains("UNSUPPORTED"));
        assertTrue(receipt.get("submissionExample").toString().contains("CONTRACT_FIELDS"));
        assertTrue(receipt.get("submissionExample").toString().contains("contractFields"));
        Map<String,Object> example = (Map<String,Object>) receipt.get("submissionExample");
        assertEquals(true, example.get("templateOnly"));
        assertEquals(false, example.get("readyToSubmit"));
        Map<String,Object> arguments = (Map<String,Object>) example.get("toolArguments");
        Map<String,Object> identity = (Map<String,Object>) arguments.get("fact");
        Map<String,Object> identityReceipt = fixture.submit(identity);
        assertEquals(1, ProofVerifier.strings(identityReceipt.get("acceptedFactIds")).size());
    }

    @Test void dateAndPartyFailuresDoNotHideBehindTheRecordFieldName() throws Exception {
        Fixture fixture = new Fixture(identityBody(), BODY);
        Map<String,Object> date = fixture.submit(candidate(fixture, "signedDate", "2023-01-01"));
        assertEquals("CONTRACT_DATE_UNGROUNDED", firstIssue(date).get("code"));
        assertEquals("/fact/attributes/signedDate", firstIssue(date).get("fieldPath"));
        assertEquals(1, RuleEvaluator.maps(date.get("readSourceCandidates")).size());
        Map<String,Object> party = fixture.submit(candidate(fixture, "party", "合成业主有限公司",
            "signedDate", "2023-01-01"));
        assertEquals("CONTRACT_ATTRIBUTE_UNGROUNDED", firstIssue(party).get("code"));
        assertEquals("/fact/attributes/party", firstIssue(party).get("fieldPath"));
        assertTrue(party.get("submittedSourceChecks").toString().contains("NOT_CHECKED"));
    }

    @Test void unreadNavigationAndDifferentContractCannotBecomeSuggestedProof() throws Exception {
        Fixture unread = new Fixture(identityBody(), BODY, false);
        unread.read(4);
        Map<String,Object> receipt = unread.submit(candidate(unread, "signedDate", "2023-01-01"));
        assertTrue(RuleEvaluator.maps(receipt.get("readSourceCandidates")).isEmpty());
        Fixture other = new Fixture(identityBody(), "合同编号：OTHER-SYN-009\n" + BODY);
        receipt = other.submit(candidate(other, "signedDate", "2023-01-01"));
        assertTrue(RuleEvaluator.maps(receipt.get("readSourceCandidates")).isEmpty());
    }

    @Test void sameNumberWithWrongTaxOrPriceBasisIsNotSuggested() throws Exception {
        Fixture fixture = new Fixture(identityBody(), BODY);
        for (Map<String,Object> candidate : Arrays.asList(
                candidate(fixture, "amount", "200", "amountBasis", "合同金额（含税）", "unit", "万元", "taxBasis", "不含税"),
                candidate(fixture, "amount", "200", "amountBasis", "装修施工费", "unit", "万元", "taxBasis", "含税"))) {
            Map<String,Object> receipt = fixture.submit(candidate);
            assertTrue(RuleEvaluator.maps(receipt.get("readSourceCandidates")).isEmpty(), receipt.toString());
            assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty());
        }
    }

    @Test void constructionDateCannotBeSuggestedAsSigningDate() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "开工日期：2023年1月1日\n施工期限另行约定。");
        Map<String,Object> receipt = fixture.submit(candidate(fixture, "signedDate", "2023-01-01"));
        assertTrue(RuleEvaluator.maps(receipt.get("readSourceCandidates")).isEmpty());
    }

    @Test void twoSigningDatesAreNotResolvedByTheSubmittedChoice() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "甲方盖章\n时间：2023年1月1日\n乙方盖章\n时间：2023年1月2日");
        Map<String,Object> receipt = fixture.submit(candidate(fixture, "signedDate", "2023-01-01"));
        assertTrue(RuleEvaluator.maps(receipt.get("readSourceCandidates")).isEmpty(), receipt.toString());
    }

    @Test void staleReadAndWrongDocumentAreNeverSuggestedEvenWithMatchingText() throws Exception {
        for (String mismatch : Arrays.asList("version", "document")) {
            Fixture fixture = new Fixture(identityBody(), BODY);
            Source original = source(fixture.corpus, 5);
            cn.bidcheck.domain.Domain.Evidence evidence = original.evidence();
            if ("document".equals(mismatch)) evidence = new cn.bidcheck.domain.Domain.Evidence(
                "another-synthetic-document", evidence.documentName(), evidence.page(), evidence.locationType(),
                evidence.quote(), evidence.anchor(), evidence.confidence());
            fixture.host.disclosed.put(original.id(), new Source(original.id(), evidence, original.opening(),
                original.index(), original.historical(), original.documentHash(),
                "version".equals(mismatch) ? original.documentVersion() - 1 : original.documentVersion(), original.scopeContext()));
            Map<String,Object> receipt = fixture.submit(candidate(fixture, "signedDate", "2023-01-01"));
            assertTrue(RuleEvaluator.maps(receipt.get("readSourceCandidates")).isEmpty(), receipt.toString());
        }
    }

    @Test void knownIdentityIsExplicitAndCannotCloseTheRemainingBusinessFields() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> first = fixture.submit(candidate(fixture));
        List<String> accepted = ProofVerifier.strings(first.get("acceptedFactIds"));
        assertEquals(1, accepted.size());
        assertEquals(accepted, ProofVerifier.strings(first.get("newlyAddedFactIds")));
        Map<String,Object> repeated = fixture.submit(candidate(fixture));
        assertTrue(ProofVerifier.strings(repeated.get("acceptedFactIds")).isEmpty());
        assertTrue(ProofVerifier.strings(repeated.get("newlyAddedFactIds")).isEmpty());
        assertEquals(accepted, ProofVerifier.strings(repeated.get("alreadyKnownFactIds")));
        Map<String,Object> needs = (Map<String,Object>) repeated.get("remainingFieldNeeds");
        assertEquals(fixture.target, needs.get("targetObligationId"));
        assertEquals(((Map<?,?>) repeated.get("targetEvidenceRevisions")).get(fixture.target), needs.get("evidenceRevision"));
        assertFalse(RuleEvaluator.maps(needs.get("contractChecks")).stream().anyMatch(check -> Boolean.TRUE.equals(check.get("counted"))));
        assertEquals(1, RuleEvaluator.maps(needs.get("contractChecks")).size());
        assertTrue(needs.toString().contains("signedDate"), needs.toString());
    }

    @Test void atomicDuplicateAndNewCounterevidenceStayDistinct() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "合同金额（含税）：200万元\n合同金额（不含税）：200万元");
        Map<String,Object> identity = candidate(fixture);
        String known = ProofVerifier.strings(fixture.submit(identity).get("acceptedFactIds")).get(0);
        Map<String,Object> amount = linked("field", "amount", "rawValue", "200",
            "sourceIds", Arrays.asList(source(fixture.corpus, 4).id(), source(fixture.corpus, 5).id()),
            "attributes", linked("contractName", NAME, "contractNumber", NUMBER, "amount", "200",
                "amountBasis", "合同金额（含税）", "unit", "万元", "taxBasis", "含税"));
        Map<String,Object> first = fixture.host.call("submit_candidate_fact", linked("targetObligationId", fixture.target,
            "fact", identity, "submissionMode", "CONTRACT_FIELDS", "contractFields", Collections.singletonList(amount)));
        assertEquals(Collections.singletonList(known), ProofVerifier.strings(first.get("alreadyKnownFactIds")));
        assertEquals(1, ProofVerifier.strings(first.get("newlyAddedFactIds")).size(), first.toString());
        Map<String,Object> attributes = (Map<String,Object>) amount.get("attributes");
        attributes.put("amountBasis", "合同金额（不含税）");
        attributes.put("taxBasis", "不含税");
        Map<String,Object> second = fixture.host.call("submit_candidate_fact", linked("targetObligationId", fixture.target,
            "fact", identity, "submissionMode", "CONTRACT_FIELDS", "contractFields", Collections.singletonList(amount)));
        assertEquals(1, ProofVerifier.strings(second.get("newlyAddedFactIds")).size(), second.toString());
        assertNotEquals(first.get("newlyAddedFactIds"), second.get("newlyAddedFactIds"));
    }

    @Test void atomicFeedbackUsesTheRealArrayPathAndKeepsCurrentPartialReceipt() throws Exception {
        Fixture fixture = new Fixture(identityBody(), BODY);
        Map<String,Object> identity = candidate(fixture);
        Map<String,Object> amount = linked("field", "amount", "rawValue", "200",
            "sourceIds", Arrays.asList(source(fixture.corpus, 4).id(), source(fixture.corpus, 5).id()),
            "attributes", linked("contractName", NAME, "contractNumber", NUMBER, "amount", "200",
                "amountBasis", "施工费用", "unit", "万元"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked("targetObligationId", fixture.target,
            "fact", identity, "submissionMode", "CONTRACT_FIELDS", "contractFields", Collections.singletonList(amount)));
        assertEquals("CONTRACT_AMOUNT_BASIS_UNPROVEN", firstIssue(receipt).get("code"));
        assertEquals("/contractFields/0/attributes/amountBasis", firstIssue(receipt).get("fieldPath"));
        assertEquals(true, receipt.get("partialAdoption"));
        assertEquals(1, ProofVerifier.strings(receipt.get("newlyAddedFactIds")).size());
        String revision = String.valueOf(((Map<?,?>) receipt.get("targetEvidenceRevisions")).get(fixture.target));
        assertEquals(revision, ((Map<?,?>) receipt.get("remainingFieldNeeds")).get("evidenceRevision"));
        for (Map<String,Object> work : RuleEvaluator.maps(receipt.get("semanticWorkItems")))
            assertEquals(revision, work.get("evidenceRevision"));
    }

    @Test void identityAliasConflictPointsAtItsCanonicalAttribute() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> candidate = candidate(fixture);
        candidate.put("field", "合同名称");
        ((Map<String,Object>) candidate.get("attributes")).put("contractName", "乙中心装饰装修工程施工合同");
        Map<String,Object> receipt = fixture.submit(candidate);
        assertEquals("CONTRACT_IDENTITY_CONFLICT", firstIssue(receipt).get("code"));
        assertEquals("/fact/attributes/contractName", firstIssue(receipt).get("fieldPath"));
        assertTrue(receipt.get("submittedSourceChecks").toString().contains("UNSUPPORTED"));
    }

    @Test void normalizedDateWithoutExactOffsetNeverReturnsAnUnrelatedPagePrefix() throws Exception {
        StringBuilder longBody = new StringBuilder();
        while (longBody.length() < 1250) longBody.append("本页为同合同施工说明。\n");
        longBody.append("合同签订日期：2023年1月1日\n双方盖章。");
        Fixture fixture = new Fixture(identityBody(), longBody.toString());
        assertTrue(source(fixture.corpus, 5).evidence().quote().indexOf("2023年1月1日") > 1200);
        Map<String,Object> receipt = fixture.submit(candidate(fixture, "signedDate", "2023-01-01"));
        List<Map<String,Object>> candidates = RuleEvaluator.maps(receipt.get("readSourceCandidates"));
        assertFalse(candidates.isEmpty(), receipt.toString());
        for (Map<String,Object> item : candidates) {
            assertFalse(item.containsKey("quote"), item.toString());
            assertEquals("NORMALIZED_MATCH_HAS_NO_EXACT_ORIGINAL_OFFSET", item.get("quoteOmittedReason"));
            assertEquals("read_section", item.get("nextAction"));
        }
    }

    @Test void whitespaceNormalizedPartyDoesNotReturnAnUnrelatedPrefix() throws Exception {
        StringBuilder longBody = new StringBuilder();
        while (longBody.length() < 1250) longBody.append("本页为同合同施工说明。\n");
        longBody.append("甲方：合成 业主 有限公司\n双方盖章。");
        Fixture fixture = new Fixture(identityBody(), longBody.toString());
        Map<String,Object> receipt = fixture.submit(candidate(fixture, "party", "合成业主有限公司"));
        List<Map<String,Object>> candidates = RuleEvaluator.maps(receipt.get("readSourceCandidates"));
        assertFalse(candidates.isEmpty(), receipt.toString());
        assertFalse(candidates.get(0).containsKey("quote"));
        assertEquals("NORMALIZED_MATCH_HAS_NO_EXACT_ORIGINAL_OFFSET", candidates.get(0).get("quoteOmittedReason"));
    }

    @Test void anEarlierRepeatedAmountLabelCannotSupplyTheWrongExcerpt() throws Exception {
        StringBuilder body = new StringBuilder("合同金额（含税）：100万元\n");
        while (body.length() < 1250) body.append("本页为同合同施工说明。\n");
        body.append("合同金额（含税）：200万元\n");
        Fixture fixture = new Fixture(identityBody(), body.toString());
        Map<String,Object> receipt = fixture.submit(candidate(fixture, "amount", "200",
            "amountBasis", "合同金额（含税）", "unit", "万元", "taxBasis", "含税"));
        List<Map<String,Object>> candidates = RuleEvaluator.maps(receipt.get("readSourceCandidates"));
        assertEquals(1, candidates.size());
        assertFalse(candidates.get(0).containsKey("quote"));
        assertEquals("FIELD_MATCH_OUTSIDE_BOUNDED_EXCERPT", candidates.get(0).get("quoteOmittedReason"));
    }

    @Test void sameBatchInsertionAndDuplicateHaveDisjointTopLevelIds() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> identity = candidate(fixture);
        Map<String,Object> atom = linked("field", identity.get("field"), "rawValue", identity.get("rawValue"),
            "sourceIds", identity.get("sourceIds"), "attributes", identity.get("attributes"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked("targetObligationId", fixture.target,
            "fact", identity, "submissionMode", "CONTRACT_FIELDS", "contractFields", Collections.singletonList(atom)));
        assertEquals(1, ProofVerifier.strings(receipt.get("newlyAddedFactIds")).size());
        assertTrue(ProofVerifier.strings(receipt.get("alreadyKnownFactIds")).isEmpty());
        assertEquals(receipt.get("newlyAddedFactIds"), RuleEvaluator.maps(receipt.get("fieldResults")).get(0).get("alreadyKnownFactIds"));
    }
}
