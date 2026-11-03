package cn.bidcheck.compat.http;

import java.io.IOException;

public class HttpTimeoutException extends IOException {
    private static final long serialVersionUID = 1L;

    public HttpTimeoutException(String message) { super(message); }
}
