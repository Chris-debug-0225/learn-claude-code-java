# S12 — Worktree 任务隔离

## 这个阶段要学什么

S11 的多个自治队友同时在工作，但它们都在**同一个目录**下操作文件。如果 Alice 在改 `Main.java`，Bob 也在改 `Main.java`，就会互相覆盖。

S12 用 **worktree** 解决这个问题：给每个任务分配一个独立的工作目录。每个队友在自己的目录里干活，互不干扰。

> Worktree 这个概念来自 Git（`git worktree`），在 Git 中它是同一个仓库的多个工作目录副本。在这个项目里是简化版——只是在 `.worktrees/` 下创建独立子目录。

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S12WorktreeTaskIsolation
```

## 你可以试试这些输入

1. "创建任务：实现用户登录功能"
2. "为任务 1 创建一个 worktree，名叫 login-feature"
3. "列出所有 worktree"
4. "查看 worktree 的生命周期事件"
5. 做完后："移除 worktree login-feature"

## 要读的源码

### 1. `StageConfig.s12()` — 四个 worktree 工具

```java
public static StageConfig s12() {
    List<Map<String, Object>> tools = new ArrayList<>(s11().tools());
    tools.add(tool("worktree_create",  "Create a task-bound worktree lane.",      ...));
    tools.add(tool("worktree_list",    "List all worktrees.",                     ...));
    tools.add(tool("worktree_remove",  "Remove a worktree.",                     ...));
    tools.add(tool("worktree_events",  "List recent worktree lifecycle events.",  ...));
    return new StageConfig("s12", ...);
}
```

### 2. `WorktreeManager` — worktree 管理器

这个类管理三样东西：

```
.worktrees/
├── index.json          ← 所有 worktree 的索引
├── events.jsonl        ← 生命周期事件流水
├── login-feature/      ← 具体的工作目录
└── bug-fix/            ← 另一个工作目录
```

**创建 worktree**：

```java
public synchronized String create(String name, int taskId) {
    // 1. 创建目录
    Path worktreePath = paths.worktreesDir().resolve(name);
    Files.createDirectories(worktreePath);

    // 2. 构建元数据
    Map<String, Object> item = Map.of(
        "name", name,
        "path", worktreePath.toString(),
        "branch", "wt/" + name,
        "task_id", taskId,
        "status", "active"
    );

    // 3. 加入索引
    List<Map<String, Object>> items = worktrees();
    items.add(item);
    saveIndex(items);

    // 4. 绑定到任务
    taskManager.bindWorktree(taskId, name, "");

    // 5. 记录事件
    emit("worktree_created", taskId, item, null);
}
```

创建 worktree 会同时做三件事：建目录、更新索引、绑定任务。

**移除 worktree**：

```java
public synchronized String remove(String name, boolean keep) {
    // 找到要移除的 worktree
    // 如果 !keep，删除整个目录（深度逆序遍历，先文件后目录）
    // 从索引中移除
    // 解除任务绑定
    // 记录移除事件
}
```

`keep` 参数让你可以选择：
- `keep = false`：连目录带文件一起删（干净）
- `keep = true`：只从索引中移除，目录和文件保留（可能还要用）

**事件追踪**：

```java
private void emit(String event, int taskId, Map<String, Object> worktree, String error) {
    Map<String, Object> payload = Map.of(
        "event", event,          // "worktree_created" 或 "worktree_removed"
        "ts", 时间戳,
        "task", Map.of("id", taskId),
        "worktree", worktree
    );
    // 追加到 events.jsonl（一行一个 JSON）
    Files.writeString(eventsPath, JsonUtils.toJson(payload) + "\n", APPEND);
}
```

### 3. `TaskManager.bindWorktree()` — 任务与 worktree 的关联

```java
public synchronized String bindWorktree(int taskId, String worktree, String owner) {
    TaskRecord task = load(taskId);
    task.worktree = worktree;
    if (owner != null && !owner.isBlank()) {
        task.owner = owner;
    }
    // 如果任务还是 pending，绑定 worktree 后自动变为 in_progress
    if ("pending".equals(task.status)) {
        task.status = "in_progress";
    }
    save(task);
}
```

绑定后，任务记录长这样：

```json
{
  "id": 1,
  "subject": "实现用户登录",
  "status": "in_progress",
  "worktree": "login-feature",
  "owner": "alice",
  "blockedBy": [],
  "blocks": []
}
```

### 4. 索引文件 `index.json` 的格式

```json
{
  "worktrees": [
    {
      "name": "login-feature",
      "path": "/repo/.worktrees/login-feature",
      "branch": "wt/login-feature",
      "task_id": 1,
      "status": "active"
    },
    {
      "name": "bug-fix",
      "path": "/repo/.worktrees/bug-fix",
      "branch": "wt/bug-fix",
      "task_id": 2,
      "status": "active"
    }
  ]
}
```

## 为什么需要隔离

```
没有隔离（S09-S11）:
  Alice 改了 Main.java 第 10 行
  Bob   改了 Main.java 第 15 行
  → 如果用的是 write_file，后写的会覆盖先写的
  → 如果用的是 edit_file，可能因为文件已经变了导致找不到原文

