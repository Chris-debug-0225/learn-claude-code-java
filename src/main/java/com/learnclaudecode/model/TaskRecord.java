package com.learnclaudecode.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务持久化模型
 */
public class TaskRecord {
    /**
     * 任务唯一 ID。
     */
    public int id;

    /**
     * 任务主题，表示这条任务的标题或一句话概述。
     */
    public String subject;

    /**
     * 任务详细描述，补充说明任务背景、要求或实现细节。
     */
    public String description = "";

    /**
     * 任务当前状态，例如 pending、in_progress、completed、deleted。
     */
    public String status = "pending";

    /**
     * 当前认领该任务的执行者标识；未认领时通常为空字符串。
     */
    public String owner = "";

    /**
     * 任务关联的 worktree 名称或路径标识；未分配独立 worktree 时通常为空字符串。
     */
    public String worktree = "";

    /**
     * 当前任务所依赖的前置任务 ID 列表。
     * 只有这些任务完成后，该任务才适合继续推进。
     */
    public List<Integer> blockedBy = new ArrayList<>();

    /**
     * 被当前任务阻塞的后续任务 ID 列表。
     * 可以理解为“当前任务完成后会解除哪些任务的阻塞”。
     */
    public List<Integer> blocks = new ArrayList<>();

    /**
     * 任务创建时间，使用 Unix 时间戳（秒）。
     */
    public long created_at;

    /**
     * 任务最近一次更新时间，使用 Unix 时间戳（秒）。
     */
    public long updated_at;
}
