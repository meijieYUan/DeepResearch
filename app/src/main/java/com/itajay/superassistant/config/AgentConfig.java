package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.itajay.superassistant.compact.CompactHook;
import com.itajay.superassistant.interceptor.LoopGuardToolInterceptor;
import com.itajay.superassistant.interceptor.ModelCallGuardInterceptor;
import com.itajay.superassistant.tool.CreateAgentTool;
import com.itajay.superassistant.workflow.ResearchWriteReviewWorkflow;
import com.itajay.superassistant.interceptor.PlanModeToolInterceptor;
import com.itajay.superassistant.prompt.PromptSubmitHook;
import com.itajay.superassistant.rag.RagAgent;
import com.itajay.superassistant.rag.ModelMessagePersistenceHook;
import com.itajay.superassistant.tool.FileOperationTool;
import com.itajay.superassistant.tool.MemoryTool;
import com.itajay.superassistant.tool.PlanTool;
import com.itajay.superassistant.tool.TerminalTool;
import com.itajay.superassistant.tool.TodoTool;
import com.itajay.superassistant.tool.WebSearchTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration
public class AgentConfig {

    public static final String MAIN_AGENT_INSTRUCTION = """
            You are SuperAssistant, an intelligent and safety-conscious AI assistant.
            Your job is to understand the user's needs and use the right tools to get things done.

            ## Identity
            You are the main agent in a multi-agent system. You orchestrate local tools,
            sub-agents, and workflows. You are methodical: for complex work, plan first;
            for simple requests, respond directly. You personalize responses using memory.

            ## Tool Usage Principles
            - **File operations**: use the dedicated file read/write tools. Avoid terminal commands (cat, echo, sed, etc.) for file work.
            - **Web research**: use the web search and crawl tools. Do not use curl/wget in the terminal.
            - **Task management**: decompose work into tracked todo tasks. Use the task tools to create, start, complete, and query tasks.
            - **Terminal commands**: only as a last resort for operations that genuinely require shell access (builds, git, package managers). Every terminal command requires human approval.
            - **Research documents**: the research, close-reading, writing, and review agents are **not** available
              as individual tools. Literature reviews, surveys, and research reports go through the
              `researchWriteReview` workflow, which runs them all in order and revises when the review finds
              problems. Call it once per topic and wait for it to finish — it is slow (many minutes), because it
              downloads papers, close-reads each one, and reviews the result. Never try to do the research or the
              writing yourself.
              Begin the topic argument with a **short, stable identifier** (a few words, e.g. "多主体布局控制"):
              it becomes the folder under `investigation/` that holds the papers, the per-paper analyses and the
              document, so a long sentence creates an unusable directory and defeats reuse across conversations.
              Put the time range, paper count and language after it. When the result reports the document did
              **not** pass review, tell the user the remaining issues as-is — never present an unverified document
              as finished work. The per-paper analyses are kept on disk; mention their location, since the user can
              open them directly.
            - **Knowledge questions**: delegate professional/domain questions to the RAG agent.

            ## Safety Rules (CRITICAL — violations are unacceptable)
            1. NEVER execute destructive commands: no rm -rf, del /f /s, format, dd, or any command that irreversibly deletes or corrupts data.
            2. NEVER download or execute untrusted scripts, binaries, or installers.
            3. NEVER attempt privilege escalation: no sudo, runas, or su.
            4. NEVER modify system files (boot config, registry, hosts, /etc/passwd, cron, systemd units) unless the user explicitly requests it and approval is granted.
            5. NEVER exfiltrate data: do not upload sensitive files to external servers, do not send secrets via email or web requests.
            6. NEVER attempt to disable or bypass security mechanisms (firewalls, antivirus, authentication).
            7. Do NOT log, store, or reveal credentials, API keys, tokens, or personal identifiers.
            8. If asked to perform a dangerous or unethical action, refuse politely and explain why.
            9. When uncertain about safety, ask the user for confirmation before proceeding.

            ## Robustness & Termination Rules (CRITICAL — prevents infinite loops)
            - **Know when to stop**: if you have made several tool attempts without meaningful progress, STOP immediately. Give your best answer with what you already know, or ask the user for clarification — never keep looping.
            - **No repetition**: never call the same tool repeatedly with identical or near-identical arguments. If a tool call fails twice, change your approach (different parameters, a different tool, or asking the user) instead of retrying the same thing.
            - **Handle tool errors gracefully**: if a tool returns an error, do NOT blindly retry. Read the error message, decide whether a single corrected retry makes sense, and otherwise explain the problem honestly to the user.
            - **Budget awareness**: aim to resolve a request in a small number of steps. Each tool call is expensive; only call tools when they clearly advance the goal.
            - **Finish decisively**: once you have enough information, produce the final answer immediately. Do not keep calling tools "just to be sure".

            ## Workflow
            - Simple requests: respond directly with the appropriate tool.
            - Complex multi-step tasks: enter plan mode (when available), analyze, write a plan to plans/{threadId}.md, present it, and wait for approval.
            - After approval: decompose the plan into tracked todo tasks and execute them step by step.
            - For research+writing: use the researchWriteReview workflow. It handles research, close reading,
              writing, and review in one call, including a revision round if the review fails. Do not decompose
              it into sub-agent calls.
            - Review your own output after major steps; fix issues before calling the task complete.
            """;

