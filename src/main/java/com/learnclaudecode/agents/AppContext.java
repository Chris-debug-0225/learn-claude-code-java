package com.learnclaudecode.agents;

import com.learnclaudecode.background.BackgroundManager;
import com.learnclaudecode.common.AnthropicClient;
import com.learnclaudecode.common.EnvConfig;
import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.context.CompressionService;
import com.learnclaudecode.skills.SkillLoader;
import com.learnclaudecode.tasks.TaskManager;
import com.learnclaudecode.tasks.WorktreeManager;
import com.learnclaudecode.team.MessageBus;
import com.learnclaudecode.team.TeammateManager;
import com.learnclaudecode.tools.CommandTools;
import com.learnclaudecode.tools.TodoManager;

/**
 * 应用装配器，集中创建共享服务。
 *
 * 这个类可以把整个项目理解成一个“总装车间”：
 * 1. 先读取环境配置，知道模型、API 地址、工作目录是什么；
 * 2. 再创建最基础的能力，例如命令执行、文件读写、路径管理；
 * 3. 然后在这些基础能力之上，继续创建更高层的 Agent 机制，
 *    比如 Todo、上下文压缩、任务系统、后台任务、团队通信、worktree；
 * 4. 最后把它们全部交给 AgentRuntime，由它统一驱动整个 Agent 循环。
 *
 * 对初学者来说，理解这个类很重要，因为它回答了一个核心问题：
 * “一个 Agent 项目到底由哪些运行时部件组成，它们是怎么被连接起来的？”
 */
public final class AppContext {
    private final AgentRuntime runtime;

    /**
     * 创建完整的应用上下文并装配所有共享服务。
     */
    public AppContext() {
        // 先装配基础环境与工作区信息，后续所有服务都会依赖这两项。
        EnvConfig env = new EnvConfig();
        WorkspacePaths paths = new WorkspacePaths(env.getWorkdir());

        // 下面按“基础能力 -> 扩展机制 -> 总运行时”的顺序创建共享服务。
        // 这样设计有两个好处：
        // 1. 每个阶段入口类都不需要重复写装配代码；
        // 2. 学习时可以很清楚地看到 Claude Code 风格 Agent 的分层结构。
        AnthropicClient client = new AnthropicClient(env);
        CommandTools commandTools = new CommandTools(paths);
        TodoManager todoManager = new TodoManager();
        SkillLoader skillLoader = new SkillLoader(paths);
        CompressionService compressionService = new CompressionService(paths, client, 10000, 3);
        TaskManager taskManager = new TaskManager(paths);
        BackgroundManager backgroundManager = new BackgroundManager(paths);
        MessageBus messageBus = new MessageBus(paths);
        TeammateManager teammateManager = new TeammateManager(paths, client, commandTools, messageBus, taskManager);
        WorktreeManager worktreeManager = new WorktreeManager(paths, taskManager);

        // AgentRuntime 是最终的统一执行器。
        // 真正的“用户输入 -> 调模型 -> 模型发起工具调用 -> 本地执行工具 -> 再回给模型”
        // 这条主链路，全部都发生在 AgentRuntime 中。
        this.runtime = new AgentRuntime(client, paths, commandTools, todoManager, skillLoader, compressionService, taskManager, backgroundManager, messageBus, teammateManager, worktreeManager);
    }

    /**
     * 返回已经装配完成的统一 Agent 运行时。
     *
     * @return 共享的 AgentRuntime 实例
     */
    public AgentRuntime runtime() {
        return runtime;
    }
}
