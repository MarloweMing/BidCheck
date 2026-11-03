package cn.bidcheck.compat.http;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable Java 8 request for the application HTTP adapters. */
public final class HttpRequest {
    private final URI uri;
    private final Duration timeout;
    private final String method;
    private final HttpHeaders headers;
    private final byte[] body;

    private HttpRequest(Builder builder) {
        uri = Objects.requireNonNull(builder.uri, "uri");
        timeout = builder.timeout;
        method = builder.method;
        headers = new HttpHeaders(builder.headers);
        // The publisher already owns a private copy; share it without duplicating large PDF uploads.
        body = builder.body.bytes;
    }

    public static Builder newBuilder(URI uri) { return new Builder().uri(uri); }
    public static Builder newBuilder() { return new Builder(); }
    public URI uri() { return uri; }
    public Optional<Duration> timeout() { return Optional.ofNullable(timeout); }
    public String method() { return method; }
    public HttpHeaders headers() { return headers; }
    byte[] body() { return body; }

    public static final class Builder {
        private URI uri;
        private Duration timeout;
        private String method = "GET";
        private BodyPublisher body = BodyPublishers.noBody();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();

        public Builder uri(URI value) {
            Objects.requireNonNull(value, "uri");
            if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme())) || value.getHost() == null) {
                throw new IllegalArgumentException("HTTP URI required");
            }
            uri = value;
            return this;
        }

        public Builder timeout(Duration value) {
            if (Objects.requireNonNull(value, "timeout").isNegative() || value.isZero()) {
                throw new IllegalArgumentException("Positive timeout required");
            }
            timeout = value;
            return this;
        }

        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid HTTP header");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder POST(BodyPublisher value) { return method("POST", value); }
        public Builder GET() { return method("GET", BodyPublishers.noBody()); }
        public Builder method(String value, BodyPublisher publisher) {
            if (!Objects.requireNonNull(value, "method").matches("[A-Za-z]+")) throw new IllegalArgumentException("Invalid HTTP method");
            method = value;
            body = Objects.requireNonNull(publisher, "body");
            return this;
        }
        public HttpRequest build() { return new HttpRequest(this); }
    }

    public static final class BodyPublisher {
        private final byte[] bytes;
        private BodyPublisher(byte[] bytes) { this.bytes = Objects.requireNonNull(bytes, "body").clone(); }
    }

    public static final class BodyPublishers {
        private BodyPublishers() { }
        public static BodyPublisher noBody() { return new BodyPublisher(new byte[0]); }
        public static BodyPublisher ofByteArray(byte[] value) { return new BodyPublisher(value); }
        public static BodyPublisher ofString(String value) { return ofString(value, StandardCharsets.UTF_8); }
        public static BodyPublisher ofString(String value, Charset charset) { return ofByteArray(value.getBytes(charset)); }
    }
}