有了隔离（S12）:
  Alice 在 .worktrees/login-feature/ 下工作
  Bob   在 .worktrees/bug-fix/ 下工作
  → 各自有独立的文件副本，互不干扰
```

## 完整的多 Agent 协作架构

把 S07-S12 的所有能力组合起来，就得到了一个完整的多 Agent 协作系统：

```
Lead:
  1. 创建任务    → .tasks/task_1.json, task_2.json, task_3.json
  2. 创建 worktree → .worktrees/wt-1/, .worktrees/wt-2/
  3. 绑定任务到 worktree
  4. 创建自治队友

Alice（自治）:
  → 自动认领 task_1
  → 在 .worktrees/wt-1/ 中工作
  → 完成后自动找下一个任务

Bob（自治）:
  → 自动认领 task_2
  → 在 .worktrees/wt-2/ 中工作
  → 互不干扰

通信:
  .team/inbox/lead.jsonl  ← 队友汇报进度
  .team/inbox/alice.jsonl ← Lead 下达指令
  .team/inbox/bob.jsonl   ← Lead 下达指令
```

## 事件追踪的价值

`events.jsonl` 记录了 worktree 的完整生命周期：

```jsonl
{"event":"worktree_created","ts":1711900000,"task":{"id":1},"worktree":{"name":"login-feature",...}}
{"event":"worktree_removed","ts":1711901000,"task":{"id":1},"worktree":{"name":"login-feature",...}}
```

这让你可以：
- 排查问题："这个任务是在哪个 worktree 里做的？什么时候创建的？什么时候删的？"
- 审计工作流："今天创建了多少个 worktree？哪些还没清理？"

## 你学到了什么

1. **多 Agent 并行工作需要隔离**。共享同一个目录必然导致冲突，worktree 通过独立目录解决这个问题。
2. **索引 + 目录 + 事件**是 worktree 的三要素。索引记录当前状态，目录是实际工作空间，事件记录变更历史。
3. **任务和 worktree 的绑定关系**让管理变得清晰。看任务记录就知道它在哪个目录里被处理。
4. **Keep/Remove 选项**提供了灵活的清理策略。做完的任务可以删 worktree 释放空间，也可以保留以备后用。

## 总结回顾

到 S12 为止，你已经学完了 Claude Code 风格 Agent 的全部核心机制：

| 阶段 | 解决的问题 |
|------|-----------|
| S01 | Agent 怎么跑起来（模型 + bash + 循环） |
| S02 | 怎么精确操作文件（专用工具比万能 bash 更好） |
| S03 | 长任务怎么保持连贯（Todo 状态追踪） |
| S04 | 调研怎么不污染主上下文（子代理隔离） |
| S05 | 面对陌生领域怎么办（技能加载） |
| S06 | 对话太长怎么办（分层压缩） |
| S07 | 任务怎么持久化管理（文件任务板） |
| S08 | 耗时命令怎么不阻塞（后台异步执行） |
| S09 | 一个 Agent 不够怎么办（多 Agent 团队） |
| S10 | 多 Agent 怎么管理（协议：关闭 + 审批） |
| S11 | 队友怎么自己找活干（自治认领） |
| S12 | 多人同时干活怎么不冲突（worktree 隔离） |

最后，`SFull` 把所有阶段的能力全部组合在一起，就是一个完整的 Claude Code 风格 Agent。
