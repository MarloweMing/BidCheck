package cn.bidcheck.checktemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated, source-preserving input to deterministic evaluators.
 * Implementations are closed so external JSON cannot select an executable Java type.
 */
public sealed interface Observation permits Observation.MaterialObservation, Observation.SealObservation,
        Observation.CertificateObservation, Observation.PriceObservation, Observation.PersonnelObservation {

    String id();
    TemplateTypes.ObservationKind kind();
    TemplateTypes.ObservationState state();
    String subjectKey();
    List<SourceRef> sources();
    double confidence();

    record MaterialObservation(
            String id,
            String materialType,
            Set<String> aliases,
            String subjectKey,
            boolean currentScope,
            boolean bodyPresent,
            boolean titleOnly,
            Set<String> commitmentPoints,
            TemplateTypes.ObservationState state,
            List<SourceRef> sources,
            double confidence) implements Observation {
        public MaterialObservation {
            id = required(id, "id");
            materialType = required(materialType, "materialType");
            aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
            subjectKey = text(subjectKey);
            commitmentPoints = commitmentPoints == null ? Set.of() : Set.copyOf(commitmentPoints);
            state = required(state, "state");
            sources = sources == null ? List.of() : List.copyOf(sources);
            confidence = normalizedConfidence(confidence);
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.MATERIAL; }
    }

    record SealObservation(
            String id,
            String materialObservationId,
            String subjectKey,
            String location,
            boolean currentScope,
            boolean observed,
            TemplateTypes.ObservationState state,
            List<SourceRef> sources,
            double confidence) implements Observation {
        public SealObservation {
            id = required(id, "id");
            materialObservationId = text(materialObservationId);
            subjectKey = text(subjectKey);
            location = text(location);
            state = required(state, "state");
            sources = sources == null ? List.of() : List.copyOf(sources);
            confidence = normalizedConfidence(confidence);
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.SEAL; }
    }

    record CertificateObservation(
            String id,
            String certificateType,
            String holderKey,
            String holderName,
            String certificateNumber,
            LocalDate validFrom,
            LocalDate validUntil,
            boolean longTerm,
            boolean currentScope,
            TemplateTypes.ObservationState state,
            List<SourceRef> sources,
            double confidence) implements Observation {
        public CertificateObservation {
            id = required(id, "id");
            certificateType = required(certificateType, "certificateType");
            holderKey = text(holderKey);
            holderName = text(holderName);
            certificateNumber = text(certificateNumber);
            state = required(state, "state");
            sources = sources == null ? List.of() : List.copyOf(sources);
            confidence = normalizedConfidence(confidence);
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.CERTIFICATE; }
        @Override public String subjectKey() { return holderKey; }
    }

    record PriceObservation(
            String id,
            String field,
            String subjectKey,
            String lot,
            String priceLayer,
            String taxBasis,
            String currency,
            String unit,
            BigDecimal amount,
            boolean currentScope,
            TemplateTypes.ObservationState state,
            List<SourceRef> sources,
            double confidence) implements Observation {
        public PriceObservation {
            id = required(id, "id");
            field = required(field, "field");
            subjectKey = text(subjectKey);
            lot = text(lot);
            priceLayer = text(priceLayer);
            taxBasis = text(taxBasis);
            currency = required(currency, "currency");
            unit = required(unit, "unit");
            if (amount == null) throw new IllegalArgumentException("amount is required");
            state = required(state, "state");
            sources = sources == null ? List.of() : List.copyOf(sources);
            confidence = normalizedConfidence(confidence);
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.PRICE; }
    }

    record PersonnelObservation(
            String id,
            String personKey,
            String personName,
            Set<String> roles,
            String employerKey,
            String consortiumRole,
            boolean currentAppointment,
            boolean currentScope,
            Map<String, String> attributes,
            TemplateTypes.ObservationState state,
            List<SourceRef> sources,
            double confidence) implements Observation {
        public PersonnelObservation {
            id = required(id, "id");
            personKey = required(personKey, "personKey");
            personName = text(personName);
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            employerKey = text(employerKey);
            consortiumRole = text(consortiumRole);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            state = required(state, "state");
            sources = sources == null ? List.of() : List.copyOf(sources);
            confidence = normalizedConfidence(confidence);
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.PERSONNEL; }
        @Override public String subjectKey() { return personKey; }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    private static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static double normalizedConfidence(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("confidence must be in [0,1]");
        return value;
    }
}
