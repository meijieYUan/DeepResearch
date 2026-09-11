# SuperAssistant —— AI 智能助理平台

基于 Spring AI Alibaba 多智能体架构的企业级 AI 助理，支持对话、RAG 知识检索、待办管理、网页搜索、文件操作、邮件发送，内置 Human-In-The-Loop 高危操作审批机制、四层上下文压缩系统与模型/工具异常兜底。

## 架构概览

```
                        POST /api/chat/{threadId}
                              │
                              ▼
        ┌─────────────────────────────────────────────────┐
        │              main-agent (ReactAgent)            │
        │                                                 │
        │  Hooks · BEFORE_AGENT（按 getOrder 升序执行）    │
        │    ├─ SkillsAgentHook            技能规则注入    │
        │    └─ CompactHook (order=10000)  四层上下文压缩  │
        │  Hooks · MODEL                                  │
        │    ├─ HumanInTheLoopHook         高危操作审批    │
        │    ├─ ModelCallLimitHook         模型调用上限    │
        │    └─ ModelMessagePersistenceHook 回答持久化     │
        │                                                 │
        │  Interceptors                                   │
        │    ├─ PromptSubmitHook        (Model) 动态系统提示词 │
        │    ├─ PlanModeToolInterceptor (Model) 计划模式工具门控 │
        │    ├─ ModelCallGuardInterceptor (Model) 模型异常兜底 │
        │    ├─ LoopGuardToolInterceptor(Tool)  工具循环防护   │
        │    ├─ ToolRetryInterceptor    (Tool)  工具重试       │
        │    └─ ToolErrorInterceptor    (Tool)  错误降级       │
        └───────────────────┬─────────────────────────────┘
                            │
     ┌──────────────────────┼──────────────────────┐
     ▼                      ▼                      ▼
┌───────────┐    ┌──────────────────┐   ┌──────────────────┐
│ 子 Agent  │    │   Local Tools    │   │   MCP Client     │
│ ├ RagAgent│    │ ├ TodoTool       │   │  ──SSE──► Email  │
│ ├ Research│    │ ├ WebSearchTool  │   │   MCP Server     │
│ ├ Writer  │    │ ├ FileOpTool     │   │     :8081        │
│ └ Reviewer│    │ ├ MemoryTool     │   │  sendEmail       │
│           │    │ ├ PlanTool       │   │  sendEmailBatch  │
│ Research- │    │ ├ TerminalTool   │   └──────────────────┘
│ Write     │    │ ├ CreateAgentTool│
│ Workflow  │    │ └ ResearchWrite  │
└─────┬─────┘    └──────────────────┘
      │ Milvus 向量 + BM25 关键词 混合检索
      ▼
┌──────────────────────────────────────────────┐
│ QueryExpansion        多路查询扩展           │
│ DocumentRetrieval     向量 + BM25 → RRF 融合 │
│ DocumentPostRetrieval 跨查询去重 + LLM 精排  │
│ RagHook               注入 RAG system prompt │
└──────────────────────────────────────────────┘
```

## 核心功能

### 1. 多智能体协作与计划模式

- **主 Agent（main-agent）**：基于 Spring AI Alibaba ReactAgent 实现统一入口和任务路由，可将子任务委派给 `rag-agent`、`research-agent`、`writer-agent`、`reviewer-agent` 四个专业子 Agent。子 Agent 通过 `AgentTool.create(...)` 包装为 `ToolCallback` 挂载。
- **PlanTool 计划模式（工具门控）**：复杂任务先进入只读计划阶段。`PlanModeToolInterceptor` 按三级策略动态裁剪模型可见的工具集：
  1. `planEnabled=false`（用户未开启开关）→ 隐藏 `enterPlanMode`/`exitPlanMode`，模型无法进入计划模式；
  2. `planEnabled=true`、`planActive=false` → 全部工具可用；
  3. `planActive=true`（已进入计划模式）→ 仅保留只读工具 + `PlanTool`，隐藏 `writeFile`/`deleteFile`/`executeCommand`/`sendEmail`/`sendEmailBatch`。
- **计划模式提示词**：`PromptSubmitHook` 在 `planEnabled=true` 时向 system message 注入"Plan Mode Active"约束说明；计划正文由 Agent 写入 `plans/{threadId}.md`（该路径由 `ContextCompactor` 识别为需保留的上下文附件）。
- **计划生命周期**：
  ```
  用户开启 Plan Mode → main-agent → PlanTool.enterPlanMode
    → 只读分析 + 写 plans/{threadId}.md → 向用户展示计划
    → PlanTool.exitPlanMode(approved)
        ├─ approved=true  → 退出计划模式，调用 todoWrite 拆解为 Todo 任务执行
        └─ approved=false → 留在计划模式，按反馈修订后重新请求审批
  ```
