package com.itajay.superassistant.tool;

import com.itajay.superassistant.entity.TodoTask;
import com.itajay.superassistant.service.TodoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TodoTool {

    private static final Logger log = LoggerFactory.getLogger(TodoTool.class);
    private final TodoService todoService;

    public TodoTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Tool(description = "将复杂任务拆解为多个子任务并持久化。内部调用 LLM 自动拆解，只需提供任务目标描述即可。")
    public String todoWrite(
            @ToolParam(description = "复杂任务描述/总目标，如'为用户管理系统添加角色权限功能'") String objective,
            ToolContext toolContext) {
        String threadId = resolveThreadId(toolContext);
        log.info("TodoWrite [thread={}, objective={}]", threadId, objective);
        List<TodoTask> tasks = todoService.breakdownAndPersist(threadId, objective);
        return "已拆解并创建 " + tasks.size() + " 个子任务：\n" + formatTaskList(tasks)
                + "\n\n请通过 getReadyTasks 获取可执行子任务，并按依赖顺序调度执行。";
    }

    @Tool(description = "创建一个子任务。dependsOn 为 JSON 数组字符串如 '[\"t1\"]' 或 '[]'。parentId 为父任务ID（可选）。")
    public String createTask(
            @ToolParam(description = "任务总目标（可选）") String objective,
            @ToolParam(description = "任务标题") String title,
            @ToolParam(description = "任务说明（可选）") String description,
            @ToolParam(description = "验收标准（可选）") String acceptanceCriteria,
            @ToolParam(description = "依赖的 step_key JSON 数组字符串，如 '[\"t1\"]'（可选）") String dependsOn,
            @ToolParam(description = "优先级 LOW/MEDIUM/HIGH/URGENT（可选）") String priority,
            @ToolParam(description = "截止时间 yyyy-MM-ddTHH:mm:ss（可选）") String dueDate,
            @ToolParam(description = "父任务ID（可选）") Long parentId,
            ToolContext toolContext) {
        String threadId = resolveThreadId(toolContext);
        log.info("Create task [thread={}, title={}]", threadId, title);
        TodoTask task = todoService.createTask(threadId, objective, null, title, description,
                acceptanceCriteria, priority, dueDate, dependsOn, parentId);
        return "子任务创建成功：\n" + formatTask(task);
    }

    @Tool(description = "标记任务开始执行，并记录负责执行的 Agent。")
    public String startTask(
            @ToolParam(description = "任务 ID") Long id,
            @ToolParam(description = "负责执行的 Agent 名称") String agentName) {
        todoService.startTask(id, agentName);
        return "任务 " + id + " 已标记为 RUNNING，负责 Agent：" + agentName;
    }

    @Tool(description = "标记任务完成，并记录执行结果摘要。")
    public String completeTask(
            @ToolParam(description = "任务 ID") Long id,
            @ToolParam(description = "执行结果摘要") String outputSummary) {
        todoService.completeTask(id, outputSummary);
        return "任务 " + id + " 已完成。";
    }

    @Tool(description = "获取指定会话中依赖已满足、可以开始执行的 PENDING 子任务。")
    public String getReadyTasks(
            @ToolParam(description = "会话/计划 threadId") String threadId) {
        List<TodoTask> tasks = todoService.getReadyTasks(threadId);
        if (tasks.isEmpty()) {
            return "没有可执行的子任务（全部完成或依赖未满足）。";
        }
        return "可执行子任务 (" + tasks.size() + ")：\n\n" + formatTaskList(tasks);
    }

    @Tool(description = "按可选条件查询当前会话的子任务：status、priority、keyword（标题/说明）。")
    public String queryTodos(
            @ToolParam(description = "状态过滤：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED") String status,
            @ToolParam(description = "优先级过滤：LOW/MEDIUM/HIGH/URGENT") String priority,
            @ToolParam(description = "标题/说明关键字") String keyword,
            ToolContext toolContext) {
        String threadId = resolveThreadId(toolContext);
        List<TodoTask> tasks = todoService.queryTasks(status, priority, keyword, threadId);
        if (tasks.isEmpty()) {
            return "没有符合条件的子任务。";
        }
        return "子任务 (" + tasks.size() + ")：\n\n" + formatTaskList(tasks);
    }

    @Tool(description = "更新子任务字段。只更新传入的字段。")
    public String updateTodo(
            @ToolParam(description = "任务 ID") Long id,
            @ToolParam(description = "新标题（可选）") String title,
            @ToolParam(description = "新状态：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED（可选）") String status,
            @ToolParam(description = "新优先级（可选）") String priority,
            @ToolParam(description = "新截止时间 yyyy-MM-ddTHH:mm:ss（可选）") String dueDate,
            @ToolParam(description = "新说明（可选）") String description,
            @ToolParam(description = "负责 Agent（可选）") String assignedTo) {
        TodoTask task = todoService.getById(id);
        if (task == null) {
            return "任务 " + id + " 不存在。";
        }
        if (title != null && !title.isBlank()) task.setTitle(title);
        if (status != null && !status.isBlank()) task.setStatus(status.toUpperCase());
        if (priority != null && !priority.isBlank()) task.setPriority(priority.toUpperCase());
        if (description != null && !description.isBlank()) task.setDescription(description);
        if (assignedTo != null && !assignedTo.isBlank()) task.setAssignedTo(assignedTo);
        if (dueDate != null && !dueDate.isBlank()) {
            try {
                task.setDueDate(java.time.LocalDateTime.parse(dueDate));
            } catch (Exception ignored) {}
        }
        todoService.update(task);
        return "子任务已更新：\n" + formatTask(task);
    }

    @Tool(description = "删除一个子任务。")
    public String deleteTodo(
            @ToolParam(description = "任务 ID") Long id) {
        TodoTask task = todoService.getById(id);
        if (task == null) {
            return "任务 " + id + " 不存在。";
        }
        todoService.deleteById(id);
        return "子任务已删除：[" + id + "] " + task.getTitle();
    }

    private String resolveThreadId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get("threadId");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        throw new IllegalArgumentException("缺少 threadId 上下文");
    }

    private String formatTaskList(List<TodoTask> tasks) {
        return tasks.stream()
                .map(this::formatTask)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatTask(TodoTask t) {
        return String.format("[%s] %s | Step: %s | Status: %s | Priority: %s | Due: %s | Assigned: %s | Parent: %s%n   目标: %s%n   %s%s",
                t.getId(),
                t.getTitle(),
                t.getStepKey() != null ? t.getStepKey() : "-",
                t.getStatus(),
                t.getPriority() != null ? t.getPriority() : "-",
                t.getDueDate() != null ? t.getDueDate().toString() : "-",
                t.getAssignedTo() != null ? t.getAssignedTo() : "待分配",
                t.getParentId() != null ? String.valueOf(t.getParentId()) : "-",
                t.getObjective() != null ? t.getObjective() : "-",
                t.getDescription() != null ? t.getDescription() : "",
                t.getAcceptanceCriteria() != null ? "\n   验收标准: " + t.getAcceptanceCriteria() : "");
    }
}