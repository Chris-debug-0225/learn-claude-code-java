# Learn Claude Code Java

一个用 Java 实现的 Claude Code 风格 Agent 学习项目。

你可以通过这个项目逐步理解：

- 大模型调用是怎么做的
- Agent 为什么不是普通聊天机器人
- Claude Code 风格的 coding agent 是怎么循环运行的
- 工具调用、子代理、上下文压缩、任务系统、多 Agent 协作是怎么一点点叠加出来的

如果你会 Java，并且想找一个能边读边跑的 Agent 示例项目，可以从这里开始。

项目设计参考了 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)。

仓库中同时包含按阶段拆分的后端代码，以及用于学习和展示的前端 `web/` 项目。

## 这个项目适合谁

- **你会 Java，但没做过大模型 API 调用**
- **你知道 LLM，但不清楚 Agent 到底比普通对话多了什么**
- **你想用一个可运行的项目理解 Claude Code 一类工具的核心设计**
- **你希望从简单到复杂，逐步理解 Agent 系统，而不是一上来就看一个大而全框架**

## 你能从这个项目学到什么

- **大模型调用基础**
  - 如何组织 `system`、`messages`、`tools`
  - 如何向 Anthropic-compatible 接口发送请求
  - 如何解析模型返回的文本与 `tool_use`

- **Agent 核心闭环**
  - 用户输入如何进入消息历史
  - 模型如何基于历史做下一步决策
  - 模型发起工具调用后，Java 本地如何执行工具
  - 工具结果如何重新回注给模型继续推理

- **Claude Code 风格设计**
  - 为什么要把能力拆成多个阶段 `S01 ~ S12`
  - 为什么运行时和能力配置要分离
  - 为什么 Todo、Skill、Compression、Task、Team、Worktree 都是“外挂式能力”

- **多 Agent 协作设计**
  - lead / teammate 的角色划分
  - 文件 inbox 的消息通信机制
  - 共享任务板如何支持多代理协作
  - 自治 teammate 如何自动找活干

## Claude Code 风格 Agent 到底是什么

如果用一句话概括：

> Claude Code 风格 Agent = **大模型负责决策** + **本地运行时负责执行工具** + **循环直到任务完成**

它和普通聊天机器人的差异在于：

- **普通聊天**
  - 模型只输出一段文本

- **Agent**
  - 模型可以输出文本，也可以请求调用工具
  - 本地程序真的去执行命令、读文件、写文件、更新任务、发消息
  - 执行结果再交回模型
  - 模型基于新状态继续下一步决策

所以，Agent 不是“更聪明的聊天”，而是“**能操作外部环境的大模型循环系统**”。

## 项目整体运行流程

这个项目最核心的运行链路如下：

1. 用户在终端输入一个请求
2. `AgentRuntime` 把它加入 `messages`
3. `StageConfig` 决定这一阶段允许哪些工具、哪些能力开关开启
4. `AnthropicClient` 把 `system + messages + tools` 发给模型
5. 模型返回：
   - 普通文本，或
   - `tool_use`
6. 如果是 `tool_use`，`AgentRuntime` 就在本地执行对应 Java 工具实现
7. 工具结果作为 `tool_result` 回写到消息历史
8. 再次调用模型
9. 一直循环，直到模型不再请求工具

这就是整个项目最重要的主线。

## 学习顺序建议

建议你按下面顺序看代码和运行示例：

- **第一步：先看 `S01AgentLoop`**
  - 只看最小闭环：用户输入 -> 调模型 -> 执行命令 -> 再调模型

- **第二步：看 `S02ToolUse`**
  - 理解为什么 Agent 要有 `read_file` / `write_file` / `edit_file`

- **第三步：看 `AgentRuntime`**
  - 理解项目真正的主循环

- **第四步：看 `StageConfig`**
  - 理解为什么同一个运行时可以演化出多个阶段

- **第五步：看 `AnthropicClient`**
  - 理解 LLM API 请求到底怎么发

- **第六步：按阶段继续学习高级能力**
  - `S03` Todo
  - `S04` Subagent
  - `S05` Skills
  - `S06` Compression
  - `S07` Task System
  - `S08` Background Tasks
  - `S09 ~ S11` Agent Teams
  - `S12` Worktree Task Isolation

- **第七步：最后看 `SFull`**
  - 观察所有能力组合后的完整运行形态

## 阶段目录说明

项目使用 `S01 ~ S12` 的渐进式结构，每个阶段只引入一个或少量新概念。

- **`S01AgentLoop`**
  - 最小 Agent 闭环
  - 重点理解：模型不是一次性完成任务，而是分步行动

- **`S02ToolUse`**
  - 引入文件工具
  - 重点理解：coding agent 为什么必须会读写代码

