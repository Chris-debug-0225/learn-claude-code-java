package com.learnclaudecode.agents;

import com.learnclaudecode.background.BackgroundManager;
import com.learnclaudecode.common.AnthropicClient;
import com.learnclaudecode.common.JsonUtils;
import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.context.CompressionService;
import com.learnclaudecode.model.ChatMessage;
import com.learnclaudecode.skills.SkillLoader;
import com.learnclaudecode.tasks.TaskManager;
import com.learnclaudecode.tasks.WorktreeManager;
import com.learnclaudecode.team.MessageBus;
import com.learnclaudecode.team.TeammateManager;
import com.learnclaudecode.tools.CommandTools;
import com.learnclaudecode.tools.TodoManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 通用 agent 运行时，通过能力开关映射 s01-s12 与 s_full 的不同阶段。
 *
 * 如果只看一个类来理解“Claude Code 风格 Agent 是怎么跑起来的”，
 * 那么最值得读的就是这个类。
 *
 * 它负责实现最核心的 Agent 闭环：
 * 1. 接收用户输入，写入消息历史；
 * 2. 带着 system prompt、messages、tools 调用大模型；
 * 3. 如果模型返回普通文本，就把它展示给用户；
 * 4. 如果模型返回 tool_use，就在本地执行工具；
 * 5. 再把工具结果作为新的消息塞回历史，继续下一轮；
 * 6. 一直循环到模型停止调用工具为止。
 *
 * 这也是绝大多数 Agent 框架最本质的运行机制：
 * “LLM 负责决定下一步动作，本地运行时负责真正执行动作并反馈结果”。
 */
public class AgentRuntime {
    private final AnthropicClient client;
    private final WorkspacePaths paths;
    private final CommandTools commandTools;
    private final TodoManager todoManager;
    private final SkillLoader skillLoader;
    private final CompressionService compressionService;
    private final TaskManager taskManager;
    private final BackgroundManager backgroundManager;
    private final MessageBus messageBus;
    private final TeammateManager teammateManager;
    private final WorktreeManager worktreeManager;

    /**
     * 构造 AgentRuntime 实例。
     *
     * @param client AnthropicClient 实例
     * @param paths WorkspacePaths 实例
     * @param commandTools CommandTools 实例
     * @param todoManager TodoManager 实例
     * @param skillLoader SkillLoader 实例
     * @param compressionService CompressionService 实例
     * @param taskManager TaskManager 实例
     * @param backgroundManager BackgroundManager 实例
     * @param messageBus MessageBus 实例
     * @param teammateManager TeammateManager 实例
     * @param worktreeManager WorktreeManager 实例
     */
    public AgentRuntime(AnthropicClient client,
                        WorkspacePaths paths,
                        CommandTools commandTools,
                        TodoManager todoManager,
                        SkillLoader skillLoader,
                        CompressionService compressionService,
                        TaskManager taskManager,
                        BackgroundManager backgroundManager,
                        MessageBus messageBus,
                        TeammateManager teammateManager,
                        WorktreeManager worktreeManager) {
        this.client = client;
        this.paths = paths;
        this.commandTools = commandTools;
        this.todoManager = todoManager;
        this.skillLoader = skillLoader;
        this.compressionService = compressionService;
        this.taskManager = taskManager;
        this.backgroundManager = backgroundManager;
        this.messageBus = messageBus;
        this.teammateManager = teammateManager;
        this.worktreeManager = worktreeManager;
    }

