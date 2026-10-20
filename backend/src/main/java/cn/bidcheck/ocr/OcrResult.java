package cn.bidcheck.ocr;

public record OcrResult(String text, String provider, String model, boolean cached, OcrLayout layout) {
    public OcrResult(String text, String provider, String model, boolean cached) { this(text, provider, model, cached, null); }
}
