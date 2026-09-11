package com.itajay.superassistant.app;

import com.itajay.superassistant.entity.TodoTask;
import com.itajay.superassistant.service.TodoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    public List<TodoTask> getAllTodos(@RequestParam String threadId) {
        return todoService.getTasksByThread(threadId);
    }

    @GetMapping("/pending")
    public List<TodoTask> getPendingTodos(@RequestParam String threadId) {
        return todoService.getPendingTasks(threadId);
    }

    @GetMapping("/overdue")
    public List<TodoTask> getOverdueTodos(@RequestParam String threadId) {
        return todoService.getOverdueTasks(threadId);
    }

    @PostMapping("/query")
    public List<TodoTask> queryTodos(@RequestBody Map<String, String> params) {
        return todoService.queryTasks(
                params.get("status"),
                params.get("priority"),
                params.get("keyword"),
                params.get("threadId"));
    }
}