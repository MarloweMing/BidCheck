package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.ParsedDocument;
import java.util.Map;

/** Parsed evidence and request-scoped extraction diagnostics; Domain wire records remain unchanged. */
public record ExtractionResult(ParsedDocument document,Map<String,Object> report) {
    public ExtractionResult { report=Map.copyOf(report); }
}
