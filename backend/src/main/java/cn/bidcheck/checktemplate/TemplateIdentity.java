package cn.bidcheck.checktemplate;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import cn.bidcheck.compat.HexFormat;

/** Canonical, content-addressed identities for the deterministic template pipeline. */
final class TemplateIdentity {
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private TemplateIdentity() { }

    static String digest(String namespace, Object value) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("namespace", namespace, "value", value));
            return sha256(canonical);
        } catch (Exception error) {
            throw new IllegalStateException("Template identity cannot be serialized", error);
        }
    }

    static boolean sameValue(Object left, Object right) {
        return JSON.valueToTree(left).equals(JSON.valueToTree(right));
    }

    static String classFingerprint(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) return digest("missing-class-resource", type.getName());
            return sha256(cn.bidcheck.compat.Java8Api.readAllBytes(input));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot fingerprint evaluator implementation " + type.getName(), error);
        }
    }

    static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
