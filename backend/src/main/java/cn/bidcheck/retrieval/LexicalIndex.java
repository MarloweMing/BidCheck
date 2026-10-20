package cn.bidcheck.retrieval;

import cn.bidcheck.domain.Domain.*;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.regex.Pattern;

/** BM25 with Chinese character/bigram terms and intact Latin/digit identifiers; no external tokenizer. */
final class LexicalIndex {
    static final int TOP_K = 30, CHUNK_SIZE = 1400, OVERLAP = 180, MAX_CHUNKS = 4000;
    private static final Pattern TERMS = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}]+(?:[-_.][\\p{L}\\p{N}]+)*");
    final List<Chunk> chunks;
    private final List<Map<String,Integer>> frequencies;
    private final Map<String,Integer> documentFrequencies;
    private final double averageLength;
    private final int[] lengths;

    record Chunk(Evidence evidence, String searchText, String scopeHint) { }
    record Scored(int index, double score) { }

    LexicalIndex(Document document) throws InterruptedIOException {
        if (document == null || document.pages() == null || document.pages().size() > 20_000) throw new IllegalArgumentException("DOCUMENT_LIMIT");
        List<Chunk> built = new ArrayList<>();
        long characters = 0;
        Set<Integer> locations = new HashSet<>();
        String previous = "";
        for (Page page : document.pages()) {
            interrupted();
            if (page == null || page.number() < 1 || !locations.add(page.number())) throw new IllegalArgumentException("DOCUMENT_LOCATIONS");
            String text = page.text() == null ? "" : page.text();
            characters += text.length();
            if (characters > 2_000_000) throw new IllegalArgumentException("DOCUMENT_LIMIT");
            String opening = clip(text, 260);
            boolean word = "BLOCK".equals(page.locationType()) || "PARAGRAPH".equals(page.locationType());
            // Adjacent Word heading/table-label context remains a labelled retrieval hint, never part of the quote.
            String context = word && previous.length() <= 260 ? previous : "";
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                if (end < text.length()) {
                    int newline = text.lastIndexOf('\n', end);
                    if (newline > start + CHUNK_SIZE / 2) end = newline + 1;
                    else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                }
                String quote = text.substring(start, end);
                if (!quote.isBlank()) {
                    Evidence evidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(), quote,
                            "p" + page.number() + "-c" + start, page.parser() != null && page.parser().contains("OCR") ? 0d : 1d);
                    String hint = scopeHint(quote);
                    built.add(new Chunk(evidence, "同位置开头原文线索：\n" + opening + "\n前一内容块线索：\n" + context
                            + "\n待检索原文：\n" + quote, hint));
                    if (built.size() > MAX_CHUNKS) throw new IllegalArgumentException("DOCUMENT_LIMIT");
                }
                if (end == text.length()) break;
                start = Math.max(start + 1, end - OVERLAP);
                if (Character.isLowSurrogate(text.charAt(start))) start++;
            }
            previous = text.strip();
        }
        chunks = List.copyOf(built);
        frequencies = new ArrayList<>(chunks.size());
        documentFrequencies = new HashMap<>();
        lengths = new int[chunks.size()];
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            interrupted();
            Map<String,Integer> terms = terms(chunks.get(i).searchText());
            frequencies.add(terms);
            lengths[i] = terms.values().stream().mapToInt(Integer::intValue).sum();
            total += lengths[i];
            terms.keySet().forEach(term -> documentFrequencies.merge(term, 1, Integer::sum));
        }
        averageLength = chunks.isEmpty() ? 1 : Math.max(1d, (double) total / chunks.size());
    }

    List<Scored> search(String query, int limit) throws InterruptedIOException {
        Set<String> terms = terms(query).keySet();
        List<Scored> scores = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            interrupted();
            double score = 0;
            for (String term : terms) {
                int tf = frequencies.get(i).getOrDefault(term, 0), df = documentFrequencies.getOrDefault(term, 0);
                if (tf == 0) continue;
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * lengths[i] / averageLength));
            }
            if (score > 0) scores.add(new Scored(i, score));
        }
        scores.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        return List.copyOf(scores.subList(0, Math.min(limit, scores.size())));
    }

    static Map<String,Integer> terms(String text) {
        Map<String,Integer> result = new HashMap<>();
        var matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            int[] points = token.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                for (int i = 0; i < points.length; i++) {
                    result.merge(new String(points, i, 1), 1, Integer::sum);
                    if (i + 1 < points.length) result.merge(new String(points, i, 2), 1, Integer::sum);
                }
            } else result.merge(token, 1, Integer::sum);
        }
        return result;
    }

    private static String scopeHint(String text) {
        if (text.matches("(?s).*(历史业绩|类似项目|业绩合同|已完工程).*")) return "HISTORICAL_CUE_UNVERIFIED";
        if (text.matches("(?s).*(拟派|拟在本合同|本项目任职).*")) return "CURRENT_ASSIGNMENT_CUE_UNVERIFIED";
        return "SCOPE_UNVERIFIED";
    }
    static String clip(String text, int length) {
        if (text.length() <= length) return text;
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }
    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("检索已中断。");
    }
}