- **`S03TodoList`**
  - 引入 Todo 工具
  - 重点理解：长任务为什么需要显式计划和状态跟踪

- **`S04Subagent`**
  - 引入子代理
  - 重点理解：为什么复杂问题要分治，为什么需要“新上下文”

- **`S05Skills`**
  - 引入技能加载
  - 重点理解：模型之外的“可装载知识”怎么接入 Agent

- **`S06ContextCompression`**
  - 引入上下文压缩
  - 重点理解：上下文窗口不够时如何保留连续性

- **`S07TaskSystem`**
  - 引入文件任务板
  - 重点理解：任务规划如何变成可持久化状态

- **`S08BackgroundTasks`**
  - 引入后台任务
  - 重点理解：长时间命令如何异步执行，不阻塞主对话

- **`S09AgentTeams`**
  - 引入多个 Agent 协作
  - 重点理解：lead 和 teammate 如何通过 inbox 通信

- **`S10ShutdownAndPlanApproval`**
  - 引入协议化管理
  - 重点理解：多 Agent 系统为什么需要 shutdown / approval 协议

- **`S11AutonomousAgentTeams`**
  - 引入自治 teammate
  - 重点理解：Agent 如何在空闲时自动认领任务

- **`S12WorktreeTaskIsolation`**
  - 引入 worktree lane
  - 重点理解：多任务并行时如何做目录级隔离

- **`SFull`**
  - 完整版
  - 重点理解：所有能力叠加后的总效果

## 目录结构说明

下面是项目中最重要的目录和它们的职责。

```text
learn-claude-code-java/
├─ src/main/java/com/learnclaudecode/
│  ├─ agents/
│  ├─ common/
│  ├─ context/
│  ├─ model/
│  ├─ tools/
│  ├─ tasks/
│  ├─ team/
│  ├─ background/
│  └─ skills/
├─ web/
├─ skills/
├─ .env.example
├─ pom.xml
└─ README.md
```

### `src/main/java/com/learnclaudecode/agents`

这是最核心的目录，负责“Agent 如何跑”。

- **`S01...S12` / `SFull`**
  - 各阶段入口类
  - 每个类都很薄，只负责选一个 `StageConfig`

- **`Launcher`**
  - 统一入口启动器
  - 负责把阶段配置交给统一运行时

- **`AppContext`**
  - 应用装配器
  - 负责创建所有共享服务，例如模型客户端、工具层、任务系统、团队系统等

- **`StageConfig`**
  - 阶段能力配置中心
  - 定义某一阶段允许哪些工具、是否启用高级机制、system prompt 长什么样

- **`AgentRuntime`**
  - 整个项目最关键的类
  - 实现 Agent 主循环、工具调用分发、subagent、后台结果回注、消息收件箱轮询等逻辑

### `src/main/java/com/learnclaudecode/common`

这是基础设施目录，负责“通用支撑”。

- **`AnthropicClient`**
  - 大模型 API 调用器
  - 把 `messages`、`system`、`tools` 发给兼容 Anthropic 的服务端

- **`EnvConfig`**
  - 环境配置读取
  - 从 `.env` 和系统环境变量中读取 API Key、模型名、Base URL、工作目录

- **`WorkspacePaths`**
  - 工作区路径管理
  - 负责生成 `.tasks`、`.team`、`.worktrees`、`transcripts` 等目录路径
  - 同时负责安全路径解析，防止模型通过 `../` 逃逸

- **`JsonUtils`**
  - JSON 序列化 / 反序列化工具

### `src/main/java/com/learnclaudecode/tools`

这是 Agent 的“手脚”，负责对文件系统和命令进行操作。

- **`CommandTools`**
  - 执行命令
  - 读取文件
  - 写入文件
  - 精确替换文件中的文本

- **`TodoManager`**
  - 维护 Todo 列表
  - 用于帮助模型把复杂任务拆成多步执行

### `src/main/java/com/learnclaudecode/context`

- **`CompressionService`**
  - 负责上下文压缩
  - 解决长对话导致的上下文窗口膨胀问题

### `src/main/java/com/learnclaudecode/tasks`

这是任务编排能力所在目录。

- **`TaskManager`**
  - 文件任务板
  - 负责任务创建、更新、认领、依赖清理、worktree 绑定

- **`WorktreeManager`**
  - 任务隔离工作区管理器
  - 负责创建和删除 worktree lane，记录生命周期事件

### `src/main/java/com/learnclaudecode/background`

- **`BackgroundManager`**
  - 负责异步执行耗时命令
  - 结果不会丢失，而是稍后回注到主 Agent 消息历史中

### `src/main/java/com/learnclaudecode/team`