- **ResearchWriteWorkflow**：研究 → 写作的顺序流水线，ResearchAgent 搜集素材后 WriterAgent 撰写结构化 Markdown 文档，作为单一 Tool 暴露给主 Agent 调用。
- **CreateAgentTool**：动态创建子 Agent 执行独立任务。

> **注意**：早期的独立计划子系统（`PlanController` / `PlanService` / `PlanStepHook` / `plan_task` / `plan_step` 相关实体与 Mapper）及其前端页面（`PlanView.vue`、`/plans` 路由、`/api/plans/*` 调用）已全部移除。现在"计划"完全通过对话完成：Agent 在计划模式下与用户商量并写出计划，用户审核后由 `exitPlanMode` 退出并拆分为 Todo 任务；前端通过 Todo 接口展示任务进展。

### 2. 四层上下文压缩系统

压缩入口是 `CompactHook`：注册在 `BEFORE_AGENT` 位置，`getOrder() = 10000`，因此在所有其他 BEFORE_AGENT hook（默认 order = 0）**之后**执行，token 估算覆盖即将送入模型的最终消息列表。

每次 agent 调用（即每次用户消息）触发一次。框架的工具循环（`_AGENT_TOOL_ → _AGENT_MODEL_`）不经过 BEFORE_AGENT，所以压缩不会在工具循环中重复触发。

**动态阈值**（`CompactThresholds`，由 `agent.compact` 配置派生）：

```
usableBudget   = modelContextWindowTokens − outputReserveTokens − systemPromptReserveTokens
               = 128000 − 8000 − 12000 = 108000

warningTokens  = floor(usableBudget × warningRatio)  = floor(108000 × 0.70) = 75600   → 触发 L1–L3
criticalTokens = floor(usableBudget × criticalRatio) = floor(108000 × 0.90) = 97200   → 触发 L4
```

预留量保证即使达到 critical 阈值，仍有空间容纳模型输出、系统提示词、工具定义与注入的上下文；warning 与 critical 之间的 10% 差值用于吸收 L1–L3 的压缩收益。配置非法时（`warningRatio >= criticalRatio` 或窗口不足）回退到默认比例 / 保守常量。

**决策流程**：

```
preTokens < warningTokens(75.6K)  → 原样返回
        │
        ▼
L1 ToolResultTruncator   单条工具结果 > 10K tokens → 落盘 + 500 tokens 预览
L2 ContextSnip           消息数 > 60 时移除高置信度低价值消息
L3 MicroCompact          保留最近 5 个工具事务，替换更早事务的结果 payload
        │
        ▼
afterL3 < criticalTokens(97.2K)  → 返回 L1–L3 结果
        │
        ▼
冷却检查：距上次全量压缩 < cooldownCalls(5) 次调用 → 跳过 L4，保留 L1–L3 结果
        │
        ▼
L4 ContextCompactor      同步 LLM 摘要 + 上下文恢复 → ReplaceAllWith 写回图状态
```

| 层 | 实现类 | 行为 | 保护机制 |
|---|---|---|---|
| L1 | `ToolResultTruncator` | 单条结果 > 10K tokens：全文落盘 `.compact/tool_results/{threadId}/`，上下文内替换为 500 tokens 预览 + 文件指针 | 目录总量 > 512MB 时按最旧文件修剪 |
| L2 | `ContextSnip` | ≥ 60 条消息才激活；移除连续重复 user 消息、瞬时错误（429 / timeout / 502-504 …）、低价值填充语 | 工具调用与工具结果**绝对保护**；最近 20 条消息保护 |
| L3 | `MicroCompact` | 保留最近 5 个工具事务，更早事务仅替换**结果 payload**（保留 assistant 调用与 call id） | `ToolCallIntegrity` 保证不切断事务 |
| L4 | `ContextCompactor` | 前缀交 LLM 总结（> 30K 字符自动分块 + 合并），保留安全后缀 | 边界回退到工具事务起点，绝不切断调用/结果配对 |

**L4 产物**（写回图状态 → 随 checkpoint 持久化 → 供后续请求复用）：

```
[COMPACT BOUNDARY   SystemMessage]           压缩边界标记
[Context Compaction Summary UserMessage]     LLM 摘要（≤ 3000 tokens）
[Post-compact recovered SystemMessage ×N]    最近访问的 ≤5 个文件（每文件 ≤5K tokens，总预算 50K）
                                             + 当前计划文件内容（plans/{threadId}.md）
... 保留的最近 20 条原始消息
```

设计要点：**摘要负责"发生过什么"，附件负责"继续工作需要的上下文"**。同时归档快照到 `.compact/snapshots/{threadId}/`（每线程保留最近 5 个）。

Token 估算由 `TokenEstimator` 抽象，默认 `BpeTokenEstimator`（jtokkit O200K BPE），失败时回退 `HeuristicTokenEstimator`；`TokenBudgets` 提供预算内二分截断。

用户记忆 `.memory/MEMORY.md` **不作为压缩附件注入** —— `PromptSubmitHook` 每次模型调用都会把最新记忆写入 system message，重复注入只会造成冗余。