    /**
     * 启动 REPL 交互循环。
     *
     * @param config 当前阶段配置
     */
    public void runRepl(StageConfig config) {
        List<ChatMessage> history = new ArrayList<>();
        Scanner scanner = new Scanner(System.in);
        while (true) {
            // 每个阶段都使用不同的 prompt 前缀。
            System.out.print("\u001B[36m" + config.prompt() + " >> \u001B[0m");
            if (!scanner.hasNextLine()) {
                break;
            }
            String query = scanner.nextLine();
            if (query == null || query.isBlank() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }
            // 用户输入首先会变成一条 user 消息，正式进入 Agent 的消息历史。
            history.add(new ChatMessage("user", query));
            agentLoop(history, config);
            Object content = history.get(history.size() - 1).content();
            if (content instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> block && block.containsKey("text")) {
                        System.out.println(block.get("text"));
                    }
                }
            }
            System.out.println();
        }
    }

    /**
     * 执行单轮任务的 Agent 主循环，直到模型停止调用工具。
     *
     * @param messages 当前消息历史
     * @param config 当前阶段配置
     */
    public void agentLoop(List<ChatMessage> messages, StageConfig config) {
        int roundsWithoutTodo = 0;
        while (true) {
            // 这里的 while(true) 就是 Agent 的主循环。
            // 每次循环都可以理解成一次“观察当前上下文 -> 调模型 -> 执行动作 -> 更新上下文”的决策周期。
            if (config.enableCompression()) {
                // 先做轻量裁剪，尽量不打断对话；只有达到阈值时才做真正压缩。
                compressionService.microCompact(messages);
                if (compressionService.needsAutoCompact(messages)) {
                    System.out.println("[auto-compact triggered]");
                    List<ChatMessage> compacted = compressionService.autoCompact(new ArrayList<>(messages));
                    messages.clear();
                    messages.addAll(compacted);
                }
            }
            if (config.enableBackground()) {
                // 后台任务结果不会直接丢失，而是作为新的 user 消息重新注入主上下文。
                List<Map<String, Object>> notifs = backgroundManager.drain();
                if (!notifs.isEmpty()) {
                    StringBuilder builder = new StringBuilder();
                    for (Map<String, Object> notif : notifs) {
                        builder.append("[bg:").append(notif.get("task_id")).append("] ")
                                .append(notif.get("status")).append(": ")
                                .append(notif.get("result")).append("\n");
                    }
                    messages.add(new ChatMessage("user", "<background-results>\n" + builder + "</background-results>"));
                    messages.add(new ChatMessage("assistant", "Noted background results."));
                }
            }
            if (config.enableInbox()) {
                // 团队阶段的 lead 会周期性轮询 inbox，把队友消息拼回对话历史中。
                List<Map<String, Object>> inbox = messageBus.readInbox("lead");
                if (!inbox.isEmpty()) {
                    messages.add(new ChatMessage("user", "<inbox>" + JsonUtils.toPrettyJson(inbox) + "</inbox>"));
                    messages.add(new ChatMessage("assistant", "Noted inbox messages."));
                }
            }
            // 统一通过 Anthropic-compatible messages API 获取下一步行动。
            // 注意：这里不是“让模型一次性做完整任务”，而是只问模型“当前这一步该做什么”。
            // 这正是 Agent 与普通聊天调用的差异所在。
            var response = client.createMessage(config.systemPrompt(skillLoader, paths.workdir()), messages, config.tools(), 8000);
            messages.add(new ChatMessage("assistant", response.content()));
            if (!"tool_use".equals(response.stop_reason())) {
                // 不是 tool_use 就说明模型已经给出最终回复，本轮循环结束。
                return;
            }
            List<Map<String, Object>> results = new ArrayList<>();
            boolean usedTodo = false;
            boolean manualCompact = false;
            for (Map<String, Object> block : response.content()) {
                if (!"tool_use".equals(String.valueOf(block.get("type")))) {
                    continue;
                }
                String toolName = String.valueOf(block.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, Object> input = (Map<String, Object>) block.getOrDefault("input", Map.of());
                // 把模型声明的工具调用映射到本地 Java 实现。
                // 你可以把这段 switch 理解成“模型动作意图 -> Java 真实执行逻辑”的翻译层。
                String output = switch (toolName) {
                    case "bash" -> commandTools.runBash(String.valueOf(input.get("command")));
                    case "read_file" -> commandTools.runRead(String.valueOf(input.get("path")), numberOrNull(input.get("limit")));
                    case "write_file" -> commandTools.runWrite(String.valueOf(input.get("path")), String.valueOf(input.get("content")));
                    case "edit_file" -> commandTools.runEdit(String.valueOf(input.get("path")), String.valueOf(input.get("old_text")), String.valueOf(input.get("new_text")));
                    case "todo", "TodoWrite" -> {
                        usedTodo = true;
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) input.getOrDefault("items", List.of());
                        yield todoManager.update(items);
                    }
                    case "task" -> runSubagent(String.valueOf(input.get("prompt")), config.subagentWritable());
                    case "load_skill" -> skillLoader.getContent(String.valueOf(input.get("name")));
                    case "compact" -> {
                        // 手动压缩不会马上丢历史，而是在本轮工具结果写回后再执行真正压缩。
                        manualCompact = true;
                        yield "Compressing...";
                    }
                    case "background_run" -> backgroundManager.run(String.valueOf(input.get("command")), numberOrDefault(input.get("timeout"), 120));
                    case "check_background" -> backgroundManager.check(String.valueOf(input.getOrDefault("task_id", "")));
                    case "task_create" -> taskManager.create(String.valueOf(input.get("subject")), String.valueOf(input.getOrDefault("description", "")));
                    case "task_get" -> taskManager.get(numberOrDefault(input.get("task_id"), 0));
                    case "task_update" -> {
                        // 兼容蛇形和驼峰两种字段名，降低不同模型输出风格带来的失败概率。
                        @SuppressWarnings("unchecked")
                        List<Integer> addBlockedBy = (List<Integer>) input.getOrDefault("add_blocked_by", input.getOrDefault("addBlockedBy", null));
                        @SuppressWarnings("unchecked")
                        List<Integer> addBlocks = (List<Integer>) input.getOrDefault("add_blocks", input.getOrDefault("addBlocks", null));
                        yield taskManager.update(numberOrDefault(input.get("task_id"), 0), stringOrNull(input.get("status")), addBlockedBy, addBlocks);
                    }
                    case "task_list" -> taskManager.listAll();
                    case "spawn_teammate" -> teammateManager.spawn(String.valueOf(input.get("name")), String.valueOf(input.get("role")), String.valueOf(input.get("prompt")), config.autonomousTeammates());
                    case "list_teammates" -> teammateManager.listAll();
                    case "send_message" -> messageBus.send("lead", String.valueOf(input.get("to")), String.valueOf(input.get("content")), String.valueOf(input.getOrDefault("msg_type", "message")), Map.of());
                    case "read_inbox" -> JsonUtils.toPrettyJson(messageBus.readInbox("lead"));
                    case "broadcast" -> messageBus.broadcast("lead", String.valueOf(input.get("content")), teammateManager.memberNames());
                    case "shutdown_request" -> teammateManager.handleShutdownRequest(String.valueOf(input.get("teammate")));
                    case "plan_approval" -> teammateManager.handlePlanReview(String.valueOf(input.get("request_id")), Boolean.parseBoolean(String.valueOf(input.get("approve"))), String.valueOf(input.getOrDefault("feedback", "")));
                    case "claim_task" -> taskManager.claim(numberOrDefault(input.get("task_id"), 0), "lead");
                    case "idle" -> "Lead does not idle.";
                    case "worktree_create" -> worktreeManager.create(String.valueOf(input.get("name")), numberOrDefault(input.get("task_id"), 0));
                    case "worktree_list" -> worktreeManager.list();
                    case "worktree_remove" -> worktreeManager.remove(String.valueOf(input.get("name")), Boolean.parseBoolean(String.valueOf(input.getOrDefault("keep", false))));
                    case "worktree_events" -> worktreeManager.recentEvents(numberOrDefault(input.get("limit"), 20));
                    default -> "Unknown tool: " + toolName;
                };
                System.out.println("> " + toolName + ": " + output.substring(0, Math.min(200, output.length())));
                results.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", String.valueOf(block.get("id")),
                        "content", output
                ));
            }
            // 所有工具执行完后，会把结果重新作为 user 侧消息喂回模型。
            // 这样模型下一轮就能基于“刚刚执行后的真实世界状态”继续推理。
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (config.enableTodoNag() && roundsWithoutTodo >= 3) {
                // s03/s_full 中如果多轮没有更新 todo，会主动给模型追加提醒。
                Map<String, Object> reminder = new LinkedHashMap<>();
                reminder.put("type", "text");
                reminder.put("text", "<reminder>Update your todos.</reminder>");
                results.add(0, reminder);
            }
            messages.add(new ChatMessage("user", results));
            if (manualCompact) {
                System.out.println("[manual compact]");
                // 手动压缩后直接用“摘要 + 已确认”两条消息替代旧上下文。
                List<ChatMessage> compacted = compressionService.autoCompact(messages);
                messages.clear();
                messages.addAll(compacted);
            }
        }
    }

    /**
     * 使用独立上下文执行一个子代理任务。
     *
     * @param prompt 子任务提示
     * @param writable 是否允许写文件
     * @return 子代理最终摘要
     */
    private String runSubagent(String prompt, boolean writable) {
        // subagent 的本质是“新开一个更小的 Agent 上下文”，而不是起一个全新进程。
        // 这样做的目的，是把某个子问题与主问题隔离开，避免主上下文被探索细节污染。
        List<Map<String, Object>> subTools = new ArrayList<>(StageConfig.baseTools());
        if (!writable) {
            // 某些阶段只允许子代理做只读探索，避免在不受控上下文里直接改文件。
            subTools.removeIf(tool -> List.of("write_file", "edit_file").contains(tool.get("name")));
        }
        List<ChatMessage> subMessages = new ArrayList<>();
        subMessages.add(new ChatMessage("user", prompt));
        for (int i = 0; i < 30; i++) {
            // 子代理和主代理共享同一个模型客户端，但拥有完全独立的消息上下文。
            var response = client.createMessage("You are a coding subagent at " + paths.workdir() + ". Complete the task, then summarize.", subMessages, subTools, 8000);
            subMessages.add(new ChatMessage("assistant", response.content()));
            if (!"tool_use".equals(response.stop_reason())) {
                return response.content().stream()
                        .filter(block -> block.containsKey("text"))
                        .map(block -> String.valueOf(block.get("text")))
                        .reduce("", String::concat);
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> block : response.content()) {
                if (!"tool_use".equals(String.valueOf(block.get("type")))) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> input = (Map<String, Object>) block.getOrDefault("input", Map.of());
                String toolName = String.valueOf(block.get("name"));
                String output = switch (toolName) {
                    case "bash" -> commandTools.runBash(String.valueOf(input.get("command")));
                    case "read_file" -> commandTools.runRead(String.valueOf(input.get("path")), null);
                    case "write_file" -> commandTools.runWrite(String.valueOf(input.get("path")), String.valueOf(input.get("content")));
                    case "edit_file" -> commandTools.runEdit(String.valueOf(input.get("path")), String.valueOf(input.get("old_text")), String.valueOf(input.get("new_text")));
                    default -> "Unknown tool: " + toolName;
                };
                results.add(Map.of("type", "tool_result", "tool_use_id", String.valueOf(block.get("id")), "content", output));
            }
            subMessages.add(new ChatMessage("user", results));
        }
        return "(subagent failed)";
    }

    /**
     * 将模型输入值尽量解析为整数。
     *
     * @param value 原始值
     * @return 解析结果，无法解析或为空时返回 null
     */
    private Integer numberOrNull(Object value) {
        // 模型返回的 JSON 字段有时是数字，有时是字符串；这里统一做容错解析。
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * 将模型输入值解析为整数，失败时回退到默认值。
     *
     * @param value 原始值
     * @param defaultValue 默认值
     * @return 解析后的整数
     */
    private int numberOrDefault(Object value, int defaultValue) {
        Integer parsed = numberOrNull(value);
        return parsed == null ? defaultValue : parsed;
    }

    /**
     * 将对象转换为字符串，空值返回 null。
     *
     * @param value 原始值
     * @return 字符串结果或 null
     */
    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
