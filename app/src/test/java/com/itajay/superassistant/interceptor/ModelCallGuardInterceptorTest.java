package com.itajay.superassistant.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ModelCallGuardInterceptor}.
 *
 * <p>The streaming tests are the ones that matter. The framework's streaming base handler
 * only builds a Flux and returns it, so the HTTP request — and any DNS or transport
 * failure — happens at subscription time, past both the framework's try/catch and this
 * interceptor's. A run that failed that way used to die with the raw Netty message
 * ("Failed to resolve 'api.deepseek.com', couldn't setup transport") instead of retrying.</p>
 */
class ModelCallGuardInterceptorTest {

    /** The failure a real run died of, verbatim. */
    private static final String DNS_MESSAGE =
            "Failed to resolve 'api.deepseek.com', couldn't setup transport: [id: 0xbb2ce4c0]";

    private final ModelRequest request = ModelRequest.builder().build();

    /** Retry delays are the only thing here that must not slow the tests down. */
    private ModelCallGuardInterceptor guard() {
        return new ModelCallGuardInterceptor(3, 1, 5, 2.0);
    }

    /** The sub-agent configuration: retries, then lets the failure surface. */
    private ModelCallGuardInterceptor retryOnlyGuard() {
        return new ModelCallGuardInterceptor(3, 1, 5, 2.0, false);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static List<String> textsOf(ModelResponse modelResponse) {
        Flux<ChatResponse> stream = ((Flux<?>) modelResponse.getMessage()).cast(ChatResponse.class);
        return stream.collectList().block().stream()
                .map(response -> response.getResult().getOutput().getText())
                .toList();
    }

    @Test
    void retriesAStreamingTransportFailureAndKeepsTheRunAlive() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> calls.incrementAndGet() == 1
                ? ModelResponse.of(Flux.error(new IOException(DNS_MESSAGE)))
                : ModelResponse.of(Flux.just(response("recovered")));

        ModelResponse guarded = guard().interceptModel(request, handler);

        assertThat(textsOf(guarded)).containsExactly("recovered");
        assertThat(calls).hasValue(2);
    }

    @Test
    void reissuesTheModelCallOnEachRetryInsteadOfResubscribingTheStream() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger subscriptions = new AtomicInteger();
        // Spring AI builds its stream against the subscription it was created for: a second
        // subscription of that same stream dies with "No StreamAdvisors available to execute".
        // Retrying by re-subscribing would therefore turn a transient blip into a hard failure.
        Flux<ChatResponse> firstStream = Flux.defer(() -> {
            if (subscriptions.incrementAndGet() > 1) {
                return Flux.error(new IllegalStateException("No StreamAdvisors available to execute"));
            }
            return Flux.error(new IOException(DNS_MESSAGE));
        });
        ModelCallHandler handler = r -> calls.incrementAndGet() == 1
                ? ModelResponse.of(firstStream)
                : ModelResponse.of(Flux.just(response("recovered")));

        ModelResponse guarded = guard().interceptModel(request, handler);

