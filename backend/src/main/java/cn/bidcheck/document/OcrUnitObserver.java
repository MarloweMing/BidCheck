package cn.bidcheck.document;

import cn.bidcheck.ocr.OcrLayout;
import java.io.IOException;
import java.util.Optional;

/** Durable OCR hooks. All methods run on the parsing caller, never an OCR worker. */
public interface OcrUnitObserver {
    /** Raw OCR result before its frames are bound to the current source location. */
    record Result(String text, String warning, OcrLayout layout) { }

    /** Position is a physical PDF page or a Word block, distinguished by locationType. */
    record Event(String locationType, int position, int imageIndex, String status, String imageHash,
                 String text, OcrLayout layout, String warning, long elapsedMillis, boolean cached,
                 int width, int height, long imageBytes) { }

    default Optional<Result> restore(String locationType, int position, int imageIndex, String imageHash) throws IOException {
        return Optional.empty();
    }

    /** Failures deliberately abort parsing, including persistence and lease-fence failures. */
    default void onState(Event event) throws IOException { }

    /** Called by the parsing caller after worker metadata is drained, under the same task lease. */
    default void onBatch(cn.bidcheck.ocr.OcrBatch.Event event) throws IOException { }

    /** Initial local routing hint; no page content has been omitted or declared complete. */
    default void onClassified(String locationType, int position, PageClassifier.Decision decision) throws IOException { }

    /**
     * A complete source-bound page/block, including native-only, failed and deferred outcomes.
     * Runs on the parsing caller as results arrive, not only after the whole document finishes.
     * The decision includes any new material type learned from OCR. Callback failure aborts parsing.
     */
    default void onPage(cn.bidcheck.domain.Domain.Page page, PageClassifier.Decision decision) throws IOException { }

    static OcrUnitObserver noop() { return NoOp.INSTANCE; }
    enum NoOp implements OcrUnitObserver { INSTANCE }
}
