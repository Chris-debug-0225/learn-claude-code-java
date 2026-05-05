package com.learnclaudecode.context;

import com.learnclaudecode.common.AnthropicClient;
import com.learnclaudecode.common.JsonUtils;
import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.model.ChatMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩服务，对齐 s06 的三层压缩思想。
 *
 * 这个类要解决的是 Agent 项目里一个非常现实的问题：
 * 对话越长，发给模型的历史就越多，最终一定会碰到上下文窗口上限。
 *
 * Claude Code 风格的解决思路不是“简单删掉旧消息”，而是分层处理：
 * 1. 先做 micro compact：清理旧工具结果中的大文本；
 * 2. 如果还不够，再做 auto compact：把完整对话转存，再生成摘要；
 * 3. 用“摘要 + 确认消息”替换旧历史，让 Agent 能继续干活。
 */
public class CompressionService {
    private final WorkspacePaths paths;
    private final AnthropicClient client;
    private final int threshold;
    private final int keepRecent;

    /**
     * 初始化上下文压缩服务。
     *
     * @param paths 工作区路径工具
     * @param client 模型客户端
     * @param threshold 触发自动压缩的阈值
     * @param keepRecent micro compact 时保留的最近结果数
     */
    public CompressionService(WorkspacePaths paths, AnthropicClient client, int threshold, int keepRecent) {
        this.paths = paths;
        this.client = client;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    /**
     * 粗略估算当前消息历史的 token 数。
     *
     * @param messages 消息历史
     * @return 估算 token 数
     */
    public int estimateTokens(List<ChatMessage> messages) {
        // 这里没有做精确 tokenizer，而是用 JSON 长度 / 4 做近似估算。
        // 对教学项目来说，这种轻量估算已经足够判断“是否快超窗”。
        return JsonUtils.toJson(messages).length() / 4;
    }

    /**
     * 对较老的工具结果做轻量清理。
     * 实现思路不是删除整条消息，而是只把“较早的 tool_result 内容”替换为简短占位符：[cleared]。
     * 这样既能减少上下文体积，又能保留这次工具调用“曾经发生过”的结构信息。
     *
     * @param messages 消息历史
     */
    public void microCompact(List<ChatMessage> messages) {
        // 统计工具结果总数，判断是否需要压缩
        int toolResultCount = 0;
        for (ChatMessage message : messages) {
            // 仅处理 user 角色的结构化消息（包含 tool_result 列表）
            if (!"user".equals(message.role()) || !(message.content() instanceof List<?> parts)) {
                continue;
            }
            for (Object part : parts) {
                if (part instanceof Map<?, ?> raw && "tool_result".equals(raw.get("type"))) {
                    toolResultCount++;
                }
            }
        }

        // 工具结果数量未超过阈值，无需压缩
        if (toolResultCount <= keepRecent) {
            return;
        }

        // 遍历消息列表，将较旧的user结果内容替换为 [cleared]
        int compactBefore = toolResultCount - keepRecent;
        int seenToolResults = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!"user".equals(message.role()) || !(message.content() instanceof List<?> parts)) {
                continue;
            }

            boolean changed = false;
            List<Object> rewrittenParts = new ArrayList<>(parts.size());
            for (Object part : parts) {
                Object rewrittenPart = part;
                if (part instanceof Map<?, ?> raw && "tool_result".equals(raw.get("type"))) {
                    seenToolResults++;
                    Object content = raw.get("content");

                    // 仅处理较旧且内容较长的user结果
                    if (seenToolResults <= compactBefore && content instanceof String text && text.length() > 100) {
                        Map<String, Object> rewrittenMap = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : raw.entrySet()) {
                            rewrittenMap.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        rewrittenMap.put("content", "[cleared]");
                        rewrittenPart = rewrittenMap;
                        changed = true;
                    }
                }
                rewrittenParts.add(rewrittenPart);
            }

            // 如果有修改，更新消息列表中的对应位置
            if (changed) {
                messages.set(i, new ChatMessage(message.role(), rewrittenParts));
            }
        }
    }

    /**
     * 将完整历史转存并生成摘要上下文。
     * 这个方法对应“真正的自动压缩”阶段：
     * 1. 先把当前完整对话历史原样写入 transcript 文件，保证细节可追溯；
     * 2. 再把整段历史整理成一个摘要请求发给模型；
     * 3. 最后用“摘要消息 + assistant 确认消息”替换原来的长历史。
     *
     * @param messages 原始消息历史
     * @return 压缩后的消息历史
     */
    public List<ChatMessage> autoCompact(List<ChatMessage> messages) {
        try {
            // 先确保 .transcripts 目录存在，后续会把完整历史落盘到这里。
            Files.createDirectories(paths.transcriptDir());
            // 真实长对话先落盘为 transcript，再把摘要注回上下文，便于后续追溯。
            Path transcript = paths.transcriptDir().resolve("transcript_" + Instant.now().getEpochSecond() + ".jsonl");
            List<String> lines = new ArrayList<>();
            for (ChatMessage message : messages) {
                // 每条消息单独序列化成一行 JSON，形成 jsonl 格式，方便后续查看和处理。
                lines.add(JsonUtils.toJson(message));
            }
            // 到这里为止，完整历史已经被安全保存；后面即使上下文被压缩，也还能回溯原文。
            Files.write(transcript, lines, StandardCharsets.UTF_8);

            // 再把整段消息历史整体转成 JSON，作为“请模型总结上下文”的原始材料。
            String conversation = JsonUtils.toJson(messages);

            // 提示词要求模型总结：已完成事项、当前状态、关键决策。
            // 同时限制输入长度，避免摘要请求本身过大。
            String prompt = "Summarize this conversation for continuity. Include what was accomplished, current state, and key decisions.\n\n"
                    + conversation.substring(0, Math.min(80000, conversation.length()));
            // 摘要本身也交给模型生成，这样能尽量保留任务状态而不是机械截断历史。
            String summary = client.createMessage(null, List.of(Map.of("role", "user", "content", prompt)), List.of(), 2000)
                    .content()
                    .stream()
                    // 这里从模型返回的内容块中提取第一段 text 文本作为摘要正文。
                    .filter(block -> block.containsKey("text"))
                    .map(block -> String.valueOf(block.get("text")))
                    .findFirst()
                    .orElse("(summary unavailable)");

            // 构造压缩后的新上下文：
            // 第一条 user 消息包含 transcript 路径和摘要，提醒后续 Agent 如需细节可回看原记录；
            // 第二条 assistant 消息相当于确认“我已经接收并理解这个摘要上下文”。
            List<ChatMessage> compacted = new ArrayList<>();
            compacted.add(new ChatMessage("user", "[Conversation compressed. Transcript: " + transcript + "]\n\n" + summary));
            compacted.add(new ChatMessage("assistant", "Understood. I have the context from the summary. Continuing."));
            return compacted;
        } catch (IOException e) {
            // 这里主要兜底文件落盘失败的情况；如果 transcript 都写不下来，就直接抛出异常。
            throw new IllegalStateException("压缩会话失败", e);
        }
    }

    /**
     * 判断当前消息历史是否需要自动压缩。
     *
     * @param messages 消息历史
     * @return 超过阈值时返回 true
     */
    public boolean needsAutoCompact(List<ChatMessage> messages) {
        return estimateTokens(messages) > threshold;
    }
}
