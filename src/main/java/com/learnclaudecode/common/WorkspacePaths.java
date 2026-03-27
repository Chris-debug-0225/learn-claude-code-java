package com.learnclaudecode.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区路径工具，负责安全路径校验和常用目录访问。
 */
public final class WorkspacePaths {
    private final Path workdir;

    /**
     * 使用指定工作目录初始化路径工具。
     * 这里的 workdir 表示当前工作区的根目录，例如一个项目仓库的根路径。
     * 构造函数会将它转换为绝对路径并做规范化，便于后续统一拼接子目录。
     *
     * @param workdir 工作目录
     */
    public WorkspacePaths(Path workdir) {
        this.workdir = workdir.toAbsolutePath().normalize();
    }

    /**
     * 返回规范化后的工作目录。
     * workdir 是整个工作区的根目录，下面的 .tasks、.team、skills 等目录
     * 都是在这个目录之下通过 Path.resolve(...) 拼接出来的。
     *
     * @return 工作目录路径
     */
    public Path workdir() {
        return workdir;
    }

    /**
     * 在工作区内安全解析相对路径。
     *
     * @param relativePath 相对路径
     * @return 解析后的绝对路径
     */
    public Path safeResolve(String relativePath) {
        // 所有文件工具都必须走这里，防止模型通过 ../ 逃逸出当前工作区。
        Path resolved = workdir.resolve(relativePath).normalize();
        if (!resolved.startsWith(workdir)) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    /**
     * 读取工作区内指定文件的文本内容。
     *
     * @param relativePath 相对路径
     * @return 文件文本
     * @throws IOException 读取失败时抛出
     */
    public String readText(String relativePath) throws IOException {
        return Files.readString(safeResolve(relativePath), StandardCharsets.UTF_8);
    }

    /**
     * 向工作区内指定文件写入文本内容。
     *
     * @param relativePath 相对路径
     * @param content 写入内容
     * @throws IOException 写入失败时抛出
     */
    public void writeText(String relativePath, String content) throws IOException {
        Path path = safeResolve(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * 返回任务目录路径。
     * 这里的 resolve(".tasks") 表示在 workdir 下拼接出一个名为 .tasks 的子目录路径。
     * 例如当 workdir 是 /repo 时，返回值就是 /repo/.tasks。
     * 返回的是一个 Path 对象，表示单个目录位置，不是多个文件，也不会自动创建目录。
     * 该目录用于存放任务系统相关数据，例如任务文件。
     *
     * @return .tasks 目录路径
     */
    public Path tasksDir() {
        return workdir.resolve(".tasks");
    }

    /**
     * 返回团队协作目录路径。
     * resolve(".team") 表示在工作区根目录下定位 .team 目录。
     * 例如当 workdir 是 /repo 时，返回值就是 /repo/.team。
     * 返回的是单个目录的 Path，对应团队协作相关的数据根目录，
     * 例如队友配置、消息收发目录等内容都会放在这里。
     *
     * @return .team 目录路径
     */
    public Path teamDir() {
        return workdir.resolve(".team");
    }

    /**
     * 返回队友 inbox 目录路径。
     * 这里先通过 teamDir() 拿到 .team 目录，再继续 resolve("inbox")，
     * 因此最终路径通常是 <workdir>/.team/inbox。
     * 返回的是单个目录的 Path，用于存放队友之间传递的消息文件。
     *
     * @return inbox 目录路径
     */
    public Path inboxDir() {
        return teamDir().resolve("inbox");
    }

    /**
     * 返回技能目录路径。
     * 这里返回 workdir 下的 skills 目录，例如 /repo/skills。
     * 返回的是目录路径本身，不代表某个具体技能文件。
     * 该目录用于扫描和加载技能定义，例如各技能目录中的 SKILL.md 文件。
     *
     * @return skills 目录路径
     */
    public Path skillsDir() {
        return workdir.resolve("skills");
    }

    /**
     * 返回会话转录目录路径。
     * 这里返回 workdir 下的 .transcripts 目录，例如 /repo/.transcripts。
     * 返回的是单个目录的 Path，用于存放会话转录、交互记录等文件。
     *
     * @return .transcripts 目录路径
     */
    public Path transcriptDir() {
        return workdir.resolve(".transcripts");
    }

    /**
     * 返回 worktree 隔离目录路径。
     * 这里返回 workdir 下的 .worktrees 目录，例如 /repo/.worktrees。
     * 返回的是单个目录的 Path，用于存放 worktree 相关的索引、事件记录
     * 以及其它隔离工作树管理数据。
     *
     * @return .worktrees 目录路径
     */
    public Path worktreesDir() {
        return workdir.resolve(".worktrees");
    }
}
