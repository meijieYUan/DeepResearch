# DeepResearchAgent · 课题调研智能体

> 从一个课题出发，交付一份**可复核**的调研报告。

**DeepResearchAgent** 是一个基于 Spring AI Alibaba 的多智能体深度调研系统。给它一个研究方向，它会像一名研究助理那样工作：检索并筛选论文、逐篇精读并核对公式、撰写对比型调研文档——最后由独立的审查 Agent 对照 PDF 原文验收，未通过不放行。

每个结论都有出处，每份细节都可以打开复核。这不是一个"生成文本"的系统，而是一条**带质量闸门的调研流水线**。

---

## 工作方式

```
        课题（如「智能体的上下文管理」）
                      │
   research-agent     ▼  多站检索 → 候选池 ≈ 目标数 × 3 → 摘要级判定 → 下载
   analyst-agent      ▼  逐篇精读（4 路并行）→ 四维信息 + 公式逐字抄录 → 落盘
   │                     截取方法框架图 / 关键机制图（图注与页码逐张核对）
   writer-agent       ▼  按模板撰写对比型文档：清单 / 逐篇定位 / 横向对比 / 汇总表
   reviewer-agent     ▼  对照原文验收 → 按问题归属回退修订（检索 / 精读 / 写作）
                      ▼
              通过审查 → 交付并如实汇报遗留问题
```

产出不是一份黑盒文本。精读细节以独立文件落盘，公式逐字来自 PDF 原文，配图从论文截取并标注图号页码——**文档负责归纳对比，文件负责可查证**。

## 一次真实调研的产出

以下全部内容由系统自动生成，未经人工修改，原始文件就在本仓库的 `investigation/` 目录中。

**课题**：智能体的上下文管理（2023–2026，6 篇论文，中文文档）

**最终报告** · [`investigation/智能体的上下文管理/document/智能体的上下文管理.md`](investigation/智能体的上下文管理/document/智能体的上下文管理.md)——对比汇总表节选：

| 论文 | 作者主要想解决的问题 | 方法框架 | 关键机制 | Training-free |
| ---- | -------------------- | -------- | -------- | ------------- |
| MemGPT | 固定上下文窗口无法承载长对话 | Main Context + Queue Manager + Function Executor | 用函数调用在分层记忆间分页，由 LLM 自主搬运 | 是 |
| LLMLingua | 长提示词导致推理成本过高 | Budget Controller + ITPC + Distribution Alignment | 按组件分配压缩率 + 迭代 token 级压缩 | 否 |
| SelectiveContext | 输入上下文自身固有冗余未被处理 | 自信息计算 + lexical unit 合并 + 百分位过滤 | 以自信息度量冗余并按百分位剪枝 | 是 |

**逐篇精读** · [`analysis/MemGPT.md`](investigation/智能体的上下文管理/analysis/MemGPT.md)——每篇一份四维分析，公式逐字抄录、来源等级如实标注。报告中对应的逐篇定位与系统截取的原文框架图：

针对固定上下文窗口无法承载长对话的问题，MemGPT 借鉴操作系统的虚拟内存分页，把 prompt tokens 视为"主存"、外部数据库视为"磁盘"，通过函数调用让模型自主搬运数据——"**用系统架构而非模型改造换取长上下文**"这一路线的开创性工作。

![MemGPT 方法框架（原文 Figure 3）](investigation/智能体的上下文管理/analysis/figures/MemGPT_Fig3.png)

**产出目录**（换一个课题名，就是同样结构的一份新报告；已下载论文与已完成的精读自动复用）：

```
investigation/智能体的上下文管理/
├── papers/       # 6 篇论文 PDF 原件
├── analysis/     # 6 份四维精读 + figures/ 9 张原文截取图
└── document/     # 调研报告（约 18 KB）
```

## 核心设计

| 设计 | 说明 |
| ---- | ---- |
| **带审查的闭环** | reviewer 对照原文逐字核对公式与引用；审查不通过按问题归属回退到检索 / 精读 / 写作，最多 2 轮，仍未通过则如实上报 |
| **全程流式可见** | 接口即 SSE：论文下载、逐篇精读进度与答案的逐 token 输出在同一条连接上，无黑盒等待 |
| **四路并行精读** | Java 驱动的有界并行，精读吞吐提升约 4 倍，单篇失败自动重试、不中断整批 |
| **上下文工程** | 四层上下文压缩 + 工具循环防护 + Plan Mode 人工审批，支撑小时级长任务 |

完整架构、工具链、提示词工程与实测踩坑记录见 **[docs/TECHNICAL.md](docs/TECHNICAL.md)**。

## 快速开始

```bash
# 后端（需配置 LLM API Key、MySQL 与 MCP 邮件服务）
./mvnw -pl app spring-boot:run            # :8080

# 前端
cd frontend && npm install && npm run dev # :3000，代理 /api → :8080
```

发一次调研——接口本身就是 SSE 流：

```bash
curl -N -X POST http://localhost:8080/api/chat/{threadId} \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"message":"请调研课题：多主体图像生成的布局控制：目标论文数 4-8 篇，中文文档"}'
```
