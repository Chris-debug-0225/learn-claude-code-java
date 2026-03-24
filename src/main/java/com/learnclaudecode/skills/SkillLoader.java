package com.learnclaudecode.skills;

import com.learnclaudecode.common.WorkspacePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能加载器，扫描 skills 目录下的 SKILL.md 文件并解析简单 frontmatter。
 */
public class SkillLoader {
    private final Map<String, Map<String, Object>> skills = new HashMap<>();

    /**
     * 扫描并加载工作区内的技能文件。
     *
     * @param paths 工作区路径工具
     */
    public SkillLoader(WorkspacePaths paths) {
        Path skillsDir = paths.skillsDir();
        if (!Files.exists(skillsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(skillsDir)) {
            // 约定每个技能目录用 SKILL.md 作为入口文件。
            stream.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(this::loadSkillFile);
        } catch (IOException ignored) {
        }
    }

    /**
     * 读取并解析单个技能文件。
     *
     * @param path SKILL.md 文件路径
     */
    private void loadSkillFile(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> meta = new HashMap<>();
            String body = text;
            if (text.startsWith("---\n")) {
                // 这里只解析最简单的 frontmatter，足够支持 name/description 等教学场景字段。
                int second = text.indexOf("\n---\n", 4);
                if (second > 0) {
                    String header = text.substring(4, second);
                    body = text.substring(second + 5).trim();
                    for (String line : header.split("\n")) {
                        int idx = line.indexOf(':');
                        if (idx > 0) {
                            meta.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                        }
                    }
                }
            }
            String name = meta.getOrDefault("name", path.getParent().getFileName().toString());
            skills.put(name, Map.of(
                    "meta", meta,
                    "body", body,
                    "path", path.toString()
            ));
        } catch (IOException ignored) {
        }
    }

    /**
     * 返回所有已加载技能的简要说明。
     *
     * @return 技能说明文本
     */
    public String getDescriptions() {
        if (skills.isEmpty()) {
            return "(no skills available)";
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : skills.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, String> meta = (Map<String, String>) entry.getValue().get("meta");
            lines.add("  - " + entry.getKey() + ": " + meta.getOrDefault("description", "No description"));
        }
        return String.join("\n", lines);
    }

    /**
     * 返回指定技能的完整内容。
     *
     * @param name 技能名
     * @return 技能内容或错误信息
     */
    public String getContent(String name) {
        Map<String, Object> skill = skills.get(name);
        if (skill == null) {
            return "Error: Unknown skill '" + name + "'. Available: " + String.join(", ", skills.keySet());
        }
        return "<skill name=\"" + name + "\">\n" + skill.get("body") + "\n</skill>";
    }
}
