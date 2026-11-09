package com.itajay.superassistant.tool;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录一次调研里没拿到全文的论文，供工作流决定是否停下来等人工补。
 *
 * <p>为什么不从 research-agent 的返回文本里解析：那是一段模型写的 Markdown，
 * 措辞每轮都可能不同，拿它当"哪些论文下载失败了"的权威来源，判断会随模型心情漂移。
 * 下载失败发生在 {@link PaperDownloadTool} 内部，那里有确切的短名、URL 和失败原因，
 * 记在这里才可靠。</p>
 *
 * <p>成功即销账：同一篇论文换源后下载成功，之前的失败记录会被移除——
 * 它已经拿到全文了，不该再让工作流停下来等。</p>
 */
@Component
public class DownloadFailureRegistry {

    /** 未处理条目保留多久。工作流等待本身有超时，这里只是防止内存无限增长。 */
    private static final Duration TTL = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Map<String, Failure>> failures = new ConcurrentHashMap<>();

    /** 一篇拿不到全文的论文。 */
    public record Failure(String shortName, String url, String reason, Instant at) {
    }

    /** 一次调研的等待上下文。 */
    public record Snapshot(String topic, List<Failure> items) {
        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /** Records that one paper could not be obtained. */
    public void record(String threadId, String topic, String shortName, String url, String reason) {
        String key = key(threadId, topic);
        Map<String, Failure> map = failures.computeIfAbsent(key, k -> new LinkedHashMap<>());
        synchronized (map) {
            map.put(shortName, new Failure(shortName, url,
                    reason == null ? "" : reason, Instant.now()));
        }
    }

    /** A later success for the same paper cancels its pending failure. */
    public void resolved(String threadId, String topic, String shortName) {
        Map<String, Failure> map = failures.get(key(threadId, topic));
        if (map == null) {
            return;
        }
        synchronized (map) {
            map.remove(shortName);
        }
    }

    /**
     * Returns and clears the pending failures for this run.
     *
     * <p>Draining rather than peeking keeps a retried workflow from re-reporting the
     * same papers: each run decides once whether it must wait, on what it itself
     * failed to get.</p>
     */
    public Snapshot drain(String threadId, String topic) {
        Map<String, Failure> map = failures.remove(key(threadId, topic));
        if (map == null) {
            return new Snapshot(topic, List.of());
        }
        List<Failure> items;
        synchronized (map) {
            items = new ArrayList<>(map.values());
        }
        return new Snapshot(topic, items);
    }

    /** Peeks without clearing, for reporting. */
    public Snapshot peek(String threadId, String topic) {
        Map<String, Failure> map = failures.get(key(threadId, topic));
        if (map == null) {
            return new Snapshot(topic, List.of());
        }
        synchronized (map) {
            return new Snapshot(topic, new ArrayList<>(map.values()));
        }
    }

    /** Drops everything belonging to a finished conversation. */
    public void clear(String threadId) {
        failures.keySet().removeIf(k -> k.startsWith(threadId + "\u0000"));
    }

    /** Periodic sweep so abandoned runs do not accumulate. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60 * 60 * 1000L)
    public void sweep() {
        Instant cutoff = Instant.now().minus(TTL);
        failures.values().forEach(map -> {
            synchronized (map) {
                map.values().removeIf(failure -> failure.at().isBefore(cutoff));
            }
        });
        failures.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static String key(String threadId, String topic) {
        return (threadId == null ? "" : threadId) + "\u0000" + (topic == null ? "" : topic);
    }
}
