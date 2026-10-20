package cn.bidcheck.ocr;

import java.io.IOException;

/** Safe, machine-readable failure. Never retains a provider body, request, key or unsafe cause. */
public class OcrFailure extends IOException {
    private final String code;
    private final Integer httpStatus;
    private final boolean temporary;

    protected OcrFailure(String message, String code, Integer httpStatus, boolean temporary) {
        super(message); this.code = code; this.httpStatus = httpStatus; this.temporary = temporary;
    }
    public String code() { return code; }
    public Integer httpStatus() { return httpStatus; }
    /** Temporary failure whose retry budget was consumed, or bounded admission was refused. */
    public boolean temporary() { return temporary; }
    static OcrFailure of(String code, boolean temporary) {
        return new OcrFailure("OCR 识别未完成（" + code + "），请稍后重试或核对原图。", code, null, temporary);
    }
    static OcrFailure http(int status) {
        return new OcrFailure("OCR 服务返回 HTTP " + status + "，本次识别未完成。", "HTTP_" + status, status,
                status == 429 || status == 408 || status == 502 || status == 503 || status == 504);
    }
}
