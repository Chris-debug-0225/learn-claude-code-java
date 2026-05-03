# S03 — Todo 任务规划

## 这个阶段要学什么

前两个阶段的 Agent 已经能执行命令、读写文件了。但如果你给它一个复杂任务，比如"给项目里所有的类加上 Javadoc 注释"，它可能做了几个文件就忘了哪些做过、哪些没做。

S03 引入 `todo` 工具，让模型可以**给自己列一个任务清单**，边做边更新状态：

```
[✅] #1: 创建 test1.java - 冒泡排序算法			← 已完成
[⚙️] #2: 创建 test2.java - 二分查找算法			 ← 正在做这个	
[⏳] #3: 创建 test3.java - 斐波那契数列算法	   ← 待处理

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

## 演示：模型返回与容错处理

### 模型返回的理想情况

当模型严格遵守 schema 时，返回的 `tool_use` 应该是这样的：

```json
{
  "type": "tool_use",
  "name": "todo",
  "input": {
    "items": [
      {"text": "创建 test1.java 并实现冒泡排序算法", "status": "pending"},
      {"text": "创建 test2.java 并实现二分查找算法", "status": "pending"},
      {"text": "创建 test3.java 并实现斐波那契递归算法", "status": "pending"}
    ]
  }
}
```

### 模型返回的实际情况（弱模型常见问题）

prompt-based tool use 的模型（如 mimo-v2.5-pro）不会强制遵守 JSON Schema，经常返回格式不符的 input：

```json
{
  "type": "tool_use",
  "name": "todo",
  "input": {
    "todos": "[{\"title\": \"创建 test1.java\", \"status\": \"pending\"}, {\"title\": \"创建 test2.java\", \"status\": \"pending\"}]"
  }
}
```

三个典型问题：

| 问题 | 理想值 | 实际返回 |
|------|--------|----------|
| 顶层字段名 | `items` | `todos` / `tasks` / `list` |
| items 的类型 | JSON 数组 | JSON 字符串 |
| 子字段名 | `text` | `title` / `name` / `task` |

### 为什么会出现这个问题

原生 function calling（如 OpenAI、Anthropic API）会在 API 层强制约束输出格式。但本项目是通过 **prompt 模拟 tool use**——模型只是在 system prompt 中"看到"了工具描述，输出时并没有被强制约束。它"大致理解"要做 todo，但没有精确匹配 schema。

## 要读的源码

### 1. `StageConfig.s03()` — 工具描述与 Schema 定义

```java
public static StageConfig s03() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("todo",
            "Update task list. Track progress on multi-step tasks. " +
            "Input MUST be a JSON object with key \"items\" (an array, NOT a string). " +
            "Each item needs \"text\" (string) and \"status\" (pending/in_progress/completed). " +
            "Example: {\"items\": [{\"text\": \"Create Hello.java\", \"status\": \"pending\"}]}",
            Map.of("type", "object",
                    "properties", Map.of("items",
                            Map.of("type", "array",
                                    "items", Map.of("type", "object",
                                            "properties", Map.of(
                                                    "text", Map.of("type", "string"),
                                                    "status", Map.of("type", "string",
                                                            "enum", List.of("pending", "in_progress", "completed"))
                                            ),
                                            "required", List.of("text", "status")
                                    )
                            )
                    ),
                    "required", List.of("items")
            )));
    return new StageConfig("s03", true, false, false, false, false, false,
            tools, "...");
}
```

三个关键变化：
- 工具列表加了 `todo`
- `enableTodoNag` 设成了 `true`（第一个布尔参数）
- description 中包含完整的 JSON 示例，input_schema 中给 items 的子对象定义了 `text`/`status` 属性和 `enum` 约束——尽可能引导模型输出正确格式（目前代码中并没有这么做，因为我发现加了后模型返回还是不符合规则）

### 2. `AgentRuntime.normalizeTodoInput()` — 输入容错层

这是解决"模型输出格式不规范"的核心手段。在 todo 分支 dispatch 之前，对模型返回的 input 做三层修复：

```java
case "todo", "TodoWrite" -> {
    usedTodo = true;
    yield todoManager.update(normalizeTodoInput(input));
}
```

`normalizeTodoInput` 的三层修复逻辑：

```java
private List<Map<String, Object>> normalizeTodoInput(Map<String, Object> input) {
    // 第一层：字段名修复
    // input 中没有 "items" 时，尝试 "todos" / "tasks" / "list"
    Object raw = input.get("items");
    if (raw == null) {
        for (String altKey : List.of("todos", "tasks", "list")) {
            if (input.containsKey(altKey)) { raw = input.get(altKey); break; }
        }
    }

    // 第二层：类型修复
    // 如果模型把数组序列化成了 JSON 字符串，用 Jackson 解析回 List
    if (raw instanceof String str) {
        items = JsonUtils.fromJson(str, new TypeReference<>() {});
    } else if (raw instanceof List<?> list) {
        // 直接转为 List<Map<String, Object>>
    }

    // 第三层：子字段名修复
    for (Map<String, Object> item : items) {
        // "title" / "name" / "task" → "text"
        // "done" / "finished" → "completed"
        // "doing" / "active" → "in_progress"
    }
}
```

### 3. `TodoManager.update()` — 双重保险

在 TodoManager 内部也做了字段别名兜底，即使 normalizeTodoInput 漏掉了某些情况，这里也能处理：

```java
// text 字段别名兜底：text > content > title > name > task > description
String text = "";
for (String key : List.of("text", "content", "title", "name", "task", "description")) {
    Object val = item.get(key);
    if (val != null && !String.valueOf(val).isBlank()) {
        text = String.valueOf(val).trim();
        break;
    }
}

