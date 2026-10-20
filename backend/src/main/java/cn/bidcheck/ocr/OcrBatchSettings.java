package cn.bidcheck.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class OcrBatchSettings {
    private final OcrBatchPolicy policy;
    public OcrBatchSettings(@Value("${bidcheck.ocr.batch.enabled:true}") boolean enabled,
            @Value("${bidcheck.ocr.batch.max-pages:8}") int maxPages,
            @Value("${bidcheck.ocr.batch.max-wait-ms:300}") long maxWaitMillis,
            @Value("${bidcheck.ocr.batch.max-bytes:16777216}") long maxBytes,
            @Value("${bidcheck.ocr.batch.max-pixels:16000000}") long maxPixels,
            @Value("${bidcheck.ocr.batch.max-estimated-tokens:20000}") int maxEstimatedTokens) {
        policy=new OcrBatchPolicy(enabled,enabled?maxPages:1,enabled?maxWaitMillis:0,maxBytes,maxPixels,maxEstimatedTokens);
    }
    public OcrBatchPolicy policy(){return policy;}
    public static OcrBatchSettings defaults(){return new OcrBatchSettings(true,8,300,16777216,16000000,20000);}
}
