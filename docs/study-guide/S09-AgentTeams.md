# S09 — 多 Agent 团队

## 这个阶段要学什么

S04 的子代理是"用完即丢"的：做完一个调研任务就消失了。S09 引入了一个全新概念——**持续运行的队友**。

在这个阶段，有两种角色：

- **Lead（主代理）**：就是你在终端里对话的那个 Agent，它是团队的领导者
- **Teammate（队友）**：由 Lead 创建的长期协作代理，在后台独立线程中运行

Lead 可以随时创建队友、给队友发消息、查看队友状态。队友有自己独立的 Agent 循环，能执行工具、读写文件，完成后通过消息回报。

和 S04 子代理的区别：

| | S04 子代理 | S09 队友 |
|---|---|---|
| 生命周期 | 做完一个任务就消失 | 持续运行，可以接多个任务 |
| 通信方式 | 只返回最终结果 | 通过 inbox 持续双向通信 |
| 运行线程 | 在主线程同步执行 | 独立守护线程异步执行 |
| 工具集 | 基础工具子集 | 基础工具 + 通信工具 |

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S09AgentTeams
```

## 你可以试试这些输入

- "创建一个名叫 alice 的队友，角色是代码分析师，让她分析项目的依赖关系"
- "再创建一个叫 bob 的队友，角色是文档编写者，让他写一份项目简介"
- "看看现在有哪些队友"
- "给 alice 发消息：你分析得怎么样了？"
- "读一下收件箱"

## 要读的源码

### 1. `StageConfig.s09()` — Lead 的工具集

```java
public static StageConfig s09() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("spawn_teammate",  "Spawn a persistent teammate.",        ...));
    tools.add(tool("list_teammates",  "List all teammates.",                 ...));
    tools.add(tool("send_message",    "Send a message to a teammate.",       ...));
    tools.add(tool("read_inbox",      "Read and drain the lead inbox.",      ...));
    tools.add(tool("broadcast",       "Send message to all teammates.",      ...));
    return new StageConfig("s09", false, false, false, true, false, false, tools,
        "You are a team lead at ${WORKDIR}. Spawn teammates and communicate via inboxes.");
    //                                       ↑ enableInbox = true
}
```

### 2. `MessageBus` — 文件型消息总线

通信的基础设施。每个 Agent 有一个 inbox 文件（JSONL 格式）：

```
.team/
└── inbox/
    ├── lead.jsonl      ← Lead 的收件箱
    ├── alice.jsonl      ← Alice 的收件箱
    └── bob.jsonl        ← Bob 的收件箱
```

**发送消息**：

```java
public synchronized String send(String sender, String to, String content, String msgType, ...) {
    Map<String, Object> message = Map.of(
        "type", msgType,
        "from", sender,
        "content", content,
        "timestamp", ...
    );
    // 往接收方的 inbox 文件追加一行 JSON
    Path path = inboxDir.resolve(to + ".jsonl");
    Files.writeString(path, JsonUtils.toJson(message) + "\n", APPEND);
}
```

**读取收件箱**：

```java
public synchronized List<Map<String, Object>> readInbox(String name) {
    Path path = inboxDir.resolve(name + ".jsonl");
    List<String> lines = Files.readAllLines(path);
    Files.writeString(path, "");  // 读取后立即清空！"读即消费"
    // 把每行 JSON 解析成消息对象返回
}
```

"读即消费"意味着：消息读过一次后就没了。如果你再读一次，收件箱是空的。

### 3. `TeammateManager.spawn()` — 创建队友

```java
public synchronized String spawn(String name, String role, String prompt, boolean autonomous) {
    // 1. 在 config.json 中注册队友
    // 2. 启动独立守护线程运行队友循环
    Thread thread = new Thread(() -> loop(name, role, prompt, autonomous));
    thread.setDaemon(true);
    thread.start();
    return "Spawned '" + name + "' (role: " + role + ")";
}
```

### 4. `TeammateManager.loop()` — 队友的 Agent 循环

每个队友都有自己完整的 Agent 循环：

```java
private void loop(String name, String role, String prompt, boolean autonomous) {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage("user", prompt));

    while (true) {
        // 1. 先读自己的 inbox
        for (Map<String, Object> msg : bus.readInbox(name)) {
            messages.add(new ChatMessage("user", JsonUtils.toJson(msg)));
        }

        // 2. 调用模型
        var response = client.createMessage(system, messages, tools, 4000);

        // 3. 如果模型不再调用工具 → 工作完成，进入 idle
        if (!"tool_use".equals(response.stop_reason())) {
            setStatus(name, "idle");
            return;
        }

        // 4. 执行工具（和主 Agent 一样的模式）
        switch (toolName) {
            case "bash"         -> commandTools.runBash(...);
            case "read_file"    -> commandTools.runRead(...);
            case "send_message" -> bus.send(name, to, content, ...);
            case "read_inbox"   -> bus.readInbox(name);
            // ...
        }
    }
}
```

注意队友的工具和 Lead 不同：队友有 `send_message` 和 `read_inbox`，但没有 `spawn_teammate`（队友不能再创建队友）。

### 5. Lead 的自动 inbox 轮询

在 `AgentRuntime.agentLoop()` 中：

```java
if (config.enableInbox()) {
    List<Map<String, Object>> inbox = messageBus.readInbox("lead");
    if (!inbox.isEmpty()) {
        messages.add(new ChatMessage("user", "<inbox>" + JsonUtils.toPrettyJson(inbox) + "</inbox>"));
        messages.add(new ChatMessage("assistant", "Noted inbox messages."));
    }
}
```

Lead 每轮循环开头都会自动检查自己的 inbox。如果队友发来了消息，会自动注入到 Lead 的对话历史中。

## 核心概念图

```
终端（你）⟷ Lead Agent（主线程）
                │
           spawn_teammate
                │
        ┌───────┼───────┐
        ↓               ↓
   Alice（线程1）    Bob（线程2）
   独立 Agent 循环   独立 Agent 循环
   独立消息历史      独立消息历史
        │               │
        └───── 通过 inbox 文件通信 ─────┘

.team/inbox/
  lead.jsonl  ← Alice 和 Bob 给 Lead 发消息到这里
  alice.jsonl ← Lead 给 Alice 发消息到这里
  bob.jsonl   ← Lead 给 Bob 发消息到这里
```

## 你学到了什么

1. **多 Agent 协作本质上是"多个 Agent 循环 + 消息通信"**。每个队友都有完整的循环能力。
2. **文件 inbox 是最简单的进程间通信方式**。不需要消息队列、不需要网络，文件系统就够了。
3. **"读即消费"保证消息不重复处理**。读一次后 inbox 就清空了。
4. **守护线程让队友和 Lead 并行运行**。Lead 不需要等队友做完才能继续。

## 下一步

S09 让多个 Agent 能协作了，但没有管理协议：Lead 没法让队友停下来，队友也不会在关键操作前征求 Lead 的同意。S10 引入 shutdown 和 plan approval 协议。