**正确性保证**：hook 返回的 `messages` 一律包装为 `ReplaceAllWith.of(...)`。框架对 `messages` 键的默认策略是 `AppendStrategy`，裸列表会被**追加**到历史之后，导致每轮上下文翻倍。

### 3. Human-In-The-Loop 高危操作审批

**需要审批的工具**（配置在 `AgentConfig`）：

| 工具 | 审批原因 |
|------|---------|
| `writeFile` | 文件写入 |
| `deleteFile` | 文件删除 |
| `executeCommand` | 终端命令执行 |
| `sendEmail` | 邮件发送（审批前可编辑收件人/主题/正文） |
| `sendEmailBatch` | 批量邮件发送（审批前可编辑参数） |

**两阶段交互流程**：

```
阶段 1: POST /api/chat/{threadId}
  → Agent 执行到高危操作
  → 返回 { type: "INTERRUPTED", pendingApprovals: [...] }
  → 前端渲染审批面板 [同意] [拒绝] [编辑]

阶段 2: POST /api/chat/{threadId}/approve
  → 提交 decisions: [{toolId, result, editedArguments?}]
  → 后端 HITLHelper.approveOneByOne() 逐条应用决策
  → RunnableConfig.addHumanFeedback() 注入 → Agent 恢复执行
  → 链式中断自动处理（多个高危操作可连续触发审批）
```

`result` 可选值：`APPROVED` / `REJECTED` / `EDITED`。`EDITED` 时必填 `editedArguments` 提供修改后的工具参数。

中断现场由 `PendingInterruptionStore` 以 `threadId` 为键暂存（含原始 `RunnableConfig` 与输入消息），恢复时据此重建 `RunnableConfig`。核心类：[SuperAssistant.java](app/src/main/java/com/itajay/superassistant/app/SuperAssistant.java) · [HITLHelper.java](app/src/main/java/com/itajay/superassistant/security/HITLHelper.java) · [ApprovalDecision.java](app/src/main/java/com/itajay/superassistant/security/ApprovalDecision.java)

### 4. RAG 检索增强生成

完整管线（实现在 [RagHook.java](app/src/main/java/com/itajay/superassistant/rag/RagHook.java)，作用于 `rag-agent` 的 BEFORE_AGENT）：

```
用户输入 → QueryExpansion        多路查询扩展（保留原始语义）
         → DocumentRetrieval     每路：Milvus 向量检索 + BM25 关键词检索 → RRF 融合
         → DocumentPostRetrieval 跨查询去重（DocumentFusion）→ LLM 精排（LlmDocumentReranker）
         → RagHook               检索结果注入 RAG system prompt
```

- 支持 PDF / Markdown / TXT / Java / Python / XML / JSON 等多格式文档导入
- TokenTextSplitter：chunkSize=800，maxChunks=500
- API：`POST /knowledge/upload`（multipart file）
- `RagHook` 通过 `ReplaceAllWith` 覆盖 `rag-agent` 的状态消息；历史上下文由 main-agent 持有，`rag-agent` 无独立 checkpoint
- BM25 倒排索引持久化于 `rag.retrieval.bm25.index-path`（默认 `./data/bm25-index`）
- `QueryTransformation`（查询压缩改写）已实现但未接入管线

### 5. 持久记忆体系

MemoryTool 提供 Agent 可调用的记忆管理能力，存储于 `.memory/` 目录（索引 `.memory/MEMORY.md`，条目 `.memory/facts/`）：

| 操作 | 说明 |
|------|------|
| `remember` | 记录用户偏好/项目/事件/知识/联系人，按重要性 1-10 分级，自动去重更新 |
| `recall` | 按关键词搜索记忆，按重要性排序返回 |
| `listMemories` | 按类型分组列出全部记忆及容量使用情况 |
| `deleteFact` | 按 ID 删除指定记忆 |
| `consolidateMemories` | 清理低重要性（<4）记忆并重新编号，保持 ≤ 50 条预算 |

记忆档案由 `PromptSubmitHook` 在**每次模型调用前**注入 system message（非 messages 列表，上限 3000 字符），因此记忆更新下一轮即生效，且不会写入 checkpoint 或前端历史。

### 6. 对话持久化

三层职责分离，互不干扰：

| 存储 | 内容 | 用途 |
|---|---|---|
| MysqlSaver checkpoint（`GRAPH_THREAD` / `GRAPH_CHECKPOINT`） | 完整 messages：含工具调用/工具结果/压缩产物 | 图状态恢复的**唯一来源**，支持跨请求续接 |
| `custom_chat_memory` 表 | 用户消息 + 有文本的 assistant 回答（纯工具调用消息跳过） | 前端渲染 fallback，由 `CustomJdbcChatMemoryRepository` 管理 |
| 前端 localStorage | `{role, content}` | 当前前端实际渲染源 |

