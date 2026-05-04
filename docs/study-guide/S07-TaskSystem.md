# S07 — 任务系统

## 这个阶段要学什么

S03 的 Todo 是个好起点，但它有三个局限：

1. **内存里的**：Agent 重启后，todo 就丢了
2. **没有依赖关系**：不能表达"先做完 A 才能做 B"
3. **只有一个人用**：后面如果要多 Agent 协作，内存中的 todo 无法共享

S07 把任务管理从"内存 todo"升级为**文件任务板**。每个任务都是一个独立的 JSON 文件，存在 `.tasks/` 目录下。

```
.tasks/
├── task_1.json    {"id":1, "subject":"设计 API", "status":"completed", ...}
├── task_2.json    {"id":2, "subject":"实现前端", "status":"in_progress", "blockedBy":[], ...}
└── task_3.json    {"id":3, "subject":"写测试", "status":"pending", "blockedBy":[2], ...}
```

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S07TaskSystem
```

## 你可以试试这些输入

- "创建三个任务：1. 分析项目结构 2. 编写文档 3. 代码审查。其中 2 和 3 依赖于 1 完成后才能开始"
- "列出所有任务"
- "把任务 1 标记为completed"（为什么要指定completed，因为我再测试的时候，他会标记为done，这也没问题，因为他不知道都有哪些状态，所以指定这样比较稳妥。然后再看任务列表，2 和 3 的阻塞会自动解除）

## 要读的源码

### 1. `StageConfig.s07()` — 四个任务工具

```java
public static StageConfig s07() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("task_create",  "Create a new task.",                 ...));
    tools.add(tool("task_update",  "Update task status or dependencies.", ...));
    tools.add(tool("task_list",    "List all tasks.",                    ...));
    tools.add(tool("task_get",     "Get task details.",                  ...));
    return new StageConfig("s07", ...);
}
```

### 2. `TaskRecord` — 任务的数据模型

```java
public class TaskRecord {
    public int id;                           // 唯一 ID
    public String subject;                   // 标题
    public String description = "";          // 详细描述
    public String status = "pending";        // pending → in_progress → completed
    public String owner = "";                // 谁在做（多 Agent 时用）
    public String worktree = "";             // 关联的 worktree（S12 用）
    public List<Integer> blockedBy = [];     // 前置依赖
    public List<Integer> blocks = [];        // 后续依赖
    public long created_at;
    public long updated_at;
}
```

### 3. `TaskManager` — 任务管理的核心逻辑

**创建任务**：

```java
public synchronized String create(String subject, String description) {
    TaskRecord task = new TaskRecord();
    task.id = nextId();         // 基于现有最大 ID + 1
    task.status = "pending";
    save(task);                 // 写入 .tasks/task_N.json
    return JsonUtils.toPrettyJson(task);
}
```

**更新任务**（包括状态变更和依赖管理）：

```java
public synchronized String update(int taskId, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {
    TaskRecord task = load(taskId);
    if (status != null) {
        task.status = status;
        if ("completed".equals(status)) {
            clearDependency(taskId);  // 重要！完成时自动清理其他任务的依赖
        }
        if ("deleted".equals(status)) {
            Files.deleteIfExists(path(taskId));  // 删除就是删文件
            return "Task " + taskId + " deleted";
        }
    }
    // 追加依赖关系（去重）
    if (addBlockedBy != null) { ... }
    if (addBlocks != null) { ... }
    save(task);
}
```

**依赖自动清理**：

```java
private void clearDependency(int completedId) {
    for (TaskRecord task : loadAll()) {
        // 从所有任务的 blockedBy 中移除已完成的任务 ID
        if (task.blockedBy.removeIf(id -> id == completedId)) {
            save(task);
        }
    }
}
```

这个设计很优雅：当任务 1 完成时，自动把所有"被任务 1 阻塞"的任务的 `blockedBy` 清空。这样任务 2 和 3 就自动变成可执行的了。

**列出任务**：

```java
public synchronized String listAll() {
    // 渲染成看板格式
    // [x] #1: 设计 API
    // [>] #2: 实现前端
    // [ ] #3: 写测试 (blocked by: [2])
}
```

### 4. 存储方式

每个任务就是一个 JSON 文件：

```json
{
  "id": 1,
  "subject": "设计 API",
  "description": "定义 REST 接口和数据模型",
  "status": "completed",
  "owner": "",
  "worktree": "",
  "blockedBy": [],
  "blocks": [2, 3],
  "created_at": 1711900000,
  "updated_at": 1711900100
}
```

为什么用文件而不是数据库？

- **简单**：不需要额外依赖
- **可共享**：多个 Agent 可以通过文件系统直接读写（S09+ 会用到）
- **可观察**：你随时可以打开 `.tasks/` 目录看任务状态
- **可持久化**：重启后数据还在

## Todo vs Task System 对比

| | S03 Todo | S07 Task System |
|---|---|---|
| 存储 | 内存中的列表 | 文件系统 JSON 文件 |
| 生命周期 | 随 Agent 启停消失 | 持久化，重启后还在 |
| 依赖关系 | 没有 | blockedBy / blocks |
| 多人协作 | 不支持 | 支持（owner 字段） |
| 适用场景 | 单次对话的短期计划 | 跨对话的长期项目管理 |

## 核心概念图

```
用户: "创建 3 个有依赖关系的任务"

模型连续调用:
  task_create("设计 API")          → .tasks/task_1.json
  task_create("实现前端")           → .tasks/task_2.json
  task_create("写测试")            → .tasks/task_3.json
  task_update(2, addBlockedBy=[1]) → task_2 被 task_1 阻塞
  task_update(3, addBlockedBy=[2]) → task_3 被 task_2 阻塞

用户: "把任务 1 标记为completed"

模型调用:
  task_update(1, status="completed")
    → task_1 状态变 completed
    → 自动清理: task_2 的 blockedBy 移除 1
    → task_2 现在没有阻塞了，可以开始做了
```

## 你学到了什么

1. **文件即数据库**。用 JSON 文件做持久化存储，简单有效，而且多个进程可以共享。
2. **依赖关系的自动级联清理**。一个任务完成后，自动解除对其他任务的阻塞。
3. **`synchronized` 保证并发安全**。所有方法都加了同步锁，避免多线程同时修改同一任务。
4. **任务系统为多 Agent 协作打基础**。`owner` 字段现在没用上，但 S09+ 开始就需要它了。

## 下一步

S07 的任务系统让 Agent 能管理复杂的多步骤工作。但有些命令需要运行很长时间（比如编译大项目、跑测试套件），如果 Agent 傻等命令执行完才能继续，效率很低。S08 引入后台任务。