        assertThat(textsOf(guarded)).containsExactly("recovered");
        assertThat(subscriptions).hasValue(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void answersWithTheFallbackWhenEveryStreamingAttemptFails() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> {
            calls.incrementAndGet();
            return ModelResponse.of(Flux.error(new IOException(DNS_MESSAGE)));
        };

        ModelResponse guarded = guard().interceptModel(request, handler);

        // Subscribe once: every subscription of the guarded stream runs its own attempts.
        List<String> texts = textsOf(guarded);
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0)).contains("模型服务当前不可用");
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryAFailureThatArrivedAfterTheFirstChunk() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> {
            calls.incrementAndGet();
            // Half the answer already reached the client: retrying would re-send it.
            return ModelResponse.of(
                    Flux.concat(Flux.just(response("partial")), Flux.error(new IOException(DNS_MESSAGE))));
        };

        ModelResponse guarded = guard().interceptModel(request, handler);

        assertThatThrownBy(() -> textsOf(guarded)).hasMessageContaining("Failed to resolve");
        assertThat(calls).hasValue(1);
    }

    @Test
    void passesASuccessfulStreamThroughUntouched() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> {
            calls.incrementAndGet();
            return ModelResponse.of(Flux.just(response("hello")));
        };

        assertThat(textsOf(guard().interceptModel(request, handler))).containsExactly("hello");
        assertThat(calls).hasValue(1);
    }

    @Test
    void retriesSynchronousTransientFailuresOnTheNonStreamingPath() {
        AtomicInteger attempts = new AtomicInteger();
        ModelCallHandler handler = r -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("request failed", new UnknownHostException("api.deepseek.com"));
            }
            return ModelResponse.of(new AssistantMessage("ok"));
        };

        ModelResponse guarded = guard().interceptModel(request, handler);

        assertThat(((AssistantMessage) guarded.getMessage()).getText()).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void answersWithTheFallbackWhenSynchronousRetriesAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        ModelCallHandler handler = r -> {
            attempts.incrementAndGet();
            throw new RuntimeException("request failed", new UnknownHostException("api.deepseek.com"));
        };

        ModelResponse guarded = guard().interceptModel(request, handler);

        assertThat(((AssistantMessage) guarded.getMessage()).getText()).contains("模型服务当前不可用");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void aRetryOnlyGuardStillRecoversFromATransientStreamingFailure() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> calls.incrementAndGet() == 1
                ? ModelResponse.of(Flux.error(new IOException(DNS_MESSAGE)))
                : ModelResponse.of(Flux.just(response("recovered")));

        assertThat(textsOf(retryOnlyGuard().interceptModel(request, handler))).containsExactly("recovered");
        assertThat(calls).hasValue(2);
    }

    @Test
    void aRetryOnlyGuardLetsAnExhaustedStreamingFailureSurface() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> {
            calls.incrementAndGet();
            return ModelResponse.of(Flux.error(new IOException(DNS_MESSAGE)));
        };

        // No invented answer: the workflow's own catch reports which stage broke.
        assertThatThrownBy(() -> textsOf(retryOnlyGuard().interceptModel(request, handler)))
                .hasMessageContaining("Failed to resolve");
        assertThat(calls).hasValue(3);
    }

    @Test
    void aRetryOnlyGuardLetsAnExhaustedSynchronousFailureSurface() {
        AtomicInteger calls = new AtomicInteger();
        ModelCallHandler handler = r -> {
            calls.incrementAndGet();
            throw new RuntimeException("request failed", new UnknownHostException("api.deepseek.com"));
        };

        assertThatThrownBy(() -> retryOnlyGuard().interceptModel(request, handler))
                .hasMessageContaining("request failed");
        assertThat(calls).hasValue(3);
    }

    @Test
    void recognisesDnsAndTransportFailuresAsTransient() {
        // The message that started this: no keyword in the original list matched it.
        assertThat(ModelCallGuardInterceptor.isTransient(new IOException(DNS_MESSAGE))).isTrue();
        assertThat(ModelCallGuardInterceptor.isTransient(new UnknownHostException("api.deepseek.com"))).isTrue();
        // Wrapped the way WebClient and reactor hand it over.
        assertThat(ModelCallGuardInterceptor.isTransient(
                new RuntimeException("request failed",
                        new IOException("connection reset by peer")))).isTrue();
        assertThat(ModelCallGuardInterceptor.isTransient(
                new IllegalStateException("429 Too Many Requests"))).isTrue();
    }

    @Test
    void treatsAnOrdinaryRequestErrorAsPermanentAndStillAnswers() {
        assertThat(ModelCallGuardInterceptor.isTransient(
                new IllegalArgumentException("400 Bad Request: tools.0.name is required"))).isFalse();

        AtomicInteger attempts = new AtomicInteger();
        ModelCallHandler handler = r -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("400 Bad Request: tools.0.name is required");
        };

        // A permanent error is not retried, but it still must not kill the run.
        ModelResponse guarded = guard().interceptModel(request, handler);

        assertThat(((AssistantMessage) guarded.getMessage()).getText()).contains("模型服务当前不可用");
        assertThat(attempts).hasValue(1);
    }
}