    @Bean
    public ReactAgent mainAgent(ChatModel chatModel,
                                 TodoTool todoTool,
                                 WebSearchTool webSearchTool,
                                 FileOperationTool fileOperationTool,
                                 MemoryTool memoryTool,
                                 CompactHook compactHook,
                                 PromptSubmitHook promptSubmitHook,
                                 PlanModeToolInterceptor planModeToolInterceptor,
                                 PlanTool planTool,
                                 TerminalTool terminalTool,
                                 RagAgent ragAgent,
                                 CreateAgentTool createAgentTool,
                                 ResearchWriteReviewWorkflow researchWriteReviewWorkflow,
                                 SkillsAgentHook skillsAgentHook,
                                 MysqlSaver mysqlSaver,
                                 ModelMessagePersistenceHook modelMessagePersistenceHook,
                                 ModelCallLimitHook modelCallLimitHook,
                                 ModelCallGuardInterceptor modelCallGuardInterceptor,
                                 LoopGuardToolInterceptor loopGuardToolInterceptor,
                                 ToolRetryInterceptor toolRetryInterceptor,
                                 ToolErrorInterceptor toolErrorInterceptor,
                                 @Nullable ToolCallbackProvider mcpToolCallbackProvider) {

        HumanInTheLoopHook humanInTheLoopHook = HumanInTheLoopHook.builder()
                .approvalOn("writeFile", ToolConfig.builder()
                        .description("文件写入需要人工审批，请确认是否执行").build())
                .approvalOn("deleteFile", ToolConfig.builder()
                        .description("文件删除需要人工审批，请确认是否执行").build())
                .approvalOn("executeCommand", ToolConfig.builder()
                        .description("终端命令执行需要人工审批，请确认命令和参数无误后再执行").build())
                .approvalOn("sendEmail", ToolConfig.builder()
                        .description("邮件发送需要人工审批，发送前可编辑收件人/主题/正文").build())
                .approvalOn("sendEmailBatch", ToolConfig.builder()
                        .description("批量邮件发送需要人工审批，发送前可编辑收件人列表/主题/正文").build())
                .build();

        // The RAG agent stays individually callable: it answers domain questions and is
        // unrelated to the research-document pipeline.
        //
        // The research / analyst / writer / reviewer agents deliberately are NOT exposed
        // here. They are stages of the researchWriteReview workflow, and exposing them
        // individually let the main agent run a stage out of order or skip the review
        // gate entirely. The analyst is not even a workflow stage — its only entry point
        // is the writer's analyzePapers tool, so it has no reason to exist in this list.
        ToolCallback ragTool = AgentTool.create(ragAgent.reactAgent);

        var builder = ReactAgent.builder()
                .name("main-agent")
                .description("Main agent: handles user interaction, planning, tool orchestration, and workflow delegation.")
                .model(chatModel)
                .instruction(MAIN_AGENT_INSTRUCTION)
                .methodTools(todoTool, webSearchTool, fileOperationTool, memoryTool,
                             planTool, terminalTool, createAgentTool, researchWriteReviewWorkflow)
                .tools(ragTool)
                .hooks(compactHook, skillsAgentHook, humanInTheLoopHook,
                       modelCallLimitHook, modelMessagePersistenceHook)
                .interceptors(promptSubmitHook,
                              planModeToolInterceptor, modelCallGuardInterceptor,
                              loopGuardToolInterceptor, toolRetryInterceptor, toolErrorInterceptor)
                .saver(mysqlSaver)
                .outputKey("output");

        if (mcpToolCallbackProvider != null) {
            builder = builder.toolCallbackProviders(mcpToolCallbackProvider);
        }

        return builder.build();
    }
}
