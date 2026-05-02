# S10 — 团队协议

## 这个阶段要学什么

S09 让多个 Agent 能一起工作了。但有两个问题：

1. **Lead 没法让队友停下来**。如果一个队友出了问题或者任务取消了，Lead 无法关闭它。
2. **队友不会征求意见**。如果队友要做一个重大操作（比如重构核心代码），Lead 希望先审批再执行。

S10 引入两个协议来解决这些问题：

### Shutdown 协议

```
Lead: "Alice，请关闭"       → shutdown_request 发送到 alice 的 inbox
Alice: "好的，我整理一下"    → 完成收尾工作
Alice: "我可以关闭了"        → shutdown_response 发回 lead 的 inbox
Alice 线程退出
```

### Plan Approval 协议

```
Alice: "我打算重构整个工具层" → plan_approval 发送到 lead 的 inbox
Lead:  "同意/拒绝 + 反馈"    → plan_approval_response 发回 alice 的 inbox
Alice: 根据审批结果继续或调整
```

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S10TeamProtocols
```

## 你可以试试这些输入

- "创建一个叫 alice 的队友，让她分析项目结构"
- 等 Alice 完成后："关闭 alice"
- 或者："看看收件箱有没有 Alice 的审批请求"

## 要读的源码

### 1. `StageConfig.s10()` — 在 S09 基础上加两个工具

```java
public static StageConfig s10() {
    List<Map<String, Object>> tools = new ArrayList<>(s09().tools());
    tools.add(tool("shutdown_request", "Request teammate shutdown.",     ...));
    tools.add(tool("plan_approval",    "Approve or reject a teammate plan.", ...));
    return new StageConfig("s10", false, false, false, true, false, false, tools, "...");
}
```

### 2. Shutdown 协议的实现

**Lead 端：发起关闭请求**

```java
public String handleShutdownRequest(String teammate) {
    String requestId = UUID.randomUUID().toString().substring(0, 8);
    shutdownRequests.put(requestId, Map.of("target", teammate, "status", "pending"));
    bus.send("lead", teammate, "Please shut down.", "shutdown_request", Map.of("request_id", requestId));
    return "Shutdown request " + requestId + " sent to '" + teammate + "'";
}
```

**队友端：收到 shutdown_request**

在 `TeammateManager.loop()` 的 inbox 处理部分：

```java
for (Map<String, Object> inboxMessage : bus.readInbox(name)) {
    if ("shutdown_request".equals(inboxMessage.get("type"))) {
        if (autonomous) {
            setStatus(name, "shutdown");
            return;  // 自治模式直接退出
        }
    }
    messages.add(new ChatMessage("user", JsonUtils.toJson(inboxMessage)));
}
```

非自治模式下，shutdown_request 会变成一条消息注入队友的上下文，队友可以先完成收尾工作再通过 `shutdown_response` 工具回复。

**队友端：回复关闭（通过 shutdown_response 工具）**

```java
case "shutdown_response" -> {
    // 通知 lead
    bus.send(name, "lead", reason, "shutdown_response", Map.of("request_id", reqId, "approve", approve));
    if (approve) {
        setStatus(name, "shutdown");
        return;  // 退出循环
    }
}
```

### 3. Plan Approval 协议的实现

**队友端：提交审批请求**

```java
case "plan_approval" -> {
    String reqId = UUID.randomUUID().toString().substring(0, 8);
    planRequests.put(reqId, Map.of("from", name, "plan", plan, "status", "pending"));
    bus.send(name, "lead", plan, "plan_approval_response", Map.of("request_id", reqId));
}
```

**Lead 端：审批**

```java
public String handlePlanReview(String requestId, boolean approve, String feedback) {
    Map<String, Object> request = planRequests.get(requestId);
    request.put("status", approve ? "approved" : "rejected");
    bus.send("lead", request.get("from"), feedback, "plan_approval_response",
        Map.of("request_id", requestId, "approve", approve));
    return "Plan " + request.get("status") + " for '" + request.get("from") + "'";
}
```

## 为什么需要协议

没有协议的多 Agent 系统就像一个没有管理制度的公司：

- 员工可能在错误方向上一直做下去，没人能叫停
- 员工做了重大决策但老板不知道
- 没法有序地结束某个员工的工作

协议的本质就是**结构化的消息 + 状态机**：

```
Shutdown 协议状态机:
  pending → approved → (线程退出)
         → rejected → (继续工作)

Plan Approval 协议状态机:
  pending → approved → (执行计划)
         → rejected → (修改计划或放弃)
```

## 核心概念图

```
Shutdown 协议:
  Lead ──shutdown_request──→ Alice 的 inbox
  Lead ←──shutdown_response── Alice
  Alice 线程退出，状态变为 "shutdown"

Plan Approval 协议:
  Alice ──plan_approval──→ Lead 的 inbox
  (Alice 等待...)
  Lead ──plan_approval_response──→ Alice 的 inbox
  Alice 收到审批结果，继续或调整
```

## 你学到了什么

1. **协议 = 约定好的消息类型 + 约定好的行为**。`shutdown_request` 和 `shutdown_response` 就是一对约定好的消息类型。
2. **requestId 把请求和响应关联起来**。每个请求都有唯一 ID，回复时带上这个 ID，Lead 就知道这是对哪个请求的回复。
3. **队友不是立刻关闭的**。它收到 shutdown_request 后可以先做完手头的事，保证关闭是优雅的。
4. **审批机制实现了人类对 AI 的监督**。Lead 可以拒绝队友的计划并给出反馈。

## 下一步

S10 的队友需要 Lead 明确告诉它做什么。如果 Lead 创建了一堆任务，队友能不能自己去任务板上找活干？S11 引入自治队友。
