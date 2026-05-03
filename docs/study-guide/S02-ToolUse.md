# S02 — 文件工具

## 这个阶段要学什么

S01 只有 `bash` 一个工具。虽然 `bash` 理论上什么都能干（`cat` 看文件、`echo >` 写文件），但对于一个 **Coding Agent** 来说，这远远不够精细。

S02 给模型加了三个专门的文件操作工具：

| 工具 | 作用 | 等价于 |
|------|------|--------|
| `read_file` | 读取文件内容 | `cat` 但支持行数限制 |
| `write_file` | 创建或覆盖文件 | `echo > file` 但自动建目录 |
| `edit_file` | 精确替换文件中的一段文字 | `sed` 但更安全 |

为什么不直接用 `bash` 做这些事？

1. **精确性**：`edit_file` 做的是"找到这段文字，替换成那段文字"，不会误伤其他内容
2. **安全性**：所有路径都通过 `WorkspacePaths.safeResolve()` 检查，模型无法通过 `../` 逃逸到项目目录之外
3. **可控性**：`read_file` 可以限制只读前 N 行，防止把巨大文件全塞进上下文

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S02ToolUse
```

## 你可以试试这些输入

- "查看 pom.xml 的内容，并简单总结。"
- "在项目的的根目录下创建一个 测试.txt 的文件，内容为：Hello Agent"

## 要读的源码

### 1. `StageConfig.s02()` — 和 S01 有什么不同

```java
public static StageConfig s02() {
    return new StageConfig("s02", false, false, false, false, false, false,
            baseTools(),  // 这里给了全部 4 个基础工具
            "You are a coding agent at ${WORKDIR}. Use tools to solve tasks. Act, don't explain.");
}
```

对比 S01：S01 只给了 `baseTools().get(0)`（即 bash），S02 给了全部 `baseTools()`。

### 2. `StageConfig.baseTools()` — 四个基础工具的定义

```java
public static List<Map<String, Object>> baseTools() {
    List<Map<String, Object>> tools = new ArrayList<>();
    tools.add(tool("bash",       "Run a shell command.",      ...));
    tools.add(tool("read_file",  "Read file contents.",       ...));
    tools.add(tool("write_file", "Write content to file.",    ...));
    tools.add(tool("edit_file",  "Replace exact text in file.", ...));
    return tools;
}
```

每个工具的定义包含三部分：
- `name`：工具名（模型通过这个名字来请求调用）
- `description`：工具说明（帮助模型理解什么时候用它）
- `input_schema`：参数格式（告诉模型需要传哪些参数）

这些定义会被发送给模型 API，模型根据这些信息决定"当前这一步该用哪个工具，传什么参数"。

### 3. `CommandTools` — 工具的 Java 实现

**`runRead`**：读文件

```java
public String runRead(String relativePath, Integer limit) {
    List<String> allLines = Files.readAllLines(paths.safeResolve(relativePath));
    if (limit != null && limit > 0 && limit < allLines.size()) {
        lines = allLines.subList(0, limit);
        lines.add("... (" + (allLines.size() - limit) + " more lines)");
    }
    // 结果最长 50000 字符，避免撑爆上下文
}
```

**`runWrite`**：写文件

```java
public String runWrite(String relativePath, String content) {
    Path path = paths.safeResolve(relativePath);
    Files.createDirectories(path.getParent());  // 自动建中间目录
    Files.writeString(path, content);
}
```

**`runEdit`**：精确替换

```java
public String runEdit(String relativePath, String oldText, String newText) {
    String content = Files.readString(path);
    if (!content.contains(oldText)) {
        return "Error: Text not found";  // 找不到就报错，不会瞎改
    }
    Files.writeString(path, content.replaceFirst(Pattern.quote(oldText), ...));
}
```

### 4. `WorkspacePaths.safeResolve()` — 路径安全

```java
public Path safeResolve(String relativePath) {
    Path resolved = workdir.resolve(relativePath).normalize();
    if (!resolved.startsWith(workdir)) {
        throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
    }
    return resolved;
}
```

如果模型尝试读 `../../etc/passwd`，这里会直接拒绝。这是 Agent 安全的第一道防线。

### 5. `AgentRuntime.agentLoop()` 中的工具分发

```java
String output = switch (toolName) {
    case "bash"       -> commandTools.runBash(...);
    case "read_file"  -> commandTools.runRead(...);
    case "write_file" -> commandTools.runWrite(...);
    case "edit_file"  -> commandTools.runEdit(...);
    default           -> "Unknown tool: " + toolName;
};
```

模型返回 `tool_use` 时会带上工具名和参数，这个 `switch` 就是把模型的"意图"翻译成真正的 Java 方法调用。

## 核心概念图

```
模型看到的工具菜单：
┌──────────┬─────────────────────────────────────┐
│ bash     │ 执行任意 shell 命令                   │
│ read_file│ 读文件，可限制行数                     │
│ write_file│ 写文件，自动建目录                    │
│ edit_file│ 精确文本替换                          │
└──────────┴─────────────────────────────────────┘

模型根据当前任务，自己决定用哪个工具
```

## 你学到了什么

1. **工具定义发给模型，模型自己选用**。你不需要写 if-else 判断"用户想读文件就调 read_file"，模型会自己根据用户意图选择合适的工具。
2. **工具有精细的参数定义**。`input_schema` 告诉模型"你需要传一个 path 和一个 content"，模型就会自动构造正确的 JSON 参数。
3. **路径安全很重要**。Agent 会真的执行模型的指令，所以必须做安全检查，防止模型做出破坏性操作。

## 下一步

S01 + S02 已经让你拥有了一个能执行命令、读写文件的 Agent。但如果任务很复杂（比如"重构整个项目的包结构"），模型可能做着做着就忘了自己做到哪一步了。S03 引入 Todo 工具来解决这个问题。
