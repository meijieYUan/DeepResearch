package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.itajay.superassistant.app.ChatStreamingService.AgentRunner;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.security.PendingInterruptionStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChatStreamingService}: the event protocol that the POST response
 * stream carries. The agent is faked through the {@link AgentRunner} seam; the
 * recording service subclass captures the (event, payload) pairs instead of writing
 * them to a socket, so the assertions are on the protocol itself.
 */
class ChatStreamingServiceTest {

    private static final String THREAD = "thread-1";

    private final ProgressChannelRegistry registry = new ProgressChannelRegistry();
    private final PendingInterruptionStore store = new PendingInterruptionStore();
    private final List<Map.Entry<String, Object>> events = new CopyOnWriteArrayList<>();

    /** Records every event the service would stream, in order. */
    private ChatStreamingService serviceReturning(Flux<NodeOutput> flux) {
        return new ChatStreamingService((input, config) -> flux, registry, store) {
            @Override
            protected boolean send(SseEmitter emitter, String event, Object payload) {
                events.add(new AbstractMap.SimpleEntry<>(event, payload));
                return true;
            }
        };
    }

    private static NodeOutput output(String answer) {
        return NodeOutput.of("end", null, new OverAllState(Map.of("output", answer)), null);
    }

    /** Waits until the stream has produced its terminal done event. */

    @Test
    void aFinishedRunStreamsTheAnswerThenDone() throws Exception {
        ChatStreamingService service = serviceReturning(Flux.just(output("你好，答案")));

        service.start(THREAD, "hi", RunnableConfig.builder().build(), false);
        awaitTerminal("done");

        assertThat(eventNames()).containsExactly("answer", "done");
        Map<String, Object> answer = payload("answer");
        assertThat(answer.get("text")).isEqualTo("你好，答案");
        assertThat(answer.get("threadId")).isEqualTo(THREAD);
        assertThat(answer.get("planEnabled")).isEqualTo(false);
    }

    @Test
    void modelDeltasStreamAsTheyArriveAndNonModelChunksDoNot() throws Exception {
        StreamingOutput modelDelta = mock(StreamingOutput.class);
        when(modelDelta.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(modelDelta.chunk()).thenReturn("你");
        StreamingOutput toolDelta = mock(StreamingOutput.class);
        when(toolDelta.getOutputType()).thenReturn(OutputType.AGENT_TOOL_STREAMING);
        when(toolDelta.chunk()).thenReturn("{\"tool\":"); // tool-call chunk, never forwarded

        ChatStreamingService service = serviceReturning(
                Flux.just(modelDelta, toolDelta, output("你好")));

        service.start(THREAD, "hi", RunnableConfig.builder().build(), false);
        awaitTerminal("done");

        assertThat(eventNames()).containsExactly("delta", "answer", "done");
        assertThat(payload("delta").get("text")).isEqualTo("你");
    }

    @Test
    void anInterruptionStoresThePendingApprovalAndStreamsIt() throws Exception {
        InterruptionMetadata metadata = InterruptionMetadata.builder("risky-node", new OverAllState())
                .toolFeedbacks(List.of())
                .build();
        ChatStreamingService service = serviceReturning(Flux.just(metadata));

        service.start(THREAD, "hi", RunnableConfig.builder().build(), true);
        awaitTerminal("done");

        assertThat(eventNames()).containsExactly("interruption", "done");
        Map<String, Object> interruption = payload("interruption");
        assertThat(interruption.get("message")).isEqualTo("High-risk operations require approval");
        assertThat(interruption.get("planEnabled")).isEqualTo(true);
        assertThat(store.get(THREAD)).as("the pending interruption must be resumable").isNotNull();
    }

    @Test
    void aFailedRunStreamsTheErrorThenDoneAndClearsThePendingState() throws Exception {
        store.put(THREAD, RunnableConfig.builder().build(),
                InterruptionMetadata.builder("n", new OverAllState()).toolFeedbacks(List.of()).build(), "hi");
        ChatStreamingService service = serviceReturning(Flux.error(new IllegalStateException("boom")));

        service.start(THREAD, "hi", RunnableConfig.builder().build(), false);
        awaitTerminal("done");

        assertThat(eventNames()).containsExactly("error", "done");
        assertThat(payload("error").get("message")).isEqualTo("boom");
        assertThat(store.get(THREAD)).as("a failed run must not leave a resumable state").isNull();
    }

    @Test
    void anEmptyRunIsAnErrorNotASilentClose() throws Exception {
        ChatStreamingService service = serviceReturning(Flux.empty());

        service.start(THREAD, "hi", RunnableConfig.builder().build(), false);
        awaitTerminal("done");

        assertThat(eventNames()).containsExactly("error", "done");
        assertThat(payload("error").get("message")).isEqualTo("Agent returned empty result");
    }

    @Test
    void progressEventsFromToolsReachTheSameStream() throws Exception {
        // The fake run publishes a progress event from "inside" the agent, the way
        // PaperDownloadTool does from the workflow's background threads. The service's
        // response emitter is attached to the registry's fan-out at start(), and the
        // probe proves the event actually reaches that emitter (its payload shape is
        // covered by ProgressChannelRegistryTest).
        CopyOnWriteArrayList<Boolean> progressSends = new CopyOnWriteArrayList<>();
        registry.attach(THREAD, new SseEmitter() {
            @Override
            public void send(SseEmitter.SseEventBuilder builder) throws IOException {
                progressSends.add(true);
            }
        });
        ChatStreamingService service = serviceReturning(Flux.defer(() -> {
            registry.publish(THREAD, ProgressEvent.of(ProgressStage.RESEARCHING, 0, "已下载论文：MemGPT"));
            return Flux.just(output("done-text"));
        }));

        service.start(THREAD, "hi", RunnableConfig.builder().build(), false);
        awaitTerminal("done");

        assertThat(progressSends).hasSize(1);
        assertThat(eventNames()).contains("answer");
    }

    @Test
    void aSecondRequestForTheSameThreadIsRejectedWhileOneIsRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ChatStreamingService service = serviceReturning(Flux.defer(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Flux.just(output("late answer"));
        }));

        service.start(THREAD, "hi", RunnableConfig.builder().build(), false);
        Thread.sleep(100); // let the first run take the slot

        SseEmitter rejected = service.start(THREAD, "again", RunnableConfig.builder().build(), false);
        assertThat(rejected).isNotNull(); // HTTP stays 200; the stream carries the refusal
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getKey()).isEqualTo("error");
            assertThat((String) ((Map<?, ?>) e.getValue()).get("message")).contains("已有任务在执行");
        });

        release.countDown();
        awaitTerminal("answer");
    }

    // ──────────────────────────────────────────────────────────────────────

    private List<String> eventNames() {
        return events.stream().map(Map.Entry::getKey).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String event) {
        return events.stream()
                .filter(e -> e.getKey().equals(event))
                .map(e -> (Map<String, Object>) e.getValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + event + " event; got " + eventNames()));
    }

    /** Waits until the given terminal event arrives (done is always last). */
    private void awaitTerminal(String event) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && eventNames().stream().noneMatch(event::equals)) {
            Thread.sleep(10);
        }
        assertThat(eventNames()).as("timed out waiting for %s", event).contains(event);
    }
}
