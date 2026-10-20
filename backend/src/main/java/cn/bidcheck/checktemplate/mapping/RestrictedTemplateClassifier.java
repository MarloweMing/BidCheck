package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Optional, restricted extension point for unresolved fragments.
 *
 * <p>The implementation may only select an ID supplied in {@code allowedTemplateIds}; its result
 * is still a draft and never becomes executable without normal source binding and confirmation.</p>
 */
@FunctionalInterface
public interface RestrictedTemplateClassifier {
    Optional<Suggestion> classify(Request request);

    static RestrictedTemplateClassifier disabled() { return request -> Optional.empty(); }

    record Request(
            String fragmentId,
            String text,
            SourceContext sourceContext,
            SourceRef sourceRef,
            Set<String> allowedTemplateIds) {

        public Request {
            if (fragmentId == null || fragmentId.isBlank()) throw new IllegalArgumentException("fragmentId is required");
            text = text == null ? "" : text.trim();
            if (sourceContext == null) throw new IllegalArgumentException("sourceContext is required");
            if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
            allowedTemplateIds = allowedTemplateIds == null ? Set.of() : Set.copyOf(allowedTemplateIds);
        }
    }

    record Suggestion(
            String templateId,
            String templateVersion,
            double confidence,
            Map<String, Object> parameters,
            List<String> unresolvedFragments,
            String explanation) {

        public Suggestion {
            if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("templateId is required");
            templateId = templateId.trim();
            templateVersion = templateVersion == null || templateVersion.isBlank() ? "1.0" : templateVersion.trim();
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            unresolvedFragments = unresolvedFragments == null ? List.of() : unresolvedFragments.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
            explanation = explanation == null ? "" : explanation.trim();
        }
    }
}
