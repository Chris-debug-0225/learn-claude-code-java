# S06 — 上下文压缩

## 这个阶段要学什么

Agent 每一轮循环都会往消息历史里追加内容：用户输入、模型回复、工具调用结果……对话越长，消息历史越大，最终一定会碰到模型的上下文窗口上限。

到了上限会怎样？**API 直接报错，Agent 崩掉。**

S06 要解决的就是这个问题：**在不丢失重要信息的前提下，把过长的消息历史压缩下来**。

做法分三层，从轻到重：

| 层级 | 名字 | 做什么 | 什么时候触发 |
|------|------|--------|------------|
| 第一层 | micro compact | 把旧的工具结果替换成 `[cleared]` | 每轮循环自动 |
| 第二层 | auto compact | 让模型生成整段对话的摘要 | 超过 token 阈值时自动 |
| 第三层 | manual compact | 用户或模型主动触发压缩 | 模型调用 compact 工具时 |

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S06ContextCompact
```

## 你可以试试这些输入

连续给它多个任务，让对话变长：
1. "读一下 pom.xml"
2. "读一下 AgentRuntime.java"
3. "读一下 StageConfig.java"
4. "读一下 CommandTools.java"
5. ... 一直读下去

观察控制台，当对话足够长时你会看到 `[auto-compact triggered]` 的提示。

或者直接对它说："压缩一下当前的对话上下文"，它会调用 `compact` 工具。

## 要读的源码

### 1. `CompressionService` — 三层压缩的实现

**第一层：`microCompact()` — 清理旧的工具结果**

```java
public void microCompact(List<ChatMessage> messages) {
    // 1. 找出所有消息中的 tool_result 块
    // 2. 只保留最近 keepRecent 条的完整内容
    // 3. 更早的工具结果，如果超过 100 字符，替换成 "[cleared]"
}
```

这一层的思路是：**旧的工具输出细节不重要了，但"调用过这个工具"这个事实要保留**。

比如，10 轮之前读过一个 500 行的文件，现在具体内容已经不需要了，但模型需要知道"我曾经读过这个文件"。所以不是删掉整条消息，而是把内容替换成 `[cleared]`。

**第二层：`autoCompact()` — 用模型生成摘要**

```java
public List<ChatMessage> autoCompact(List<ChatMessage> messages) {
    // 1. 把完整对话历史保存到 .transcripts/transcript_时间戳.jsonl
    // 2. 把整段对话发给模型，让它生成摘要
    // 3. 用 "摘要 + 确认消息" 替换原来的全部消息历史
}
```

压缩后的消息历史只有两条：

```
[user]      "[Conversation compressed. Transcript: .transcripts/xxx.jsonl]\n\n摘要内容..."
[assistant] "Understood. I have the context from the summary. Continuing."
```

关键设计：**原始对话不会丢失，它被完整保存到了 transcript 文件里**。所以压缩是安全的——如果需要回溯，可以去看 transcript。

**阈值判断：`needsAutoCompact()`**

```java
public boolean needsAutoCompact(List<ChatMessage> messages) {
    return estimateTokens(messages) > threshold;  // threshold = 50000
}
```

Token 估算用的是 `JSON长度 / 4`，是一个粗略但够用的近似值。

**第三层：手动压缩**

模型也可以主动调用 `compact` 工具来触发压缩。在 `agentLoop` 中：

```java
case "compact" -> {
    manualCompact = true;  // 先标记，等工具结果写回后再真正压缩
    yield "Compressing...";
}
// ... 所有工具执行完后
if (manualCompact) {
    List<ChatMessage> compacted = compressionService.autoCompact(messages);
    messages.clear();
    messages.addAll(compacted);
}
```

### 2. `AgentRuntime.agentLoop()` 中的调用时机

```java
while (true) {
    // 每轮循环开头先做 micro compact
    if (config.enableCompression()) {
        compressionService.microCompact(messages);
        if (compressionService.needsAutoCompact(messages)) {
            System.out.println("[auto-compact triggered]");
            // 自动做完整压缩
        }
    }
    // ... 然后继续正常的模型调用和工具执行
}
```

## 类比理解

想象你在做笔记：

- **Micro compact** = 把旧笔记里的详细数据擦掉，只留标题："第3页：读过 pom.xml"
- **Auto compact** = 把整本笔记本拍照存档，然后用一张新纸写一段摘要继续工作
- **Manual compact** = 你主动说"太乱了，让我整理一下笔记"

## 核心概念图

```
消息历史膨胀过程:
  [user] 问题1
  [assistant] 回答1 + 工具调用
  [user] 工具结果（500行代码）    ← micro compact 会把这个替换成 [cleared]
  [assistant] 回答2
  [user] 问题2
  ...
  [user] 工具结果（大文件）       ← 最近 3 条保留完整
  [assistant] 回答N

当 token 数超过 50000:
  → 保存完整历史到 .transcripts/transcript_xxx.jsonl
  → 让模型生成摘要
  → 消息历史变成只有 2 条（摘要 + 确认）
  → 继续工作
```

## 你学到了什么

1. **上下文窗口是 Agent 的硬约束**。不处理这个问题，Agent 做长任务必然崩掉。
2. **分层压缩是 Claude Code 风格的解决方案**。先轻量清理，不够再完整压缩，渐进式减负。
3. **原始记录不丢失**。transcript 文件保留了完整历史，压缩是可逆的。
4. **用模型自己做摘要**。比机械截断好得多，因为模型知道哪些信息对继续工作最重要。

## 下一步

S01-S06 构建了一个单 Agent 的完整能力栈。S07 开始引入更结构化的任务管理——从内存中的 todo 升级为文件持久化的任务板。