- `ModelMessagePersistenceHook`（AFTER_MODEL）在每次模型调用后持久化最新一条**有文本**的 assistant 消息；工具调用/结果属于 checkpoint 状态，不进入渲染表。
- 压缩产物（边界标记 / 摘要 / 恢复附件）随 `messages` 进入 checkpoint，后续请求可直接复用；前端渲染历史时可按需过滤这些内部标记。
- `CheckpointRetentionService`（`@EnableScheduling`）按 `agent.checkpoint-retention` 定时清理过期 checkpoint。

### 7. Tools 工具集

**已挂载到 main-agent**（`methodTools`）：

| 工具 | 能力 |
|---|---|
| **TodoTool** | `todoWrite(objective)` LLM 拆解并持久化；`createTask` / `startTask` / `completeTask` / `updateTodo` / `deleteTodo` / `getReadyTasks` / `queryTodos`。所有操作绑定当前会话 `threadId`（`createTask`、`queryTodos` 从 `ToolContext` 解析，不由模型传入） |
| **WebSearchTool** | DuckDuckGo HTML 搜索（无需 API Key），解析 top 8 结果；`webCrawl(url)` Jsoup 抓取全文，> 8K 字符自动截断 |
| **FileOperationTool** | `readFile` / `writeFile` / `createFile` / `deleteFile` / `listFiles`，路径限制在 workspace 内 |
| **MemoryTool** | `remember` / `recall` / `listMemories` / `deleteFact` / `consolidateMemories` |
| **PlanTool** | `enterPlanMode` / `exitPlanMode` |
| **TerminalTool** | `executeCommand`（纳入 HITL 审批） |
| **CreateAgentTool** | 动态创建子 Agent 执行独立任务 |
| **ResearchWriteWorkflow** | 研究 → 写作一站式流水线 |

**子 Agent 工具**（`tools`）：`rag-agent` / `research-agent` / `writer-agent` / `reviewer-agent`

**MCP 工具**（独立 Email Server :8081）：`sendEmail` / `sendEmailBatch`（纳入 HITL 审批）

**已实现但未挂载**：`DateTimeTool`（`getCurrentDateTime` / `dateDifference` / `dateAdd`）、`WeatherTool`（`getWeather`）。两者均为 `@Component`，如需启用在 `AgentConfig` 的 `methodTools(...)` 中追加即可。

### 8. Agent Skills

[SkillConfig](app/src/main/java/com/itajay/superassistant/skill/SkillConfig.java) 通过 ClasspathSkillRegistry 加载 skills：

- `research_writing_skill`：科研论文调研与文献综述撰写。自动检索 arXiv / Semantic Scholar / Google Scholar，精读 4-8 篇论文，提取方法框架/创新点/训练目标，按模板生成结构化调研文档。

添加新 skill：在 `resources/skills/{skill-name}/` 下创建 `SKILL.md` + 可选 `template/`、`example/` 目录即可。

### 9. 模型/工具兜底与循环防护

配置前缀 `agent.guard`，由 `AgentGuardConfig` 装配：

| 组件 | 位置 | 作用 |
|---|---|---|
| `ModelCallGuardInterceptor` | Model | 模型调用异常重试（指数退避 + 抖动）；判定"瞬时故障"后重试，最终失败返回兜底回答 |
| `ToolRetryInterceptor` | Tool | 工具调用失败重试，`retryOn` 复用 `ModelCallGuardInterceptor.isTransient`，超限后 `RETURN_MESSAGE` 优雅降级 |
| `ToolErrorInterceptor` | Tool | 将工具异常转换为消息返回模型，避免整轮运行失败 |
| `LoopGuardToolInterceptor` | Tool | 拦截重复/超量工具调用，防止死循环 |
| `ModelCallLimitHook` | MODEL hook | 限制单次运行的模型调用次数 |

`MAIN_AGENT_INSTRUCTION` 中同时内置"安全规则"（禁止破坏性命令、提权、数据外泄等）与"健壮性/终止规则"（避免重复调用、及时停止、错误优雅处理）以配合上述防护。

## 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 21 |
| **框架** | Spring Boot 4.1.0 · Spring AI 2.0 · Spring AI Alibaba 2.0.0-M1.1 |
| **LLM** | DeepSeek (deepseek-chat) |
| **Embedding** | DashScope（`EMBEDDING_KEY`） |
| **Agent** | ReactAgent + StateGraph + MysqlSaver（checkpoint 持久化） |
| **RAG** | QueryExpansion · DocumentRetrieval（向量 + BM25 + RRF）· LlmDocumentReranker |
| **向量库** | Milvus 2.4.15 (localhost:9090 → 容器 19530) |
| **数据库** | MySQL 8.0（`superassistant` + `superassistant_rag`） |
| **ORM** | MyBatis Plus |
| **文档解析** | PDF (ParagraphPdfDocumentReader) · Markdown · TXT/Code (TextReader) |
| **Token 估算** | jtokkit O200K BPE（失败时回退启发式估算） |
| **工具集成** | MCP (Model Context Protocol) — Client SSE ↔ Server WebMVC |
| **前端** | Vue 3 + Vite + Vue Router + Lucide Icons |
| **构建** | Maven 多模块 |

