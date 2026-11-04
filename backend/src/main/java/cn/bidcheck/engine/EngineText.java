package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

final class EngineText {
    static final String NUMBER = "([0-9]+(?:[.,][0-9]+)*|[零〇一二两三四五六七八九十百千万亿]+)";
    static final String TIME_UNIT = "(个?日历天|个?工作日|天|日|个?月|年)";
    static final String VIN_PATTERN = "(?i)(?:VIN|车架号|车架号码|车辆识别代号|车辆识别代码)\\s*[:：=]?\\s*([A-HJ-NPR-Z0-9]{8,17})(?![A-Z0-9])";
    static final String ENGINE_PATTERN = "(?i)(?:发动机号|发动机号码)\\s*[:：=]?\\s*([A-Z0-9-]{4,24})";
    static final String PLATE_PATTERN = "(?:车牌号(?:码)?|号牌号码|车牌)\\s*[:：=]?\\s*([\\u4e00-\\u9fff][A-Za-z][A-Za-z0-9·・]{5,7})";
    static final Pattern DATE = Pattern.compile("(20\\d{2})\\s*[年/.-]\\s*(\\d{1,2})\\s*[月/.-]\\s*(\\d{1,2})(?!\\d)\\s*日?");
    static final List<String> MATERIALS = cn.bidcheck.compat.Java8Api.listOf("营业执照", "授权委托书", "法定代表人身份证明", "财务审计报告", "财务报告", "纳税证明", "社保证明", "资质证书", "业绩合同", "投标保证金", "投标函", "车辆行驶证");
    private EngineText() {}

    static String clean(String text) { return text == null ? "" : text.replace('\u00a0', ' ').replace('：', ':').trim(); }
    static String normalized(String text) { return clean(text).replaceAll("[\\s·・]", "").toUpperCase(Locale.ROOT); }
    // Quotes must retain original punctuation; normalize extracted values separately.
    static List<String> lines(Page page) { return cn.bidcheck.compat.Java8Api.toList(Arrays.stream((page.text()==null?"":page.text()).split("\\R")).map(String::trim).filter(s -> !cn.bidcheck.compat.Java8Api.isBlank(s))); }
    static Evidence evidence(Document doc, Page page, int line, String quote) {
        return new Evidence(doc.id(), doc.name(), page.number(), page.locationType(), quote, "p"+page.number()+"-l"+line,
                page.parser() != null && page.parser().contains("OCR") ? 0d : 1.0d);
    }
    static Optional<String> match(String regex, String text) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }
    static BigDecimal number(String text) {
        String value = text.replace(",", "").trim();
        if (value.matches("[0-9]+(?:\\.[0-9]+)?")) return new BigDecimal(value);
        long total=0, section=0, digit=0;
        String digits="零一二三四五六七八九";
        for (char c : value.toCharArray()) {
            int d=digits.indexOf(c);
            if (d>=0) { digit=d; continue; }
            if (c=='两') { digit=2; continue; }
            if (c=='〇') { digit=0; continue; }
            long unit;
switch (c) {case '十':unit = 10; break;case '百':unit = 100; break;case '千':unit = 1000; break;case '万':unit = 10000; break;case '亿':unit = 100000000; break;default:throw new IllegalArgumentException("无法识别数值");}
            if(unit<10000) { section+=(digit==0 ? 1 : digit)*unit; digit=0; }
            else { section+=digit; total+=(section==0 ? 1 : section)*unit; section=0; digit=0; }
        }
        return BigDecimal.valueOf(total+section+digit);
    }
    static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    static String amount(String value, String unit) {
        BigDecimal scale=unit!=null && unit.contains("亿") ? new BigDecimal("100000000") : unit!=null && unit.contains("万") ? new BigDecimal("10000") : BigDecimal.ONE;
        return decimal(number(value).multiply(scale));
    }
    static String time(String value, String unit, String field) {
        BigDecimal n=number(value);
        if(unit.contains("工作日")) return "WORKDAY:"+decimal(n);
        // Warranty uses calendar months. Day-based requirements never approximate months or years.
        if ("warranty".equals(field)) {
            if (unit.contains("年")) n=n.multiply(BigDecimal.valueOf(12));
            else if (!unit.contains("月")) return "DAY:"+decimal(n);
        } else if (unit.contains("年") || unit.contains("月")) return (unit.contains("年")?"YEAR:":"MONTH:")+decimal(n);
        return decimal(n);
    }
    static List<LocalDate> dates(String text) {
        List<LocalDate> result=new ArrayList<>(); Matcher m=DATE.matcher(text);
        while(m.find()) try { result.add(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))); } catch(RuntimeException ignored) {}
        return result;
    }
    static boolean incomplete(Document document) {
        if (deferred(document)) return true;
        String warnings=String.join(" ", cn.bidcheck.compat.Java8Api.toList((document.warnings()==null ? cn.bidcheck.compat.Java8Api.<String>listOf() : document.warnings()).stream().filter(EngineText::needsCompletenessCheck)));
        if (warnings.matches("(?is).*(OCR|扫描|未识别|未解析|无法解析|解析失败|不完整|图片未|图像未|无可提取|无可复制|未提取).*")) return true;
        return document.pages()==null || document.pages().isEmpty() || document.pages().stream().anyMatch(p -> p.text()==null || cn.bidcheck.compat.Java8Api.isBlank(p.text()) || (p.warning()!=null && needsCompletenessCheck(p.warning()) && p.warning().matches("(?is).*(OCR|扫描|未识别|未解析|无法|失败|无文本|图片).*")));
    }
    private static boolean needsCompletenessCheck(String warning) {
        return warning==null || !warning.contains("[OCR已识别]") || ocrIncomplete(warning);
    }
    static boolean ocrDeferred(String warning) { return warning != null && warning.contains("[OCR_DEFERRED]"); }
    static boolean ocrIncomplete(String warning) { return ocrDeferred(warning) || warning != null && warning.contains("[OCR未完成]"); }
    static boolean ocrFailed(String warning) {
        if (warning == null) return false;
        // A block can contain both a postponed image and a genuinely unfinished image.
        return warning.replaceAll("\\[OCR_DEFERRED\\]\\s*\\[OCR未完成\\]", "").contains("[OCR未完成]");
    }
    static boolean deferred(Document document) {
        return document != null && ((document.warnings()!=null && document.warnings().stream().anyMatch(EngineText::ocrDeferred))
            || document.pages()!=null && document.pages().stream().anyMatch(page -> ocrDeferred(page.warning())));
    }
    static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
}
