package com.learnclaudecode.common;

import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 环境配置读取器
 */
public final class EnvConfig {
    private final Dotenv dotenv;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private final Path workdir;

    /**
     * 读取环境变量并初始化模型访问所需配置。
     */
    public EnvConfig() {
        // 优先允许从 .env 读取，同时兼容系统环境变量覆盖。
        this.dotenv = Dotenv.configure().ignoreIfMissing().load();
        this.modelId = require("MODEL_ID");
        this.apiKey = getenv("ANTHROPIC_API_KEY").orElse("");
        this.baseUrl = getenv("ANTHROPIC_BASE_URL").orElse("https://api.anthropic.com");
        this.workdir = Paths.get("").toAbsolutePath().normalize();
    }

    /**
     * 读取必填环境变量。
     *
     * @param key 环境变量名
     * @return 环境变量值
     */
    private String require(String key) {
        return getenv(key).orElseThrow(() -> new IllegalStateException("缺少环境变量: " + key));
    }

    /**
     * 读取环境变量，优先使用系统环境变量，其次读取 .env。
     *
     * @param key 环境变量名
     * @return 环境变量值
     */
    public Optional<String> getenv(String key) {
        // 系统环境变量优先级高于 .env，便于在 CI 或 IDEA 运行配置里覆盖本地值。
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return Optional.of(envValue);
        }
        String dotValue = dotenv.get(key);
        if (dotValue != null && !dotValue.isBlank()) {
            return Optional.of(dotValue);
        }
        return Optional.empty();
    }

    /**
     * 返回当前使用的模型 ID。
     *
     * @return 模型 ID
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * 返回当前使用的 API Key。
     *
     * @return API Key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 返回 Anthropic-compatible 服务的基础地址。
     *
     * @return 基础 URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回当前工作目录。
     *
     * @return 工作目录路径
     */
    public Path getWorkdir() {
        return workdir;
    }
}
