package com.itajay.superassistant.tool;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    private final ConcurrentHashMap<String, List<Failure>> failures = new ConcurrentHashMap<>();

    /**
     * 一篇拿不到全文的论文。
     *
     * @param identity 是哪一篇论文。短名只是标签：同名的另一篇下载成功，不代表这一篇拿到了
     */
    public record Failure(String shortName, String url, String reason, Instant at, PaperIdentity identity) {

        /** 论文标题，可能为空。人工补论文时用户要按标题去找，短名本身（如 "LCPD2024"）不够。 */
        public String title() {
            return identity.title();
        }

        /** DOI，可能为空。用户可以直接拿它去出版社或图书馆下载。 */
        public String doi() {
            return identity.doi();
        }

        /** arXiv 编号，可能为空。续跑时靠它认回这一篇，用户也可以直接拿它去 arXiv 下载。 */
        public String arxivId() {
            return identity.arxivId();
        }

        /** 首页读不出身份时（扫描件、改过标题的预印本），按这个名字存进论文库就能认出来。 */
        public String fileName() {
            return PaperShelf.distinctName(shortName, identity) + ".pdf";
        }

        private boolean isFor(String name, PaperIdentity paper) {
            return identity.isSameRequest(paper, shortName.equals(name));
        }
    }

    /** 一次调研的等待上下文。 */
    public record Snapshot(String topic, List<Failure> items) {
        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /** Records that one paper could not be obtained, known only by its name and source. */
    public void record(String threadId, String topic, String shortName, String url, String reason) {
        record(threadId, topic, shortName, url, reason, PaperIdentity.fromReference(url));
    }

    /** Records that one paper could not be obtained; a repeated failure replaces the earlier entry. */
    public void record(String threadId, String topic, String shortName, String url, String reason,
                       PaperIdentity identity) {
        List<Failure> list = failures.computeIfAbsent(key(threadId, topic), k -> new ArrayList<>());
        synchronized (list) {
            list.removeIf(failure -> failure.isFor(shortName, identity));
            list.add(new Failure(shortName, url, reason == null ? "" : reason, Instant.now(), identity));
        }
    }

    /** A later success for the same paper cancels its pending failure — whatever name it got. */
    public void resolved(String threadId, String topic, String shortName, PaperIdentity identity) {
        List<Failure> list = failures.get(key(threadId, topic));
        if (list == null) {
            return;
        }
        synchronized (list) {
            list.removeIf(failure -> failure.isFor(shortName, identity));
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
        List<Failure> list = failures.remove(key(threadId, topic));
        if (list == null) {
            return new Snapshot(topic, List.of());
        }
        synchronized (list) {
            return new Snapshot(topic, new ArrayList<>(list));
        }
    }

    /** Peeks without clearing, for reporting. */
    public Snapshot peek(String threadId, String topic) {
        List<Failure> list = failures.get(key(threadId, topic));
        if (list == null) {
            return new Snapshot(topic, List.of());
        }
        synchronized (list) {
            return new Snapshot(topic, new ArrayList<>(list));
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
        failures.values().forEach(list -> {
            synchronized (list) {
                list.removeIf(failure -> failure.at().isBefore(cutoff));
            }
        });
        failures.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static String key(String threadId, String topic) {
        return (threadId == null ? "" : threadId) + "\u0000" + (topic == null ? "" : topic);
    }
}
