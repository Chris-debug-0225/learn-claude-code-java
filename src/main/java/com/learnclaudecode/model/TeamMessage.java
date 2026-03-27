package com.learnclaudecode.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 队友消息模型。
 */
public class TeamMessage {
    /**
     * 消息类型，例如 message、broadcast、shutdown_request 等。
     */
    public String type;

    /**
     * 消息发送者标识，通常表示发送该消息的队友名称或 Agent 名称。
     */
    public String from;

    /**
     * 消息正文内容。
     */
    public String content;

    /**
     * 消息时间戳，通常使用 Unix 时间戳表示消息创建时间。
     */
    public double timestamp;

    /**
     * 扩展字段，用于附加存放不同消息类型需要的额外数据。
     */
    public Map<String, Object> extra = new HashMap<>();
}
