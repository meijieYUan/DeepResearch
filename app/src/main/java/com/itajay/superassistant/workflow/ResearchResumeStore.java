package com.itajay.superassistant.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂停在「等待人工补充论文」的调研任务，供 resume 接口续跑。
 *
 * <p>工作流本身是同步的 Java 调用：暂停就是把控制权交还出去，不在内存里挂着线程。
 * 因此这里只保存恢复所需的最小信息——课题方向、原始要求、缺哪些论文——而不是运行中的状态。
 * 恢复时重新发起同一课题的调研：已下载的论文、论文库命中和已完成的精读都会自动跳过，
 * 代价只有那几篇真正缺失的论文。</p>
 *
 * <p>与 {@link com.itajay.superassistant.security.PendingInterruptionStore} 的区别：
 * 那是"高危操作待审批"，等在工具调用内部；这是"材料不全，等人补文件"，等在工作流阶段之间。
 * 两条路径互不干扰。</p>
 */
@Component
public class ResearchResumeStore {

    private static final Logger log = LoggerFactory.getLogger(ResearchResumeStore.class);

    /** 超过这个时间未恢复就丢弃，避免用户早已放弃的课题一直占着提示位。 */
    static final Duration TTL = Duration.ofHours(24);

    private final ConcurrentHashMap<String, ResumeTask> tasks = new ConcurrentHashMap<>();

    /**
     * 一次可恢复的调研。
     *
     * @param threadId 会话线程
     * @param topic    课题方向（课题目录名）
     * @param brief    原始调研要求，恢复时原样重放
     * @param missing  缺哪几篇论文（工具名），用于前端展示
     */
    public record ResumeTask(String threadId, String topic, String brief,
                             List<MissingPaper> missing, Instant createdAt) {
    }

    /** 一篇没拿到全文的论文。 */
    public record MissingPaper(String shortName, String reason) {
    }

    public void put(String threadId, String topic, String brief, List<MissingPaper> missing) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        tasks.put(threadId, new ResumeTask(threadId, topic, brief, missing, Instant.now()));
        log.info("Research paused, resumable [thread={}, topic={}, missing={}]",
                threadId, topic, missing.size());
    }

    /** The paused task for this thread, or empty when there is none (or it expired). */
    public Optional<ResumeTask> get(String threadId) {
        ResumeTask task = tasks.get(threadId);
        if (task == null) {
            return Optional.empty();
        }
        if (task.createdAt().isBefore(Instant.now().minus(TTL))) {
            tasks.remove(threadId, task);
            log.info("Resume task expired [thread={}, topic={}]", threadId, task.topic());
            return Optional.empty();
        }
        return Optional.of(task);
    }

    public void remove(String threadId) {
        tasks.remove(threadId);
    }

    /** Peek without extending: used by the resume endpoint to report what is still missing. */
    public Map<String, ResumeTask> snapshot() {
        return Map.copyOf(tasks);
    }

    /** Periodic sweep so abandoned pauses do not accumulate. */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    public void sweep() {
        Instant cutoff = Instant.now().minus(TTL);
        tasks.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }
}
