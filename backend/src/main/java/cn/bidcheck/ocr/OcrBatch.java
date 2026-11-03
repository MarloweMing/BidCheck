package cn.bidcheck.ocr;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Independent images retain their identities even when transmitted in one provider request. */
public final class OcrBatch {
    private OcrBatch(){}
    public static final int PROMPT_ESTIMATED_TOKENS=512,ITEM_METADATA_ESTIMATED_TOKENS=128;
    public static int estimatedRequestTokens(List<Input> inputs){return PROMPT_ESTIMATED_TOKENS+inputs.stream().mapToInt(i->i.estimatedTokens()+ITEM_METADATA_ESTIMATED_TOKENS).sum();}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Input {
        private final String id;
        private final byte[] image;
        private final String mimeType;
        private final int width;
        private final int height;
        private final String pageType;
        private final String schema;
        public String id() { return id; }
        public byte[] image() { return image; }
        public String mimeType() { return mimeType; }
        public int width() { return width; }
        public int height() { return height; }
        public String pageType() { return pageType; }
        public String schema() { return schema; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Input that = (Input) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.image, that.image) && java.util.Objects.equals(this.mimeType, that.mimeType) && java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height) && java.util.Objects.equals(this.pageType, that.pageType) && java.util.Objects.equals(this.schema, that.schema); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.image); hash = 31 * hash + java.util.Objects.hashCode(this.mimeType); hash = 31 * hash + java.util.Objects.hashCode(this.width); hash = 31 * hash + java.util.Objects.hashCode(this.height); hash = 31 * hash + java.util.Objects.hashCode(this.pageType); hash = 31 * hash + java.util.Objects.hashCode(this.schema); return hash; }
        @Override public String toString() { return "Input[" + "id=" + id + ", image=" + image + ", mimeType=" + mimeType + ", width=" + width + ", height=" + height + ", pageType=" + pageType + ", schema=" + schema + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Input(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("image") byte[] image, @com.fasterxml.jackson.annotation.JsonProperty("mimeType") String mimeType, @com.fasterxml.jackson.annotation.JsonProperty("width") int width, @com.fasterxml.jackson.annotation.JsonProperty("height") int height, @com.fasterxml.jackson.annotation.JsonProperty("pageType") String pageType, @com.fasterxml.jackson.annotation.JsonProperty("schema") String schema) {
            Objects.requireNonNull(id);Objects.requireNonNull(image);Objects.requireNonNull(mimeType);
            Objects.requireNonNull(pageType);Objects.requireNonNull(schema);
            if(!id.matches("[A-Za-z0-9_:.-]{1,100}")||width<1||height<1||(long)width*height>16_000_000)
                throw new IllegalArgumentException("OCR 批次图像身份或尺寸无效。");

            this.id = id; this.image = image; this.mimeType = mimeType; this.width = width; this.height = height; this.pageType = pageType; this.schema = schema;
        }
        public long pixels(){return (long)width*height;}
        public int estimatedTokens(){return (int)((pixels()+1023)/1024)+2;}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Item {
        private final String id;
        private final OcrResult result;
        private final String errorCode;
        private final boolean retryable;
        public String id() { return id; }
        public OcrResult result() { return result; }
        public String errorCode() { return errorCode; }
        public boolean retryable() { return retryable; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Item that = (Item) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.result, that.result) && java.util.Objects.equals(this.errorCode, that.errorCode) && java.util.Objects.equals(this.retryable, that.retryable); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.result); hash = 31 * hash + java.util.Objects.hashCode(this.errorCode); hash = 31 * hash + java.util.Objects.hashCode(this.retryable); return hash; }
        @Override public String toString() { return "Item[" + "id=" + id + ", result=" + result + ", errorCode=" + errorCode + ", retryable=" + retryable + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Item(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("result") OcrResult result, @com.fasterxml.jackson.annotation.JsonProperty("errorCode") String errorCode, @com.fasterxml.jackson.annotation.JsonProperty("retryable") boolean retryable) {Objects.requireNonNull(id);errorCode=cn.bidcheck.compat.Java8Api.requireNonNullElse(errorCode, "");
            this.id = id; this.result = result; this.errorCode = errorCode; this.retryable = retryable;
        }
        public static Item success(String id,OcrResult result){return new Item(id,Objects.requireNonNull(result),"",false);}
        public static Item failed(String id,String code,boolean retryable){return new Item(id,null,code,retryable);}
    }
    /** Metadata only. Images, credentials and transcription never enter batch diagnostics. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Event {
        private final String id;
        private final String parentId;
        private final String status;
        private final List<String> inputIds;
        private final String model;
        private final String policy;
        private final String inputHash;
        private final long pixels;
        private final int estimatedTokens;
        private final long elapsedMillis;
        private final String errorCode;
        public String id() { return id; }
        public String parentId() { return parentId; }
        public String status() { return status; }
        public List<String> inputIds() { return inputIds; }
        public String model() { return model; }
        public String policy() { return policy; }
        public String inputHash() { return inputHash; }
        public long pixels() { return pixels; }
        public int estimatedTokens() { return estimatedTokens; }
        public long elapsedMillis() { return elapsedMillis; }
        public String errorCode() { return errorCode; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Event that = (Event) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.parentId, that.parentId) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.inputIds, that.inputIds) && java.util.Objects.equals(this.model, that.model) && java.util.Objects.equals(this.policy, that.policy) && java.util.Objects.equals(this.inputHash, that.inputHash) && java.util.Objects.equals(this.pixels, that.pixels) && java.util.Objects.equals(this.estimatedTokens, that.estimatedTokens) && java.util.Objects.equals(this.elapsedMillis, that.elapsedMillis) && java.util.Objects.equals(this.errorCode, that.errorCode); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.parentId); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.inputIds); hash = 31 * hash + java.util.Objects.hashCode(this.model); hash = 31 * hash + java.util.Objects.hashCode(this.policy); hash = 31 * hash + java.util.Objects.hashCode(this.inputHash); hash = 31 * hash + java.util.Objects.hashCode(this.pixels); hash = 31 * hash + java.util.Objects.hashCode(this.estimatedTokens); hash = 31 * hash + java.util.Objects.hashCode(this.elapsedMillis); hash = 31 * hash + java.util.Objects.hashCode(this.errorCode); return hash; }
        @Override public String toString() { return "Event[" + "id=" + id + ", parentId=" + parentId + ", status=" + status + ", inputIds=" + inputIds + ", model=" + model + ", policy=" + policy + ", inputHash=" + inputHash + ", pixels=" + pixels + ", estimatedTokens=" + estimatedTokens + ", elapsedMillis=" + elapsedMillis + ", errorCode=" + errorCode + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Event(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("parentId") String parentId, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("inputIds") List<String> inputIds, @com.fasterxml.jackson.annotation.JsonProperty("model") String model, @com.fasterxml.jackson.annotation.JsonProperty("policy") String policy, @com.fasterxml.jackson.annotation.JsonProperty("inputHash") String inputHash, @com.fasterxml.jackson.annotation.JsonProperty("pixels") long pixels, @com.fasterxml.jackson.annotation.JsonProperty("estimatedTokens") int estimatedTokens, @com.fasterxml.jackson.annotation.JsonProperty("elapsedMillis") long elapsedMillis, @com.fasterxml.jackson.annotation.JsonProperty("errorCode") String errorCode) {inputIds=cn.bidcheck.compat.Java8Api.listCopyOf(inputIds);
            this.id = id; this.parentId = parentId; this.status = status; this.inputIds = inputIds; this.model = model; this.policy = policy; this.inputHash = inputHash; this.pixels = pixels; this.estimatedTokens = estimatedTokens; this.elapsedMillis = elapsedMillis; this.errorCode = errorCode;
        }
    }
    @FunctionalInterface public interface Listener {
        void accept(Item item)throws IOException;
        default void onBatch(Event event)throws IOException{}
    }
}
