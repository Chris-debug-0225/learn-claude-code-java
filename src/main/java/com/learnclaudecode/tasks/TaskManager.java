package com.learnclaudecode.tasks;

import com.learnclaudecode.common.JsonUtils;
import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.model.TaskRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件任务系统，对齐 s07/s11/s12 的任务持久化模式。
 *
 * 这个类实现的是一个简单但能表征核心的任务板：
 * - 每个任务都是一个 JSON 文件；
 * - Agent 可以创建、查看、更新、认领任务；
 * - 多个 Agent 可以通过共享任务目录来协作。
 *
 * Agent 的任务规划系统，本质上也只是状态持久化 + 状态流转规则。
 */
public class TaskManager {
    private final Path taskDir;

    /**
     * 初始化文件任务系统。
     *
     * @param paths 工作区路径工具
     */
    public TaskManager(WorkspacePaths paths) {
        this.taskDir = paths.tasksDir();
        try {
            Files.createDirectories(taskDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 .tasks 目录", e);
        }
    }

    /**
     * 创建新任务。
     *
     * @param subject 任务主题
     * @param description 任务描述
     * @return 新任务的 JSON 表示
     */
    public synchronized String create(String subject, String description) {
        TaskRecord task = new TaskRecord();
        task.id = nextId();
        task.subject = subject;
        task.description = description == null ? "" : description;
        task.status = "pending";
        task.created_at = Instant.now().getEpochSecond();
        task.updated_at = task.created_at;
        save(task);
        return JsonUtils.toPrettyJson(task);
    }

    /**
     * 获取指定任务详情。
     *
     * @param taskId 任务 ID
     * @return 任务 JSON
     */
    public synchronized String get(int taskId) {
        return JsonUtils.toPrettyJson(load(taskId));
    }

    /**
     * 更新任务状态与依赖关系。
     * 这个方法会在同一个同步临界区内完成整个更新流程，避免并发修改同一任务时出现状态覆盖。
     * 整体顺序是：先加载任务，再处理状态变更，随后追加依赖关系，最后刷新更新时间并持久化。
     *
     * @param taskId 任务 ID
     * @param status 新状态
     * @param addBlockedBy 新增 blockedBy 依赖
     * @param addBlocks 新增 blocks 依赖
     * @return 更新后的任务 JSON 或删除结果
     */
    public synchronized String update(int taskId, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {
        // 先从磁盘加载目标任务，后续所有修改都基于这份最新记录进行。
        TaskRecord task = load(taskId);

        // 如果调用方传入了新的状态，就先处理状态更新逻辑。
        if (status != null && !status.isBlank()) {
            task.status = status;
            if ("completed".equals(status)) {
                // 某任务完成后，自动把其他任务对它的 blockedBy 依赖清掉。
                clearDependency(taskId);
            }
            if ("deleted".equals(status)) {
                // deleted 是一个特殊状态：这里不会把状态写回 JSON，而是直接删除任务文件。
                // 删除成功后立即返回，不再继续下面的依赖追加和保存流程。
                try {
                    Files.deleteIfExists(path(taskId));
                } catch (IOException e) {
                    throw new IllegalStateException("删除任务失败", e);
                }
                return "Task " + taskId + " deleted";
            }
        }

        // 追加“当前任务被哪些前置任务阻塞”的依赖。
        // 这里只做去重追加，不会清空已有 blockedBy，也不会覆盖原有依赖关系。
        if (addBlockedBy != null) {
            for (Integer id : addBlockedBy) {
                if (!task.blockedBy.contains(id)) {
                    task.blockedBy.add(id);
                }
            }
        }

        // 追加“当前任务会阻塞哪些后续任务”的依赖。
        // 同样采用去重追加，避免重复写入相同任务 ID。
        if (addBlocks != null) {
            for (Integer id : addBlocks) {
                if (!task.blocks.contains(id)) {
                    task.blocks.add(id);
                }
            }
        }

        // 只要任务没有被删除，这里就刷新更新时间并重新落盘保存。
        task.updated_at = Instant.now().getEpochSecond();
        save(task);

        // 返回最新任务 JSON，便于调用方直接看到更新后的完整结果。
        return JsonUtils.toPrettyJson(task);
    }

    /**
     * 列出所有任务的看板视图。
     *
     * @return 任务列表文本
     */
    public synchronized String listAll() {
        List<TaskRecord> tasks = loadAll();
        if (tasks.isEmpty()) {
            return "No tasks.";
        }
        List<String> lines = new ArrayList<>();
        for (TaskRecord task : tasks) {
            // 这里把底层 JSON 任务记录转成更适合人读的看板格式。
            String marker = switch (task.status) {
                case "completed" -> "[x]";
                case "in_progress" -> "[>]";
                default -> "[ ]";
            };
            String owner = task.owner == null || task.owner.isBlank() ? "" : " @" + task.owner;
            String blocked = task.blockedBy.isEmpty() ? "" : " (blocked by: " + task.blockedBy + ")";
            String worktree = task.worktree == null || task.worktree.isBlank() ? "" : " wt=" + task.worktree;
            lines.add(marker + " #" + task.id + ": " + task.subject + owner + blocked + worktree);
        }
        return String.join("\n", lines);
    }

    /**
     * 认领指定任务。
     *
     * @param taskId 任务 ID
     * @param owner 认领者
     * @return 认领结果
     */
    public synchronized String claim(int taskId, String owner) {
        TaskRecord task = load(taskId);
        task.owner = owner;
        task.status = "in_progress";
        task.updated_at = Instant.now().getEpochSecond();
        save(task);
        return "Claimed task #" + taskId + " for " + owner;
    }

    /**
     * 扫描当前可立即认领的待处理任务。
     * 这里返回的是满足以下条件的任务：
     * 1. 状态仍为 pending；
     * 2. 还没有被任何执行者认领；
     * 3. 没有 blockedBy 前置依赖阻塞。
     * 这类任务通常可以被队友流程或调度逻辑直接拿来认领并开始处理。
     *
     * @return 可认领任务列表
     */
    public synchronized List<TaskRecord> scanUnclaimed() {
        List<TaskRecord> result = new ArrayList<>();
        for (TaskRecord task : loadAll()) {
            // 这里只筛出“当前就可以开始做”的任务：
            // pending 表示尚未开始，owner 为空表示还没人接手，blockedBy 为空表示没有前置任务阻塞。
            if ("pending".equals(task.status) && (task.owner == null || task.owner.isBlank()) && task.blockedBy.isEmpty()) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 将任务绑定到指定 worktree。
     * 这样做的目的，是把“任务记录”与“实际工作的独立目录”关联起来。
     * 绑定后，外部系统可以知道某个任务正在什么 worktree 中处理，便于：
     * 1. 为不同任务提供彼此隔离的工作空间；
     * 2. 跟踪任务当前对应的开发车道；
     * 3. 在移除 worktree、回收资源或恢复任务现场时找到对应关系。
     *
     * 这个方法本身只负责把绑定关系写回任务记录；
     * 它不会创建 worktree 目录，而是记录“该任务已经关联到哪个 worktree”。
     *
     * @param taskId 任务 ID
     * @param worktree worktree 名称
     * @param owner 可选拥有者
     * @return 更新后的任务 JSON
     */
    public synchronized String bindWorktree(int taskId, String worktree, String owner) {
        // 先加载任务，再把 worktree 标识写入任务记录，建立任务到工作目录的关联。
        TaskRecord task = load(taskId);
        task.worktree = worktree;

        // 如果调用方同时传入了 owner，就一并记录认领者，避免任务和执行者信息脱节。
        if (owner != null && !owner.isBlank()) {
            task.owner = owner;
        }

        // 若任务此前还处于 pending，说明它只是待处理；
        // 一旦绑定到具体 worktree，就意味着已经进入实际执行阶段，因此推进为 in_progress。
        if ("pending".equals(task.status)) {
            task.status = "in_progress";
        }

        // 更新任务最后修改时间并保存，使任务状态、认领者和 worktree 绑定关系持久化。
        task.updated_at = Instant.now().getEpochSecond();
        save(task);
        return JsonUtils.toPrettyJson(task);
    }

    /**
     * 解除任务与 worktree 的绑定。
     *
     * @param taskId 任务 ID
     * @return 更新后的任务 JSON
     */
    public synchronized String unbindWorktree(int taskId) {
        TaskRecord task = load(taskId);
        task.worktree = "";
        task.updated_at = Instant.now().getEpochSecond();
        save(task);
        return JsonUtils.toPrettyJson(task);
    }

    /**
     * 计算任务文件路径。
     *
     * @param taskId 任务 ID
     * @return 任务文件路径
     */
    private Path path(int taskId) {
        return taskDir.resolve("task_" + taskId + ".json");
    }

    /**
     * 计算下一个可用任务 ID。
     *
     * @return 下一个任务 ID
     */
    private int nextId() {
        return loadAll().stream().map(task -> task.id).max(Integer::compareTo).orElse(0) + 1;
    }

    /**
     * 从磁盘加载指定任务。
     *
     * @param taskId 任务 ID
     * @return 任务记录
     */
    private TaskRecord load(int taskId) {
        try {
            Path path = path(taskId);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Task " + taskId + " not found");
            }
            return JsonUtils.fromJson(Files.readString(path, StandardCharsets.UTF_8), TaskRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取任务失败", e);
        }
    }

    /**
     * 加载全部任务记录。
     *
     * @return 任务列表
     */
    private List<TaskRecord> loadAll() {
        try (Stream<Path> stream = Files.list(taskDir)) {
            // 任务文件直接按 task_*.json 扫描，避免引入额外数据库或索引系统。
            return stream.filter(path -> path.getFileName().toString().startsWith("task_") && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> {
                        try {
                            return JsonUtils.fromJson(Files.readString(path, StandardCharsets.UTF_8), TaskRecord.class);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 将任务记录保存到磁盘。
     *
     * @param task 任务记录
     */
    private void save(TaskRecord task) {
        try {
            Files.writeString(path(task.id), JsonUtils.toPrettyJson(task), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存任务失败", e);
        }
    }

    /**
     * 清理其他任务对已完成任务的阻塞依赖。
     *
     * @param completedId 已完成任务 ID
     */
    private void clearDependency(int completedId) {
        for (TaskRecord task : loadAll()) {
            if (task.blockedBy.removeIf(id -> id == completedId)) {
                // 依赖变化后立即落盘，确保多个代理读到的任务状态一致。
                save(task);
            }
        }
    }
}
