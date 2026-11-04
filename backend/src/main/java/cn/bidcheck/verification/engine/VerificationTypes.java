package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;

final class VerificationTypes {
    static final String VERSION="verification-engine-2.31-java-strict-ticket-revision-10";
    static final Set<String> PREDICATES=cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "DECLARATION", "SECTION", "PERSON_ROLE", "PERSON_COUNT", "ENTITY_COUNT", "NOT_APPLICABLE", "CERTIFICATE", "VALIDITY", "MONTHS", "RELATION", "NUMBER", "SAME_FIELD", "PRICE_RECONCILE", "DATE_RELATION", "CONTRACT", "SCORE_COUNT", "TEXT_ANOMALY", "QUALITY_ADVISORY", "QUALITY", "EXTERNAL", "VISUAL", "REFERENCE_TIME", "INPUT", "UNSUPPORTED");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Rule {
        private final String id;
        private final String description;
        private final String predicate;
        private final String subject;
        private final String field;
        private final String operator;
        private final String expected;
        private final String unit;
        private final Map<String,Object> parameters;
        private final String sourceQuote;
        private final String logic;
        private final List<Rule> children;
        public String id() { return id; }
        public String description() { return description; }
        public String predicate() { return predicate; }
        public String subject() { return subject; }
        public String field() { return field; }
        public String operator() { return operator; }
        public String expected() { return expected; }
        public String unit() { return unit; }
        public Map<String,Object> parameters() { return parameters; }
        public String sourceQuote() { return sourceQuote; }
        public String logic() { return logic; }
        public List<Rule> children() { return children; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Rule that = (Rule) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.description, that.description) && java.util.Objects.equals(this.predicate, that.predicate) && java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.operator, that.operator) && java.util.Objects.equals(this.expected, that.expected) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.sourceQuote, that.sourceQuote) && java.util.Objects.equals(this.logic, that.logic) && java.util.Objects.equals(this.children, that.children); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.description); hash = 31 * hash + java.util.Objects.hashCode(this.predicate); hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.operator); hash = 31 * hash + java.util.Objects.hashCode(this.expected); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.parameters); hash = 31 * hash + java.util.Objects.hashCode(this.sourceQuote); hash = 31 * hash + java.util.Objects.hashCode(this.logic); hash = 31 * hash + java.util.Objects.hashCode(this.children); return hash; }
        @Override public String toString() { return "Rule[" + "id=" + id + ", description=" + description + ", predicate=" + predicate + ", subject=" + subject + ", field=" + field + ", operator=" + operator + ", expected=" + expected + ", unit=" + unit + ", parameters=" + parameters + ", sourceQuote=" + sourceQuote + ", logic=" + logic + ", children=" + children + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        Rule(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("description") String description, @com.fasterxml.jackson.annotation.JsonProperty("predicate") String predicate, @com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("operator") String operator, @com.fasterxml.jackson.annotation.JsonProperty("expected") String expected, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("parameters") Map<String,Object> parameters, @com.fasterxml.jackson.annotation.JsonProperty("sourceQuote") String sourceQuote, @com.fasterxml.jackson.annotation.JsonProperty("logic") String logic, @com.fasterxml.jackson.annotation.JsonProperty("children") List<Rule> children) { parameters=parameters==null?cn.bidcheck.compat.Java8Api.mapOf():cn.bidcheck.compat.Java8Api.mapCopyOf(parameters);children=children==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(children);
            this.id = id; this.description = description; this.predicate = predicate; this.subject = subject; this.field = field; this.operator = operator; this.expected = expected; this.unit = unit; this.parameters = parameters; this.sourceQuote = sourceQuote; this.logic = logic; this.children = children;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Plan {
        private final String logic;
        private final List<Rule> rules;
        private final String method;
        private final List<String> issues;
        private final List<Map<String,Object>> policyNotes;
        private final Map<String,Object> requirementScope;
        @com.fasterxml.jackson.annotation.JsonCreator
        Plan(@com.fasterxml.jackson.annotation.JsonProperty("logic") String logic, @com.fasterxml.jackson.annotation.JsonProperty("rules") List<Rule> rules, @com.fasterxml.jackson.annotation.JsonProperty("method") String method, @com.fasterxml.jackson.annotation.JsonProperty("issues") List<String> issues, @com.fasterxml.jackson.annotation.JsonProperty("policyNotes") List<Map<String,Object>> policyNotes, @com.fasterxml.jackson.annotation.JsonProperty("requirementScope") Map<String,Object> requirementScope) { this.logic = logic; this.rules = rules; this.method = method; this.issues = issues; this.policyNotes = policyNotes; this.requirementScope = requirementScope; }
        public String logic() { return logic; }
        public List<Rule> rules() { return rules; }
        public String method() { return method; }
        public List<String> issues() { return issues; }
        public List<Map<String,Object>> policyNotes() { return policyNotes; }
        public Map<String,Object> requirementScope() { return requirementScope; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Plan that = (Plan) other; return java.util.Objects.equals(this.logic, that.logic) && java.util.Objects.equals(this.rules, that.rules) && java.util.Objects.equals(this.method, that.method) && java.util.Objects.equals(this.issues, that.issues) && java.util.Objects.equals(this.policyNotes, that.policyNotes) && java.util.Objects.equals(this.requirementScope, that.requirementScope); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.logic); hash = 31 * hash + java.util.Objects.hashCode(this.rules); hash = 31 * hash + java.util.Objects.hashCode(this.method); hash = 31 * hash + java.util.Objects.hashCode(this.issues); hash = 31 * hash + java.util.Objects.hashCode(this.policyNotes); hash = 31 * hash + java.util.Objects.hashCode(this.requirementScope); return hash; }
        @Override public String toString() { return "Plan[" + "logic=" + logic + ", rules=" + rules + ", method=" + method + ", issues=" + issues + ", policyNotes=" + policyNotes + ", requirementScope=" + requirementScope + "]"; }

        Plan(String logic,List<Rule> rules,String method,List<String> issues){this(logic,rules,method,issues,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());}
        Plan(String logic,List<Rule> rules,String method,List<String> issues,List<Map<String,Object>> policyNotes){this(logic,rules,method,issues,policyNotes,cn.bidcheck.compat.Java8Api.mapOf());}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Source {
        private final String id;
        private final Evidence evidence;
        private final String opening;
        private final boolean index;
        private final boolean historical;
        private final String documentHash;
        private final int documentVersion;
        private final DocumentSubjectScope.Context scopeContext;
        public String id() { return id; }
        public Evidence evidence() { return evidence; }
        public String opening() { return opening; }
        public boolean index() { return index; }
        public boolean historical() { return historical; }
        public String documentHash() { return documentHash; }
        public int documentVersion() { return documentVersion; }
        public DocumentSubjectScope.Context scopeContext() { return scopeContext; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Source that = (Source) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.evidence, that.evidence) && java.util.Objects.equals(this.opening, that.opening) && java.util.Objects.equals(this.index, that.index) && java.util.Objects.equals(this.historical, that.historical) && java.util.Objects.equals(this.documentHash, that.documentHash) && java.util.Objects.equals(this.documentVersion, that.documentVersion) && java.util.Objects.equals(this.scopeContext, that.scopeContext); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); hash = 31 * hash + java.util.Objects.hashCode(this.opening); hash = 31 * hash + java.util.Objects.hashCode(this.index); hash = 31 * hash + java.util.Objects.hashCode(this.historical); hash = 31 * hash + java.util.Objects.hashCode(this.documentHash); hash = 31 * hash + java.util.Objects.hashCode(this.documentVersion); hash = 31 * hash + java.util.Objects.hashCode(this.scopeContext); return hash; }
        @Override public String toString() { return "Source[" + "id=" + id + ", evidence=" + evidence + ", opening=" + opening + ", index=" + index + ", historical=" + historical + ", documentHash=" + documentHash + ", documentVersion=" + documentVersion + ", scopeContext=" + scopeContext + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        Source(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence, @com.fasterxml.jackson.annotation.JsonProperty("opening") String opening, @com.fasterxml.jackson.annotation.JsonProperty("index") boolean index, @com.fasterxml.jackson.annotation.JsonProperty("historical") boolean historical, @com.fasterxml.jackson.annotation.JsonProperty("documentHash") String documentHash, @com.fasterxml.jackson.annotation.JsonProperty("documentVersion") int documentVersion, @com.fasterxml.jackson.annotation.JsonProperty("scopeContext") DocumentSubjectScope.Context scopeContext) { scopeContext=scopeContext==null?DocumentSubjectScope.Context.unspecified():scopeContext;
            this.id = id; this.evidence = evidence; this.opening = opening; this.index = index; this.historical = historical; this.documentHash = documentHash; this.documentVersion = documentVersion; this.scopeContext = scopeContext;
        }
        Source(String id,Evidence evidence,String opening,boolean index,boolean historical,String documentHash,int documentVersion){this(id,evidence,opening,index,historical,documentHash,documentVersion,DocumentSubjectScope.Context.unspecified());}
        Source(String id,Evidence evidence,String opening,boolean index,boolean historical){this(id,evidence,opening,index,historical,"",0);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Fact {
        private final String id;
        private final String kind;
        private final String subject;
        private final String field;
        private final String rawValue;
        private final String value;
        private final String unit;
        private final String basis;
        private final String scope;
        private final List<String> sourceIds;
        private final Map<String,Object> attributes;
        @com.fasterxml.jackson.annotation.JsonCreator
        Fact(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("kind") String kind, @com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("rawValue") String rawValue, @com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("basis") String basis, @com.fasterxml.jackson.annotation.JsonProperty("scope") String scope, @com.fasterxml.jackson.annotation.JsonProperty("sourceIds") List<String> sourceIds, @com.fasterxml.jackson.annotation.JsonProperty("attributes") Map<String,Object> attributes) { this.id = id; this.kind = kind; this.subject = subject; this.field = field; this.rawValue = rawValue; this.value = value; this.unit = unit; this.basis = basis; this.scope = scope; this.sourceIds = sourceIds; this.attributes = attributes; }
        public String id() { return id; }
        public String kind() { return kind; }
        public String subject() { return subject; }
        public String field() { return field; }
        public String rawValue() { return rawValue; }
        public String value() { return value; }
        public String unit() { return unit; }
        public String basis() { return basis; }
        public String scope() { return scope; }
        public List<String> sourceIds() { return sourceIds; }
        public Map<String,Object> attributes() { return attributes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fact that = (Fact) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.rawValue, that.rawValue) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.basis, that.basis) && java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.sourceIds, that.sourceIds) && java.util.Objects.equals(this.attributes, that.attributes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.rawValue); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.basis); hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.sourceIds); hash = 31 * hash + java.util.Objects.hashCode(this.attributes); return hash; }
        @Override public String toString() { return "Fact[" + "id=" + id + ", kind=" + kind + ", subject=" + subject + ", field=" + field + ", rawValue=" + rawValue + ", value=" + value + ", unit=" + unit + ", basis=" + basis + ", scope=" + scope + ", sourceIds=" + sourceIds + ", attributes=" + attributes + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Gap {
        private final String code;
        private final String description;
        private final String requiredInput;
        private final String resumeTrigger;
        @com.fasterxml.jackson.annotation.JsonCreator
        Gap(@com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("description") String description, @com.fasterxml.jackson.annotation.JsonProperty("requiredInput") String requiredInput, @com.fasterxml.jackson.annotation.JsonProperty("resumeTrigger") String resumeTrigger) { this.code = code; this.description = description; this.requiredInput = requiredInput; this.resumeTrigger = resumeTrigger; }
        public String code() { return code; }
        public String description() { return description; }
        public String requiredInput() { return requiredInput; }
        public String resumeTrigger() { return resumeTrigger; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Gap that = (Gap) other; return java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.description, that.description) && java.util.Objects.equals(this.requiredInput, that.requiredInput) && java.util.Objects.equals(this.resumeTrigger, that.resumeTrigger); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.description); hash = 31 * hash + java.util.Objects.hashCode(this.requiredInput); hash = 31 * hash + java.util.Objects.hashCode(this.resumeTrigger); return hash; }
        @Override public String toString() { return "Gap[" + "code=" + code + ", description=" + description + ", requiredInput=" + requiredInput + ", resumeTrigger=" + resumeTrigger + "]"; }

        Map<String,Object> map(){return linked("code",code,"description",description,"requiredInput",requiredInput,"resumeTrigger",resumeTrigger);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Decision {
        private final String id;
        private final String description;
        private final String status;
        private final String reason;
        private final List<Evidence> evidences;
        private final List<Gap> gaps;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        Decision(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("description") String description, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("evidences") List<Evidence> evidences, @com.fasterxml.jackson.annotation.JsonProperty("gaps") List<Gap> gaps, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.id = id; this.description = description; this.status = status; this.reason = reason; this.evidences = evidences; this.gaps = gaps; this.proof = proof; }
        public String id() { return id; }
        public String description() { return description; }
        public String status() { return status; }
        public String reason() { return reason; }
        public List<Evidence> evidences() { return evidences; }
        public List<Gap> gaps() { return gaps; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Decision that = (Decision) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.description, that.description) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.evidences, that.evidences) && java.util.Objects.equals(this.gaps, that.gaps) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.description); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.evidences); hash = 31 * hash + java.util.Objects.hashCode(this.gaps); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "Decision[" + "id=" + id + ", description=" + description + ", status=" + status + ", reason=" + reason + ", evidences=" + evidences + ", gaps=" + gaps + ", proof=" + proof + "]"; }

        Map<String,Object> map(){return linked("id",id,"description",description,"status",status,"reason",reason,"evidences",evidences,"gaps",cn.bidcheck.compat.Java8Api.toList(gaps.stream().map(Gap::map)),"proof",proof);}
    }
    static Map<String,Object> linked(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(values[i].toString(),values[i+1]);return out;}
    static String str(Map<String,?> map,String key){Object v=map.get(key);return v==null?"":v.toString();}
    static String clean(String value){return value==null?"":value.replaceAll("\\s+","").trim();}
    static String identity(String value){try{return cn.bidcheck.compat.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String redact(String value){return value==null?"":value.replaceAll("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])","[证件号码已隐藏]");}
    static Gap gap(String code,String detail){return new Gap(code,detail,detail,"INPUT_OR_EVIDENCE_CHANGED");}
}
