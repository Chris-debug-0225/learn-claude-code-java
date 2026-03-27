package com.learnclaudecode.tasks;

import com.learnclaudecode.common.JsonUtils;
import com.learnclaudecode.common.WorkspacePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * worktree 管理器，对齐 s12 的目录隔离语义。
 * 这个类负责维护一套轻量的“任务 <-> worktree”映射关系：
 * 1. 在 .worktrees 下创建和移除独立目录；
 * 2. 在 index.json 中保存当前 worktree 清单；
 * 3. 在 events.jsonl 中追加生命周期事件；
 * 4. 同步更新任务记录里的 worktree 绑定状态。
 */
public class WorktreeManager {
    private final WorkspacePaths paths;
    private final TaskManager taskManager;
    private final Path indexPath;
    private final Path eventsPath;

    /**
     * 初始化 worktree 管理器。
     * 初始化时会确保 .worktrees 目录存在，并准备两个基础文件：
     * - index.json：保存当前所有 worktree 的索引；
     * - events.jsonl：追加记录 worktree 生命周期事件。
     *
     * @param paths 工作区路径工具
     * @param taskManager 任务管理器
     */
    public WorktreeManager(WorkspacePaths paths, TaskManager taskManager) {
        this.paths = paths;
        this.taskManager = taskManager;
        this.indexPath = paths.worktreesDir().resolve("index.json");
        this.eventsPath = paths.worktreesDir().resolve("events.jsonl");
        try {
            // 先确保 worktree 根目录存在，后续索引文件和各 worktree 子目录都会放在这里。
            Files.createDirectories(paths.worktreesDir());
            if (!Files.exists(indexPath)) {
                // index.json 保存当前所有 worktree 的列表；首次启动时初始化为空数组结构。
                Files.writeString(indexPath, JsonUtils.toPrettyJson(Map.of("worktrees", new ArrayList<>())), StandardCharsets.UTF_8);
            }
            if (!Files.exists(eventsPath)) {
                // events.jsonl 是事件流水文件；首次启动时创建一个空文件即可。
                Files.writeString(eventsPath, "", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("初始化 worktree 目录失败", e);
        }
    }

    /**
     * 创建并绑定一个新的 worktree 车道。
     * 这个方法会同时完成三件事：
     * 1. 在 .worktrees 下创建对应目录；
     * 2. 把 worktree 元信息写入索引；
     * 3. 把该 worktree 与指定任务绑定，并记录创建事件。
     *
     * @param name worktree 名称
     * @param taskId 关联任务 ID
     * @return worktree 信息
     */
    public synchronized String create(String name, int taskId) {
        // worktree 的实际目录位置位于 .worktrees/<name>。
        Path worktreePath = paths.worktreesDir().resolve(name);
        try {
            // 创建独立目录，作为该任务对应的隔离工作空间。
            Files.createDirectories(worktreePath);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        // 当前实现用独立目录模拟 worktree 车道，并把它与任务 ID 绑定。
        Map<String, Object> item = new HashMap<>();
        item.put("name", name);
        item.put("path", worktreePath.toString());
        item.put("branch", "wt/" + name);
        item.put("task_id", taskId);
        item.put("status", "active");

        // 把新建的 worktree 追加到索引列表中，便于后续列出、删除和查找。
        List<Map<String, Object>> items = worktrees();
        items.add(item);
        saveIndex(items);

        // 同步更新任务记录，让任务知道自己当前绑定到了哪个 worktree。
        taskManager.bindWorktree(taskId, name, "");

        // 记录一条创建事件，便于追踪生命周期。
        emit("worktree_created", taskId, item, null);
        return JsonUtils.toPrettyJson(item);
    }

    /**
     * 列出所有已记录的 worktree。
     * 这里只读取索引文件中的元数据，不会扫描磁盘目录树。
     *
     * @return worktree 列表 JSON
     */
    public synchronized String list() {
        return JsonUtils.toPrettyJson(Map.of("worktrees", worktrees()));
    }

    /**
     * 移除指定 worktree。
     * 这里的“移除”包括两层含义：
     * 1. 从索引中删除这条 worktree 记录；
     * 2. 根据 keep 参数决定是否同时删除对应目录文件。
     * 如果该 worktree 绑定了任务，还会顺带解除任务上的 worktree 绑定。
     *
     * @param name worktree 名称
     * @param keep 是否保留目录文件
     * @return 移除结果
     */
    public synchronized String remove(String name, boolean keep) {
        // 先从索引里找到要移除的 worktree 记录。
        List<Map<String, Object>> items = worktrees();
        Map<String, Object> target = null;
        for (Map<String, Object> item : items) {
            if (name.equals(item.get("name"))) {
                target = item;
                break;
            }
        }
        if (target == null) {
            return "Error: Unknown worktree '" + name + "'";
        }
        if (!keep) {
            try {
                // 删除时按深度逆序遍历，确保先删文件后删目录。
                Path path = Path.of(String.valueOf(target.get("path")));
                if (Files.exists(path)) {
                    Files.walk(path)
                            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
            } catch (IOException ignored) {
            }
        }

        // 无论是否保留目录文件，只要逻辑上移除了 worktree，都要把索引记录删掉。
        items.remove(target);
        saveIndex(items);

        // 如果这条 worktree 关联了某个任务，则把任务记录中的 worktree 字段清空。
        Number taskId = (Number) target.get("task_id");
        if (taskId != null) {
            taskManager.unbindWorktree(taskId.intValue());
        }

        // 再补一条移除事件，方便后续审计和排查。
        emit("worktree_removed", taskId == null ? -1 : taskId.intValue(), target, null);
        return "Removed worktree '" + name + "'" + (keep ? " (kept files)" : "");
    }

    /**
     * 读取最近的 worktree 生命周期事件。
     * 事件按 jsonl 逐行存储，这里只截取最后若干行并拼成 JSON 数组文本返回。
     *
     * @param limit 返回条数上限
     * @return 事件 JSON 数组文本
     */
    public synchronized String recentEvents(int limit) {
        try {
            List<String> lines = Files.readAllLines(eventsPath, StandardCharsets.UTF_8);
            // limit 会被限制在 1 到 200 之间，避免一次返回过多事件。
            int from = Math.max(0, lines.size() - Math.max(1, Math.min(limit, 200)));
            return "[\n" + String.join(",\n", lines.subList(from, lines.size())) + "\n]";
        } catch (IOException e) {
            return "[]";
        }
    }

    /**
     * 从索引文件读取当前 worktree 列表。
     * 这里读取的是 index.json 中持久化的元数据，而不是直接扫描目录。
     * 如果文件读取失败，则返回空列表作为兜底。
     *
     * @return worktree 列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> worktrees() {
        try {
            Map<String, Object> data = JsonUtils.fromJson(Files.readString(indexPath, StandardCharsets.UTF_8), Map.class);
            // 如果 JSON 中还没有 worktrees 字段，就现场补一个空列表，简化后续处理。
            return (List<Map<String, Object>>) data.computeIfAbsent("worktrees", key -> new ArrayList<>());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 保存 worktree 索引。
     * 每次创建或移除 worktree 后，都会用最新列表整体覆盖写回 index.json。
     *
     * @param items worktree 列表
     */
    private void saveIndex(List<Map<String, Object>> items) {
        try {
            Files.writeString(indexPath, JsonUtils.toPrettyJson(Map.of("worktrees", items)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存 worktree 索引失败", e);
        }
    }

    /**
     * 追加一条 worktree 生命周期事件。
     * 事件会以一行一个 JSON 对象的形式追加到 events.jsonl，
     * 便于后续查看历史变更，也方便按行流式处理。
     *
     * @param event 事件名
     * @param taskId 关联任务 ID
     * @param worktree worktree 信息
     * @param error 可选错误信息
     */
    private void emit(String event, int taskId, Map<String, Object> worktree, String error) {
        // 所有 worktree 事件都追加到 events.jsonl，方便排查隔离任务的生命周期。
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", event);
        payload.put("ts", Instant.now().getEpochSecond());
        payload.put("task", Map.of("id", taskId));
        payload.put("worktree", worktree == null ? Map.of() : worktree);
        if (error != null && !error.isBlank()) {
            payload.put("error", error);
        }
        try {
            Files.writeString(eventsPath, JsonUtils.toJson(payload) + System.lineSeparator(), StandardCharsets.UTF_8,
                    Files.exists(eventsPath) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException ignored) {
        }
    }
}
