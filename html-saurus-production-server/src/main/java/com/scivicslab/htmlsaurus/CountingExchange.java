package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * An exchange that passes everything to the one it wraps, and counts the bytes of the response body and remembers the
 * status the handler sent, so the access log can say what was answered ({@code %b} and {@code %>s}).
 */
final class CountingExchange extends HttpExchange {

    private final HttpExchange inner;
    private final OutputStream counted;
    private long bytes;
    private int status = -1;

    CountingExchange(HttpExchange inner) {
        this.inner = inner;
        OutputStream real = inner.getResponseBody();
        this.counted = new OutputStream() {
            @Override public void write(int b) throws IOException { real.write(b); bytes++; }
            @Override public void write(byte[] b, int off, int len) throws IOException { real.write(b, off, len); bytes += len; }
            @Override public void flush() throws IOException { real.flush(); }
            @Override public void close() throws IOException { real.close(); }
        };
    }

    /** @return the bytes of the response body written so far */
    long bytes() { return bytes; }

    /** @return the status the handler sent, or {@code -1} when it sent none */
    int status() { return status; }

    @Override public Headers getRequestHeaders() { return inner.getRequestHeaders(); }
    @Override public Headers getResponseHeaders() { return inner.getResponseHeaders(); }
    @Override public URI getRequestURI() { return inner.getRequestURI(); }
    @Override public String getRequestMethod() { return inner.getRequestMethod(); }
    @Override public HttpContext getHttpContext() { return inner.getHttpContext(); }
    @Override public void close() { inner.close(); }
    @Override public InputStream getRequestBody() { return inner.getRequestBody(); }
    @Override public OutputStream getResponseBody() { return counted; }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        inner.sendResponseHeaders(rCode, responseLength);
        status = rCode;
    }

    @Override public InetSocketAddress getRemoteAddress() { return inner.getRemoteAddress(); }
    @Override public int getResponseCode() { return inner.getResponseCode(); }
    @Override public InetSocketAddress getLocalAddress() { return inner.getLocalAddress(); }
    @Override public String getProtocol() { return inner.getProtocol(); }
    @Override public Object getAttribute(String name) { return inner.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { inner.setAttribute(name, value); }
    @Override public void setStreams(InputStream i, OutputStream o) { inner.setStreams(i, o); }
    @Override public HttpPrincipal getPrincipal() { return inner.getPrincipal(); }
}