## 项目结构

```
SuperAssistant/
├── pom.xml                        # 父 POM，依赖管理（app + server 两模块）
├── docker-compose.yml             # MySQL + Milvus(+etcd+minio) 容器编排
├── frontend/                      # Vue 3 前端 :5173
│   ├── vite.config.js
│   └── src/
│       ├── App.vue
│       ├── main.js
│       ├── api/index.js           # axios 封装（chat / todos / knowledge）
│       ├── assets/style.css       # 全局样式与 CSS 变量
│       ├── router/index.js        # 路由：/ /knowledge
│       ├── components/ToastHost.vue
│       ├── utils/{markdown.js,toast.js}
│       └── views/
│           ├── ChatView.vue       # 对话界面（HITL 审批面板 + 计划模式开关 + 任务进度）
│           └── KnowledgeView.vue  # 知识库上传管理
├── app/                           # 主应用模块 :8080
│   ├── pom.xml
│   └── src/main/java/com/itajay/superassistant/
│       ├── SuperAssistantApplication.java  # @MapperScan + @ConfigurationPropertiesScan
│       ├── app/                   # Controller 层
│       │   ├── SuperAssistant.java       # 核心 API：对话 + HITL 审批
│       │   ├── TodoController.java       # Todo REST API
│       │   ├── RagController.java        # 知识库文件上传 (/knowledge/upload)
│       │   └── HealthController.java     # 健康检查 (/api/health)
│       ├── config/                # Spring 配置
│       │   ├── AgentConfig.java          # main-agent 组装 + Hooks/Interceptors 注册 + 系统提示词
│       │   ├── ModelConfig.java          # DeepSeek ChatModel / ChatClient
│       │   ├── McpConfig.java            # MCP Client 自动配置
│       │   ├── SaverConfig.java          # 双数据源 (superassistant + superassistant_rag) + MysqlSaver
│       │   ├── VectorConfig.java         # Milvus 向量库
│       │   ├── AgentGuardConfig.java     # 模型/工具异常兜底
│       │   ├── CompactProperties.java    # agent.compact 配置绑定
│       │   ├── CompactPropertiesConfig.java
│       │   ├── CheckpointRetentionProperties.java
│       │   ├── CheckpointRetentionConfig.java   # @EnableScheduling
│       │   └── ChatMessagePersistenceConfig.java
│       ├── compact/               # 四层上下文压缩系统
│       │   ├── CompactHook.java          # BEFORE_AGENT 入口，order=10000
│       │   ├── CompactThresholds.java    # 动态阈值（窗口 − 预留）× 比例
│       │   ├── CompactConfig.java        # 静态调优常量
│       │   ├── ContextCompactor.java     # L4：同步 LLM 摘要 + 上下文恢复
│       │   ├── ToolResultTruncator.java  # L1：工具结果截断 + 落盘
│       │   ├── ContextSnip.java          # L2：低价值消息裁剪
│       │   ├── MicroCompact.java         # L3：陈旧工具结果 payload 替换
│       │   ├── ToolCallIntegrity.java    # 工具调用/结果事务边界保护
│       │   ├── FileReadState.java        # 文件访问追踪（压缩后恢复用）
│       │   ├── TokenEstimator.java       # Token 估算接口
│       │   ├── BpeTokenEstimator.java    # jtokkit O200K BPE 实现
│       │   ├── HeuristicTokenEstimator.java  # 启发式回退
│       │   └── TokenBudgets.java         # token 预算内二分截断
│       ├── checkpoint/            # checkpoint 生命周期管理
│       │   └── CheckpointRetentionService.java  # 定时清理过期 checkpoint
│       ├── agent/                 # 专业子 Agent
│       │   ├── ResearchAgent.java        # 研究 Agent：搜索 + 素材收集
│       │   ├── WriterAgent.java          # 写作 Agent：结构化文档撰写
│       │   └── ReviewerAgent.java        # 审查 Agent：验收与修订判定
│       ├── plan/                  # 计划模式上下文
│       │   ├── PlanModeContext.java      # 计划模式 启用/激活 状态
│       │   └── PlanContextHolder.java    # ThreadLocal 上下文持有者
│       ├── prompt/                # 动态系统提示词
│       │   └── PromptSubmitHook.java     # ModelInterceptor：注入记忆 + 计划模式提示
│       ├── interceptor/           # 自定义拦截器
│       │   ├── PlanModeToolInterceptor.java   # 计划模式工具门控（三级）
│       │   ├── ModelCallGuardInterceptor.java # 模型调用异常兜底
│       │   └── LoopGuardToolInterceptor.java  # 工具循环防护
│       ├── rag/                   # RAG 检索增强管线
│       │   ├── RagAgent.java             # 子 Agent：知识检索回答
│       │   ├── RagHook.java              # 检索 → 注入 system prompt
│       │   ├── RagService.java           # 文档导入 + 切分 + 入库
│       │   ├── QueryExpansion.java       # 多路查询扩展
│       │   ├── QueryTransformation.java  # 查询压缩改写（当前未接入管线）
│       │   ├── DocumentRetrieval.java    # 向量 + BM25 混合检索
│       │   ├── Bm25Index.java            # BM25 倒排索引
│       │   ├── Bm25DocumentRetriever.java
│       │   ├── DocumentFusion.java       # RRF 融合 + 去重
│       │   ├── DocumentReranker.java     # 精排接口
│       │   ├── LlmDocumentReranker.java  # LLM Listwise 精排
│       │   ├── DocumentPostRetrieval.java# 去重 + 精排编排
│       │   ├── CustomJdbcChatMemoryRepository.java  # 自研对话记忆仓库
│       │   └── ModelMessagePersistenceHook.java     # AFTER_MODEL 回答持久化
│       ├── security/              # HITL 人工审批
│       │   ├── HITLHelper.java            # 审批决策工具（逐一/全部/编辑参数）
│       │   ├── ApprovalDecision.java      # 审批决策 DTO
│       │   ├── PendingApproval.java       # 待审批项 DTO（前端渲染）
│       │   └── PendingInterruptionStore.java  # 中断状态暂存
│       ├── tool/                  # Agent 工具
│       │   ├── TodoTool.java              # 待办 CRUD
│       │   ├── WebSearchTool.java         # DuckDuckGo + Jsoup
│       │   ├── FileOperationTool.java     # 文件读写/创建/删除/列表
│       │   ├── MemoryTool.java            # 持久记忆
│       │   ├── PlanTool.java              # 计划模式入口/出口
│       │   ├── TerminalTool.java          # 终端命令执行（HITL 审批）
│       │   ├── CreateAgentTool.java       # 动态创建子 Agent
│       │   ├── DateTimeTool.java          # 日期时间（未挂载）
│       │   └── WeatherTool.java           # 天气查询（未挂载）
│       ├── workflow/              # Agent 工作流
│       │   └── ResearchWriteWorkflow.java # 研究→写作顺序流水线
│       ├── service/               # 业务服务层
│       │   ├── TodoService.java
│       │   ├── WebSearchService.java
│       │   ├── FileOperationService.java
│       │   ├── TaskBreakdown.java
│       │   ├── AgentRunLogService.java        # Agent 运行日志（已实现，暂无调用方）
│       │   └── ChatMessagePersistenceService.java  # 回答持久化服务
│       ├── entity/                # MyBatis Plus 实体
│       │   ├── TodoTask.java
│       │   └── AgentRunLog.java
│       ├── mapper/                # MyBatis Mapper
│       │   ├── TodoTaskMapper.java
│       │   └── AgentRunLogMapper.java
│       ├── skill/                 # Agent Skills
│       │   └── SkillConfig.java           # ClasspathSkillRegistry
│       └── resources/
│           ├── application.yml
│           ├── sql/
│           │   ├── todo_task.sql          # todo_task 表
│           │   ├── plan.sql               # plan_task / plan_step / agent_run_log（计划子系统已移除）
│           │   ├── plan_agent_decouple.sql
│           │   └── custom_chat_memory.sql # custom_chat_memory 表
│           └── skills/
│               └── research_writing_skill/
│                   ├── SKILL.md
│                   ├── template/template.md
│                   └── example/*.md, *.png
│
└── server/                        # MCP Email Server :8081
    ├── pom.xml
    └── src/main/java/com/itajay/mcpemail/
        ├── McpEmailServerApplication.java
        └── tool/
            └── EmailMcpTools.java        # sendEmail / sendEmailBatch
```

