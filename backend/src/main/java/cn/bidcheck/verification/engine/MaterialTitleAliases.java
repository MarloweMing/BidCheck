package cn.bidcheck.verification.engine;

import java.util.Objects;

/** Exact document identities. A cover and the volume it introduces are separate targets. */
public final class MaterialTitleAliases {
    private MaterialTitleAliases() {}

    static String title(String text) {
        String line = Objects.toString(text, "").trim().replaceFirst("^#{1,6}\\s*", "");
        return SectionCoverage.strip(line).replaceAll("[\\s\\u3000]+", "")
            .replaceFirst("^第[一二三四五六七八九十0-9]+分册", "")
            .replace('(', '（').replace(')', '）')
            .replaceFirst("(?<=索引表)（适用[^）]{1,20}）$", "");
    }

    static String canonical(String text) {
        String t = title(text);
        return t.replace("投标文件（商务部分）", "商务投标文件")
                .replace("投标文件（技术部分）", "技术投标文件")
                .replace("投标文件（报价部分）", "报价文件")
                .replace("报价投标文件", "报价文件")
                .replace("技术投标索引表", "技术评审索引表");
    }

    public static boolean matches(String expected, String candidate) {
        String left = canonical(expected), right = canonical(candidate);
        if (left.isEmpty() || right.isEmpty()) return false;
        return left.equals(right) || ("商务技术评审索引表".equals(left)
            && ("商务评审索引表".equals(right) || "技术评审索引表".equals(right)))
            || ("商务技术评审索引表".equals(right)
            && ("商务评审索引表".equals(left) || "技术评审索引表".equals(left)));
    }

    static String volume(String text) {
        String t = canonical(text).replaceFirst("封面$", "");
        return t.matches("商务投标文件|技术投标文件|报价文件") ? t : "";
    }

    static boolean structured(String text) {
        return !volume(text).isEmpty() || canonical(text).matches("(商务技术|商务|技术)评审索引表|技术建议书");
    }

    static boolean navigation(String text) {
        String t=canonical(text);
        return t.contains("索引")||t.contains("目录");
    }
}
