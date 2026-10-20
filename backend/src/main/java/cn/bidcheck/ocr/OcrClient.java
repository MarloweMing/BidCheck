package cn.bidcheck.ocr;

import java.io.IOException;

public interface OcrClient {
    boolean enabled();
    String modelName();
    String identity();
    OcrResult recognize(byte[] image, String mimeType) throws IOException;
    /** PDF packet recovery needs transcription first; other image paths keep this client's behavior. */
    default OcrClient pdfRecoveryClient() { return this; }
    default OcrBatchPolicy batchPolicy(){return OcrBatchPolicy.single();}
    default void recognizeBatch(java.util.List<OcrBatch.Input> inputs,OcrBatch.Listener listener)throws IOException {
        for(OcrBatch.Input input:inputs) {
            OcrBatch.Item item;
            try {item=OcrBatch.Item.success(input.id(),recognize(input.image(),input.mimeType()));}
            catch(java.io.InterruptedIOException stopped){throw stopped;}
            catch(IOException failed){item=OcrBatch.Item.failed(input.id(),"OCR_UNIT_FAILED",true);}
            listener.accept(item);
        }
    }

    static OcrClient disabled() { return Disabled.INSTANCE; }

    enum Disabled implements OcrClient {
        INSTANCE;
        public boolean enabled() { return false; }
        public String modelName() { return ""; }
        public String identity() { return "DISABLED"; }
        public OcrResult recognize(byte[] image, String mimeType) throws IOException { throw new IOException("OCR 尚未启用，请人工核对图片原件"); }
    }
}
