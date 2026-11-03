package cn.bidcheck.ocr;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** OCR-only PDF batches; physical source page numbers are supplied by the caller. */
public interface PdfOcrClient {
    boolean enabled();
    String identity();
    String modelName();
    default int batchPages() { return 10; }
    default int concurrency() { return 5; }
    BatchResult recognizePdf(byte[] pdf, List<Integer> sourcePages) throws IOException;

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class BatchResult {
        private final Map<Integer,OcrResult> pages;
        private final Map<Integer,String> errors;
        private final Map<String,Object> metrics;
        public Map<Integer,OcrResult> pages() { return pages; }
        public Map<Integer,String> errors() { return errors; }
        public Map<String,Object> metrics() { return metrics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; BatchResult that = (BatchResult) other; return java.util.Objects.equals(this.pages, that.pages) && java.util.Objects.equals(this.errors, that.errors) && java.util.Objects.equals(this.metrics, that.metrics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.pages); hash = 31 * hash + java.util.Objects.hashCode(this.errors); hash = 31 * hash + java.util.Objects.hashCode(this.metrics); return hash; }
        @Override public String toString() { return "BatchResult[" + "pages=" + pages + ", errors=" + errors + ", metrics=" + metrics + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public BatchResult(@com.fasterxml.jackson.annotation.JsonProperty("pages") Map<Integer,OcrResult> pages, @com.fasterxml.jackson.annotation.JsonProperty("errors") Map<Integer,String> errors, @com.fasterxml.jackson.annotation.JsonProperty("metrics") Map<String,Object> metrics) { pages=cn.bidcheck.compat.Java8Api.mapCopyOf(pages);errors=cn.bidcheck.compat.Java8Api.mapCopyOf(errors);metrics=cn.bidcheck.compat.Java8Api.mapCopyOf(metrics);
            this.pages = pages; this.errors = errors; this.metrics = metrics;
        }
    }

    static PdfOcrClient disabled() { return Disabled.INSTANCE; }
    enum Disabled implements PdfOcrClient {
        INSTANCE;
        public boolean enabled() { return false; }
        public String identity() { return "DISABLED"; }
        public String modelName() { return ""; }
        public BatchResult recognizePdf(byte[] pdf,List<Integer> sourcePages)throws IOException { throw new IOException("PDF OCR 尚未启用"); }
    }
}
