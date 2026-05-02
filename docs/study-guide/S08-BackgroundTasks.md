# S08 — 后台任务

## 这个阶段要学什么

假设你让 Agent 跑一个耗时 30 秒的测试命令。在 S01-S07 里，Agent 会卡在那里等 30 秒，这期间什么也做不了。

S08 引入后台任务机制：Agent 可以把耗时命令"丢到后台去跑"，自己继续和你对话。命令跑完后，结果会自动被注入回 Agent 的消息历史。

```
用户: "在后台跑一下 mvn compile"
Agent: "好的，后台任务 abc123 已启动"
用户: "帮我看看 README 写了什么"
Agent: （一边回答你的问题）
      （后台 mvn compile 完成了，结果自动注入）
Agent: "顺便告诉你，刚才的 mvn compile 成功了"
```

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S08BackgroundTasks
```

## 你可以试试这些输入

- "在后台运行 `ping -c 5 localhost`"（Linux/Mac）或 "在后台运行 `ping -n 5 localhost`"（Windows）
- 然后立刻问它别的问题，比如"读一下 pom.xml"
- 再问"刚才的后台任务完成了吗？"

## 要读的源码

### 1. `StageConfig.s08()` — 两个新工具

```java
public static StageConfig s08() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("background_run",   "Run command in background thread.", ...));
    tools.add(tool("check_background", "Check background task status.",     ...));
    return new StageConfig("s08", false, false, true, false, false, false, tools, "...");
    //                                       ↑ enableBackground = true
}
```

### 2. `BackgroundManager` — 后台任务管理

这个类有两个核心数据结构：

```java
// 所有后台任务的状态表
ConcurrentHashMap<String, Map<String, Object>> tasks;

// 已完成任务的通知队列
BlockingQueue<Map<String, Object>> notifications;
```

**启动后台任务**：

```java
public String run(String command, int timeoutSeconds) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);  // 短 ID
    tasks.put(taskId, Map.of("status", "running", "command", command, "result", ""));

    // 关键：在独立线程中执行，不阻塞主循环
    Executors.newSingleThreadExecutor().submit(() -> execute(taskId, command, timeoutSeconds));

    return "Background task " + taskId + " started: " + command;
}
```

**后台执行**：

```java
private void execute(String taskId, String command, int timeoutSeconds) {
    // 1. 创建进程，在工作区目录中运行命令
    ProcessBuilder builder = new ProcessBuilder(commandTools.shellCommand(command));
    builder.directory(paths.workdir().toFile());
    Process process = builder.start();

    // 2. 等待命令完成（有超时保护）
    boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

    // 3. 更新 tasks 表
    tasks.put(taskId, Map.of("status", status, "command", command, "result", result));

    // 4. 投递通知到队列
    notifications.offer(Map.of("task_id", taskId, "status", status, "result", 结果摘要));
}
```

**取出通知**：

```java
public List<Map<String, Object>> drain() {
    List<Map<String, Object>> result = new ArrayList<>();
    notifications.drainTo(result);  // 一次性取出所有已完成的通知
    return result;
}
```

### 3. `AgentRuntime.agentLoop()` — 自动注入后台结果

在每轮循环开头：

```java
if (config.enableBackground()) {
    List<Map<String, Object>> notifs = backgroundManager.drain();
    if (!notifs.isEmpty()) {
        // 把后台完成的结果作为一条 user 消息注入
        messages.add(new ChatMessage("user",
            "<background-results>\n"
            + "[bg:abc123] completed: 编译成功...\n"
            + "</background-results>"));
        messages.add(new ChatMessage("assistant", "Noted background results."));
    }
}
```

这就是"后台结果自动回注"的核心机制：

1. 后台线程执行完毕后把结果放入通知队列
2. 主循环每轮开头检查通知队列
3. 有通知就作为新消息注入历史
4. 模型下一轮就能看到后台任务的结果

## 为什么需要后台任务

```
没有后台任务：
  用户: "编译项目"
  Agent: bash("mvn compile")  ← 阻塞 30 秒
  Agent: "编译完成"
  用户: 这 30 秒里什么也做不了 😤

有了后台任务：
  用户: "在后台编译项目"
  Agent: background_run("mvn compile")  ← 立即返回
  Agent: "已启动后台编译"
  用户: "帮我看看还有哪些 TODO 注释"  ← 不用等编译完就能继续
  Agent: 正常回答...
  (编译完成，结果自动注入)
  Agent: "顺便告诉你，编译成功了"
```

## 核心概念图

```
主线程（Agent 循环）          后台线程
       │                        │
  用户输入                      │
       │                        │
  模型调用 background_run ──────→ 启动 mvn compile
       │                        │
  返回 taskId                    │（编译中...）
       │                        │
  继续处理用户其他请求            │
       │                        │
  ┌──下一轮循环开头───┐           │
  │ drain() 检查通知 │           │
  │ 暂时没有         │          │
  └────────────────┘          │
       │                        │
  继续处理...                   编译完成
       │                        │
  ┌──下一轮循环开头───┐           │
  │ drain() 检查通知 │←─────── 投递通知
  │ 有了！注入消息   │
  └────────────────┘
       │
  模型看到编译结果，告诉用户
```

## 你学到了什么

1. **异步执行 = 不阻塞主循环**。耗时命令在独立线程跑，Agent 可以继续服务用户。
2. **通知队列是后台和前台的桥梁**。`BlockingQueue` + `drain()` 实现了线程安全的结果传递。
3. **结果注入是透明的**。后台结果作为普通消息注入历史，模型不需要知道"这是后台来的"——它只看到一条新消息。
4. **`ConcurrentHashMap` 保证线程安全**。主线程和后台线程可能同时访问任务状态。

## 下一步

到 S08 为止，我们一直在讨论"单个 Agent"的能力增强。S09 开始进入全新领域——**多个 Agent 协作**。一个 Agent 不够用怎么办？创建一个团队！
