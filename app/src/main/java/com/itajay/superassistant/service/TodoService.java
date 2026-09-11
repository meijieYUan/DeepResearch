package com.itajay.superassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itajay.superassistant.entity.TodoTask;
import com.itajay.superassistant.mapper.TodoTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private static final String BREAKDOWN_SYSTEM_PROMPT = """
            You are a task decomposition engine. Break complex objectives into executable subtasks.
            Output ONLY valid JSON matching this schema:
            {"steps": [{"stepKey":"t1","title":"...","description":"...","acceptanceCriteria":"...","dependsOn":"[]"}, ...]}

            Rules:
            1. 2-8 subtasks.
            2. stepKey format: t1, t2, t3... sequential.
            3. dependsOn is a JSON array string referencing earlier stepKeys (e.g. "[]" or "[\"t1\"]").
            4. Each task MUST have all five fields.
            5. Output ONLY the JSON object. No markdown, no commentary.
            """;

    private final TodoTaskMapper todoTaskMapper;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;

    public TodoService(TodoTaskMapper todoTaskMapper, ObjectMapper objectMapper, ChatClient chatClient) {
        this.todoTaskMapper = todoTaskMapper;
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }

    public List<TodoTask> breakdownAndPersist(String threadId, String objective) {
        if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("Missing threadId");
        if (objective == null || objective.isBlank()) throw new IllegalArgumentException("Objective cannot be empty");
        List<TodoTask> steps = callLlmForBreakdown(objective);
        return createTasks(threadId, objective, steps);
    }

    private List<TodoTask> callLlmForBreakdown(String objective) {
        String raw = chatClient.prompt()
                .system(BREAKDOWN_SYSTEM_PROMPT)
                .user("Objective: " + objective)
                .call()
                .content();

        String json = extractJson(raw);
        try {
            TaskBreakdown breakdown = objectMapper.readValue(json, TaskBreakdown.class);
            if (breakdown.steps() == null || breakdown.steps().isEmpty()) {
                throw new IllegalArgumentException("LLM returned empty task breakdown");
            }
            List<TaskBreakdown.TaskStep> steps = breakdown.steps();
            if (steps.size() > 8) {
                log.warn("LLM returned {} tasks, truncating to 8", steps.size());
                steps = steps.subList(0, 8);
            }
            return toTodoTasks(steps);
        } catch (Exception e) {
            log.warn("Task breakdown JSON invalid, retrying: {}", e.getMessage());
            String retryRaw = chatClient.prompt()
                    .system(BREAKDOWN_SYSTEM_PROMPT)
                    .user("Objective: " + objective + "\n\nPrevious output was invalid: " + e.getMessage() + "\nPlease regenerate valid JSON.")
                    .call()
                    .content();
            try {
                TaskBreakdown breakdown = objectMapper.readValue(extractJson(retryRaw), TaskBreakdown.class);
                return toTodoTasks(breakdown.steps());
            } catch (Exception ex) {
                throw new IllegalArgumentException("Task breakdown failed twice: " + ex.getMessage(), ex);
            }
        }
    }

    private List<TodoTask> toTodoTasks(List<TaskBreakdown.TaskStep> steps) {
        List<TodoTask> tasks = new ArrayList<>();
        for (TaskBreakdown.TaskStep step : steps) {
            TodoTask t = new TodoTask();
            t.setStepKey(step.stepKey());
            t.setTitle(step.title());
            t.setDescription(step.description());
            t.setAcceptanceCriteria(step.acceptanceCriteria());
            t.setDependsOn(step.dependsOn());
            tasks.add(t);
        }
        return tasks;
    }

    public List<TodoTask> createTasks(String threadId, String objective, List<TodoTask> steps) {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("Task list cannot be empty");
        Set<String> existingKeys = loadStepKeys(threadId);
        Set<String> keys = new HashSet<>();
        int baseStepNo = maxStepNo(threadId) + 1;
        List<Prepared> prepared = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            TodoTask step = steps.get(i);
            String key = step.getStepKey() == null || step.getStepKey().isBlank() ? "t" + (baseStepNo + i) : step.getStepKey();
            if (!keys.add(key) || existingKeys.contains(key)) throw new IllegalArgumentException("Duplicate step key: " + key);
            if (step.getTitle() == null || step.getTitle().isBlank()) throw new IllegalArgumentException("Step title cannot be empty: " + key);
            prepared.add(new Prepared(key, step, baseStepNo + i));
        }
        for (Prepared p : prepared) {
            List<String> deps = parseDependsOnList(p.step.getDependsOn());
            if (deps == null) continue;
            for (String dep : deps) {
                if (dep == null || dep.isBlank() || !keys.contains(dep)) throw new IllegalArgumentException("Unknown dependency: " + dep);
                if (dep.equals(p.key)) throw new IllegalArgumentException("Step cannot depend on itself: " + p.key);
            }
        }
        List<TodoTask> created = new ArrayList<>();
        for (Prepared p : prepared) {
            TodoTask task = new TodoTask();
            task.setThreadId(threadId); task.setObjective(objective);
            task.setStepKey(p.key); task.setStepNo(p.stepNo);
            task.setTitle(p.step.getTitle()); task.setDescription(p.step.getDescription());
            task.setAcceptanceCriteria(p.step.getAcceptanceCriteria()); task.setDependsOn(normalizeDependsOn(p.step.getDependsOn()));
            task.setParentId(p.step.getParentId()); task.setStatus("PENDING"); task.setPriority("MEDIUM");
            task.setCreatedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now());
            todoTaskMapper.insert(task); created.add(task);
        }
        log.info("Created {} todo tasks [thread={}]", created.size(), threadId);
        return created;
    }

    public TodoTask createTask(String threadId, String objective, String stepKey, String title, String description, String acceptanceCriteria, String priority, String dueDateStr, String dependsOn, Long parentId) {
        if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("Missing threadId");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Task title cannot be empty");
        int stepNo = maxStepNo(threadId) + 1;
        String key = stepKey == null || stepKey.isBlank() ? "t" + stepNo : stepKey;
        Set<String> existingKeys = loadStepKeys(threadId);
        if (existingKeys.contains(key)) throw new IllegalArgumentException("Step key already exists: " + key);
        List<String> deps = parseDependsOnList(dependsOn);
        if (deps != null) {
            for (String dep : deps) {
                if (dep == null || dep.isBlank() || !existingKeys.contains(dep)) throw new IllegalArgumentException("Unknown dependency: " + dep);
                if (dep.equals(key)) throw new IllegalArgumentException("Step cannot depend on itself: " + key);
            }
        }
        TodoTask task = new TodoTask();
        task.setThreadId(threadId); task.setObjective(objective); task.setStepKey(key); task.setStepNo(stepNo);
        task.setTitle(title); task.setDescription(description); task.setAcceptanceCriteria(acceptanceCriteria);
        task.setDependsOn(normalizeDependsOn(dependsOn)); task.setParentId(parentId);
        task.setStatus("PENDING"); task.setPriority(priority != null ? priority.toUpperCase() : "MEDIUM");
        if (dueDateStr != null && !dueDateStr.isBlank()) {
            try { task.setDueDate(LocalDateTime.parse(dueDateStr)); } catch (Exception e) { log.warn("Failed to parse due date: {}", dueDateStr); }
        }
        task.setCreatedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now());
        todoTaskMapper.insert(task); return task;
    }

    public List<TodoTask> getReadyTasks(String threadId) {
        List<TodoTask> all = todoTaskMapper.selectList(new LambdaQueryWrapper<TodoTask>().eq(TodoTask::getThreadId, threadId).orderByAsc(TodoTask::getStepNo));
        Set<String> completedKeys = all.stream().filter(t -> "COMPLETED".equals(t.getStatus())).map(TodoTask::getStepKey).filter(k -> k != null && !k.isBlank()).collect(Collectors.toSet());
        return all.stream().filter(t -> "PENDING".equals(t.getStatus())).filter(t -> parseDependsOnList(t.getDependsOn()).stream().allMatch(completedKeys::contains)).toList();
    }

    public void startTask(Long id, String agentName) {
        TodoTask task = requireTask(id);
        task.setStatus("RUNNING"); task.setAssignedTo(agentName); task.setUpdatedAt(LocalDateTime.now());
        todoTaskMapper.updateById(task);
    }

    public void completeTask(Long id, String outputSummary) {
        TodoTask task = requireTask(id);
        task.setStatus("COMPLETED"); task.setOutputSummary(outputSummary); task.setErrorMessage(null); task.setUpdatedAt(LocalDateTime.now());
        todoTaskMapper.updateById(task);
    }

    public void failTask(Long id, String error) {
        TodoTask task = requireTask(id);
        task.setStatus("FAILED"); task.setErrorMessage(error); task.setUpdatedAt(LocalDateTime.now());
        todoTaskMapper.updateById(task);
    }

    public List<TodoTask> queryTasks(String status, String priority, String keyword, String threadId) {
        requireThreadId(threadId);
        if (status != null && !status.isBlank()) status = status.toUpperCase(); else status = null;
        if (priority != null && !priority.isBlank()) priority = priority.toUpperCase(); else priority = null;
        if (keyword != null && keyword.isBlank()) keyword = null;
        return todoTaskMapper.searchTasks(status, priority, keyword, threadId);
    }

    public List<TodoTask> getTasksByThread(String threadId) {
        requireThreadId(threadId);
        return todoTaskMapper.selectList(new LambdaQueryWrapper<TodoTask>()
                .eq(TodoTask::getThreadId, threadId)
                .orderByAsc(TodoTask::getStepNo)
                .orderByAsc(TodoTask::getId));
    }

    public List<TodoTask> getPendingTasks(String threadId) {
        requireThreadId(threadId);
        return todoTaskMapper.selectList(new LambdaQueryWrapper<TodoTask>()
                .eq(TodoTask::getThreadId, threadId)
                .eq(TodoTask::getStatus, "PENDING")
                .orderByAsc(TodoTask::getStepNo)
                .orderByAsc(TodoTask::getId));
    }

    public List<TodoTask> getOverdueTasks(String threadId) {
        requireThreadId(threadId);
        return todoTaskMapper.findOverdue("PENDING", LocalDateTime.now(), threadId);
    }

    private static void requireThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("Missing threadId");
    }
    public TodoTask getById(Long id) { return todoTaskMapper.selectById(id); }
    public boolean update(TodoTask task) { return todoTaskMapper.updateById(task) > 0; }
    public boolean deleteById(Long id) { return todoTaskMapper.deleteById(id) > 0; }

    private TodoTask requireTask(Long id) { TodoTask t = todoTaskMapper.selectById(id); if (t == null) throw new IllegalArgumentException("Task not found: " + id); return t; }

    private int maxStepNo(String threadId) {
        return todoTaskMapper.selectList(new LambdaQueryWrapper<TodoTask>().eq(TodoTask::getThreadId, threadId).orderByDesc(TodoTask::getStepNo).last("LIMIT 1")).stream().findFirst().map(TodoTask::getStepNo).orElse(0);
    }

    private Set<String> loadStepKeys(String threadId) {
        return todoTaskMapper.selectList(new LambdaQueryWrapper<TodoTask>().eq(TodoTask::getThreadId, threadId)).stream().map(TodoTask::getStepKey).filter(k -> k != null && !k.isBlank()).collect(Collectors.toSet());
    }

    private List<String> parseDependsOnList(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank()) return List.of();
        String trimmed = dependsOn.trim();
        if (trimmed.startsWith("[")) { try { return objectMapper.readValue(trimmed, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)); } catch (Exception e) { return List.of(); } }
        return List.of(trimmed);
    }

    private String normalizeDependsOn(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank()) return "[]";
        String trimmed = dependsOn.trim();
        if (trimmed.startsWith("[")) return trimmed;
        return "[\"" + trimmed + "\"]";
    }

    private String extractJson(String raw) {
        if (raw == null) throw new IllegalArgumentException("Empty LLM response");
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) { trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", ""); trimmed = trimmed.replaceFirst("\\s*```$", ""); }
        return trimmed;
    }

    private record Prepared(String key, TodoTask step, int stepNo) {}
}