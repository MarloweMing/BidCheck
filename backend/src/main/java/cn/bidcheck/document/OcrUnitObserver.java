package cn.bidcheck.document;

import cn.bidcheck.ocr.OcrLayout;
import java.io.IOException;
import java.util.Optional;

/** Durable OCR hooks. All methods run on the parsing caller, never an OCR worker. */
public interface OcrUnitObserver {
    /** Raw OCR result before its frames are bound to the current source location. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Result {
        private final String text;
        private final String warning;
        private final OcrLayout layout;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("warning") String warning, @com.fasterxml.jackson.annotation.JsonProperty("layout") OcrLayout layout) { this.text = text; this.warning = warning; this.layout = layout; }
        public String text() { return text; }
        public String warning() { return warning; }
        public OcrLayout layout() { return layout; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.warning, that.warning) && java.util.Objects.equals(this.layout, that.layout); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.warning); hash = 31 * hash + java.util.Objects.hashCode(this.layout); return hash; }
        @Override public String toString() { return "Result[" + "text=" + text + ", warning=" + warning + ", layout=" + layout + "]"; }
 }

    /** Position is a physical PDF page or a Word block, distinguished by locationType. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Event {
        private final String locationType;
        private final int position;
        private final int imageIndex;
        private final String status;
        private final String imageHash;
        private final String text;
        private final OcrLayout layout;
        private final String warning;
        private final long elapsedMillis;
        private final boolean cached;
        private final int width;
        private final int height;
        private final long imageBytes;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Event(@com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("position") int position, @com.fasterxml.jackson.annotation.JsonProperty("imageIndex") int imageIndex, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("imageHash") String imageHash, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("layout") OcrLayout layout, @com.fasterxml.jackson.annotation.JsonProperty("warning") String warning, @com.fasterxml.jackson.annotation.JsonProperty("elapsedMillis") long elapsedMillis, @com.fasterxml.jackson.annotation.JsonProperty("cached") boolean cached, @com.fasterxml.jackson.annotation.JsonProperty("width") int width, @com.fasterxml.jackson.annotation.JsonProperty("height") int height, @com.fasterxml.jackson.annotation.JsonProperty("imageBytes") long imageBytes) { this.locationType = locationType; this.position = position; this.imageIndex = imageIndex; this.status = status; this.imageHash = imageHash; this.text = text; this.layout = layout; this.warning = warning; this.elapsedMillis = elapsedMillis; this.cached = cached; this.width = width; this.height = height; this.imageBytes = imageBytes; }
        public String locationType() { return locationType; }
        public int position() { return position; }
        public int imageIndex() { return imageIndex; }
        public String status() { return status; }
        public String imageHash() { return imageHash; }
        public String text() { return text; }
        public OcrLayout layout() { return layout; }
        public String warning() { return warning; }
        public long elapsedMillis() { return elapsedMillis; }
        public boolean cached() { return cached; }
        public int width() { return width; }
        public int height() { return height; }
        public long imageBytes() { return imageBytes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Event that = (Event) other; return java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.position, that.position) && java.util.Objects.equals(this.imageIndex, that.imageIndex) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.imageHash, that.imageHash) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.layout, that.layout) && java.util.Objects.equals(this.warning, that.warning) && java.util.Objects.equals(this.elapsedMillis, that.elapsedMillis) && java.util.Objects.equals(this.cached, that.cached) && java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height) && java.util.Objects.equals(this.imageBytes, that.imageBytes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.position); hash = 31 * hash + java.util.Objects.hashCode(this.imageIndex); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.imageHash); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.layout); hash = 31 * hash + java.util.Objects.hashCode(this.warning); hash = 31 * hash + java.util.Objects.hashCode(this.elapsedMillis); hash = 31 * hash + java.util.Objects.hashCode(this.cached); hash = 31 * hash + java.util.Objects.hashCode(this.width); hash = 31 * hash + java.util.Objects.hashCode(this.height); hash = 31 * hash + java.util.Objects.hashCode(this.imageBytes); return hash; }
        @Override public String toString() { return "Event[" + "locationType=" + locationType + ", position=" + position + ", imageIndex=" + imageIndex + ", status=" + status + ", imageHash=" + imageHash + ", text=" + text + ", layout=" + layout + ", warning=" + warning + ", elapsedMillis=" + elapsedMillis + ", cached=" + cached + ", width=" + width + ", height=" + height + ", imageBytes=" + imageBytes + "]"; }
 }

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
