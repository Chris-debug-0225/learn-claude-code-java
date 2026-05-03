# S03 — Todo 任务规划

## 这个阶段要学什么

前两个阶段的 Agent 已经能执行命令、读写文件了。但如果你给它一个复杂任务，比如"给项目里所有的类加上 Javadoc 注释"，它可能做了几个文件就忘了哪些做过、哪些没做。

S03 引入 `todo` 工具，让模型可以**给自己列一个任务清单**，边做边更新状态：

```
[ ] #1: 给 Main.java 加注释
[>] #2: 给 AgentRuntime.java 加注释    ← 正在做这个
[x] #3: 给 Launcher.java 加注释        ← 已完成

(1/3 completed)
```

这不是给你看的——是给模型自己看的。模型每做完一步，就更新 todo，然后在下一轮看到"还有哪些没做"，从而保持连贯性。

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S03TodoWrite
```

## 你可以试试这些输入

- "帮我在 src 目录下创建 3 个 Java 文件：test1.java、test2.java、test3.java，请使用todo规划，每个都写一个简单的算法"

给它一个需要多步骤完成的任务，观察它是不是会自动列 todo。

> 提示词约束：
>
> 1. 你必须严格按照工具的 JSON Schema 定义调用工具。
> 2. 禁止修改字段名，禁止将数组序列化为字符串。
> 3. 如果不确定格式，先输出 {"name": "...", "input": {...}} 的结构。
>
> 我增强了todo 工具的 description，包含完整的 JSON 示例和明确的格式约束。但感觉还是没有用，模型返回的依旧随心所欲。所以目前还是在代码层面拿到返回结果做容错处理。你也可以在输入提示词的时候做约束。
>
> ```java
>         tools.add(tool("todo",
>                 "Update task list. Track progress on multi-step tasks. " +
>                 "Input MUST be a JSON object with key \"items\" (an array, NOT a string). " +
>                 "Each item needs \"text\" (string) and \"status\" (pending/in_progress/completed). " +
>                 "Example: {\"items\": [{\"text\": \"Create Hello.java\", \"status\": \"pending\"}, {\"text\": \"Create World.java\", \"status\": \"in_progress\"}]}",
>                 Map.of("type", "object",
>                         "properties", Map.of("items",
>                                 Map.of("type", "array",
>                                         "items", Map.of("type", "object",
>                                                 "properties", Map.of(
>                                                         "text", Map.of("type", "string"),
>                                                         "status", Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed"))
>                                                 ),
>                                                 "required", List.of("text", "status")
>                                         )
>                                 )
>                         ),
>                         "required", List.of("items")
>                 )));
> ```

## 要读的源码

### 1. `StageConfig.s03()` — 新增了什么

```java
public static StageConfig s03() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("todo", "Update task list. Track progress on multi-step tasks.", ...));
    return new StageConfig("s03", true, false, false, false, false, false,
            tools, "...");
}
```

两个关键变化：
- 工具列表加了 `todo`
- `enableTodoNag` 设成了 `true`（第一个布尔参数）

### 2. `TodoManager.update()` — todo 工具的实现

```java
public String update(List<Map<String, Object>> newItems) {
    // 每个 item 需要有 text/content 和 status
    // status 只能是 pending（待处理） / in_progress（进行中） / completed（已完成）
    // 同一时间只能有一个 in_progress 的任务
    // 最多 20 个 todo
}
```

模型调用 todo 工具时，会传入整个 todo 列表（不是追加单条，而是整体替换）。这意味着模型需要记住之前的 todo 内容，并在此基础上更新。

`render()` 方法会把 todo 渲染成人类可读的格式：

```
[ ] #1: 读取所有 Java 文件
[>] #2: 统计行数                ← in_progress
[x] #3: 输出结果                ← completed
```

### 3. "Todo Nag" 机制 — 提醒模型更新 todo

在 `AgentRuntime.agentLoop()` 中：

```java
roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
if (config.enableTodoNag() && roundsWithoutTodo >= 3) {
    // 加一条提醒消息
    results.add(0, Map.of("type", "text", "text", "<reminder>Update your todos.</reminder>"));
}
```

如果模型连续 3 轮都没更新 todo，系统会自动在工具结果前面插一条提醒："你应该更新一下 todo 了"。

这是一个很巧妙的设计：**不是强制模型必须用 todo，而是温和地提醒它**。模型看到这个提醒后，通常就会把进度更新到 todo 里。

## 为什么 Todo 对 Agent 重要

想象你在写代码，同时要做 5 件事：
1. 你会在便利贴上列一个清单
2. 做完一件划掉一件
3. 如果被打断了，看一眼清单就知道做到哪了

Agent 也一样。模型的"记忆"就是消息历史，对话越长越容易"忘事"。Todo 就是模型的便利贴——一个显式的、结构化的进度追踪器。

## 核心概念图

```
用户: "创建 3 个 Java 文件"
        ↓
模型: 先调 todo 工具列计划
    [ ] 创建 Hello.java
    [ ] 创建 World.java
    [ ] 创建 App.java
        ↓
模型: 更新 todo → [>] Hello.java，然后调 write_file 创建
        ↓
模型: 更新 todo → [x] Hello.java, [>] World.java，继续...
        ↓
（如果 3 轮没更新 todo，系统插入提醒）
        ↓
最终全部 [x]，模型回复完成
```

## 你学到了什么

1. **Agent 需要显式的状态管理**。模型只靠对话历史记住进度是不可靠的，需要一个外部化的"便签"。
2. **Nag 机制是一种"柔性约束"**。不强制模型必须做某事，但通过注入提醒消息来引导行为。
3. **工具的数据格式很灵活**。Todo 使用整体替换而非增量修改，这简化了实现但要求模型每次都传完整列表。

## 下一步

Todo 解决了"单个 Agent 做长任务时保持连贯性"的问题。但有些任务需要先做调研（比如"看看项目里有没有类似的实现"），把调研过程混在主任务里会污染上下文。S04 引入子代理来解决这个问题。
