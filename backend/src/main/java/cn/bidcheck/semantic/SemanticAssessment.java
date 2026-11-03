package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Evaluation;
import java.util.Map;

/** Raw is nullable when no valid structured model judgment was obtained. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class SemanticAssessment {
        private final Evaluation rawEvaluation;
        private final Evaluation evaluation;
        private final Map<String, Object> audit;
        @com.fasterxml.jackson.annotation.JsonCreator
        public SemanticAssessment(@com.fasterxml.jackson.annotation.JsonProperty("rawEvaluation") Evaluation rawEvaluation, @com.fasterxml.jackson.annotation.JsonProperty("evaluation") Evaluation evaluation, @com.fasterxml.jackson.annotation.JsonProperty("audit") Map<String, Object> audit) { this.rawEvaluation = rawEvaluation; this.evaluation = evaluation; this.audit = audit; }
        public Evaluation rawEvaluation() { return rawEvaluation; }
        public Evaluation evaluation() { return evaluation; }
        public Map<String, Object> audit() { return audit; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SemanticAssessment that = (SemanticAssessment) other; return java.util.Objects.equals(this.rawEvaluation, that.rawEvaluation) && java.util.Objects.equals(this.evaluation, that.evaluation) && java.util.Objects.equals(this.audit, that.audit); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.rawEvaluation); hash = 31 * hash + java.util.Objects.hashCode(this.evaluation); hash = 31 * hash + java.util.Objects.hashCode(this.audit); return hash; }
        @Override public String toString() { return "SemanticAssessment[" + "rawEvaluation=" + rawEvaluation + ", evaluation=" + evaluation + ", audit=" + audit + "]"; }
 }
