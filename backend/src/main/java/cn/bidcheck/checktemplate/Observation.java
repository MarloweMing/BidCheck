package cn.bidcheck.checktemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated, source-preserving input to deterministic evaluators.
 * Persistence uses an explicit allowlist so external JSON cannot select an executable Java type.
 */
public interface Observation {

    String id();
    TemplateTypes.ObservationKind kind();
    TemplateTypes.ObservationState state();
    String subjectKey();
    List<SourceRef> sources();
    double confidence();

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class MaterialObservation implements Observation {
        private final String id;
        private final String materialType;
        private final Set<String> aliases;
        private final String subjectKey;
        private final boolean currentScope;
        private final boolean bodyPresent;
        private final boolean titleOnly;
        private final Set<String> commitmentPoints;
        private final TemplateTypes.ObservationState state;
        private final List<SourceRef> sources;
        private final double confidence;
        public String id() { return id; }
        public String materialType() { return materialType; }
        public Set<String> aliases() { return aliases; }
        public String subjectKey() { return subjectKey; }
        public boolean currentScope() { return currentScope; }
        public boolean bodyPresent() { return bodyPresent; }
        public boolean titleOnly() { return titleOnly; }
        public Set<String> commitmentPoints() { return commitmentPoints; }
        public TemplateTypes.ObservationState state() { return state; }
        public List<SourceRef> sources() { return sources; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; MaterialObservation that = (MaterialObservation) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.materialType, that.materialType) && java.util.Objects.equals(this.aliases, that.aliases) && java.util.Objects.equals(this.subjectKey, that.subjectKey) && java.util.Objects.equals(this.currentScope, that.currentScope) && java.util.Objects.equals(this.bodyPresent, that.bodyPresent) && java.util.Objects.equals(this.titleOnly, that.titleOnly) && java.util.Objects.equals(this.commitmentPoints, that.commitmentPoints) && java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.materialType); hash = 31 * hash + java.util.Objects.hashCode(this.aliases); hash = 31 * hash + java.util.Objects.hashCode(this.subjectKey); hash = 31 * hash + java.util.Objects.hashCode(this.currentScope); hash = 31 * hash + java.util.Objects.hashCode(this.bodyPresent); hash = 31 * hash + java.util.Objects.hashCode(this.titleOnly); hash = 31 * hash + java.util.Objects.hashCode(this.commitmentPoints); hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "MaterialObservation[" + "id=" + id + ", materialType=" + materialType + ", aliases=" + aliases + ", subjectKey=" + subjectKey + ", currentScope=" + currentScope + ", bodyPresent=" + bodyPresent + ", titleOnly=" + titleOnly + ", commitmentPoints=" + commitmentPoints + ", state=" + state + ", sources=" + sources + ", confidence=" + confidence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public MaterialObservation(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("materialType") String materialType, @com.fasterxml.jackson.annotation.JsonProperty("aliases") Set<String> aliases, @com.fasterxml.jackson.annotation.JsonProperty("subjectKey") String subjectKey, @com.fasterxml.jackson.annotation.JsonProperty("currentScope") boolean currentScope, @com.fasterxml.jackson.annotation.JsonProperty("bodyPresent") boolean bodyPresent, @com.fasterxml.jackson.annotation.JsonProperty("titleOnly") boolean titleOnly, @com.fasterxml.jackson.annotation.JsonProperty("commitmentPoints") Set<String> commitmentPoints, @com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ObservationState state, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) {
            id = required(id, "id");
            materialType = required(materialType, "materialType");
            aliases = aliases == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(aliases);
            subjectKey = text(subjectKey);
            commitmentPoints = commitmentPoints == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(commitmentPoints);
            state = required(state, "state");
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            confidence = normalizedConfidence(confidence);

            this.id = id; this.materialType = materialType; this.aliases = aliases; this.subjectKey = subjectKey; this.currentScope = currentScope; this.bodyPresent = bodyPresent; this.titleOnly = titleOnly; this.commitmentPoints = commitmentPoints; this.state = state; this.sources = sources; this.confidence = confidence;
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.MATERIAL; }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class SealObservation implements Observation {
        private final String id;
        private final String materialObservationId;
        private final String subjectKey;
        private final String location;
        private final boolean currentScope;
        private final boolean observed;
        private final TemplateTypes.ObservationState state;
        private final List<SourceRef> sources;
        private final double confidence;
        public String id() { return id; }
        public String materialObservationId() { return materialObservationId; }
        public String subjectKey() { return subjectKey; }
        public String location() { return location; }
        public boolean currentScope() { return currentScope; }
        public boolean observed() { return observed; }
        public TemplateTypes.ObservationState state() { return state; }
        public List<SourceRef> sources() { return sources; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SealObservation that = (SealObservation) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.materialObservationId, that.materialObservationId) && java.util.Objects.equals(this.subjectKey, that.subjectKey) && java.util.Objects.equals(this.location, that.location) && java.util.Objects.equals(this.currentScope, that.currentScope) && java.util.Objects.equals(this.observed, that.observed) && java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.materialObservationId); hash = 31 * hash + java.util.Objects.hashCode(this.subjectKey); hash = 31 * hash + java.util.Objects.hashCode(this.location); hash = 31 * hash + java.util.Objects.hashCode(this.currentScope); hash = 31 * hash + java.util.Objects.hashCode(this.observed); hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "SealObservation[" + "id=" + id + ", materialObservationId=" + materialObservationId + ", subjectKey=" + subjectKey + ", location=" + location + ", currentScope=" + currentScope + ", observed=" + observed + ", state=" + state + ", sources=" + sources + ", confidence=" + confidence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public SealObservation(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("materialObservationId") String materialObservationId, @com.fasterxml.jackson.annotation.JsonProperty("subjectKey") String subjectKey, @com.fasterxml.jackson.annotation.JsonProperty("location") String location, @com.fasterxml.jackson.annotation.JsonProperty("currentScope") boolean currentScope, @com.fasterxml.jackson.annotation.JsonProperty("observed") boolean observed, @com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ObservationState state, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) {
            id = required(id, "id");
            materialObservationId = text(materialObservationId);
            subjectKey = text(subjectKey);
            location = text(location);
            state = required(state, "state");
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            confidence = normalizedConfidence(confidence);

            this.id = id; this.materialObservationId = materialObservationId; this.subjectKey = subjectKey; this.location = location; this.currentScope = currentScope; this.observed = observed; this.state = state; this.sources = sources; this.confidence = confidence;
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.SEAL; }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class CertificateObservation implements Observation {
        private final String id;
        private final String certificateType;
        private final String holderKey;
        private final String holderName;
        private final String certificateNumber;
        private final LocalDate validFrom;
        private final LocalDate validUntil;
        private final boolean longTerm;
        private final boolean currentScope;
        private final TemplateTypes.ObservationState state;
        private final List<SourceRef> sources;
        private final double confidence;
        public String id() { return id; }
        public String certificateType() { return certificateType; }
        public String holderKey() { return holderKey; }
        public String holderName() { return holderName; }
        public String certificateNumber() { return certificateNumber; }
        public LocalDate validFrom() { return validFrom; }
        public LocalDate validUntil() { return validUntil; }
        public boolean longTerm() { return longTerm; }
        public boolean currentScope() { return currentScope; }
        public TemplateTypes.ObservationState state() { return state; }
        public List<SourceRef> sources() { return sources; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; CertificateObservation that = (CertificateObservation) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.certificateType, that.certificateType) && java.util.Objects.equals(this.holderKey, that.holderKey) && java.util.Objects.equals(this.holderName, that.holderName) && java.util.Objects.equals(this.certificateNumber, that.certificateNumber) && java.util.Objects.equals(this.validFrom, that.validFrom) && java.util.Objects.equals(this.validUntil, that.validUntil) && java.util.Objects.equals(this.longTerm, that.longTerm) && java.util.Objects.equals(this.currentScope, that.currentScope) && java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.certificateType); hash = 31 * hash + java.util.Objects.hashCode(this.holderKey); hash = 31 * hash + java.util.Objects.hashCode(this.holderName); hash = 31 * hash + java.util.Objects.hashCode(this.certificateNumber); hash = 31 * hash + java.util.Objects.hashCode(this.validFrom); hash = 31 * hash + java.util.Objects.hashCode(this.validUntil); hash = 31 * hash + java.util.Objects.hashCode(this.longTerm); hash = 31 * hash + java.util.Objects.hashCode(this.currentScope); hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "CertificateObservation[" + "id=" + id + ", certificateType=" + certificateType + ", holderKey=" + holderKey + ", holderName=" + holderName + ", certificateNumber=" + certificateNumber + ", validFrom=" + validFrom + ", validUntil=" + validUntil + ", longTerm=" + longTerm + ", currentScope=" + currentScope + ", state=" + state + ", sources=" + sources + ", confidence=" + confidence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public CertificateObservation(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("certificateType") String certificateType, @com.fasterxml.jackson.annotation.JsonProperty("holderKey") String holderKey, @com.fasterxml.jackson.annotation.JsonProperty("holderName") String holderName, @com.fasterxml.jackson.annotation.JsonProperty("certificateNumber") String certificateNumber, @com.fasterxml.jackson.annotation.JsonProperty("validFrom") LocalDate validFrom, @com.fasterxml.jackson.annotation.JsonProperty("validUntil") LocalDate validUntil, @com.fasterxml.jackson.annotation.JsonProperty("longTerm") boolean longTerm, @com.fasterxml.jackson.annotation.JsonProperty("currentScope") boolean currentScope, @com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ObservationState state, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) {
            id = required(id, "id");
            certificateType = required(certificateType, "certificateType");
            holderKey = text(holderKey);
            holderName = text(holderName);
            certificateNumber = text(certificateNumber);
            state = required(state, "state");
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            confidence = normalizedConfidence(confidence);

            this.id = id; this.certificateType = certificateType; this.holderKey = holderKey; this.holderName = holderName; this.certificateNumber = certificateNumber; this.validFrom = validFrom; this.validUntil = validUntil; this.longTerm = longTerm; this.currentScope = currentScope; this.state = state; this.sources = sources; this.confidence = confidence;
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.CERTIFICATE; }
        @Override public String subjectKey() { return holderKey; }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class PriceObservation implements Observation {
        private final String id;
        private final String field;
        private final String subjectKey;
        private final String lot;
        private final String priceLayer;
        private final String taxBasis;
        private final String currency;
        private final String unit;
        private final BigDecimal amount;
        private final boolean currentScope;
        private final TemplateTypes.ObservationState state;
        private final List<SourceRef> sources;
        private final double confidence;
        public String id() { return id; }
        public String field() { return field; }
        public String subjectKey() { return subjectKey; }
        public String lot() { return lot; }
        public String priceLayer() { return priceLayer; }
        public String taxBasis() { return taxBasis; }
        public String currency() { return currency; }
        public String unit() { return unit; }
        public BigDecimal amount() { return amount; }
        public boolean currentScope() { return currentScope; }
        public TemplateTypes.ObservationState state() { return state; }
        public List<SourceRef> sources() { return sources; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PriceObservation that = (PriceObservation) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.subjectKey, that.subjectKey) && java.util.Objects.equals(this.lot, that.lot) && java.util.Objects.equals(this.priceLayer, that.priceLayer) && java.util.Objects.equals(this.taxBasis, that.taxBasis) && java.util.Objects.equals(this.currency, that.currency) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.amount, that.amount) && java.util.Objects.equals(this.currentScope, that.currentScope) && java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.subjectKey); hash = 31 * hash + java.util.Objects.hashCode(this.lot); hash = 31 * hash + java.util.Objects.hashCode(this.priceLayer); hash = 31 * hash + java.util.Objects.hashCode(this.taxBasis); hash = 31 * hash + java.util.Objects.hashCode(this.currency); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.amount); hash = 31 * hash + java.util.Objects.hashCode(this.currentScope); hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "PriceObservation[" + "id=" + id + ", field=" + field + ", subjectKey=" + subjectKey + ", lot=" + lot + ", priceLayer=" + priceLayer + ", taxBasis=" + taxBasis + ", currency=" + currency + ", unit=" + unit + ", amount=" + amount + ", currentScope=" + currentScope + ", state=" + state + ", sources=" + sources + ", confidence=" + confidence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public PriceObservation(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("subjectKey") String subjectKey, @com.fasterxml.jackson.annotation.JsonProperty("lot") String lot, @com.fasterxml.jackson.annotation.JsonProperty("priceLayer") String priceLayer, @com.fasterxml.jackson.annotation.JsonProperty("taxBasis") String taxBasis, @com.fasterxml.jackson.annotation.JsonProperty("currency") String currency, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("amount") BigDecimal amount, @com.fasterxml.jackson.annotation.JsonProperty("currentScope") boolean currentScope, @com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ObservationState state, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) {
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
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            confidence = normalizedConfidence(confidence);

            this.id = id; this.field = field; this.subjectKey = subjectKey; this.lot = lot; this.priceLayer = priceLayer; this.taxBasis = taxBasis; this.currency = currency; this.unit = unit; this.amount = amount; this.currentScope = currentScope; this.state = state; this.sources = sources; this.confidence = confidence;
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.PRICE; }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class PersonnelObservation implements Observation {
        private final String id;
        private final String personKey;
        private final String personName;
        private final Set<String> roles;
        private final String employerKey;
        private final String consortiumRole;
        private final boolean currentAppointment;
        private final boolean currentScope;
        private final Map<String, String> attributes;
        private final TemplateTypes.ObservationState state;
        private final List<SourceRef> sources;
        private final double confidence;
        public String id() { return id; }
        public String personKey() { return personKey; }
        public String personName() { return personName; }
        public Set<String> roles() { return roles; }
        public String employerKey() { return employerKey; }
        public String consortiumRole() { return consortiumRole; }
        public boolean currentAppointment() { return currentAppointment; }
        public boolean currentScope() { return currentScope; }
        public Map<String, String> attributes() { return attributes; }
        public TemplateTypes.ObservationState state() { return state; }
        public List<SourceRef> sources() { return sources; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PersonnelObservation that = (PersonnelObservation) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.personKey, that.personKey) && java.util.Objects.equals(this.personName, that.personName) && java.util.Objects.equals(this.roles, that.roles) && java.util.Objects.equals(this.employerKey, that.employerKey) && java.util.Objects.equals(this.consortiumRole, that.consortiumRole) && java.util.Objects.equals(this.currentAppointment, that.currentAppointment) && java.util.Objects.equals(this.currentScope, that.currentScope) && java.util.Objects.equals(this.attributes, that.attributes) && java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.personKey); hash = 31 * hash + java.util.Objects.hashCode(this.personName); hash = 31 * hash + java.util.Objects.hashCode(this.roles); hash = 31 * hash + java.util.Objects.hashCode(this.employerKey); hash = 31 * hash + java.util.Objects.hashCode(this.consortiumRole); hash = 31 * hash + java.util.Objects.hashCode(this.currentAppointment); hash = 31 * hash + java.util.Objects.hashCode(this.currentScope); hash = 31 * hash + java.util.Objects.hashCode(this.attributes); hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "PersonnelObservation[" + "id=" + id + ", personKey=" + personKey + ", personName=" + personName + ", roles=" + roles + ", employerKey=" + employerKey + ", consortiumRole=" + consortiumRole + ", currentAppointment=" + currentAppointment + ", currentScope=" + currentScope + ", attributes=" + attributes + ", state=" + state + ", sources=" + sources + ", confidence=" + confidence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public PersonnelObservation(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("personKey") String personKey, @com.fasterxml.jackson.annotation.JsonProperty("personName") String personName, @com.fasterxml.jackson.annotation.JsonProperty("roles") Set<String> roles, @com.fasterxml.jackson.annotation.JsonProperty("employerKey") String employerKey, @com.fasterxml.jackson.annotation.JsonProperty("consortiumRole") String consortiumRole, @com.fasterxml.jackson.annotation.JsonProperty("currentAppointment") boolean currentAppointment, @com.fasterxml.jackson.annotation.JsonProperty("currentScope") boolean currentScope, @com.fasterxml.jackson.annotation.JsonProperty("attributes") Map<String, String> attributes, @com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ObservationState state, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) {
            id = required(id, "id");
            personKey = required(personKey, "personKey");
            personName = text(personName);
            roles = roles == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(roles);
            employerKey = text(employerKey);
            consortiumRole = text(consortiumRole);
            attributes = attributes == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(attributes);
            state = required(state, "state");
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            confidence = normalizedConfidence(confidence);

            this.id = id; this.personKey = personKey; this.personName = personName; this.roles = roles; this.employerKey = employerKey; this.consortiumRole = consortiumRole; this.currentAppointment = currentAppointment; this.currentScope = currentScope; this.attributes = attributes; this.state = state; this.sources = sources; this.confidence = confidence;
        }
        @Override public TemplateTypes.ObservationKind kind() { return TemplateTypes.ObservationKind.PERSONNEL; }
        @Override public String subjectKey() { return personKey; }
    }

    static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    static String text(String value) { return value == null ? "" : value.trim(); }
    static double normalizedConfidence(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("confidence must be in [0,1]");
        return value;
    }
}
