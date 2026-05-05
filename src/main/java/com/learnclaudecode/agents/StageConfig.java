package com.learnclaudecode.agents;

import com.learnclaudecode.skills.SkillLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段配置，控制不同教学脚本暴露的能力。
 *
 * 这个类是整个教学项目最关键的“课程编排器”之一。
 * 它表达了 Claude Code 风格 Agent 的一个核心思想：
 * Agent 的能力并不是写死在代码里的，而是由运行时配置决定的。
 *
 * 在这个项目中，一个 StageConfig 主要回答 3 个问题：
 * 1. 当前阶段给模型什么 system prompt；
 * 2. 当前阶段允许模型使用哪些工具；
 * 3. 当前阶段是否打开 Todo、压缩、后台、团队、自主认领等高级机制。
 *
 * 因此，从 s01 到 s12 的演进，本质上不是“换了一套 Agent”，
 * 而是“在同一个 AgentRuntime 上逐步打开更多能力开关”。
 */
public record StageConfig(
        String prompt,
        boolean enableTodoNag,
        boolean enableCompression,
        boolean enableBackground,
        boolean enableInbox,
        boolean subagentWritable,
        boolean autonomousTeammates,
        List<Map<String, Object>> tools,
        String systemTemplate
) {
    /**
     * 根据当前工作区与可用技能生成本阶段实际生效的 system prompt。
     *
     * @param skillLoader 技能加载器
     * @param workdir 当前工作区路径
     * @return 展开占位符后的 system prompt
     */
    public String systemPrompt(SkillLoader skillLoader, Path workdir) {
        // systemTemplate 中保留占位符，运行时再按当前工作区和可用技能动态展开。
        return systemTemplate
                .replace("${WORKDIR}", workdir.toString())
                .replace("${SKILLS}", skillLoader.getDescriptions());
    }

    /**
     * 返回最基础的文件与命令工具集合。
     *
     * @return 基础工具定义列表
     */
    public static List<Map<String, Object>> baseTools() {
        // baseTools 对应最基础的文件/命令操作能力。
        // 这是最小可工作的 coding agent 工具集：
        // - bash：执行命令
        // - read_file：查看代码
        // - write_file：新建或覆盖文件
        // - edit_file：对已有文件做精确替换
        // 从 Agent 视角看，这些工具就是它的“手脚”。
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("bash", "Run a shell command.", Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")), "required", List.of("command"))));
        tools.add(tool("read_file", "Read file contents.", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")), "required", List.of("path"))));
        tools.add(tool("write_file", "Write content to file.", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), "required", List.of("path", "content"))));
        tools.add(tool("edit_file", "Replace exact text in file.", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), "required", List.of("path", "old_text", "new_text"))));
        return tools;
    }

    /**
     * 构建 s01 最小闭环阶段配置。
     *
     * @return s01 阶段配置
     */
    public static StageConfig s01() {
        // s01 只开放 bash，目的是让读者先理解最原始的“模型思考 + 命令执行”闭环。
        return new StageConfig("s01", false, false, false, false, false, false,
                List.of(baseTools().get(0)),
                "You are a coding agent at ${WORKDIR}. Use bash to solve tasks. Act, don't explain.");
    }

    /**
     * 构建 s02 文件工具阶段配置。
     *
     * @return s02 阶段配置
     */
    public static StageConfig s02() {
        // s02 在 s01 基础上加入文件工具，Agent 开始能直接读写项目内容。
        return new StageConfig("s02", false, false, false, false, false, false,
                baseTools(),
                "You are a coding agent at ${WORKDIR}. Use tools to solve tasks. Act, don't explain.");
    }

    /**
     * 构建 s03 Todo 规划阶段配置。
     *
     * @return s03 阶段配置
     */
    public static StageConfig s03() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        // s03 引入 todo，帮助模型把“长任务”拆成多个可跟踪步骤。
        tools.add(tool("todo", "Update task list. Track progress on multi-step tasks.", Map.of("type", "object", "properties", Map.of("items", Map.of("type", "array", "items", Map.of("type", "object"))), "required", List.of("items"))));
        return new StageConfig("s03", true, false, false, false, false, false,
                tools,
                "You are a coding agent at ${WORKDIR}. Use the todo tool to plan multi-step tasks. Mark in_progress before starting, completed when done. Prefer tools over prose.");
    }

    /**
     * 构建 s04 子代理阶段配置。
     *
     * @return s04 阶段配置
     */
    public static StageConfig s04() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        // s04 引入 subagent，体现 Claude Code 的重要思想：复杂问题可以分治。
        tools.add(tool("task", "Spawn a subagent with fresh context.", Map.of("type", "object", "properties", Map.of("prompt", Map.of("type", "string"), "description", Map.of("type", "string")), "required", List.of("prompt"))));
        return new StageConfig("s04", false, false, false, false, false, false,
                tools,
                "You are a coding agent at ${WORKDIR}. Use the task tool to delegate exploration or subtasks.");
    }

    /**
     * 构建 s05 技能加载阶段配置。
     *
     * @return s05 阶段配置
     */
    public static StageConfig s05() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        tools.add(tool("load_skill", "Load specialized knowledge by name.", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")), "required", List.of("name"))));
        return new StageConfig("s05", false, false, false, false, false, false,
                tools,
                "You are a coding agent at ${WORKDIR}. Use load_skill to access specialized knowledge before unfamiliar work.\n\nSkills available:\n${SKILLS}");
    }

    /**
     * 构建 s06 上下文压缩阶段配置。
     *
     * @return s06 阶段配置
     */
    public static StageConfig s06() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        // s06 解决上下文窗口问题：对话太长时，Agent 需要学会压缩历史而不是无限堆积。
        tools.add(tool("compact", "Trigger manual conversation compression.", Map.of("type", "object", "properties", Map.of("focus", Map.of("type", "string")))));
        return new StageConfig("s06", false, true, false, false, false, false,
                tools,
                "You are a coding agent at ${WORKDIR}. Use tools to solve tasks.");
    }

    /**
     * 构建 s07 任务系统阶段配置。
     *
     * @return s07 阶段配置
     */
    public static StageConfig s07() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        tools.add(tool("task_create", "Create a new task.", Map.of("type", "object", "properties", Map.of("subject", Map.of("type", "string"), "description", Map.of("type", "string")), "required", List.of("subject"))));
        tools.add(tool("task_update", "Update task status or dependencies.", Map.of("type", "object", "properties", Map.of("task_id", Map.of("type", "integer"), "status", Map.of("type", "string"), "addBlockedBy", Map.of("type", "array"), "addBlocks", Map.of("type", "array")), "required", List.of("task_id"))));
        tools.add(tool("task_list", "List all tasks.", Map.of("type", "object", "properties", Map.of())));
        tools.add(tool("task_get", "Get task details.", Map.of("type", "object", "properties", Map.of("task_id", Map.of("type", "integer")), "required", List.of("task_id"))));
        return new StageConfig("s07", false, false, false, false, false, false,
                tools,
                "You are a coding agent at ${WORKDIR}. Use task tools to plan and track work.");
    }

    /**
     * 构建 s08 后台任务阶段配置。
     *
     * @return s08 阶段配置
     */
    public static StageConfig s08() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        tools.add(tool("background_run", "Run command in background thread.", Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string"), "timeout", Map.of("type", "integer")), "required", List.of("command"))));
        tools.add(tool("check_background", "Check background task status. Only use when the user explicitly asks about a background task.", Map.of("type", "object", "properties", Map.of("task_id", Map.of("type", "string")))));
        return new StageConfig("s08", false, false, true, false, false, false,
                tools,
                "You are a coding agent at ${WORKDIR}. Use background_run for long-running commands. "
                + "After calling background_run, IMMEDIATELY reply with text (e.g. 'Task started, feel free to ask me anything else'). "
                + "Do NOT poll with check_background -- results will be injected automatically.");
    }

    /**
     * 构建 s09 多 Agent 协作阶段配置。
     *
     * @return s09 阶段配置
     */
    public static StageConfig s09() {
        List<Map<String, Object>> tools = new ArrayList<>(baseTools());
        // s09 开始进入“多 Agent 协作”，lead 不再独自完成全部工作，而是能创建队友。
        tools.add(tool("spawn_teammate", "Spawn a persistent teammate.", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"), "role", Map.of("type", "string"), "prompt", Map.of("type", "string")), "required", List.of("name", "role", "prompt"))));
        tools.add(tool("list_teammates", "List all teammates.", Map.of("type", "object", "properties", Map.of())));
        tools.add(tool("send_message", "Send a message to a teammate.", Map.of("type", "object", "properties", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string")), "required", List.of("to", "content"))));
        tools.add(tool("read_inbox", "Read and drain the lead inbox.", Map.of("type", "object", "properties", Map.of())));
        tools.add(tool("broadcast", "Send message to all teammates.", Map.of("type", "object", "properties", Map.of("content", Map.of("type", "string")), "required", List.of("content"))));
        return new StageConfig("s09", false, false, false, true, false, false,
                tools,
                "You are a team lead at ${WORKDIR}. Spawn teammates and communicate via inboxes.");
    }

    /**
     * 构建 s10 团队协议阶段配置。
     *
     * @return s10 阶段配置
     */
    public static StageConfig s10() {
        List<Map<String, Object>> tools = new ArrayList<>(s09().tools());
        tools.add(tool("shutdown_request", "Request teammate shutdown.", Map.of("type", "object", "properties", Map.of("teammate", Map.of("type", "string")), "required", List.of("teammate"))));
        tools.add(tool("plan_approval", "Approve or reject a teammate plan.", Map.of("type", "object", "properties", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "feedback", Map.of("type", "string")), "required", List.of("request_id", "approve"))));
        return new StageConfig("s10", false, false, false, true, false, false,
                tools,
                "You are a team lead at ${WORKDIR}. Manage teammates with shutdown and plan approval protocols.");
    }

    /**
     * 构建 s11 自治队友阶段配置。
     *
     * @return s11 阶段配置
     */
    public static StageConfig s11() {
        List<Map<String, Object>> tools = new ArrayList<>(s10().tools());
        // s11 进一步让队友具备自治能力：它们可以在空闲时主动认领任务，而不是一直等 lead 分配。
        tools.add(tool("claim_task", "Claim a task from the board.", Map.of("type", "object", "properties", Map.of("task_id", Map.of("type", "integer")), "required", List.of("task_id"))));
        tools.add(tool("idle", "Enter idle state.", Map.of("type", "object", "properties", Map.of())));
        tools.addAll(List.of(s07().tools().get(4), s07().tools().get(5), s07().tools().get(6), s07().tools().get(7)));
        return new StageConfig("s11", false, false, false, true, false, true,
                dedupe(tools),
                "You are a team lead at ${WORKDIR}. Teammates are autonomous -- they find work themselves.");
    }

    /**
     * 构建 s12 worktree 隔离阶段配置。
     *
     * @return s12 阶段配置
     */
    public static StageConfig s12() {
        List<Map<String, Object>> tools = new ArrayList<>(s11().tools());
        // s12 引入 worktree lane 概念，用于把不同任务分到不同目录隔离执行，减少上下文和文件冲突。
        tools.add(tool("worktree_create", "Create a task-bound worktree lane.", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"), "task_id", Map.of("type", "integer")), "required", List.of("name", "task_id"))));
        tools.add(tool("worktree_list", "List all worktrees.", Map.of("type", "object", "properties", Map.of())));
        tools.add(tool("worktree_remove", "Remove a worktree.", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"), "keep", Map.of("type", "boolean")), "required", List.of("name"))));
        tools.add(tool("worktree_events", "List recent worktree lifecycle events.", Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer")))));
        return new StageConfig("s12", false, false, false, true, false, true,
                dedupe(tools),
                "You are a coding agent at ${WORKDIR}. Use task + worktree tools for multi-task work. Use worktree_events when you need lifecycle visibility.");
    }

    /**
     * 构建完整能力版本的阶段配置。
     *
     * @return s_full 阶段配置
     */
    public static StageConfig sFull() {
        // s_full 不是重新定义一套工具，而是把前面各阶段的能力合并成一个完整视图。
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.addAll(baseTools());
        tools.addAll(s03().tools().subList(4, 5));
        tools.addAll(s04().tools().subList(4, 5));
        tools.addAll(s05().tools().subList(4, 5));
        tools.addAll(s06().tools().subList(4, 5));
        tools.addAll(s08().tools().subList(4, 6));
        tools.addAll(s07().tools().subList(4, 8));
        tools.addAll(s10().tools().subList(4, s10().tools().size()));
        tools.addAll(s11().tools().subList(s10().tools().size(), s11().tools().size()));
        tools.addAll(s12().tools().subList(s11().tools().size(), s12().tools().size()));
        return new StageConfig("s_full", true, true, true, true, true, true,
                dedupe(tools),
                "You are a coding agent at ${WORKDIR}. Use tools to solve tasks. Prefer task_create/task_update/task_list for multi-step work. Use TodoWrite for short checklists. Use task for subagent delegation. Use load_skill for specialized knowledge. Skills: ${SKILLS}");
    }

    /**
     * 按 messages API 约定构造单个工具定义。
     *
     * @param name 工具名
     * @param description 工具描述
     * @param schema 输入 schema
     * @return 工具定义映射
     */
    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        // 这里构造的结构直接对齐 messages API 所需的 tool 定义格式。
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("input_schema", schema);
        return tool;
    }

    /**
     * 对工具列表按名字去重。
     *
     * @param tools 原始工具列表
     * @return 去重后的工具列表
     */
    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> tools) {
        // 多阶段合并时会出现同名工具，这里按名字去重，保留最后一次定义。
        Map<String, Map<String, Object>> unique = new HashMap<>();
        for (Map<String, Object> tool : tools) {
            unique.put(String.valueOf(tool.get("name")), tool);
        }
        return new ArrayList<>(unique.values());
    }
}
