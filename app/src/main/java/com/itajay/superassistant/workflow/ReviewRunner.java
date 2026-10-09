package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.RunnableConfig;

/** Retries unusable review output once, without changing the reviewed artifact. */
final class ReviewRunner {

    private ReviewRunner() {
    }

    static ReviewResult run(WorkflowAgentRun reviewer, String input, RunnableConfig config,
                            Runnable onRetry) throws Exception {
        checkInterrupted();
        ReviewResult review = ReviewResult.parse(reviewer.invoke(input, config));
        checkInterrupted();
        if (!review.executionFailed()) {
            return review;
        }
        onRetry.run();
        checkInterrupted();
        review = ReviewResult.parse(reviewer.invoke(input + """

                上一次审查输出不符合约定，尚未形成可用审查结论。请重新审查上面的同一份产物，
                不要求研究、写作或精读阶段返工。只输出符合 ReviewResult.Payload 的单个 JSON 对象：
                verdict 为 PASS 或 REVISE，summary 为总体意见，issues 为问题数组；不要输出 approved。
                REVISE 必须列出可执行问题；无法核验时请说明具体原因，不得猜测为 PASS。
                """, config));
        checkInterrupted();
        return review;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Review interrupted");
        }
    }
}