测试位于 `app/src/test/`：覆盖压缩阈值/估算器/截断器/Snip/MicroCompact/文件状态、`PromptSubmitHook`、checkpoint 保留策略等。

## API 参考

### 核心对话

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/api/chat/{threadId}` | `{"message":"...", "mode":"Default|PlanMode"}` | `{type:"ANSWER", response:"..."}` 或 `{type:"INTERRUPTED", pendingApprovals:[...]}` |
| POST | `/api/chat/{threadId}/approve` | `{"decisions":[{"toolId","result","description?","editedArguments?"}]}` | 同 chat 响应格式 |

响应中额外包含 `planEnabled` / `planActive` 字段反映计划模式状态。

### 知识库

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/knowledge/upload` | multipart `file` | `{success:true, chunks:12, filename:"..."}` |

### Todo

任务系统是**线程维度**：每个任务都带 `thread_id`（由 Agent 工具从 `ToolContext` 绑定，不由模型提供），所有查询接口**必须**传 `threadId`，只返回该会话的任务。

| Method | Path | Request | Response |
|--------|------|---------|----------|
| GET | `/api/todos?threadId={id}` | query `threadId`（必填） | 该会话全部任务 `List<TodoTask>`（按 stepNo 排序） |
| GET | `/api/todos/pending?threadId={id}` | query `threadId`（必填） | 该会话 PENDING 任务 |
| GET | `/api/todos/overdue?threadId={id}` | query `threadId`（必填） | 该会话逾期的 PENDING 任务 |
| POST | `/api/todos/query` | `{threadId, status, priority, keyword}`（`threadId` 必填） | 过滤后的任务 |

