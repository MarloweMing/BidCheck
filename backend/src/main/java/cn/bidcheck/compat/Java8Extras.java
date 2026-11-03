package cn.bidcheck.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Java 8 counterparts of less common JDK convenience APIs. */
public final class Java8Extras {
    private Java8Extras() { }

    public static void writeBytes(ByteArrayOutputStream output, byte[] bytes) { output.write(bytes, 0, bytes.length); }
    public static String toString(ByteArrayOutputStream output, Charset charset) { return new String(output.toByteArray(), charset); }
    public static long matchCount(Matcher matcher) { long count = 0; while (matcher.find()) count++; return count; }
    public static <T> Stream<T> streamOfNullable(T value) { return value == null ? Stream.empty() : Stream.of(value); }
    public static String urlEncode(String value, Charset charset) {
        try { return java.net.URLEncoder.encode(value, charset.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalArgumentException(impossible); }
    }
    public static String urlDecode(String value, Charset charset) {
        try { return java.net.URLDecoder.decode(value, charset.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalArgumentException(impossible); }
    }

    /** String.lines splits only CR, LF, and CRLF and omits a trailing empty line. */
    public static Stream<String> lines(String value) {
        Objects.requireNonNull(value);
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<String>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE) {
            private int next;
            @Override public boolean tryAdvance(Consumer<? super String> action) {
                Objects.requireNonNull(action);
                if (next == value.length()) return false;
                int start = next;
                while (next < value.length() && value.charAt(next) != '\r' && value.charAt(next) != '\n') next++;
                int end = next;
                if (next < value.length()) {
                    char delimiter = value.charAt(next++);
                    if (delimiter == '\r' && next < value.length() && value.charAt(next) == '\n') next++;
                }
                action.accept(value.substring(start, end));
                return true;
            }
        }, false);
    }

    public static OutputStream nullOutputStream() {
        return new OutputStream() {
            private boolean closed;
            private void checkOpen() throws IOException { if (closed) throw new IOException("Stream closed"); }
            @Override public void write(int value) throws IOException { checkOpen(); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.requireNonNull(bytes);
                if ((offset | length) < 0 || offset > bytes.length || bytes.length - offset < length) throw new IndexOutOfBoundsException();
                checkOpen();
            }
            @Override public void close() { closed = true; }
        };
    }

    public static InputStream nullInputStream() {
        return new InputStream() {
            private boolean closed;
            private void checkOpen() throws IOException { if (closed) throw new IOException("Stream closed"); }
            @Override public int read() throws IOException { checkOpen(); return -1; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.requireNonNull(bytes);
                if ((offset | length) < 0 || offset > bytes.length || bytes.length - offset < length) throw new IndexOutOfBoundsException();
                checkOpen(); return length == 0 ? 0 : -1;
            }
            @Override public long skip(long count) throws IOException { checkOpen(); return 0; }
            @Override public int available() throws IOException { checkOpen(); return 0; }
            @Override public void close() { closed = true; }
        };
    }

    public static java.io.Writer nullWriter() {
        return new java.io.Writer() {
            private boolean closed;
            private void checkOpen() throws IOException { if (closed) throw new IOException("Writer closed"); }
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                Objects.requireNonNull(chars);
                if ((offset | length) < 0 || offset > chars.length || chars.length - offset < length) throw new IndexOutOfBoundsException();
                checkOpen();
            }
            @Override public void flush() throws IOException { checkOpen(); }
            @Override public void close() { closed = true; }
        };
    }
}
