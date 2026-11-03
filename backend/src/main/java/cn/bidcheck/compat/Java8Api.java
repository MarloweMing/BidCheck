package cn.bidcheck.compat;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Java 8 implementations of the JDK convenience methods used by BidCheck. */
public final class Java8Api {
    private Java8Api() { }

    @SafeVarargs
    public static <T> List<T> listOf(T... values) {
        List<T> result = new ArrayList<T>(values.length);
        for (T value : values) result.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(result);
    }
    public static <T> List<T> listCopyOf(Collection<? extends T> values) {
        List<T> result = new ArrayList<T>(values.size());
        for (T value : values) result.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(result);
    }
    @SafeVarargs
    public static <T> Set<T> setOf(T... values) {
        Set<T> result = new LinkedHashSet<T>();
        for (T value : values) if (!result.add(Objects.requireNonNull(value)))
            throw new IllegalArgumentException("duplicate element: " + value);
        return Collections.unmodifiableSet(result);
    }
    public static <T> Set<T> setCopyOf(Collection<? extends T> values) {
        Set<T> result = new LinkedHashSet<T>();
        for (T value : values) result.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSet(result);
    }
    public static <K,V> Map.Entry<K,V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<K,V>(Objects.requireNonNull(key), Objects.requireNonNull(value));
    }
    @SafeVarargs
    public static <K,V> Map<K,V> mapOfEntries(Map.Entry<? extends K,? extends V>... entries) {
        Map<K,V> result = new LinkedHashMap<K,V>();
        for (Map.Entry<? extends K,? extends V> item : entries) {
            K key = Objects.requireNonNull(item.getKey());
            V value = Objects.requireNonNull(item.getValue());
            if (result.containsKey(key)) throw new IllegalArgumentException("duplicate key: " + key);
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }
    public static <K,V> Map<K,V> mapCopyOf(Map<? extends K,? extends V> values) {
        Map<K,V> result = new LinkedHashMap<K,V>();
        for (Map.Entry<? extends K,? extends V> item : values.entrySet())
            result.put(Objects.requireNonNull(item.getKey()), Objects.requireNonNull(item.getValue()));
        return Collections.unmodifiableMap(result);
    }
    public static <K,V> Map<K,V> mapOf() { return mapOfEntries(); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0) { return mapOfEntries(entry(k0, v0)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1) { return mapOfEntries(entry(k0, v0), entry(k1, v1)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3), entry(k4, v4)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3), entry(k4, v4), entry(k5, v5)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3), entry(k4, v4), entry(k5, v5), entry(k6, v6)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3), entry(k4, v4), entry(k5, v5), entry(k6, v6), entry(k7, v7)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3), entry(k4, v4), entry(k5, v5), entry(k6, v6), entry(k7, v7), entry(k8, v8)); }
    public static <K,V> Map<K,V> mapOf(K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) { return mapOfEntries(entry(k0, v0), entry(k1, v1), entry(k2, v2), entry(k3, v3), entry(k4, v4), entry(k5, v5), entry(k6, v6), entry(k7, v7), entry(k8, v8), entry(k9, v9)); }

    public static boolean isBlank(String value) { return strip(value).isEmpty(); }
    public static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end) { int cp = value.codePointAt(start); if (!Character.isWhitespace(cp)) break; start += Character.charCount(cp); }
        while (end > start) { int cp = value.codePointBefore(end); if (!Character.isWhitespace(cp)) break; end -= Character.charCount(cp); }
        return value.substring(start, end);
    }
    public static String stripLeading(String value) {
        int at=0;
        while(at<value.length()){int cp=value.codePointAt(at);if(!Character.isWhitespace(cp))break;at+=Character.charCount(cp);}
        return value.substring(at);
    }
    public static String stripTrailing(String value) {
        int at=value.length();
        while(at>0){int cp=value.codePointBefore(at);if(!Character.isWhitespace(cp))break;at-=Character.charCount(cp);}
        return value.substring(0,at);
    }
    public static String repeat(String value, int count) {
        if(count<0)throw new IllegalArgumentException("count is negative: "+count);
        if(count==0||value.isEmpty())return "";
        if(value.length()>Integer.MAX_VALUE/count)throw new OutOfMemoryError("Required length exceeds implementation limit");
        StringBuilder result=new StringBuilder(value.length()*count);
        for(int i=0;i<count;i++)result.append(value);
        return result.toString();
    }
    public static String formatted(String value, Object... arguments) { return String.format(value, arguments); }
    public static <T> T requireNonNullElse(T value, T fallback) { return value!=null?value:Objects.requireNonNull(fallback); }
    public static <T> T requireNonNullElseGet(T value, Supplier<? extends T> fallback) { return value!=null?value:Objects.requireNonNull(fallback.get()); }
    public static <T> List<T> toList(Stream<T> stream) {
        return Collections.unmodifiableList(stream.collect(Collectors.toList()));
    }
    public static String readString(Path path) throws IOException { return readString(path, StandardCharsets.UTF_8); }
    public static String readString(Path path, Charset charset) throws IOException {
        return charset.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    }
    public static Path writeString(Path path, CharSequence value, OpenOption... options) throws IOException { return writeString(path,value,StandardCharsets.UTF_8,options); }
    public static Path writeString(Path path, CharSequence value, Charset charset, OpenOption... options) throws IOException {
        java.nio.ByteBuffer encoded=charset.newEncoder().encode(java.nio.CharBuffer.wrap(value));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return Files.write(path,bytes,options);
    }
    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();transferTo(input,output);return output.toByteArray();
    }
    public static byte[] readNBytes(InputStream input, int length) throws IOException {
        if(length<0)throw new IllegalArgumentException("length is negative");
        ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(length,8192));
        byte[] buffer=new byte[Math.min(length,8192)];int remaining=length;
        while(remaining>0){int read=input.read(buffer,0,Math.min(buffer.length,remaining));if(read<0)break;if(read==0){int one=input.read();if(one<0)break;output.write(one);remaining--;}else{output.write(buffer,0,read);remaining-=read;}}
        return output.toByteArray();
    }
    public static long transferTo(InputStream input, OutputStream output) throws IOException {
        byte[] buffer=new byte[8192];long total=0;int read;
        while((read=input.read(buffer))!=-1){output.write(buffer,0,read);total+=read;}return total;
    }
    public static <T,K,V> Collector<T,?,Map<K,V>> toUnmodifiableMap(Function<? super T,? extends K> keys, Function<? super T,? extends V> values) {
        return Collectors.collectingAndThen(Collectors.toMap(item->Objects.requireNonNull(keys.apply(item)),item->Objects.requireNonNull(values.apply(item))),Collections::unmodifiableMap);
    }
    public static <T,K,V> Collector<T,?,Map<K,V>> toUnmodifiableMap(Function<? super T,? extends K> keys, Function<? super T,? extends V> values,BinaryOperator<V> merge) {
        return Collectors.collectingAndThen(Collectors.toMap(item->Objects.requireNonNull(keys.apply(item)),item->Objects.requireNonNull(values.apply(item)),merge),Collections::unmodifiableMap);
    }
}
