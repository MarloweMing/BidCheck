package cn.bidcheck.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Shared original-file bound. Decompression, rendering and OCR have separate budgets. */
public final class DocumentLimits {
    public static final int MAX_FILE_MB = 1024;
    public static final long MAX_FILE_BYTES = MAX_FILE_MB * 1024L * 1024;
    private DocumentLimits() {}

    public static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            total += length;
            if (total > limit) throw new IOException("原始文件超过 " + MAX_FILE_MB + " MB 限制。");
            output.write(buffer, 0, length);
        }
        return total;
    }
}