缺 `threadId` 时接口返回 400 或业务错误（`Missing threadId`）。前端 `ChatView` 使用 `/api/todos?threadId=` 读取当前会话的任务；原独立的 `TodoView` 页面已移除，任务进展直接在对话界面展示。

### 健康检查

| Method | Path | Response |
|--------|------|----------|
| GET | `/api/health` | 健康状态 |

### MCP Email Server

独立进程，端口 8081，通过 Spring AI MCP SSE 自动暴露工具回调给主应用。

## 配置说明

**环境变量**（必须）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx    # DeepSeek API 密钥（对话/摘要/精排）
EMBEDDING_KEY=sk-xxxxxxxx       # DashScope API 密钥（向量化）
SMTP_PASSWORD=xxxxxxxx          # 邮箱 SMTP 密码（MCP Email Server）
# 可选：SMTP_PORT（默认 465）
```

**`app/application.yml` 关键配置**：

```yaml
spring.ai.deepseek.api-key: ${DEEPSEEK_API_KEY}
spring.ai.dashscope.api-key: ${EMBEDDING_KEY}
spring.ai.mcp.client.sse.connections.email-server.url: http://localhost:8081
spring.datasource.url: jdbc:mysql://localhost:3306/superassistant
spring.datasource.username: root
spring.datasource.password: 123456

# RAG 检索与精排
rag:
  retrieval:
    vector: { top-k: 5, similarity-threshold: 0.7 }
    bm25:   { top-k: 10, index-path: ./data/bm25-index }
    fusion: { top-k: 20, rrf-k: 60 }
  rerank:
    enabled: true
    top-k: 5

# 上下文压缩：动态阈值 =（模型窗口 − 输出预留 − 系统/工具预留）× 比例
agent.compact:
  model-context-window-tokens: 128000   # deepseek-chat 上下文窗口
  output-reserve-tokens: 8000           # 为模型输出预留
  system-prompt-reserve-tokens: 12000   # 为系统提示词/工具定义/注入上下文预留
  warning-ratio: 0.70                   # 触发 L1–L3
  critical-ratio: 0.90                  # 触发 L4（须 > warning-ratio）
  cooldown-calls: 5                     # 两次全量压缩间的最少调用次数
  snapshot-keep: 5                      # 每线程保留的压缩快照数

# 模型/工具异常兜底与循环防护
agent.guard:
  model-retry:  { max-attempts: 3, initial-delay-ms: 500, max-delay-ms: 8000, backoff-multiplier: 2.0 }
  tool-retry:   { max-retries: 2,  initial-delay-ms: 300, max-delay-ms: 3000, backoff-factor: 2.0 }
  loop:         { max-model-calls-per-run: 15, max-model-calls-per-thread: 60,
                  max-total-tool-calls: 60, max-identical-calls: 3 }

# checkpoint 保留策略（定时清理）
agent.checkpoint-retention:
  retention-days: 30
  max-per-thread: 20
  active-grace-hours: 24
  cron: "0 0 3 * * *"
```

**外依赖启动**：

```
Milvus    → localhost:9090
MySQL     → localhost:3306（database: superassistant；另需 superassistant_rag）
```

也可直接用项目根目录的 Docker Compose 启动 MySQL + Milvus（含 etcd / minio 依赖）：

```bash
docker compose up -d
docker compose ps
```

**初始化数据库表**：

```bash
mysql -u root -p superassistant < app/src/main/resources/sql/todo_task.sql
mysql -u root -p superassistant < app/src/main/resources/sql/custom_chat_memory.sql
# plan_task / plan_step / agent_run_log（计划子系统已移除，按需导入）
mysql -u root -p superassistant < app/src/main/resources/sql/plan.sql
mysql -u root -p superassistant < app/src/main/resources/sql/plan_agent_decouple.sql
```

## 快速启动

```bash
# 1. 确保 Milvus + MySQL 已启动（可用 docker compose up -d）

# 2. 启动 MCP Email Server
cd server
mvn spring-boot:run    # → :8081

# 3. 启动主应用
cd app
mvn spring-boot:run    # → :8080

# 4. 前端
cd frontend
npm install && npm run dev   # → :5173

