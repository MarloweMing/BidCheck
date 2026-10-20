package cn.bidcheck.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared admission applies equally to individual images and multi-image requests. */
@Component
public record OcrRequestSettings(int queueCapacity, int retryAttempts, int retryBaseMs, int retryMaxDelayMs,
                                 int requestsPerMinute,int burst,int circuitFailures,int circuitOpenMillis) {
    public OcrRequestSettings(int queueCapacity,int retryAttempts,int retryBaseMs,int retryMaxDelayMs){
        this(queueCapacity,retryAttempts,retryBaseMs,retryMaxDelayMs,0,8,5,15000);
    }
    @Autowired
    public OcrRequestSettings(@Value("${bidcheck.ocr.queue-capacity:16}") int queueCapacity,
                              @Value("${bidcheck.ocr.retry-attempts:3}") int retryAttempts,
                              @Value("${bidcheck.ocr.retry-base-ms:100}") int retryBaseMs,
                              @Value("${bidcheck.ocr.retry-max-delay-ms:2000}") int retryMaxDelayMs,
                              @Value("${bidcheck.ocr.requests-per-minute:0}") int requestsPerMinute,
                              @Value("${bidcheck.ocr.request-burst:8}") int burst,
                              @Value("${bidcheck.ocr.circuit-failures:5}") int circuitFailures,
                              @Value("${bidcheck.ocr.circuit-open-ms:15000}") int circuitOpenMillis) {
        if (queueCapacity < 1 || queueCapacity > 256 || retryAttempts < 1 || retryAttempts > 3
                || retryBaseMs < 1 || retryBaseMs > 10000 || retryMaxDelayMs < retryBaseMs || retryMaxDelayMs > 60000)
            throw new IllegalArgumentException("OCR 队列或重试设置超出允许范围。");
        this.queueCapacity = queueCapacity; this.retryAttempts = retryAttempts;
        this.retryBaseMs = retryBaseMs; this.retryMaxDelayMs = retryMaxDelayMs;
        if(requestsPerMinute<0||requestsPerMinute>60000||burst<1||burst>256||circuitFailures<2||circuitFailures>100
                ||circuitOpenMillis<10||circuitOpenMillis>120000)throw new IllegalArgumentException("OCR 速率或熔断设置超出允许范围。");
        this.requestsPerMinute=requestsPerMinute;this.burst=burst;this.circuitFailures=circuitFailures;this.circuitOpenMillis=circuitOpenMillis;
    }
    public static OcrRequestSettings defaults() { return new OcrRequestSettings(16, 3, 100, 2000); }
}
