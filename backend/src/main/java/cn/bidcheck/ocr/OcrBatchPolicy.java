package cn.bidcheck.ocr;

/** Limits apply to one request; caller memory and shared provider capacity remain separate bounds. */
public record OcrBatchPolicy(boolean enabled,int maxPages,long maxWaitMillis,long maxBytes,long maxPixels,int maxEstimatedTokens) {
    public OcrBatchPolicy {
        if(maxPages<1||maxPages>16||maxWaitMillis<0||maxWaitMillis>2000||maxBytes<1024||maxBytes>64L*1024*1024
                ||maxPixels<100_000||maxPixels>64_000_000||maxEstimatedTokens<512||maxEstimatedTokens>64000)
            throw new IllegalArgumentException("OCR 批次预算超出允许范围。");
    }
    public static OcrBatchPolicy single(){return new OcrBatchPolicy(false,1,0,6L*1024*1024,16_000_000,20000);}
    public static OcrBatchPolicy defaults(){return new OcrBatchPolicy(true,8,300,16L*1024*1024,16_000_000,20000);}
}
