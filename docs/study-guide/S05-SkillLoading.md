# S05 — 技能加载

## 这个阶段要学什么

到目前为止，Agent 的所有知识都来自两个地方：
1. 模型自身的训练数据（通用知识）
2. 对话过程中读到的代码和命令输出（临时知识）

但有些知识是"项目特有"的，比如：
- "这个项目的代码审查标准是什么"
- "怎么用 Claude API 构建应用"
- "PDF 文件应该怎么处理"

这些知识如果每次都让模型自己去读代码、看文档，既浪费时间又浪费上下文空间。

S05 的解决方案是**技能（Skill）**：把这些领域知识预先写好，放在 `skills/` 目录里，模型需要时随时加载。

## 运行方式

```bash
mvn exec:java -Dexec.mainClass=com.learnclaudecode.agents.S05SkillLoading
```

## 你可以试试这些输入

- "有哪些可用的技能？"
- "加载 code-review 技能"
- "帮我做代码审查"（模型可能会自动加载相关技能）

## 技能文件长什么样

看一下 `skills/` 目录的结构：

```
skills/
├── agent-builder/
│   └── SKILL.md
├── code-review/
│   └── SKILL.md
├── mcp-builder/
│   └── SKILL.md
└── pdf/
    └── SKILL.md
```

每个 `SKILL.md` 的格式是：

```markdown
---
name: code-review
description: 代码审查标准和流程
---

## 代码审查要点

1. 代码风格是否一致
2. 有无潜在 bug
3. ...
```

顶部的 `---` 之间是元数据（名字和描述），下面是正文内容。

## 要读的源码

### 1. `StageConfig.s05()` — 新增 load_skill 工具

```java
public static StageConfig s05() {
    List<Map<String, Object>> tools = new ArrayList<>(baseTools());
    tools.add(tool("load_skill", "Load specialized knowledge by name.", ...));
    return new StageConfig("s05", false, false, false, false, false, false,
            tools,
            "... Use load_skill to access specialized knowledge before unfamiliar work.\n\n"
            + "Skills available:\n${SKILLS}");
}
```

注意 system prompt 里有 `${SKILLS}`，这个占位符会被替换成所有已加载技能的列表，让模型一开始就知道有哪些技能可用。

### 2. `SkillLoader` — 技能加载器

**构造函数**：启动时扫描整个 `skills/` 目录

```java
public SkillLoader(WorkspacePaths paths) {
    Path skillsDir = paths.skillsDir();
    // 递归找所有 SKILL.md 文件
    Files.walk(skillsDir)
         .filter(path -> path.getFileName().toString().equals("SKILL.md"))
         .forEach(this::loadSkillFile);
}
```

**`loadSkillFile`**：解析单个技能文件

```java
private void loadSkillFile(Path path) {
    String text = Files.readString(path);
    // 解析 --- 之间的 frontmatter 元数据
    // 如果没有 name 字段，就用目录名作为技能名
    String name = meta.getOrDefault("name", path.getParent().getFileName().toString());
    skills.put(name, Map.of("meta", meta, "body", body, "path", path.toString()));
}
```

**`getDescriptions`**：返回技能列表（替换 `${SKILLS}` 用）

```java
public String getDescriptions() {
    //   - code-review: 代码审查标准和流程
    //   - pdf: PDF 文件处理指南
}
```

**`getContent`**：返回某个技能的完整内容

```java
public String getContent(String name) {
    return "<skill name=\"" + name + "\">\n" + skill.get("body") + "\n</skill>";
}
```

### 3. `AgentRuntime.agentLoop()` 中的处理

```java
case "load_skill" -> skillLoader.getContent(String.valueOf(input.get("name")));
```

当模型调用 `load_skill("code-review")` 时，完整的技能文档会作为工具结果返回，模型在后续对话中就可以参考这些内容来工作。

## 技能 vs 直接读文件

你可能会问：模型用 `read_file` 读一下文档不也行吗？

区别在于：

| | 技能加载 | 直接读文件 |
|---|---|---|
| 模型知道有什么 | system prompt 里列出了所有可用技能 | 需要先知道文件路径 |
| 加载方式 | 一个工具调用 | 可能需要多次 read_file |
| 内容格式 | 预先组织好，针对 Agent 优化 | 原始文件，可能很杂 |
| 可维护性 | 技能和代码分离，方便更新 | 混在项目文件里 |

技能的本质是：**把给 Agent 的"说明书"单独管理，让 Agent 在需要时按需加载**。

## 核心概念图

```
启动时：
  SkillLoader 扫描 skills/ 目录
  → 发现 4 个技能
  → 把列表注入 system prompt: "Skills available: code-review, pdf, ..."

运行时：
  用户: "帮我审查这段代码"
  模型: 我需要 code-review 技能 → load_skill("code-review")
  系统: 返回完整的代码审查标准文档
  模型: 参照文档来审查代码
```

## 你学到了什么

1. **技能是 Agent 的"可装载知识包"**。和代码分离，按需加载，不占用初始上下文空间。
2. **System prompt 中列出可用技能**。让模型知道"我有哪些知识可以调用"。
3. **SKILL.md 的 frontmatter 约定**。用简单的 `---` 格式实现元数据，不依赖复杂解析器。
4. **添加新技能非常简单**。在 `skills/` 下新建目录，放一个 `SKILL.md`，重启即可。

## 下一步

S01 到 S05 解决了 Agent 的基础能力问题：执行命令、操作文件、规划任务、分治调研、加载知识。但还有一个物理限制没有解决——模型的上下文窗口是有限的。对话太长怎么办？S06 引入上下文压缩。
