package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.itajay.superassistant.interceptor.ModelCallGuardInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主 Agent 健壮性配置：
 * <ul>
 *   <li>{@link ModelCallLimitHook}：模型调用次数上限（单轮 / 整个会话），防止 ReAct 无限循环；</li>
 *   <li>{@link ToolRetryInterceptor}：工具调用失败后的重试与优雅降级；</li>
 *   <li>{@link ToolErrorInterceptor}：将工具异常转换为消息返回给模型，避免运行整体失败。</li>
 * </ul>
 */
@Configuration
public class AgentGuardConfig {

    /**
     * 模型调用次数上限：超过后以 END 优雅结束（返回已有输出），而不是抛错导致整个会话失败。
     */
    @Bean
    public ModelCallLimitHook modelCallLimitHook(
            @Value("${agent.guard.loop.max-model-calls-per-run:15}") int runLimit,
            @Value("${agent.guard.loop.max-model-calls-per-thread:60}") int threadLimit) {
        return ModelCallLimitHook.builder()
                .runLimit(runLimit) //单词调用
                //.threadLimit(threadLimit)
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();
    }

    /**
     * 单篇论文精读的模型调用上限，给 analyst-agent 用（不是主 Agent）。
     *
     * <p>为什么单独一个 Bean：{@link #modelCallLimitHook} 挂在主 Agent 上，而三个调研子
     * Agent 都没有接任何循环上限——原先无所谓，因为每个子 Agent 每轮只跑一次。但
     * {@code analyzePapers} 会在一个 Java 循环里连续调用 analyst N 次，每次都是一次**不受限**
     * 的运行；一篇排版混乱的 PDF 足以让某一篇反复翻页不收敛，而外层循环还会继续往下走。
     * 按"单篇"而不是"单轮"设限，是因为一次批量分析里 analyst 会被调用很多次，主 Agent 的
     * 15 次上限对单篇而言过宽，对整批而言又会被反复重置。</p>
     */
    @Bean
    public ModelCallLimitHook paperAnalysisCallLimitHook(
            @Value("${agent.guard.loop.max-model-calls-per-paper:8}") int paperLimit) {
        return ModelCallLimitHook.builder()
                .runLimit(paperLimit)
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();
    }

    /**
     * 工具调用重试：仅对瞬时性错误重试，重试耗尽后返回错误消息（而非抛出异常终止运行）。
     */
    @Bean
    public ToolRetryInterceptor toolRetryInterceptor(
            @Value("${agent.guard.tool-retry.max-retries:2}") int maxRetries,
            @Value("${agent.guard.tool-retry.initial-delay-ms:300}") long initialDelayMs,
            @Value("${agent.guard.tool-retry.max-delay-ms:3000}") long maxDelayMs,
            @Value("${agent.guard.tool-retry.backoff-factor:2.0}") double backoffFactor) {
        return ToolRetryInterceptor.builder()
                .maxRetries(maxRetries)
                .initialDelay(initialDelayMs)
                .maxDelay(maxDelayMs)
                .backoffFactor(backoffFactor)//.backoffFactor(backoffFactor): 退避因子
                .jitter(true)//.jitter(true): 是否启用“抖动”。
                .retryOn(ModelCallGuardInterceptor::isTransient)//重试条件判断.意味着只有当异常被判定为“瞬时”故障时才会触发重试。
                .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                .build();
    }

    /**
     * 工具异常兜底：把未被重试拦截器捕获的异常转换为错误消息返回给模型。
     */
    @Bean
    public ToolErrorInterceptor toolErrorInterceptor() {
        return new ToolErrorInterceptor();
    }
}
