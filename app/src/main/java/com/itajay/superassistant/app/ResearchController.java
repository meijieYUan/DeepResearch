package com.itajay.superassistant.app;

import com.itajay.superassistant.workspace.TopicCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调研方向现状。
 *
 * <p>存在的原因很简单：方向就是 {@code investigation/} 下的一个目录，而此前唯一能知道
 * 有哪些目录的办法是去翻文件系统。前端要提供"在已有方向下继续调研"的选择，就得先有一份
 * 权威清单；模型要复用某个方向，也得拿到逐字正确的目录名——让模型照着提示词重新拼一个
 * 名字，拼错了就是一个新目录，一次调研被拆成两半。</p>
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final TopicCatalog topicCatalog;

    public ResearchController(TopicCatalog topicCatalog) {
        this.topicCatalog = topicCatalog;
    }

    /**
     * 所有已有调研方向，最近修改的在前。
     *
     * <p>字段刻意与续调研入口对齐：{@code topic} 就是 {@code POST /api/chat/{threadId}}
     * 里 {@code continueTopic} 应当携带的值，前端原样回传即可，不需要做任何变换。</p>
     */
    @GetMapping("/topics")
    public List<Map<String, Object>> topics() {
        return topicCatalog.list().stream().map(topic -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topic", topic.topic());
            row.put("papers", topic.papers());
            row.put("analyses", topic.analyses());
            row.put("hasDocument", topic.hasDocument());
            row.put("documentPath", topic.documentPath());
            row.put("updatedAt", topic.updatedAt() == null ? null : topic.updatedAt().toString());
            return row;
        }).toList();
    }
}
