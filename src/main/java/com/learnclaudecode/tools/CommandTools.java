package com.learnclaudecode.tools;

import com.learnclaudecode.common.WorkspacePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基础文件与命令工具
 */
public class CommandTools {
    private static final List<String> DANGEROUS = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private final WorkspacePaths paths;

    /**
     * 初始化命令与文件工具。
     *
     * @param paths 工作区路径工具
     */
    public CommandTools(WorkspacePaths paths) {
        this.paths = paths;
    }

    /**
     * 在工作区目录内执行 shell 命令。
     *
     * @param command 待执行命令
     * @return 命令输出或错误信息
     */
    public String runBash(String command) {
        // 这里只做最小黑名单拦截，目的是对齐教学项目的“轻量保护”而不是实现完整沙箱。
        for (String danger : DANGEROUS) {
            if (command.contains(danger)) {
                return "Error: Dangerous command blocked";
            }
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(paths.workdir().toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String out = (stdout + stderr).trim();
            if (out.isBlank()) {
                return "(no output)";
            }
            return out.substring(0, Math.min(50000, out.length()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 根据当前操作系统构造底层 shell 启动命令。
     *
     * @param command 原始命令文本
     * @return ProcessBuilder 可用的命令数组
     */
    public List<String> shellCommand(String command) {
        if (IS_WINDOWS) {
            return List.of("powershell", "-Command", command);
        }
        return List.of("bash", "-lc", command);
    }

    /**
     * 读取工作区内指定文件的内容。
     *
     * @param relativePath 相对路径
     * @param limit        最大读取行数
     * @return 文件内容或错误信息
     */
    public String runRead(String relativePath, Integer limit) {
        try {
            // 读取文件时支持 limit，避免一次性把超大文件全部塞回模型上下文。
            List<String> allLines = Files.readAllLines(paths.safeResolve(relativePath), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(allLines);
            if (limit != null && limit > 0 && limit < allLines.size()) {
                lines = new ArrayList<>(allLines.subList(0, limit));
                lines.add("... (" + (allLines.size() - limit) + " more lines)");
            }
            String text = String.join("\n", lines);
            return text.substring(0, Math.min(50000, text.length()));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 向工作区内指定文件写入内容。
     *
     * @param relativePath 相对路径
     * @param content      写入内容
     * @return 写入结果
     */
    public String runWrite(String relativePath, String content) {
        try {
            Path path = paths.safeResolve(relativePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return "Wrote " + content.length() + " bytes to " + relativePath;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 对工作区内已有文件做精确文本替换。
     *
     * @param relativePath 相对路径
     * @param oldText      原文本
     * @param newText      新文本
     * @return 编辑结果
     */
    public String runEdit(String relativePath, String oldText, String newText) {
        try {
            Path path = paths.safeResolve(relativePath);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + relativePath;
            }
            // edit_file 采用精确文本替换
            Files.writeString(path, content.replaceFirst(java.util.regex.Pattern.quote(oldText), java.util.regex.Matcher.quoteReplacement(newText)), StandardCharsets.UTF_8);
            return "Edited " + relativePath;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