# 5. 测试
curl -X POST http://localhost:8080/api/chat/test-001 \
  -H "Content-Type: application/json" \
  -d '{"message":"帮我搜索最新的大模型新闻"}'
```

## HITL 审批流程（开发参考）

审批触发时，`SuperAssistant.chat()` 返回的数据结构：

```json
{
  "type": "INTERRUPTED",
  "threadId": "test-001",
  "message": "High-risk operations require approval",
  "planEnabled": false,
  "planActive": false,
  "pendingApprovals": [
    {
      "toolId": "call_abc123",
      "toolName": "sendEmail",
      "arguments": "{\"to\":\"a@x.com\",\"subject\":\"hello\",\"body\":\"world\",\"isHtml\":false,\"cc\":\"\"}",
      "description": "邮件发送需要人工审批，发送前可编辑收件人/主题/正文"
    }
  ]
}
```

前端提交审批：

```json
POST /api/chat/test-001/approve
{
  "decisions": [
    {
      "toolId": "call_abc123",
      "result": "EDITED",
      "editedArguments": "{\"to\":\"b@x.com\",\"subject\":\"修改后的标题\",\"body\":\"新正文\",\"isHtml\":true,\"cc\":\"c@x.com\"}"
    }
  ]
}
```

`result` 可选值：`APPROVED` / `REJECTED` / `EDITED`。`REJECTED` 时可选填 `description` 描述拒绝理由；`EDITED` 时必填 `editedArguments`。

## Database

| 表 | 用途 |
|---|------|
| `todo_task` | 待办事项，MyBatis Plus 管理 |
| `custom_chat_memory` | 对话渲染记录，`CustomJdbcChatMemoryRepository` 管理 |
| `GRAPH_THREAD` / `GRAPH_CHECKPOINT` | MysqlSaver 图状态快照，`CheckpointRetentionService` 定时清理 |
| `plan_task` / `plan_step` | 计划子系统遗留表（对应代码已移除，可忽略） |
| `agent_run_log` | Agent 运行日志（`AgentRunLogService` 已实现，暂无调用方） |

## 注意事项

- `SaverConfig` 配置了两个 DataSource Bean：`dataSource`（`superassistant` 库，`@Primary`）与 `ragDataSource`（`superassistant_rag` 库），后者供 `CustomJdbcChatMemoryRepository` 使用；数据库名均为**小写**。
- **任务系统为线程维度**：`todo_task.thread_id` 是任务的归属键，`step_no` 编号与 `step_key` 去重都按会话计算。Agent 工具（`todoWrite` / `createTask` / `queryTodos` / `getReadyTasks`）一律从 `ToolContext` 的 `threadId` 绑定，**不由模型传入**；REST 查询接口必须带 `threadId`，缺省返回错误。
- `HumanInTheLoopHook` 的恢复依赖 `RunnableConfig.Builder.addHumanFeedback(InterruptionMetadata)`，不要手动调用 `CompiledGraph.updateState()`。
- MCP email 工具名称必须与 `@Tool` 注解暴露的名称一致：`sendEmail` / `sendEmailBatch`。
- **压缩阈值不是常量**：由 `agent.compact` 配置经 `CompactThresholds` 动态派生（默认 75.6K / 97.2K）；旧的 `CONTEXT_WARNING_TOKENS` / `CONTEXT_CRITICAL_TOKENS` 已移除。
- **hook 返回 `messages` 必须包 `ReplaceAllWith`**：框架 `messages` 键默认策略为 `AppendStrategy`，返回裸列表会把历史追加一份。
- **`PromptSubmitHook` 与 `PlanModeToolInterceptor` 均为 `ModelInterceptor`（不是 hook）**：通过 `AgentConfig` 的 `.interceptors(...)` 注册，动态系统提示词 / 工具裁剪只作用于 `ModelRequest`，不进入 `messages`、不写入 checkpoint。
- `PromptSubmitHook` / `PlanModeToolInterceptor` 依赖请求 context 中的 `threadId`（由控制器写入 `RunnableConfig` metadata / `PlanContextHolder`）；缺失时仅跳过对应逻辑。
- `CompactHook` 必须在 `BEFORE_AGENT` hook 中最后执行（`getOrder() = 10000`），以保证 token 估算覆盖最终消息列表。
- `PlanModeToolInterceptor` 在计划模式激活时隐藏 `writeFile`、`deleteFile`、`executeCommand`、`sendEmail`、`sendEmailBatch`；未启用计划模式时隐藏 `enterPlanMode` / `exitPlanMode`。
- `compaction` 的 LRU 缓存（文件访问状态、线程调用计数）上限为 `CompactConfig.FILE_STATE_CACHE_MAX_ENTRIES`（100）。
- 本地运行测试若使用 JDK 25，Mockito 会因 MockMaker 与新版 JDK 不兼容而报错（`AgentRunLogServiceTest` 使用 mock）；建议以 JDK 21 运行测试。

维护者：[itajay](mailto:author@itajay.com)
