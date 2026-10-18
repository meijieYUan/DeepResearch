package com.itajay.superassistant.trace;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TraceToolInterceptorTest {

    @Test
    void recordsStartAndSuccessWithSecretsRedacted() {
        TraceService service = mock(TraceService.class);
        TraceToolInterceptor interceptor = new TraceToolInterceptor(service);

        try (TraceContext.Scope ignored =
                     TraceContext.open("tr-dddddddddddddddd", "thread", "run")) {
            ToolCallRequest request = ToolCallRequest.builder()
                    .toolName("sendEmail")
                    .toolCallId("call-1")
                    .arguments("{\"password\":\"hunter2\",\"to\":\"a@x.com\"}")
                    .build();

            ToolCallResponse response = interceptor.interceptToolCall(request, req ->
                    ToolCallResponse.builder()
                            .toolName(req.getToolName())
                            .toolCallId(req.getToolCallId())
                            .content("sent")
                            .status("success")
                            .build());

            assertThat(response.getResult()).isEqualTo("sent");
        }

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(service, times(2)).record(any(), eq(TraceEvent.Category.TOOL),
                eq("sendEmail"), any(), anyLong(), detail.capture(), anyMap());
        assertThat(detail.getAllValues().getFirst()).contains("args:");
        assertThat(detail.getAllValues().getFirst()).contains("***").doesNotContain("hunter2");
        assertThat(detail.getAllValues().get(1)).contains("sent");
    }
}
