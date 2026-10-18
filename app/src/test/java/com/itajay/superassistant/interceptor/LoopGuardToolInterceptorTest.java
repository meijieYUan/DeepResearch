package com.itajay.superassistant.interceptor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.itajay.superassistant.trace.RunFinishedEvent;
import com.itajay.superassistant.trace.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoopGuardToolInterceptorTest {

    private static ToolCallRequest request(String runId, String tool, String args) {
        RunnableConfig config = RunnableConfig.builder().threadId("thread-1").build();
        config.context().put(TraceContext.RUN_ID, runId);
        return ToolCallRequest.builder()
                .toolName(tool)
                .arguments(args)
                .toolCallId("call-" + tool)
                .executionContext(new ToolCallExecutionContext(config, new OverAllState()))
                .build();
    }

    private static ToolCallHandler ok(AtomicInteger calls) {
        return request -> {
            calls.incrementAndGet();
            return ToolCallResponse.of(request.getToolName(), request.getToolCallId(), "ok");
        };
    }

    @Test
    void limitsAreScopedToTheRunAndClearedWhenItFinishes() {
        LoopGuardToolInterceptor guard = new LoopGuardToolInterceptor(2, 10);
        AtomicInteger calls = new AtomicInteger();
        ToolCallHandler handler = ok(calls);
        ToolCallRequest first = request("run-1", "readFile", "{}");

        assertThat(guard.interceptToolCall(first, handler).getResult()).isEqualTo("ok");
        assertThat(guard.interceptToolCall(first, handler).getResult()).isEqualTo("ok");
        assertThat(guard.interceptToolCall(first, handler).getResult()).contains("工具调用次数上限");
        assertThat(calls).hasValue(2);

        // A new run gets a fresh budget immediately; the finished run's counters are freed.
        ToolCallRequest secondRun = request("run-2", "readFile", "{}");
        assertThat(guard.interceptToolCall(secondRun, handler).getResult()).isEqualTo("ok");
        assertThat(calls).hasValue(3);

        guard.onRunFinished(new RunFinishedEvent("run-1", "thread-1"));
        ToolCallRequest sameRunAgain = request("run-1", "readFile", "{}");
        assertThat(guard.interceptToolCall(sameRunAgain, handler).getResult()).isEqualTo("ok");
        assertThat(calls).hasValue(4);
    }

    @Test
    void identicalCallsAreShortCircuitedWithinOneRun() {
        LoopGuardToolInterceptor guard = new LoopGuardToolInterceptor(10, 1);
        AtomicInteger calls = new AtomicInteger();
        ToolCallHandler handler = ok(calls);
        ToolCallRequest request = request("run-3", "webSearch", "{\"query\":\"same\"}");

        assertThat(guard.interceptToolCall(request, handler).getResult()).isEqualTo("ok");
        assertThat(guard.interceptToolCall(request, handler).getResult()).contains("完全相同的参数");
        assertThat(calls).hasValue(1);
    }
}
