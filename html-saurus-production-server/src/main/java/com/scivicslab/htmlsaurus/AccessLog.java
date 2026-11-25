package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * The access log of production mode: one line per request in Apache's {@code combined} format ({@link AccessLogFormat}),
 * written to a file or to standard output by {@link AccessLogWriter}, which this runs as an actor so the lines of
 * concurrent requests never interleave.
 *
 * <p>{@link #filter()} is the one place a request is logged from. {@link SearchServer} adds it to every context it
 * creates, so a page, a redirect, a 404, a search and a failed request all reach it the same way.
 */
public final class AccessLog implements AutoCloseable {

    private final ActorSystem system = new ActorSystem("access-log-system");
    private final ActorRef<AccessLogWriter> writer;
    private final TrustedProxies trusted;
    private final boolean responseTime;
    private final Clock clock;

    AccessLog(AccessLogWriter writer, TrustedProxies trusted, boolean responseTime, Clock clock) {
        this.writer = system.actorOf("access-log", writer);
        this.trusted = trusted;
        this.responseTime = responseTime;
        this.clock = clock;
    }

    /** Opens the log the configuration asks for. */
    public static AccessLog open(AccessLogConfig config) {
        AccessLogWriter w = config.toStdout() ? new AccessLogWriter((OutputStream) System.out)
                : new AccessLogWriter(config.file(), System::nanoTime);
        return new AccessLog(w, config.trustedProxies(), config.responseTime(), Clock.systemDefaultZone());
    }

    /** @return the filter that logs every exchange that goes through it, once, when its handler has finished or failed */
    Filter filter() {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                long start = System.nanoTime();
                ZonedDateTime received = ZonedDateTime.now(clock);
                CountingExchange counting = new CountingExchange(exchange);
                boolean completed = false;
                try {
                    chain.doFilter(counting);
                    completed = true;
                } finally {
                    // A handler that threw before it answered has been given no status: the server closes the connection
                    // without one, and the log says 500. One that returned without answering is 000, as Apache writes it.
                    int status = counting.status() >= 0 ? counting.status() : completed ? 0 : 500;
                    log(exchange, status, counting.bytes(), received, System.nanoTime() - start);
                }
            }

            @Override
            public String description() {
                return "access log";
            }
        };
    }

    private void log(HttpExchange exchange, int status, long bytes, ZonedDateTime received, long nanos) {
        String host = ClientAddress.of(exchange.getRemoteAddress().getAddress(),
                exchange.getRequestHeaders().getFirst("X-Forwarded-For"), trusted);
        String requestLine = exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + exchange.getProtocol();
        String line = AccessLogFormat.line(host, requestLine, status, bytes,
                exchange.getRequestHeaders().getFirst("Referer"), exchange.getRequestHeaders().getFirst("User-Agent"),
                received, responseTime ? nanos / 1000 : null);
        record(line);
    }

    /** Queues one finished line for the writer. */
    void record(String line) {
        writer.tell(w -> w.append(line));
    }

    /** Writes what is queued, closes the file and stops the writer. A second call does nothing. */
    @Override
    public void close() {
        // Closing twice is allowed (a test closes it, and the shutdown hook closes it again); the second finds no actor.
        if (!writer.isAlive()) return;
        try {
            writer.ask(w -> { w.close(); return null; }).orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join();
        } catch (RuntimeException e) {
            System.err.println("html-saurus access log: could not close the log file: " + e);
        }
        system.terminate();
    }
}
