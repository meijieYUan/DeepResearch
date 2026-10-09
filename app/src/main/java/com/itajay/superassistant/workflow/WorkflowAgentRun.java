package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.RunnableConfig;

/** One fresh stage invocation, with an explicit input and conversation config. */
@FunctionalInterface
interface WorkflowAgentRun {
    String invoke(String input, RunnableConfig config) throws Exception;
}
