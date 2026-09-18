package com.itajay.superassistant.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the reviewer's verdict parsing.
 *
 * <p>The property that matters is asymmetric: a malformed review must never be
 * read as an approval. Passing an unverified document is the failure mode this
 * whole workflow exists to prevent, so every unparseable input has to come back
 * as a blocking result rather than a throw or a silent pass.</p>
 */
class ReviewResultTest {

    @Test
    void parsesABareJsonObject() {
        ReviewResult result = ReviewResult.parse("""
                {"approved": true, "verdict": "PASS", "summary": "整体质量良好",
                 "issues": [{"level": "MINOR", "target": "WRITER", "location": "# 1.",
                             "problem": "空行缺失", "fix": "补空行"}]}""");

        assertThat(result.approved()).isTrue();
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.summary()).isEqualTo("整体质量良好");
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).isBlocker()).isFalse();
    }

    @Test
    void parsesThroughAJsonCodeFence() {
        ReviewResult result = ReviewResult.parse("""
                好的，我已完成审查：

                ```json
                {"approved": false, "verdict": "REVISE", "summary": "公式有误",
                 "issues": [{"level": "BLOCKER", "target": "WRITER", "location": "# 2.4",
                             "problem": "公式符号抄错", "fix": "按材料原文改正"}]}
                ```

                以上就是我的审查结论。""");

        assertThat(result.approved()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).isBlocker()).isTrue();
    }

    @Test
    void parsesWithProseBeforeAndAfter() {
        ReviewResult result = ReviewResult.parse(
                "审查如下 { \"verdict\": \"PASS\", \"approved\": true, \"issues\": [] } 完毕");

        assertThat(result.approved()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void trailingProseContainingABraceDoesNotCorruptThePayload() {
        // Slicing from the first '{' to the last '}' would swallow this trailing brace
        // and produce invalid JSON. The scan must stop at the balanced close.
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "PASS", "approved": true, "summary": "ok", "issues": []}
                备注：汇总表用了 { } 包裹的占位符。""");

        assertThat(result.approved()).isTrue();
        assertThat(result.summary()).isEqualTo("ok");
    }

    @Test
    void bracesInsideStringsDoNotConfuseTheScan() {
        ReviewResult result = ReviewResult.parse(
                "{\"verdict\": \"PASS\", \"approved\": true, \"summary\": \"文中出现 { 与 } 符号\", \"issues\": []}");

        assertThat(result.approved()).isTrue();
        assertThat(result.summary()).contains("{ 与 }");
    }

    @Test
    void escapedQuoteInsideAStringDoesNotEndItEarly() {
        ReviewResult result = ReviewResult.parse(
                "{\"verdict\": \"PASS\", \"approved\": true, \"summary\": \"他说\\\"可以\\\"\", \"issues\": []}");

        assertThat(result.approved()).isTrue();
        assertThat(result.summary()).contains("可以");
    }

    @Test
    void missingIssuesArrayIsTreatedAsNoFindings() {
        ReviewResult result = ReviewResult.parse("{\"verdict\": \"PASS\", \"approved\": true}");

        assertThat(result.approved()).isTrue();
        assertThat(result.issuesOrEmpty()).isEmpty();
    }

    @Test
    void contradictoryVerdictAndBlockerResolvesToRevise() {
        // A BLOCKER cannot coexist with PASS. Take the conservative reading.
        ReviewResult result = ReviewResult.parse("""
                {"approved": true, "verdict": "PASS", "summary": "ok",
                 "issues": [{"level": "BLOCKER", "target": "WRITER", "problem": "虚构引用"}]}""");

        assertThat(result.approved()).isFalse();
        assertThat(result.verdict()).isEqualTo("REVISE");
    }

    @Test
    void missingVerdictIsNotAnApproval() {
        ReviewResult result = ReviewResult.parse("{\"summary\": \"看起来还行\", \"issues\": []}");

        assertThat(result.approved()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).isBlocker()).isTrue();
    }

    @Test
    void unparseableOutputIsBlockingRatherThanSilentlyPassing() {
        for (String garbage : new String[]{
                "审查完成，文档质量良好。",
                "{}",
                "{not json at all",
                "",
                "   ",
                null}) {
            ReviewResult result = ReviewResult.parse(garbage);

            assertThat(result.approved())
                    .as("input %s must not be read as an approval", garbage)
                    .isFalse();
            assertThat(result.issues()).isNotEmpty();
            assertThat(result.issues().get(0).isBlocker()).isTrue();
            assertThat(result.summary()).contains("无法解析");
        }
    }

    @Test
    void routesFindingsToTheAgentThatCanFixThem() {
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "混合问题",
                 "issues": [
                   {"level": "BLOCKER", "target": "RESEARCHER", "problem": "关键论文未下载"},
                   {"level": "MAJOR", "target": "WRITER", "problem": "blockquote 非原文"},
                   {"level": "MINOR", "target": "WRITER", "problem": "空行缺失"}
                 ]}""");

        assertThat(result.needsResearcher()).isTrue();
        assertThat(result.researcherIssues()).hasSize(1);
        assertThat(result.writerIssues()).hasSize(2);
        assertThat(result.renderFor(ReviewResult.Target.RESEARCHER)).contains("关键论文未下载");
        assertThat(result.renderFor(ReviewResult.Target.WRITER))
                .contains("blockquote 非原文")
                .doesNotContain("关键论文未下载");
    }

    @Test
    void writerOnlyFindingsDoNotTriggerAResearchPass() {
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "仅写法问题",
                 "issues": [{"level": "BLOCKER", "target": "WRITER", "problem": "公式抄错"}]}""");

        assertThat(result.needsResearcher()).isFalse();
    }

    @Test
    void missingTargetDefaultsToWriter() {
        // Defaulting to WRITER is the cheaper mistake: a rewrite costs less than
        // re-running the research pass, and a wrong RESEARCHER routing wastes the
        // most expensive stage of the workflow.
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "x",
                 "issues": [{"level": "MAJOR", "problem": "无 target 字段"}]}""");

        assertThat(result.needsResearcher()).isFalse();
        assertThat(result.writerIssues()).hasSize(1);
    }

    @Test
    void targetMatchingIsCaseInsensitiveAndAcceptsPrefixes() {
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "x",
                 "issues": [
                   {"level": "MAJOR", "target": "researcher", "problem": "小写"},
                   {"level": "MAJOR", "target": "RESEARCH_AGENT", "problem": "带前缀"}
                 ]}""");

        assertThat(result.researcherIssues()).hasSize(2);
    }

    @Test
    void routesAnalystFindingsToTheAnalyst() {
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "公式与原文不符",
                 "issues": [{"level": "BLOCKER", "target": "ANALYST",
                             "location": "analysis/LCP-Diffusion.md 的 3. 关键机制与创新点",
                             "problem": "注意力公式的求和范围与原文不一致",
                             "fix": "按原文 Eq.(4) 改正求和下标"}]}""");

        assertThat(result.needsAnalyst()).isTrue();
        assertThat(result.analystIssues()).hasSize(1);
        assertThat(result.renderFor(ReviewResult.Target.ANALYST))
                .contains("求和范围与原文不一致")
                .contains("Eq.(4)");
    }

    @Test
    void analystFindingsDoNotTriggerAResearchPassNorAWriterOnlyRewrite() {
        // A wrong formula in an analysis file is fixed by re-reading that one paper.
        // Routing it to RESEARCHER would waste the most expensive stage; routing it to
        // WRITER leaves the bad analysis on disk, so the next round reports it again.
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "x",
                 "issues": [{"level": "BLOCKER", "target": "ANALYST", "problem": "公式抄错"}]}""");

        assertThat(result.needsResearcher()).isFalse();
        assertThat(result.writerIssues()).isEmpty();
        assertThat(result.renderFor(ReviewResult.Target.WRITER)).isEmpty();
        assertThat(result.renderFor(ReviewResult.Target.RESEARCHER)).isEmpty();
    }

    @Test
    void eachTargetRendersInIsolation() {
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "三类问题混杂",
                 "issues": [
                   {"level": "BLOCKER", "target": "RESEARCHER", "problem": "论文不相关"},
                   {"level": "BLOCKER", "target": "ANALYST", "problem": "来源等级标错"},
                   {"level": "MAJOR", "target": "WRITER", "problem": "复述了四维正文"}
                 ]}""");

        assertThat(result.needsResearcher()).isTrue();
        assertThat(result.needsAnalyst()).isTrue();
        assertThat(result.researcherIssues()).hasSize(1);
        assertThat(result.analystIssues()).hasSize(1);
        assertThat(result.writerIssues()).hasSize(1);

        assertThat(result.renderFor(ReviewResult.Target.RESEARCHER))
                .contains("论文不相关")
                .doesNotContain("来源等级标错", "复述了四维正文");
        assertThat(result.renderFor(ReviewResult.Target.ANALYST))
                .contains("来源等级标错")
                .doesNotContain("论文不相关", "复述了四维正文");
        assertThat(result.renderFor(ReviewResult.Target.WRITER))
                .contains("复述了四维正文")
                .doesNotContain("论文不相关", "来源等级标错");
    }

    @Test
    void analystTargetAcceptsTheNamingVariantsAModelMightEmit() {
        for (String target : new String[]{"ANALYST", "analyst", "ANALYSIS", "analyst-agent"}) {
            ReviewResult result = ReviewResult.parse("""
                    {"verdict": "REVISE", "approved": false, "summary": "x",
                     "issues": [{"level": "MAJOR", "target": "%s", "problem": "x"}]}"""
                    .formatted(target));

            assertThat(result.analystIssues())
                    .as("target %s must route to the analyst", target)
                    .hasSize(1);
            assertThat(result.needsResearcher()).isFalse();
        }
    }

    @Test
    void unknownTargetStaysOnTheCheapestRoute() {
        // "REVIEWER" and friends are not routing destinations. They must not be
        // mistaken for ANALYST, whose extra step (re-reading a paper) is only worth
        // paying when the review actually blames the analysis.
        ReviewResult result = ReviewResult.parse("""
                {"verdict": "REVISE", "approved": false, "summary": "x",
                 "issues": [{"level": "MAJOR", "target": "REVIEWER", "problem": "x"}]}""");

        assertThat(result.needsAnalyst()).isFalse();
        assertThat(result.needsResearcher()).isFalse();
        assertThat(result.writerIssues()).hasSize(1);
    }

    // ── the reviewer's output contract ──

    @Test
    void theSchemaHandedToTheReviewerCanBeGeneratedFromTheWireType() {
        // The reviewer agent is built with outputType(Payload.class), so this conversion runs at
        // context startup. A record containing a list of records is exactly the shape where schema
        // generation can fail, and it would fail there rather than here — taking the whole
        // application down on boot instead of failing a test.
        String format = new org.springframework.ai.converter.BeanOutputConverter<>(
                ReviewResult.Payload.class).getFormat();

        assertThat(format).isNotBlank();
        // The reader and the instruction have to name the same things, or the model is told to
        // produce one shape and the workflow tries to read another.
        assertThat(format).contains("verdict").contains("summary").contains("issues")
                .contains("level").contains("target").contains("problem");
        // Derived by the workflow from the verdict, so it must not be asked for.
        assertThat(format).doesNotContain("approved");
    }

    @Test
    void anApprovedFieldFromTheModelDoesNotOverrideTheVerdict() {
        // The model may still emit this — an older guide, a habit, a stale prompt. It is not part
        // of the payload type, so it is ignored and the verdict decides. The dangerous reading
        // would be the optimistic one: a reviewer that hedges by writing both must not be able to
        // talk the workflow into PASS.
        ReviewResult result = ReviewResult.parse(
                "{\"approved\": true, \"verdict\": \"REVISE\", \"summary\": \"公式有误\", \"issues\": []}");

        assertThat(result.approved()).isFalse();
        assertThat(result.verdict()).isEqualTo("REVISE");
    }
}
