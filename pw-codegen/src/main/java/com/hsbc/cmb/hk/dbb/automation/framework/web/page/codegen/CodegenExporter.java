package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把面板里「只能看」的三栏产物落成可直接拷进工程的 {@code .java} 文件。
 *
 * <p>面板是只读 Monaco 编辑器且会话结束后句柄释放，产物没有出口（评审 P0-6）。这里按
 * {@link MultiPageCodegen.CodeBlock} 的标签（类名）逐块写文件：一个块 = 一个 Java 文件，
 * 不使用拼接的 {@code pageCode}（那里面可能有多个 public class，单文件放不下）。
 *
 * <p>文件名一律净化为 {@code [A-Za-z0-9_]}，杜绝由页面 URL / 类名拼出路径穿越；重名追加序号，
 * 绝不覆盖同一轮里已写出的文件。写盘使用 UTF-8 无 BOM，与本仓 Java 源码一致。
 */
final class CodegenExporter {

    private CodegenExporter() {
    }

    /**
     * 导出最近一次生成结果。
     *
     * @param result 生成结果（{@code null} 表示尚未生成）
     * @param dir    目标目录；不存在时自动创建（含父目录）
     * @return 实际写入的目录
     * @throws IllegalStateException 尚无产物
     * @throws IOException           目录创建 / 写盘失败
     */
    static Path export(MultiPageCodegen.MultiPageResult result, Path dir) throws IOException {
        if (result == null) throw new IllegalStateException("no generated code yet - start a session first");
        if (dir == null) throw new IllegalArgumentException("export dir must not be null");
        Files.createDirectories(dir);

        List<MultiPageCodegen.CodeBlock> blocks = new ArrayList<>();
        append(blocks, result.pageBlocks);
        append(blocks, result.stepBlocks);
        append(blocks, result.assertBlocks);

        Set<String> used = new HashSet<>();
        int written = 0;
        for (MultiPageCodegen.CodeBlock b : blocks) {
            if (b == null || b.code == null || b.code.isBlank()) continue;
            String name = uniqueName(sanitize(b.label), used);
            Path pkgDir = packageDir(dir, b.code);
            Files.createDirectories(pkgDir);
            Files.writeString(pkgDir.resolve(name + ".java"), b.code, StandardCharsets.UTF_8);
            written++;
        }
        if (written == 0) throw new IllegalStateException("generated code is empty - nothing to export");
        return dir;
    }

    /**
     * 从源码首部的 {@code package} 声明推出子目录（{@code a.b.c → dir/a/b/c}），
     * 使产物拷进 {@code src/main/java} 后路径与包名一致，无需手工归位（评审 N-06）。
     *
     * <p>只接受合法 Java 标识符段：{@code package ../../evil;} 这类声明一律视为「无包」，
     * 产物仍写在 {@code dir} 根部——导出内容里出现的字符串绝不参与路径拼接。
     */
    static Path packageDir(Path dir, String code) {
        String pkg = packageOf(code);
        if (pkg.isEmpty()) return dir;
        Path p = dir;
        for (String seg : pkg.split("\\.")) p = p.resolve(seg);
        return p;
    }

    /** 源码首部的 package 声明；没有声明或声明非法时返回空串。 */
    static String packageOf(String code) {
        if (code == null) return "";
        for (String line : code.split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("package ")) continue;
            if (!t.endsWith(";")) return "";
            String pkg = t.substring("package ".length(), t.length() - 1).trim();
            // 严格白名单：段必须是 Java 标识符，杜绝 ".." / 绝对路径 / 分隔符注入。
            return pkg.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                    ? pkg : "";
        }
        return "";
    }

    private static void append(List<MultiPageCodegen.CodeBlock> out,
                               List<MultiPageCodegen.CodeBlock> blocks) {
        if (blocks != null) out.addAll(blocks);
    }

    /** 类名可能含中文 / 空格 / 路径分隔符，一律落成安全的 ASCII 文件名。 */
    static String sanitize(String label) {
        if (label == null || label.isBlank()) return "GeneratedCode";
        String cleaned = label.replaceAll("[^A-Za-z0-9_]", "_");
        // 类名不能以数字开头（Java 文件名通常与类名一致）
        if (cleaned.isEmpty()) return "GeneratedCode";
        if (Character.isDigit(cleaned.charAt(0))) cleaned = "Code_" + cleaned;
        return cleaned.toLowerCase(Locale.ROOT).endsWith(".java")
                ? cleaned.substring(0, cleaned.length() - 5) : cleaned;
    }

    private static String uniqueName(String base, Set<String> used) {
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) candidate = base + "_" + n++;
        return candidate;
    }
}
