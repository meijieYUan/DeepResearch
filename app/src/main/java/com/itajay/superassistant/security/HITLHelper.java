package com.itajay.superassistant.security;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import java.util.ArrayList;
import java.util.List;

/**
 * Human-In-The-Loop 审批工具类。
 * 核心能力：逐一审批（同意 / 拒绝 / 编辑参数），支持前台提取待审批列表。
 */
public class HITLHelper {

    // ================================================================
    //  核心：逐一审批
    // ================================================================

    /**
     * 根据决策列表对每个工具调用执行同意 / 拒绝 / 编辑参数。
     * 未出现在 decisions 中的工具保持原样不动。
     *
     * <pre>{@code
     * // 前端提交示例:
     * approveOneByOne(metadata, List.of(
     *     ApprovalDecision.approve("tool-call-1"),
     *     ApprovalDecision.reject("tool-call-2", "不允许删除"),
     *     ApprovalDecision.edit("tool-call-3", "{\"to\":\"b@x.com\",\"subject\":\"改过的标题\"}")
     * ));
     * }</pre>
     */
    public static InterruptionMetadata approveOneByOne(
            InterruptionMetadata metadata,
            List<ApprovalDecision> decisions) {

        var builder = InterruptionMetadata.builder()
                .nodeId(metadata.node())
                .state(metadata.state());

        for (var tf : metadata.toolFeedbacks()) {
            var d = find(decisions, tf.getId());
            builder.addToolFeedback(d != null ? applyDecision(tf, d) : tf);
        }
        return builder.build();
    }

    // ================================================================
    //  快捷：全部同意 / 全部拒绝
    // ================================================================

    /** 全部同意。等价于对每个 tool 调用 approveOneByOne(allApproved)。 */
    public static InterruptionMetadata approveAll(InterruptionMetadata metadata) {
        var decisions = metadata.toolFeedbacks().stream()
                .map(tf -> ApprovalDecision.approve(tf.getId()))
                .toList();
        return approveOneByOne(metadata, decisions);
    }

    /** 全部拒绝，统一理由。 */
    public static InterruptionMetadata rejectAll(InterruptionMetadata metadata, String reason) {
        var decisions = metadata.toolFeedbacks().stream()
                .map(tf -> ApprovalDecision.reject(tf.getId(), reason))
                .toList();
        return approveOneByOne(metadata, decisions);
    }

    // ================================================================
    //  前端交互：提取待审批列表
    // ================================================================

    /**
     * 提取待审批工具列表，前端据此渲染每条工具调用的 [同意] [拒绝] [编辑] 按钮。
     */
    public static List<PendingApproval> getPendingApprovals(InterruptionMetadata metadata) {
        var list = new ArrayList<PendingApproval>();
        for (var tf : metadata.toolFeedbacks()) {
            list.add(new PendingApproval(tf.getId(), tf.getName(), tf.getArguments(), tf.getDescription()));
        }
        return list;
    }

    // ================================================================
    //  内部
    // ================================================================

    private static ApprovalDecision find(List<ApprovalDecision> decisions, String toolId) {
        if (decisions == null) {
            return null;
        }
        for (var d : decisions) {
            if (d != null && java.util.Objects.equals(d.toolId(), toolId)) return d;
        }
        return null;
    }

    private static InterruptionMetadata.ToolFeedback applyDecision(
            InterruptionMetadata.ToolFeedback original, ApprovalDecision d) {
        // A decision without a result carries no instruction — leave the feedback as is
        // rather than writing null into the builder.
        if (d.result() == null) {
            return original;
        }
        var fb = InterruptionMetadata.ToolFeedback.builder(original);
        if (d.result() == InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED
                && d.editedArguments() != null) {
            fb.arguments(d.editedArguments());
        }
        fb.result(d.result());
        if (d.description() != null) fb.description(d.description());
        return fb.build();
    }
}