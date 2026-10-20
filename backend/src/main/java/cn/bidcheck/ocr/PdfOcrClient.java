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

    record BatchResult(Map<Integer,OcrResult> pages, Map<Integer,String> errors, Map<String,Object> metrics) {
        public BatchResult { pages=Map.copyOf(pages);errors=Map.copyOf(errors);metrics=Map.copyOf(metrics); }
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
