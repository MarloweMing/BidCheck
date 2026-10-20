package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Evaluation;
import java.util.Map;

/** Raw is nullable when no valid structured model judgment was obtained. */
public record SemanticAssessment(Evaluation rawEvaluation, Evaluation evaluation, Map<String, Object> audit) { }
