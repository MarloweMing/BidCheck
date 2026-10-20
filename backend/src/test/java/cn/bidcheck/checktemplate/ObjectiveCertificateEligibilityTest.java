package cn.bidcheck.checktemplate;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectiveCertificateEligibilityTest {
    @Test void usesTheLabelledValidityRangeInsteadOfInitialCertificationDate() {
        String certificate = "证书编号：SYN-QMS-001 初次认证日期：2022年09月05日 "
                + "证书颁发日期：2025年09月04日 证书有效日期：2025年09月04日至2028年09月03日";

        ObjectiveCertificateEligibility.Assessment assessment =
                ObjectiveCertificateEligibility.assess(certificate, LocalDate.of(2025, 8, 5));

        assertEquals(ObjectiveCertificateEligibility.State.INELIGIBLE, assessment.state());
        assertEquals("CERTIFICATE_NOT_EFFECTIVE_AT_REFERENCE_DATE", assessment.reason());
        assertEquals(LocalDate.of(2025, 9, 4), assessment.validFrom());
    }

    @Test void acceptsAValidityRangeThatCoversTheTenderDeadline() {
        String certificate = "证书编号：Q-100 证书有效日期：2024年09月04日至2028年09月03日";
        assertEquals(ObjectiveCertificateEligibility.State.ELIGIBLE,
                ObjectiveCertificateEligibility.assess(certificate, LocalDate.of(2025, 8, 5)).state());
    }

    @Test void undatedInvalidStatusCannotProveHistoricalIneligibility() {
        String certificate = "证书编号：Q-100 证书状态：已过期 有效期至：2028-09-03";
        assertEquals(ObjectiveCertificateEligibility.State.UNRESOLVED,
                ObjectiveCertificateEligibility.assess(certificate, LocalDate.of(2025, 8, 5)).state());
    }

    @Test void labelledRangeCoveringDeadlineWinsOverCurrentExpiredStatus() {
        String certificate = "证书编号：Q-100 证书状态：已过期 证书有效日期：2020-01-01至2024-12-31";
        assertEquals(ObjectiveCertificateEligibility.State.ELIGIBLE,
                ObjectiveCertificateEligibility.assess(certificate, LocalDate.of(2024, 6, 1)).state());
    }

    @Test void anExpiryDateAloneCannotProveHistoricalDeadlineEligibility() {
        String certificate = "证书编号：Q-100 证书状态：有效 证书到期日期：2028-09-03";
        assertEquals(ObjectiveCertificateEligibility.State.UNRESOLVED,
                ObjectiveCertificateEligibility.assess(certificate, LocalDate.of(2025, 8, 5)).state());
    }

    @Test void missingTenderDeadlineNeverFallsBackToTheCurrentDate() {
        String certificate = "证书编号：Q-100 证书状态：有效 证书有效日期：2024年09月04日至2028年09月03日";
        ObjectiveCertificateEligibility.Assessment assessment =
                ObjectiveCertificateEligibility.assess(certificate, null);

        assertEquals(ObjectiveCertificateEligibility.State.UNRESOLVED, assessment.state());
        assertEquals("REFERENCE_DATE_UNRESOLVED", assessment.reason());
    }
}
