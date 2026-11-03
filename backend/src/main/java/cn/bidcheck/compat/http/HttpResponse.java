package cn.bidcheck.compat.http;

import cn.bidcheck.compat.concurrent.Flow;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;

public final class HttpResponse<T> {
    private final int status;
    private final HttpHeaders headers;
    private final T body;

    HttpResponse(int status, HttpHeaders headers, T body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public int statusCode() { return status; }
    public HttpHeaders headers() { return headers; }
    public T body() { return body; }

    public interface ResponseInfo {
        int statusCode();
        HttpHeaders headers();
    }

    @FunctionalInterface
    public interface BodyHandler<T> {
        BodySubscriber<T> apply(ResponseInfo info);
    }

    public interface BodySubscriber<T> extends Flow.Subscriber<List<ByteBuffer>> {
        CompletionStage<T> getBody();
    }
}
