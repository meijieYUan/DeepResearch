package com.itajay.superassistant.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itajay.superassistant.config.AgentConfig;
import com.itajay.superassistant.workflow.ReviewResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Focused resource contracts, not snapshots of the guides or live-model tests. */
class SkillContractTest {

    private static final String ROOT = "/skills/research_writing_skill/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void skillReviewExampleHasExactlyThePayloadAndSevenIssueFields() throws IOException {
        String reviewSection = section(resource("SKILL.md"), "## 第五步：质量检查", "## 第六步：");
        // Jackson accepts either LF or CRLF whitespace; do not make the fence match platform-specific.
        String json = section(reviewSection, "```json", "```");
        JsonNode example = MAPPER.readTree(json);
        ReviewResult.ReviewIssue issue = new ReviewResult.ReviewIssue(
                "MAJOR", "ANALYST", "analysis/X.md", "F6", "问题", "原文", "修订建议");
        JsonNode payloadShape = MAPPER.valueToTree(new ReviewResult.Payload("REVISE", "总体意见", List.of(issue)));

        assertThat(fields(example)).containsExactlyInAnyOrderElementsOf(fields(payloadShape));
        assertThat(fields(example)).containsExactlyInAnyOrder("verdict", "summary", "issues");
        assertThat(example.path("verdict").isTextual()).isTrue();
        assertThat(example.path("verdict").asText()).isIn("PASS", "REVISE");
        assertThat(example.path("summary").isTextual()).isTrue();
        assertThat(example.path("issues").isArray()).isTrue();
        assertThat(example.path("issues").get(0).path("level").asText()).isIn("BLOCKER", "MAJOR", "MINOR");
        assertThat(example.path("issues").get(0).path("target").asText()).isIn("WRITER", "ANALYST", "RESEARCHER");
        assertThat(fields(example.path("issues").get(0)))
                .containsExactlyInAnyOrderElementsOf(fields(payloadShape.path("issues").get(0)))
                .hasSize(7);
        assertThat(MAPPER.readValue(json, ReviewResult.Payload.class).issues()).hasSize(1);
        assertThat(reviewSection).contains("ReviewResult.Payload", "Java", "派生", "不输出 `approved`");
        assertThat(section(resource("references/review-guide.md"), "**输出格式**", "**`target` 决定"))
                .contains("ReviewResult.Payload", "每条七项", "不要输出 `approved`");
    }

    @Test
    void everyRoleUsesAFourFigureCeilingAndAllowsZero() throws IOException {
        String skill = section(resource("SKILL.md"), "## 第三步：", "## 第四步：");
        String analysis = section(resource("references/analysis-guide.md"), "### 4.4 张数与位置", "### 4.5");
        String writing = section(resource("references/writing-guide.md"), "### 3.1 图片怎么放", "## 4.");
        String review = resource("references/review-guide.md");
        for (String policy : List.of(skill, analysis, writing, row(review, "A12"), row(review, "P4"))) {
            assertThat(policy).contains("0-4 张", "上限 4 张");
        }
        assertThat(section(resource("references/writing-guide.md"), "## 9. 交付前自检清单", null))
                .contains("不超过 4 张").doesNotContain("不超过 2 张");
        assertThat(section(resource("references/analysis-guide.md"), "## 7. 交付前自检清单", null))
                .contains("0-4 张", "上限 4 张");
        assertThat(section(resource("example/example.md"), "这一篇的核心机制", "![MUSE 中"))
                .contains("0-4 张", "上限 4 张").doesNotContain("至多两张");
    }

    @Test
    void abstractAndSnippetRemainFigureFreeAndMissingFiguresStayHonest() throws IOException {
        String analysis = section(resource("references/analysis-guide.md"), "## 3. 来源等级限制", "## 5. 落盘");
        assertThat(analysis).contains("`ABSTRACT_ONLY` 与 `SNIPPET`", "也不得截图", "一张都不截",
                "图见原文 Fig.", "不编造图号或页码");
        String review = resource("references/review-guide.md");
        assertThat(row(review, "A12")).contains("`ABSTRACT_ONLY` / `SNIPPET`", "没有图");
        assertThat(row(review, "P4")).contains("没有合格图", "诚实说明", "不编造图号或页码");
    }

    @Test
    void summaryColumnsCanTruthfullyShareValuesIncludingTrainingFree() throws IOException {
        String writing = section(resource("references/writing-guide.md"), "## 5. 汇总表", "## 6.");
        assertThat(writing).contains("允许同值", "Training-free", "是", "否", "不得编造差异")
                .doesNotContain("需要重写");
        assertThat(row(resource("references/review-guide.md"), "C8"))
                .contains("允许同值", "Training-free", "不得编造差异")
                .doesNotContain("两篇取值雷同即");
        assertThat(section(resource("references/writing-guide.md"), "## 9. 交付前自检清单", null))
                .contains("允许同值", "不得编造差异")
                .doesNotContain("每列体现论文间差异");
        String summaryExample = section(resource("example/example.md"), "## 2.4 对比汇总表", "# 3.");
        // The example intentionally retains two plain “否” cells; equal facts are not a defect.
        assertThat(row(summaryExample, "MS-Diffusion")).endsWith("| 否 |");
        assertThat(row(summaryExample, "MUSE")).endsWith("| 否 |");
    }

    @Test
    void chineseParaphrasesDoNotPretendToBeOriginalLanguageQuotes() throws IOException {
        String analysis = resource("references/analysis-guide.md");
        String intent = section(analysis, "### 2.1 论文概要与作者意图", "### 2.2");
        String mechanism = section(analysis, "### 2.3 关键机制与创新点", "### 2.4");
        assertThat(intent).contains("原语言", "普通中文释义")
                .doesNotContain("> 在不对每个主体");
        assertThat(mechanism).contains("原语言", "普通中文释义")
                .doesNotContain("> 用 bbox 限制 subject attention");
        assertThat(section(analysis, "## 5. 落盘", "## 6."))
                .contains("可引用原句", "原语言");
    }

    @Test
    void quoteChecksExcludeMetadataCaptionsAndPathsButKeepOriginalQuoteVerification() throws IOException {
        String review = resource("references/review-guide.md");
        assertThat(row(review, "F6")).contains("作者引文", "原语言", "逐字比对",
                "元数据", "图注", "路径", "不做逐字 F6");
        assertThat(row(review, "L5")).contains("元数据", "图注", "路径", "不冒充作者引文");
        String writing = resource("references/writing-guide.md");
        assertThat(row(writing, "blockquote")).contains("原语言", "元数据", "图注", "路径");
        assertThat(section(writing, "## 8. 红线", "## 9."))
                .contains("原语言", "普通中文释义", "不冒充作者引文");
    }

    @Test
    void reviewAndRevisionLimitsAreNotTwoRedosAndFormatRepairIsSeparate() throws IOException {
        String skill = resource("SKILL.md");
        String review = resource("references/review-guide.md");
        for (String loop : List.of(
                section(skill, "## 第六步：判定与修订回边", "## 全局红线"),
                section(review, "## 0.1 单篇精读模式", "## 1. 审查输入"),
                section(review, "## 5. 修订循环", "## 6. 审查执行顺序"))) {
            assertThat(loop).contains("最多 2 轮内容审查", "最多 1 次内容修订", "每份产物",
                    "至多即时补审 1 次", "不消耗内容审查或修订额度");
            assertThat(loop).doesNotContain("浪费一整轮修订", "然后进入下一轮", "2 轮修订后");
        }
        String singlePaperInstruction = section(AgentConfig.MAIN_AGENT_INSTRUCTION,
                "- **Single-paper deep read**", "- **Knowledge questions**");
        assertThat(singlePaperInstruction).contains("at most 2 content reviews", "1 content revision")
                .doesNotContain("2 redo");
        assertThat(section(resource("references/writing-guide.md"), "### 修订轮次", "## 2."))
                .contains("旧稿正文", "工作流", "补审", "不要改动正确产物")
                .doesNotContain("按 §9 完整自检一遍再提交");
    }

    @Test
    void formatRecoveryOnlyCoversParseFailuresAndPreservesContentBlockers() throws IOException {
        for (String recovery : List.of(
                section(resource("SKILL.md"), "**审查输出格式异常", "交付时向用户汇报"),
                section(resource("references/review-guide.md"), "**审查输出格式异常时的处置**", "## 6."))) {
            assertThat(recovery).contains("`PARSE`", "空输出", "坏 JSON", "未知或缺失 `verdict`",
                    "空 `REVISE`", "缺失 `problem`", "至多即时补审 1 次",
                    "不消耗内容审查或修订额度", "`PASS + BLOCKER`", "保留有证据的 BLOCKER",
                    "走内容修订而非格式补审", "`target`、`location`、`fix` 缺失",
                    "兼容口径", "不单独视为 `PARSE`");
            assertThat(recovery).doesNotContain("契约字段缺失或判定矛盾");
        }
    }

    @Test
    void templateMissingFiguresDoNotRequireInventedFigureNumbersOrPages() throws IOException {
        String figureRule = section(resource("template/template.md"), "<!-- 图片规则：", "-->");
        assertThat(figureRule).contains("每篇至多 4 张", "方法框架图或关键机制图",
                "不适用或未取得合格图", "明确说明", "图片语法整行删掉", "诚实说明",
                "只有已核实图号与页码才写", "图见原文 Fig.", "不编造图号或页码");
        assertThat(figureRule).doesNotContain("改为一行「图见原文");
    }

    @Test
    void retryKeepsTheOriginalBriefAndFourDimensionsWithNotApplicableReasons() throws IOException {
        String writing = resource("references/writing-guide.md");
        assertThat(section(writing, "## 1. 输入契约", "## 2."))
                .contains("原始 brief", "requirements", "analyzePapers", "reanalyzePaper", "旧稿正文");
        assertThat(section(resource("references/analysis-guide.md"), "## 5. 落盘", "## 6."))
                .contains("## 1. 论文概要与作者意图", "## 2. 方法框架", "## 3. 关键机制与创新点", "## 4. 训练目标");
        String review = resource("references/review-guide.md");
        assertThat(row(review, "A3")).contains("四维齐全", "不适用者必须写明原因");
        assertThat(section(review, "> **S7 的判定口径**", "### 2.2"))
                .contains("不适用的原因", "即视为通过");
        assertThat(section(writing, "### 结构硬性规则", "## 3."))
                .contains("不适用", "原因", "不要增删章节");
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = SkillContractTest.class.getResourceAsStream(ROOT + path)) {
            assertThat(stream).as("classpath resource %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Extract just the relevant rule/section, so unrelated prose can evolve freely. */
    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        assertThat(from).as("section start %s", start).isGreaterThanOrEqualTo(0);
        from += start.length();
        int to = end == null ? text.length() : text.indexOf(end, from);
        assertThat(to).as("section end %s", end).isGreaterThanOrEqualTo(from);
        return text.substring(from, to);
    }

    private static String row(String text, String key) {
        return text.lines().filter(line -> line.startsWith("| " + key + " |"))
                .findFirst().orElseThrow(() -> new AssertionError("Missing table row: " + key));
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
