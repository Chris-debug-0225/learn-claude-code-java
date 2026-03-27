package com.learnclaudecode.background;

import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.tools.CommandTools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 后台任务管理器，对齐 s08 的异步执行与通知机制。
 */
public class BackgroundManager {
    private final WorkspacePaths paths;
    private final CommandTools commandTools;
    private final ConcurrentHashMap<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private final BlockingQueue<Map<String, Object>> notifications = new LinkedBlockingQueue<>();

    /**
     * 初始化后台任务管理器。
     *
     * @param paths 工作区路径工具
     */
    public BackgroundManager(WorkspacePaths paths) {
        this.paths = paths;
        this.commandTools = new CommandTools(paths);
    }

    /**
     * 以异步方式启动后台命令。
     *
     * @param command 命令文本
     * @param timeoutSeconds 超时时间
     * @return 后台任务启动结果
     */
    public String run(String command, int timeoutSeconds) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        tasks.put(taskId, new ConcurrentHashMap<>(Map.of(
                "status", "running",
                "command", command,
                "result", ""
        )));
        // 每个后台命令放到独立线程执行，主对话循环只拿 taskId，不阻塞当前交互。
        Executors.newSingleThreadExecutor().submit(() -> execute(taskId, command, timeoutSeconds));
        return "Background task " + taskId + " started: " + command.substring(0, Math.min(80, command.length()));
    }

    /**
     * 在后台线程中真正执行命令并记录状态。
     * 整体流程是：
     * 1. 根据原始命令构造底层 shell 进程；
     * 2. 将进程工作目录设置为当前工作区根目录；
     * 3. 在限定时间内等待命令执行完成；
     * 4. 根据执行结果写回 tasks 表；
     * 5. 再向 notifications 队列投递一条摘要通知，供主循环后续读取。
     *
     * @param taskId 任务 ID
     * @param command 命令文本
     * @param timeoutSeconds 超时时间
     */
    private void execute(String taskId, String command, int timeoutSeconds) {
        String status;
        String result;
        try {
            // 先把业务层传入的命令文本包装成底层 shell 可执行的命令数组，
            // 例如在类 Unix 系统下通常会变成 bash -lc <command>。
            ProcessBuilder builder = new ProcessBuilder(commandTools.shellCommand(command));
            // 所有后台命令都固定在工作区根目录执行，避免相对路径解析位置不一致。
            builder.directory(paths.workdir().toFile());

            // 真正启动子进程。启动成功后，命令会在当前后台线程对应的子进程中运行。
            Process process = builder.start();

            // 在给定超时时间内阻塞等待命令结束。
            // finished=true 表示进程已正常结束；false 表示到时间仍未结束。
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // 超时后强制终止，避免失控命令长期占用资源。
                process.destroyForcibly();
                status = "timeout";
                result = "Error: Timeout (" + timeoutSeconds + "s)";
            } else {
                // 命令结束后同时读取标准输出和标准错误，
                // 然后把两者拼接成统一结果，便于调用方一次查看完整输出。
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                result = (stdout + stderr).trim();

                // 如果命令执行成功但没有任何输出，使用固定占位文本，
                // 这样前端或调用方就不会把空字符串误解为“结果还没返回”。
                if (result.isBlank()) {
                    result = "(no output)";
                }
                status = "completed";
            }
        } catch (Exception e) {
            // 启动进程、等待进程或读取输出时只要出现异常，
            // 都统一记为 error，并把异常信息写入结果字段。
            status = "error";
            result = "Error: " + e.getMessage();
        }
        // 执行结束后同时更新任务表和通知队列，供主循环在后续轮次注入结果。
        // tasks 保存的是该任务的完整当前状态，供 check(...) 查询。
        tasks.put(taskId, new ConcurrentHashMap<>(Map.of(
                "status", status,
                "command", command,
                "result", result.substring(0, Math.min(50000, result.length()))
        )));
        // notifications 只放一份较短摘要，避免通知过大，
        // 主循环可以周期性 drain() 取出并回传给上层。
        notifications.offer(Map.of(
                "task_id", taskId,
                "status", status,
                "result", result.substring(0, Math.min(500, result.length()))
        ));
    }

    /**
     * 查询后台任务状态。
     *
     * @param taskId 任务 ID，为空时返回全部任务
     * @return 任务状态文本
     */
    public String check(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            Map<String, Object> task = tasks.get(taskId);
            if (task == null) {
                return "Error: Unknown task " + taskId;
            }
            return "[" + task.get("status") + "] " + task.get("command") + "\n" + task.get("result");
        }
        if (tasks.isEmpty()) {
            return "No background tasks.";
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : tasks.entrySet()) {
            lines.add(entry.getKey() + ": [" + entry.getValue().get("status") + "] " + entry.getValue().get("command"));
        }
        return String.join("\n", lines);
    }

    /**
     * 取出当前累计的后台结果通知。
     *
     * @return 通知列表
     */
    public List<Map<String, Object>> drain() {
        List<Map<String, Object>> result = new ArrayList<>();
        notifications.drainTo(result);
        return result;
    }
}
