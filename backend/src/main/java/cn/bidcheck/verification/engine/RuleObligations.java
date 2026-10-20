package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A business predicate is an obligation even when the model attaches supporting children to it. */
final class RuleObligations {
    static boolean hasOwnPredicate(Rule rule){return !rule.children().isEmpty()&&!Set.of("","GROUP","SCORE_COUNT","UNSUPPORTED").contains(rule.predicate());}
    static Rule own(Rule rule){return new Rule(rule.id()+"::own",rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),"",List.of());}
}
