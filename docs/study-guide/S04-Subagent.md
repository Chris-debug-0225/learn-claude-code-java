# S04 — 子代理

## 这个阶段要学什么

S03 的 Agent 已经能列计划、追踪进度了。但有时候任务需要先"调研"一下：

- "项目里有没有用过 Jackson？用法是什么样的？（请使用 task 工具）"
- "哪些文件里引用了这个类？"
- "这个 bug 的根因是什么？"

这些调研可能涉及大量的文件读取和命令执行，会产生很多中间输出。如果这些中间输出全部堆在主对话的消息历史里，主 Agent 的上下文就会被"污染"——大量的调研细节会挤占有限的上下文空间。

S04 的解决方案是**子代理**：把调研任务交给一个"分身"去做，分身有自己独立的上下文，做完后只把结论汇报回来。

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S04Subagent
```

## 你可以试试这些输入

- "帮我调查一下这个项目用了哪些 Maven 依赖，每个依赖是干什么的"
- "分析一下 AgentRuntime.java 和 StageConfig.java 之间的关系"

观察控制台输出，你会看到子代理在执行工具调用（`> read_file: ...`），但这些中间过程不会出现在主 Agent 的消息历史里。

## 要读的源码

### 1. `StageConfig.s04()` — 新增 task 工具

```java
public static StageConfig s04() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("task", "Spawn a subagent with fresh context.", ...));
    return new StageConfig("s04", false, false, false, false, false, false, tools, "...");
}
```

注意这里的工具名叫 `task`，不叫 `subagent`。模型看到的描述是"Spawn a subagent with fresh context"——用全新的上下文开一个子代理。

### 2. `AgentRuntime.runSubagent()` — 子代理的实现

```java
private String runSubagent(String prompt, boolean writable) {
    // 1. 准备子代理可用的工具
    List<Map<String, Object>> subTools = new ArrayList<>(StageConfig.baseTools());
    if (!writable) {
        subTools.removeIf(tool -> List.of("write_file", "edit_file").contains(tool.get("name")));
    }

    // 2. 创建独立的消息历史（全新的上下文！）
    List<ChatMessage> subMessages = new ArrayList<>();
    subMessages.add(new ChatMessage("user", prompt));

    // 3. 子代理自己跑循环，最多 30 轮
    for (int i = 0; i < 30; i++) {
        var response = client.createMessage(
            "You are a coding subagent at ...",  // 独立的 system prompt
            subMessages,                          // 独立的消息历史
            subTools,                             // 独立的工具集
            8000);

        // 和主循环一样的工具执行逻辑
        // 区别是：这些中间结果只存在于 subMessages 中
        // 主 Agent 的 messages 完全不受影响
    }
}
```

核心设计思想：

1. **独立上下文**：子代理有自己的 `subMessages`，和主 Agent 的 `messages` 完全隔离
2. **共享模型客户端**：子代理和主代理用的是同一个 `AnthropicClient`，只是对话内容不同
3. **可控权限**：`writable` 参数控制子代理能不能写文件（S04 阶段默认不允许写）
4. **只返回结论**：子代理跑完后，只把最终的文本回复返回给主 Agent

### 3. 子代理的工具权限

```java
if (!writable) {
    subTools.removeIf(tool -> List.of("write_file", "edit_file").contains(tool.get("name")));
}
```

S04 阶段的 `subagentWritable` 是 `false`，意味着子代理只能读不能写。这是一个重要的安全设计：

- 主 Agent 负责决策和执行修改
- 子代理只负责调研和汇报
- 避免在不受控的子上下文里直接改代码

## 类比理解

你可以把主 Agent 想象成一个项目经理，子代理就是一个临时助手：

```
项目经理（主 Agent）: "去调查一下项目用了哪些依赖"
    ↓
助手（子代理）: 
    - 读 pom.xml
    - 读每个依赖的说明
    - 整理成报告
    ↓
助手把报告交给项目经理
项目经理继续自己的工作，桌上只多了一份报告
（助手的草稿纸、查阅的资料不会堆在项目经理的桌上）
```

## 核心概念图

```
主 Agent 消息历史:
  [user] "调查项目用了哪些依赖"
  [assistant] tool_use → task("分析 pom.xml...")
  [user] tool_result → "项目使用了 3 个依赖: 1. jackson-databind..."
  [assistant] "根据调查结果，项目使用了..."

子代理消息历史（独立的，用完即丢）:
  [user] "分析 pom.xml..."
  [assistant] tool_use → read_file("pom.xml")
  [user] tool_result → "<project>...</project>"
  [assistant] tool_use → bash("mvn dependency:tree")
  [user] tool_result → "..."
  [assistant] "项目使用了 3 个依赖: 1. jackson-databind..."
```

## 你学到了什么

1. **上下文隔离**是 Agent 架构中非常重要的概念。不同任务的中间过程不应该互相污染。
2. **子代理不是新进程**。它只是在同一个 JVM 里、用同一个模型客户端、开了一段新的对话历史。
3. **权限控制**让子代理安全可控。调研型子代理不需要写权限。
4. **只传递结论，不传递过程**。这是减轻主上下文负担的关键。

## 下一步

子代理解决了"调研不污染主上下文"的问题。但模型面对陌生领域时（比如某个没用过的框架），它可能需要先学习一些知识才能开始工作。S05 引入技能加载机制。
