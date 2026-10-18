package com.itajay.superassistant.app;

import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.security.PendingInterruptionStore;
import com.itajay.superassistant.service.ChatMessagePersistenceService;
import com.itajay.superassistant.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Route contract for the trace/cancel endpoints that the frontend now calls. */
class SuperAssistantEndpointTest {

    private final ChatStreamingService streaming = mock(ChatStreamingService.class);
    private final TraceService traceService = mock(TraceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SuperAssistant(
                    streaming, new PendingInterruptionStore(),
                    mock(ChatMessagePersistenceService.class), new ProgressChannelRegistry(),
                    traceService))
            .build();

    @Test
    void traceEndpointReturnsTheLatestSnapshot() throws Exception {
        when(traceService.recent("t1")).thenReturn(
                new TraceService.TraceSnapshot("tr-0123456789abcdef", "run-1", 10, 20, List.of()));

        mvc.perform(get("/api/chat/t1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("tr-0123456789abcdef"))
                .andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test
    void cancelEndpointDelegatesAndReportsTheResult() throws Exception {
        when(streaming.cancel("t1")).thenReturn(true);

        mvc.perform(post("/api/chat/t1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true));

        verify(streaming).cancel("t1");
    }
}
