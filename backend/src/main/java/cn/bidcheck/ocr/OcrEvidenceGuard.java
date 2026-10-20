package cn.bidcheck.ocr;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects explicit generation commentary, not OCR accuracy. A finding requests a fresh
 * page transcription; callers must not silently delete the matching passage and accept
 * the remainder. Offsets refer to the submitted text and never contain its content.
 */
public final class OcrEvidenceGuard {
    private OcrEvidenceGuard() { }
    public record Finding(String code, int start, int end) { }
    private record Rule(String code, int window, Pattern anchor, List<Pattern> evidence) { }
    private static Pattern p(String value) { return Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static Rule rule(String code, int window, String anchor, String... evidence) {
        return new Rule(code, window, p(anchor), java.util.Arrays.stream(evidence).map(OcrEvidenceGuard::p).toList());
    }
    private static final List<Rule> RULES = List.of(
        rule("OCR_GENERATED_META_NARRATIVE", 240,
            "符合[^\\r\\n]{0,20}(?:信息|文字|数据|图像)?(?:抽取|提取|转录|识别)专家(?:角色要求|输出标准|角色设定)"),
        rule("OCR_GENERATED_META_NARRATIVE", 240,
            "此(?:格式|输出|报告|文档)[^\\r\\n]{0,16}(?:严格)?遵循[^\\r\\n]{0,16}Markdown[^\\r\\n]{0,12}(?:语法|规范|格式)"),
        rule("OCR_GENERATED_META_NARRATIVE", 320,
            "\\bthis\\s+(?:report|output|response)\\s+(?:strictly\\s+)?(?:follows|complies\\s+with)[^\\r\\n]{0,60}markdown\\s+(?:formatting|format|syntax)",
            "(?:as\\s+requested|requested\\s+(?:format|instructions))"),
        rule("OCR_GENERATED_QR_REPORT", 2400,
            "\\bQR\\s*Code\\s+(?:Analysis|Extraction)\\s+Report\\b",
            "(?:Decoded\\s+(?:Content|Data)\\s+Placeholder|Extraction\\s+Status)",
            "(?:Recommended\\s+Action|Actual\\s+payload\\s+remains\\s+inaccessible|No\\s+decoding\\s+(?:has\\s+been|was)\\s+performed)"),
        rule("OCR_GENERATED_DECODE_NARRATIVE", 500,
            "(?:当前|本)(?:运行)?环境[^\\r\\n]{0,16}(?:不支持|无法|不能)[^\\r\\n]{0,24}(?:图像|二维码|QR)[^\\r\\n]{0,16}解码",
            "(?:请|建议)[^\\r\\n]{0,50}(?:扫描|解码|提供)"),
        rule("OCR_GENERATED_DECODE_NARRATIVE", 500,
            "请提供[^\\r\\n]{0,12}已解码[^\\r\\n]{0,12}(?:原始字符串|数据|内容)",
            "我(?:将|可以|会)[^\\r\\n]{0,32}(?:转换|生成|整理|提取)"),
        rule("OCR_GENERATED_PORTRAIT_ANALYSIS", 4000,
            "人物肖像(?:信息)?(?:提取|分析|识别)报告",
            "年龄估计|根据[^\\r\\n]{0,20}(?:面部|发型|外貌)[^\\r\\n]{0,12}推断",
            "图像技术参数[^\\r\\n]{0,12}推测|(?:种族|民族)[^\\r\\n]{0,80}(?:人种|可能性|推测|估计)")
    );

    /** Empty means no known commentary signature; it does not establish literal fidelity. */
    public static String code(String text) { return inspect(text).map(Finding::code).orElse(""); }

    public static Optional<Finding> inspect(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        Finding earliest = null;
        for (Rule rule : RULES) {
            Matcher anchor = rule.anchor.matcher(text);
            while (anchor.find()) {
                int start = anchor.start(), end = anchor.end();
                int bound = (int) Math.min(text.length(), (long) start + rule.window);
                boolean matches = true;
                for (Pattern evidence : rule.evidence) {
                    Matcher supporting = evidence.matcher(text).region(start, Math.max(end, bound));
                    if (!supporting.find()) { matches = false; break; }
                    end = Math.max(end, supporting.end());
                }
                if (matches && (earliest == null || start < earliest.start())) earliest = new Finding(rule.code, start, end);
                if (earliest != null && earliest.start() <= start) break;
            }
        }
        return Optional.ofNullable(earliest);
    }
}
