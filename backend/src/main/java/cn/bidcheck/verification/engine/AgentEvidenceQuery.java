package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Search expansion is scoped to the current evidence question, never all checkpoint subjects. */
final class AgentEvidenceQuery {
    record Selection(String query,List<Rule> targets,List<Rule> anchors,List<String> expandedSubjects) {}
    static Selection select(String query,String targetId,List<Rule> rules,List<Fact> facts,EvidenceCorpus corpus) {
        List<Rule> targets=targetId.isBlank()?List.of():rules.stream().filter(r->r.id().equals(targetId)).toList();
        List<String> subjects=targets.stream().flatMap(r->AgentRuleSubjects.subjects(r,facts,corpus).stream()).distinct().toList();
        // A named-person query remains a named-person query. Collective requirements must not
        // append every known colleague to each search for a particular social-insurance row.
        List<String> mentioned=subjects.stream().filter(query::contains).toList();
        List<String> expand=!mentioned.isEmpty()?List.of():subjects.size()==1?subjects:List.of();
        String expanded=query+(expand.isEmpty()?"":" "+String.join(" ",expand));
        // Appointment sources remain accessible through knownSources. Once a person's binding
        // exists, pinning their appointment above credential/social-insurance hits wastes slots.
        List<Rule> anchors=targets.stream().filter(r->Set.of("PERSON_ROLE","PERSON_COUNT","ENTITY_COUNT").contains(r.predicate())
            ||AgentRuleSubjects.selectors(r).stream().anyMatch(s->DomainBindings.role(s)&&DomainBindings.subjects(s,facts,corpus).isEmpty())).toList();
        return new Selection(expanded,targets,anchors,expand);
    }
    private AgentEvidenceQuery() {}
}
