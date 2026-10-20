package cn.bidcheck.ocr;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Independent images retain their identities even when transmitted in one provider request. */
public final class OcrBatch {
    private OcrBatch(){}
    public static final int PROMPT_ESTIMATED_TOKENS=512,ITEM_METADATA_ESTIMATED_TOKENS=128;
    public static int estimatedRequestTokens(List<Input> inputs){return PROMPT_ESTIMATED_TOKENS+inputs.stream().mapToInt(i->i.estimatedTokens()+ITEM_METADATA_ESTIMATED_TOKENS).sum();}
    public record Input(String id,byte[] image,String mimeType,int width,int height,String pageType,String schema) {
        public Input {
            Objects.requireNonNull(id);Objects.requireNonNull(image);Objects.requireNonNull(mimeType);
            Objects.requireNonNull(pageType);Objects.requireNonNull(schema);
            if(!id.matches("[A-Za-z0-9_:.-]{1,100}")||width<1||height<1||(long)width*height>16_000_000)
                throw new IllegalArgumentException("OCR 批次图像身份或尺寸无效。");
        }
        public long pixels(){return (long)width*height;}
        public int estimatedTokens(){return (int)((pixels()+1023)/1024)+2;}
    }
    public record Item(String id,OcrResult result,String errorCode,boolean retryable) {
        public Item {Objects.requireNonNull(id);errorCode=Objects.requireNonNullElse(errorCode,"");}
        public static Item success(String id,OcrResult result){return new Item(id,Objects.requireNonNull(result),"",false);}
        public static Item failed(String id,String code,boolean retryable){return new Item(id,null,code,retryable);}
    }
    /** Metadata only. Images, credentials and transcription never enter batch diagnostics. */
    public record Event(String id,String parentId,String status,List<String> inputIds,String model,String policy,
                        String inputHash,long pixels,int estimatedTokens,long elapsedMillis,String errorCode) {
        public Event {inputIds=List.copyOf(inputIds);}
    }
    @FunctionalInterface public interface Listener {
        void accept(Item item)throws IOException;
        default void onBatch(Event event)throws IOException{}
    }
}
