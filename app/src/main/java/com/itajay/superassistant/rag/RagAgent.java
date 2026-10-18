package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class RagAgent {
    public final ReactAgent reactAgent;
    //系统提示词
    public final String AGENT_DESCRIPTION="""
    这是一个RagAgent，是专业领域的专家，任何专业的知识问题都需要调用该工具回答。
    tips：如果用户提到 `面试`，`复习`，`知识检索`，`专业回答`，`向量库`，`文档库`等相关提示词时，必须调用该工具回答用户。
    """;


    public RagAgent(RagHook raghook,
                    ChatModel chatModel,
                    com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook ragCallLimitHook,
                    com.itajay.superassistant.trace.TraceToolInterceptor traceToolInterceptor,
                    com.itajay.superassistant.trace.TraceModelInterceptor traceModelInterceptor) {
        this.reactAgent = ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                // Do not use "{input}" here: AgentTool passes the tool argument as a
                // UserMessage under the `messages` state key, so a template variable
                // named `input` is never present and PromptTemplate.render fails with
                // "Missing variable names are: [input]". RagHook injects the retrieved
                // context into the system prompt; this is just the role instruction.
                .instruction("""
                        你是知识库检索助手。优先依据系统提示中注入的知识库上下文回答用户问题；
                        上下文没有覆盖的内容，如实说明未在知识库中找到，不要编造。
                        """)
                .includeContents(false) //让rag agent专注于用户问题进行回答
                .description(AGENT_DESCRIPTION)
                .hooks(raghook, ragCallLimitHook)
                .interceptors(traceToolInterceptor, traceModelInterceptor)
                .build();
    }

}
