package cn.bidcheck.checktemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses only dates that are explicitly bound to a certificate validity field.  Award dates,
 * initial-certification dates and unrelated dates on the same OCR page are deliberately ignored.
 */
final class ObjectiveCertificateEligibility {
    enum State { ELIGIBLE, INELIGIBLE, UNRESOLVED }

    record Assessment(State state, String reason, LocalDate validFrom, LocalDate validUntil) { }

    private static final String DATE_TOKEN = "\\d{4}[年./\\-]\\d{1,2}[月./\\-]\\d{1,2}日?";
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?:证书编号|注册号|许可证号)\\s*[:：]?\\s*[A-Za-z0-9-]{4,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile(
            "(?:证书)?(?:有效日期|有效期|有效期限|有效时间)\\s*[:：]?\\s*(" + DATE_TOKEN + ")"
                    + "\\s*(?:至|到|[-—~～])\\s*(" + DATE_TOKEN + ")");
    private static final Pattern VALID_FROM = Pattern.compile(
            "(?:有效期自|有效期起始日期|有效起始日期|生效日期)\\s*[:：]?\\s*(" + DATE_TOKEN + ")");
    private static final Pattern VALID_UNTIL = Pattern.compile(
            "(?:证书到期日期|到期日期|有效期至|有效至|有效截止日期|截止日期)\\s*[:：]?\\s*(" + DATE_TOKEN + ")");
    private static final Pattern ISSUED = Pattern.compile(
            "(?:证书颁发日期|颁证日期|发证日期|签发日期|换证日期)\\s*[:：]?\\s*(" + DATE_TOKEN + ")");
    private static final Pattern INVALID_STATUS = Pattern.compile(
            "(?:证书状态|状态标签|认证状态)\\s*[:：]?\\s*(?:已过期|过期|失效|无效|撤销|吊销|注销|暂停|终止)");
    private ObjectiveCertificateEligibility() { }

    static Assessment assess(String rawText, LocalDate comparisonDate) {
        String text = clean(rawText);
        if (text.isBlank() || !IDENTIFIER.matcher(text).find())
            return unresolved("CERTIFICATE_IDENTIFIER_MISSING");
        // Scoring is historical: without one proved tender deadline, neither today's date nor a
        // current status can establish whether the certificate qualified when bids closed.
        if (comparisonDate == null) return unresolved("REFERENCE_DATE_UNRESOLVED");

        LocalDate from = null, until = null;
        Matcher range = RANGE.matcher(text);
        if (range.find()) {
            from = date(range.group(1));
            until = date(range.group(2));
        }
        if (from == null) from = capturedDate(VALID_FROM, text);
        if (until == null) until = capturedDate(VALID_UNTIL, text);
        // A labelled issue/award date is a safe lower bound only when a labelled expiry is also
        // present.  “初次认证日期” is intentionally absent from this list.
        if (from == null && until != null) from = capturedDate(ISSUED, text);
        if (until == null && text.matches("(?s).*(?:有效期|有效期限)[^。；;\\n]{0,20}(?:长期|永久|无固定期限).*"))
            until = LocalDate.of(9999, 12, 31);

        if (from != null && comparisonDate.isBefore(from))
            return new Assessment(State.INELIGIBLE, "CERTIFICATE_NOT_EFFECTIVE_AT_REFERENCE_DATE", from, until);
        if (until != null && comparisonDate.isAfter(until))
            return new Assessment(State.INELIGIBLE, "CERTIFICATE_EXPIRED_AT_REFERENCE_DATE", from, until);
        if (from != null && until != null && !until.isBefore(from))
            return new Assessment(State.ELIGIBLE, "CERTIFICATE_VALID_AT_REFERENCE_DATE", from, until);

        // An undated current-status label does not establish the status at a historical deadline.
        // A labelled validity range above is authoritative for that date; otherwise keep the
        // conflict open instead of inventing a historical failure.
        if (INVALID_STATUS.matcher(text).find())
            return unresolved("CERTIFICATE_STATUS_REFERENCE_UNRESOLVED");
        // A current valid-status label and an expiry date do not prove that the certificate had
        // already taken effect at the historical deadline; a labelled lower bound is still needed.
        return unresolved("CERTIFICATE_VALIDITY_RANGE_UNRESOLVED");
    }

    private static Assessment unresolved(String reason) {
        return new Assessment(State.UNRESOLVED, reason, null, null);
    }

    private static LocalDate capturedDate(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? date(matcher.group(1)) : null;
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        Matcher matcher = Pattern.compile("(\\d{4})[年./\\-](\\d{1,2})[月./\\-](\\d{1,2})日?")
                .matcher(value);
        if (matcher.find()) try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException ignored) { return null; }
        try { return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static String clean(String value) {
        return Objects.toString(value, "").replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[*#`]", "").replaceAll("[\\s\\p{Z}]+", " ").strip();
    }
}