这是多 Agent 协作能力所在目录。

- **`MessageBus`**
  - 文件型消息总线
  - 通过 inbox JSONL 文件实现代理之间的低依赖通信

- **`TeammateManager`**
  - 队友生命周期管理器
  - 负责 spawn teammate、收发消息、shutdown 协议、plan approval、自主认领任务

### `src/main/java/com/learnclaudecode/model`

这是数据结构目录，主要放消息对象、响应对象、任务记录等模型类。

### `src/main/java/com/learnclaudecode/skills`

- **`SkillLoader`**
  - 负责扫描 `skills/` 目录中的 `SKILL.md`
  - 把外部知识作为可加载能力提供给 Agent

### `skills/`

这里存放技能文档。每个技能通常是一个目录，入口文件为 `SKILL.md`。

你可以把它理解成：

- **模型的长期补充知识包**
- **和代码分离的外挂知识源**
- **让 Agent 在陌生领域先加载说明再行动的一种机制**

## 运行前准备

### 1. 配置 `.env`

复制 `.env.example` 为 `.env`，并至少配置下面几个变量：

- `ANTHROPIC_API_KEY`
- `MODEL_ID`
- `ANTHROPIC_BASE_URL`（如果你使用兼容 Anthropic 协议的第三方服务，通常需要配置）

常见示例：

```env
ANTHROPIC_API_KEY=your_api_key
MODEL_ID=your_model_name
ANTHROPIC_BASE_URL=https://your-provider.example.com/api/anthropic
```

### 2. JDK 版本

- **Java 17**

### 3. Maven

确保本机可以使用 Maven 构建项目。

## 构建项目

```bash
mvn compile
```

## 运行项目

### 运行最小闭环示例

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S01AgentLoop
```

### 运行完整能力版本

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.SFull
```

### 运行其他阶段

把主类替换成对应阶段即可，例如：

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S04Subagent
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S07TaskSystem
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S11AutonomousAgentTeams
```

### 运行前端项目

Java 版本仓库中也提供了独立的前端目录 `web/`，其内容与 `learn-claude-code-main/web` 对齐。

启动方式：

```bash
cd web
npm install
npm run dev
```

启动后默认访问：`http://localhost:3000`

## 建议你如何读源码

如果你是 Java 开发者，建议按下面顺序读：

1. `README.md`
2. `S01AgentLoop`
3. `Launcher`
4. `AppContext`
5. `StageConfig`
6. `AgentRuntime`
7. `AnthropicClient`
8. `CommandTools`
9. `CompressionService`
10. `TaskManager`
11. `TeammateManager`
12. `WorktreeManager`
13. `SFull`

这样读的好处是：

- 先理解入口
- 再理解装配
- 再理解能力配置
- 最后理解高级机制

## 这个项目里的几个核心设计思想

### 1. 运行时和能力配置分离

`AgentRuntime` 只负责“怎么跑”，`StageConfig` 负责“能做什么”。

这是一种非常重要的 Agent 设计方式，因为这样可以：

- 保持运行时稳定
- 方便逐步开放能力
- 方便做教学阶段拆分

### 2. 工具不是附属品，而是 Agent 的执行器官

在这个项目里，大模型只负责决策。

真正的：

- 执行命令
- 读写文件
- 更新 Todo
- 管理任务
- 收发消息

这些事情，都是本地 Java 代码真正做的。

### 3. 消息历史就是 Agent 的工作记忆

Agent 每一轮都依赖已有 `messages` 做决策。

因此：

- 用户输入会进入消息历史
- 工具结果会进入消息历史
- 队友消息会进入消息历史
- 后台任务结果也会进入消息历史

理解这一点，对理解 Agent 为什么“连续”非常重要。

### 4. 长任务一定要有状态管理

Todo、Task、Worktree、Team 这些能力，看上去分散，实际上都在解决同一个问题：

> 当任务变长、变复杂、变多人协作时，Agent 不能只靠一轮对话记住所有事情。

所以必须把状态外置出来。

### 5. 多 Agent 协作不是魔法，本质是协议 + 状态 + 通信

这个项目没有引入复杂的消息队列系统，而是用文件 inbox、JSON 任务板、状态字段和简单协议，就实现了多 Agent 协作。

 这能帮助你看清楚本质，而不是被框架细节淹没。

## 当前实现

### 实现方式
  - 部分结构采用 DTO + `Map` 混合表示，以兼顾实现速度与可维护性

### 兼容性考虑
  - 当前实现面向 Java 17
  - 模型接口按 Anthropic-compatible 形式组织

### 技能支持
  - 已集成 `skills/` 目录，可通过 `SkillLoader` 动态加载技能说明

