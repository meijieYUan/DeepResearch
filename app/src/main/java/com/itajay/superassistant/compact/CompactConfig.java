package com.itajay.superassistant.compact;

import java.nio.file.Path;
import java.util.List;

/**
 * Static tuning constants for the context-compaction pipeline.
 *
 * <p>Dynamic thresholds (warning / critical token counts) are intentionally
 * <em>not</em> hard-coded here: they are derived from the model context window
 * minus reserved space for the output window and the system-prompt/tools/user
 * overhead. See {@link CompactThresholds} and
 * {@code com.itajay.superassistant.config.CompactProperties}.</p>
 */
public final class CompactConfig {

    private CompactConfig() {}

    // ── Layer 1: Tool result truncation ──
    /** Max estimated tokens for a single tool result before truncation triggers. */
    public static final int TOOL_RESULT_MAX_TOKENS = 10_000;
    /** Estimated tokens kept as an in-context preview after truncation. */
    public static final int TOOL_RESULT_PREVIEW_TOKENS = 500;

    // ── Layer 2: Snip ──
    /** Minimum message count before snip activates. */
    public static final int SNIP_MIN_MESSAGE_COUNT = 60;
    /** Messages at the end of the conversation that ContextSnip never removes. */
    public static final int SNIP_PROTECT_RECENT_MESSAGES = 20;
    /** Max length for a message to be considered a "short filler" (snip candidate). */
    public static final int SNIP_SHORT_MSG_MAX_CHARS = 80;
    /** Lower-cased substrings that identify transient errors worth snipping. */
    public static final List<String> SNIP_ERROR_PATTERNS = List.of(
            "429", "rate limit", "too many requests", "timeout", "timed out",
            "connection refused", "connection reset", "temporarily unavailable",
            "502", "503", "504");

    // ── Layer 3: MicroCompact ──
    /** Number of most recent tool call/result pairs to preserve verbatim. */
    public static final int MICROCOMPACT_KEEP_RECENT_TOOL_PAIRS = 5;

    // ── Layer 4: Full LLM compaction ──
    /** Minimum number of original messages retained by full compaction. */
    public static final int FULL_COMPACT_KEEP_RECENT_MESSAGES = 20;
    /** Compaction summary token budget (the model is asked to stay within it). */
    public static final int SUMMARY_TOKEN_BUDGET = 3_000;
    /** Max files re-injected after compaction, newest first. */
    public static final int POST_COMPACT_MAX_FILES = 5;
    /** Max estimated tokens per recovered file. */
    public static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;
    /** Total token budget for recovered file attachments. */
    public static final int POST_COMPACT_FILE_BUDGET = 50_000;

    // ── Session memory ──
    /** Max entries in the fileStateCache LRU. */
    public static final int FILE_STATE_CACHE_MAX_ENTRIES = 100;

    // ── Storage paths ──
    public static final Path TOOL_RESULTS_DIR = Path.of(".compact", "tool_results");
    public static final Path COMPACT_SNAPSHOTS_DIR = Path.of(".compact", "snapshots");
    /** Max total bytes of truncated tool-result files before the oldest files are pruned. */
    public static final long TOOL_RESULT_DIR_MAX_BYTES = 512L * 1024 * 1024;

    // ── Token estimation ──
    private static final TokenEstimator TOKEN_ESTIMATOR = new BpeTokenEstimator();

    /** Estimate token count from a string. */
    public static int estimateTokens(String text) {
        return TOKEN_ESTIMATOR.estimate(text);
    }

    /** Estimate token count from an iterable of messages. */
    public static int estimateTokens(Iterable<?> messages) {
        return TOKEN_ESTIMATOR.estimate(messages);
    }

    /** Whether tool result content exceeds the truncation threshold. */
    public static boolean shouldTruncate(String content) {
        return content != null && estimateTokens(content) > TOOL_RESULT_MAX_TOKENS;
    }
}
