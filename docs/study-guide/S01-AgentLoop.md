# S01 — 最小 Agent 闭环

## 这个阶段要学什么

S01 是整个项目的起点。它只做一件事：**让大模型能执行 shell 命令**。

在你运行 S01 之后，你可以在终端输入一句话，比如"帮我看看当前目录下有哪些文件"，模型就会自己决定运行 `ls` 命令，拿到结果后再用自然语言告诉你答案。

这件事看上去简单，但它和普通聊天有本质区别：

- **普通聊天**：你问一句，模型回一句，就完了。
- **Agent**：你问一句，模型可能不直接回答，而是先执行一个命令，看到结果后再决定下一步——可能继续执行命令，也可能回答你。

这就是 Agent 的核心：**模型不只是说话，还能动手干活，而且能根据干活的结果继续思考**。

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S01AgentLoop
```

## 你可以试试这些输入

1. "你好，请你做一个自我介绍。"
2. "查看 pom.xml 的内容，并简单总结。"

## 要读的源码

### 1. `S01AgentLoop.java` — 入口

```java
public static void main(String[] args) {
    Launcher.launch(StageConfig.s01());
} 	
```

就这么一行。它做的事情是：选一个配置（`StageConfig.s01()`），交给 `Launcher` 启动。

### 2. `StageConfig.s01()` — 这个阶段开放了什么能力

```java
public static StageConfig s01() {
    return new StageConfig("s01", false, false, false, false, false, false,
            List.of(baseTools().get(0)),  // 只给 bash 一个工具
            "You are a coding agent at ${WORKDIR}. Use bash to solve tasks. Act, don't explain.");
}
```

注意两个关键点：
- **工具只有 `bash`**：模型能用的唯一工具就是执行命令
- **所有高级功能的开关全是 `false`**：没有 todo、没有压缩、没有后台任务、没有团队

### 3. `AgentRuntime.agentLoop()` — 最核心的循环

这是整个项目最重要的方法。简化后的逻辑是这样：

```
while (true) {
    1. 把当前消息历史 + 工具列表发给模型
    2. 模型返回结果
    3. 如果模型说的是普通文本 → 展示给用户，循环结束12
    4. 如果模型要调用工具 → 在本地执行工具，把结果塞回消息历史
    5. 回到第 1 步，再问一次模型
}
```

这个循环就是 Agent 的"心跳"。你可以把它想象成：

> 模型是大脑，工具是手脚，消息历史是记忆。每一轮循环就是"想一下→动一下→看到结果→再想一下"。

### 4. `AnthropicClient.createMessage()` — 怎么调用模型

这个类负责发 HTTP 请求给模型 API。请求体长这样：

```json
{
  "model": "claude-sonnet-4-6",
  "system": "You are a coding agent at /path/to/project...",
  "messages": [用户输入 + 之前的对话历史],
  "tools": [bash 工具的定义],
  "max_tokens": 8000
}
```

模型返回两种东西之一：
- `stop_reason: "end_turn"` → 模型直接回复了文本
- `stop_reason: "tool_use"` → 模型想执行 `bash` 命令

### 5. `CommandTools.runBash()` — 命令怎么被执行

当模型说"我要执行 `ls -la`"时，Java 代码真的会启动一个进程来运行这条命令：

```java
ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
builder.directory(paths.workdir().toFile());
Process process = builder.start();
```

- Windows 下用 `powershell -Command`
- Linux/Mac 下用 `bash -lc`
- 有最基本的危险命令黑名单（`rm -rf /`、`sudo` 等）
- 120 秒超时

## 核心概念图

```
用户输入 "列出当前目录的文件"
        ↓
   消息历史: [{role: "user", content: "列出当前目录的文件"}]
        ↓
   发给模型 API（带 bash 工具定义）
        ↓
   模型返回: tool_use → bash → "ls -la"
        ↓
   Java 真的执行了 ls -la，拿到输出
        ↓
   消息历史追加: [{role: "user", content: 工具结果}]
        ↓
   再次发给模型
        ↓
   模型返回: 普通文本 "当前目录有以下文件：..."
        ↓
   展示给用户，循环结束
```

## 你学到了什么

1. **Agent = 模型 + 工具 + 循环**。不是调一次 API 就完了，而是持续循环直到任务做完。
2. **模型决定做什么，Java 代码负责真正执行**。模型不会自己去跑命令，它只是告诉运行时"我想跑这条命令"。
3. **消息历史就是 Agent 的工作记忆**。每一轮工具执行的结果都会被塞回消息历史，模型下一轮就能看到"刚才发生了什么"。

## 下一步

S01 只有一个 `bash` 工具，模型想读文件只能 `cat`、想写文件只能 `echo >`。S02 会给模型专门的文件读写工具，让它更精准地操作代码。
