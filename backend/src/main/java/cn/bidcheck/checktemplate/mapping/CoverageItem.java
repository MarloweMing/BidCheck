package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;

/** One ledger row per source fragment; no source fragment disappears silently. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class CoverageItem {
        private final String fragmentId;
        private final String clauseId;
        private final String text;
        private final SourceRef sourceRef;
        private final SourceContext sourceContext;
        private final Status status;
        private final String draftId;
        private final List<String> templateIds;
        private final String reasonCode;
        private final String explanation;
        public String fragmentId() { return fragmentId; }
        public String clauseId() { return clauseId; }
        public String text() { return text; }
        public SourceRef sourceRef() { return sourceRef; }
        public SourceContext sourceContext() { return sourceContext; }
        public Status status() { return status; }
        public String draftId() { return draftId; }
        public List<String> templateIds() { return templateIds; }
        public String reasonCode() { return reasonCode; }
        public String explanation() { return explanation; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; CoverageItem that = (CoverageItem) other; return java.util.Objects.equals(this.fragmentId, that.fragmentId) && java.util.Objects.equals(this.clauseId, that.clauseId) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.sourceRef, that.sourceRef) && java.util.Objects.equals(this.sourceContext, that.sourceContext) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.draftId, that.draftId) && java.util.Objects.equals(this.templateIds, that.templateIds) && java.util.Objects.equals(this.reasonCode, that.reasonCode) && java.util.Objects.equals(this.explanation, that.explanation); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.fragmentId); hash = 31 * hash + java.util.Objects.hashCode(this.clauseId); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.sourceRef); hash = 31 * hash + java.util.Objects.hashCode(this.sourceContext); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.draftId); hash = 31 * hash + java.util.Objects.hashCode(this.templateIds); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); hash = 31 * hash + java.util.Objects.hashCode(this.explanation); return hash; }
        @Override public String toString() { return "CoverageItem[" + "fragmentId=" + fragmentId + ", clauseId=" + clauseId + ", text=" + text + ", sourceRef=" + sourceRef + ", sourceContext=" + sourceContext + ", status=" + status + ", draftId=" + draftId + ", templateIds=" + templateIds + ", reasonCode=" + reasonCode + ", explanation=" + explanation + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public CoverageItem(@com.fasterxml.jackson.annotation.JsonProperty("fragmentId") String fragmentId, @com.fasterxml.jackson.annotation.JsonProperty("clauseId") String clauseId, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("sourceRef") SourceRef sourceRef, @com.fasterxml.jackson.annotation.JsonProperty("sourceContext") SourceContext sourceContext, @com.fasterxml.jackson.annotation.JsonProperty("status") Status status, @com.fasterxml.jackson.annotation.JsonProperty("draftId") String draftId, @com.fasterxml.jackson.annotation.JsonProperty("templateIds") List<String> templateIds, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode, @com.fasterxml.jackson.annotation.JsonProperty("explanation") String explanation) {
        fragmentId = required(fragmentId, "fragmentId");
        clauseId = required(clauseId, "clauseId");
        text = text == null ? "" : text.trim();
        if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
        if (sourceContext == null) throw new IllegalArgumentException("sourceContext is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        draftId = draftId == null ? "" : draftId.trim();
        templateIds = templateIds == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(templateIds.stream().distinct());
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        explanation = explanation == null ? "" : explanation.trim();

            this.fragmentId = fragmentId; this.clauseId = clauseId; this.text = text; this.sourceRef = sourceRef; this.sourceContext = sourceContext; this.status = status; this.draftId = draftId; this.templateIds = templateIds; this.reasonCode = reasonCode; this.explanation = explanation;
        }

    public enum Status {
        MAPPED,
        NEEDS_CONFIRMATION,
        NON_CHECKING,
        EXTERNAL_REQUIRED,
        UNSUPPORTED
    }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
