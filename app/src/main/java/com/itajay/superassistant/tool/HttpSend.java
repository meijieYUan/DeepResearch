package com.itajay.superassistant.tool;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A request that is guaranteed to come back within its deadline.
 *
 * <p>{@link HttpRequest#timeout} is meant to do exactly that, but in a real run it did not:
 * a PDF host accepted the connection and then sent nothing, and the blocking
 * {@link HttpClient#send} sat there for over half an hour on a request whose timeout was
 * two minutes — the whole research run stopped behind it. Here the exchange runs as a
 * future and is abandoned when the clock runs out, so no single host can stall a run.</p>
 *
 * <p>Both timeouts are kept: the request's own lets the client abort cleanly where it can,
 * and the future's is the one that is relied on.</p>
 */
final class HttpSend {

    private HttpSend() {
    }

    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> handler, Duration deadline)
            throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<HttpResponse<T>> pending = client.sendAsync(request, handler);
        try {
            return pending.get(Math.max(1, deadline.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TimeoutException("no complete response within " + deadline.toMillis() + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause == null ? "request failed" : String.valueOf(cause), cause);
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }
}
