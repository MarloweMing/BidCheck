package cn.bidcheck.checktemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckTemplateRegistryVerificationModeTest {
    private final CheckTemplateRegistry registry = CheckTemplateRegistry.defaultRegistry();
    private final SourceRef source = new SourceRef("tender", 1, "hash", "parser", "source",
            "PAGE", 1, "测试要求", 0, 4);

    @Test void externalCheckIsTheOnlyTemplateAllowedToUseExternalVerification() {
        assertFalse(modeError(spec("EXTERNAL_CHECK", TemplateTypes.VerificationMode.EXTERNAL,
                TemplateTypes.ProofStandard.FACTUAL_CONDITION)));
        assertTrue(modeError(spec("EXTERNAL_CHECK", TemplateTypes.VerificationMode.DOCUMENT,
                TemplateTypes.ProofStandard.FACTUAL_CONDITION)));
        assertTrue(modeError(spec("MATERIAL_PRESENT", TemplateTypes.VerificationMode.EXTERNAL,
                TemplateTypes.ProofStandard.MATERIAL_SUBMISSION)));
    }

    @Test void qualityAdviceRequiresAnExplicitHumanJudgmentMode() {
        assertFalse(modeError(spec("QUALITY_ADVISORY", TemplateTypes.VerificationMode.HUMAN_JUDGMENT,
                TemplateTypes.ProofStandard.QUALITY_ADVISORY)));
        assertTrue(modeError(spec("QUALITY_ADVISORY", TemplateTypes.VerificationMode.DOCUMENT,
                TemplateTypes.ProofStandard.QUALITY_ADVISORY)));
    }

    @Test void publicMetadataDeclaresTheModeThatTheRegistryEnforces() {
        Map<String,TemplateTypes.VerificationMode> modes=registry.metadata().stream()
                .collect(java.util.stream.Collectors.toMap(TemplateMetadata::id,TemplateMetadata::requiredVerificationMode));
        assertEquals(TemplateTypes.VerificationMode.EXTERNAL,modes.get("EXTERNAL_CHECK"));
        assertEquals(TemplateTypes.VerificationMode.HUMAN_JUDGMENT,modes.get("QUALITY_ADVISORY"));
        assertEquals(TemplateTypes.VerificationMode.DOCUMENT,modes.get("PRICE_COMPARE"));
    }

    private boolean modeError(CheckSpec spec) {
        return registry.validate(spec).errors().stream()
                .anyMatch(error -> "verificationMode".equals(error.field()));
    }

    private CheckSpec spec(String templateId, TemplateTypes.VerificationMode mode,
                           TemplateTypes.ProofStandard standard) {
        return new CheckSpec("spec-" + templateId, 1, "item-" + templateId,
                "checkpoint-" + templateId, TemplateTypes.Category.QUALIFICATION,
                TemplateTypes.Purpose.MANDATORY, mode, templateId, "1.0",
                SubjectSelector.bidder(), Map.of(), Applicability.applies(), standard,
                List.of(source), List.of(), Map.of(), Map.of(),
                Map.of("state", "FULL", "complete", true), true,
                Confirmation.legacyConfirmed(), "semantic-" + templateId);
    }
}
