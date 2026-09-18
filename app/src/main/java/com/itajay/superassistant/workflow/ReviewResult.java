package com.itajay.superassistant.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/**
 * The reviewer agent's verdict, parsed from its JSON output.
 *
 * <p>Each issue names the agent that can actually fix it, which is what lets the
 * workflow skip a full re-research when only the prose is wrong.</p>
 *
 * @param approved true only when the verdict is an explicit PASS
 * @param verdict  the raw verdict string, kept for reporting
 * @param summary  overall comments
 * @param issues   the findings, in the reviewer's order (BLOCKER first)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewResult(
        boolean approved,
        String verdict,
        String summary,
        List<ReviewIssue> issues) {

    /** Which agent a finding should be routed back to. */
    public enum Target {
        /** The material is wrong or missing: only a new research pass can fix it. */
        RESEARCHER,
        /** A single paper's analysis is wrong: re-read that paper, then rewrite the document. */
        ANALYST,
        /** Material and analyses are fine but the document misuses them: a rewrite suffices. */
        WRITER;

        static Target parse(String raw) {
            if (raw == null) {
                return WRITER;
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            // "ANALYST" and "ANALYSIS" both land here; the tasks an issue can be
            // routed to is a closed set, so anything unrecognised is treated as the
            // cheapest fix rather than silently becoming a full re-research.
            if (value.startsWith("RESEARCH")) {
                return RESEARCHER;
            }
            if (value.startsWith("ANALY")) {
                return ANALYST;
            }
            return WRITER;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewIssue(
            String level,
            String target,
            String location,
            String check,
            String problem,
            String evidence,
            String fix) {

        public Target targetEnum() {
            return Target.parse(target);
        }

        /** True when this finding is severe enough to block delivery. */
        public boolean isBlocker() {
            return level != null && level.trim().equalsIgnoreCase("BLOCKER");
        }

        /** One-line rendering for the workflow's report and the downstream agent's input. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(level == null ? "ISSUE" : level)
                    .append('/').append(targetEnum()).append("] ");
            if (location != null && !location.isBlank()) {
                sb.append(location).append(" — ");
            }
            sb.append(problem == null ? "(未描述)" : problem);
            if (fix != null && !fix.isBlank()) {
                sb.append("\n    修改建议: ").append(fix);
            }
            if (evidence != null && !evidence.isBlank()) {
                sb.append("\n    依据: ").append(evidence);
            }
            return sb.toString();
        }
    }

    /**
     * Exactly what the reviewer is asked to emit, and nothing more.
     *
     * <p>Separate from {@link ReviewResult} because that type also carries {@code approved},
     * which this workflow derives from the verdict rather than trusting. Asking the model for it
     * — as the hand-written contract in {@code review-guide.md} did — invites a self-contradicting
     * artifact ("approved": true beside "verdict": "REVISE") that the parse then silently
     * overrides.</p>
     *
     * <p>The reviewer agent is built with {@code outputType(Payload.class)}, so this record is
     * also the source of the JSON schema appended to its prompt. That makes the shape the model is
     * told to produce and the shape this class reads into the same declaration, which the two
     * places that used to carry it — this file and the guide's hand-written example — were not.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(String verdict, String summary, List<ReviewIssue> issues) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses the reviewer's output, tolerating the ways a model wraps JSON.
     *
     * <p>Handles a bare object, a ```json fence, and prose before or after the
     * object. On failure it returns a blocking result rather than throwing or
     * approving: an unparseable review means the document was <em>not</em> verified,
     * and silently passing it would defeat the point of having a reviewer.</p>
     *
     * <p>Still a tolerant parse rather than a straight conversion. The schema the framework
     * injects asks for well-formed JSON and makes malformed output rarer, but nothing enforces
     * it — the reviewer runs with tools, so no provider-side JSON mode is in play — and a run
     * whose verdict cannot be read has to block rather than approve.</p>
     */
    public static ReviewResult parse(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            return unparseable(raw, "未找到 JSON 对象");
        }
        try {
            Payload parsed = MAPPER.readValue(json, Payload.class);
            if (parsed.verdict() == null || parsed.verdict().isBlank()) {
                // A result with no verdict cannot be trusted as an approval.
                return unparseable(raw, "缺少 verdict 字段");
            }
            boolean approved = "PASS".equalsIgnoreCase(parsed.verdict().trim());
            List<ReviewIssue> issues = parsed.issues() == null ? List.of() : parsed.issues();
            String summary = parsed.summary() == null ? "" : parsed.summary();
            if (approved && issues.stream().anyMatch(ReviewIssue::isBlocker)) {
                // Contradictory output: a BLOCKER cannot coexist with PASS. Treat the
                // finding as authoritative — the more conservative reading.
                return new ReviewResult(false, "REVISE", summary, issues);
            }
            return new ReviewResult(approved, parsed.verdict(), summary, issues);
        } catch (Exception e) {
            return unparseable(raw, e.getMessage());
        }
    }

    /** True when at least one finding needs a new research pass. */
    public boolean needsResearcher() {
        return issuesOrEmpty().stream().anyMatch(i -> i.targetEnum() == Target.RESEARCHER);
    }

    /** True when at least one finding needs a single paper re-read. */
    public boolean needsAnalyst() {
        return issuesOrEmpty().stream().anyMatch(i -> i.targetEnum() == Target.ANALYST);
    }

    /** The findings a rewrite alone can address. */
    public List<ReviewIssue> writerIssues() {
        return issuesOrEmpty().stream().filter(i -> i.targetEnum() == Target.WRITER).toList();
    }

    /** The findings that need a paper re-read before the document is rewritten. */
    public List<ReviewIssue> analystIssues() {
        return issuesOrEmpty().stream().filter(i -> i.targetEnum() == Target.ANALYST).toList();
    }

    /** The findings only a new research pass can address. */
    public List<ReviewIssue> researcherIssues() {
        return issuesOrEmpty().stream().filter(i -> i.targetEnum() == Target.RESEARCHER).toList();
    }

    /** Renders the issues routed to one agent, for passing back as revision input. */
    public String renderFor(Target target) {
        List<ReviewIssue> selected = switch (target) {
            case RESEARCHER -> researcherIssues();
            case ANALYST -> analystIssues();
            case WRITER -> writerIssues();
        };
        if (selected.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ReviewIssue issue : selected) {
            sb.append("- ").append(issue.render()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Best-effort extraction of a JSON object from model output.
     *
     * <p>Scans for the first balanced {@code {...}} rather than slicing from the
     * first brace to the last, so trailing prose containing a brace cannot corrupt
     * the payload.</p>
     */
    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        // Unbalanced braces: hand the remainder to Jackson and let it report the error.
        return raw.substring(start);
    }

    private static ReviewResult unparseable(String raw, String reason) {
        String excerpt = raw == null ? "(空输出)"
                : raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
        ReviewIssue synthetic = new ReviewIssue(
                "BLOCKER",
                Target.WRITER.name(),
                "(审查输出)",
                "PARSE",
                "审查输出无法解析为约定 JSON，文档事实核验未完成：" + reason,
                "审查原文：" + excerpt,
                "重新执行审查，确保输出为符合 review-guide.md §4 的单个 JSON 对象");
        return new ReviewResult(false, "REVISE",
                "审查输出无法解析（" + reason + "），按未通过处理，未完成事实核验。",
                List.of(synthetic));
    }

    /** The schema the reviewer is instructed to emit, used to build its prompt. */
    public static String outputSchema() {
        return """
                {"approved": true|false, "verdict": "PASS|REVISE", "summary": "总体意见",
                 "issues": [{"level": "BLOCKER|MAJOR|MINOR", "target": "WRITER|ANALYST|RESEARCHER",
                             "location": "章节定位", "check": "检查项编号", "problem": "问题",
                             "evidence": "材料依据", "fix": "可执行的修改建议"}]}""";
    }

    /** The issues, never null. */
    public List<ReviewIssue> issuesOrEmpty() {
        return issues == null ? List.of() : issues;
    }
}
