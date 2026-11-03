package cn.bidcheck.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared admission applies equally to individual images and multi-image requests. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    @Component
public final class OcrRequestSettings {
        private final int queueCapacity;
        private final int retryAttempts;
        private final int retryBaseMs;
        private final int retryMaxDelayMs;
        private final int requestsPerMinute;
        private final int burst;
        private final int circuitFailures;
        private final int circuitOpenMillis;
        public int queueCapacity() { return queueCapacity; }
        public int retryAttempts() { return retryAttempts; }
        public int retryBaseMs() { return retryBaseMs; }
        public int retryMaxDelayMs() { return retryMaxDelayMs; }
        public int requestsPerMinute() { return requestsPerMinute; }
        public int burst() { return burst; }
        public int circuitFailures() { return circuitFailures; }
        public int circuitOpenMillis() { return circuitOpenMillis; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; OcrRequestSettings that = (OcrRequestSettings) other; return java.util.Objects.equals(this.queueCapacity, that.queueCapacity) && java.util.Objects.equals(this.retryAttempts, that.retryAttempts) && java.util.Objects.equals(this.retryBaseMs, that.retryBaseMs) && java.util.Objects.equals(this.retryMaxDelayMs, that.retryMaxDelayMs) && java.util.Objects.equals(this.requestsPerMinute, that.requestsPerMinute) && java.util.Objects.equals(this.burst, that.burst) && java.util.Objects.equals(this.circuitFailures, that.circuitFailures) && java.util.Objects.equals(this.circuitOpenMillis, that.circuitOpenMillis); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.queueCapacity); hash = 31 * hash + java.util.Objects.hashCode(this.retryAttempts); hash = 31 * hash + java.util.Objects.hashCode(this.retryBaseMs); hash = 31 * hash + java.util.Objects.hashCode(this.retryMaxDelayMs); hash = 31 * hash + java.util.Objects.hashCode(this.requestsPerMinute); hash = 31 * hash + java.util.Objects.hashCode(this.burst); hash = 31 * hash + java.util.Objects.hashCode(this.circuitFailures); hash = 31 * hash + java.util.Objects.hashCode(this.circuitOpenMillis); return hash; }
        @Override public String toString() { return "OcrRequestSettings[" + "queueCapacity=" + queueCapacity + ", retryAttempts=" + retryAttempts + ", retryBaseMs=" + retryBaseMs + ", retryMaxDelayMs=" + retryMaxDelayMs + ", requestsPerMinute=" + requestsPerMinute + ", burst=" + burst + ", circuitFailures=" + circuitFailures + ", circuitOpenMillis=" + circuitOpenMillis + "]"; }

    public OcrRequestSettings(int queueCapacity,int retryAttempts,int retryBaseMs,int retryMaxDelayMs){
        this(queueCapacity,retryAttempts,retryBaseMs,retryMaxDelayMs,0,8,5,15000);
    }
    @com.fasterxml.jackson.annotation.JsonCreator
        @Autowired
    public OcrRequestSettings(@com.fasterxml.jackson.annotation.JsonProperty("queueCapacity") @Value("${bidcheck.ocr.queue-capacity:16}") int queueCapacity,
                              @com.fasterxml.jackson.annotation.JsonProperty("retryAttempts") @Value("${bidcheck.ocr.retry-attempts:3}") int retryAttempts,
                              @com.fasterxml.jackson.annotation.JsonProperty("retryBaseMs") @Value("${bidcheck.ocr.retry-base-ms:100}") int retryBaseMs,
                              @com.fasterxml.jackson.annotation.JsonProperty("retryMaxDelayMs") @Value("${bidcheck.ocr.retry-max-delay-ms:2000}") int retryMaxDelayMs,
                              @com.fasterxml.jackson.annotation.JsonProperty("requestsPerMinute") @Value("${bidcheck.ocr.requests-per-minute:0}") int requestsPerMinute,
                              @com.fasterxml.jackson.annotation.JsonProperty("burst") @Value("${bidcheck.ocr.request-burst:8}") int burst,
                              @com.fasterxml.jackson.annotation.JsonProperty("circuitFailures") @Value("${bidcheck.ocr.circuit-failures:5}") int circuitFailures,
                              @com.fasterxml.jackson.annotation.JsonProperty("circuitOpenMillis") @Value("${bidcheck.ocr.circuit-open-ms:15000}") int circuitOpenMillis) {
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
