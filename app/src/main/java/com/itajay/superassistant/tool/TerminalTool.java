package com.itajay.superassistant.tool;

import com.itajay.superassistant.plan.PlanModeContext;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class TerminalTool {

    private static final Logger log = LoggerFactory.getLogger(TerminalTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 8000;
    /** Extra grace for the process to die after destroyForcibly, and for the pump to drain. */
    private static final int DESTROY_GRACE_SECONDS = 5;
    private static final Set<String> MUTATING_TOKENS = Set.of(
            "rm", "del", "erase", "rd", "rmdir", "mv", "move", "ren", "cp", "copy", "xcopy",
            "mkdir", "md", "touch", "install", "format", "taskkill", "kill", "pkill",
            "shutdown", "restart", "setx", "push", "commit", "checkout", "reset", "clean",
            "stash", "apply", "add");
    /** Shell metacharacters that can smuggle in a second command or a write. */
    private static final List<String> FORBIDDEN_OPERATORS =
            List.of("&&", "||", ";", "|", ">", "<", "`", "$(");
    /** find(1) actions that mutate or execute; `find . -delete` must not pass as read-only. */
    private static final Set<String> FIND_MUTATING_FLAGS =
            Set.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprintf", "-fls");
    private final Path workspaceRoot;

    public TerminalTool() {
        // Same root as the investigation tools (nearest .git ancestor), so a start from
        // a submodule does not give the terminal a different world than the file tools.
        this.workspaceRoot = WorkspacePaths.root();
    }

    @Tool(description = "Execute a shell/terminal command and return the output. Supports common commands like dir/ls, echo, mkdir, type/cat, etc. Use with caution. Output is truncated at 8000 characters and commands time out after 30 seconds. The working directory must stay inside the project workspace.")
    public String executeCommand(
            @ToolParam(description = "The shell command to execute, e.g. 'dir' on Windows or 'ls -la' on Linux/Mac") String command,
            @ToolParam(description = "Working directory for the command. Use '.' for current workspace. Must be inside the workspace. Defaults to workspace root.") String workingDir,
            ToolContext toolContext) {
        if (PlanModeContext.isActive(threadId(toolContext)) && !isReadOnlyCommand(command)) {
            return "计划模式下禁止运行非只读命令: " + command;
        }
        log.info("Executing command: {} in dir: {}", command, workingDir);

        Path dir;
        try {
            dir = resolveWorkingDir(workingDir);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        File dirFile = dir.toFile();
        if (!dirFile.exists() || !dirFile.isDirectory()) {
            return "Error: directory not found: " + dir;
        }

        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(dirFile);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Pump the output on a daemon thread. Reading on the calling thread until
            // EOF first (the old order) deadlocked whenever the command kept running
            // without producing output: waitFor(30s) was never reached and the chat
            // stream thread hung forever.
            StringBuilder output = new StringBuilder();
            Thread pump = new Thread(() -> drain(process, output), "terminal-output-pump");
            pump.setDaemon(true);
            pump.start();

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS);
            }
            pump.join(TimeUnit.SECONDS.toMillis(DESTROY_GRACE_SECONDS));

            int exitCode = finished ? process.exitValue() : -1;
            StringBuilder body = new StringBuilder();
            synchronized (output) {
                body.append(output);
            }
            if (!finished) {
                body.append("\n[Command timed out after ").append(DEFAULT_TIMEOUT_SECONDS)
                        .append("s and was forcibly terminated]");
            }

            String header = String.format("Command: %s\nDirectory: %s\nExit code: %d\n\n",
                    command, dir, exitCode);

            if (body.isEmpty()) {
                return header + "(no output)";
            }
            if (body.length() > MAX_OUTPUT_CHARS) {
                body.setLength(MAX_OUTPUT_CHARS);
                body.append("\n[Output truncated at ").append(MAX_OUTPUT_CHARS).append(" chars]");
            }

            return header + body.toString().trim();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Command interrupted: {}", command);
            return "Error: command interrupted";
        } catch (Exception e) {
            log.error("Command execution failed: {}", command, e);
            return "Error executing command: " + e.getMessage();
        }
    }

    private void drain(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            // The process was destroyed or the stream broke; whatever was captured stands.
            log.debug("Output pump stopped: {}", e.getMessage());
        }
    }

    /**
     * Confines the working directory to the workspace: normalize, then require the
     * result to stay under the root. Absolute paths and {@code ../..} climbs that
     * escape are rejected — the file tools enforce the same boundary via
     * {@code FileOperationService.resolveSafe}; the terminal must not be the hole.
     */
    private Path resolveWorkingDir(String workingDir) {
        Path base = workspaceRoot.toAbsolutePath().normalize();
        if (workingDir == null || workingDir.isBlank() || workingDir.equals(".")) {
            return base;
        }
        Path requested = Path.of(workingDir);
        Path resolved = (requested.isAbsolute() ? requested : base.resolve(requested))
                .toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "workingDir escapes the workspace (must stay inside " + base + "): " + workingDir);
        }
        return resolved;
    }

    private boolean isReadOnlyCommand(String command) {
        String cmd = command == null ? "" : command.trim().toLowerCase();
        if (cmd.isBlank()) {
            return false;
        }
        // Any shell operator can smuggle a mutating command past a token check
        // (`ls | tee x`, `cat <(rm ...)`, backticks, $(...)). Reject them outright.
        for (String operator : FORBIDDEN_OPERATORS) {
            if (cmd.contains(operator)) {
                return false;
            }
        }
        String[] tokens = cmd.split("\\s+");
        for (String token : tokens) {
            if (MUTATING_TOKENS.contains(token)) {
                return false;
            }
        }
        String first = tokens[0];
        return switch (first) {
            case "dir", "ls", "pwd", "type", "cat", "more", "less", "rg", "grep",
                 "where", "which", "head", "tail", "wc", "uniq", "tree" -> true;
            case "find" -> isReadOnlyFind(tokens);
            case "sort" -> isReadOnlySort(tokens);
            case "git" -> isReadOnlyGit(tokens);
            case "java" -> cmd.matches("java\\s+(-version|--version|--help).*");
            case "node" -> cmd.matches("node\\s+(-v|--version|--help).*");
            case "npm" -> cmd.matches("npm\\s+(-v|--version|--help).*");
            case "mvn" -> cmd.matches("mvn\\s+(-v|--version|--help).*");
            case "docker" -> cmd.matches("docker\\s+(ps|images|version|help|inspect).*");
            default -> false;
        };
    }

    /** `find` is read-only only without -delete/-exec/-fprint family actions. */
    private boolean isReadOnlyFind(String[] tokens) {
        return Arrays.stream(tokens).noneMatch(FIND_MUTATING_FLAGS::contains);
    }

    /** `sort -o file` writes; plain sort only reads. */
    private boolean isReadOnlySort(String[] tokens) {
        return Arrays.stream(tokens).noneMatch(t -> t.equals("-o") || t.equals("--output"));
    }

    private boolean isReadOnlyGit(String[] tokens) {
        if (tokens.length < 2) {
            return false;
        }
        List<String> args = Arrays.asList(tokens).subList(2, tokens.length);
        return switch (tokens[1]) {
            case "status", "diff", "log", "show", "help", "ls-files", "ls-tree", "grep" -> true;
            // `git branch <name>` creates, `git branch -D <name>` deletes: only the
            // bare listing form (option flags, no branch name argument) is read-only.
            case "branch" -> args.isEmpty() || allListFlags(args, Set.of("-v", "-a", "-r", "--verbose", "--all", "--remotes", "--list"));
            // `git remote add/remove/rename` mutates; bare listing and `show`/`-v` do not.
            case "remote" -> args.isEmpty()
                    || (args.size() == 1 && Set.of("-v", "show", "--verbose").contains(args.get(0)));
            // `git config key value` writes, `--global/--system` writes outside the repo:
            // only listing and single-key reads without a value are read-only.
            case "config" -> isReadOnlyGitConfig(args);
            case "tag" -> args.isEmpty() || allListFlags(args, Set.of("-l", "-n", "--list"));
            default -> false;
        };
    }

    private boolean isReadOnlyGitConfig(List<String> args) {
        if (args.isEmpty()) {
            return false;
        }
        if (allListFlags(args, Set.of("-l", "--list", "--get", "--get-all", "--get-regexp"))) {
            return true;
        }
        // Legacy read form: `git config <key>` — exactly one non-flag argument.
        return args.size() == 1 && !args.get(0).startsWith("-");
    }

    /** True when every argument is a flag from the allowed listing set (no operands). */
    private boolean allListFlags(List<String> args, Set<String> allowedFlags) {
        return args.stream().allMatch(a -> a.startsWith("-") && allowedFlags.contains(a));
    }

    private String threadId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get("threadId");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
