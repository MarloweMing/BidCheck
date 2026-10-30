package cn.bidcheck.verification.facts;

import java.util.*;

/** Separates an observed business value from the prose explaining why it was extracted. */
public final class SemanticFactIdentity {
    public static final String VERSION="semantic-fact-2-typed-basis";
    private SemanticFactIdentity() {}

    public static Map<String,Object> value(Map<String,Object> fact) {
        Map<String,Object> result=new LinkedHashMap<>(fact);
        // MATERIAL/DECLARATION basis is an extraction explanation, not the declaration's actual text.
        // Keep rawValue/value, subject, scope, source and all business attributes. In
        // particular NUMBER/FIELD/CONTRACT basis may contain tax or pricing scope.
        if(Set.of("MATERIAL","DECLARATION").contains(Objects.toString(fact.get("kind"),"")))result.remove("basis");
        // References form a set. This is deliberately shallow: table cells, coordinates
        // and arrays inside business attributes remain ordered evidence.
        if(fact.get("sourceIds") instanceof Collection<?> refs)result.put("sourceIds",refs.stream().map(v->Objects.toString(v,"")).distinct().sorted().toList());
        return result;
    }
}