// status 别名映射
String status = String.valueOf(item.getOrDefault("status", "pending")).toLowerCase();
if (status.equals("done") || status.equals("finished")) status = "completed";
if (status.equals("doing") || status.equals("active")) status = "in_progress";
```

### 4. "Todo Nag" 机制 — 提醒模型更新 todo

在 `AgentRuntime.agentLoop()` 中：

```java
roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
if (config.enableTodoNag() && roundsWithoutTodo >= 3) {
    results.add(0, Map.of("type", "text", "text", "<reminder>Update your todos.</reminder>"));
}
```

如果模型连续 3 轮都没更新 todo，系统会自动在工具结果前面插一条提醒："你应该更新一下 todo 了"。

这是一个很巧妙的设计：**不是强制模型必须用 todo，而是温和地提醒它**。模型看到这个提醒后，通常就会把进度更新到 todo 里。

## 容错策略总结

```
方案 1：丰富工具描述（提高正确率）
  description 中写明字段名、类型、附带 JSON 示例
  input_schema 中细化 properties 和 enum 约束
        ↓
方案 2：后处理容错（兜底修复）
  normalizeTodoInput() 在 AgentRuntime 中做三层修复
  TodoManager.update() 内部做字段别名兜底
        ↓
方案 3：Todo Nag（引导行为）
  连续 3 轮没更新 todo 时注入提醒消息
```

方案 1 提高模型输出正确率，方案 2 兜底处理残余错误，方案 3 引导模型养成使用习惯。三者组合使用。

## 为什么 Todo 对 Agent 重要

想象你在写代码，同时要做 5 件事：
1. 你会在便利贴上列一个清单
2. 做完一件划掉一件
3. 如果被打断了，看一眼清单就知道做到哪了

Agent 也一样。模型的"记忆"就是消息历史，对话越长越容易"忘事"。Todo 就是模型的便利贴——一个显式的、结构化的进度追踪器。

## 核心概念图

```
用户: "创建 3 个 Java 文件，每个写一个简单算法"
        ↓
模型: 先调 todo 工具列计划
    [⏳] 创建 test1.java — 冒泡排序
    [⏳] 创建 test2.java — 二分查找
    [⏳] 创建 test3.java — 斐波那契递归
        ↓
normalizeTodoInput() 容错修复（如果格式不规范）
        ↓
模型: 更新 todo → [⚙️] test1.java，然后调 write_file 创建
        ↓
模型: 更新 todo → [✅] test1.java, [⚙️] test2.java，继续...
        ↓
（如果 3 轮没更新 todo，系统插入提醒）
        ↓
最终全部 [✅]，模型回复完成
```

## 你学到了什么

1. **Agent 需要显式的状态管理**。模型只靠对话历史记住进度是不可靠的，需要一个外部化的"便签"。
2. **Nag 机制是一种"柔性约束"**。不强制模型必须做某事，但通过注入提醒消息来引导行为。
3. **工具的数据格式很灵活**。Todo 使用整体替换而非增量修改，这简化了实现但要求模型每次都传完整列表。
4. **弱模型需要容错设计**。prompt-based tool use 无法强制约束输出格式，必须在工具描述（提高正确率）和后处理层（兜底修复）两个维度同时下功夫。

## 下一步

Todo 解决了"单个 Agent 做长任务时保持连贯性"的问题。但有些任务需要先做调研（比如"看看项目里有没有类似的实现"），把调研过程混在主任务里会污染上下文。S04 引入子代理来解决这个问题。
