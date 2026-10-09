package com.itajay.superassistant.progress;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldConstructorAndFactoriesKeepANullPayloadAndAllExistingFields() throws Exception {
        ProgressEvent direct = new ProgressEvent(ProgressStage.DONE, "精读完成", 2, "完成", 123L);
        assertThat(direct.payload()).isNull();
        assertThat(ProgressEvent.of(ProgressStage.RESEARCHING, 0).payload()).isNull();
        ProgressEvent factory = ProgressEvent.of(ProgressStage.FAILED, 1, null, "精读失败");
        assertThat(factory.detail()).isEmpty();
        assertThat(factory.label()).isEqualTo("精读失败");
        assertThat(factory.payload()).isNull();
        var json = mapper.readTree(mapper.writeValueAsString(direct));
        assertThat(json.path("stage").asText()).isEqualTo("DONE");
        assertThat(json.path("label").asText()).isEqualTo("精读完成");
        assertThat(json.path("round").asInt()).isEqualTo(2);
        assertThat(json.path("detail").asText()).isEqualTo("完成");
        assertThat(json.path("timestamp").asLong()).isEqualTo(123L);
        assertThat(json.path("payload").isNull()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(direct), ProgressEvent.class)).isEqualTo(direct);
        assertThat(mapper.readValue("""
                {"stage":"DONE","label":"调研完成","round":1,"detail":"ok","timestamp":123}
                """, ProgressEvent.class).payload()).isNull();
    }

    @Test
    void structuredPayloadSerializesUppercaseEnumsWithoutHttpOrActionFields() throws Exception {
        ProgressContext context = new ProgressContext("thread", "run", ProgressEvent.Workflow.PAPER_DEEP_READ,
                ProgressStage.ANALYZING, 1, "topic", "paper");
        ProgressEvent event = context.event(ProgressEvent.Operation.ANALYSIS, ProgressEvent.Status.RUNNING,
                "reading", null, new ProgressEvent.Counts(3, 1, 0, 0, 1, 2), 1, 2);
        var json = mapper.readTree(mapper.writeValueAsString(event));
        var payload = json.path("payload");
        assertThat(payload.path("workflow").asText()).isEqualTo("PAPER_DEEP_READ");
        assertThat(payload.path("operation").asText()).isEqualTo("ANALYSIS");
        assertThat(payload.path("status").asText()).isEqualTo("RUNNING");
        assertThat(payload.path("counts").path("active").asInt()).isEqualTo(2);
        assertThat(payload.path("attempt").asInt()).isEqualTo(1);
        assertThat(payload.path("maxAttempts").asInt()).isEqualTo(2);
        assertThat(payload.has("threadId")).isFalse();
        assertThat(payload.has("streamId")).isFalse();
        assertThat(payload.has("action")).isFalse();
        assertThat(mapper.readValue(mapper.writeValueAsString(event), ProgressEvent.class)).isEqualTo(event);
    }

    @Test
    void childConfigContainsOnlyExplicitProgressAndRequirementsAndKeepsContextImmutable() {
        ProgressContext parent = new ProgressContext("thread", "run", ProgressEvent.Workflow.RESEARCH_WRITE_REVIEW,
                ProgressStage.WRITING, 1, "topic", null);
        RunnableConfig config = parent.childConfig("requirements");
        ProgressContext child = ProgressContext.from(new ToolContext(Map.of("_AGENT_CONFIG_", config)))
                .orElseThrow().at(ProgressStage.ANALYZING, 2).forPaper("topic", "Solo");
        assertThat(parent.paper()).isNull();
        assertThat(parent.round()).isEqualTo(1);
        assertThat(child.runId()).isEqualTo(parent.runId());
        assertThat(child.childConfig("requirements")).isNotSameAs(config);
        assertThat(config.threadId()).contains("thread");
        assertThat(config.metadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY)).contains("requirements");
        assertThat(config.metadata(ProgressContext.METADATA_KEY)).contains(parent);
        assertThat(config.metadata("approval")).isEmpty();
        assertThat(config.metadata("feedback")).isEmpty();
        assertThat(ProgressContext.from(null)).isEmpty();
        assertThat(ProgressContext.from(new ToolContext(Map.of()))).isEmpty();
        assertThat(ProgressContext.from(new ToolContext(Map.of("_AGENT_CONFIG_",
                RunnableConfig.builder().addMetadata(ProgressContext.METADATA_KEY, "not a context").build()))))
                .isEmpty();
    }
}
