package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Search expansion is scoped to the current evidence question, never all checkpoint subjects. */
final class AgentEvidenceQuery {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Selection {
        private final String query;
        private final List<Rule> targets;
        private final List<Rule> anchors;
        private final List<String> expandedSubjects;
        @com.fasterxml.jackson.annotation.JsonCreator
        Selection(@com.fasterxml.jackson.annotation.JsonProperty("query") String query, @com.fasterxml.jackson.annotation.JsonProperty("targets") List<Rule> targets, @com.fasterxml.jackson.annotation.JsonProperty("anchors") List<Rule> anchors, @com.fasterxml.jackson.annotation.JsonProperty("expandedSubjects") List<String> expandedSubjects) { this.query = query; this.targets = targets; this.anchors = anchors; this.expandedSubjects = expandedSubjects; }
        public String query() { return query; }
        public List<Rule> targets() { return targets; }
        public List<Rule> anchors() { return anchors; }
        public List<String> expandedSubjects() { return expandedSubjects; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Selection that = (Selection) other; return java.util.Objects.equals(this.query, that.query) && java.util.Objects.equals(this.targets, that.targets) && java.util.Objects.equals(this.anchors, that.anchors) && java.util.Objects.equals(this.expandedSubjects, that.expandedSubjects); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.query); hash = 31 * hash + java.util.Objects.hashCode(this.targets); hash = 31 * hash + java.util.Objects.hashCode(this.anchors); hash = 31 * hash + java.util.Objects.hashCode(this.expandedSubjects); return hash; }
        @Override public String toString() { return "Selection[" + "query=" + query + ", targets=" + targets + ", anchors=" + anchors + ", expandedSubjects=" + expandedSubjects + "]"; }
}
    static Selection select(String query,String targetId,List<Rule> rules,List<Fact> facts,EvidenceCorpus corpus) {
        List<Rule> targets=cn.bidcheck.compat.Java8Api.isBlank(targetId)?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(rules.stream().filter(r->r.id().equals(targetId)));
        List<String> subjects=cn.bidcheck.compat.Java8Api.toList(targets.stream().flatMap(r->AgentRuleSubjects.subjects(r,facts,corpus).stream()).distinct());
        // A named-person query remains a named-person query. Collective requirements must not
        // append every known colleague to each search for a particular social-insurance row.
        List<String> mentioned=cn.bidcheck.compat.Java8Api.toList(subjects.stream().filter(query::contains));
        List<String> expand=!mentioned.isEmpty()?cn.bidcheck.compat.Java8Api.listOf():subjects.size()==1?subjects:cn.bidcheck.compat.Java8Api.listOf();
        String expanded=query+(expand.isEmpty()?"":" "+String.join(" ",expand));
        // Appointment sources remain accessible through knownSources. Once a person's binding
        // exists, pinning their appointment above credential/social-insurance hits wastes slots.
        List<Rule> anchors=cn.bidcheck.compat.Java8Api.toList(targets.stream().filter(r->cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "PERSON_COUNT", "ENTITY_COUNT").contains(r.predicate())
            ||AgentRuleSubjects.selectors(r).stream().anyMatch(s->DomainBindings.role(s)&&DomainBindings.subjects(s,facts,corpus).isEmpty())));
        return new Selection(expanded,targets,anchors,expand);
    }
    private AgentEvidenceQuery() {}
}
