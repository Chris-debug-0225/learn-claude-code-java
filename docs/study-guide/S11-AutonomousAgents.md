# S11 — 自治队友

## 这个阶段要学什么

S09-S10 的队友是"被动"的：Lead 创建它时给一个任务，它做完就停下来等新指令。Lead 需要手动给每个队友分配工作。

S11 让队友变成"自治"的——**队友做完当前任务后，会自动去任务板上找活干**。

就像一个优秀的员工：你不需要每次都告诉他"接下来做什么"，他会自己看看任务板上还有什么没人做的，主动认领一个开始干。

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S11AutonomousAgents
```

## 你可以试试这些输入

先创建一堆任务，再创建自治队友：

1. "创建任务：分析项目结构"
2. "创建任务：统计代码行数"
3. "创建任务：列出所有依赖"
4. "创建一个叫 alice 的队友，角色是分析师，让她自己去找活干"

然后观察 Alice 是否自动认领了任务板上的任务。

## 要读的源码

### 1. `StageConfig.s11()` — 新增的能力

```java
public static StageConfig s11() {
    List<Map<String, Object>> tools = new ArrayList<>(s10().tools());
    tools.add(tool("claim_task", "Claim a task from the board.", ...));
    tools.add(tool("idle",       "Enter idle state.",            ...));
    tools.addAll(/* 把 s07 的 task_create/task_update/task_list/task_get 也加进来 */);
    return new StageConfig("s11", false, false, false, true, false, true, tools,
        "You are a team lead at ${WORKDIR}. Teammates are autonomous -- they find work themselves.");
    //                                                           ↑ autonomousTeammates = true
}
```

关键变化：
- `autonomousTeammates = true`
- 加入了 `claim_task` 和 `idle` 工具
- 合并了任务系统工具

### 2. `TeammateManager.loop()` — 自治循环的差异

非自治队友（S09/S10）做完任务后直接退出：

```java
if (!"tool_use".equals(response.stop_reason())) {
    setStatus(name, "idle");
    return;  // 退出
}
```

自治队友做完后不退出，而是尝试找新任务：

```java
if (!"tool_use".equals(response.stop_reason())) {
    if (autonomous) {
        setStatus(name, "idle");
        // 不退出！而是尝试找新任务
        if (!resumeAutonomous(name, role, teamName, messages)) {
            setStatus(name, "shutdown");
            return;  // 找不到任务才退出
        }
        setStatus(name, "working");
        continue;  // 继续循环！
    }
    // ...
}
```

### 3. `resumeAutonomous()` — 自动找活干的核心逻辑

```java
private boolean resumeAutonomous(String name, String role, String teamName, List<ChatMessage> messages) {
    int attempts = 60 / 5;  // 最多等 60 秒

    for (int i = 0; i < attempts; i++) {
        Thread.sleep(5000);  // 每 5 秒检查一次

        // 优先检查 inbox，有新消息就继续工作
        List<Map<String, Object>> inbox = bus.readInbox(name);
        if (!inbox.isEmpty()) {
            for (Map<String, Object> msg : inbox) {
                if ("shutdown_request".equals(msg.get("type"))) {
                    return false;  // 收到关闭请求，退出
                }
                messages.add(new ChatMessage("user", JsonUtils.toJson(msg)));
            }
            return true;  // 有新消息，继续工作
        }

        // 没有消息就扫描任务板
        List<TaskRecord> unclaimed = taskManager.scanUnclaimed();
        if (!unclaimed.isEmpty()) {
            TaskRecord task = unclaimed.get(0);
            taskManager.claim(task.id, name);
            // 注入认领消息
            messages.add(new ChatMessage("user",
                "<auto-claimed>Task #" + task.id + ": " + task.subject + "\n" + task.description + "</auto-claimed>"));
            messages.add(new ChatMessage("assistant",
                "Claimed task #" + task.id + ". Working on it."));
            return true;  // 认领成功，继续工作
        }
    }

    return false;  // 60 秒内没有任何新任务或消息，退出
}
```

### 4. `TaskManager.scanUnclaimed()` — 扫描可认领任务

```java
public synchronized List<TaskRecord> scanUnclaimed() {
    List<TaskRecord> result = new ArrayList<>();
    for (TaskRecord task : loadAll()) {
        if ("pending".equals(task.status)           // 状态是待处理
            && (task.owner == null || task.owner.isBlank())  // 没人认领
            && task.blockedBy.isEmpty()) {           // 没有被阻塞
            result.add(task);
        }
    }
    return result;
}
```

只返回"现在就可以开始做"的任务：待处理 + 没人做 + 没有前置依赖。

## 自治队友的生命周期

```
创建 → working → 做完当前任务 → idle
                                 ↓
                        每 5 秒检查一次:
                        ├── 有新 inbox 消息？ → working（处理消息）
                        ├── 任务板有未认领的任务？ → claim → working
                        ├── 收到 shutdown_request？ → shutdown
                        └── 60 秒都没有？ → shutdown
```

## 类比理解

```
传统团队（S09-S10）:
  老板: "张三，你去做任务 A"
  张三: "做完了"
  老板: "好，你再去做任务 B"

自治团队（S11）:
  老板: "我在看板上列了 5 个任务"
  张三: 做完了手头的事 → 自己看看板 → "任务 C 没人做" → 认领 → 开始做
  李四: 同时也在看看板 → "任务 D 没人做" → 认领 → 开始做
```

## 核心概念图

```
Lead 创建任务:
  .tasks/task_1.json  status: pending, owner: ""
  .tasks/task_2.json  status: pending, owner: ""
  .tasks/task_3.json  status: pending, owner: ""

Alice 自治循环:
  初始任务做完 → idle
    → scanUnclaimed() → 发现 task_1
    → claim(1, "alice") → task_1.owner = "alice", status = "in_progress"
    → 开始做 task_1
    → 做完 → idle
    → scanUnclaimed() → 发现 task_2
    → claim(2, "alice")
    → ...

Bob 同时也在自治:
  → scanUnclaimed() → 发现 task_3（task_1 和 task_2 已被 alice 认领）
  → claim(3, "bob")
  → 开始做 task_3
```

## 你学到了什么

1. **自治 = 做完后自动找新活**。队友从"被动接受任务"变成"主动扫描任务板"。
2. **轮询机制简单有效**。每 5 秒检查一次 inbox 和任务板，不需要事件驱动或回调。
3. **消息优先于任务认领**。如果 inbox 有新消息，优先处理消息而不是盲目抢任务。
4. **超时自动退出**。60 秒内没有新任务和新消息就自动关闭，避免空转浪费资源。

## 下一步

多个自治队友同时工作时，它们都在同一个目录下读写文件，可能会冲突。S12 引入 worktree 机制，给每个任务分配独立的工作目录。
